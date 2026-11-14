package com.mktvhub.mktv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

class BrowserActivity : Activity(), RemoteServer.Listener {

    private lateinit var prefs: Prefs
    private lateinit var blocker: AdBlocker
    private lateinit var root: FrameLayout
    private lateinit var toolbar: View
    private val topBar: View by lazy { findViewById(R.id.topBar) }
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var urlField: EditText
    private lateinit var progress: ProgressBar
    private lateinit var btnShield: View
    private lateinit var shieldIcon: android.widget.ImageView
    private lateinit var shieldBadge: TextView
    private lateinit var shieldPanel: View
    private lateinit var snackbar: View
    private lateinit var btnBack: View
    private lateinit var btnForward: View
    private lateinit var btnStream: View
    private lateinit var webView: WebView

    private val pageScript by lazy { assets.open("page_script.js").bufferedReader().use { it.readText() } }
    /** Site-specific ad cleanup that host blocking can't do (ads served from the site's own servers). */
    private val ytScript by lazy { assets.open("yt_clean.js").bufferedReader().use { it.readText() } }
    private var docStartScript = false

    /** Read from WebView's background thread in shouldInterceptRequest. */
    @Volatile private var pageHost: String? = null
    private val blockedAds = java.util.concurrent.atomic.AtomicInteger()
    private val blockedPopups = java.util.concurrent.atomic.AtomicInteger()
    private val blockedRedirects = java.util.concurrent.atomic.AtomicInteger()
    /** Unsaved part of the all-time counter; flushed to prefs in onPause. */
    private val pendingTotal = java.util.concurrent.atomic.AtomicLong()
    /** False when blocking is off globally or paused for the current site. Read off the UI thread. */
    @Volatile private var protectionOn = true
    /** A blocked pop-up/redirect URL the user chose to open: let it through once. */
    @Volatile private var bypassOnce: String? = null

    /** HLS/DASH manifests seen on the current page. */
    private val streams = CopyOnWriteArrayList<DetectedStream>()

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var clickDownTime = 0L
    private var lastBackPress = 0L

    /** Headers are the ones the page actually sent when it requested the manifest. */
    data class DetectedStream(val url: String, val referer: String?, val origin: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        prefs = Prefs(this)
        blocker = AdBlocker(this)
        blocker.load(prefs.blocklistUrl)

        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        webContainer = findViewById(R.id.webContainer)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        cursor = findViewById(R.id.cursor)
        urlField = findViewById(R.id.urlField)
        progress = findViewById(R.id.progress)
        btnShield = findViewById(R.id.btnShield)
        shieldIcon = findViewById(R.id.shieldIcon)
        shieldBadge = findViewById(R.id.shieldBadge)
        shieldPanel = findViewById(R.id.shieldPanel)
        snackbar = findViewById(R.id.snackbar)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnStream = findViewById(R.id.btnStream)

        hideSystemBars()
        setupWebView()
        setupShortcuts()
        setupSettings()
        setupToolbar()
        cursor.onEdgeScroll = ::scrollAtCursor
        cursor.onMove = { _, y -> if (y < topBar.height) showBar(autoHide = false) else if (barShown) scheduleHideBar(1500) }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            refreshCursor()
            if (isInside(toolbar, now) || overlayOpen()) showBar(autoHide = false)
        }
        applyNavMode()

