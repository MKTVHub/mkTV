package com.mktvhub.mktv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure helpers for the native player's controls (no Android imports, unit-tested): the accelerating
 * ◀/▶ seek, seek clamping, time labels, "Ends at" and the title fallback.
 */
object PlayerUi {
    /** One tap of ◀/▶ (or Rewind / Fast forward). */
    const val TAP_MS = 10_000L
    /** While a seek key is held, the preview moves one step per tick (remotes repeat every ~50 ms; too fast to step on each). */
    const val TICK_MS = 250L
    /** Overlay hides after this long without a key while playing. */
    const val HIDE_MS = 4_000L

    /** Step for a seek key held for [heldMs]: 10 s, then 30 s after 1 s, then 60 s after 3 s. */
    fun seekStep(heldMs: Long): Long = when {
        heldMs < 1_000 -> TAP_MS
        heldMs < 3_000 -> 30_000L
        else -> 60_000L
    }

    /** [from] moved by [delta], kept inside the stream ([durationMs] ≤ 0 means unknown: only the start is a limit). */
    fun seekTarget(from: Long, delta: Long, durationMs: Long): Long {
        val t = (from + delta).coerceAtLeast(0)
        return if (durationMs > 0) t.coerceAtMost(durationMs) else t
    }

    /** "4:05", "1:02:03". */
    fun clock(ms: Long): String = ContinueWatching.clock(ms.coerceAtLeast(0) / 1000.0)

    /** Seek preview offset: "+1:30", "−0:10". */
    fun delta(ms: Long): String = (if (ms < 0) "−" else "+") + clock(kotlin.math.abs(ms))

    /**
     * "Ends at 9:42 PM" (or "Ends at 21:42" with [is24h]) for [remainingMs] of video at [speed], from [nowMs].
     * Null when the remaining time isn't known.
     */
    fun endsAt(nowMs: Long, remainingMs: Long, speed: Double, is24h: Boolean, locale: Locale, zone: TimeZone): String? {
        if (remainingMs < 0 || !speed.isFinite() || speed <= 0) return null
        return "Ends at " + timeOfDay(nowMs + (remainingMs / speed).toLong(), is24h, locale, zone)
    }

    /** "9:42 PM" or "21:42", following the TV's 12/24-hour setting. */
    fun timeOfDay(ms: Long, is24h: Boolean, locale: Locale, zone: TimeZone): String =
        SimpleDateFormat(if (is24h) "H:mm" else "h:mm a", locale).apply { timeZone = zone }.format(Date(ms))

    /**
     * What to show at the top: the page or file title the browser passed, unless it's blank or just the
     * address (WebView reports the URL when a page has no title); then the host for a stream (manifests
     * are named "master.m3u8" and so on), or the file name for a local file.
     */
    fun title(title: String?, url: String): String {
        val t = title?.trim().orEmpty()
        if (t.isNotEmpty() && t != url && !t.startsWith("http://") && !t.startsWith("https://")) return t
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        val host = uri?.host?.removePrefix("www.").orEmpty()
        if (uri?.scheme?.startsWith("http") == true && host.isNotEmpty()) return host
        val last = uri?.rawPath.orEmpty().trimEnd('/').substringAfterLast('/')
        val name = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        // content:// IDs ("1234") aren't names.
        return name.takeIf { it.isNotBlank() && !it.all(Char::isDigit) } ?: "Video"
    }
}
