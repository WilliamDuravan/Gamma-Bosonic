package com.example.bosondiag

/** Dispatches a Y16 frame to the active tone mode. One instance per consumer thread. */
class FrameMapper(w: Int, h: Int, wantHist: Boolean = false) {
    val tone = ToneMapper(w, h, wantHist)
    val agc = AgcMapper(w, h)
    private var lastMode: ToneMode? = null

    fun map(f: ByteArray, fpn: IntArray?, out: IntArray) {
        val s = AppSettings
        val mode = s.toneMode
        if (mode != lastMode) {
            // Start the newly selected mapper from a fresh range so it does not glide in from stale state.
            if (mode == ToneMode.AGC) agc.reset() else tone.reset()
            lastMode = mode
        }
        val lut = Palettes.luts[s.paletteIdx]
        when (mode) {
            ToneMode.AGC -> {
                val i = s.agcClipIdx
                agc.map(
                    f, lut, fpn, s.minRange, s.agcSetpoint,
                    AppSettings.agcClipHigh[i], AppSettings.agcClipLow[i], s.agcProtectDarks, out
                )
            }
            ToneMode.CURVE -> tone.map(f, lut, fpn, s.minRange, s.curveLut, out)
            ToneMode.MINMAX -> tone.map(f, lut, fpn, s.minRange, null, out)
        }
    }

    fun reset() {
        tone.reset()
        agc.reset()
    }

    /** Range used for display (for the histogram panel and diagnostics). */
    val rangeBase: Float get() = if (lastMode == ToneMode.AGC) agc.rangeBase else tone.rangeBase
    val rangeSpan: Float get() = if (lastMode == ToneMode.AGC) agc.rangeSpan else tone.rangeSpan
    val lo: Float get() = if (lastMode == ToneMode.AGC) agc.lo else tone.lo
    val hi: Float get() = if (lastMode == ToneMode.AGC) agc.hi else tone.hi
}
