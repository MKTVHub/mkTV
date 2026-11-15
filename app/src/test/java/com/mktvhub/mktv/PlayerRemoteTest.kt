package com.mktvhub.mktv

import com.mktvhub.mktv.PlayerRemote.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRemoteTest {
    @Test fun mediaActionsGoToThePlayerExceptTheSleepTimer() {
        assertEquals(Target.PLAYER, PlayerRemote.media("seek"))
        assertEquals(Target.PLAYER, PlayerRemote.media("speed"))
        assertEquals(Target.PLAYER, PlayerRemote.media("captions"))
        assertEquals(Target.BROWSER, PlayerRemote.media("sleep"))
        assertEquals(Target.NONE, PlayerRemote.media("fullscreen"))
        assertEquals(Target.NONE, PlayerRemote.media("nonsense"))
        // Every action the server accepts has a route.
        RemoteInput.MEDIA.forEach { PlayerRemote.media(it) }
    }

    @Test fun seekBarPositionIsClampedToTheStream() {
        assertEquals(90_500L, PlayerRemote.seekMs(90.5, 600_000))
        assertEquals(600_000L, PlayerRemote.seekMs(900.0, 600_000))
        assertEquals(0L, PlayerRemote.seekMs(-5.0, 600_000))
        // Unknown length: only the start is a limit.
        assertEquals(900_000L, PlayerRemote.seekMs(900.0, -1))
    }

    @Test fun statusMatchesThePageShape() {
        val s = PlayerRemote.status(playing = true, positionMs = 61_500, durationMs = 3_600_000, live = false, speed = 1.25, captions = true, captionsOn = true)
        assertTrue(s.getBoolean("has"))
        assertTrue(s.getBoolean("playing"))
        assertEquals(61.5, s.getDouble("t"), 1e-9)
        assertEquals(3600.0, s.getDouble("d"), 1e-9)
        assertEquals(1.25, s.getDouble("speed"), 1e-9)
        assertTrue(s.getBoolean("cc"))
        assertTrue(s.getBoolean("ccOn"))
        assertTrue(s.getBoolean("fs"))
    }

    @Test fun liveOrUnknownLengthHasNoSeekBar() {
        assertEquals(0.0, PlayerRemote.status(true, 5_000, 3_600_000, live = true, speed = 1.0, captions = false, captionsOn = false).getDouble("d"), 0.0)
        assertEquals(0.0, PlayerRemote.status(false, 5_000, -9, live = false, speed = 1.0, captions = false, captionsOn = false).getDouble("d"), 0.0)
        // Captions can't be "on" without a track, and a negative position reads as the start.
        val s = PlayerRemote.status(false, -1, 0, live = false, speed = 1.0, captions = false, captionsOn = true)
        assertFalse(s.getBoolean("ccOn"))
        assertEquals(0.0, s.getDouble("t"), 0.0)
    }

    private val browser = 1
    private val player = 2

    /** Browser starts, player opens over it (started before the browser stops), player closes, then TV Home. */
    @Test fun serverRunsAcrossThePlayerAndStopsWhenMkTvLeaves() {
        val events = mutableListOf<String>()
        val c = ForegroundCounter({ events += "start" }, { events += "stop" })
        c.activityStarted(browser)
        c.activityStarted(player); c.activityStopped(browser)
        assertTrue(c.visible)
        c.activityStarted(browser); c.activityStopped(player) // Back from the player
        assertEquals(listOf("start"), events)
        c.activityStopped(browser)                            // TV Home
        assertFalse(c.visible)
        assertEquals(listOf("start", "stop"), events)
    }

    /** TV Home while the player is up stops the server; coming back to the player (browser stays stopped) starts it. */
    @Test fun leavingAndReturningFromThePlayer() {
        val events = mutableListOf<String>()
        val c = ForegroundCounter({ events += "start" }, { events += "stop" })
        c.activityStarted(browser); c.activityStarted(player); c.activityStopped(browser)
        c.activityStopped(player) // TV Home from the player
        c.activityStarted(player) // back to the player from recents
        assertEquals(listOf("start", "stop", "start"), events)
        assertEquals(1, c.count)
    }

    /** Process restored with only the player: Back creates the browser; the uncounted player's stop must not stop the server. */
    @Test fun stopOfAnActivityStartedBeforeCountingIsIgnored() {
        val events = mutableListOf<String>()
        val c = ForegroundCounter({ events += "start" }, { events += "stop" })
        c.activityStarted(browser); c.activityStopped(player)
        assertTrue(c.visible)
        assertEquals(listOf("start"), events)
        // A repeated start of the same activity doesn't count twice.
        c.activityStarted(browser); c.activityStopped(browser)
        assertEquals(listOf("start", "stop"), events)
    }
}
