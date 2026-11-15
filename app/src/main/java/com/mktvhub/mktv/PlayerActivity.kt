package com.mktvhub.mktv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Full-screen native player for HLS / DASH streams found on a page and for downloaded files.
 * Our own remote-first overlay (activity_player.xml) replaces Media3's controller: the bar has focus,
 * ◀/▶ seek (faster while held), OK plays/pauses, ▼ reaches the buttons, Back hides the overlay, then exits.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var screen: View
    private lateinit var overlay: View
    private lateinit var bar: PlayerBar
    private lateinit var message: TextView
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var ends: TextView
    private lateinit var clockView: TextView
    private lateinit var playButton: ImageButton
    private lateinit var nextButton: View
    private lateinit var captionsButton: View
    private lateinit var captionsLabel: TextView
    private lateinit var speedLabel: TextView
    /** Continue watching: the page this stream came from, or null when it mustn't be remembered (private, setting off, a file). */
    private var pageUrl: String? = null
    private var pageTitle = ""
    private var failed = false
    private var overlayOn = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() { savePosition(); handler.postDelayed(this, 10_000) }
    }
    private val uiTick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 500) }
    }
    private val hideOverlay = Runnable { setOverlay(false) }

    // Held ◀/▶ seek: direction (0 = none), where it will land, and when the preview last moved.
    private var seekDir = 0
    private var seekTarget = 0L
    private var lastStep = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val local = !url.startsWith("http", ignoreCase = true)
        pageUrl = intent.getStringExtra(EXTRA_PAGE_URL)?.takeIf { !local && intent.getBooleanExtra(EXTRA_REMEMBER, false) && it.startsWith("http") }
        pageTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val headers = buildMap {
            intent.getStringExtra(EXTRA_REFERER)?.let { put("Referer", it) }
            intent.getStringExtra(EXTRA_ORIGIN)?.let { put("Origin", it) }
            intent.getStringExtra(EXTRA_COOKIE)?.let { put("Cookie", it) }
        }
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)
            .apply { intent.getStringExtra(EXTRA_USER_AGENT)?.let { setUserAgent(it) } }

        val exo = ExoPlayer.Builder(this)
            // DefaultDataSource: http(s) through [http], plus content:// for downloaded files opened from Downloads.
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(this, http)))
            .build()
        player = exo
        // The page's speed for this site (watch mode controls); the speed button here changes it for this video only.
        exo.setPlaybackSpeed((PlaybackSpeed.exact(intent.getDoubleExtra(EXTRA_SPEED, PlaybackSpeed.DEFAULT)) ?: PlaybackSpeed.DEFAULT).toFloat())

        setContentView(R.layout.activity_player)
        screen = findViewById(R.id.playerRoot)
        overlay = findViewById(R.id.playerOverlay)
        bar = findViewById(R.id.playerBar)
        message = findViewById(R.id.playerMessage)
        elapsed = findViewById(R.id.playerElapsed)
        total = findViewById(R.id.playerTotal)
        ends = findViewById(R.id.playerEnds)
        clockView = findViewById(R.id.playerClock)
        playButton = findViewById(R.id.playerPlay)
        nextButton = findViewById(R.id.playerNext)
        captionsButton = findViewById(R.id.playerCaptions)
        captionsLabel = findViewById(R.id.playerCaptionsLabel)
        speedLabel = findViewById(R.id.playerSpeedLabel)
        findViewById<PlayerView>(R.id.playerView).apply {
            this.player = exo
            // Media3's controller is replaced by the overlay; its buffering spinner and subtitles stay.
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        findViewById<TextView>(R.id.playerTitle).text = PlayerUi.title(pageTitle, url)
        findViewById<TextView>(R.id.playerSite).text = (intent.getStringExtra(EXTRA_PAGE_URL) ?: url)
            .takeIf { it.startsWith("http") }?.let { android.net.Uri.parse(it).host?.removePrefix("www.") }.orEmpty()

        playButton.setOnClickListener { togglePlay() }
        findViewById<View>(R.id.playerRewind).setOnClickListener { seekBy(-PlayerUi.TAP_MS) }
        findViewById<View>(R.id.playerForward).setOnClickListener { seekBy(PlayerUi.TAP_MS) }
        nextButton.setOnClickListener { player?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem() }
        captionsButton.setOnClickListener { nextCaptions() }
        findViewById<View>(R.id.playerSpeed).setOnClickListener {
            exo.setPlaybackSpeed(PlaybackSpeed.next(exo.playbackParameters.speed.toDouble()).toFloat())
            refresh()
        }
        // Focus: the white ring plus a slight grow, so it reads from the sofa.
        for (id in intArrayOf(R.id.playerRewind, R.id.playerPlay, R.id.playerForward, R.id.playerNext, R.id.playerCaptions, R.id.playerSpeed)) {
            findViewById<View>(id).setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(120).start() }
        }

        exo.addListener(object : Player.Listener {
            private var resumeChecked = false
            override fun onPlaybackStateChanged(state: Int) {
                // Sleep timer "End of video": say why it stopped (a single stream has no autoplay to stop).
                if (state == Player.STATE_ENDED && sleepAtEnd) { sleepAtEnd = false; sleepPause() }
                if (state == Player.STATE_ENDED) setOverlay(true)
                // Once the length is known: pick up where this page's video was left (◀ goes back).
                if (state != Player.STATE_READY || resumeChecked) return
                resumeChecked = true
                val page = pageUrl ?: return
                val dur = exo.duration
                if (dur == C.TIME_UNSET || exo.isCurrentMediaItemLive) return
                val saved = ContinueWatching.find(Prefs(this@PlayerActivity).continueWatching, page) ?: return
                if (!ContinueWatching.shouldOffer(saved, exo.currentPosition / 1000.0, dur / 1000.0)) return
                exo.seekTo((saved.position * 1000).toLong())
                showMessage("Resumed from ${ContinueWatching.clock(saved.position)}")
                handler.postDelayed({ if (message.text.startsWith("Resumed")) message.visibility = View.GONE }, 3_000)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                refresh()
                // Paused: keep the overlay up. Playing again: let it hide after a while.
                if (isPlaying) poke() else handler.removeCallbacks(hideOverlay)
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
            override fun onTracksChanged(tracks: Tracks) = refresh()
            override fun onPlayerError(e: PlaybackException) {
                failed = true
                showMessage(if (local) "Can't play this file\n${e.errorCodeName}\n\nPress Back, then try Open with…"
                    else "Can't play this stream\n${e.errorCodeName}\n\nPress Back to return to the page")
                setOverlay(false)
            }
        })
        val mime = when {
            local -> null // a downloaded file: let ExoPlayer detect the container (MP4, MKV, MP3, ...)
            url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            else -> MimeTypes.APPLICATION_M3U8
        }
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(mime).build())
        exo.playWhenReady = true
        exo.prepare()
        setOverlay(true)
    }

    override fun onStop() {
        super.onStop()
        if (current?.get() === this) current = null
        handler.removeCallbacks(saveTick)
        handler.removeCallbacks(uiTick)
        // A held ◀/▶ whose key-up never came (Home pressed mid-seek): land where the preview was.
        if (seekDir != 0) commitSeek()
        savePosition()
        player?.pause()
        if (pageUrl != null) TvHome.sync(this, foreground = false)
    }

    override fun onStart() {
        super.onStart()
        current = java.lang.ref.WeakReference(this)
        player?.play()
        if (pageUrl != null) handler.postDelayed(saveTick, 10_000)
        if (overlayOn) { handler.removeCallbacks(uiTick); handler.post(uiTick) }
    }

    /** Same rules as the page's video (see [ContinueWatching.record]): long videos only, finished ones are dropped. */
    private fun savePosition() {
        val page = pageUrl ?: return
        val exo = player ?: return
        val dur = exo.duration
        if (dur == C.TIME_UNSET || exo.isCurrentMediaItemLive || exo.playbackState == Player.STATE_IDLE) return
        val prefs = Prefs(this)
        if (!prefs.rememberWatching) return
        val list = prefs.continueWatching
        val host = android.net.Uri.parse(page).host?.removePrefix("www.").orEmpty()
        val next = ContinueWatching.record(list, page, pageTitle, host, exo.currentPosition / 1000.0, dur / 1000.0, System.currentTimeMillis())
        if (next !== list) prefs.continueWatching = next
    }

    /** The browser's sleep timer fired while this player is on screen: pause, say so, let the screen sleep. */
    private fun sleepPause() {
        player?.pause()
        setOverlay(true)
        screen.keepScreenOn = false
        android.widget.Toast.makeText(this, "Sleep timer: paused", android.widget.Toast.LENGTH_LONG).show()
    }

    // ---- Overlay ----

    private fun showMessage(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun setOverlay(show: Boolean) {
        handler.removeCallbacks(hideOverlay)
        if (show) {
            if (!overlayOn) {
                overlayOn = true
                overlay.animate().cancel()
                overlay.alpha = 0f
                overlay.visibility = View.VISIBLE
                overlay.animate().alpha(1f).setDuration(180).start()
                bar.requestFocus()
                handler.removeCallbacks(uiTick)
                handler.post(uiTick)
            }
            poke()
        } else if (overlayOn) {
            overlayOn = false
            handler.removeCallbacks(uiTick)
            overlay.animate().cancel()
            overlay.animate().alpha(0f).setDuration(220).withEndAction { if (!overlayOn) overlay.visibility = View.INVISIBLE }.start()
        }
    }

    /** Restart the auto-hide countdown; only while playing and not mid-seek. */
    private fun poke() {
        handler.removeCallbacks(hideOverlay)
        if (overlayOn && seekDir == 0 && player?.isPlaying == true) handler.postDelayed(hideOverlay, PlayerUi.HIDE_MS)
    }

    /** Bar, times, "Ends at", clock and buttons from the player's state. */
    private fun refresh() {
        val exo = player ?: return
        val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val pos = exo.currentPosition
        val at = if (seekDir != 0) seekTarget else pos
        bar.update(dur, pos, exo.bufferedPosition, if (seekDir != 0) seekTarget else -1)
        val live = exo.isCurrentMediaItemLive
        val speed = exo.playbackParameters.speed.toDouble()
        elapsed.text = PlayerUi.clock(at)
        total.text = when { live -> "LIVE"; dur > 0 -> PlayerUi.clock(dur); else -> "" }
        total.setTextColor(if (live) 0xFFFFFFFF.toInt() else 0xB3FFFFFF.toInt())
        val is24h = android.text.format.DateFormat.is24HourFormat(this)
        val now = System.currentTimeMillis()
        val locale = java.util.Locale.getDefault()
        val zone = java.util.TimeZone.getDefault()
        // "Ends at" follows the seek preview and the speed, so it answers "will this finish before bed?".
        ends.text = if (dur > 0 && !live) PlayerUi.endsAt(now, dur - at, speed, is24h, locale, zone).orEmpty() else ""
        clockView.text = PlayerUi.timeOfDay(now, is24h, locale, zone)
        val playing = exo.isPlaying || (exo.playWhenReady && exo.playbackState == Player.STATE_BUFFERING)
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = if (playing) "Pause" else "Play"
        speedLabel.text = PlaybackSpeed.label(speed)
        // Next only when the player really has another item (today every caller passes one, so it stays hidden).
        setShown(nextButton, exo.hasNextMediaItem())
        val captions = captionTracks(exo)
        setShown(captionsButton, captions.isNotEmpty())
        if (captions.isNotEmpty()) captionsLabel.text = captions.firstOrNull { (g, i) -> g.isTrackSelected(i) }?.let { (g, i) -> captionLabel(g, i) } ?: "Off"
    }

    /** Show or hide a button; if it had focus, the bar takes it so focus isn't lost. */
    private fun setShown(v: View, show: Boolean) {
        val want = if (show) View.VISIBLE else View.GONE
        if (v.visibility == want) return
        if (!show && v.hasFocus()) bar.requestFocus()
        v.visibility = want
    }

    // ---- Playback ----

    private fun togglePlay() {
        val exo = player ?: return
        when {
            exo.playbackState == Player.STATE_ENDED -> { exo.seekTo(0); exo.play() }
            exo.playWhenReady -> exo.pause()
            else -> { screen.keepScreenOn = true; exo.play() }
        }
        refresh()
    }

    private fun seekBy(delta: Long) {
        val exo = player ?: return
        if (!exo.isCurrentMediaItemSeekable) return
        exo.seekTo(PlayerUi.seekTarget(exo.currentPosition, delta, exo.duration))
        refresh()
    }

    /** ◀/▶ (or Rewind / Fast forward) down and up: a tap is 10 s; held, the preview speeds up; the seek happens on release. */
    private fun seekKey(event: KeyEvent, dir: Int) {
        val exo = player ?: return
        if (event.action == KeyEvent.ACTION_UP) { if (seekDir != 0) commitSeek(); return }
        if (event.action != KeyEvent.ACTION_DOWN || !exo.isCurrentMediaItemSeekable) return
        if (event.repeatCount == 0 || seekDir != dir) {
            if (seekDir != dir) seekTarget = exo.currentPosition
            seekDir = dir
            seekTarget = PlayerUi.seekTarget(seekTarget, dir * PlayerUi.TAP_MS, exo.duration)
            lastStep = event.eventTime
        } else if (event.eventTime - lastStep >= PlayerUi.TICK_MS) {
            seekTarget = PlayerUi.seekTarget(seekTarget, dir * PlayerUi.seekStep(event.eventTime - event.downTime), exo.duration)
            lastStep = event.eventTime
        }
        handler.removeCallbacks(hideOverlay)
        refresh()
    }

    private fun commitSeek() {
        player?.seekTo(seekTarget)
        seekDir = 0
        refresh()
        poke()
    }

    // Subtitles: Media3's controller had a menu for them; here one pill cycles Off → each track → Off.

    private fun captionTracks(exo: ExoPlayer): List<Pair<Tracks.Group, Int>> =
        exo.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }

    private fun captionLabel(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(i)
        return f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } } ?: "On"
    }

    private fun nextCaptions() {
        val exo = player ?: return
        val list = captionTracks(exo)
        if (list.isEmpty()) return
        val i = list.indexOfFirst { (g, t) -> g.isTrackSelected(t) } + 1
        val params = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        exo.trackSelectionParameters = if (i >= list.size) params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            else params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(list[i].first.mediaTrackGroup, list[i].second)).build()
        refresh()
    }

    // ---- Keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!screen.keepScreenOn) screen.keepScreenOn = true
        val exo = player ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            // Back goes through onBackPressed; volume stays with the system.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                return super.dispatchKeyEvent(event)
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { if (first) { togglePlay(); setOverlay(true) }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (first) { screen.keepScreenOn = true; exo.play(); setOverlay(true) }; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (first) { exo.pause(); setOverlay(true) }; return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (first && exo.hasNextMediaItem()) { exo.seekToNextMediaItem(); setOverlay(true) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) setOverlay(true); seekKey(event, -1); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) setOverlay(true); seekKey(event, 1); return true }
        }
        if (failed) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        val arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
        val dir = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
        // Overlay hidden: OK plays/pauses, ◀/▶ seek, any other key only brings the overlay back.
        if (!overlayOn) {
            if (!down) return true // the key-up of the press that hid it
            setOverlay(true)
            if (arrow) seekKey(event, dir) else if (first && ok) togglePlay()
            return true
        }
        poke()
        if (bar.isFocused || seekDir != 0) {
            if (arrow || (seekDir != 0 && !down)) { seekKey(event, dir); return true }
            if (ok) { if (first) togglePlay(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // First Back hides the overlay; the next one leaves the player.
        if (overlayOn && !failed) { setOverlay(false); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_USER_AGENT = "ua"
        const val EXTRA_COOKIE = "cookie"
        /** Shown at the top (page title, or the file name from Downloads); [PlayerUi.title] has the fallback. */
        const val EXTRA_TITLE = "title"
        /** Playback speed, one of [PlaybackSpeed.LEVELS]. */
        const val EXTRA_SPEED = "speed"

        /** The player on screen (between onStart and onStop), so the browser's sleep timer can pause it. */
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null
        /** Sleep timer set to "End of video"; cleared by whichever (page or this player) reaches the end first. */
        @Volatile var sleepAtEnd = false

        /** Main thread only. True if a player was on screen. */
        fun pauseForSleep(): Boolean {
            val a = current?.get() ?: return false
            a.sleepPause()
            return true
        }
        const val EXTRA_PAGE_URL = "page_url"
        const val EXTRA_REMEMBER = "remember"
    }
}
