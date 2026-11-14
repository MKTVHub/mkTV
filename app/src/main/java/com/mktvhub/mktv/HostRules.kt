package com.mktvhub.mktv

/** Pure helpers (no Android deps) so they can be unit tested on the JVM. */
object HostRules {
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "ac", "edu", "or", "ne", "go")

    /** True if [host] or any parent domain of it is in [blocked]. */
    fun isBlocked(host: String?, blocked: Set<String>): Boolean {
        if (host.isNullOrEmpty() || blocked.isEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        if (!h.contains('.')) return false
        while (true) {
            if (h in blocked) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false // don't match bare TLDs
        }
    }

    /** Approximate registrable domain: "a.b.example.co.uk" -> "example.co.uk". */
    fun siteOf(host: String?): String? {
        if (host.isNullOrEmpty()) return null
        val parts = host.lowercase().trimEnd('.').split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val take = if (parts.last().length == 2 && parts[parts.size - 2] in SECOND_LEVEL) 3 else 2
        return parts.takeLast(take).joinToString(".")
    }

    fun sameSite(a: String?, b: String?): Boolean {
        val sa = siteOf(a) ?: return false
        return sa == siteOf(b)
    }

    private val SELF_ENTRIES = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost", "ip6-loopback")

    /** Parses hosts-file ("0.0.0.0 ads.example.com") or plain domain-per-line lists. */
    fun parseHosts(text: String): Set<String> {
        val out = HashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            // Second token if present ("0.0.0.0 host"), else the first. No regex: lists have ~150k lines.
            val firstEnd = line.indexOfFirst { it == ' ' || it == '\t' }
            val token = if (firstEnd < 0) line else line.substring(firstEnd).trim().let { rest ->
                val end = rest.indexOfFirst { it == ' ' || it == '\t' }
                if (end < 0) rest else rest.substring(0, end)
            }
            val domain = token.lowercase().trimEnd('.')
            if (domain in SELF_ENTRIES || !domain.contains('.') || domain.all { it.isDigit() || it == '.' }) return@forEach
            out += domain
        }
        return out
    }

    private val STREAM_EXT = Regex("""\.(m3u8|mpd)(\?|$|#)""", RegexOption.IGNORE_CASE)

    /** HLS / DASH manifest URLs that the native player can open. */
    fun isStreamUrl(url: String): Boolean = STREAM_EXT.containsMatchIn(url.substringBefore('#'))

    /** True if [url] sits on the same host under the directory of [master], e.g. a quality-level playlist. */
    fun isVariantOf(url: String, master: String): Boolean {
        val dir = master.substringBefore('?').substringBeforeLast('/') + "/"
        return url != master && url.startsWith(dir)
    }

    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid", "li_fat_id",
        "mc_cid", "mc_eid", "igshid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id", "rb_clickid",
    )

    /** Removes known click/campaign tracking parameters (utm_*, fbclid, gclid, YouTube "si", ...). */
    fun stripTracking(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hashAt = url.indexOf('#', q)
        val base = url.substring(0, q)
        val query = if (hashAt < 0) url.substring(q + 1) else url.substring(q + 1, hashAt)
        val fragment = if (hashAt < 0) "" else url.substring(hashAt)
        val host = base.substringAfter("://", "").substringBefore('/').lowercase()
        val youtube = host.endsWith("youtube.com") || host == "youtu.be"
        val kept = query.split('&').filter { part ->
            val key = part.substringBefore('=').lowercase()
            part.isNotEmpty() && !key.startsWith("utm_") && key !in TRACKING_PARAMS && !(youtube && (key == "si" || key == "feature"))
        }
        if (kept.size == query.split('&').count { it.isNotEmpty() }) return url
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }
}
