package com.mktvhub.mktv

import java.net.URLEncoder

/** Address-bar logic: search engines, input → URL, suggestion parsing and local matches. No Android deps. */
object WebSearch {
    /** [searchUrl] and [suggestUrl] contain `%s` for the encoded query. Suggestion endpoints return OpenSearch JSON. */
    data class Engine(val id: String, val name: String, val searchUrl: String, val suggestUrl: String)

    val ENGINES = listOf(
        Engine("google", "Google", "https://www.google.com/search?q=%s", "https://suggestqueries.google.com/complete/search?client=firefox&q=%s"),
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/ac/?q=%s&type=list"),
        Engine("bing", "Bing", "https://www.bing.com/search?q=%s", "https://api.bing.com/osjson.aspx?form=OSDJAS&query=%s"),
        Engine("brave", "Brave", "https://search.brave.com/search?q=%s", "https://search.brave.com/api/suggest?q=%s"),
        Engine("startpage", "Startpage", "https://www.startpage.com/sp/search?query=%s", "https://www.startpage.com/osuggestions?q=%s"),
    )

    fun engine(id: String?): Engine = ENGINES.firstOrNull { it.id == id } ?: ENGINES[0]

    /** Spaces as %20 (not "+"), so every engine reads the query the same way. */
    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun searchUrl(query: String, engine: Engine): String = engine.searchUrl.replace("%s", encode(query.trim()))
    fun suggestUrl(query: String, engine: Engine): String = engine.suggestUrl.replace("%s", encode(query.trim()))

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** True for input that should be opened as an address rather than searched. */
    fun looksLikeUrl(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) return false
        if (SCHEME.containsMatchIn(t) || t.startsWith("about:")) return true
        return !t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))
    }

    /** Turns address-bar input into a URL: keeps addresses, adds https://, or searches with [engine]. */
    fun normalize(input: String, engine: Engine): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        if (SCHEME.containsMatchIn(t) || t.startsWith("about:")) return t
        if (looksLikeUrl(t)) return "https://$t"
        return searchUrl(t, engine)
    }

    /** Parses an OpenSearch suggestion response: `["query", ["s1", "s2", ...], ...]`. Bad input gives an empty list. */
    fun parseSuggestions(json: String, max: Int = 6): List<String> = runCatching {
        val list = org.json.JSONArray(json).getJSONArray(1)
        (0 until list.length()).mapNotNull { list.optString(it).trim().takeIf { s -> s.isNotEmpty() } }.distinct().take(max)
    }.getOrDefault(emptyList())

    /** "https://www.example.com/a/" → "example.com/a", for matching and display. */
    fun displayUrl(url: String): String =
        url.substringAfter("://").removePrefix("www.").trimEnd('/')

    /**
     * Favourites and history entries (title, url) that match what was typed, best first:
     * address starts with it, then a title word starts with it, then it appears anywhere. One per address.
     */
    fun localMatches(query: String, entries: List<Pair<String, String>>, max: Int = 3): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        return entries.mapNotNull { e ->
            val addr = displayUrl(e.second).lowercase()
            val title = e.first.lowercase()
            val rank = when {
                addr.startsWith(q) -> 0
                title.split(' ', '-', '|', '·', ':').any { it.startsWith(q) } -> 1
                addr.contains(q) || title.contains(q) -> 2
                else -> return@mapNotNull null
            }
            rank to e
        }.sortedBy { it.first }.map { it.second }.filter { seen.add(displayUrl(it.second).lowercase()) }.take(max)
    }
}
