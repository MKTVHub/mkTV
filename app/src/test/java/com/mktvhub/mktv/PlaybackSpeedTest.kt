package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedTest {
    @Test fun exactOnlyAcceptsOfferedLevels() {
        assertEquals(1.25, PlaybackSpeed.exact(1.25)!!, 0.0)
        assertEquals(2.0, PlaybackSpeed.exact(2.0)!!, 0.0)
        assertNull(PlaybackSpeed.exact(1.3))
        assertNull(PlaybackSpeed.exact(Double.NaN))
    }

    @Test fun snapAndNext() {
        assertEquals(1.0, PlaybackSpeed.snap(1.1), 0.0)
        assertEquals(2.0, PlaybackSpeed.snap(16.0), 0.0)
        assertEquals(1.0, PlaybackSpeed.snap(Double.NaN), 0.0)
        assertEquals(1.25, PlaybackSpeed.next(1.0), 0.0)
        assertEquals(0.75, PlaybackSpeed.next(2.0), 0.0) // wraps
    }

    @Test fun labels() {
        assertEquals(listOf("0.75×", "1×", "1.25×", "1.5×", "2×"), PlaybackSpeed.LEVELS.map { PlaybackSpeed.label(it) })
    }

    @Test fun perSiteStorage() {
        var sites = PlaybackSpeed.withSpeed(emptyMap(), "www.Example.com", 1.5)
        assertEquals(mapOf("example.com" to 1.5), sites)
        assertEquals(1.5, PlaybackSpeed.speedFor("example.com", sites), 0.0)
        assertEquals(1.0, PlaybackSpeed.speedFor("other.org", sites), 0.0)
        assertEquals(1.0, PlaybackSpeed.speedFor(null, sites), 0.0)
        sites = PlaybackSpeed.withSpeed(sites, "example.com", 1.0) // 1× removes the entry
        assertTrue(sites.isEmpty())
        assertEquals(emptyMap<String, Double>(), PlaybackSpeed.withSpeed(emptyMap(), null, 2.0))
    }

    @Test fun parseSkipsBadEntries() {
        val parsed = PlaybackSpeed.parse(setOf("a.com=1.25", "b.com=9", "=1.5", "c.com=x", "www.d.com=0.75"))
        assertEquals(mapOf("a.com" to 1.25, "d.com" to 0.75), parsed)
        assertEquals(parsed, PlaybackSpeed.parse(PlaybackSpeed.serialize(parsed)))
    }

    @Test fun sleepChoices() {
        assertEquals(0, PlaybackSpeed.sleepChoice(0.0))
        assertEquals(-1, PlaybackSpeed.sleepChoice(-1.0))
        assertEquals(30, PlaybackSpeed.sleepChoice(30.0))
        assertNull(PlaybackSpeed.sleepChoice(20.0))
        assertNull(PlaybackSpeed.sleepChoice(15.2))
        assertNull(PlaybackSpeed.sleepChoice(Double.NaN))
        assertEquals("Off", PlaybackSpeed.sleepLabel(0))
        assertEquals("15 min", PlaybackSpeed.sleepLabel(15))
        assertEquals("End of video", PlaybackSpeed.sleepLabel(-1))
    }

    @Test fun remainingTime() {
        assertEquals("15:00", PlaybackSpeed.remaining(15 * 60_000L))
        assertEquals("0:01", PlaybackSpeed.remaining(1)) // rounds up, never 0:00 while running
        assertEquals("0:00", PlaybackSpeed.remaining(-5))
        assertEquals("1:00:00", PlaybackSpeed.remaining(3_600_000L))
        assertEquals("14:59", PlaybackSpeed.remaining(14 * 60_000L + 58_500))
    }
}
