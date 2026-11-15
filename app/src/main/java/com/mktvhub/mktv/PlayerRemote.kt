package com.mktvhub.mktv

import org.json.JSONObject

/**
 * Phone remote while the native player ([PlayerActivity]) is in front (pure, tested in PlayerRemoteTest).
 * The one [RemoteServer] stays the browser's (same port and pairing code); the browser hands keys and
 * media actions to the player and reports the player's video as Now playing.
 */
object PlayerRemote {
    enum class Target { PLAYER, BROWSER, NONE }

    /**
     * Seek, speed and captions act on the player. The sleep timer stays the browser's: it already pauses
     * the player when it fires. Full screen has nothing to do (the player is full screen).
     */
    fun media(action: String): Target = when (action) {
        "seek", "speed", "captions" -> Target.PLAYER
        "sleep" -> Target.BROWSER
        else -> Target.NONE
    }

    /** The phone's seek bar position (seconds) as a player position, kept inside the stream. */
    fun seekMs(seconds: Double, durationMs: Long): Long = PlayerUi.seekTarget(0, (seconds * 1000).toLong(), durationMs)

    /** Now playing for the phone, in the same shape as the page's `__sp.status()`. Live streams have no length (no seek bar). */
    fun status(playing: Boolean, positionMs: Long, durationMs: Long, live: Boolean, speed: Double, captions: Boolean, captionsOn: Boolean): JSONObject =
        JSONObject().put("has", true).put("playing", playing)
            .put("t", positionMs.coerceAtLeast(0) / 1000.0)
            .put("d", if (live || durationMs <= 0) 0.0 else durationMs / 1000.0)
            .put("cc", captions).put("ccOn", captions && captionsOn)
            .put("speed", speed).put("fs", true)
}

/**
 * Tracks mkTV's started activities (by identity), so the phone remote keeps listening while the native
 * player covers the browser and stops only when mkTV really leaves the screen. Opening the player starts
 * it before the browser stops (Android's order), so the set is never empty on the way. A stop for an
 * activity started before tracking began (a player restored before the browser) is ignored. Main thread only.
 */
class ForegroundCounter(private val onForeground: () -> Unit, private val onBackground: () -> Unit) {
    private val started = HashSet<Int>()
    val count get() = started.size
    val visible get() = started.isNotEmpty()

    fun activityStarted(id: Int) {
        if (started.add(id) && started.size == 1) onForeground()
    }

    fun activityStopped(id: Int) {
        if (started.remove(id) && started.isEmpty()) onBackground()
    }
}
