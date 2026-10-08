package com.example.bosondiag

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Y16 -> palette-colored ARGB using the two-parameter AGC from
 * Hung, Tinh, Minh, "Real-Time Implementation of a Novel Automatic Gain Control Algorithm for
 * Infrared Image Processing Based on MPSoC", Automatic Control and Computer Sciences 56(6), 2022.
 *
 * Per frame (paper section 2):
 *  1. Normalize to 0..255 with the max-min method (eq. 1).
 *  2. Histogram of the output image -> Low_Bins (output < N1) and High_Bins (output >= N2) (eq. 3).
 *  3. GAIN update (eq. 2): High_Bins > H_Threshold -> GAIN - delta;
 *     else Low_Bins > L_Threshold -> GAIN + delta; else unchanged.
 *  4. Image_G = Norm * GAIN; M = mean(Image_G); OFFSET = SETPOINT - M; Out = Image_G + OFFSET (eq. 4-7).
 *  5. Repeat 2-4 until |GAIN(n) - GAIN(n-1)| < delta (the flowchart's exit condition).
 *
 * Differences from the paper, all needed for live video rather than a single still:
 *  - The loop runs to convergence on every frame, starting from the previous frame's gain.
 *  - The GAIN "increase" step is only taken if the increased gain would not itself trip the
 *    High_Bins test (with a margin). Without this the +delta / -delta rules can chatter forever.
 *  - The gain actually applied follows the converged gain through a short low-pass filter, so the
 *    0.1 steps do not show as visible pops.
 *  - The min/max range is the app's usual smoothed 0.05%..99.95% range (a stray hot pixel would
 *    otherwise set the whole scale), and honours the "min range" setting.
 *
 * Optional "protect darks" variant (not in the paper): with OFFSET re-centring the mean, Low_Bins and
 * High_Bins both grow with GAIN, so the paper's "Low_Bins > L_Threshold -> GAIN + delta" rule keeps
 * raising gain on scenes with a large cold object until that object is crushed to black. In this
 * variant dark clipping lowers GAIN exactly like bright clipping does, and GAIN may recover (up to 1)
 * when neither side clips.
 *
 * Because mean(Norm*GAIN) = GAIN*mean(Norm), the output histogram for any candidate GAIN can be
 * evaluated in closed form from one cumulative histogram of the input, so no extra per-pixel
 * passes are needed inside the loop.
 *
 * One instance per consumer thread (it holds state).
 */
class AgcMapper(private val w: Int, private val h: Int) {
    private val total = w * h
    private val vals = IntArray(total)
    private val hist = IntArray(65536)
    private val cum = IntArray(65536)

    private var haveRange = false
    private var haveGain = false
    private var targetGain = 1.0
    private var appliedGain = 1.0

    private var base = 0.0
    private var scale = 1.0

    @Volatile var lo = 0f
    @Volatile var hi = 0f
    @Volatile var rangeBase = 0f
    @Volatile var rangeSpan = 1f

    /** Gain and offset actually applied to the last frame (diagnostics). */
    @Volatile var gain = 1f
    @Volatile var offset = 0f
    /** Fraction of output pixels < N1 and >= N2 in the last frame (diagnostics). */
    @Volatile var lowFrac = 0f
    @Volatile var highFrac = 0f

    fun reset() {
        haveRange = false
        haveGain = false
        targetGain = 1.0
        appliedGain = 1.0
    }

    private fun cumAt(v: Int): Int = if (v < 0) 0 else if (v > 65535) total else cum[v]

    /** Number of pixels whose output (clamp(norm)*g + off, clamped) is >= [level]. */
    internal fun countAtLeast(level: Double, g: Double, off: Double): Int {
        val t = (level - off) / g
        if (t <= 0.0) return total
        if (t > 255.0) return 0
        val vmin = ceil(base + t / scale).toInt()
        return total - cumAt(vmin - 1)
    }

    /** Number of pixels whose output is < [level]. */
    internal fun countBelow(level: Double, g: Double, off: Double): Int {
        val t = (level - off) / g
        if (t <= 0.0) return 0
        if (t > 255.0) return total
        val vmax = ceil(base + t / scale).toInt() - 1
        return cumAt(vmax)
    }

