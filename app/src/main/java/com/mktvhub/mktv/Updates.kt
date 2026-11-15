package com.mktvhub.mktv

/** In-app update check against GitHub releases: version compare and release parsing. No Android deps. */
object Updates {
    const val RELEASES_API = "https://api.github.com/repos/MKTVHub/mkTV/releases/latest"
    /** The release asset name that stays the same across versions. */
    const val APK_ASSET = "mkTV.apk"
    const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    /** App stores that update mkTV themselves; installed from one, mkTV never offers its own update (store policy). */
    val STORES = setOf("com.android.vending", "com.amazon.venezia")

    /** Sideloaded (adb, a file manager, mkTV's own updater) or unknown: mkTV checks GitHub itself. */
    fun selfUpdates(installer: String?): Boolean = installer !in STORES

    data class Release(val version: String, val apkUrl: String, val size: Long)

    /** "v1.2.10-beta" → [1, 2, 10]. Anything after the numeric part is ignored. */
    fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
            .split('.').mapNotNull { it.toIntOrNull() }

    /** True if [latest] is a higher version than [current], comparing numbers (1.0.10 > 1.0.9). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** At most once a day; a clock that went backwards counts as due. */
    fun due(lastCheck: Long, now: Long): Boolean = now - lastCheck >= CHECK_INTERVAL_MS || now < lastCheck

    /** The GitHub "latest release" JSON → version and APK download, or null if it isn't a usable release. */
    fun parseRelease(json: String): Release? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val tag = o.getString("tag_name")
        if (parseVersion(tag).isEmpty()) return null
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val url = a.optString("browser_download_url")
            if (a.optString("name") == APK_ASSET && url.startsWith("https://")) {
                return Release(tag.removePrefix("v").removePrefix("V"), url, a.optLong("size"))
            }
        }
        null
    }.getOrNull()
}
