package com.mktvhub.mktv

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("browser", Context.MODE_PRIVATE)
    /** Separate file so the (large, frequently rewritten) history doesn't slow every other setting. */
    private val h = context.getSharedPreferences("history", Context.MODE_PRIVATE)

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
    /** Share Wi-Fi: kept only on this TV (app-private storage) so it needn't be retyped. */
    var wifiSsid: String
        get() = p.getString("wifi_ssid", "") ?: ""
        set(v) = p.edit().putString("wifi_ssid", v).apply()
    var wifiPassword: String
        get() = p.getString("wifi_password", "") ?: ""
        set(v) = p.edit().putString("wifi_password", v).apply()
    /** Phone remote asks once for the permission that reveals the Wi-Fi name; never nags again. */
    var askedWifiName: Boolean
        get() = p.getBoolean("asked_wifi_name", false)
        set(v) = p.edit().putBoolean("asked_wifi_name", v).apply()
    /** Phone remote: on once the user has opened it, so it listens whenever mkTV is on screen; its pairing code. */
    var remoteEnabled: Boolean
        get() = p.getBoolean("remote_enabled", false)
        set(v) = p.edit().putBoolean("remote_enabled", v).apply()
    var remoteToken: String?
        get() = p.getString("remote_token", null)
        set(v) = p.edit().putString("remote_token", v).apply()
    var desktopMode: Boolean
        get() = p.getBoolean("desktop_mode", false)
        set(v) = p.edit().putBoolean("desktop_mode", v).apply()
    /** Favourites: links and one level of folders (see [Bookmarks]); still stored under the old "shortcuts" key. */
    var bookmarks: List<Bookmarks.Node>
        get() = bookmarksCache ?: (p.getString("shortcuts", null)?.let { Bookmarks.parse(it) } ?: DEFAULT_SHORTCUTS).also { bookmarksCache = it }
        set(v) { bookmarksCache = v; p.edit().putString("shortcuts", Bookmarks.toJson(v)).apply() }
    @Volatile private var bookmarksCache: List<Bookmarks.Node>? = null // read on every address-bar keystroke and by the phone remote
    /** Every favourite as (title, url), folders flattened. Change them through [bookmarks]. */
    val shortcuts: List<Pair<String, String>>
        get() = Bookmarks.flatten(bookmarks).map { it.title to it.url }

    /** The home screen's Popular row; the user can remove sites and add others. */
    var popular: List<Pair<String, String>>
        get() {
            val raw = p.getString("popular", null) ?: return DEFAULT_POPULAR
            val arr = org.json.JSONArray(raw)
            return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("title") to o.getString("url") } }
        }
        set(v) {
            val arr = org.json.JSONArray()
            v.forEach { (t, u) -> arr.put(org.json.JSONObject().put("title", t).put("url", u)) }
            p.edit().putString("popular", arr.toString()).apply()
        }

    /** Home screen section order, e.g. "fav,pop,rec"; unknown or missing ids fall back to the default order. */
    var homeOrder: List<String>
        get() {
            val saved = p.getString("home_order", null)?.split(',').orEmpty().filter { it in HOME_SECTIONS }
            return (saved + HOME_SECTIONS).distinct()
        }
        set(v) = p.edit().putString("home_order", v.joinToString(",")).apply()
    var hiddenSections: Set<String>
        get() = p.getStringSet("hidden_sections", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("hidden_sections", v).apply()

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
    /** Pointer mode: [PointerMotion] level (Slow / Normal / Fast) and whether a held direction speeds up. */
    var pointerSpeed: Int
        get() = PointerMotion.level(p.getInt("pointer_speed", PointerMotion.NORMAL))
        set(v) = p.edit().putInt("pointer_speed", PointerMotion.level(v)).apply()
    var pointerAccel: Boolean
        get() = p.getBoolean("pointer_accel", true)
        set(v) = p.edit().putBoolean("pointer_accel", v).apply()

    /** Sites (registrable domains) where all blocking is paused. */
    var pausedSites: Set<String>
        get() = p.getStringSet("paused_sites", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("paused_sites", v).apply()
    var totalBlocked: Long
        get() = p.getLong("total_blocked", 0)
        set(v) = p.edit().putLong("total_blocked", v).apply()

    /** Ids of the filter lists that are on (presets and lists added by address); see [FilterLists]. */
    var filterListsOn: Set<String>
        get() = p.getStringSet("filter_lists_on", null)?.toSet() ?: FilterLists.PRESETS.filter { it.defaultOn }.map { it.id }.toSet()
        set(v) = p.edit().putStringSet("filter_lists_on", v).apply()
    /** Lists the user added by address, as (name, url) pairs. */
    var customFilterLists: List<Pair<String, String>>
        get() {
            val arr = org.json.JSONArray(p.getString("custom_filter_lists", null) ?: "[]")
            return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("name") to o.getString("url") } }
        }
        set(v) {
            val arr = org.json.JSONArray()
            v.forEach { (n, u) -> arr.put(org.json.JSONObject().put("name", n).put("url", u)) }
            p.edit().putString("custom_filter_lists", arr.toString()).apply()
        }
    /** Domains (and their subdomains) that are never blocked. */
    var allowlist: Set<String>
        get() = p.getStringSet("allowlist", emptySet())!!.toSet()
        set(v) = p.edit().putStringSet("allowlist", v).apply()

    /**
     * Before filter lists, a single "Extra blocklist URL" (default StevenBlack) was stored here.
     * Moved once into [filterListsOn] / [customFilterLists]: empty = StevenBlack off, another URL = a custom list.
     */
    fun migrateLegacyBlocklist(): Boolean {
        if (p.getBoolean("filter_lists_migrated", false)) return false
        val legacy = p.getString("blocklist_url", null)
        val sb = FilterLists.PRESETS.first { it.defaultOn }
        when {
            legacy == null || legacy == sb.url -> {}
            legacy.isBlank() -> filterListsOn = filterListsOn - sb.id
            else -> FilterLists.normalizeListUrl(legacy)?.let { url ->
                if (FilterLists.PRESETS.none { it.url == url }) customFilterLists = customFilterLists + (FilterLists.nameFor(url) to url)
                filterListsOn = filterListsOn - sb.id + (FilterLists.PRESETS.firstOrNull { it.url == url }?.id ?: FilterLists.customId(url))
            }
        }
        p.edit().putBoolean("filter_lists_migrated", true).remove("blocklist_url").apply()
        return legacy == null || legacy == sb.url
    }

    /** Full browsing history, newest first (normal mode only, capped at [History.MAX]). */
    var history: List<History.Entry>
        get() = historyCache ?: History.fromJson(h.getString("entries", null)).also { historyCache = it }
        set(v) { historyCache = v; h.edit().putString("entries", History.toJson(v)).apply() }
    /** Parsed once; read on every keystroke in the address bar. */
    private var historyCache: List<History.Entry>? = null

    /** Downloads panel rows, newest first (normal mode only; private-mode downloads are never saved). */
    var downloads: List<Downloads.Entry>
        get() = Downloads.fromJson(p.getString("downloads", null))
        set(v) = p.edit().putString("downloads", Downloads.toJson(v)).apply()

    /** [WebSearch.Engine.id] used for address-bar searches and suggestions. */
    var searchEngine: String
        get() = p.getString("search_engine", null) ?: "google"
        set(v) = p.edit().putString("search_engine", v).apply()
    /** Off by default: suggestions send what you type to the search engine. Never used in private mode. */
    var searchSuggestions: Boolean
        get() = p.getBoolean("search_suggestions", false)
        set(v) = p.edit().putBoolean("search_suggestions", v).apply()

    /** Daily check of the GitHub "latest release" for a newer mkTV. */
    var updateCheck: Boolean
        get() = p.getBoolean("update_check", true)
        set(v) = p.edit().putBoolean("update_check", v).apply()
    var lastUpdateCheck: Long
        get() = p.getLong("last_update_check", 0)
        set(v) = p.edit().putLong("last_update_check", v).apply()
    /** Newest release seen by the last check, kept so the home card shows without a new request. */
    var latestRelease: Updates.Release?
        get() {
            val v = p.getString("latest_version", null) ?: return null
            return Updates.Release(v, p.getString("latest_apk_url", null) ?: return null, p.getLong("latest_apk_size", 0))
        }
        set(r) = p.edit().putString("latest_version", r?.version).putString("latest_apk_url", r?.apkUrl).putLong("latest_apk_size", r?.size ?: 0).apply()
    /** Version whose home-screen card the user dismissed with "Later". */
    var dismissedUpdate: String?
        get() = p.getString("dismissed_update", null)
        set(v) = p.edit().putString("dismissed_update", v).apply()

    // ---- Page zoom (see PageZoom) ----
    /** Zoom for sites without their own level, in percent. */
    var defaultZoom: Int
        get() = PageZoom.snap(p.getInt("default_zoom", PageZoom.DEFAULT))
        set(v) = p.edit().putInt("default_zoom", PageZoom.snap(v)).apply()
    /** Per-host zoom levels (normal mode only; private browsing keeps its levels in memory). */
    var siteZoom: Map<String, Int>
        get() = PageZoom.parse(p.getStringSet("site_zoom", emptySet())!!)
        set(v) = p.edit().putStringSet("site_zoom", PageZoom.serialize(v)).apply()

    /** Per-host video speed (see PlaybackSpeed; normal mode only, private browsing keeps its speeds in memory). */
    var siteSpeed: Map<String, Double>
        get() = PlaybackSpeed.parse(p.getStringSet("site_speed", emptySet())!!)
        set(v) = p.edit().putStringSet("site_speed", PlaybackSpeed.serialize(v)).apply()

    companion object {
        val HOME_SECTIONS = listOf("pop", "fav", "rec")
        /** Curated Popular row a fresh install starts with. */
        val DEFAULT_POPULAR = listOf(
            "YouTube" to "https://www.youtube.com/tv",
            "Netflix" to "https://www.netflix.com/",
            "Prime Video" to "https://www.primevideo.com/",
            "Disney+" to "https://www.disneyplus.com/",
            "Spotify" to "https://open.spotify.com/",
            "YouTube Music" to "https://music.youtube.com/",
            "Twitch" to "https://www.twitch.tv/",
            "TikTok" to "https://www.tiktok.com/foryou",
        )
        /** Offered as one-press "+ Add" chips while editing Popular (plus any default that was removed). */
        val POPULAR_SUGGESTIONS = listOf(
            "Vimeo" to "https://vimeo.com/",
            "Pluto TV" to "https://pluto.tv/",
            "Tubi" to "https://tubitv.com/",
            "Plex" to "https://app.plex.tv/",
            "Kick" to "https://kick.com/",
            "Crunchyroll" to "https://www.crunchyroll.com/",
            "Dailymotion" to "https://www.dailymotion.com/",
            "Apple TV+" to "https://tv.apple.com/",
            "Max" to "https://play.max.com/",
            "SoundCloud" to "https://soundcloud.com/",
            "Wikipedia" to "https://www.wikipedia.org/",
        )
        private val DEFAULT_SHORTCUTS = listOf<Bookmarks.Node>(Bookmarks.Link("Google", "https://www.google.com/"))
    }
}
