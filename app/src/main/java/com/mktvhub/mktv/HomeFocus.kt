package com.mktvhub.mktv

/**
 * Home's rows are rebuilt when they change (a favourite added, an icon that arrived, a video's progress).
 * These rules keep the remote's focus on the tile it was on, and skip a rebuild that would show the same thing.
 * Home tiles are tagged "kind:index|url" (fav, pop, cw, rec); other controls have tags like "favact:name" or "sug:2|url".
 */
object HomeFocus {
    private fun kind(tag: String) = tag.substringBefore(':')
    private fun index(tag: String) = tag.substringAfter(':').substringBefore('|').toIntOrNull()
    private fun url(tag: String) = tag.substringAfter('|', "").takeIf { it.isNotEmpty() }

    /**
     * Where focus goes after a rebuild, given the focused control's tag before it and the tags on screen after it:
     * the same control, else the same site in the same row (it moved), else the same place in that row (it went).
     * Null when that row is now empty or [old] isn't a tile: the caller falls back to the row's own button.
     */
    fun pick(old: String?, now: List<String>): String? {
        if (old == null) return null
        if (old in now) return old
        val k = kind(old)
        val same = now.filter { kind(it) == k }
        if (same.isEmpty()) return null
        url(old)?.let { u -> same.firstOrNull { url(it) == u }?.let { return it } }
        val i = index(old) ?: return null
        val rows = same.mapNotNull { t -> index(t)?.let { it to t } }.sortedBy { it.first }
        if (rows.isEmpty()) return null
        return rows[i.coerceIn(0, rows.size - 1)].second
    }

    /** What a row of tiles looks like: same signature, same tiles, so the row is left alone (and focus with it). */
    fun signature(kind: String, items: List<Pair<String, String>>, hasIcon: List<Boolean>, state: String = ""): String =
        buildString {
            append(kind).append('\u0001').append(state)
            items.forEachIndexed { i, (title, url) ->
                append('\u0001').append(title).append('\u0002').append(url).append('\u0002').append(hasIcon.getOrNull(i) == true)
            }
        }
}

/** Saved site icons: one file per host (minus "www."), fetched from the network at most once per host per run. */
object SiteIcons {
    /** The icon cache key for [host]; null for hosts without a dot (localhost, a bare name), which get the letter tile. */
    fun key(host: String?): String? = host?.lowercase()?.removePrefix("www.")?.takeIf { it.contains('.') }

    private val tried = HashSet<String>()

    /**
     * Hosts to fetch now: one per icon key, not saved yet ([saved]), and never tried before in this process, whatever
     * the result was then. A host that fails (no https, an IP address, a challenge page) is not asked again until
     * mkTV restarts, so showing Home again can't start the same downloads over and over.
     */
    @Synchronized fun claim(hosts: List<String>, saved: (String) -> Boolean): List<String> =
        hosts.mapNotNull { h -> key(h)?.let { it to h } }.distinctBy { it.first }
            .filter { (k, _) -> !saved(k) && tried.add(k) }.map { it.second }

    /** For tests. */
    @Synchronized internal fun reset() = tried.clear()

    /** A fetch result changes Home only if a tile on screen now has an icon it didn't have: otherwise no rebuild. */
    fun changesShown(savedKeys: Collection<String>, shownHosts: List<String?>): Boolean {
        if (savedKeys.isEmpty()) return false
        val shown = shownHosts.mapNotNull(::key).toSet()
        return savedKeys.any { it in shown }
    }
}
