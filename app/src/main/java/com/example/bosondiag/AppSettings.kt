package com.example.bosondiag

import android.content.Context

enum class ToneMode(val label: String) { MINMAX("MIN/MAX"), CURVE("CURVE"), AGC("AGC") }

/** Capture output format for photos and videos. */
enum class OutFormat(val label: String, val normal: Boolean, val raw: Boolean) {
    NORMAL("STD", true, false),
    BOTH("STD+RAW", true, true),
    RAW("RAW", false, true)
}

/** All persisted user settings. Fields are volatile because mapper/record threads read them. */
object AppSettings {
    private const val PREFS = "tone" // same file as v0.8 so palette/min-range/curve carry over
    private var ctx: Context? = null

    val minRangeChoices = listOf(64f, 150f, 300f, 600f)
    val agcSetpointChoices = listOf(96f, 112f, 128f, 144f)
    /** H_Threshold / L_Threshold as fractions of all pixels. */
    val agcClipLabels = listOf("Tight", "Normal", "Loose")
    val agcClipHigh = floatArrayOf(0.01f, 0.02f, 0.05f)
    val agcClipLow = floatArrayOf(0.025f, 0.05f, 0.10f)

    @Volatile var paletteIdx = 0
    @Volatile var minRange = 64f
    @Volatile var toneMode = ToneMode.MINMAX
    @Volatile var curveT = ToneCurve.DEF_T
    @Volatile var curveM = ToneCurve.DEF_M
    @Volatile var curveS = ToneCurve.DEF_S
    @Volatile var curveLut: IntArray? = null
    @Volatile var agcSetpoint = 112f
    @Volatile var agcClipIdx = 1
    @Volatile var agcProtectDarks = false

    @Volatile var videoMode = false
    @Volatile var photoFormat = OutFormat.NORMAL
    @Volatile var videoFormat = OutFormat.NORMAL
    @Volatile var fps60 = true

    fun load(c: Context) {
        ctx = c.applicationContext
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        paletteIdx = p.getInt("palette", 0).coerceIn(0, Palettes.names.size - 1)
        minRange = p.getFloat("minRange", 64f)
        toneMode = if (p.contains("toneMode")) {
            ToneMode.values().getOrElse(p.getInt("toneMode", 0)) { ToneMode.MINMAX }
        } else if (p.getBoolean("curveOn", false)) ToneMode.CURVE else ToneMode.MINMAX
        curveT = p.getFloat("curveT", ToneCurve.DEF_T)
        curveM = p.getFloat("curveM", ToneCurve.DEF_M)
        curveS = p.getFloat("curveS", ToneCurve.DEF_S)
        if (!(curveT >= ToneCurve.T_MIN && curveT + ToneCurve.GAP <= curveM &&
                    curveM + ToneCurve.GAP <= curveS && curveS <= ToneCurve.S_MAX)
        ) resetCurve()
        agcSetpoint = p.getFloat("agcSetpoint", 112f)
        agcClipIdx = p.getInt("agcClip", 1).coerceIn(0, 2)
        agcProtectDarks = p.getBoolean("agcProtect", false)
        videoMode = p.getBoolean("videoMode", false)
        photoFormat = OutFormat.values().getOrElse(p.getInt("photoFmt", 0)) { OutFormat.NORMAL }
        videoFormat = OutFormat.values().getOrElse(p.getInt("videoFmt", 0)) { OutFormat.NORMAL }
        fps60 = p.getBoolean("fps60", true)
        rebuildCurve()
    }

    fun resetCurve() {
        curveT = ToneCurve.DEF_T
        curveM = ToneCurve.DEF_M
        curveS = ToneCurve.DEF_S
    }

    fun rebuildCurve() {
        curveLut = ToneCurve.buildLut(curveT, curveM, curveS)
    }

    fun save() {
        val c = ctx ?: return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("palette", paletteIdx)
            .putFloat("minRange", minRange)
            .putInt("toneMode", toneMode.ordinal)
            .putFloat("curveT", curveT)
            .putFloat("curveM", curveM)
            .putFloat("curveS", curveS)
            .putFloat("agcSetpoint", agcSetpoint)
            .putInt("agcClip", agcClipIdx)
            .putBoolean("agcProtect", agcProtectDarks)
            .putBoolean("videoMode", videoMode)
            .putInt("photoFmt", photoFormat.ordinal)
            .putInt("videoFmt", videoFormat.ordinal)
            .putBoolean("fps60", fps60)
            .apply()
    }
}
