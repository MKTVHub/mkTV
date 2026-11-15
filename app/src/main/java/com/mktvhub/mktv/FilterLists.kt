package com.mktvhub.mktv

/**
 * Pure (Android-free) logic for ad-block filter lists: built-in presets, list parsing, allowlist matching.
 * None of these lists is bundled; each is downloaded by the TV only when the user turns it on.
 */
object FilterLists {
    data class Preset(val id: String, val name: String, val url: String, val about: String, val licence: String, val defaultOn: Boolean = false)

    /** A list the blocker should load: a preset or one the user added by address. */
    data class Source(val id: String, val name: String, val url: String, val custom: Boolean)

    // Formats and licences checked by hand against each list's header and repository (November 2026).
    val PRESETS = listOf(
        // github.com/StevenBlack/hosts, MIT. Hosts format ("0.0.0.0 host").
        Preset("sb-unified", "StevenBlack unified", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "Ads and malware. A safe default.", "MIT", defaultOn = true),
        Preset("sb-gambling", "StevenBlack + gambling", "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling/hosts",
            "The unified list plus gambling sites", "MIT"),
        Preset("sb-fakenews", "StevenBlack + fake news", "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews/hosts",
            "The unified list plus fake-news sites", "MIT"),
        // oisd.nl (github.com/sjhgvr/oisd), GPL-3.0. "domainswild" format ("*.domain").
        Preset("oisd-small", "OISD small", "https://small.oisd.nl/domainswild",
            "Ads and trackers, tuned to break nothing", "GPL-3.0"),
        Preset("oisd-big", "OISD big", "https://big.oisd.nl/domainswild",
            "Ads, trackers, malware and phishing. Large.", "GPL-3.0"),
        // github.com/hagezi/dns-blocklists, GPL-3.0. Plain domain per line ("wildcard/*-onlydomains.txt").
        Preset("hagezi-light", "HaGeZi Light", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt",
            "Basic ads and trackers", "GPL-3.0"),
        Preset("hagezi-normal", "HaGeZi Normal", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/multi-onlydomains.txt",
            "All-round ads, trackers and malware. Large.", "GPL-3.0"),
        Preset("hagezi-pro", "HaGeZi Pro", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
            "Stricter; may break a few sites. Large.", "GPL-3.0"),
        // pgl.yoyo.org/adservers, McRae GPL (free for non-commercial use). Hosts format.
        Preset("pgl", "Peter Lowe's list", "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
            "Small, hand-checked ad and tracking servers", "McRae GPL (non-commercial)"),
    )

    /** Above this, Settings warns that the lists use a lot of the TV's memory. */
    const val WARN_DOMAINS = 1_000_000
    /** Hard stop so a huge list can't run a TV without largeHeap out of memory. */
    const val MAX_DOMAINS = 1_500_000
    /** Biggest download accepted (OISD big is ~5 MB). */
    const val MAX_BYTES = 40L * 1024 * 1024
    const val REFRESH_MS = 24 * 3600_000L

    fun preset(id: String) = PRESETS.firstOrNull { it.id == id }

    /** Enabled sources, presets first, in a stable order. */
    fun sources(enabled: Set<String>, custom: List<Pair<String, String>>): List<Source> =
        PRESETS.filter { it.id in enabled }.map { Source(it.id, it.name, it.url, custom = false) } +
            custom.map { (name, url) -> Source(customId(url), name, url, custom = true) }.filter { it.id in enabled }

    /** Stable id (and cache file name) for a list added by address. */
    fun customId(url: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1").digest(url.trim().toByteArray())
        return "c-" + md.take(8).joinToString("") { "%02x".format(it) }
    }

    /** "example.com/lists/hosts.txt" style label for a list added by address. */
    fun nameFor(url: String): String {
        val rest = url.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/')
        val host = rest.substringBefore('/')
        val file = rest.substringAfterLast('/', "")
        return if (file.isEmpty() || !rest.contains('/')) host else "$host/$file"
    }

    /** Cleans up a typed list address; null if it isn't an http(s) URL with a host. */
    fun normalizeListUrl(input: String): String? {
        val t = input.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val url = if (t.contains("://")) t else "https://$t"
        val scheme = url.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        if (validDomain(host.lowercase()) == null && host != "localhost") return null
        return scheme + url.substring(url.indexOf("://"))
    }

    // ---- Parsing ----

    private val SELF_ENTRIES = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost", "ip6-loopback")