        protectionOn = prefs.adBlock
        val start = intent?.dataString
        when {
            start != null -> load(normalizeInput(start))
            prefs.startWithShortcuts -> openShortcuts()
            else -> load(normalizeInput(prefs.homeUrl))
        }
        webView.isFocusableInTouchMode = true
        urlField.clearFocus()
        if (!overlayOpen()) webView.requestFocus()
    }

    // region WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Leftover private profile from a crash or force-stop: wipe it.
        if (multiProfile) runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
        webView = createWebView(isPrivate = false)
        attach(webView)
    }

    private val multiProfile by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

    private fun cookieManagerFor(isPrivate: Boolean): CookieManager =
        if (isPrivate && multiProfile) androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE).cookieManager
        else CookieManager.getInstance()

    private fun attach(wv: WebView) {
        webContainer.removeAllViews()
        webContainer.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(isPrivate: Boolean): WebView {
        val wv = WebView(this)
        if (isPrivate && multiProfile) {
            // Separate cookie jar, storage and cache; deleted when private browsing closes.
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
            WebViewCompat.setProfile(wv, PRIVATE_PROFILE)
        }
        wv.setBackgroundColor(android.graphics.Color.BLACK)
        wv.isFocusableInTouchMode = true
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            // Needed so target=_blank / window.open go through onCreateWindow, where we filter them.
            // With false, they silently replace the current page instead.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        wv.settings.userAgentString = if (prefs.desktopMode) DESKTOP_UA else null
        if (isPrivate && !multiProfile) wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        cookieManagerFor(isPrivate).setAcceptThirdPartyCookies(wv, !isPrivate && !prefs.blockThirdPartyCookies)

        docStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStartScript) {
            if (!prefs.autoUnmute) WebViewCompat.addDocumentStartJavaScript(wv, "window.__spNoUnmute = true;", setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(wv, pageScript, setOf("*"))
            if (prefs.adBlock) WebViewCompat.addDocumentStartJavaScript(wv, ytScript,
                setOf("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com", "https://www.youtube-nocookie.com", "https://music.youtube.com"))
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (protectionOn && !request.isForMainFrame && blocker.isBlocked(url.host)) {
                    countBlocked(blockedAds)
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                val s = url.toString()
                // Skip variant playlists that live under an already-seen master playlist.
                if (HostRules.isStreamUrl(s) && streams.none { it.url == s || HostRules.isVariantOf(s, it.url) }) {
                    val h = request.requestHeaders.mapKeys { it.key.lowercase() }
                    streams += DetectedStream(s, h["referer"], h["origin"])
                    runOnUiThread { updateStreamButton() }
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (scheme !in setOf("http", "https", "about", "data", "blob", "javascript")) {
                    if (request.isForMainFrame) { countBlocked(blockedRedirects); showBlocked("Blocked link to an app ($scheme:)", null) }
                    return true
                }
                if (bypassOnce == url.toString()) { bypassOnce = null; return false }
                if (request.isForMainFrame && uaFor(url.toString()) != uaFor(view.url)) {
                    load(url.toString()); return true
                }
                if (request.isForMainFrame && prefs.stripTracking) {
                    val clean = HostRules.stripTracking(url.toString())
                    if (clean != url.toString()) { view.loadUrl(clean); return true }
                }
                if (!protectionOn) return false
                if (blocker.isBlocked(url.host)) {
                    if (request.isForMainFrame) { countBlocked(blockedRedirects); showBlocked("Blocked ad page · ${url.host}", url.toString()) }
                    else countBlocked(blockedAds)
                    return true
                }
                if (request.isForMainFrame && prefs.blockRedirects && !request.hasGesture() && !request.isRedirect &&
                    pageHost != null && !HostRules.sameSite(pageHost, url.host)
                ) {
                    // Script-driven jump to another site without a click: classic ad redirect.
                    countBlocked(blockedRedirects)
                    showBlocked("Blocked redirect · ${url.host}", url.toString())
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (view !== webView) return
                pageHost = Uri.parse(url).host
                protectionOn = isProtected(pageHost)
                blockedAds.set(0); blockedPopups.set(0); blockedRedirects.set(0)
                cursor.showRing(null)
                exitWatch(silent = true)
                watchDismissedFor = null
                showBar()
                streams.clear()
                updateShield()
                updateStreamButton()
                if (!urlField.hasFocus()) urlField.setText(url)
                if (!docStartScript) { view.evaluateJavascript(pageScript, null); if (prefs.adBlock) view.evaluateJavascript(ytScript, null) }
                updateNav()
                updateStar()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view !== webView) return
                rememberRecent(view.title, url)
                updateStar()
                scheduleHideBar(3000)
                if (!docStartScript) view.evaluateJavascript(pageScript, null)
                if (!urlField.hasFocus()) urlField.setText(url)
                updateNav()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = updateNav()
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== webView) return
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                fullscreenContainer.visibility = View.VISIBLE
                hideSystemBars()
                if (watching) showDock()
            }

            override fun onHideCustomView() = exitFullscreen(notifyPage = false)

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) {
                    if (protectionOn) { countBlocked(blockedPopups); return false }
                    return false
                }
                // Catch the pop-up's URL with a throwaway WebView, then decide.
                val temp = WebView(this@BrowserActivity)
                var handled = false
                fun decide(url: String?) {
                    if (handled || url.isNullOrBlank() || url == "about:blank") return
                    handled = true
                    temp.stopLoading()
                    webView.post { temp.destroy() } // temp is never attached, so its own post() would never run
                    val host = Uri.parse(url).host
                    val blocked = protectionOn &&
                        (blocker.isBlocked(host) || (prefs.blockPopups && !HostRules.sameSite(pageHost, host)))
                    if (blocked) {
                        countBlocked(blockedPopups)
                        showBlocked("Blocked pop-up · $host", url)
                    } else load(url)
                }
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { decide(r.url.toString()); return true }
                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) = decide(url)
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                // Some pop-ups never navigate past about:blank; don't leak the WebView.
                webView.postDelayed({ if (!handled) { handled = true; temp.destroy() } }, 3_000)
                return true
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                // YouTube's TV interface reports the YouTube TV app icon; keep the normal YouTube icon for the tile.
                if (view === webView && !inPrivate && !isTvSite(view.url)) saveFavicon(icon)
            }

            // Hide the grey "play" placeholder WebView shows before a video starts.
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        return wv
    }

    private fun applyUserAgent() {
        listOfNotNull(normalWebView, privateWebView, webView).forEach {
            it.settings.userAgentString = uaFor(it.url)
        }
    }

    /** YouTube's TV interface (built for remotes) is only served to TV browsers. */
    private fun isTvSite(url: String?): Boolean {
        val u = Uri.parse(url ?: return false)
        return (u.host == "www.youtube.com" || u.host == "youtube.com") && (u.path ?: "").startsWith("/tv")
    }

    /** Sites that bounce the stock Android TV WebView to an "unsupported" page but work with desktop Chrome. */
    private fun needsDesktop(url: String?): Boolean {
        val host = Uri.parse(url ?: return false).host ?: return false
        return HostRules.isBlocked(host, DESKTOP_ONLY)
    }

    private fun uaFor(url: String?): String? = when {
        isTvSite(url) -> TV_UA
        prefs.desktopMode || needsDesktop(url) -> DESKTOP_UA
        else -> null
    }

    /** All app-initiated navigation goes through here so the right user agent is set first. */
    private fun load(url: String) {
        val ua = uaFor(url)
        if (webView.settings.userAgentString != (ua ?: WebSettings.getDefaultUserAgent(this))) webView.settings.userAgentString = ua
        webView.loadUrl(url)
    }

    /** On YouTube TV the site drives focus itself; the remote goes straight to it. */
    private val siteRemote get() = isTvSite(webView.url)

    // ---- Private browsing ----

    private var normalWebView: WebView? = null
    private var privateWebView: WebView? = null
    private val inPrivate get() = privateWebView != null && webView === privateWebView

    private fun enterPrivate() {
        if (inPrivate) return
        normalWebView = webView
        webView.onPause()
        // Without profile support, the only way to keep this session's cookies out of the normal jar is to refuse them.
        if (!multiProfile) CookieManager.getInstance().setAcceptCookie(false)
        val pw = createWebView(isPrivate = true)
        privateWebView = pw
        webView = pw
        attach(pw)
        resetPageState()
        // Keep private pages out of Recents thumbnails and screenshots (release builds; debug stays capturable for testing).
        if (!BuildConfig.DEBUG) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        applyPrivateLook()
        openShortcuts()
    }

    private fun exitPrivate() {
        val pw = privateWebView ?: return
        val normal = normalWebView ?: return
        webView = normal
        attach(normal)
        normal.onResume()
        privateWebView = null
        normalWebView = null
        pw.stopLoading()
        pw.clearHistory()
        pw.destroy()
        if (multiProfile) {
            // The profile can only be deleted once the WebView is gone.
            root.postDelayed({ runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) } }, 600)
        } else {
            CookieManager.getInstance().setAcceptCookie(true)
        }
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        resetPageState()
        pageHost = Uri.parse(normal.url ?: "").host
        protectionOn = isProtected(pageHost)
        urlField.setText(normal.url ?: "")
        applyPrivateLook()
        updateNav(); updateStar(); updateShield()
        note("Private browsing closed. History, cookies and site data from it are gone.")
        if (!hasPage()) openShortcuts() else if (shortcutsPanel.visibility == View.VISIBLE) renderShortcuts()
    }

    private fun resetPageState() {
        streams.clear(); updateStreamButton()
        blockedAds.set(0); blockedPopups.set(0); blockedRedirects.set(0); updateShield()
        cursor.showRing(null)
        if (webView.url.isNullOrBlank()) urlField.setText("")
    }

    private fun applyPrivateLook() {
        val priv = inPrivate
        toolbar.setBackgroundColor(if (priv) 0xFF2E1065.toInt() else 0xFF1A1C21.toInt())
        urlField.hint = if (priv) "Private · Search or enter address" else "Search or enter address"
        findViewById<android.widget.ImageButton>(R.id.btnMenu).setImageResource(if (priv) R.drawable.ic_private_on else R.drawable.ic_more)
    }

    private fun exitFullscreen(notifyPage: Boolean) {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        if (notifyPage) customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // endregion

    // region Toolbar

    private fun setupToolbar() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.btnReload).setOnClickListener { webView.reload() }
        findViewById<View>(R.id.btnHome).setOnClickListener {
            if (prefs.startWithShortcuts) openShortcuts() else { closeShortcuts(); load(normalizeInput(prefs.homeUrl)) }
        }
        findViewById<View>(R.id.btnMenu).setOnClickListener { toggleMenu() }

        btnShield.setOnClickListener { toggleShieldPanel() }
        findViewById<View>(R.id.panelToggle).setOnClickListener { toggleSitePause() }
        findViewById<View>(R.id.snackClose).setOnClickListener { hideSnackbar() }
        findViewById<View>(R.id.snackAction).setOnClickListener {
            snackUrl?.let { bypassOnce = it; load(it) }
            hideSnackbar()
        }
        btnStream.setOnClickListener { openStreamPicker() }
        urlField.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) {
                closeShortcuts()
                load(normalizeInput(urlField.text.toString()))
                hideKeyboard()
                urlField.clearFocus()
                webView.requestFocus()
            }
            go
        }
        updateShield()
        updateNav()
    }

    private fun isProtected(host: String?) = prefs.adBlock && HostRules.siteOf(host) !in prefs.pausedSites

    /** Called from any thread. */
    private fun countBlocked(counter: java.util.concurrent.atomic.AtomicInteger) {
        counter.incrementAndGet()
        pendingTotal.incrementAndGet()
        runOnUiThread { updateShield(pulse = true) }
    }

    private var lastPulse = 0L
    private fun updateShield(pulse: Boolean = false) {
        val on = protectionOn
        shieldIcon.setImageResource(if (on) R.drawable.ic_shield_on else R.drawable.ic_shield_off)
        val n = blockedAds.get() + blockedPopups.get() + blockedRedirects.get()
        shieldBadge.visibility = if (on && n > 0) View.VISIBLE else View.GONE
        shieldBadge.text = if (n > 99) "99+" else n.toString()
        val now = SystemClock.uptimeMillis()
        if (pulse && on && now - lastPulse > 600) {
            lastPulse = now
            shieldBadge.animate().cancel()
            shieldBadge.scaleX = 1.35f; shieldBadge.scaleY = 1.35f
            shieldBadge.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        }
        if (shieldPanel.visibility == View.VISIBLE) bindShieldPanel()
    }

    private fun toggleShieldPanel() {
        menuPanel.visibility = View.GONE
        if (shieldPanel.visibility == View.VISIBLE) { closeShieldPanel(); return }
        bindShieldPanel()
        shieldPanel.visibility = View.VISIBLE
        onOverlayChanged(findViewById(R.id.panelToggle))
    }

    private fun closeShieldPanel() {
        shieldPanel.visibility = View.GONE
        onOverlayChanged(null)
    }

    private fun bindShieldPanel() {
        val site = HostRules.siteOf(pageHost) ?: "this page"
        val globalOff = !prefs.adBlock
        val on = protectionOn
        findViewById<android.widget.ImageView>(R.id.panelIcon).setImageResource(if (on) R.drawable.ic_shield_on else R.drawable.ic_shield_off)
        findViewById<TextView>(R.id.panelTitle).text = when {
            globalOff -> "Blocking is off"
            on -> "Protection on"
            else -> "Paused on this site"
        }
        findViewById<TextView>(R.id.panelSite).text = if (globalOff) "Turn it on in ⚙ Settings" else site
        findViewById<TextView>(R.id.panelToggle).apply {
            visibility = if (globalOff) View.GONE else View.VISIBLE
            text = if (on) "ON" else "OFF"
            setBackgroundResource(if (on) R.drawable.pill_on else R.drawable.pill_off)
        }
        findViewById<TextView>(R.id.statAds).text = blockedAds.get().toString()
        findViewById<TextView>(R.id.statPopups).text = blockedPopups.get().toString()
        findViewById<TextView>(R.id.statRedirects).text = blockedRedirects.get().toString()
        val total = prefs.totalBlocked + pendingTotal.get()
        findViewById<TextView>(R.id.panelTotal).text = "%,d blocked since install".format(total)
    }

    /** Pause / resume all blocking for the current site, then reload so it takes effect. */
    private fun toggleSitePause() {
        val site = HostRules.siteOf(pageHost) ?: return
        val paused = prefs.pausedSites
        prefs.pausedSites = if (site in paused) paused - site else paused + site
        protectionOn = isProtected(pageHost)
        updateShield()
        webView.reload()
    }

    private var snackUrl: String? = null
    private val hideSnack = Runnable { hideSnackbar() }

    /** Shows what was blocked, with "Open anyway" when there is a URL to open. Any thread. */
    private fun showBlocked(message: String, url: String?) = runOnUiThread {
        snackUrl = url
        findViewById<TextView>(R.id.snackText).text = message
        findViewById<View>(R.id.snackAction).visibility = if (url != null) View.VISIBLE else View.GONE
        if (snackbar.visibility != View.VISIBLE) {
            snackbar.alpha = 0f; snackbar.translationY = 40f
            snackbar.visibility = View.VISIBLE
            snackbar.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }
        snackbar.removeCallbacks(hideSnack)
        snackbar.postDelayed(hideSnack, 6_000)
    }

    private fun hideSnackbar() {
        snackbar.removeCallbacks(hideSnack)
        snackbar.animate().alpha(0f).setDuration(150).withEndAction { snackbar.visibility = View.GONE }.start()
    }

    private fun updateStreamButton() {
        val wasVisible = btnStream.visibility == View.VISIBLE
        btnStream.visibility = if (streams.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.streamCount).apply {
            visibility = if (streams.size > 1) View.VISIBLE else View.GONE
            text = streams.size.toString()
        }
        if (!wasVisible && streams.isNotEmpty()) {
            // Draw the eye once when a stream is found.
            btnStream.alpha = 0f; btnStream.scaleX = 0.85f; btnStream.scaleY = 0.85f
            btnStream.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }
    }

    private fun updateNav() {
        btnBack.alpha = if (webView.canGoBack()) 1f else 0.4f
        btnForward.alpha = if (webView.canGoForward()) 1f else 0.4f
    }

    private fun openStreamPicker() {
        val list = streams.toList()
        if (list.isEmpty()) return
        if (list.size == 1) { playNative(list[0]); return }
        val labels = list.map { Uri.parse(it.url).let { u -> "${u.host}${u.path}" } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Detected streams")
            .setItems(labels) { _, i -> playNative(list[i]) }
            .show()
    }

    /** Hands the manifest to the native player, with the headers the page used. */
    private fun playNative(s: DetectedStream) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, s.url)
                .putExtra(PlayerActivity.EXTRA_REFERER, s.referer)
                .putExtra(PlayerActivity.EXTRA_ORIGIN, s.origin)
                .putExtra(PlayerActivity.EXTRA_USER_AGENT, webView.settings.userAgentString)
                .putExtra(PlayerActivity.EXTRA_COOKIE, cookieManagerFor(inPrivate).getCookie(s.url))
                .putExtra(PlayerActivity.EXTRA_TITLE, webView.title),
        )
    }

    // endregion

    // region Shortcuts

    private lateinit var shortcutsPanel: View
    private var editingShortcuts = false

    private fun setupShortcuts() {
        shortcutsPanel = findViewById(R.id.shortcutsPanel)
        findViewById<View>(R.id.shortcutsClose).setOnClickListener { closeShortcuts() }
        findViewById<View>(R.id.homePrivate).setOnClickListener { if (inPrivate) exitPrivate() else enterPrivate() }
        findViewById<TextView>(R.id.shortcutsEdit).setOnClickListener {
            editingShortcuts = !editingShortcuts
            renderShortcuts()
            findViewById<ViewGroup>(R.id.shortcutsGrid).getChildAt(0)?.requestFocus()
            if (!editingShortcuts) findViewById<View>(R.id.shortcutsEdit).requestFocus()
        }
        findViewById<View>(R.id.addThisPage).setOnClickListener {
            val url = webView.url.orEmpty()
            addShortcut(webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host.orEmpty(), url)
        }
        val field = findViewById<EditText>(R.id.addField)
        field.setOnEditorActionListener { _, _, _ ->
            val text = field.text.toString().trim()
            if (text.isNotEmpty()) {
                val url = normalizeInput(text)
                addShortcut(prettyHost(url), url)
                field.setText("")
            }
            hideKeyboard(field); field.clearFocus(); webView.requestFocus()
            true
        }
    }

    private fun openShortcuts() {
        menuPanel.visibility = View.GONE
        shieldPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        editingShortcuts = false
        renderShortcuts()
        shortcutsPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.shortcutsScroll).scrollTo(0, 0)
        onOverlayChanged(findViewById<ViewGroup>(R.id.popularGrid).getChildAt(0))
    }

    private fun closeShortcuts() {
        if (shortcutsPanel.visibility != View.VISIBLE) return
        shortcutsPanel.visibility = View.GONE
        editingShortcuts = false
        onOverlayChanged(null)
    }

    private fun hasPage() = !webView.url.isNullOrBlank() && webView.url != "about:blank"

    private fun renderShortcuts() {
        val focusedTag = currentFocus?.tag as? String
        val favs = prefs.shortcuts
        // With no page behind it, this is the start screen: opaque and nothing to close back to.
        shortcutsPanel.setBackgroundColor(if (hasPage()) 0xF0101217.toInt() else 0xFF101217.toInt())
        findViewById<View>(R.id.shortcutsClose).visibility = if (hasPage()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.addThisPage).visibility = if (hasPage() && !isFavourite(webView.url)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.shortcutsEdit).apply {
            text = if (editingShortcuts) "Done" else "Edit"
            visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.shortcutsEmpty).visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.homeTitle).text = if (inPrivate) "Private browsing" else "Home"
        findViewById<TextView>(R.id.homePrivate).text = if (inPrivate) "Close private browsing" else "Private browsing"
        findViewById<TextView>(R.id.privateInfo).apply {
            visibility = if (inPrivate) View.VISIBLE else View.GONE
            text = buildString {
                append("Nothing from this session is kept: no history, no recently visited, no cookies, no saved logins or site data. ")
                append("It's all erased when you close private browsing or the app.")
                if (!multiProfile) append(" (This TV's WebView is older, so sites can't store cookies at all while private.)")
                append("\n\nTrackers and ads stay blocked, tracking codes are stripped from links, and sites are asked not to share your data. ")
                append("Websites, your internet provider and your network can still see what you visit. Use a VPN app on the TV to hide that.")
            }
        }
        fillGrid(R.id.popularGrid, POPULAR, height = 160, iconSize = 64, kind = "pop")
        fillGrid(R.id.shortcutsGrid, favs, height = 150, iconSize = 56, kind = "fav")
        val recent = if (inPrivate) emptyList() else prefs.recent.filterNot { r -> favs.any { it.second == r.second } }.take(10)
        findViewById<View>(R.id.recentTitle).visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        fillGrid(R.id.recentGrid, recent, height = 130, iconSize = 44, kind = "rec")

        // Keep focus where it was after a re-render (e.g. deleting a favourite in Edit mode).
        if (focusedTag != null && focusedTag.startsWith("fav:")) {
            val grid = findViewById<ViewGroup>(R.id.shortcutsGrid)
            val idx = focusedTag.substringAfter("fav:").substringBefore('|').toIntOrNull() ?: 0
            (grid.getChildAt(idx.coerceAtMost(grid.childCount - 1)) ?: findViewById(R.id.shortcutsEdit)).requestFocus()
        }
        if (!inPrivate) fetchMissingIcons(POPULAR + favs + recent)
    }

    private fun fillGrid(gridId: Int, items: List<Pair<String, String>>, height: Int, iconSize: Int, kind: String) {
        val grid = findViewById<android.widget.GridLayout>(gridId)
        grid.removeAllViews()
        val cols = grid.columnCount
        val gridWidth = resources.displayMetrics.widthPixels - dp(128)
        val gap = dp(16)
        val tileW = (gridWidth - gap * (cols - 1)) / cols
        items.forEachIndexed { i, (title, url) ->
            val tile = buildTile(title, url, iconSize, deletable = kind == "fav")
            tile.tag = "$kind:$i|$url"
            grid.addView(tile, android.widget.GridLayout.LayoutParams().apply {
                width = tileW
                this.height = dp(height)
                setMargins(0, 0, if (i % cols == cols - 1) 0 else gap, gap)
            })
        }
    }

    private fun buildTile(title: String, url: String, iconSize: Int, deletable: Boolean): View {
        val d = resources.displayMetrics.density
        val tile = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.tile_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (editingShortcuts && deletable) {
                    prefs.shortcuts = prefs.shortcuts.filterNot { it.second == url }
                    renderShortcuts()
                } else {
                    closeShortcuts()
                    load(url)
                }
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(siteAvatar(url, title), LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
        col.addView(TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(TextView(this).apply {
            text = prettyHost(url)
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tile.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (editingShortcuts && deletable) {
            tile.addView(TextView(this).apply {
                text = "✕"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFFEF4444.toInt())
                }
            }, FrameLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt(), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                setMargins(0, (8 * d).toInt(), (8 * d).toInt(), 0)
            })
        }
        return tile
    }

    // ---- Favourites / recent ----

    private fun sameUrl(a: String?, b: String?) = a != null && b != null && a.trimEnd('/') == b.trimEnd('/')
    private fun isFavourite(url: String?) = prefs.shortcuts.any { sameUrl(it.second, url) }

    private fun updateStar() {
        if (menuOpen() && !inClearMenu) buildMenu()
    }

    private fun toggleFavourite() {
        if (!hasPage()) return
        val url = webView.url ?: return
        if (isFavourite(url)) {
            prefs.shortcuts = prefs.shortcuts.filterNot { sameUrl(it.second, url) }
            note("Removed from Favourites")
        } else {
            addShortcut(webView.title?.takeIf { it.isNotBlank() } ?: prettyHost(url), url)
        }
        updateStar()
    }

    private fun rememberRecent(title: String?, url: String) {
        if (inPrivate) return
        if (url.isBlank() || url.startsWith("about:") || url.startsWith("data:")) return
        val site = HostRules.siteOf(Uri.parse(url).host) ?: return
        val entry = (title?.takeIf { it.isNotBlank() } ?: prettyHost(url)) to url
        prefs.recent = (listOf(entry) + prefs.recent.filterNot { HostRules.siteOf(Uri.parse(it.second).host) == site }).take(12)
    }

    private val iconFetchTried = HashSet<String>()

    /** Grabs the site's own touch icon / favicon for tiles we haven't seen an icon for yet. */
    private fun fetchMissingIcons(items: List<Pair<String, String>>) {
        val hosts = items.mapNotNull { Uri.parse(it.second).host }
            .filter { h -> faviconFile(h)?.exists() == false && iconFetchTried.add(h) }
        if (hosts.isEmpty()) return
        val ua = webView.settings.userAgentString ?: "" // WebView may only be touched on the UI thread
        kotlin.concurrent.thread(name = "icons") {
            var any = false
            hosts.forEach { host ->
                for (path in iconCandidates(host) + listOf("/apple-touch-icon.png", "/favicon.ico")) {
                    val bmp = runCatching {
                        val target = if (path.startsWith("http")) path else "https://$host$path"
                        val c = java.net.URL(target).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 5000; c.readTimeout = 8000
                        c.setRequestProperty("User-Agent", ua)
                        try { if (c.responseCode in 200..299) c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } else null } finally { c.disconnect() }
                    }.getOrNull()
                    if (bmp != null && bmp.width >= 16) {
                        faviconFile(host)?.outputStream()?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        any = true
                        break
                    }
                }
            }
            if (any) runOnUiThread { if (shortcutsPanel.visibility == View.VISIBLE) renderShortcuts() }
        }
    }

    /** Icons a site lists in its home page's <link rel="icon"/"apple-touch-icon">, largest first. Network thread only. */
    private fun iconCandidates(host: String): List<String> = runCatching {
        val c = java.net.URL("https://$host/").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", DESKTOP_UA) // desktop pages list the full icon set
        val html = try {
            if (c.responseCode !in 200..299) return@runCatching emptyList<String>()
            c.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream(); val chunk = ByteArray(16 * 1024)
                while (buf.size() < 300_000) { val n = input.read(chunk); if (n < 0) break; buf.write(chunk, 0, n) }
                buf.toString("UTF-8")
            }
        } finally { c.disconnect() }
        val linkRe = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        val attr = { tag: String, name: String -> Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) }
        linkRe.findAll(html).map { it.value }
            .filter { tag -> attr(tag, "rel")?.lowercase()?.let { "icon" in it } == true }
            .mapNotNull { tag ->
                val href = attr(tag, "href") ?: return@mapNotNull null
                val size = attr(tag, "sizes")?.substringBefore('x')?.toIntOrNull()
                    ?: if (attr(tag, "rel")!!.contains("apple", true)) 180 else 32
                java.net.URL(java.net.URL("https://$host/"), href.replace("&amp;", "&")).toString() to size
            }
            .filter { it.first.startsWith("https://") && !it.first.endsWith(".svg") }
            .sortedByDescending { it.second }.map { it.first }.distinct().take(3).toList()
    }.getOrDefault(emptyList())

    /** Saved favicon if we have one, otherwise a coloured letter avatar. */
    private fun siteAvatar(url: String, title: String): View {
        val d = resources.displayMetrics.density
        val icon = faviconFile(Uri.parse(url).host)?.takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
        if (icon != null) {
            return android.widget.ImageView(this).apply {
                setImageBitmap(icon)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 14 * d; setColor(android.graphics.Color.WHITE) }
                val pad = (10 * d).toInt(); setPadding(pad, pad, pad, pad)
                clipToOutline = true
            }
        }
        val site = HostRules.siteOf(Uri.parse(url).host) ?: title
        val palette = intArrayOf(0xFF6366F1.toInt(), 0xFF0EA5E9.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFFA855F7.toInt(), 0xFFEC4899.toInt())
        return TextView(this).apply {
            text = (site.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercase()
            setTextColor(android.graphics.Color.WHITE)
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(palette[Math.floorMod(site.hashCode(), palette.size)])
            }
        }
    }

    /** One icon per host (minus "www."), so youtube.com and music.youtube.com keep their own icons. */
    private fun faviconFile(host: String?): java.io.File? {
        val key = host?.lowercase()?.removePrefix("www.")?.takeIf { it.contains('.') } ?: return null
        val dir = java.io.File(filesDir, "icons2")
        if (!dir.exists()) { java.io.File(filesDir, "icons").deleteRecursively(); dir.mkdirs() } // old per-site cache
        return java.io.File(dir, "$key.png")
    }

    private fun saveFavicon(icon: Bitmap) {
        if (icon.width < 16) return
        val file = faviconFile(pageHost) ?: return
        if (file.exists() && file.length() > 0 && android.graphics.BitmapFactory.decodeFile(file.path)?.width ?: 0 >= icon.width) return
        runCatching { file.outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun prettyHost(url: String) = Uri.parse(url).host?.removePrefix("www.") ?: url

    private fun addShortcut(title: String, url: String) {
        if (url.isBlank() || url == "about:blank") return
        prefs.shortcuts = prefs.shortcuts.filterNot { sameUrl(it.second, url) } + (title to url)
        note("Added to Favourites: $title")
        updateStar()
        if (shortcutsPanel.visibility == View.VISIBLE) renderShortcuts()
    }

    // endregion

    // region Settings

    private lateinit var settingsPanel: View
    private var settingsDirty = false
    private var confirmClear = false

    private fun setupSettings() {
        settingsPanel = findViewById(R.id.settingsPanel)
        findViewById<View>(R.id.settingsDone).setOnClickListener { closeSettings() }
    }

    private fun showSettings() {
        menuPanel.visibility = View.GONE
        shieldPanel.visibility = View.GONE
        closeShortcuts()
        settingsDirty = false
        confirmClear = false
        renderSettings()
        findViewById<android.widget.ScrollView>(R.id.settingsScroll).scrollTo(0, 0)
        settingsPanel.visibility = View.VISIBLE
        onOverlayChanged(findViewById<ViewGroup>(R.id.settingsContent).findViewWithTag("first"))
    }

    private fun closeSettings() {
        currentFocus?.let { if (it is EditText) { hideKeyboard(it); it.clearFocus() } }
        settingsPanel.visibility = View.GONE
        onOverlayChanged(null)
        if (settingsDirty) {
            protectionOn = isProtected(pageHost)
            updateShield()
            if (hasPage()) webView.reload() else if (prefs.startWithShortcuts) openShortcuts()
        }
    }

    /** Settings save as you change them; closing reloads the page if anything changed. */
    private fun renderSettings() {
        val c = findViewById<LinearLayout>(R.id.settingsContent)
        val focusTag = currentFocus?.tag
        c.removeAllViews()

        section(c, "Navigation") { card ->
            card.addView(segmented(listOf("Jump between items", "Mouse pointer"), if (jumpMode) 0 else 1, first = true) { i ->
                prefs.navMode = if (i == 0) "jump" else "pointer"; applyNavMode(); renderSettings()
            })
            card.addView(TextView(this).apply {
                text = if (jumpMode) "The D-pad moves a highlight from one link or button to the next. Best for most sites."
                else "The D-pad moves a mouse pointer; push against an edge to scroll. Use it when a site's buttons can't be reached."
                setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
                setPadding(dp(16), dp(10), dp(16), dp(8))
            })
        }

        section(c, "Start screen") { card ->
            card.addView(segmented(
                listOf("Home screen", "Home page address"),
                if (prefs.startWithShortcuts) 0 else 1,
            ) { i -> prefs.startWithShortcuts = i == 0; settingsDirty = true; renderSettings() })
            card.addView(fieldRow(
                "Home page address",
                if (prefs.startWithShortcuts) "Popular sites, your Favourites and Recently visited. Switch to use a fixed page instead." else "Opens on launch and with the home button",
                prefs.homeUrl, "https://www.google.com",
            ) { prefs.homeUrl = it; settingsDirty = true })
        }

        section(c, "Protection") { card ->
            card.addView(toggleRow("Block ads & trackers", "Stops requests to known ad networks and hides ad boxes", prefs.adBlock) {
                prefs.adBlock = it; settingsDirty = true; renderSettings()
            })
            card.addView(toggleRow("Block pop-ups", "New windows that open another site", prefs.blockPopups, enabled = prefs.adBlock) {
                prefs.blockPopups = it; settingsDirty = true
            })
            card.addView(toggleRow("Block sneaky redirects", "Pages jumping to another site without you clicking", prefs.blockRedirects, enabled = prefs.adBlock) {
                prefs.blockRedirects = it; settingsDirty = true
            })
            val paused = prefs.pausedSites.sorted()
            if (paused.isNotEmpty()) {
                card.addView(subheading("Paused on these sites"))
                paused.forEach { site ->
                    card.addView(actionRow(site, null, "Resume") {
                        prefs.pausedSites = prefs.pausedSites - site; settingsDirty = true; renderSettings()
                    })
                }
            }
        }

        section(c, "Privacy") { card ->
            card.addView(actionRow(if (inPrivate) "Private browsing is on" else "Private browsing",
                "Separate session that keeps no history, cookies or site data",
                if (inPrivate) "Close" else "Open") { closeSettings(); if (inPrivate) exitPrivate() else enterPrivate() })
            card.addView(toggleRow("Remove tracking codes from links", "Strips utm_, fbclid, gclid and similar from addresses", prefs.stripTracking) {
                prefs.stripTracking = it
            })
            card.addView(toggleRow("Block third-party cookies", "Stops cross-site tracking cookies. May break some sign-ins and embedded players.", prefs.blockThirdPartyCookies) {
                prefs.blockThirdPartyCookies = it
                normalWebView?.let { w -> CookieManager.getInstance().setAcceptThirdPartyCookies(w, !it) }
                if (!inPrivate) CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !it)
                settingsDirty = true
            })
        }

        section(c, "Blocklist") { card ->
            val updated = blocker.lastUpdated()
            val ago = if (updated == 0L) "not downloaded yet" else "updated " + android.text.format.DateUtils.getRelativeTimeSpanString(updated)
            val count = blocker.hosts.size
            card.addView(actionRow(if (count == 0) "Loading blocklist…" else "%,d blocked hosts".format(count), ago, "Update now") { btn ->
                btn.text = "Updating…"
                blocker.load(prefs.blocklistUrl, force = true) { runOnUiThread { if (settingsPanel.visibility == View.VISIBLE) renderSettings() } }
            })
            card.addView(fieldRow("Extra blocklist URL", "Hosts-file format, refreshed daily. Leave empty to use only the built-in list.",
                prefs.blocklistUrl, "https://…/hosts") {
                prefs.blocklistUrl = it; settingsDirty = true
                blocker.load(it, force = true) { runOnUiThread { if (settingsPanel.visibility == View.VISIBLE) renderSettings() } }
            })
        }

        section(c, "Browsing") { card ->
            card.addView(toggleRow("Auto-hide the top bar", "Slides away while you use a page; press ▲ at the top or move the pointer up to bring it back", prefs.autoHideToolbar) {
                prefs.autoHideToolbar = it; if (!it) showBar(autoHide = false)
            })
            card.addView(toggleRow("Auto-unmute videos", "Sites start videos muted when they can't tell you're there; turn their sound on automatically", prefs.autoUnmute) {
                prefs.autoUnmute = it
                val flag = if (it) "window.__spNoUnmute = false;" else "window.__spNoUnmute = true;"
                webView.evaluateJavascript(flag, null)
                note("Takes full effect after restarting the app")
            })
            card.addView(toggleRow("Desktop sites", "Ask sites for their desktop version (bigger layouts, fewer app banners)", prefs.desktopMode) {
                prefs.desktopMode = it; applyUserAgent(); settingsDirty = true
            })
            card.addView(actionRow("Clear browsing data", "Cookies, cache and site storage. You'll be signed out of sites.",
                "Clear", danger = true) { btn ->
                if (!confirmClear) { confirmClear = true; btn.text = "Press again to confirm"; return@actionRow }
                CookieManager.getInstance().removeAllCookies(null)
                android.webkit.WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
                webView.clearHistory()
                prefs.recent = emptyList()
                confirmClear = false
                btn.text = "Cleared ✓"
                settingsDirty = true
            })
        }

        if (focusTag != null) c.findViewWithTag<View>(focusTag)?.requestFocus()

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        c.addView(TextView(this).apply {
            text = "mkTV $version"
            setTextColor(0xFF6B7280.toInt()); textSize = 13f
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        parent.addView(TextView(this).apply {
            text = title.uppercase()
            setTextColor(0xFF818CF8.toInt()); textSize = 13f; letterSpacing = 0.12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(20), 0, dp(10))
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        build(card)
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun textBlock(title: String, subtitle: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply { text = title; setTextColor(android.graphics.Color.WHITE); textSize = 17f })
        if (subtitle != null) addView(TextView(context).apply { text = subtitle; setTextColor(0xFF9CA3AF.toInt()); textSize = 13f; setPadding(0, dp(2), 0, 0) })
    }

    private fun rowContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setBackgroundResource(R.drawable.row_bg)
    }

    /** Whole row is clickable; the pill shows the state. */
    private fun toggleRow(title: String, subtitle: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): View {
        var state = on
        val pill = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minWidth = dp(64)
            setPadding(dp(14), 0, dp(14), 0)
        }
        fun paint() { pill.text = if (state) "ON" else "OFF"; pill.setBackgroundResource(if (state) R.drawable.pill_on else R.drawable.pill_off) }
        paint()
        return rowContainer().apply {
            addView(textBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(16) })
            alpha = if (enabled) 1f else 0.4f
            isClickable = enabled
            isFocusable = enabled
            tag = "row:$title"
            if (enabled) setOnClickListener { state = !state; paint(); onChange(state) }
        }
    }

    private fun actionRow(title: String, subtitle: String?, action: String, danger: Boolean = false, onClick: (TextView) -> Unit): View {
        val btn = TextView(this).apply {
            text = action
            gravity = android.view.Gravity.CENTER
            setTextColor(if (danger) 0xFFFCA5A5.toInt() else android.graphics.Color.WHITE); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(if (danger) R.drawable.btn_danger else R.drawable.toolbar_button)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            tag = "btn:$title"
        }
        btn.setOnClickListener { onClick(btn) }
        return rowContainer().apply {
            addView(textBlock(title, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(16) })
        }
    }

    /** Label + text field; saves on Done / when focus leaves. */
    private fun fieldRow(title: String, subtitle: String, value: String, hint: String, onSave: (String) -> Unit): View {
        val field = EditText(this).apply {
            setText(value); this.hint = hint
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(android.graphics.Color.WHITE); setHintTextColor(0xFF6B7280.toInt()); textSize = 15f
            setBackgroundResource(R.drawable.add_field)
            setPadding(dp(14), 0, dp(14), 0)
            tag = "field:$title"
        }
        var saved = value
        fun commit() { val v = field.text.toString().trim(); if (v != saved) { saved = v; onSave(v) } }
        field.setOnEditorActionListener { _, _, _ -> commit(); hideKeyboard(field); field.clearFocus(); true }
        field.setOnFocusChangeListener { _, has -> if (!has) commit() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(textBlock(title, subtitle))
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        }
    }

    private fun segmented(options: List<String>, selected: Int, first: Boolean = false, onSelect: (Int) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(4))
        options.forEachIndexed { i, label ->
            addView(TextView(context).apply {
                text = label
                gravity = android.view.Gravity.CENTER
                setTextColor(if (i == selected) android.graphics.Color.WHITE else 0xFFD1D5DB.toInt()); textSize = 15f
                setTypeface(typeface, if (i == selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setBackgroundResource(if (i == selected) R.drawable.seg_on else R.drawable.seg_off)
                isClickable = true
                isFocusable = true
                tag = if (first && i == selected) "first" else "seg:$label"
                setOnClickListener { if (i != selected) onSelect(i) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun subheading(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
        setPadding(dp(16), dp(14), dp(16), dp(4))
    }

    // endregion

    // region Remote control → cursor

    private val jumpMode get() = prefs.navMode == "jump"

    private fun overlayOpen() =
        shortcutsPanel.visibility == View.VISIBLE || settingsPanel.visibility == View.VISIBLE ||
            shieldPanel.visibility == View.VISIBLE || menuOpen() || remotePanel.visibility == View.VISIBLE

    // ---- Phone remote (LAN web page, paired by QR code) ----

    private val remotePanel: View by lazy { findViewById(R.id.remotePanel) }
    private val remoteServer by lazy { RemoteServer(this, this) }

    private fun showPhoneRemote() {
        closeMenu()
        remoteServer.start()
        val url = remoteServer.url()
        val steps = findViewById<TextView>(R.id.remoteSteps)
        val qr = findViewById<android.widget.ImageView>(R.id.remoteQr)
        if (url == null || !remoteServer.running) {
            steps.text = "Couldn't start the phone remote. Make sure the TV is connected to Wi-Fi or Ethernet."
            qr.setImageDrawable(null)
            findViewById<TextView>(R.id.remoteUrl).text = ""
        } else {
            steps.text = "1. Connect your phone to the same Wi-Fi as this TV.\n2. Scan this code with the phone's camera.\n3. Use the remote and keyboard in your phone's browser."
            findViewById<TextView>(R.id.remoteUrl).text = url
            qr.setImageBitmap(qrBitmap(url, dp(240)))
        }
        findViewById<View>(R.id.remoteClose).setOnClickListener { closePhoneRemote() }
        remotePanel.visibility = View.VISIBLE
        onOverlayChanged(findViewById(R.id.remoteClose))
    }

    private fun closePhoneRemote() {
        if (remotePanel.visibility != View.VISIBLE) return
        remotePanel.visibility = View.GONE
        onOverlayChanged(null)
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    private val heldKeys = HashMap<Int, Runnable>()

    override fun onKey(key: String, action: String) = runOnUiThread {
        if (key == "home") { if (action != "up") { closeMenu(); closePhoneRemote(); if (prefs.startWithShortcuts) openShortcuts() else load(normalizeInput(prefs.homeUrl)) }; return@runOnUiThread }
        val code = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK; "menu" -> KeyEvent.KEYCODE_MENU
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return@runOnUiThread
        }
        val now = SystemClock.uptimeMillis()
        when (action) {
            "down" -> {
                heldKeys.remove(code)?.let { root.removeCallbacks(it) }
                val downTime = now
                dispatchKeyEvent(KeyEvent(downTime, now, KeyEvent.ACTION_DOWN, code, 0))
                // Holding a phone button behaves like holding the remote: key repeats after 450 ms.
                val repeater = object : Runnable {
                    var count = 1
                    override fun run() {
                        val t = SystemClock.uptimeMillis()
                        dispatchKeyEvent(KeyEvent(downTime, t, KeyEvent.ACTION_DOWN, code, count++, 0, 0, 0, KeyEvent.FLAG_LONG_PRESS.takeIf { count == 2 } ?: 0))
                        root.postDelayed(this, 90)
                    }
                }
                heldKeys[code] = repeater
                root.postDelayed(repeater, 450)
            }
            "up" -> {
                heldKeys.remove(code)?.let { root.removeCallbacks(it) }
                dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
            else -> {
                dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
        }
    }

    /** Types into whatever has focus: an app text field, or the web page (inputs, YouTube TV search, ...). */
    override fun onText(text: String, enter: Boolean) = runOnUiThread {
        val target: View = currentFocus ?: webView
        val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
        if (events != null) events.forEach { target.dispatchKeyEvent(it) }
        else (target as? EditText)?.append(text)
        if (enter) {
            target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    override fun onOpen(url: String) = runOnUiThread {
        if (url.isBlank()) return@runOnUiThread
        closeMenu(); closePhoneRemote(); closeShortcuts()
        load(normalizeInput(url))
    }

    override fun state(): org.json.JSONObject {
        val latch = java.util.concurrent.CountDownLatch(1)
        val o = org.json.JSONObject()
        runOnUiThread { o.put("title", webView.title ?: "mkTV").put("url", webView.url ?: ""); latch.countDown() }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        return o
    }

    override fun onPhoneConnected() = runOnUiThread {
        closePhoneRemote()
        note("Phone remote connected")
    }

    // ---- ⋮ menu ----

    private val menuPanel: LinearLayout by lazy { findViewById(R.id.menuPanel) }
    private fun menuOpen() = menuPanel.visibility == View.VISIBLE

    private fun toggleMenu() { if (menuOpen()) closeMenu() else openMenu() }

    private fun openMenu() {
        shieldPanel.visibility = View.GONE
        inClearMenu = false
        buildMenu()
        menuPanel.visibility = View.VISIBLE
        onOverlayChanged(menuPanel.getChildAt(0))
    }

    private fun closeMenu() {
        if (!menuOpen()) return
        menuPanel.visibility = View.GONE
        onOverlayChanged(null)
    }

    private fun buildMenu() {
        val focusedTag = currentFocus?.tag
        menuPanel.removeAllViews()
        val fav = isFavourite(webView.url)
        fun item(icon: Int, label: String, state: String? = null, enabled: Boolean = true, action: () -> Unit) {
            menuPanel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(52)
                setPadding(dp(14), 0, dp(14), 0)
                setBackgroundResource(R.drawable.row_bg)
                isClickable = enabled; isFocusable = enabled; alpha = if (enabled) 1f else 0.4f
                tag = "menu:$label"
                addView(android.widget.ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(22), dp(22)))
                addView(TextView(context).apply {
                    text = label; setTextColor(android.graphics.Color.WHITE); textSize = 16f; setPadding(dp(14), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (state != null) addView(TextView(context).apply { text = state; setTextColor(0xFF9CA3AF.toInt()); textSize = 14f })
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        item(if (fav) R.drawable.ic_star else R.drawable.ic_star_border, if (fav) "Remove from Favourites" else "Add to Favourites", enabled = hasPage()) {
            toggleFavourite(); buildMenu()
        }
        item(if (inPrivate) R.drawable.ic_private_on else R.drawable.ic_private, if (inPrivate) "Close private browsing" else "Private browsing") {
            closeMenu(); if (inPrivate) exitPrivate() else enterPrivate()
        }
        item(if (jumpMode) R.drawable.ic_mode_jump else R.drawable.ic_mode_pointer, "Navigation", if (jumpMode) "Jump" else "Pointer") {
            prefs.navMode = if (jumpMode) "pointer" else "jump"
            applyNavMode(); buildMenu()
        }
        item(R.drawable.ic_desktop, "Desktop site", if (prefs.desktopMode) "On" else "Off") {
            prefs.desktopMode = !prefs.desktopMode; applyUserAgent(); if (hasPage()) webView.reload(); buildMenu()
        }
        item(R.drawable.ic_phone, "Phone remote") { showPhoneRemote() }
        item(R.drawable.ic_delete, "Clear browsing data…") { showClearMenu() }
        item(R.drawable.ic_settings, "Settings") { closeMenu(); showSettings() }
        if (focusedTag != null) menuPanel.findViewWithTag<View>(focusedTag)?.requestFocus()
    }

    // ---- Clear browsing data (inside the ⋮ menu) ----

    private var clearHistory = true
    private var clearCookies = false
    private var clearCache = true
    private var inClearMenu = false

    private fun showClearMenu() {
        inClearMenu = true
        menuPanel.removeAllViews()
        menuPanel.addView(TextView(this).apply {
            text = "Clear browsing data"
            setTextColor(android.graphics.Color.WHITE); textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(6))
        })
        fun check(label: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(56)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setBackgroundResource(R.drawable.row_bg)
                isClickable = true; isFocusable = true
                tag = "clear:$label"
            }
            val box = TextView(this).apply {
                gravity = android.view.Gravity.CENTER; textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            fun paint() { box.text = if (get()) "✓" else ""; box.setBackgroundResource(if (get()) R.drawable.pill_on else R.drawable.pill_off) }
            paint()
            row.addView(box, LinearLayout.LayoutParams(dp(26), dp(26)))
            row.addView(textBlock(label, sub).apply { setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener { set(!get()); paint() }
            menuPanel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        check("History", "Back/forward history and Recently visited", { clearHistory }) { clearHistory = it }
        check("Cookies & site data", "Signs you out of most sites", { clearCookies }) { clearCookies = it }
        check("Cached images & files", "Frees space; pages load slower once", { clearCache }) { clearCache = it }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(dp(8), dp(10), dp(8), dp(6))
        }
        fun button(label: String, bg: Int, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label; gravity = android.view.Gravity.CENTER; textSize = 15f
            setTextColor(color); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(bg); setPadding(dp(18), 0, dp(18), 0)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        buttons.addView(button("Cancel", R.drawable.toolbar_button, android.graphics.Color.WHITE) { inClearMenu = false; buildMenu(); menuPanel.getChildAt(0)?.requestFocus() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        buttons.addView(button("Clear", R.drawable.btn_danger, 0xFFFCA5A5.toInt()) { clearData() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(10) })
        menuPanel.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        menuPanel.getChildAt(1)?.requestFocus()
    }

    private fun clearData() {
        val what = mutableListOf<String>()
        if (clearHistory) {
            listOfNotNull(normalWebView, webView).forEach { it.clearHistory() }
            prefs.recent = emptyList()
            what += "history"
        }
        if (clearCookies) {
            CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
            what += "cookies & site data"
        }
        if (clearCache) {
            webView.clearCache(true)
            what += "cache"
        }
        inClearMenu = false
        closeMenu()
        updateNav()
        note(if (what.isEmpty()) "Nothing selected" else "Cleared " + what.joinToString(", "))
        if (shortcutsPanel.visibility == View.VISIBLE) renderShortcuts()
    }

    private fun isInside(parent: View, v: View?): Boolean {
        var p: Any? = v
        while (p is View) { if (p === parent) return true; p = p.parent }
        return false
    }

    /** True when D-pad keys should drive the web page (pointer or jump), not native focus. */
    private fun pageActive(): Boolean {
        val f = currentFocus
        return !overlayOpen() && !isInside(toolbar, f) && !isInside(snackbar, f) && f !is EditText
    }

    /** Overlays use normal TV focus; the page underneath must not steal it. */
    private fun onOverlayChanged(focusTarget: View?) {
        if (overlayOpen()) showBar(autoHide = false) else scheduleHideBar(2500)
        webContainer.descendantFocusability =
            if (overlayOpen()) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (focusTarget != null) focusTarget.requestFocus() else if (!overlayOpen()) webView.requestFocus()
        refreshCursor()
    }

    private fun applyNavMode() {
        cursor.jumpMode = jumpMode
        if (!jumpMode) cursor.showRing(null)
        refreshCursor()
    }

    /** Pointer/ring only shows while the page has the remote's attention. */
    private fun refreshCursor() {
        val active = pageActive()
        cursor.visibility = if (active && !siteRemote) View.VISIBLE else View.INVISIBLE
        root.removeCallbacks(pollRing)
        if (active) root.postDelayed(pollRing, 350)
        if (watching) cursor.visibility = View.INVISIBLE
    }

    private fun leaveToPage() {
        (currentFocus as? EditText)?.let { hideKeyboard(it); it.clearFocus() }
        webView.requestFocus()
        refreshCursor()
        if (jumpMode && cursor.ringCenter() == null) jump("none")
    }

    private fun focusToolbarNear(x: Float) {
        val bar = toolbar as ViewGroup
        var best: View? = null; var bestD = Float.MAX_VALUE
        for (i in 0 until bar.childCount) {
            val v = bar.getChildAt(i)
            if (v.visibility != View.VISIBLE || !v.isFocusable) continue
            val d = kotlin.math.abs(v.x + v.width / 2f - x)
            if (d < bestD) { bestD = d; best = v }
        }
        (best ?: urlField).requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isKeyboardVisible()) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        val f = currentFocus
        // ⋮ menu from anywhere: page, home screen, toolbar or an open panel.
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_BOOKMARK) {
            if (event.action == KeyEvent.ACTION_UP) toggleMenu()
            return true
        }
        // Toolbar / overlays / bottom bar: plain TV focus navigation.
        if (!pageActive()) {
            val leaving = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && down && !overlayOpen() &&
                (f === urlField || (isInside(toolbar, f) && f !is EditText))
            if (leaving) { leaveToPage(); return true }
            return super.dispatchKeyEvent(event)
        }
        if (customView != null && (handleMediaKey(event) || (watching && handleWatchKey(event)))) return true
        // Fullscreen video in jump mode: let the player have the arrows, OK taps the middle.
        if (customView != null && jumpMode) {
            if (isOk(event.keyCode)) { tapAt(event, root.width / 2f, root.height / 2f); return true }
            return super.dispatchKeyEvent(event)
        }
        if (siteRemote && handleSiteRemote(event)) return true
        if (down && event.repeatCount == 0) scheduleHideBar(400)
        if (handleMediaKey(event)) return true
        if (watching && handleWatchKey(event)) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BOOKMARK -> {
                if (event.action == KeyEvent.ACTION_UP) toggleMenu()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) scrollAtCursor(0, -6)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) scrollAtCursor(0, 6)
                return true
            }
        }
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> CursorView.L
            KeyEvent.KEYCODE_DPAD_RIGHT -> CursorView.R
            KeyEvent.KEYCODE_DPAD_UP -> CursorView.U
            KeyEvent.KEYCODE_DPAD_DOWN -> CursorView.D
            else -> -1
        }
        if (jumpMode) {
            if (dir >= 0) {
                val vertical = dir == CursorView.U || dir == CursorView.D
                when {
                    !down -> holdJumped = false
                    // Holding ▲/▼ jumps to the site's fixed top/bottom bar (YouTube: Home · Shorts · You).
                    vertical && event.repeatCount == 1 && !holdJumped -> {
                        holdJumped = true
                        jumpBusy = false
                        jump(if (dir == CursorView.D) "barDown" else "barUp")
                    }
                    holdJumped -> Unit
                    else -> jump(arrayOf("left", "right", "up", "down")[dir])
                }
                return true
            }
            if (isOk(event.keyCode)) {
                val c = cursor.ringCenter()
                if (c == null) { if (down) jump("none"); return true }
                tapAt(event, c.x, c.y)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (dir >= 0) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) cursor.press(dir)
                KeyEvent.ACTION_UP -> cursor.release(dir)
            }
            return true
        }
        if (isOk(event.keyCode)) {
            cursor.wake()
            tapAt(event, cursor.x0, cursor.y0)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private var backHeld = false

    /** YouTube TV: arrows/OK/media go to the page; Back = YouTube's back; hold Back = mkTV home. */
    private fun handleSiteRemote(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (down && e.repeatCount == 0) { backHeld = false; return true }
                if (down && e.repeatCount >= 1 && !backHeld) { backHeld = true; showBar(autoHide = false); openShortcuts(); return true }
                if (!down && !backHeld) {
                    webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                    webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> return false
        }
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP || e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || isOk(e.keyCode) || KeyEvent.isMediaSessionKey(e.keyCode) ||
            e.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || e.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
            if (webView.findFocus() == null) webView.requestFocus()
            webView.dispatchKeyEvent(e)
            if (down && !tvTipShown) { tvTipShown = true; showTip("YouTube TV mode · hold Back for mkTV home") }
            return true
        }
        return false
    }
    private var tvTipShown = false

    private fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A

    /** OK down/up become touch down/up at (x, y), so holding OK is a long-press. */
    private fun tapAt(event: KeyEvent, x: Float, y: Float) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) touchAt(MotionEvent.ACTION_DOWN, x, y)
        else if (event.action == KeyEvent.ACTION_UP) touchAt(MotionEvent.ACTION_UP, x, y)
    }

    // ---- Jump mode ----

    private var jumpBusy = false
    private var holdJumped = false
    private val barHintShown = HashSet<String>()

    /** Asks the page script to move the highlight; "none" just picks something on screen. */
    private fun jump(dir: String) {
        if (jumpBusy || !hasPage()) return // drop presses while one is in flight (held D-pad)
        jumpBusy = true
        val js = if (dir == "none") "window.__sp ? (__sp.rect() || __sp.move('down')) : null" else "window.__sp ? __sp.move('$dir') : null"
        webView.evaluateJavascript(js) { res ->
            jumpBusy = false
            val obj = parseJs(res) ?: return@evaluateJavascript
            if (obj.has("nobar")) {
                if (obj.getString("nobar") == "top") { showBar(autoHide = false); focusToolbarNear(cursor.ringCenter()?.x ?: root.width / 2f) }
                return@evaluateJavascript
            }
            if (obj.has("edge")) {
                if (obj.getString("edge") == "up") { showBar(autoHide = false); focusToolbarNear(cursor.ringCenter()?.x ?: root.width / 2f) }
                return@evaluateJavascript
            }
            showRingFor(obj)
            maybeHintBars()
            // Re-measure once layout settles (lazy images, sticky headers moving).
            root.postDelayed({ if (jumpMode && !watching) webView.evaluateJavascript("window.__sp ? __sp.rect() : null") { r -> parseJs(r)?.let { if (!it.has("edge")) showRingFor(it) } } }, 160)
        }
    }

    private var pollTick = 0
    private fun maybeHintBars() {
        val host = pageHost ?: return
        if (host in barHintShown) return
        barHintShown += host
        webView.evaluateJavascript("window.__sp ? __sp.bars() : null") { res ->
            val o = parseJs(res) ?: return@evaluateJavascript
            if (o.optBoolean("bottom")) showTip("Hold ▼ to reach the site's bottom menu")
            else if (o.optBoolean("top")) showTip("Hold ▲ to reach the site's top menu")
        }
    }

    /** Small, quiet hint chip at the bottom (reuses the watch dock look). */
    private fun showTip(text: String) {
        val dock = findViewById<LinearLayout>(R.id.watchDock)
        dock.removeAllViews()
        dock.addView(TextView(this).apply {
            this.text = text; setTextColor(0xFFE5E7EB.toInt()); textSize = 15f
            setPadding(dp(16), 0, dp(16), 0)
        })
        if (dock.visibility != View.VISIBLE) {
            dock.alpha = 0f; dock.translationY = dp(30).toFloat(); dock.visibility = View.VISIBLE
            dock.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        root.removeCallbacks(hideDockRunnable)
        root.postDelayed(hideDockRunnable, 3500)
    }

    private val pollRing = object : Runnable {
        override fun run() {
            if (!pageActive()) return
            if (siteRemote) { cursor.showRing(null); root.postDelayed(this, 700); return }
            if (jumpMode && !watching && !jumpBusy && hasPage()) webView.evaluateJavascript("window.__sp ? __sp.rect() : null") { res ->
                val obj = parseJs(res)
                if (obj == null) cursor.showRing(null) else if (!obj.has("edge")) showRingFor(obj)
            }
            if (++pollTick % 3 == 0 && hasPage()) checkWatch()
            root.postDelayed(this, 350)
        }
    }

    private fun parseJs(res: String?): org.json.JSONObject? {
        if (res.isNullOrEmpty() || res == "null") return null
        return runCatching { org.json.JSONObject(org.json.JSONTokener(res).nextValue() as String) }.getOrNull()
    }

    /** Page CSS px → screen px: scale by actual WebView width / page innerWidth, offset by WebView position. */
    private fun showRingFor(o: org.json.JSONObject) {
        val iw = o.optDouble("iw", 0.0)
        if (iw <= 0) return
        val scale = (webView.width / iw).toFloat()
        val loc = IntArray(2).also { webView.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val ox = (loc[0] - rootLoc[0]).toFloat(); val oy = (loc[1] - rootLoc[1]).toFloat()
        val l = ox + o.getDouble("x").toFloat() * scale
        val t = oy + o.getDouble("y").toFloat() * scale
        val r = android.graphics.RectF(l, t, l + o.getDouble("w").toFloat() * scale, t + o.getDouble("h").toFloat() * scale)
        // Clip to the web area so the ring never draws over the toolbar.
        val area = android.graphics.RectF(ox, oy, ox + webView.width, oy + webView.height)
        if (!r.intersect(area)) { cursor.showRing(null); return }
        cursor.showRing(r)
    }

    private fun touchAt(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) {
            clickDownTime = now
            clickX = x; clickY = y
            if (shieldPanel.visibility == View.VISIBLE && !hit(shieldPanel) && !hit(btnShield)) closeShieldPanel()
            if (menuOpen() && !hit(menuPanel) && !hit(findViewById(R.id.btnMenu))) closeMenu()
        }
        if (clickDownTime == 0L) return
        // Up goes where down went, even if the ring moved in between.
        val ev = MotionEvent.obtain(clickDownTime, now, action, clickX, clickY, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(ev)
        ev.recycle()
        if (action == MotionEvent.ACTION_UP) clickDownTime = 0L
    }
    private var clickX = 0f
    private var clickY = 0f

    private fun hit(v: View): Boolean {
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val x = cursor.x0 + rootLoc[0]; val y = cursor.y0 + rootLoc[1]
        return x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
    }

    /** Mouse-wheel scroll at the pointer, so inner scroll areas scroll too. */
    private fun scrollAtCursor(dx: Int, dy: Int) {
        // Overlay panels scroll themselves; the page underneath shouldn't move.
        val overlay = when {
            settingsPanel.visibility == View.VISIBLE -> findViewById<View>(R.id.settingsScroll)
            shortcutsPanel.visibility == View.VISIBLE -> findViewById<View>(R.id.shortcutsScroll)
            else -> null
        }
        if (overlay != null) { overlay.scrollBy(0, dy * dp(14)); return }
        val target: View = customView ?: webView
        val loc = IntArray(2).also { target.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val px = if (jumpMode) cursor.ringCenter()?.x ?: (root.width / 2f) else cursor.x0
        val py = if (jumpMode) cursor.ringCenter()?.y ?: (root.height / 2f) else cursor.y0
        val x = (px + rootLoc[0] - loc[0]).coerceIn(0f, target.width - 1f)
        val y = (py + rootLoc[1] - loc[1]).coerceIn(0f, target.height - 1f)

        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x; this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy.toFloat())
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx.toFloat())
        }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    // ---- Auto-hiding top bar ----

    private var barShown = true
    private val hideBarRunnable = Runnable { hideBar() }

    private fun canHideBar() = prefs.autoHideToolbar && hasPage() && pageActive() && !overlayOpen() &&
        progress.visibility != View.VISIBLE && customView == null

    private fun showBar(autoHide: Boolean = true) {
        root.removeCallbacks(hideBarRunnable)
        if (!barShown) {
            barShown = true
            topBar.animate().translationY(0f).setDuration(180).start()
        }
        if (autoHide) scheduleHideBar(3500)
    }

    private fun scheduleHideBar(delay: Long) {
        root.removeCallbacks(hideBarRunnable)
        if (prefs.autoHideToolbar) root.postDelayed(hideBarRunnable, delay)
    }

    private fun hideBar() {
        if (!barShown || !canHideBar()) return
        barShown = false
        topBar.animate().translationY(-topBar.height.toFloat()).setDuration(220).start()
    }

    // ---- Watch mode: the remote controls the playing video directly ----

    private var watching = false
    private var watchVertical = false
    private var watchDismissedFor: String? = null
    private var noVideoChecks = 0

    private fun checkWatch() {
        if (customView != null) return
        webView.evaluateJavascript("window.__sp ? __sp.media() : null") { res ->
            val o = parseJs(res) ?: return@evaluateJavascript
            val has = o.optBoolean("has")
            noVideoChecks = if (has) 0 else noVideoChecks + 1
            when {
                has && o.optBoolean("playing") && !watching && watchDismissedFor != webView.url -> enterWatch(o.optBoolean("vertical"))
                // Feeds briefly have no big video between items; only leave after ~3 s without one.
                noVideoChecks >= 3 && watching -> exitWatch(silent = true)
                has && watching -> watchVertical = o.optBoolean("vertical")
            }
        }
    }

    private fun enterWatch(vertical: Boolean) {
        watching = true
        watchVertical = vertical
        cursor.showRing(null)
        cursor.visibility = View.INVISIBLE
        hideBar()
        showDock()
    }

    private val theater get() = customView != null

    private fun toggleTheater() {
        if (customView != null) { exitFullscreen(notifyPage = true); return }
        // A key press counts as user activation, which the Fullscreen API requires.
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F12))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F12))
        webView.evaluateJavascript("window.__sp ? __sp.fullscreen() : null") { res ->
            if (res?.trim('"') == "on") flash("⛶")
        }
    }

    private fun exitWatch(silent: Boolean = false) {
        if (!watching) return
        watching = false
        webView.evaluateJavascript("window.__sp && __sp.clear()", null)
        cursor.showRing(null)
        if (!silent) watchDismissedFor = webView.url
        hideDock()
        refreshCursor()
        if (!silent && jumpMode) jump("none")
    }

    /** Keys while watching. Vertical feeds (Shorts, TikTok): ▲▼ = previous/next. ◀▶ seek, OK pause. */
    private fun handleWatchKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!watchVertical) {
                    // Normal video: ▼ = full screen, ▲ = leave full screen / back to browsing the page.
                    if (down && e.repeatCount == 0) {
                        if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { if (!theater) toggleTheater() else showDock() }
                        else if (theater) toggleTheater() else exitWatch()
                    }
                    return true
                }
                // Scroll the feed one item. (Sending DPAD keys to the WebView made Chromium
                // move its own focus ring onto the site's header instead.)
                if (down && e.repeatCount == 0) {
                    val step = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    webView.evaluateJavascript("window.__sp && __sp.feed($step)", null)
                    showDock(); flash(if (step < 0) "▲" else "▼")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    val secs = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10 else 10
                    webView.evaluateJavascript("window.__sp && __sp.seek($secs)", null)
                    flash(if (secs < 0) "⟲ 10" else "10 ⟳"); showDock()
                }
                return true
            }
        }
        if (isOk(e.keyCode)) {
            if (down && e.repeatCount == 0) togglePlay()
            return true
        }
        return false
    }

    /** Remote media keys work on any page with a video. */
    private fun handleMediaKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (down) togglePlay(); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { webView.evaluateJavascript("window.__sp && __sp.seek(10)", null); flash("10 ⟳") }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { webView.evaluateJavascript("window.__sp && __sp.seek(-10)", null); flash("⟲ 10") }; return true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (down) webView.evaluateJavascript("window.__sp && __sp.feed(${if (e.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) 1 else -1})", null)
                return true
            }
        }
        return false
    }

    private fun togglePlay() {
        webView.evaluateJavascript("window.__sp ? __sp.toggle() : null") { res ->
            when (res?.trim('"')) {
                "paused" -> flash("❚❚")
                "playing" -> flash("▶")
            }
        }
        if (watching) showDock()
    }

    private val hideDockRunnable = Runnable { hideDock() }

    private fun showDock() {
        val dock = findViewById<LinearLayout>(R.id.watchDock)
        dock.removeAllViews()
        val hints = buildList {
            if (watchVertical) { add("▲" to "Previous"); add("▼" to "Next") }
            else if (theater) add("▲" to "Exit full screen") else add("▼" to "Full screen")
            add("◀ ▶" to "10s"); add("OK" to "Pause / play")
            add("Back" to (if (theater) "Exit full screen" else "Browse page"))
        }
        hints.forEach { (key, label) ->
            dock.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                addView(TextView(context).apply {
                    text = key; setTextColor(android.graphics.Color.WHITE); textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setBackgroundResource(R.drawable.key_chip); setPadding(dp(8), dp(3), dp(8), dp(3))
                })
                addView(TextView(context).apply {
                    text = label; setTextColor(0xFFD1D5DB.toInt()); textSize = 14f; setPadding(dp(8), 0, 0, 0)
                })
            })
        }
        if (dock.visibility != View.VISIBLE) {
            dock.alpha = 0f; dock.translationY = dp(30).toFloat(); dock.visibility = View.VISIBLE
            dock.animate().alpha(1f).translationY(0f).setDuration(200).start()
        }
        root.removeCallbacks(hideDockRunnable)
        root.postDelayed(hideDockRunnable, 2500)
    }

    private fun hideDock() {
        root.removeCallbacks(hideDockRunnable)
        val dock = findViewById<View>(R.id.watchDock)
        if (dock.visibility != View.VISIBLE) return
        dock.animate().alpha(0f).translationY(dp(30).toFloat()).setDuration(200).withEndAction { dock.visibility = View.GONE }.start()
    }

    private fun flash(symbol: String) {
        val f = findViewById<TextView>(R.id.watchFlash)
        f.text = symbol
        f.animate().cancel()
        f.alpha = 1f; f.scaleX = 0.8f; f.scaleY = 0.8f; f.visibility = View.VISIBLE
        f.animate().scaleX(1f).scaleY(1f).setDuration(150).withEndAction {
            f.animate().alpha(0f).setStartDelay(350).setDuration(250).withEndAction { f.visibility = View.GONE }.start()
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> exitFullscreen(notifyPage = true)
            remotePanel.visibility == View.VISIBLE -> closePhoneRemote()
            menuOpen() && inClearMenu -> { inClearMenu = false; buildMenu(); menuPanel.getChildAt(0)?.requestFocus() }
            menuOpen() -> closeMenu()
            watching && theater -> toggleTheater()
            watching -> exitWatch()
            settingsPanel.visibility == View.VISIBLE -> closeSettings()
            shieldPanel.visibility == View.VISIBLE -> closeShieldPanel()
            shortcutsPanel.visibility == View.VISIBLE && editingShortcuts -> { editingShortcuts = false; renderShortcuts() }
            shortcutsPanel.visibility == View.VISIBLE && hasPage() -> closeShortcuts()
            isInside(toolbar, currentFocus) -> leaveToPage()
            webView.canGoBack() -> webView.goBack()
            SystemClock.uptimeMillis() - lastBackPress < 2000 -> @Suppress("DEPRECATION") super.onBackPressed()
            else -> { lastBackPress = SystemClock.uptimeMillis(); note("Press Back again to exit") }
        }
    }

    // endregion

    // region Helpers

    private fun isKeyboardVisible(): Boolean =
        ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun hideKeyboard(view: View = urlField) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private var lastToast: Toast? = null
    private fun note(msg: String) = runOnUiThread {
        lastToast?.cancel()
        lastToast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { closeShortcuts(); load(normalizeInput(it)) }
    }

    override fun onPause() {
        super.onPause()
        val add = pendingTotal.getAndSet(0)
        if (add > 0) prefs.totalBlocked += add
        cursor.releaseAll()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        hideSystemBars()
    }

    /** The phone remote only listens while mkTV is on screen; it comes back with the same QR code. */
    private var remoteWasRunning = false

    override fun onStart() {
        super.onStart()
        if (remoteWasRunning) remoteServer.start()
    }

    override fun onStop() {
        super.onStop()
        remoteWasRunning = remoteServer.running
        if (remoteWasRunning) remoteServer.stop()
    }

    override fun onDestroy() {
        remoteServer.stop()
        privateWebView?.destroy()
        (normalWebView ?: webView).destroy()
        if (multiProfile) runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
        super.onDestroy()
    }

    // endregion

    companion object {
        /** Curated "Popular" row on the home screen. */
        val POPULAR = listOf(
            "YouTube" to "https://www.youtube.com/tv",
            "YouTube Music" to "https://music.youtube.com/",
            "TikTok" to "https://www.tiktok.com/foryou",
            "Twitch" to "https://www.twitch.tv/",
            "Dailymotion" to "https://www.dailymotion.com/",
        )

        private const val PRIVATE_PROFILE = "private"

        /** A Smart-TV browser UA: YouTube serves its remote-friendly TV interface to it. */
        private const val TV_UA =
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.5 TV Safari/537.36"

        private val DESKTOP_ONLY = setOf("tiktok.com")

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /** Turns address-bar input into a URL: adds https:// or makes it a search. */
        fun normalizeInput(input: String): String = HostRules.stripTracking(normalizeRaw(input))

        private fun normalizeRaw(input: String): String {
            val t = input.trim()
            if (t.isEmpty()) return "about:blank"
            if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t) || t.startsWith("about:")) return t
            if (!t.contains(' ') && (t.contains('.') || t.startsWith("localhost"))) return "https://$t"
            return "https://www.google.com/search?q=" + Uri.encode(t)
        }
    }
}
