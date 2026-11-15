package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterListsTest {
    private fun parse(text: String) = FilterLists.parse(text)

    @Test fun hostsFormat() {
        val text = """
            # Title: test
            127.0.0.1 localhost
            127.0.0.1 localhost.localdomain
            ::1 ip6-localhost
            fe80::1%lo0 localhost
            0.0.0.0 0.0.0.0
            255.255.255.255 broadcasthost
            0.0.0.0 ads.foo.com   # trailing comment
            127.0.0.1	tab.example.net
            0.0.0.0 Tracker.Bar.NET.
            0.0.0.0 a.one.com b.two.com
            :: v6.example.org
        """.trimIndent()
        assertEquals(setOf("ads.foo.com", "tab.example.net", "tracker.bar.net", "a.one.com", "b.two.com", "v6.example.org"), parse(text))
    }

    @Test fun plainDomainsAndWildcards() {
        val text = "﻿ads.example.com\n*.wild.example.org\nexample.co.uk\n\n  spaced.example.com  \n1.2.3.4\nlocalhost\nnodot\n"
        assertEquals(setOf("ads.example.com", "wild.example.org", "example.co.uk", "spaced.example.com"), parse(text))
    }

    @Test fun adblockRules() {
        val text = """
            [Adblock Plus 2.0]
            ! Title: test list
            ||ads.example.com^
            ||Track.Example.NET^
            ||opts.example.com^${'$'}third-party
            ||path.example.com/banner^
            ||wild*.example.com^
            |https://x.example.com/ad.js
            @@||allowed.example.com^
            example.com##.ad-banner
            ##.global-ad
            example.org#@#.ad
            example.net#?#div:has(.ad)
            /banner/*/ad.js
            .ads.
        """.trimIndent()
        assertEquals(setOf("ads.example.com", "track.example.net"), parse(text))
    }

    @Test fun rejectsJunk() {
        // An HTML error page or captive portal must not turn into block entries.
        val html = "<!DOCTYPE html>\n<html><head><title>Login</title></head>\n<body>Please sign in</body></html>\n"
        assertTrue(parse(html).isEmpty())
        assertNull(FilterLists.validDomain("bad..example.com"))
        assertNull(FilterLists.validDomain(".example.com"))
        assertNull(FilterLists.validDomain("-x.example.com"))
        assertNull(FilterLists.validDomain("exa mple.com"))
        assertNull(FilterLists.validDomain("ex\$ample.com"))
        assertNull(FilterLists.validDomain("a".repeat(250) + ".com"))
        assertNull(FilterLists.validDomain("10.0.0.1"))
        assertEquals("under_score.example.com", FilterLists.validDomain("under_score.example.com"))
        assertEquals("xn--bcher-kva.example", FilterLists.validDomain("xn--bcher-kva.example"))
    }

    @Test fun dedupesAcrossListsAndCaps() {
        val into = HashSet<String>()
        assertEquals(3, FilterLists.parse(sequenceOf("a.com", "b.com", "c.com"), into))
        assertEquals(2, FilterLists.parse(sequenceOf("0.0.0.0 b.com", "||d.com^"), into))
        assertEquals(setOf("a.com", "b.com", "c.com", "d.com"), into)
        val capped = HashSet<String>()
        FilterLists.parse(sequenceOf("a.com", "b.com", "c.com", "d.com"), capped, max = 2)
        assertEquals(2, capped.size)
        assertEquals(3, FilterLists.count(sequenceOf("a.com", "# x", "0.0.0.0 b.com c.com", "junk line here")))
    }

    @Test fun hostRulesParseHostsStillWorks() {
        assertEquals(setOf("doubleclick.net"), HostRules.parseHosts("# c\ndoubleclick.net\n"))
    }

    @Test fun allowlistBeatsBlocklist() {
        val blocked = setOf("ads.example.com", "tracker.net", "cdn.shop.com")
        assertTrue(FilterLists.isBlocked("ads.example.com", blocked, emptySet()))
        assertFalse(FilterLists.isBlocked("ads.example.com", blocked, setOf("example.com")))
        assertFalse(FilterLists.isBlocked("x.ads.example.com", blocked, setOf("ads.example.com")))
        // Allowing a subdomain doesn't unblock its parent or siblings.
        assertTrue(FilterLists.isBlocked("cdn.shop.com", blocked, setOf("img.cdn.shop.com")))
        assertFalse(FilterLists.isBlocked("img.cdn.shop.com", blocked, setOf("img.cdn.shop.com")))
        assertTrue(FilterLists.isBlocked("a.tracker.net", blocked, setOf("nottracker.net")))
        assertFalse(FilterLists.isBlocked("Tracker.NET.", blocked, setOf("tracker.net")))
        assertFalse(FilterLists.isBlocked("free.com", blocked, emptySet()))
        assertFalse(FilterLists.isBlocked(null, blocked, emptySet()))
    }

    @Test fun normalizesAllowlistInput() {
        assertEquals("www.example.com", FilterLists.normalizeDomain("https://www.Example.com/path?q=1"))
        assertEquals("example.com", FilterLists.normalizeDomain("  example.com:8080 "))
        assertEquals("example.com", FilterLists.normalizeDomain("*.example.com"))
        assertEquals("example.com", FilterLists.normalizeDomain("||example.com^"))
        assertEquals("example.com", FilterLists.normalizeDomain("user@example.com"))
        assertNull(FilterLists.normalizeDomain("not a domain"))
        assertNull(FilterLists.normalizeDomain("com"))
        assertNull(FilterLists.normalizeDomain(""))
    }

    @Test fun normalizesListUrls() {
        assertEquals("https://example.com/hosts.txt", FilterLists.normalizeListUrl(" example.com/hosts.txt "))
        assertEquals("https://example.com/hosts", FilterLists.normalizeListUrl("HTTPS://example.com/hosts"))
        assertEquals("http://lists.example.org/a?b=1", FilterLists.normalizeListUrl("http://lists.example.org/a?b=1"))
        assertNull(FilterLists.normalizeListUrl("ftp://example.com/hosts"))
        assertNull(FilterLists.normalizeListUrl("javascript:alert(1)"))
        assertNull(FilterLists.normalizeListUrl("https://"))
        assertNull(FilterLists.normalizeListUrl("two words"))
        assertNull(FilterLists.normalizeListUrl(""))
    }

    @Test fun namesAndIds() {
        assertEquals("example.com/hosts.txt", FilterLists.nameFor("https://example.com/lists/hosts.txt?x=1"))
        assertEquals("example.com", FilterLists.nameFor("https://example.com/"))
        val id = FilterLists.customId("https://example.com/a")
        assertTrue(id.matches(Regex("c-[0-9a-f]{16}")))
        assertEquals(id, FilterLists.customId("https://example.com/a"))
        assertNotEquals(id, FilterLists.customId("https://example.com/b"))
    }

    @Test fun presetsAndSources() {
        assertEquals(FilterLists.PRESETS.size, FilterLists.PRESETS.map { it.id }.toSet().size)
        assertEquals(listOf("sb-unified"), FilterLists.PRESETS.filter { it.defaultOn }.map { it.id })
        FilterLists.PRESETS.forEach { assertTrue(it.url, it.url.startsWith("https://")); assertTrue(it.licence.isNotBlank()) }
        val url = "https://example.com/mine.txt"
        val custom = listOf("mine" to url, "off" to "https://example.com/off.txt")
        val src = FilterLists.sources(setOf("oisd-small", "sb-unified", FilterLists.customId(url)), custom)
        assertEquals(listOf("sb-unified", "oisd-small", FilterLists.customId(url)), src.map { it.id })
        assertTrue(src.last().custom)
        assertFalse(src.first().custom)
    }
}
