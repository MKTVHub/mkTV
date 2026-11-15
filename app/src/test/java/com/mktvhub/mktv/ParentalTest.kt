package com.mktvhub.mktv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class ParentalTest {
    // ---- PIN hashing ----

    @Test fun pbkdf2MatchesTheJvmImplementation() {
        val salt = ByteArray(16) { it.toByte() }
        for ((iterations, bytes) in listOf(1 to 32, 2 to 32, 1000 to 32, 3 to 48)) {
            val jvm = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("1234".toCharArray(), salt, iterations, bytes * 8)).encoded
            assertArrayEquals("iterations=$iterations bytes=$bytes", jvm, Parental.pbkdf2("1234", salt, iterations, bytes))
        }
    }

    @Test fun recordVerifiesOnlyTheRightPin() {
        val rec = Parental.record("4821")
        assertTrue(Parental.verify("4821", rec))
        assertFalse(Parental.verify("4822", rec))
        assertFalse(Parental.verify("482", rec))
        assertFalse(Parental.verify("", rec))
    }

    @Test fun recordNeverContainsThePinAndIsSalted() {
        val a = Parental.record("0000")
        val b = Parental.record("0000")
        assertFalse(a.contains("0000:") || a.endsWith("0000"))
        assertNotEquals(a, b) // random salt each time
        assertTrue(a.startsWith("pbkdf2-sha256:${Parental.ITERATIONS}:"))
        assertTrue(Parental.verify("0000", a) && Parental.verify("0000", b))
    }

    @Test fun damagedRecordsMatchNothing() {
        assertFalse(Parental.verify("1234", null))
        assertFalse(Parental.verify("1234", ""))
        assertFalse(Parental.verify("1234", "sha1:1:00:00"))
        assertFalse(Parental.verify("1234", "pbkdf2-sha256:x:00:00"))
        assertFalse(Parental.verify("1234", "pbkdf2-sha256:10:0g:00"))
        assertFalse(Parental.verify("1234", "pbkdf2-sha256:10:000:00"))
        assertFalse(Parental.verify("1234", "pbkdf2-sha256:10:00:"))
    }

    @Test fun validPinIsFourDigits() {
        assertTrue(Parental.validPin("0123"))
        assertFalse(Parental.validPin("123"))
        assertFalse(Parental.validPin("12345"))
        assertFalse(Parental.validPin("12a4"))
    }

    // ---- Lockout and unlock window ----

    @Test fun fifthWrongTryLocksForThirtySeconds() {
        var s = Parental.Lockout()
        repeat(4) { s = Parental.afterWrong(s, 1000) }
        assertEquals(4, s.fails)
        assertEquals(1, Parental.triesLeft(s))
        assertEquals(0, Parental.lockedFor(s, 1000))
        s = Parental.afterWrong(s, 1000)
        assertEquals(0, s.fails)
        assertEquals(30_000, Parental.lockedFor(s, 1000))
        assertEquals(10_000, Parental.lockedFor(s, 21_000))
        assertEquals(0, Parental.lockedFor(s, 31_000))
        assertEquals(Parental.MAX_TRIES, Parental.triesLeft(s)) // a fresh five tries after the wait
    }

    @Test fun clockSetBackwardsNeverLocksLongerThanTheWait() {
        val s = Parental.Lockout(0, until = 1_000_000)
        assertEquals(Parental.LOCKOUT_MS, Parental.lockedFor(s, 0))
    }

    @Test fun unlockLastsFiveMinutes() {
        assertFalse(Parental.stillUnlocked(0, 5000))
        assertTrue(Parental.stillUnlocked(1000, 1000))
        assertTrue(Parental.stillUnlocked(1000, 1000 + Parental.UNLOCK_MS - 1))
        assertFalse(Parental.stillUnlocked(1000, 1000 + Parental.UNLOCK_MS))
        assertFalse(Parental.stillUnlocked(5000, 1000)) // clock went backwards (reboot)
    }

    // ---- Blocked sites ----

    @Test fun blockedSitesIncludeSubdomains() {
        val sites = setOf("example.com")
        assertTrue(Parental.isBlocked("example.com", sites))
        assertTrue(Parental.isBlocked("www.example.com", sites))
        assertTrue(Parental.isBlocked("a.b.Example.com", sites))
        assertFalse(Parental.isBlocked("notexample.com", sites))
        assertFalse(Parental.isBlocked("example.com.evil.net", sites))
        assertFalse(Parental.isBlocked(null, sites))
        assertFalse(Parental.isBlocked("example.com", emptySet()))
    }

    @Test fun normalizeSiteCleansInput() {
        assertEquals("example.com", Parental.normalizeSite("https://www.Example.com/path?q=1"))
        assertEquals("example.com", Parental.normalizeSite(" example.com "))
        assertEquals("video.example.co.uk", Parental.normalizeSite("video.example.co.uk"))
        assertEquals("www.com", Parental.normalizeSite("www.com"))
        assertNull(Parental.normalizeSite("not a domain"))
        assertNull(Parental.normalizeSite("localhost"))
    }

    @Test fun blockedPageEscapesTheHost() {
        val page = Parental.blockedPage("<b>x")
        assertTrue(page.contains("Blocked by parental controls"))
        assertTrue(page.contains("&lt;b&gt;x"))
        assertFalse(page.contains("<b>x"))
    }

    // ---- SafeSearch ----

    @Test fun googleSearchGetsSafeActive() {
        assertEquals("https://www.google.com/search?q=cats&safe=active", Parental.safeSearch("https://www.google.com/search?q=cats"))
        assertEquals("https://www.google.co.uk/search?q=cats&safe=active", Parental.safeSearch("https://www.google.co.uk/search?q=cats"))
        assertEquals("https://google.com.au/search?q=a&safe=active#top", Parental.safeSearch("https://google.com.au/search?q=a#top"))
        assertEquals("https://www.google.com/search?q=cats&tbm=isch&safe=active",
            Parental.safeSearch("https://www.google.com/search?q=cats&safe=off&tbm=isch"))
    }

    @Test fun bingAndDuckDuckGoGetTheirStrictParams() {
        assertEquals("https://www.bing.com/search?q=cats&adlt=strict", Parental.safeSearch("https://www.bing.com/search?q=cats"))
        assertEquals("https://www.bing.com/images/search?q=cats&adlt=strict", Parental.safeSearch("https://www.bing.com/images/search?q=cats&adlt=off"))
        assertEquals("https://duckduckgo.com/?q=cats&kp=1", Parental.safeSearch("https://duckduckgo.com/?q=cats"))
        assertEquals("https://duckduckgo.com/?q=cats&kp=1", Parental.safeSearch("https://duckduckgo.com/?kp=-2&q=cats"))
        assertEquals("https://html.duckduckgo.com/html/?q=cats&kp=1", Parental.safeSearch("https://html.duckduckgo.com/html/?q=cats"))
    }

    @Test fun safeSearchIsIdempotent() {
        listOf(
            "https://www.google.com/search?q=cats&safe=active",
            "https://www.bing.com/search?q=cats&adlt=strict",
            "https://duckduckgo.com/?kp=1&q=cats",
        ).forEach { assertEquals(it, Parental.safeSearch(it)); assertEquals(Parental.safeSearch(it), Parental.safeSearch(Parental.safeSearch(it))) }
    }

    @Test fun otherAddressesAreLeftAlone() {
        listOf(
            "https://www.google.com/",
            "https://maps.google.com/search?q=cats",
            "https://www.google.com/maps?q=cats",
            "https://www.bing.com/maps?q=cats",
            "https://duckduckgo.com/",
            "https://duckduckgo.com/?q=",
            "https://search.brave.com/search?q=cats",
            "https://example.com/search?q=cats",
            "https://notgoogle.com/search?q=cats",
            "about:blank",
            "data:text/html,x",
        ).forEach { assertEquals(it, Parental.safeSearch(it)) }
    }

    @Test fun webSearchUrlsAreRewritten() {
        for (id in listOf("google", "bing", "duckduckgo")) {
            val url = WebSearch.searchUrl("red pandas", WebSearch.engine(id))
            assertNotEquals(id, url, Parental.safeSearch(url))
            assertTrue(Parental.safeSearch(url).contains("red%20pandas"))
        }
    }
}
