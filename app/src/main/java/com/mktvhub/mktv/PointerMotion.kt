package com.mktvhub.mktv

/**
 * Pure pointer-mode speed math (no Android imports, unit-tested). [CursorView] multiplies the result by
 * the screen density; the phone touchpad uses [touchGain].
 */
object PointerMotion {
    /** Settings → Navigation → Pointer speed. */
    val LEVELS = listOf("Slow", "Normal", "Fast")
    const val SLOW = 0
    const val NORMAL = 1
    const val FAST = 2

    /** Normal is the original ramp; Slow helps hit small links, Fast crosses a 4K-sized page quicker. */
    private val SCALE = floatArrayOf(0.6f, 1f, 1.6f)

    /** Acceleration on: starts slow for precision, reaches full speed after [RAMP_MS] of holding. */
    const val MIN_DP_S = 260f
    const val MAX_DP_S = 1300f
    const val RAMP_MS = 900f
    /** Acceleration off: one steady speed, between the ramp's ends so a TV screen is about 2 s across. */
    const val STEADY_DP_S = 600f
    /** Phone touchpad: dp of pointer per phone px at Normal (crosses the TV in about two swipes). */
    const val TOUCH_GAIN = 1.8f

    fun level(i: Int): Int = i.coerceIn(SLOW, FAST)

    fun scale(level: Int): Float = SCALE[level(level)]

    /** Pointer speed in dp per second after holding a direction for [heldMs]. */
    fun speed(heldMs: Long, level: Int, accelerate: Boolean): Float {
        val base = if (!accelerate) STEADY_DP_S
        else MIN_DP_S + (MAX_DP_S - MIN_DP_S) * (heldMs.coerceAtLeast(0) / RAMP_MS).coerceAtMost(1f)
        return base * scale(level)
    }

    /** dp of pointer movement per phone px of finger movement. */
    fun touchGain(level: Int): Float = TOUCH_GAIN * scale(level)
}