    /**
     * Parses one line of a hosts file ("0.0.0.0 ads.example.com"), a plain domain list ("ads.example.com",
     * "*.ads.example.com") or an Adblock-style list (only bare "||ads.example.com^" rules) into [out].
     * Everything else is ignored: comments, cosmetic rules, exceptions, paths, rules with options.
     */
    fun parseLine(raw: String, out: MutableCollection<String>) {
        var line = raw.trim().removePrefix("﻿").trim()
        if (line.isEmpty()) return
        val c0 = line[0]
        if (c0 == '#' || c0 == '!' || c0 == '[' || c0 == '@') return
        // Cosmetic rules ("site.com##.ad") must not become a block of site.com.
        if (line.contains("##") || line.contains("#@#") || line.contains("#?#") || line.contains("#$#") || line.contains("#%#")) return
        // '#' starts a comment only after whitespace ("0.0.0.0 host # note").
        for (i in 1 until line.length) {
            if (line[i] == '#' && line[i - 1].isWhitespace()) { line = line.substring(0, i).trimEnd(); break }
        }
        if (line.startsWith("||")) {
            // Bare "||domain^" only: options ($third-party, domain=...) or paths would change its meaning.
            if (!line.endsWith("^")) return
            validDomain(line.substring(2, line.length - 1).lowercase())?.let { out += it }
            return
        }
        // No regex: big lists have 200k+ lines and TV CPUs are slow.
        val firstEnd = line.indexOfFirst { it == ' ' || it == '\t' }
        if (firstEnd < 0) {
            validDomain(line.lowercase().removePrefix("*."))?.let { out += it }
            return
        }
        // Hosts line: IP followed by one or more host names.
        val ip = line.substring(0, firstEnd)
        val ipv4 = ip.all { it.isDigit() || it == '.' }
        val ipv6 = ip.contains(':') && ip.all { it.isDigit() || it == ':' || it == '%' || it.lowercaseChar() in 'a'..'z' }
        if (!ipv4 && !ipv6) return
        line.substring(firstEnd).split(' ', '\t').forEach { tok ->
            if (tok.isNotEmpty()) validDomain(tok.lowercase())?.let { out += it }
        }
    }

    /** Parses a whole list, streaming line by line; stops once [into] holds [max] domains. Returns domains seen in this list. */
    fun parse(lines: Sequence<String>, into: MutableSet<String> = HashSet(), max: Int = MAX_DOMAINS): Int {
        val seen = ArrayList<String>(1)
        var count = 0
        for (l in lines) {
            seen.clear()
            parseLine(l, seen)
            for (d in seen) {
                count++
                if (into.size < max) into += d
            }
        }
        return count
    }

    /** Number of valid entries, without keeping them (checks a fresh download). */
    fun count(lines: Sequence<String>): Int {
        val seen = ArrayList<String>(1)
        var count = 0
        for (l in lines) { seen.clear(); parseLine(l, seen); count += seen.size }
        return count
    }

    fun parse(text: String): Set<String> = HashSet<String>().also { parse(text.lineSequence(), it) }

    /** Lower-case hostname with at least one dot, or null for IPs, junk and local names. Input must already be lower case. */
    fun validDomain(d0: String): String? {
        val d = d0.trimEnd('.')
        if (d.isEmpty() || d.length > 253 || d[0] == '.' || d[0] == '-' || !d.contains('.') || d in SELF_ENTRIES) return null
        var allDigits = true
        var prev = ' '
        for (ch in d) {
            val ok = ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.'
            if (!ok) return null
            if (ch == '.' && prev == '.') return null
            if (ch != '.' && !ch.isDigit()) allDigits = false
            prev = ch
        }
        return if (allDigits) null else d
    }

    // ---- Allowlist ----

    /** Cleans up a typed allowlist entry ("https://www.Example.com/x" -> "www.example.com"); null if not a domain. */
    fun normalizeDomain(input: String): String? {
        var t = input.trim().lowercase()
        t = t.substringAfter("://")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':')
        t = t.removePrefix("||").removeSuffix("^").removePrefix("*.").removePrefix(".")
        return validDomain(t)
    }

    /** Blocked unless [host] or a parent domain is allowlisted; an allow entry wins over a more specific block entry. */
    fun isBlocked(host: String?, blocked: Set<String>, allowed: Set<String>): Boolean =
        HostRules.isBlocked(host, blocked) && !HostRules.isBlocked(host, allowed)
}