    /**
     * @param setpoint     desired mean output grey level (paper's SETPOINT), 0..255
     * @param hThr         H_Threshold as a fraction of all pixels
     * @param lThr         L_Threshold as a fraction of all pixels
     */
    fun map(
        f: ByteArray, lut: IntArray, fpn: IntArray?, minRange: Float,
        setpoint: Float, hThr: Float, lThr: Float, protectDarks: Boolean, out: IntArray
    ) {
        // ---- pass 1: decode, histogram
        java.util.Arrays.fill(hist, 0)
        var vmin = 65535
        var vmax = 0
        var j = 0
        for (p in 0 until total) {
            var v = (f[j].toInt() and 0xFF) or ((f[j + 1].toInt() and 0xFF) shl 8)
            j += 2
            if (fpn != null) {
                v -= fpn[p]
                if (v < 0) v = 0 else if (v > 65535) v = 65535
            }
            vals[p] = v
            hist[v]++
            if (v < vmin) vmin = v
            if (v > vmax) vmax = v
        }
        var acc = 0
        for (v in vmin..vmax) {
            acc += hist[v]
            cum[v] = acc
        }
        for (v in vmax + 1..65535) cum[v] = total
        // (cum[v] below vmin is 0 from the untouched initial state only if never written; clear it)
        for (v in 0 until vmin) cum[v] = 0

        // ---- step 1: normalization range (eq. 1), robust and smoothed
        val clip = maxOf(1, total / 2000)
        val lowTarget = clip
        val highTarget = total - clip
        var loV = vmin
        var hiV = vmax
        run {
            var gotLo = false
            for (v in vmin..vmax) {
                val c = cum[v]
                if (!gotLo && c >= lowTarget) {
                    loV = v
                    gotLo = true
                }
                if (c >= highTarget) {
                    hiV = v
                    break
                }
            }
        }
        if (!haveRange) {
            lo = loV.toFloat()
            hi = hiV.toFloat()
            haveRange = true
        } else {
            lo += (loV - lo) * 0.1f
            hi += (hiV - hi) * 0.1f
        }
        var b = lo
        var range = hi - lo
        if (range < minRange) {
            b = (lo + hi) / 2f - minRange / 2f
            range = minRange
        }
        rangeBase = b
        rangeSpan = range
        base = b.toDouble()
        scale = 255.0 / range

        // ---- mean of the normalized image (needed for OFFSET; GAIN scales it linearly)
        var sum = 0.0
        for (v in vmin..vmax) {
            val n = hist[v]
            if (n == 0) continue
            var nv = (v - base) * scale
            if (nv < 0.0) nv = 0.0 else if (nv > 255.0) nv = 255.0
            sum += n * nv
        }
        val meanNorm = sum / total

        // ---- steps 2, 3, 5: iterate the GAIN rule to convergence
        val hLimit = hThr.toDouble() * total
        val lLimit = lThr.toDouble() * total
        val sp = setpoint.toDouble()
        var g = if (haveGain) targetGain else 1.0
        var iter = 0
        while (iter < MAX_ITER) {
            iter++
            val off = sp - g * meanNorm
            var gn = g
            if (protectDarks) {
                if (countAtLeast(N2, g, off) > hLimit || countBelow(N1, g, off) > lLimit) {
                    gn = g - DELTA
                } else if (g < 1.0 - 1e-9) {
                    val g2 = g + DELTA
                    val off2 = sp - g2 * meanNorm
                    if (countAtLeast(N2, g2, off2) <= hLimit * HYSTERESIS &&
                        countBelow(N1, g2, off2) <= lLimit * HYSTERESIS
                    ) gn = minOf(g2, 1.0)
                }
            } else if (countAtLeast(N2, g, off) > hLimit) {
                gn = g - DELTA
            } else if (countBelow(N1, g, off) > lLimit) {
                val g2 = g + DELTA
                val off2 = sp - g2 * meanNorm
                if (countAtLeast(N2, g2, off2) <= hLimit * HYSTERESIS) gn = g2
            }
            if (gn < G_MIN) gn = G_MIN else if (gn > G_MAX) gn = G_MAX
            if (abs(gn - g) < DELTA * 0.5) break
            g = gn
        }
        targetGain = g

        // ---- applied gain: low-pass the converged gain for video
        if (!haveGain) {
            appliedGain = g
            haveGain = true
        } else {
            appliedGain += (g - appliedGain) * GAIN_SMOOTH
        }
        val gA = appliedGain
        val off = sp - gA * meanNorm
        gain = gA.toFloat()
        offset = off.toFloat()
        highFrac = countAtLeast(N2, gA, off).toFloat() / total
        lowFrac = countBelow(N1, gA, off).toFloat() / total

        // ---- step 4: apply
        val sc = scale.toFloat()
        val bs = base.toFloat()
        val gf = gA.toFloat()
        val of = off.toFloat()
        for (p in 0 until total) {
            var n = (vals[p] - bs) * sc
            if (n < 0f) n = 0f else if (n > 255f) n = 255f
            var o = n * gf + of
            if (o < 0f) o = 0f else if (o > 255f) o = 255f
            out[p] = lut[o.toInt()]
        }
    }

    companion object {
        /** Paper's rate of change for GAIN. */
        const val DELTA = 0.1
        const val G_MIN = 0.3
        const val G_MAX = 4.0
        /** Low_Bins counts output < N1; High_Bins counts output >= N2. */
        const val N1 = 16.0
        const val N2 = 240.0
        private const val MAX_ITER = 64
        private const val HYSTERESIS = 0.75
        private const val GAIN_SMOOTH = 0.15
    }
}
