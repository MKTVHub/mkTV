package com.mktvhub.mktv

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The phone remote's one [RemoteServer] for the whole process (same port and pairing code everywhere).
 * Whichever mkTV activity is created first attaches it ([attach]), so the remote also works when Android
 * restores only the native player after the process was killed. The browser is the listener while it
 * exists; without it a small player-only listener answers. The server lives while any mkTV activity is
 * started ([ForegroundCounter]); nothing here holds an activity strongly. Main thread except the listener calls.
 */
object RemoteHub {
    /** Intent extras for the browser, from the player-only remote: open this address / show Home. */
    const val EXTRA_REMOTE_OPEN = "remote_open"
    const val EXTRA_REMOTE_HOME = "remote_home"
    /** A held phone button whose "up" never arrives (screen locked, Wi-Fi blip) lets go by itself after this long. */
    private const val HELD_KEY_MAX_MS = 10_000L

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private var browser: WeakReference<BrowserActivity>? = null
    private var retry: Runnable? = null
    private val heldKeys = HashMap<Int, Runnable>()

    val server: RemoteServer by lazy { RemoteServer(app, listener, { Prefs(app).remoteToken }, { Prefs(app).remoteToken = it }) }

    private val foreground = ForegroundCounter(
        onForeground = { if (Prefs(app).remoteEnabled) start() },
        onBackground = { stop(); releaseHeldKeys() }, // the server is gone, so no "up" can arrive
    )

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) = foreground.activityStarted(System.identityHashCode(a))
        override fun onActivityStopped(a: Activity) {
            releaseHeldKeys() // the keys were going to this screen; the next one starts fresh
            foreground.activityStopped(System.identityHashCode(a))
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityResumed(a: Activity) = Unit
        override fun onActivityPaused(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    /** From every mkTV activity's onCreate, before onStart, so the first one on screen counts and starts the server. */
    fun attach(a: Activity) {
        if (!::app.isInitialized) { app = a.application; app.registerActivityLifecycleCallbacks(callbacks) }
        if (a is BrowserActivity) browser = WeakReference(a)
    }

    /** The browser is going away: the player-only listener answers until a browser attaches again. */
    fun detach(a: BrowserActivity) {
        if (browser?.get() === a) browser = null
    }

    /** Coming back from the background the old socket can still be closing: retry for a few seconds. */
    fun start(attempt: Int = 0) {
        retry?.let(main::removeCallbacks); retry = null
        server.start()
        if (!server.running && attempt < 6) retry = Runnable { if (foreground.visible) start(attempt + 1) }.also { main.postDelayed(it, 700) }
    }

    fun stop() {
        retry?.let(main::removeCallbacks); retry = null
        server.stop()
    }

    // ---- Keys (shared by the browser and the player-only listener) ----

    /**
     * A phone key other than Home into the screen in front (the native player over the browser). Volume goes
     * to the media stream (apps can't inject volume keys; FLAG_SHOW_UI shows the TV's own volume bar).
     * A held phone button behaves like a held remote button: the key repeats after 450 ms. Main thread.
     */
    fun key(key: String, action: String) {
        if (key == "volup" || key == "voldown" || key == "mute") {
            if (action != "up") (app.getSystemService(Application.AUDIO_SERVICE) as android.media.AudioManager).adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                when (key) { "volup" -> android.media.AudioManager.ADJUST_RAISE; "voldown" -> android.media.AudioManager.ADJUST_LOWER; else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE },
                android.media.AudioManager.FLAG_SHOW_UI)
            return
        }
        val code = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK; "menu" -> KeyEvent.KEYCODE_MENU
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "pageup" -> KeyEvent.KEYCODE_PAGE_UP; "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        when (action) {
            "down" -> {
                heldKeys.remove(code)?.let(main::removeCallbacks)
                val downTime = now
                send(KeyEvent(downTime, now, KeyEvent.ACTION_DOWN, code, 0))
                val repeater = object : Runnable {
                    var count = 1
                    override fun run() {
                        val t = SystemClock.uptimeMillis()
                        if (t - downTime > HELD_KEY_MAX_MS) { heldKeys.remove(code); send(KeyEvent(downTime, t, KeyEvent.ACTION_UP, code, 0)); return }
                        send(KeyEvent(downTime, t, KeyEvent.ACTION_DOWN, code, count++, 0, 0, 0, KeyEvent.FLAG_LONG_PRESS.takeIf { count == 2 } ?: 0))
                        main.postDelayed(this, 90)
                    }
                }
                heldKeys[code] = repeater
                main.postDelayed(repeater, 450)
            }
            "up" -> {
                heldKeys.remove(code)?.let(main::removeCallbacks)
                send(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
            else -> {
                send(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                send(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
        }
    }

    /** The native player when it covers the browser, else the browser ([dispatchRemoteKey]). */
    private fun send(e: KeyEvent): Boolean = (PlayerActivity.front() ?: liveBrowser())?.dispatchRemoteKey(e) ?: false

    private fun releaseHeldKeys() {
        heldKeys.values.forEach(main::removeCallbacks)
        heldKeys.clear()
    }

    private fun liveBrowser(): BrowserActivity? = browser?.get()?.takeIf { !it.isDestroyed }

    /** Runs [block] on the main thread and waits up to a second for its answer (the server's threads call in). */
    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val out = AtomicReference<T>()
        main.post { try { out.set(block()) } finally { latch.countDown() } }
        latch.await(1, TimeUnit.SECONDS)
        return out.get() ?: fallback
    }

    /** Hands each call to the browser while it exists, else to [PlayerOnly]. */
    private val listener = object : RemoteServer.Listener {
        private fun target(): RemoteServer.Listener = liveBrowser() ?: PlayerOnly
        override fun onKey(key: String, action: String) = target().onKey(key, action)
        override fun onText(text: String, deletes: Int, clear: Boolean, enter: Boolean) = target().onText(text, deletes, clear, enter)
        override fun onOpen(url: String) = target().onOpen(url)
        override fun onMove(dx: Double, dy: Double) = target().onMove(dx, dy)
        override fun onMedia(action: String, value: Double) = target().onMedia(action, value)
        override fun onFavourite(action: String?) = target().onFavourite(action)
        override fun onNavigation(mode: String?) = target().onNavigation(mode)
        override fun state() = target().state()
        override fun home() = target().home()
        override fun icon(host: String) = target().icon(host)
        override fun onPhoneConnected(address: String) = target().onPhoneConnected(address)
    }

    /**
     * Only the native player is alive (Android restored it alone after killing the process). Keys and media go
     * to it, Now playing is its video, ★ saves its page; Home and opening a page start the browser, which takes
     * over as the listener. No typing, pointer or sleep timer here: those belong to the browser.
     */
    private object PlayerOnly : RemoteServer.Listener {
        override fun onKey(key: String, action: String) {
            main.post { if (key != "home") RemoteHub.key(key, action) else if (action != "up") openBrowser(Intent().putExtra(EXTRA_REMOTE_HOME, true)) }
        }
        override fun onText(text: String, deletes: Int, clear: Boolean, enter: Boolean) = Unit
        override fun onOpen(url: String) { main.post { openBrowser(Intent().putExtra(EXTRA_REMOTE_OPEN, url)) } }
        override fun onMove(dx: Double, dy: Double) = Unit
        override fun onMedia(action: String, value: Double) {
            main.post { if (PlayerRemote.media(action) == PlayerRemote.Target.PLAYER) PlayerActivity.front()?.remoteMedia(action, value) }
        }
        override fun onFavourite(action: String?): JSONObject =
            onMain(JSONObject().put("ok", false)) { PlayerActivity.front()?.remoteFavourite(action) ?: JSONObject().put("ok", false) }
        override fun onNavigation(mode: String?): JSONObject = PlayerRemote.navigationRefused(Prefs(app).navMode)
        override fun state(): JSONObject = onMain(JSONObject().put("title", "mkTV")) {
            val p = PlayerActivity.front()
            val page = p?.remotePage
            val o = JSONObject().put("title", p?.remoteTitle() ?: "mkTV").put("url", page ?: "")
                .put("nav", Prefs(app).navMode).put("pointer", false).put("player", p != null)
                .put("canFav", page != null).put("fav", page != null && Bookmarks.containsUrl(Prefs(app).bookmarks, page))
                .put("sleep", JSONObject().put("mode", 0).put("left", 0))
            if (p != null) o.put("media", p.remoteStatus())
            o
        }
        /** Popular and Favourites; Recent is left out, as the browser's private state isn't known here. */
        override fun home(): JSONObject = homeLists(app, private = true)
        override fun icon(host: String): ByteArray? = savedIcon(app, host)
        override fun onPhoneConnected(address: String) { main.post { PlayerActivity.front()?.toast("New phone connected · $address") } }

        /** singleTask: the browser comes back to the front over the task, which closes the player above it. */
        private fun openBrowser(extras: Intent) {
            val from: android.content.Context = PlayerActivity.front() ?: app
            val i = Intent(from, BrowserActivity::class.java).putExtras(extras)
            if (from !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            from.startActivity(i)
        }
    }

    /** Home lists for the phone, as on the TV's Home screen. Never Recent while [private]. */
    fun homeLists(context: android.content.Context, private: Boolean): JSONObject {
        val prefs = Prefs(context)
        val hidden = prefs.hiddenSections
        val favs = if ("fav" in hidden) emptyList() else prefs.shortcuts
        val recent = if (private || "rec" in hidden) emptyList() else prefs.recent.filterNot { r -> favs.any { it.second == r.second } }.take(10)
        fun tiles(list: List<Pair<String, String>>) = RemoteInput.tiles(list.map { (title, url) ->
            val host = android.net.Uri.parse(url).host
            RemoteInput.tile(title, url, host, SiteIcons.file(context.filesDir, host)?.exists() == true)
        })
        return JSONObject()
            .put("popular", tiles(if ("pop" in hidden) emptyList() else prefs.popular))
            .put("favourites", tiles(favs))
            .put("recent", tiles(recent))
    }

    /** The saved favicon (PNG) for [host], if it's a sensible size. */
    fun savedIcon(context: android.content.Context, host: String): ByteArray? =
        SiteIcons.file(context.filesDir, host)?.takeIf { it.isFile && it.length() in 1..512_000 }?.readBytes()
}
