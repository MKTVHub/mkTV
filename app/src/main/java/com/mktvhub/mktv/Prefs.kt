package com.mktvhub.mktv

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("browser", Context.MODE_PRIVATE)

    var homeUrl: String
        get() = p.getString("home_url", null) ?: BuildConfig.DEFAULT_HOME_URL
        set(v) = p.edit().putString("home_url", v.trim()).apply()
    var adBlock: Boolean
        get() = p.getBoolean("ad_block", true)
        set(v) = p.edit().putBoolean("ad_block", v).apply()
    var blockPopups: Boolean
        get() = p.getBoolean("block_popups", true)
        set(v) = p.edit().putBoolean("block_popups", v).apply()
    var blockRedirects: Boolean
        get() = p.getBoolean("block_redirects", true)
        set(v) = p.edit().putBoolean("block_redirects", v).apply()
    var desktopMode: Boolean
        get() = p.getBoolean("desktop_mode", false)
        set(v) = p.edit().putBoolean("desktop_mode", v).apply()
    /** User-saved shortcuts as (title, url) pairs. */
    var shortcuts: List<Pair<String, String>>
        get() {
            val raw = p.getString("shortcuts", null) ?: return DEFAULT_SHORTCUTS
            val arr = org.json.JSONArray(raw)
            return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("title") to o.getString("url") } }
        }
        set(v) {
            val arr = org.json.JSONArray()
            v.forEach { (t, u) -> arr.put(org.json.JSONObject().put("title", t).put("url", u)) }
            p.edit().putString("shortcuts", arr.toString()).apply()
        }

    /** Start on (and ⌂ goes to) the shortcuts grid instead of [homeUrl]. */
    var startWithShortcuts: Boolean
        get() = p.getBoolean("start_with_shortcuts", true)
        set(v) = p.edit().putBoolean("start_with_shortcuts", v).apply()

    /** Recently visited pages, newest first, one per site. */
    var recent: List<Pair<String, String>>
        get() {
            val arr = org.json.JSONArray(p.getString("recent", null) ?: "[]")
            return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("title") to o.getString("url") } }
        }
        set(v) {
            val arr = org.json.JSONArray()
            v.forEach { (t, u) -> arr.put(org.json.JSONObject().put("title", t).put("url", u)) }
            p.edit().putString("recent", arr.toString()).apply()
        }

    var stripTracking: Boolean
        get() = p.getBoolean("strip_tracking", true)
        set(v) = p.edit().putBoolean("strip_tracking", v).apply()
    var blockThirdPartyCookies: Boolean
        get() = p.getBoolean("block_3p_cookies", false)
        set(v) = p.edit().putBoolean("block_3p_cookies", v).apply()
    var autoUnmute: Boolean
        get() = p.getBoolean("auto_unmute", true)
        set(v) = p.edit().putBoolean("auto_unmute", v).apply()
    var autoHideToolbar: Boolean
        get() = p.getBoolean("auto_hide_toolbar", true)
        set(v) = p.edit().putBoolean("auto_hide_toolbar", v).apply()

    /** "jump" = D-pad hops between links/buttons, "pointer" = D-pad moves a mouse pointer. */
    var navMode: String
        get() = p.getString("nav_mode", "jump")!!
        set(v) = p.edit().putString("nav_mode", v).apply()

    /** Sites (registrable domains) where all blocking is paused. */
    var pausedSites: Set<String>
        get() = p.getStringSet("paused_sites", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("paused_sites", v).apply()
    var totalBlocked: Long
        get() = p.getLong("total_blocked", 0)
        set(v) = p.edit().putLong("total_blocked", v).apply()

    /** Optional hosts-format list downloaded once a day. Empty = bundled list only. */
    var blocklistUrl: String
        get() = p.getString("blocklist_url", null) ?: BuildConfig.DEFAULT_BLOCKLIST_URL
        set(v) = p.edit().putString("blocklist_url", v.trim()).apply()

    private companion object {
        val DEFAULT_SHORTCUTS = listOf("Google" to "https://www.google.com/")
    }
}
