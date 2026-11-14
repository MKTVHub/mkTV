package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRulesTest {
    private val blocked = setOf("doubleclick.net", "ads.example.com")

    @Test fun matchesExactAndSubdomains() {
        assertTrue(HostRules.isBlocked("doubleclick.net", blocked))
        assertTrue(HostRules.isBlocked("stats.g.doubleclick.net", blocked))
        assertTrue(HostRules.isBlocked("x.ads.example.com", blocked))
        assertTrue(HostRules.isBlocked("ADS.Example.com.", blocked))
    }

    @Test fun doesNotOverMatch() {
        assertFalse(HostRules.isBlocked("example.com", blocked))
        assertFalse(HostRules.isBlocked("notdoubleclick.net", blocked))
        assertFalse(HostRules.isBlocked(null, blocked))
        assertFalse(HostRules.isBlocked("net", setOf("net")))
    }

    @Test fun siteOf() {
        assertEquals("example.com", HostRules.siteOf("a.b.example.com"))
        assertEquals("example.co.uk", HostRules.siteOf("www.example.co.uk"))
        assertEquals("example.com.au", HostRules.siteOf("m.example.com.au"))
        assertEquals("example.de", HostRules.siteOf("www.example.de"))
        assertEquals("example.com", HostRules.siteOf("example.com"))
        assertTrue(HostRules.sameSite("www.example.com", "cdn.example.com"))
        assertFalse(HostRules.sameSite("example.com", "other.com"))
    }

    @Test fun parsesHostsFile() {
        val text = """
            # comment
            127.0.0.1 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.foo.com   # trailing comment
            0.0.0.0 Tracker.Bar.NET
            plain.example.org
            1.2.3.4
        """.trimIndent()
        assertEquals(setOf("ads.foo.com", "tracker.bar.net", "plain.example.org"), HostRules.parseHosts(text))
    }

    @Test fun detectsStreams() {
        assertTrue(HostRules.isStreamUrl("https://cdn.x.com/live/index.m3u8"))
        assertTrue(HostRules.isStreamUrl("https://cdn.x.com/live/index.m3u8?token=1"))
        assertTrue(HostRules.isStreamUrl("https://cdn.x.com/v/manifest.MPD"))
        assertFalse(HostRules.isStreamUrl("https://cdn.x.com/v/seg001.ts"))
        assertFalse(HostRules.isStreamUrl("https://x.com/page?file=m3u8"))
    }

    @Test fun detectsVariants() {
        val master = "https://cdn.x.com/v1/master.m3u8?t=1"
        assertTrue(HostRules.isVariantOf("https://cdn.x.com/v1/url_0/hd.m3u8", master))
        assertFalse(HostRules.isVariantOf("https://cdn.x.com/v2/master.m3u8", master))
        assertFalse(HostRules.isVariantOf(master, master))
    }

    @Test fun stripsTracking() {
        assertEquals("https://x.com/a?id=5", HostRules.stripTracking("https://x.com/a?utm_source=t&id=5&fbclid=abc"))
        assertEquals("https://x.com/a#top", HostRules.stripTracking("https://x.com/a?gclid=1#top"))
        assertEquals("https://youtu.be/abc", HostRules.stripTracking("https://youtu.be/abc?si=XYZ"))
        assertEquals("https://www.youtube.com/watch?v=1", HostRules.stripTracking("https://www.youtube.com/watch?v=1&si=q"))
        assertEquals("https://other.com/?si=keep", HostRules.stripTracking("https://other.com/?si=keep"))
        val same = "https://x.com/a?q=1&page=2"
        assertTrue(HostRules.stripTracking(same) === same)
    }
}
