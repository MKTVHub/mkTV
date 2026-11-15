package com.mktvhub.mktv

/**
 * Pure helpers for watch-mode playback controls (no Android imports, unit-tested): the speed steps,
 * per-site speed storage (same host key as [PageZoom]) and the sleep timer choices.
 */
object PlaybackSpeed {
    /** Speeds offered on the TV and accepted from the phone remote. */
    val LEVELS = listOf(0.75, 1.0, 1.25, 1.5, 2.0)
    const val DEFAULT = 1.0

    /** The offered level equal to [v], or null (the phone remote may only pick from [LEVELS]). */
    fun exact(v: Double): Double? = if (!v.isFinite()) null else LEVELS.firstOrNull { kotlin.math.abs(it - v) < 0.001 }

    /** Nearest offered level, so a rate the site set itself (e.g. 1.1) still steps sensibly. */
    fun snap(v: Double): Double = if (!v.isFinite()) DEFAULT else LEVELS.minByOrNull { kotlin.math.abs(it - v) } ?: DEFAULT

    /** The next level, wrapping from the fastest back to the slowest (the phone's speed chip cycles). */
    fun next(v: Double): Double = LEVELS[(LEVELS.indexOf(snap(v)) + 1) % LEVELS.size]

    /** "0.75×", "1×", "1.25×", "1.5×", "2×". */
    fun label(v: Double): String {
        val s = snap(v)
        val text = if (s == Math.floor(s)) s.toInt().toString() else s.toString().trimEnd('0')
        return "$text×"
    }

    /** Speed for [host]: its own saved speed, else 1×. */
    fun speedFor(host: String?, sites: Map<String, Double>): Double = PageZoom.hostKey(host)?.let { sites[it] } ?: DEFAULT

    /** New per-site map after the user picks [v] for [host]; 1× is stored as "no entry". */
    fun withSpeed(sites: Map<String, Double>, host: String?, v: Double): Map<String, Double> {
        val key = PageZoom.hostKey(host) ?: return sites
        val s = snap(v)
        return if (s == DEFAULT) sites - key else sites + (key to s)
    }

    /** Stored as "host=speed" strings in a string set. Bad entries are skipped. */
    fun parse(entries: Set<String>): Map<String, Double> = entries.mapNotNull { e ->
        val i = e.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        val host = PageZoom.hostKey(e.substring(0, i)) ?: return@mapNotNull null
        val v = e.substring(i + 1).toDoubleOrNull()?.let { exact(it) } ?: return@mapNotNull null
        host to v
    }.toMap()

    fun serialize(sites: Map<String, Double>): Set<String> = sites.map { (h, v) -> "$h=$v" }.toSet()

    /** Script that sets the page's wanted speed (current video and every later one in this document). */
    fun script(v: Double): String = "window.__sp && __sp.rate && __sp.rate(${snap(v)})"

    // ---- Sleep timer ----

    /** Minutes; 0 = off, [SLEEP_END] = pause when the current video ends. */
    const val SLEEP_OFF = 0
    const val SLEEP_END = -1
    val SLEEP = listOf(SLEEP_OFF, 15, 30, 60, SLEEP_END)

    /** A sleep choice from the phone remote: a whole number in [SLEEP], else null. */
    fun sleepChoice(v: Double): Int? =
        if (!v.isFinite() || v != Math.floor(v)) null else v.toInt().takeIf { it in SLEEP }

    fun sleepLabel(m: Int): String = when (m) {
        SLEEP_OFF -> "Off"
        SLEEP_END -> "End of video"
        else -> "$m min"
    }

    /** Remaining time as "m:ss" (or "h:mm:ss"), rounded up so it never shows 0:00 while still running. */
    fun remaining(ms: Long): String {
        val total = ((ms.coerceAtLeast(0) + 999) / 1000)
        val h = total / 3600; val m = (total / 60) % 60; val s = total % 60
        val l = java.util.Locale.ROOT
        return if (h > 0) "%d:%02d:%02d".format(l, h, m, s) else "%d:%02d".format(l, m, s)
    }
}
