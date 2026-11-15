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
    val MEDIA = setOf("seek", "fullscreen", "captions", "speed", "sleep")
    const val MAX_TEXT = 1000
    const val MAX_URL = 2048
    const val MAX_MOVE = 400.0
    const val MAX_SEEK = 86_400.0 * 2

    fun key(k: String?): String? = k?.takeIf { it in KEYS }

    /** "Pixel 8 · Chrome", "iPhone · Safari", "Mac · Firefox": a friendly name for a connected device from its User-Agent. */
    fun deviceLabel(ua: String?): String {
        val u = ua.orEmpty()
        val model = Regex("""Android [\d.]+; ([^;)]+)""").find(u)?.groupValues?.get(1)
            ?.substringBefore(" Build")?.trim()?.filter { it.isLetterOrDigit() || it in " -_+." }
            ?.takeIf { it.length in 2..40 && it != "K" }
        val device = when {
            "iPhone" in u -> "iPhone"
            "iPad" in u -> "iPad"
            "Android" in u -> model ?: if ("Mobile" in u) "Android phone" else "Android tablet"
            "CrOS" in u -> "Chromebook"
            "Macintosh" in u -> "Mac"
            "Windows" in u -> "Windows PC"
            "Linux" in u -> "Linux PC"
            else -> "Device"
        }
        val browser = when {
            "EdgA/" in u || "EdgiOS" in u || "Edg/" in u -> "Edge"
            "SamsungBrowser" in u -> "Samsung Internet"
            "Firefox/" in u || "FxiOS" in u -> "Firefox"
            "OPR/" in u -> "Opera"
            "CriOS" in u || "Chrome/" in u -> "Chrome"
            "Safari/" in u -> "Safari"
            "curl/" in u -> "curl"
            else -> null
        }
        return listOfNotNull(device, browser).joinToString(" · ")
    }
    fun action(a: String?): String = a?.takeIf { it in ACTIONS } ?: "press"

    fun text(t: String?): String = (t ?: "").take(MAX_TEXT)
    fun deletes(n: Int): Int = n.coerceIn(0, MAX_TEXT)

    /** A pointer step in phone px; NaN or huge values (a malformed or hostile request) become 0 / are clamped. */
    fun move(d: Double): Double = if (d.isFinite()) d.coerceIn(-MAX_MOVE, MAX_MOVE) else 0.0

    /** Absolute seek target in seconds, or null if it isn't a sane number. */
    fun seekTo(t: Double): Double? = t.takeIf { it.isFinite() && it >= 0 && it <= MAX_SEEK }

    /**
     * /api/media: the checked value for [action], or null to answer 400. seek: target seconds ([to]);
     * speed: one of [PlaybackSpeed.LEVELS] ([value]); sleep: minutes from [PlaybackSpeed.SLEEP] ([value], 0 = off,
     * -1 = end of video); fullscreen / captions take no value (0).
     */
    fun mediaValue(action: String?, to: Double, value: Double): Double? = when (action?.takeIf { it in MEDIA }) {
        "seek" -> seekTo(to)
        "speed" -> PlaybackSpeed.exact(value)
        "sleep" -> PlaybackSpeed.sleepChoice(value)?.toDouble()
        null -> null
        else -> 0.0
    }

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

    /**
     * Whether the page should be in Favourites after /api/favourite: "add" and "remove" do nothing when it
     * already is / isn't (so a phone showing stale state can't undo it); anything else toggles.
     */
    fun favouriteTarget(action: String?, saved: Boolean): Boolean = when (action) {
        "add" -> true
        "remove" -> false
        else -> !saved
    }
}
