package com.mktvhub.mktv

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Full-screen native player for HLS / DASH streams found on a page. */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var screen: FrameLayout? = null
    /** Continue watching: the page this stream came from, or null when it mustn't be remembered (private, setting off, a file). */
    private var pageUrl: String? = null
    private var pageTitle = ""
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() { savePosition(); handler.postDelayed(this, 10_000) }
    }

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
        // The page's speed for this site (watch mode controls); the player's own ⚙ menu can change it here.
        exo.setPlaybackSpeed((PlaybackSpeed.exact(intent.getDoubleExtra(EXTRA_SPEED, PlaybackSpeed.DEFAULT)) ?: PlaybackSpeed.DEFAULT).toFloat())

        val root = FrameLayout(this).apply { keepScreenOn = true; setBackgroundColor(android.graphics.Color.BLACK) }
        val view = PlayerView(this).apply {
            this.player = exo
            useController = true
            controllerShowTimeoutMs = 3000
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        val error = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(error, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        screen = root
        playerView = view
        view.requestFocus()

        exo.addListener(object : Player.Listener {
            private var resumeChecked = false
            override fun onPlaybackStateChanged(state: Int) {
                // Sleep timer "End of video": say why it stopped (a single stream has no autoplay to stop).
                if (state == Player.STATE_ENDED && sleepAtEnd) { sleepAtEnd = false; sleepPause() }
                // Once the length is known: pick up where this page's video was left (the controls' rewind goes back).
                if (state != Player.STATE_READY || resumeChecked) return
                resumeChecked = true
                val page = pageUrl ?: return
                val dur = exo.duration
                if (dur == C.TIME_UNSET || exo.isCurrentMediaItemLive) return
                val saved = ContinueWatching.find(Prefs(this@PlayerActivity).continueWatching, page) ?: return
                if (!ContinueWatching.shouldOffer(saved, exo.currentPosition / 1000.0, dur / 1000.0)) return
                exo.seekTo((saved.position * 1000).toLong())
                error.text = "Resumed from ${ContinueWatching.clock(saved.position)}"
                handler.postDelayed({ if (error.text.startsWith("Resumed")) error.text = "" }, 3_000)
            }
            override fun onPlayerError(e: PlaybackException) {
                error.text = if (local) "Can't play this file\n${e.errorCodeName}\n\nPress Back, then try Open with…"
                    else "Can't play this stream\n${e.errorCodeName}\n\nPress Back to return to the page"
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
    }

    override fun onStop() {
        super.onStop()
        if (current?.get() === this) current = null
        handler.removeCallbacks(saveTick)
        savePosition()
        player?.pause()
        if (pageUrl != null) TvHome.sync(this, foreground = false)
    }

    override fun onStart() {
        super.onStart()
        current = java.lang.ref.WeakReference(this)
        player?.play()
        if (pageUrl != null) handler.postDelayed(saveTick, 10_000)
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
        playerView?.showController()
        screen?.keepScreenOn = false
        android.widget.Toast.makeText(this, "Sleep timer: paused", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        screen?.let { if (!it.keepScreenOn) it.keepScreenOn = true }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveTick)
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
