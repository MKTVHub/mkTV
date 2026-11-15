package com.mktvhub.mktv

import org.json.JSONArray
import org.json.JSONObject

/** Pure validation for phone remote requests (no Android deps) so it can be unit tested on the JVM. */
object RemoteInput {
    val KEYS = setOf(
        "up", "down", "left", "right", "ok", "back", "home", "menu",
        "playpause", "ff", "rew", "next", "prev", "volup", "voldown", "mute", "pageup", "pagedown",
    )
    val ACTIONS = setOf("down", "up", "press")
    val MEDIA = setOf("seek", "fullscreen", "captions")
    const val MAX_TEXT = 1000
    const val MAX_URL = 2048
    const val MAX_MOVE = 400.0
    const val MAX_SEEK = 86_400.0 * 2

    fun key(k: String?): String? = k?.takeIf { it in KEYS }
    fun action(a: String?): String = a?.takeIf { it in ACTIONS } ?: "press"

    fun text(t: String?): String = (t ?: "").take(MAX_TEXT)
    fun deletes(n: Int): Int = n.coerceIn(0, MAX_TEXT)

    /** A pointer step in phone px; NaN or huge values (a malformed or hostile request) become 0 / are clamped. */
    fun move(d: Double): Double = if (d.isFinite()) d.coerceIn(-MAX_MOVE, MAX_MOVE) else 0.0

    /** Absolute seek target in seconds, or null if it isn't a sane number. */
    fun seekTo(t: Double): Double? = t.takeIf { it.isFinite() && it >= 0 && it <= MAX_SEEK }

    /** Only http(s) pages may be opened from the phone (checked after the address-bar normalisation). */
    fun openable(url: String?): Boolean {
        if (url == null || url.length > MAX_URL) return false
        val scheme = url.substringBefore(':', "").lowercase()
        return (scheme == "http" || scheme == "https") && url.substring(scheme.length).startsWith("://")
    }

    private val HOST = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")

    /** Host names used as favicon file names: letters, digits, dots and dashes only, so no path tricks. */
    fun iconHost(h: String?): String? =
        h?.lowercase()?.removePrefix("www.")?.takeIf { it.length <= 253 && HOST.matches(it) }

    private val PALETTE = listOf("#6366F1", "#0EA5E9", "#10B981", "#F59E0B", "#EF4444", "#A855F7", "#EC4899")

    /** Same letter and colour as the TV's Home tiles. */
    fun tile(title: String, url: String, host: String?, hasIcon: Boolean): JSONObject {
        val site = HostRules.siteOf(host) ?: title
        return JSONObject()
            .put("title", title.take(200)).put("url", url)
            .put("letter", (site.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercaseChar().toString())
            .put("color", PALETTE[Math.floorMod(site.hashCode(), PALETTE.size)])
            .put("icon", if (hasIcon) iconHost(host) ?: "" else "")
    }

    fun tiles(list: List<JSONObject>) = JSONArray().also { a -> list.forEach { a.put(it) } }
}
