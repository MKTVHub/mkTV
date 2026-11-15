package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class PlayerUiTest {
    @Test fun seekStepAccelerates() {
        assertEquals(10_000L, PlayerUi.seekStep(0))
        assertEquals(10_000L, PlayerUi.seekStep(999))
        assertEquals(30_000L, PlayerUi.seekStep(1_000))
        assertEquals(30_000L, PlayerUi.seekStep(2_999))
        assertEquals(60_000L, PlayerUi.seekStep(3_000))
        assertEquals(60_000L, PlayerUi.seekStep(60_000))
    }

    @Test fun seekTargetClamps() {
        assertEquals(15_000L, PlayerUi.seekTarget(5_000, 10_000, 60_000))
        assertEquals(0L, PlayerUi.seekTarget(5_000, -10_000, 60_000))
        assertEquals(60_000L, PlayerUi.seekTarget(55_000, 10_000, 60_000))
        assertEquals(65_000L, PlayerUi.seekTarget(55_000, 10_000, 0)) // unknown length: no upper limit
    }

    @Test fun clockAndDelta() {
        assertEquals("0:00", PlayerUi.clock(-5))
        assertEquals("4:05", PlayerUi.clock(245_000))
        assertEquals("1:02:03", PlayerUi.clock(3_723_000))
        assertEquals("+1:30", PlayerUi.delta(90_000))
        assertEquals("−0:10", PlayerUi.delta(-10_000))
    }

    @Test fun endsAtFollowsClockSettingAndSpeed() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 20L * 3_600_000 // 20:00 UTC
        val left = 102L * 60_000 // 1 h 42 min
        assertEquals("Ends at 9:42 PM", PlayerUi.endsAt(now, left, 1.0, false, Locale.US, utc))
        assertEquals("Ends at 21:42", PlayerUi.endsAt(now, left, 1.0, true, Locale.US, utc))
        assertEquals("Ends at 21:08", PlayerUi.endsAt(now, left, 1.5, true, Locale.US, utc)) // 68 min at 1.5×
        assertNull(PlayerUi.endsAt(now, -1, 1.0, true, Locale.US, utc))
        assertNull(PlayerUi.endsAt(now, left, 0.0, true, Locale.US, utc))
        assertEquals("8:05 AM", PlayerUi.timeOfDay(8L * 3_600_000 + 5 * 60_000, false, Locale.US, utc))
        assertEquals("0:05", PlayerUi.timeOfDay(5L * 60_000, true, Locale.US, utc))
    }

    @Test fun titleFallsBack() {
        assertEquals("My video", PlayerUi.title(" My video ", "https://cdn.example.com/master.m3u8"))
        assertEquals("example.com", PlayerUi.title(null, "https://www.example.com/hls/master.m3u8"))
        assertEquals("example.com", PlayerUi.title("https://example.com/watch", "https://example.com/a.m3u8"))
        assertEquals("Holiday clip.mp4", PlayerUi.title("", "file:///sdcard/Download/Holiday%20clip.mp4"))
        assertEquals("Video", PlayerUi.title(null, "content://downloads/all_downloads/1234"))
    }
}
