package com.mktvhub.mktv

/**
 * Pure helpers for page zoom and the full-screen fallback (no Android imports, unit-tested).
 *
 * Zoom is applied as CSS `zoom` on the page's root element (see `__sp.zoom` in page_script.js), not with
 * WebView's textZoom or page scale: textZoom only enlarges text (icons, images and buttons stay tiny on
 * desktop-sized sites), and page scale (setInitialScale / zoomBy) magnifies without reflowing, so a zoomed
 * page grows wider than the screen and needs sideways panning that a remote can't do. CSS zoom reflows the
 * whole page at the new size, keeps text sharp, and changes live without a reload.
 */
object PageZoom {
    /** Steps offered by − / +; the same scale as desktop browsers, trimmed to what is useful on a TV. */
    val LEVELS = listOf(75, 90, 100, 110, 125, 150, 175, 200)
    const val DEFAULT = 100

    /** Nearest offered level, so odd saved values still step sensibly. */
    fun snap(pct: Int): Int = LEVELS.minByOrNull { kotlin.math.abs(it - pct) } ?: DEFAULT

    /** One step up (dir > 0) or down (dir < 0), clamped at the ends. */
    fun step(pct: Int, dir: Int): Int {
        val i = LEVELS.indexOf(snap(pct))
        return LEVELS[(i + if (dir > 0) 1 else if (dir < 0) -1 else 0).coerceIn(0, LEVELS.size - 1)]
    }

    fun canStep(pct: Int, dir: Int) = step(pct, dir) != snap(pct)

    fun label(pct: Int) = "$pct %"

    /** Zoom is remembered per host; "www." is dropped so www.example.com and example.com share a level. */
    fun hostKey(host: String?): String? {
        return host?.trim()?.lowercase()?.removePrefix("www.")?.trimEnd('.')?.ifEmpty { null }
    }

    /** Level for [host]: its own saved level, else the default. */
    fun levelFor(host: String?, sites: Map<String, Int>, default: Int): Int =
        hostKey(host)?.let { sites[it] } ?: default

    /**
     * New per-site map after the user picks [pct] for [host]. A level equal to the default is stored as
     * "no override", so changing the default later still applies to that site.
     */
    fun withLevel(sites: Map<String, Int>, host: String?, pct: Int, default: Int): Map<String, Int> {
        val key = hostKey(host) ?: return sites
        return if (snap(pct) == default) sites - key else sites + (key to snap(pct))
    }

    /** Stored as "host=pct" strings in a string set. Bad entries are skipped. */
    fun parse(entries: Set<String>): Map<String, Int> = entries.mapNotNull { e ->
        val i = e.lastIndexOf('=')
        if (i <= 0) return@mapNotNull null
        val host = hostKey(e.substring(0, i)) ?: return@mapNotNull null
        val pct = e.substring(i + 1).toIntOrNull()?.takeIf { it in LEVELS.first()..LEVELS.last() } ?: return@mapNotNull null
        host to snap(pct)
    }.toMap()

    fun serialize(sites: Map<String, Int>): Set<String> = sites.map { (h, p) -> "$h=$p" }.toSet()

    /** Script that applies [pct] to the current page (top frame); safe to run repeatedly. */
    fun script(pct: Int): String = "window.__sp && window.__sp.zoom && __sp.zoom(${snap(pct)})"

    // ---- Full-screen fallback ----

    /** What `__sp.fullscreen()` answered: "on" = Fullscreen API asked, "fail" = API missing or threw. */
    enum class Fs { REQUESTED, FAILED, NO_VIDEO }

    fun parseFs(res: String?): Fs = when (res?.trim()?.trim('"')) {
        "on" -> Fs.REQUESTED
        "fail" -> Fs.FAILED
        else -> Fs.NO_VIDEO
    }

    /**
     * After asking for real full screen, fall back to filling the window with the video if the page
     * never showed its full-screen view (WebView's onShowCustomView) and fill mode isn't already on.
     */
    fun needsFill(fs: Fs, nativeShown: Boolean, filling: Boolean): Boolean =
        !nativeShown && !filling && fs != Fs.NO_VIDEO
}
