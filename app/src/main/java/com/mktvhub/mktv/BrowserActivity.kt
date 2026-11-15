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
        blocker = AdBlocker(this, prefs) // lists load in onResume

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
        setupHistory()
        setupSettings()
        setupToolbar()
        cursor.onEdgeScroll = ::scrollAtCursor
        cursor.onMove = { _, y -> if (y < topBar.height) showBar(autoHide = false) else if (barShown) scheduleHideBar(1500) }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (suggestOpen() && !renderingSuggestions && now !== urlField && !isInside(suggestPanel, now)) hideSuggestions()
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
        // After the first page has had a head start; at most once a day, silent on failure.
        root.postDelayed({ checkForUpdate() }, 4000)
    }

    // region WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Leftover private profile from a crash or force-stop: wipe it.
        if (multiProfile) runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
        showTab(newTab(isPrivate = false))
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
    /** The one place every tab's WebView is made (normal and private), so all share the same clients, scripts and policy. */
    private fun createWebView(isPrivate: Boolean, tab: Tab): WebView {
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
                    if (tab.active) countBlocked(blockedAds) // counters and streams belong to the tab on screen
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                val s = url.toString()
                // Skip variant playlists that live under an already-seen master playlist.
                if (tab.active && HostRules.isStreamUrl(s) && streams.none { it.url == s || HostRules.isVariantOf(s, it.url) }) {
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
                    // On this view, not load(): a background tab navigating itself must not load into the tab on screen.
                    view.settings.userAgentString = uaFor(url.toString()); view.loadUrl(url.toString()); return true
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
                    tab.host != null && !HostRules.sameSite(tab.host, url.host)
                ) {
                    // Script-driven jump to another site without a click: classic ad redirect.
                    countBlocked(blockedRedirects)
                    showBlocked("Blocked redirect · ${url.host}", url.toString())
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.host = Uri.parse(url).host
                tab.icon = null
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
                filling = false; applyZoom(view, url)
                updateNav()
                updateStar()
            }

            // First paint of the new document: apply its zoom before the user sees it at 100 %.
            override fun onPageCommitVisible(view: WebView, url: String) { if (view === webView) applyZoom(view, url) }

            override fun onPageFinished(view: WebView, url: String) {
                if (view !== webView) return
                rememberRecent(view.title, url)
                updateStar()
                scheduleHideBar(3000)
                if (!docStartScript) view.evaluateJavascript(pageScript, null)
                applyZoom(view, url)
                applySpeed(view, url)
                if (!urlField.hasFocus()) urlField.setText(url)
                updateNav()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = updateNav()
        }

        // Downloads: confirm, then DownloadManager with this WebView's (possibly private) cookies, its user agent and the page as Referer.
        wv.setDownloadListener { url, ua, disposition, mime, length ->
            // Without profiles the private session has no cookie jar of its own: send none rather than the normal ones.
            val cookie = if (url.startsWith("http") && (!isPrivate || multiProfile)) runCatching { cookieManagerFor(isPrivate).getCookie(url) }.getOrNull() else null
            // A file typed into the address bar leaves no page behind: show Home rather than a blank screen.
            if (wv === webView && wv.copyBackForwardList().size == 0) openShortcuts()
            downloads.offer(wv, isPrivate, url, ua, disposition, mime, length, cookie)
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val drm = android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                if (drm in request.resources) request.grant(arrayOf(drm)) else request.deny()
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== webView) return
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                if (filling) exitFill() // the page's real full screen took over
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
                        (blocker.isBlocked(host) || (prefs.blockPopups && !HostRules.sameSite(tab.host, host)))
                    if (blocked) {
                        countBlocked(blockedPopups)
                        showBlocked("Blocked pop-up · $host", url, newTab = true)
                    } else openInNewTab(url, tab.isPrivate) // a private tab's pop-ups stay private
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

            override fun onReceivedTitle(view: WebView, title: String?) {
                // Many pages set their real title after onPageFinished; keep the history entry's title current.
                val url = view.url ?: return
                if (view === webView && !inPrivate && title != null && !title.startsWith("http")) {
                    val list = prefs.history
                    val updated = History.retitle(list, url, title)
                    if (updated !== list) prefs.history = updated
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                tab.icon = icon // for the tab switcher, memory only
                // YouTube's TV interface reports the YouTube TV app icon; keep the normal YouTube icon for the tile.
                if (view === webView && !inPrivate && !isTvSite(view.url)) saveFavicon(icon)
            }

            // Hide the grey "play" placeholder WebView shows before a video starts.
            override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        return wv
    }

    private fun applyUserAgent() {
        tabs.mapNotNull { it.webView }.forEach {
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

    /** Private browsing is simply "the tab on screen is a private tab"; its pop-ups and new tabs are private too. */
    private val inPrivate get() = ::activeTab.isInitialized && activeTab.isPrivate

    private fun enterPrivate() {
        if (inPrivate) return
        // Without profile support, the only way to keep this session's cookies out of the normal jar is to refuse them.
        if (!multiProfile) CookieManager.getInstance().setAcceptCookie(false)
        showTab(newTab(isPrivate = true))
        // Keep private pages out of Recents thumbnails and screenshots (release builds; debug stays capturable for testing).
        if (!BuildConfig.DEBUG) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        openShortcuts()
    }

    /** Closes every private tab, then deletes the private profile (or lets cookies back in). */
    private fun exitPrivate() {
        if (tabs.none { it.isPrivate }) return
        showTab(tabs.filter { !it.isPrivate }.maxByOrNull { it.lastUsed } ?: newTab(isPrivate = false))
        tabs.filter { it.isPrivate }.forEach { destroyTab(it) }
        tabs.removeAll { it.isPrivate }
        privateZoom.clear() // private zoom levels live only as long as private browsing
        privateSpeed.clear()
        downloads.dropPrivate()
        if (multiProfile) {
            // The profile can only be deleted once all its WebViews are gone.
            root.postDelayed({ runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) } }, 600)
        } else {
            CookieManager.getInstance().setAcceptCookie(true)
        }
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        note("Private browsing closed. History, cookies and site data from it are gone.")
        if (!tabHasPage(activeTab)) openShortcuts() else if (shortcutsPanel.visibility == View.VISIBLE) renderShortcuts()
    }

    // ---- Tabs: a few WebViews kept alive, the rest reload when shown (see Tabs.kt) ----

    private val tabs = mutableListOf<Tab>()
    private lateinit var activeTab: Tab
    private var tabSeq = 0
    private var tabUse = 0L

    private fun newTab(isPrivate: Boolean): Tab = Tab(++tabSeq, isPrivate).also { tabs += it }
    private fun tabById(id: Int) = tabs.firstOrNull { it.id == id }
    /** Like hasPage(), but also true for a sleeping tab whose page is only starting to reload. */
    private fun tabHasPage(t: Tab) = t.currentUrl.let { it.isNotBlank() && it != "about:blank" }
    /** Tabs of the current kind: the switcher never mixes private and normal tabs. */
    private fun modeTabs() = tabs.filter { it.isPrivate == inPrivate }

    /** The tab's WebView, created (and its page restored or reloaded) if it was discarded. */
    private fun liveWebView(t: Tab): WebView {
        t.webView?.let { return it }
        val wv = createWebView(t.isPrivate, t)
        t.webView = wv
        val saved = t.state
        t.state = null
        if (t.url.isNotBlank()) {
            wv.settings.userAgentString = uaFor(t.url)
            if (saved == null || wv.restoreState(saved) == null) wv.loadUrl(t.url)
        }
        return wv
    }

    /** Puts [t] on screen: the old tab is paused (and its media stopped), page state follows the new one. */
    private fun showTab(t: Tab) {
        val old = if (::activeTab.isInitialized) activeTab else null
        if (old != null && old !== t) {
            exitFullscreen(notifyPage = true)
            exitFill() // unfills the old page while webView still points at it
            exitWatch(silent = true)
            old.active = false
            old.webView?.let { it.evaluateJavascript(PAUSE_MEDIA_JS, null); it.onPause() }
        }
        val wv = liveWebView(t)
        activeTab = t
        t.active = true
        t.lastUsed = ++tabUse
        webView = wv
        attach(wv)
        wv.onResume()
        resetPageState()
        pageHost = Uri.parse(t.currentUrl).host
        protectionOn = isProtected(pageHost)
        urlField.setText(wv.url ?: t.url)
        progress.visibility = View.INVISIBLE
        applyPrivateLook()
        updateNav(); updateStar(); updateShield()
        TabPolicy.toClose(tabs.map { it.info() }, t.id).mapNotNull(::tabById).forEach { destroyTab(it); tabs.remove(it) }
        TabPolicy.toDiscard(tabs.map { it.info() }, t.id).mapNotNull(::tabById).forEach(::discardTab)
        // attach() removed the focused WebView, so focus fell to the toolbar: give the page the remote back.
        // (Not at startup: the panels overlayOpen() reads aren't set up yet.)
        if (old != null && old !== t && !overlayOpen()) leaveToPage()
    }

    /** Frees the tab's WebView but keeps its address and back/forward list (in memory) for when it is shown again. */
    private fun discardTab(t: Tab) {
        val wv = t.webView ?: return
        if (t === activeTab) return
        t.url = t.currentUrl; t.title = t.currentTitle
        t.state = Bundle().also { b -> runCatching { wv.saveState(b) } }
        t.webView = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    private fun destroyTab(t: Tab) {
        t.webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.stopLoading(); wv.clearHistory(); wv.destroy()
        }
        t.webView = null; t.state = null; t.icon = null; t.active = false
    }

    /** Opens [url] in a new tab in front (pop-ups from a click). */
    private fun openInNewTab(url: String, isPrivate: Boolean) {
        showTab(newTab(isPrivate))
        closeShortcuts()
        load(url)
        note("Opened in a new tab · Tabs")
    }

    /** Closes [t]; if it was on screen, the most recently used tab of the same kind comes back. */
    private fun closeTab(t: Tab) {
        if (t.isPrivate && tabs.count { it.isPrivate } == 1) { exitPrivate(); return }
        if (t === activeTab) {
            val next = TabPolicy.nextAfterClose(tabs.map { it.info() }, t.id)?.let(::tabById)
            showTab(next ?: newTab(isPrivate = false))
            if (tabHasPage(activeTab)) closeShortcuts() else openShortcuts()
        }
        tabs.remove(t)
        destroyTab(t)
    }

    /** Low memory: keep only the tab on screen alive. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!TabPolicy.discardOnTrim(level) || !::activeTab.isInitialized) return
        TabPolicy.background(tabs.map { it.info() }, activeTab.id).mapNotNull(::tabById).forEach(::discardTab)
    }

    private fun resetPageState() {
        streams.clear(); updateStreamButton()
        blockedAds.set(0); blockedPopups.set(0); blockedRedirects.set(0); updateShield()
        cursor.showRing(null)
        if (webView.url.isNullOrBlank()) urlField.setText("")
        filling = false
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
            val action = snackOnAction
            hideSnackbar()
            action?.invoke()
        }
        btnStream.setOnClickListener { openStreamPicker() }
        urlField.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) submitAddress(urlField.text.toString())
            go
        }
        urlField.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            // Only what the user types: page loads set the text while the field doesn't have focus.
            override fun afterTextChanged(s: android.text.Editable?) {
                root.removeCallbacks(suggestRunnable)
                if (urlField.hasFocus()) root.postDelayed(suggestRunnable, 200)
            }
        })
        val voice = findViewById<View>(R.id.btnVoice)
        voice.visibility = if (voiceAvailable) View.VISIBLE else View.GONE
        voice.setOnClickListener { startVoice() }
        updateShield()
        updateNav()
    }

    /** Opens what was typed (or picked) in the address bar and hands the remote back to the page. */
    private fun submitAddress(input: String) {
        hideSuggestions()
        hideKeyboard()
        urlField.clearFocus()
        historyPanel.visibility = View.GONE
        downloadsPanel.visibility = View.GONE
        shortcutsPanel.visibility = View.GONE
        editingShortcuts = false
        editingPopular = false
        onOverlayChanged(null)
        load(normalizeInput(input))
    }

    // ---- Address-bar suggestions ----

    private val suggestPanel: LinearLayout by lazy { findViewById(R.id.suggestPanel) }
    private fun suggestOpen() = suggestPanel.visibility == View.VISIBLE
    private val suggestRunnable = Runnable { updateSuggestions() }
    private val suggestExecutor by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    /** Bumped on every keystroke; a response for an older one is dropped. */
    @Volatile private var suggestGen = 0
    private var lastRemote: Pair<String, List<String>>? = null
    /** Rebuilding the list moves focus for a moment; that must not count as leaving it. */
    private var renderingSuggestions = false
    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(this) }

    /** Local matches (Favourites, History) always; engine suggestions only if turned on, never in private mode. */
    private fun updateSuggestions() {
        val q = urlField.text.toString().trim()
        val editing = urlField.hasFocus() || isInside(suggestPanel, currentFocus)
        if (!editing || q.isEmpty() || q == webView.url) { hideSuggestions(); return }
        val engine = WebSearch.engine(prefs.searchEngine)
        val remoteOk = prefs.searchSuggestions && !inPrivate && !WebSearch.looksLikeUrl(q)
        // Keep the previous answer while the next one loads, if it is for the start of what's typed now.
        renderSuggestions(q, lastRemote?.takeIf { remoteOk && q.startsWith(it.first, ignoreCase = true) }?.second.orEmpty())
        if (!remoteOk) return
        val gen = ++suggestGen
        val url = WebSearch.suggestUrl(q, engine)
        val ua = defaultUa
        suggestExecutor.execute {
            if (gen != suggestGen) return@execute
            // No cookies: HttpURLConnection doesn't share the WebView's cookie jar, so this isn't tied to a signed-in account.
            val list = runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 3000; c.readTimeout = 3000
                c.setRequestProperty("User-Agent", ua)
                try { if (c.responseCode == 200) WebSearch.parseSuggestions(c.inputStream.bufferedReader().use { it.readText() }) else emptyList() }
                finally { c.disconnect() }
            }.getOrDefault(emptyList())
            runOnUiThread {
                if (gen != suggestGen || !urlField.hasFocus() && !isInside(suggestPanel, currentFocus)) return@runOnUiThread
                lastRemote = q to list
                renderSuggestions(q, list)
            }
        }
    }

    private fun renderSuggestions(q: String, remote: List<String>) {
        val focusedTag = (currentFocus?.takeIf { isInside(suggestPanel, it) })?.tag as? String
        renderingSuggestions = true
        suggestPanel.removeAllViews()
        val engine = WebSearch.engine(prefs.searchEngine)
        val favs = prefs.shortcuts
        val history = if (inPrivate) emptyList() else prefs.history.map { it.title to it.url }
        val local = WebSearch.localMatches(q, favs + history, max = 3)
        val isUrl = WebSearch.looksLikeUrl(q)
        if (isKeyboardVisible()) suggestPanel.addView(TextView(this).apply {
            text = "Back closes the keyboard, then ▼ to choose"
            setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
        })
        val rows = mutableListOf<View>()
        fun row(icon: Int, title: String, subtitle: String?, action: () -> Unit) {
            rows += LinearLayout(this).apply {
                id = View.generateViewId()
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(46)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setBackgroundResource(R.drawable.row_bg)
                isClickable = true; isFocusable = true
                tag = "sug:${rows.size}"
                addView(android.widget.ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(20), dp(20)))
                addView(textBlock(title, subtitle).apply {
                    setPadding(dp(14), 0, 0, 0)
                    (getChildAt(0) as TextView).apply { textSize = 16f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                    (getChildAt(1) as? TextView)?.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { action() }
            }.also { suggestPanel.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        if (isUrl) row(R.drawable.ic_forward, q, "Open address") { submitAddress(q) }
        else row(R.drawable.ic_search, q, "Search ${engine.name}") { submitAddress(q) }
        local.forEach { (title, url) ->
            val fav = favs.any { sameUrl(it.second, url) }
            row(if (fav) R.drawable.ic_star else R.drawable.ic_history, title, WebSearch.displayUrl(url)) { submitAddress(url) }
        }
        remote.filterNot { it.equals(q, ignoreCase = true) }.take(5).forEach { s -> row(R.drawable.ic_search, s, null) { submitAddress(s) } }
        if (!prefs.searchSuggestions && !inPrivate && !isUrl) {
            row(R.drawable.ic_settings, "Show ${engine.name} suggestions as you type", "Sends what you type to ${engine.name}. Never in private browsing.") {
                prefs.searchSuggestions = true
                note("Search suggestions on · turn off in Settings → Search")
                updateSuggestions()
            }
        }
        // D-pad: ▲ from the first row returns to the address field; ◀ ▶ and ▼ at the end stay in the list.
        rows.forEachIndexed { i, v ->
            v.nextFocusUpId = if (i == 0) R.id.urlField else rows[i - 1].id
            v.nextFocusDownId = if (i == rows.size - 1) v.id else rows[i + 1].id
            v.nextFocusLeftId = v.id; v.nextFocusRightId = v.id
        }
        val fieldLoc = IntArray(2).also { urlField.getLocationInWindow(it) }
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        (suggestPanel.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = fieldLoc[0] - rootLoc[0]
            topMargin = fieldLoc[1] - rootLoc[1] + urlField.height + dp(6)
            width = urlField.width
        }
        suggestPanel.requestLayout()
        suggestPanel.visibility = View.VISIBLE
        if (focusedTag != null) (suggestPanel.findViewWithTag<View>(focusedTag) ?: rows.firstOrNull())?.requestFocus()
        renderingSuggestions = false
    }

    private fun hideSuggestions() {
        root.removeCallbacks(suggestRunnable)
        suggestGen++
        if (!suggestOpen()) return
        val hadFocus = isInside(suggestPanel, currentFocus)
        suggestPanel.visibility = View.GONE
        suggestPanel.removeAllViews()
        if (hadFocus) urlField.requestFocus()
    }

    // ---- Voice search ----

    private val voiceAvailable by lazy {
        runCatching { packageManager.queryIntentActivities(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }.getOrDefault(false)
    }
    /** An app text field (History search, add favourite) that should get the spoken text instead of the address bar. */
    private var voiceTarget: EditText? = null

    private fun startVoice() {
        voiceTarget = (currentFocus as? EditText)?.takeIf { it !== urlField }
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, if (voiceTarget != null) "Speak to type" else "Say a search or a website")
        try {
            startActivityForResult(intent, REQ_VOICE)
        } catch (e: android.content.ActivityNotFoundException) {
            note("Voice search isn't available on this TV. Type with your phone instead: ⋮ → Phone remote")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VOICE) return
        val target = voiceTarget
        voiceTarget = null
        val text = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (resultCode != RESULT_OK || text.isNullOrEmpty()) return
        if (target != null && target.isShown) {
            target.setText(text); target.setSelection(text.length); target.requestFocus()
            return
        }
        urlField.setText(text)
        submitAddress(text)
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
        menuScroll.visibility = View.GONE
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

    private var snackOnAction: (() -> Unit)? = null
    /** Only download notices can be reached with ▼, so a stray press can't "Open anyway" a blocked ad. */
    private var snackReachable = false
    private val hideSnack = Runnable { hideSnackbar() }

    /** Shows what was blocked, with "Open anyway" when there is a URL to open ([newTab]: a pop-up, opened in a new tab). Any thread. */
    private fun showBlocked(message: String, url: String?, newTab: Boolean = false) = runOnUiThread {
        showSnack(R.drawable.ic_shield_on, message, "Open anyway", action = url?.let { u -> { bypassOnce = u; if (newTab) openInNewTab(u, inPrivate) else load(u) } })
    }

    /** Bottom notice with an optional action button; if [reachable], ▼ at the bottom of a page (jump mode) reaches it. */
    private fun showSnack(icon: Int, message: String, actionLabel: String, reachable: Boolean = false, action: (() -> Unit)?) {
        snackOnAction = action
        snackReachable = reachable
        findViewById<android.widget.ImageView>(R.id.snackIcon).setImageResource(icon)
        findViewById<TextView>(R.id.snackText).text = message
        findViewById<TextView>(R.id.snackAction).apply { text = actionLabel; visibility = if (action != null) View.VISIBLE else View.GONE }
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
        if (isInside(snackbar, currentFocus) && !overlayOpen()) onOverlayChanged(null) // hand the remote back to the page
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
                .putExtra(PlayerActivity.EXTRA_TITLE, webView.title)
                .putExtra(PlayerActivity.EXTRA_SPEED, speedFor(webView.url)),
        )
    }

    // endregion

    // region Shortcuts

    private lateinit var shortcutsPanel: View
    private var editingShortcuts = false
    private var editingPopular = false
    /** Top-level index of the Favourites folder being shown, or -1 for the top level. */
    private var openFolder = -1
    /** Favourite whose actions (rename, move, folder, remove) show under the grid in edit mode. */
    private var selectedFav: Bookmarks.Loc? = null

    private fun setupShortcuts() {
        shortcutsPanel = findViewById(R.id.shortcutsPanel)
        findViewById<View>(R.id.shortcutsClose).setOnClickListener { closeShortcuts() }
        findViewById<View>(R.id.homePrivate).setOnClickListener { if (inPrivate) exitPrivate() else enterPrivate() }
        findViewById<TextView>(R.id.shortcutsEdit).setOnClickListener {
            editingShortcuts = !editingShortcuts
            selectedFav = null
            renderShortcuts()
            findViewById<ViewGroup>(R.id.shortcutsGrid).getChildAt(0)?.requestFocus()
            if (!editingShortcuts) findViewById<View>(R.id.shortcutsEdit).requestFocus()
        }
        findViewById<View>(R.id.addThisPage).setOnClickListener {
            val url = webView.url.orEmpty()
            addShortcut(webView.title?.takeIf { it.isNotBlank() } ?: Uri.parse(url).host.orEmpty(), url)
        }
        findViewById<TextView>(R.id.popularEdit).setOnClickListener {
            editingPopular = !editingPopular
            renderShortcuts()
            (if (editingPopular) findViewById<ViewGroup>(R.id.popularGrid).getChildAt(0) ?: findViewById<ViewGroup>(R.id.popularSuggest).getChildAt(0) else null
                ?: findViewById(R.id.popularEdit))?.requestFocus()
        }
        val popField = findViewById<EditText>(R.id.popularAddField)
        popField.setOnEditorActionListener { _, _, _ ->
            val text = popField.text.toString().trim()
            if (text.isNotEmpty()) {
                val url = normalizeInput(text)
                addPopular(prettyHost(url), url)
                popField.setText("")
            }
            hideKeyboard(popField); findViewById<View>(R.id.popularEdit).requestFocus()
            true
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
        menuScroll.visibility = View.GONE
        shieldPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        historyPanel.visibility = View.GONE
        downloadsPanel.visibility = View.GONE
        editingShortcuts = false
        editingPopular = false
        openFolder = -1
        selectedFav = null
        renderShortcuts()
        shortcutsPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.shortcutsScroll).scrollTo(0, 0)
        onOverlayChanged(findViewById<ViewGroup>(R.id.popularGrid).getChildAt(0) ?: findViewById(R.id.homeRemote))
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
        val tree = prefs.bookmarks
        if (tree.getOrNull(openFolder) !is Bookmarks.Folder) openFolder = -1
        val folder = tree.getOrNull(openFolder) as? Bookmarks.Folder
        val favs = prefs.shortcuts
        // With no page behind it, this is the start screen: opaque and nothing to close back to.
        // Base colour (see-through a little over an open page) under the violet glow of home_bg.
        ((shortcutsPanel.background.mutate() as android.graphics.drawable.LayerDrawable).getDrawable(0) as android.graphics.drawable.GradientDrawable)
            .setColor(if (hasPage()) 0xF0101217.toInt() else 0xFF101217.toInt())
        findViewById<View>(R.id.shortcutsClose).visibility = if (hasPage()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.addThisPage).visibility = if (hasPage() && !isFavourite(webView.url)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.shortcutsEdit).apply {
            text = if (editingShortcuts) "Stop editing" else "Edit"
            visibility = if (tree.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.shortcutsEmpty).apply {
            visibility = if (tree.isEmpty() || folder?.items?.isEmpty() == true) View.VISIBLE else View.GONE
            text = if (folder != null) "This folder is empty. In Edit, choose a favourite and “Move into” this folder, or type an address above."
                else "No favourites yet. Open a site and press ☆ in the toolbar, or type an address above."
        }

        renderUpdateCard()
        findViewById<TextView>(R.id.homeTitle).text = if (inPrivate) "Private browsing" else "Home"
        findViewById<TextView>(R.id.homePrivate).text = if (inPrivate) "Close private browsing" else "Private browsing"
        findViewById<TextView>(R.id.homePrivate).setCompoundDrawablesRelativeWithIntrinsicBounds(if (inPrivate) R.drawable.ic_private_on else R.drawable.ic_private, 0, 0, 0)
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
        val popular = prefs.popular
        findViewById<TextView>(R.id.popularEdit).text = if (editingPopular) "Stop editing" else "Edit"
        findViewById<View>(R.id.popularEmpty).visibility = if (popular.isEmpty() && !editingPopular) View.VISIBLE else View.GONE
        fillGrid(R.id.popularGrid, popular, height = 104, iconSize = 44, kind = "pop")
        renderPopularAdd(popular)
        arrangeHome()
        renderFavourites(tree, folder)
        val recent = if (inPrivate || "rec" in prefs.hiddenSections) emptyList() else prefs.recent.filterNot { r -> favs.any { it.second == r.second } }.take(10)
        findViewById<View>(R.id.recentTitle).visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        fillGrid(R.id.recentGrid, recent, height = 130, iconSize = 44, kind = "rec")

        // Keep focus where it was after a re-render (e.g. deleting a favourite in Edit mode).
        if (focusedTag != null && focusedTag.startsWith("fav:")) {
            val grid = findViewById<ViewGroup>(R.id.shortcutsGrid)
            val idx = focusedTag.substringAfter("fav:").substringBefore('|').toIntOrNull() ?: 0
            (grid.getChildAt(idx.coerceAtMost(grid.childCount - 1)) ?: findViewById(R.id.shortcutsEdit)).requestFocus()
        }
        if (focusedTag != null && focusedTag.startsWith("pop:")) {
            val grid = findViewById<ViewGroup>(R.id.popularGrid)
            val idx = focusedTag.substringAfter("pop:").substringBefore('|').toIntOrNull() ?: 0
            (grid.getChildAt(idx.coerceAtMost(grid.childCount - 1)) ?: findViewById(R.id.popularEdit)).requestFocus()
        }
        if (focusedTag != null && focusedTag.startsWith("favact:"))
            (favActions.findViewWithTag<View>(focusedTag)?.takeIf { it.isFocusable } ?: findViewById<ViewGroup>(R.id.shortcutsGrid).getChildAt(0)
                ?: findViewById(R.id.shortcutsEdit)).requestFocus()
        if (focusedTag != null && focusedTag.startsWith("sug:"))
            (findViewById<ViewGroup>(R.id.popularSuggest).getChildAt(0) ?: findViewById<View>(R.id.popularAddField)).requestFocus()
        if (!inPrivate) fetchMissingIcons(popular + favs + recent)
    }

    /** Edit mode: one-press chips for well-known sites not already in Popular, plus a free address field. */
    private fun sectionView(id: String): View = findViewById(when (id) { "pop" -> R.id.popularSection; "fav" -> R.id.favSection; else -> R.id.recentSection })
    private fun sectionName(id: String) = when (id) { "pop" -> "Popular"; "fav" -> "Favourites"; else -> "Recently visited" }

    /** Puts the sections in the saved order and hides the ones the user turned off. */
    private fun arrangeHome() {
        val parent = findViewById<ViewGroup>(R.id.popularSection).parent as ViewGroup
        val views = prefs.homeOrder.map { sectionView(it) }
        val at = views.minOf { parent.indexOfChild(it) }
        views.forEach { parent.removeView(it) }
        views.forEachIndexed { i, v -> parent.addView(v, at + i) }
        val hidden = prefs.hiddenSections
        prefs.homeOrder.forEach { sectionView(it).visibility = if (it in hidden) View.GONE else View.VISIBLE }
        renderSectionTools("pop", R.id.popularTools, editingPopular)
        renderSectionTools("fav", R.id.favTools, editingShortcuts)
    }

    /** In edit mode: move the section up/down or hide it. Hidden sections come back from Settings → Start screen. */
    private fun renderSectionTools(id: String, toolsId: Int, editing: Boolean) {
        val row = findViewById<LinearLayout>(toolsId)
        row.removeAllViews()
        row.visibility = if (editing) View.VISIBLE else View.GONE
        if (!editing) return
        val order = prefs.homeOrder
        val pos = order.indexOf(id)
        fun tool(label: String, enabled: Boolean, action: () -> Unit) {
            if (!enabled) return
            row.addView(TextView(this).apply {
                text = label; textSize = 14f; setTextColor(android.graphics.Color.WHITE)
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.toolbar_button); setPadding(dp(16), 0, dp(16), 0)
                isClickable = true; isFocusable = true; tag = "tool:$id:$label"
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(10) })
        }
        fun move(by: Int) {
            val o = order.toMutableList(); o.removeAt(pos); o.add(pos + by, id); prefs.homeOrder = o
            renderShortcuts()
            sectionView(id).findViewWithTag<View>("tool:$id:" + if (by < 0) "↑  Move up" else "↓  Move down")?.requestFocus()
                ?: findViewById<View>(if (id == "pop") R.id.popularEdit else R.id.shortcutsEdit).requestFocus()
        }
        tool("↑  Move up", pos > 0) { move(-1) }
        tool("↓  Move down", pos < order.size - 1) { move(1) }
        tool("＋  New folder", id == "fav" && openFolder < 0) { newFavFolder() }
        tool("Hide ${sectionName(id)}", true) {
            prefs.hiddenSections = prefs.hiddenSections + id
            if (id == "pop") editingPopular = false else editingShortcuts = false
            renderShortcuts()
            note("${sectionName(id)} hidden. Show it again in Settings → Start screen")
            (findViewById<ViewGroup>(R.id.popularGrid).getChildAt(0)?.takeIf { it.isShown } ?: findViewById(R.id.homeRemote)).requestFocus()
        }
    }

    private fun renderPopularAdd(popular: List<Pair<String, String>>) {
        findViewById<View>(R.id.popularAdd).visibility = if (editingPopular) View.VISIBLE else View.GONE
        val grid = findViewById<android.widget.GridLayout>(R.id.popularSuggest)
        grid.removeAllViews()
        if (!editingPopular) return
        val have = popular.map { prettyHost(it.second) }.toSet()
        val offer = (Prefs.DEFAULT_POPULAR + Prefs.POPULAR_SUGGESTIONS).filter { prettyHost(it.second) !in have }
        val cols = grid.columnCount
        val gap = dp(10)
        val chipW = (resources.displayMetrics.widthPixels - dp(128) - gap * (cols - 1)) / cols
        offer.forEachIndexed { i, (title, url) ->
            grid.addView(TextView(this).apply {
                text = "＋  $title"
                setTextColor(android.graphics.Color.WHITE); textSize = 15f
                gravity = android.view.Gravity.CENTER
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setBackgroundResource(R.drawable.toolbar_button)
                isClickable = true; isFocusable = true
                tag = "sug:$i|$url"
                if (i % cols == cols - 1 || i == offer.size - 1) { id = View.generateViewId(); nextFocusRightId = id }
                setOnClickListener { addPopular(title, url) }
            }, android.widget.GridLayout.LayoutParams().apply {
                width = chipW; height = dp(44)
                setMargins(0, 0, if (i % cols == cols - 1) 0 else gap, gap)
            })
        }
    }

    private fun addPopular(title: String, url: String) {
        if (prefs.popular.any { sameUrl(it.second, url) || prettyHost(it.second) == prettyHost(url) }) { note("$title is already in Popular"); return }
        prefs.popular = prefs.popular + (title to url)
        renderShortcuts()
        note("Added $title to Popular")
    }

    private fun fillGrid(gridId: Int, items: List<Pair<String, String>>, height: Int, iconSize: Int, kind: String,
                         tileFor: ((Int, String, String) -> View)? = null) {
        val grid = findViewById<android.widget.GridLayout>(gridId)
        grid.removeAllViews()
        val cols = grid.columnCount
        val gridWidth = resources.displayMetrics.widthPixels - dp(128)
        val gap = dp(16)
        val tileW = (gridWidth - gap * (cols - 1)) / cols
        items.forEachIndexed { i, (title, url) ->
            val tile = tileFor?.invoke(i, title, url) ?: buildTile(title, url, iconSize, kind)
            tile.tag = "$kind:$i|$url"
            // ▶ on a row's last tile stays put instead of jumping up to the toolbar's ⋮
            if (i % cols == cols - 1 || i == items.size - 1) { tile.id = View.generateViewId(); tile.nextFocusRightId = tile.id }
            grid.addView(tile, android.widget.GridLayout.LayoutParams().apply {
                width = tileW
                this.height = dp(height)
                setMargins(0, 0, if (i % cols == cols - 1) 0 else gap, gap)
            })
        }
    }

    /** [onClick] replaces the default open/delete action; [badge] (with [badgeColor]) replaces the edit-mode ✕. */
    private fun buildTile(title: String, url: String, iconSize: Int, kind: String,
                          onClick: (() -> Unit)? = null, badge: String? = null, badgeColor: Int = 0xFFEF4444.toInt()): View {
        val deletable = onClick == null && ((kind == "fav" && editingShortcuts) || (kind == "pop" && editingPopular))
        val d = resources.displayMetrics.density
        val tile = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.tile_bg)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (onClick != null) onClick()
                else if (deletable) {
                    if (kind == "pop") prefs.popular = prefs.popular.filterNot { it.second == url }
                    else prefs.bookmarks = Bookmarks.removeUrl(prefs.bookmarks, url)
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
            val pad = dp(if (kind == "pop") 6 else 12); setPadding(pad, dp(12), pad, dp(12))
        }
        col.addView(siteAvatar(url, title), LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)))
        col.addView(TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(if (kind == "pop") 8 else 10), 0, 0)
            if (kind == "pop") textSize = 12f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (kind != "pop") col.addView(TextView(this).apply {
            text = prettyHost(url)
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tile.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (deletable || badge != null) {
            tile.addView(TextView(this).apply {
                text = badge ?: "✕"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(badgeColor)
                }
            }, FrameLayout.LayoutParams(dp(if (kind == "pop") 22 else 28), dp(if (kind == "pop") 22 else 28), android.view.Gravity.TOP or android.view.Gravity.END).apply {
                setMargins(0, dp(6), dp(6), 0)
            })
        }
        return tile
    }

    // ---- Favourites: folders and edit-mode actions (pure tree operations in Bookmarks.kt) ----

    /** "◀ Favourites › Folder" above the grid while a folder is open; OK (or Back) returns to the top level. */
    private val favFolderBar: TextView by lazy {
        TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE); textSize = 15f
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.toolbar_button); setPadding(dp(16), 0, dp(16), 0)
            isClickable = true; isFocusable = true; tag = "favbar"
            setOnClickListener { closeFavFolder() }
        }.also { bar ->
            val section = findViewById<LinearLayout>(R.id.favSection)
            section.addView(bar, section.indexOfChild(findViewById(R.id.addRow)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { bottomMargin = dp(14) })
        }
    }

    /** Rename / move / folder / remove for the selected favourite, right under the grid (edit mode only). */
    private val favActions: LinearLayout by lazy {
        LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }.also { box ->
            val section = findViewById<LinearLayout>(R.id.favSection)
            section.addView(box, section.indexOfChild(findViewById(R.id.shortcutsGrid)) + 1,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })
        }
    }

    private fun renderFavourites(tree: List<Bookmarks.Node>, folder: Bookmarks.Folder?) {
        favFolderBar.visibility = if (folder != null) View.VISIBLE else View.GONE
        if (folder != null) favFolderBar.text = "◀  Favourites  ›  ${folder.title}"
        val nodes: List<Bookmarks.Node> = folder?.items ?: tree
        val sel = selectedFav?.takeIf { editingShortcuts && it.folder == openFolder }
        val items = nodes.map { n -> n.title to ((n as? Bookmarks.Link)?.url ?: "folder:${n.title}") }
        fillGrid(R.id.shortcutsGrid, items, height = 150, iconSize = 56, kind = "fav") { i, title, url ->
            val n = nodes[i]
            val loc = Bookmarks.Loc(openFolder, i)
            val selected = sel == loc
            val badge = if (!editingShortcuts) null else if (selected) "✓" else "✎"
            val color = if (selected) 0xFF10B981.toInt() else 0xFF3B82F6.toInt()
            if (n is Bookmarks.Folder) folderTile(n, { onFavTile(n, loc) }, badge, color)
            else buildTile(title, url, 56, "fav", { onFavTile(n, loc) }, badge, color)
        }
        renderFavActions(tree)
    }

    /** OK on a favourite: open it, open the folder, or (edit mode) choose it for the actions below the grid. */
    private fun onFavTile(n: Bookmarks.Node, loc: Bookmarks.Loc) {
        when {
            editingShortcuts -> {
                selectedFav = if (selectedFav == loc) null else loc
                renderShortcuts()
                (selectedFav?.let { firstFocusable(favActions) } ?: favTile(loc.index))?.requestFocus()
            }
            n is Bookmarks.Folder -> openFavFolder(loc.index)
            n is Bookmarks.Link -> { closeShortcuts(); load(n.url) }
        }
    }

    private fun favTile(index: Int): View? {
        val grid = findViewById<ViewGroup>(R.id.shortcutsGrid)
        return grid.getChildAt(index.coerceIn(0, maxOf(0, grid.childCount - 1)))
    }

    private fun firstFocusable(v: View): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.isFocusable) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun openFavFolder(index: Int) {
        openFolder = index
        selectedFav = null
        renderShortcuts()
        (findViewById<ViewGroup>(R.id.shortcutsGrid).getChildAt(0) ?: favFolderBar).requestFocus()
    }

    private fun closeFavFolder() {
        val was = openFolder
        openFolder = -1
        selectedFav = null
        renderShortcuts()
        favTile(was)?.requestFocus()
    }

    private fun folderTile(f: Bookmarks.Folder, onClick: () -> Unit, badge: String?, badgeColor: Int): View {
        val tile = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.tile_bg)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_folder); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(56), dp(56)))
        col.addView(TextView(this).apply {
            text = f.title; setTextColor(android.graphics.Color.WHITE); textSize = 15f
            gravity = android.view.Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(TextView(this).apply {
            text = when (f.items.size) { 0 -> "Empty folder"; 1 -> "1 site"; else -> "${f.items.size} sites" }
            setTextColor(0xFF9CA3AF.toInt()); textSize = 12f; gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tile.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tile.addView(TextView(this).apply {
            text = f.items.size.toString(); setTextColor(android.graphics.Color.WHITE); textSize = 12f
            gravity = android.view.Gravity.CENTER; setBackgroundResource(R.drawable.count_chip); setPadding(dp(8), 0, dp(8), 0)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(22), android.view.Gravity.TOP or android.view.Gravity.START).apply { setMargins(dp(8), dp(8), 0, 0) })
        if (badge != null) tile.addView(TextView(this).apply {
            text = badge; setTextColor(android.graphics.Color.WHITE); textSize = 14f; gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(badgeColor) }
        }, FrameLayout.LayoutParams(dp(28), dp(28), android.view.Gravity.TOP or android.view.Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        return tile
    }

    private fun favButton(label: String, tag: String, enabled: Boolean, danger: Boolean = false, action: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = android.view.Gravity.CENTER
        setTextColor(if (danger) 0xFFFCA5A5.toInt() else android.graphics.Color.WHITE)
        setBackgroundResource(if (danger) R.drawable.btn_danger else R.drawable.toolbar_button)
        setPadding(dp(16), 0, dp(16), 0)
        isClickable = enabled; isFocusable = enabled; alpha = if (enabled) 1f else 0.4f
        this.tag = tag
        setOnClickListener { action() }
    }

    /** Changes save as they happen: there is no Done button. */
    private fun renderFavActions(tree: List<Bookmarks.Node>) {
        favActions.removeAllViews()
        val sel = selectedFav?.takeIf { editingShortcuts && it.folder == openFolder }
        val node = sel?.let { Bookmarks.get(tree, it) }
        if (sel == null || node == null) { selectedFav = null; favActions.visibility = View.GONE; return }
        favActions.visibility = View.VISIBLE
        val isFolder = node is Bookmarks.Folder
        val count = (tree.getOrNull(sel.folder) as? Bookmarks.Folder)?.items?.size ?: tree.size
        fun lp(end: Int = 10) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginEnd = dp(end) }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row1.addView(favButton("◀  Move", "favact:left", sel.index > 0) { moveFav(sel, -1) }, lp())
        row1.addView(favButton("Move  ▶", "favact:right", sel.index < count - 1) { moveFav(sel, 1) }, lp())
        val field = EditText(this).apply {
            setText(node.title)
            hint = if (isFolder) "Folder name" else "Name"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setBackgroundResource(R.drawable.add_field)
            setTextColor(android.graphics.Color.WHITE); setHintTextColor(0xFF7A7F8A.toInt()); textSize = 16f
            setPadding(dp(16), 0, dp(16), 0)
            tag = "favact:name"
        }
        var saved = node.title
        fun commit(): Boolean {
            val v = field.text.toString().trim()
            if (v.isEmpty() || v == saved) return false
            saved = v
            prefs.bookmarks = Bookmarks.rename(prefs.bookmarks, sel, v)
            return true
        }
        field.setOnEditorActionListener { _, _, _ ->
            hideKeyboard(field)
            if (commit()) renderShortcuts()
            favActions.findViewWithTag<View>("favact:name")?.requestFocus()
            true
        }
        // Leaving the field saves too; re-render afterwards so the tile shows the new name.
        field.setOnFocusChangeListener { _, has -> if (!has && commit()) root.post { if (shortcutsPanel.visibility == View.VISIBLE) renderShortcuts() } }
        row1.addView(field, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(10) })
        row1.addView(favButton(if (isFolder) "Remove folder" else "Remove", "favact:remove", true, danger = true) { removeFav(sel, node) }, lp(0))
        favActions.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        when {
            isFolder -> row2.addView(favButton("Open folder", "favact:open", true) { openFavFolder(sel.index) }, lp())
            sel.inFolder -> row2.addView(favButton("Move out of folder", "favact:out", true) { moveFavOut(sel) }, lp())
            else -> tree.forEachIndexed { j, f ->
                if (f is Bookmarks.Folder) row2.addView(favButton("Move into  ${f.title}", "favact:into:$j", true) { moveFavInto(sel, j, f.title) }, lp())
            }
        }
        if (row2.childCount > 0) favActions.addView(android.widget.HorizontalScrollView(this).apply {
            isFocusable = false; isHorizontalScrollBarEnabled = false; addView(row2)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        favActions.addView(TextView(this).apply {
            text = when {
                isFolder -> "Removing a folder keeps its sites: they go back to Favourites."
                !sel.inFolder && tree.none { it is Bookmarks.Folder } -> "Tip: “＋ New folder” above groups favourites."
                else -> "Changes are saved as you make them."
            }
            setTextColor(0xFF9CA3AF.toInt()); textSize = 13f; setPadding(dp(4), dp(8), 0, 0)
        })
    }

    private fun moveFav(sel: Bookmarks.Loc, by: Int) {
        val (list, at) = Bookmarks.move(prefs.bookmarks, sel, by)
        prefs.bookmarks = list
        selectedFav = at
        renderShortcuts()
        (favActions.findViewWithTag<View>(if (by < 0) "favact:left" else "favact:right")?.takeIf { it.isFocusable } ?: firstFocusable(favActions))?.requestFocus()
    }

    private fun removeFav(sel: Bookmarks.Loc, node: Bookmarks.Node) {
        prefs.bookmarks = Bookmarks.remove(prefs.bookmarks, sel)
        selectedFav = null
        if (prefs.bookmarks.isEmpty()) editingShortcuts = false
        note(if (node is Bookmarks.Folder) "Folder removed. Its sites are back in Favourites" else "Removed ${node.title}")
        renderShortcuts()
        (favTile(sel.index) ?: findViewById(R.id.addField)).requestFocus()
    }

    private fun moveFavInto(sel: Bookmarks.Loc, folder: Int, name: String) {
        prefs.bookmarks = Bookmarks.moveInto(prefs.bookmarks, sel, folder)
        selectedFav = null
        note("Moved into $name")
        renderShortcuts()
        favTile(sel.index)?.requestFocus()
    }

    private fun moveFavOut(sel: Bookmarks.Loc) {
        prefs.bookmarks = Bookmarks.moveOut(prefs.bookmarks, sel)
        selectedFav = null
        note("Moved out of the folder")
        renderShortcuts()
        (favTile(sel.index) ?: favFolderBar).requestFocus()
    }

    private fun newFavFolder() {
        prefs.bookmarks = Bookmarks.newFolder(prefs.bookmarks)
        openFolder = -1
        selectedFav = Bookmarks.Loc(-1, prefs.bookmarks.size - 1)
        renderShortcuts()
        favActions.findViewWithTag<View>("favact:name")?.requestFocus()
        note("Folder added: press OK on its name to rename it")
    }

    // ---- Favourites / recent ----

    private fun sameUrl(a: String?, b: String?) = a != null && b != null && a.trimEnd('/') == b.trimEnd('/')
    private fun isFavourite(url: String?) = prefs.shortcuts.any { sameUrl(it.second, url) }

    private fun updateStar() {
        if (menuOpen() && !inSubMenu) buildMenu()
    }

    private fun toggleFavourite() {
        if (!hasPage()) return
        val url = webView.url ?: return
        if (isFavourite(url)) {
            prefs.bookmarks = Bookmarks.removeUrl(prefs.bookmarks, url)
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
        prefs.history = History.add(prefs.history, History.Entry(entry.first, url, System.currentTimeMillis()))
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
        // While a folder is open on Home, "Add" puts the site in that folder.
        val into = if (shortcutsPanel.visibility == View.VISIBLE) openFolder else -1
        prefs.bookmarks = Bookmarks.add(prefs.bookmarks, Bookmarks.Link(title, url), into)
        note("Added to Favourites: $title")
        updateStar()
        if (shortcutsPanel.visibility == View.VISIBLE) renderShortcuts()
    }

    // ---- History page ----

    private val historyPanel: View by lazy { findViewById(R.id.historyPanel) }
    private val historySearch: EditText by lazy { findViewById(R.id.historySearch) }
    private var confirmClearHistory = false

    private fun setupHistory() {
        findViewById<View>(R.id.homeHistory).setOnClickListener { showHistory() }
        findViewById<View>(R.id.homeRemote).setOnClickListener { showPhoneRemote() }
        findViewById<TextView>(R.id.historyClear).setOnClickListener { btn ->
            btn as TextView
            if (!confirmClearHistory) { confirmClearHistory = true; btn.text = "Press again to clear"; return@setOnClickListener }
            prefs.history = emptyList()
            prefs.recent = emptyList()
            confirmClearHistory = false
            btn.text = "Clear history"
            renderHistory()
            note("History cleared")
        }
        historySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { if (historyPanel.visibility == View.VISIBLE) renderHistory() }
        })
        historySearch.setOnEditorActionListener { _, _, _ ->
            hideKeyboard(historySearch)
            // Straight to the first result, so OK on the keyboard's search key lands somewhere useful.
            (findViewById<ViewGroup>(R.id.historyContent).findViewWithTag<View>("hist:0") ?: historySearch).requestFocus()
            true
        }
    }

    private fun showHistory() {
        closeMenu()
        shieldPanel.visibility = View.GONE
        downloadsPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        confirmClearHistory = false
        findViewById<TextView>(R.id.historyClear).text = "Clear history"
        historySearch.setText("")
        renderHistory()
        findViewById<android.widget.ScrollView>(R.id.historyScroll).scrollTo(0, 0)
        historyPanel.visibility = View.VISIBLE
        val first = findViewById<ViewGroup>(R.id.historyContent).findViewWithTag<View>("hist:0")
        onOverlayChanged(first ?: historySearch)
    }

    private fun closeHistory() {
        if (historyPanel.visibility != View.VISIBLE) return
        hideKeyboard(historySearch); historySearch.clearFocus()
        historyPanel.visibility = View.GONE
        // Opened from the home screen: land back on its History button.
        onOverlayChanged(if (shortcutsPanel.visibility == View.VISIBLE) findViewById(R.id.homeHistory) else null)
    }

    private fun openFromHistory(url: String) {
        historyPanel.visibility = View.GONE
        closeShortcuts()
        onOverlayChanged(null)
        load(url)
    }

    /** Day headings, then one row per page. Rendering is capped so a long history stays quick on TV hardware. */
    private fun renderHistory() {
        val c = findViewById<LinearLayout>(R.id.historyContent)
        val focusTag = currentFocus?.tag as? String
        c.removeAllViews()
        val all = prefs.history
        val query = historySearch.text.toString()
        val matches = History.search(all, query)
        findViewById<View>(R.id.historyClear).visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
        if (matches.isEmpty()) {
            c.addView(TextView(this).apply {
                text = when {
                    all.isEmpty() && inPrivate -> "Private browsing doesn't save history. These are pages from normal browsing; there are none yet."
                    all.isEmpty() -> "No history yet. Pages you open appear here (not in private browsing)."
                    else -> "Nothing in your history matches \"${query.trim()}\"."
                }
                setTextColor(0xFF9CA3AF.toInt()); textSize = 16f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            return
        }
        val now = System.currentTimeMillis()
        val timeFormat = android.text.format.DateFormat.getTimeFormat(this)
        var lastDay: String? = null
        val shown = matches.take(HISTORY_RENDER_MAX)
        val rows = mutableListOf<ViewGroup>()
        shown.forEachIndexed { i, e ->
            val day = History.dayLabel(e.time, now)
            if (day != lastDay) {
                lastDay = day
                c.addView(TextView(this).apply {
                    text = day.uppercase()
                    setTextColor(0xFF818CF8.toInt()); textSize = 13f; letterSpacing = 0.12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(4), dp(20), 0, dp(8))
                })
            }
            c.addView(historyRow(e, i, timeFormat.format(java.util.Date(e.time))).also { rows += it })
        }
        // ▲▼ move row to row (or Remove to Remove), ◀▶ only between a row and its Remove button, so focus can't wander to the toolbar.
        rows.forEachIndexed { i, row ->
            val del = row.getChildAt(row.childCount - 1)
            row.nextFocusUpId = if (i == 0) R.id.historySearch else rows[i - 1].id
            row.nextFocusDownId = if (i == rows.size - 1) row.id else rows[i + 1].id
            row.nextFocusLeftId = row.id
            row.nextFocusRightId = del.id
            del.nextFocusLeftId = row.id
            del.nextFocusUpId = if (i == 0) R.id.historySearch else rows[i - 1].getChildAt(rows[i - 1].childCount - 1).id
            del.nextFocusDownId = if (i == rows.size - 1) del.id else rows[i + 1].getChildAt(rows[i + 1].childCount - 1).id
            del.nextFocusRightId = del.id
        }
        if (matches.size > shown.size) c.addView(subheading("Showing the newest ${shown.size} of ${matches.size}. Search to find older pages."))
        // Keep focus on the same row (or the one that took its place) after a re-render.
        if (focusTag != null && focusTag.startsWith("hist")) {
            val idx = focusTag.substringAfter(':').toIntOrNull() ?: 0
            val tag = if (focusTag.startsWith("histDel") && idx < shown.size) "histDel:$idx" else "hist:${idx.coerceAtMost(shown.size - 1)}"
            (c.findViewWithTag<View>(tag) ?: historySearch).requestFocus()
        }
    }

    private fun historyRow(e: History.Entry, index: Int, time: String): ViewGroup {
        val row = rowContainer().apply {
            id = View.generateViewId()
            isClickable = true; isFocusable = true
            tag = "hist:$index"
            setOnClickListener { openFromHistory(e.url) }
        }
        row.addView(siteAvatar(e.url, e.title), LinearLayout.LayoutParams(dp(36), dp(36)))
        row.addView(textBlock(e.title.ifBlank { prettyHost(e.url) }, "$time · ${WebSearch.displayUrl(e.url)}").apply {
            setPadding(dp(16), 0, dp(8), 0)
            (getChildAt(0) as TextView).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            (getChildAt(1) as TextView).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "Remove"
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFD1D5DB.toInt()); textSize = 14f
            setBackgroundResource(R.drawable.toolbar_button)
            setPadding(dp(14), 0, dp(14), 0)
            id = View.generateViewId()
            isClickable = true; isFocusable = true
            tag = "histDel:$index"
            contentDescription = "Remove from history"
            setOnClickListener {
                prefs.history = History.remove(prefs.history, e.url)
                renderHistory()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        return row
    }

    // ---- Downloads page (logic and rows in DownloadCenter) ----

    private val downloadsPanel: View by lazy { findViewById(R.id.downloadsPanel) }
    private val downloads: DownloadCenter by lazy {
        DownloadCenter(this, prefs, downloadsPanel, object : DownloadCenter.Host {
            override fun note(msg: String) = this@BrowserActivity.note(msg)
            override fun panelOpen() = downloadsPanel.visibility == View.VISIBLE
            override fun downloaded(e: Downloads.Entry) {
                if (downloadsPanel.visibility != View.VISIBLE) showSnack(R.drawable.ic_download, "Downloaded ${e.name}", "Open", reachable = true) { downloads.open(e) }
            }
        })
    }

    private fun showDownloads() {
        closeMenu()
        shieldPanel.visibility = View.GONE
        historyPanel.visibility = View.GONE
        if (::settingsPanel.isInitialized) settingsPanel.visibility = View.GONE
        findViewById<android.widget.ScrollView>(R.id.downloadsScroll).scrollTo(0, 0)
        downloadsPanel.visibility = View.VISIBLE
        downloads.onPanelShown()
        onOverlayChanged(downloads.firstFocus())
    }

    private fun closeDownloads() {
        if (downloadsPanel.visibility != View.VISIBLE) return
        downloadsPanel.visibility = View.GONE
        onOverlayChanged(if (shortcutsPanel.visibility == View.VISIBLE) findViewById<ViewGroup>(R.id.popularGrid).getChildAt(0) ?: findViewById(R.id.homeRemote) else null)
    }

    // ---- Updates: GitHub "latest release" check, download, system installer ----

    /** Debug builds can pretend to be older (`am start ... --es debug_version 1.0.0`) to test the update card. */
    private val currentVersion: String by lazy {
        intent?.getStringExtra("debug_version")?.takeIf { BuildConfig.DEBUG } ?: BuildConfig.VERSION_NAME
    }
    private var checkingUpdate = false
    private var updateDownloading = false

    private fun availableUpdate(): Updates.Release? =
        prefs.latestRelease?.takeIf { Updates.isNewer(it.version, currentVersion) }

    /** At most once a day unless [force]d. Offline, rate-limited or no usable release all just mean "no card". */
    private fun checkForUpdate(force: Boolean = false, done: (() -> Unit)? = null) {
        if (checkingUpdate) return
        if (!force && (!prefs.updateCheck || !Updates.due(prefs.lastUpdateCheck, System.currentTimeMillis()))) return
        checkingUpdate = true
        kotlin.concurrent.thread(name = "update-check") {
            val answer = runCatching {
                val c = java.net.URL(Updates.RELEASES_API).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "mkTV/${BuildConfig.VERSION_NAME}") // GitHub's API requires one
                try { c.responseCode to (if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else "") }
                finally { c.disconnect() }
            }.getOrNull()
            runOnUiThread {
                checkingUpdate = false
                if (answer != null) {
                    // GitHub answered (even with an error): don't ask again until tomorrow.
                    prefs.lastUpdateCheck = System.currentTimeMillis()
                    if (answer.first == 200) prefs.latestRelease = Updates.parseRelease(answer.second)
                    else if (force) note("GitHub didn't answer the update check (${answer.first}). Try again later.")
                } else if (force) note("Couldn't reach GitHub. Check the connection and try again.")
                if (shortcutsPanel.visibility == View.VISIBLE) renderUpdateCard()
                done?.invoke()
            }
        }
    }

    /** Non-blocking card at the top of the home screen; "Later" hides it until the next version. */
    private fun renderUpdateCard() {
        val card = findViewById<View>(R.id.updateCard)
        val r = availableUpdate()?.takeIf { it.version != prefs.dismissedUpdate || updateDownloading }
        card.visibility = if (r == null) View.GONE else View.VISIBLE
        if (r == null) return
        findViewById<TextView>(R.id.updateTitle).text = "mkTV ${r.version} is available"
        findViewById<TextView>(R.id.updateText).text = buildString {
            append("You have $currentVersion. Downloads from GitHub")
            if (r.size > 0) append(" (%.1f MB)".format(r.size / 1_048_576.0))
            append(", then Android asks you to install it.")
        }
        val go = findViewById<TextView>(R.id.updateGo)
        if (!updateDownloading) go.text = "Update"
        go.setOnClickListener { startUpdate(r, go) }
        findViewById<View>(R.id.updateLater).setOnClickListener {
            prefs.dismissedUpdate = r.version
            card.visibility = View.GONE
            (findViewById<ViewGroup>(R.id.popularGrid).getChildAt(0) ?: findViewById(R.id.homeRemote)).requestFocus()
        }
    }

    private fun updateDir() = java.io.File(cacheDir, "updates").apply { mkdirs() }

    /** Downloads mkTV.apk (once per version), checks its size, then hands it to the system installer. */
    private fun startUpdate(r: Updates.Release, btn: TextView) {
        if (updateDownloading) return
        val file = java.io.File(updateDir(), "mkTV-${r.version}.apk")
        if (file.exists() && (r.size <= 0 || file.length() == r.size)) { installUpdate(file); return }
        updateDownloading = true
        btn.text = "Downloading…"
        kotlin.concurrent.thread(name = "update-download") {
            val ok = runCatching {
                updateDir().listFiles()?.forEach { it.delete() } // older downloads
                val part = java.io.File(updateDir(), "download.part")
                // GitHub redirects to its download CDN (https → https), which HttpURLConnection follows.
                val c = java.net.URL(r.apkUrl).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "mkTV/${BuildConfig.VERSION_NAME}")
                try {
                    check(c.responseCode == 200)
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                    var got = 0L; var shown = -1
                    c.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); got += n
                                val pct = if (total > 0) (got * 100 / total).toInt() else -1
                                if (pct != shown) { shown = pct; runOnUiThread { btn.text = if (pct >= 0) "Downloading $pct%" else "Downloading…" } }
                            }
                        }
                    }
                } finally { c.disconnect() }
                check(r.size <= 0 || part.length() == r.size) { "size mismatch" }
                check(part.renameTo(file))
            }.isSuccess
            runOnUiThread {
                updateDownloading = false
                btn.text = "Update"
                if (ok) installUpdate(file) else note("Couldn't download the update. Check the connection and try again.")
            }
        }
    }

    private fun installUpdate(file: java.io.File) {
        if (!signedLikeThisApp(file)) {
            note("This update is signed differently from the mkTV installed here (for example a self-built copy), so Android won't install it over it.")
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            note("Allow mkTV to install apps, then press Update again")
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: android.content.ActivityNotFoundException) {
                note("Allow mkTV in Settings → Apps → Security & restrictions → Unknown sources, then press Update again")
            }
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            note("This TV has no app installer")
        }
    }

    /** Android refuses an update signed with another key; catch that here with a clear message instead of a vague installer error. */
    @Suppress("DEPRECATION")
    private fun signedLikeThisApp(file: java.io.File): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < 28) return@runCatching true
        val flags = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val apk = packageManager.getPackageArchiveInfo(file.path, flags) ?: return@runCatching false
        if (apk.packageName != packageName) return@runCatching false
        val theirs = apk.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return@runCatching true
        val ours = packageManager.getPackageInfo(packageName, flags).signingInfo?.let { info ->
            listOfNotNull(info.apkContentsSigners, info.signingCertificateHistory).flatMap { it.toList() }.map { it.toCharsString() }.toSet()
        } ?: return@runCatching true
        theirs.any { it in ours }
    }.getOrDefault(true)

    // endregion

    // region Settings

    private lateinit var settingsPanel: View
    private var settingsDirty = false
    /** The section shown on the right; the sidebar on the left picks it. */
    private var settingsTab = SETTINGS_TABS[0].first
    private var confirmClear = false

    private fun setupSettings() {
        settingsPanel = findViewById(R.id.settingsPanel)
        // Branding: the app icon before the Home title.
        findViewById<TextView>(R.id.homeTitle).apply {
            setCompoundDrawablesRelative(appIcon(36), null, null, null)
            compoundDrawablePadding = dp(16)
        }
    }

    private fun appIcon(size: Int) = androidx.core.content.ContextCompat.getDrawable(this, R.mipmap.ic_launcher)!!.mutate().apply { setBounds(0, 0, dp(size), dp(size)) }

    private fun showSettings() {
        menuScroll.visibility = View.GONE
        shieldPanel.visibility = View.GONE
        historyPanel.visibility = View.GONE
        downloadsPanel.visibility = View.GONE
        closeShortcuts()
        settingsDirty = false
        confirmClear = false
        renderSettingsNav()
        renderSettings()
        findViewById<android.widget.ScrollView>(R.id.settingsScroll).scrollTo(0, 0)
        settingsPanel.visibility = View.VISIBLE
        onOverlayChanged(findViewById<ViewGroup>(R.id.settingsNav).findViewWithTag("settab:$settingsTab"))
    }

    /** Sidebar: one row per section. Moving onto a row shows that section; OK or ▶ goes into it. */
    private fun renderSettingsNav() {
        val nav = findViewById<LinearLayout>(R.id.settingsNav)
        nav.removeAllViews()
        SETTINGS_TABS.forEachIndexed { i, (title, icon) ->
            nav.addView(TextView(this).apply {
                text = title
                tag = "settab:$title"
                id = View.generateViewId()
                textSize = 17f
                gravity = android.view.Gravity.CENTER_VERTICAL
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                compoundDrawablePadding = dp(16)
                setPadding(dp(16), 0, dp(16), 0)
                setBackgroundResource(R.drawable.nav_item)
                isFocusable = true; isClickable = true
                setOnFocusChangeListener { _, has -> if (has && settingsTab != title) selectSettingsTab(title) }
                setOnClickListener { firstSettingsControl()?.requestFocus() }
                setOnKeyListener { _, code, ev ->
                    if (code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                    if (ev.action == KeyEvent.ACTION_DOWN) firstSettingsControl()?.requestFocus()
                    true
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { if (i > 0) topMargin = dp(4) })
        }
        (0 until nav.childCount).map { nav.getChildAt(it) }.let { rows ->
            rows.forEach { it.nextFocusLeftId = it.id }
            rows.first().nextFocusUpId = rows.first().id
            rows.last().nextFocusDownId = rows.last().id
        }
        markSettingsTab()
    }

    private fun selectSettingsTab(title: String) {
        settingsTab = title
        confirmClear = false
        markSettingsTab()
        renderSettings()
        findViewById<android.widget.ScrollView>(R.id.settingsScroll).scrollTo(0, 0)
    }

    private fun markSettingsTab() {
        val nav = findViewById<LinearLayout>(R.id.settingsNav)
        for (i in 0 until nav.childCount) (nav.getChildAt(i) as TextView).apply {
            val on = tag == "settab:$settingsTab"
            isSelected = on
            setTextColor(if (on) android.graphics.Color.WHITE else 0xFFD1D5DB.toInt())
            setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(if (on) 0xFF818CF8.toInt() else 0xFF9CA3AF.toInt())
        }
    }

    /** Settings arrows: ◀ off the left of a section returns to its sidebar row; ▲▼ never leave the panel for the toolbar. */
    private fun settingsKey(f: View?, code: Int): Boolean {
        val content = findViewById<ViewGroup>(R.id.settingsContent)
        if (f == null || !isInside(content, f)) return false
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        val next = f.focusSearch(dir)
        if (next != null && isInside(content, next)) return false
        if (dir == View.FOCUS_LEFT) findViewById<ViewGroup>(R.id.settingsNav).findViewWithTag<View>("settab:$settingsTab")?.requestFocus()
        return true
    }

    private fun firstSettingsControl(): View? =
        findViewById<ViewGroup>(R.id.settingsContent).getFocusables(View.FOCUS_DOWN).firstOrNull { it.isShown && it.isEnabled }

    private fun closeSettings() {
        currentFocus?.let { if (it is EditText) { hideKeyboard(it); it.clearFocus() } }
        settingsPanel.visibility = View.GONE
        onOverlayChanged(null)
        if (settingsDirty) {
            protectionOn = isProtected(pageHost)
            updateShield()
            if (hasPage()) webView.reload()
        }
        // Opening Settings hid Home; with no page behind, go back to Home rather than a blank screen.
        if (!hasPage() && prefs.startWithShortcuts) openShortcuts()
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
            card.addView(textBlock("Pointer speed", "How fast the pointer moves in pointer mode and with the phone touchpad").apply {
                setPadding(dp(16), dp(12), dp(16), 0)
            })
            card.addView(segmented(PointerMotion.LEVELS, prefs.pointerSpeed,
                icons = listOf(R.drawable.ic_speed_slow, R.drawable.ic_speed, R.drawable.ic_speed_fast)) { i ->
                prefs.pointerSpeed = i; applyPointerTuning(); renderSettings()
            })
            card.addView(toggleRow("Pointer acceleration", "Starts slow for precision and speeds up while you hold a direction. Off: one steady speed.",
                prefs.pointerAccel) { prefs.pointerAccel = it; applyPointerTuning() })
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
            prefs.homeOrder.forEach { id ->
                card.addView(toggleRow("Show ${sectionName(id)}", when (id) {
                    "pop" -> "Well-known video sites; Edit on the home screen to change them"
                    "fav" -> "Sites you saved"
                    else -> "The last sites you opened"
                }, id !in prefs.hiddenSections) { on ->
                    prefs.hiddenSections = if (on) prefs.hiddenSections - id else prefs.hiddenSections + id
                })
            }
            card.addView(actionRow("Home screen layout", prefs.homeOrder.joinToString(" → ") { sectionName(it) } + ". Change the order with Edit on the home screen. Reset also restores the Popular sites.", "Reset") {
                prefs.homeOrder = Prefs.HOME_SECTIONS; prefs.hiddenSections = emptySet(); prefs.popular = Prefs.DEFAULT_POPULAR; renderSettings()
            })
        }

        section(c, "Search") { card ->
            val engine = WebSearch.engine(prefs.searchEngine)
            card.addView(segmented(WebSearch.ENGINES.map { it.name }, WebSearch.ENGINES.indexOf(engine)) { i ->
                prefs.searchEngine = WebSearch.ENGINES[i].id; renderSettings()
            })
            card.addView(TextView(this).apply {
                text = "Used for searches typed or spoken in the address bar."
                setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
                setPadding(dp(16), dp(10), dp(16), dp(4))
            })
            card.addView(toggleRow("Search suggestions", "Suggests searches as you type. Sends what you type in the address bar to ${engine.name}. Never in private browsing.",
                prefs.searchSuggestions) { prefs.searchSuggestions = it })
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
                tabs.filter { t -> !t.isPrivate }.mapNotNull { t -> t.webView }.forEach { w -> CookieManager.getInstance().setAcceptThirdPartyCookies(w, !it) }
                settingsDirty = true
            })
        }

        section(c, "Blocklists") { card -> renderFilterLists(card) }

        section(c, "Phone remote") { card ->
            card.addView(toggleRow("Phone remote", "Control mkTV from a phone browser on the same Wi-Fi. Turns on when you open ⋮ → Phone remote; only runs while mkTV is on screen.", prefs.remoteEnabled) {
                setRemoteEnabled(it)
            })
            card.addView(actionRow("Unpair all phones", "Makes a new pairing code. Phones that had the remote must scan the new QR code.", "New code") { btn ->
                unpairPhones(); btn.text = "Done ✓"
            })
            card.addView(subheading("Anyone with the code can control mkTV on this network. Use it on your home Wi-Fi, not guest or public networks. mkTV shows a notice whenever a new phone connects."))
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
            card.addView(zoomSettingRow())
            card.addView(toggleRow("Desktop sites", "Ask sites for their desktop version (bigger layouts, fewer app banners)", prefs.desktopMode) {
                prefs.desktopMode = it; applyUserAgent(); settingsDirty = true
            })
            card.addView(actionRow("Clear browsing data", "Cookies, cache, site storage and history. You'll be signed out of sites.",
                "Clear", danger = true) { btn ->
                if (!confirmClear) { confirmClear = true; btn.text = "Press again to confirm"; return@actionRow }
                CookieManager.getInstance().removeAllCookies(null)
                android.webkit.WebStorage.getInstance().deleteAllData()
                webView.clearCache(true)
                webView.clearHistory()
                prefs.recent = emptyList()
                prefs.history = emptyList()
                confirmClear = false
                btn.text = "Cleared ✓"
                settingsDirty = true
            })
        }

        section(c, "Downloads") { card ->
            card.addView(actionRow("Downloads", if (android.os.Build.VERSION.SDK_INT >= 29) "Files are saved to the TV's Downloads folder"
                else "Files are saved in mkTV's own folder on the TV (deleted if you uninstall mkTV)", "Open") { closeSettings(); showDownloads() })
        }

        section(c, "Updates") { card ->
            val update = availableUpdate()
            val status = when {
                checkingUpdate -> "Checking…"
                update != null -> "mkTV ${update.version} is available"
                prefs.lastUpdateCheck == 0L -> "Not checked yet"
                else -> "Up to date · checked " + android.text.format.DateUtils.getRelativeTimeSpanString(prefs.lastUpdateCheck)
            }
            card.addView(actionRow("mkTV $currentVersion", status, if (update != null) "Update" else "Check now") { btn ->
                if (update != null) startUpdate(update, btn)
                else { btn.text = "Checking…"; checkForUpdate(force = true) { if (settingsPanel.visibility == View.VISIBLE) renderSettings() } }
            })
            card.addView(toggleRow("Check for updates", "Once a day, asks GitHub for the latest mkTV release. Updates install only when you choose.", prefs.updateCheck) {
                prefs.updateCheck = it
            })
        }

        if (focusTag != null) c.findViewWithTag<View>(focusTag)?.requestFocus()

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        c.addView(TextView(this).apply {
            text = "mkTV $version"
            setTextColor(0xFF9CA3AF.toInt()); textSize = 14f
            gravity = android.view.Gravity.CENTER
            setCompoundDrawablesRelative(appIcon(28), null, null, null)
            compoundDrawablePadding = dp(10)
            setPadding(0, dp(24), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Builds a Settings section, but only the one open in the sidebar. */
    private fun section(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        if (title != settingsTab) return
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE); textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(10), 0, dp(14))
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

    /** Label + text field; saves on Done / when focus leaves ([commitOnBlur] false: on Done only, for "add" fields). */
    private fun fieldRow(title: String, subtitle: String, value: String, hint: String, commitOnBlur: Boolean = true, onSave: (String) -> Unit): View {
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
        if (commitOnBlur) field.setOnFocusChangeListener { _, has -> if (!has) commit() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(textBlock(title, subtitle))
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(10) })
        }
    }

    private fun segmented(options: List<String>, selected: Int, first: Boolean = false, icons: List<Int>? = null, onSelect: (Int) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(12), dp(16), dp(4))
        options.forEachIndexed { i, label ->
            addView(TextView(context).apply {
                text = icons?.getOrNull(i)?.let { iconText(it, label, 20) } ?: label
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

    /** "[icon] label" as one centred run of text, for buttons that are wider than their content. */
    private fun iconText(icon: Int, label: String, sizeDp: Int): CharSequence {
        val d = androidx.core.content.ContextCompat.getDrawable(this, icon)!!.mutate().apply { setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }
        val align = if (android.os.Build.VERSION.SDK_INT >= 29) android.text.style.DynamicDrawableSpan.ALIGN_CENTER else android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM
        return android.text.SpannableString("\u00A0  $label").apply { setSpan(android.text.style.ImageSpan(d, align), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun subheading(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
        setPadding(dp(16), dp(14), dp(16), dp(4))
    }

    // ---- Blocklists: filter lists and allowlist (logic in AdBlocker / FilterLists) ----

    private var confirmRemoveList: String? = null
    private val ADD_LIST = "Add a list by address"
    private val ADD_ALLOW = "Allow a domain"

    /** Not while typing: re-rendering would empty the field under the keyboard. */
    private fun rerenderWhenListsDone(): (Int) -> Unit = { runOnUiThread { if (settingsPanel.visibility == View.VISIBLE && currentFocus !is EditText) renderSettings() } }

    /** Re-render after a click without pulling focus away: to [tag] if it still exists, else to the given field. */
    private fun rerenderListsFocus(tag: String?, fallback: String) = root.post {
        renderSettings()
        val c = findViewById<ViewGroup>(R.id.settingsContent)
        (tag?.let { c.findViewWithTag<View>(it) } ?: c.findViewWithTag<View>("field:$fallback"))?.requestFocus()
    }

    private fun renderFilterLists(card: LinearLayout) {
        confirmRemoveList = null
        val on = prefs.filterListsOn
        val custom = prefs.customFilterLists
        val total = blocker.hosts.size
        val updating = blocker.status.values.any { it.updating }
        val summary = when {
            blocker.capped -> "Too much for a TV: only the first %,d are used. Turn some lists off.".format(FilterLists.MAX_DOMAINS)
            total >= FilterLists.WARN_DOMAINS -> "That's a lot for a TV and may slow pages down. Turn off lists you don't need."
            else -> "The built-in list plus the lists that are on. Each is updated once a day."
        }
        card.addView(actionRow(if (total == 0) "Loading blocklists…" else "%,d blocked domains".format(total), summary,
            if (updating) "Updating…" else "Update all") {
            blocker.load(force = true, onDone = rerenderWhenListsDone())
            rerenderListsFocus("btn:lists:all", ADD_LIST)
        }.also { row -> (row as ViewGroup).getChildAt(1).tag = "btn:lists:all" }) // stable tag: the title holds the count

        card.addView(subheading("Built-in lists (downloaded from their authors when on)"))
        FilterLists.PRESETS.forEach { p -> card.addView(listRow(p.id, p.name, "${p.about} · ${p.licence}", p.id in on, custom = false)) }
        if (custom.isNotEmpty()) {
            card.addView(subheading("Added by you"))
            custom.forEach { (name, url) -> card.addView(listRow(FilterLists.customId(url), name, url, FilterLists.customId(url) in on, custom = true)) }
        }
        card.addView(fieldRow(ADD_LIST, "A hosts file (0.0.0.0 host), one domain per line, or ||domain^ rules. Press Done to add.",
            "", "https://…/hosts.txt", commitOnBlur = false) { input ->
            val url = FilterLists.normalizeListUrl(input)
            val preset = FilterLists.PRESETS.firstOrNull { it.url == url }
            when {
                url == null -> note("That doesn't look like a web address")
                preset != null -> { prefs.filterListsOn = prefs.filterListsOn + preset.id; note("${preset.name} is built in and now on") }
                custom.any { it.second == url } -> note("That list is already added")
                else -> {
                    prefs.customFilterLists = custom + (FilterLists.nameFor(url) to url)
                    prefs.filterListsOn = prefs.filterListsOn + FilterLists.customId(url)
                }
            }
            if (url != null) { settingsDirty = true; blocker.load(onDone = rerenderWhenListsDone()) }
            rerenderListsFocus(null, ADD_LIST)
        })

        val allow = prefs.allowlist
        card.addView(subheading("Never blocked"))
        allow.sorted().forEach { d ->
            card.addView(actionRow(d, "And its subdomains", "Remove") {
                blocker.setAllowlist(prefs.allowlist - d); settingsDirty = true
                rerenderListsFocus(null, ADD_ALLOW)
            })
        }
        val site = HostRules.siteOf(pageHost)
        // Not offered in private mode: it would write the private page's site to Prefs.
        if (!inPrivate && hasPage() && site != null && FilterLists.normalizeDomain(site) != null && site !in allow) {
            card.addView(actionRow("Allow $site", "Never block requests to $site, on any page. To pause all blocking on a site, use the shield.", "Allow") {
                blocker.setAllowlist(prefs.allowlist + site); settingsDirty = true
                rerenderListsFocus("btn:$site", ADD_ALLOW)
            })
        }
        card.addView(fieldRow(ADD_ALLOW, "It and its subdomains are never blocked, on any site. Press Done to add.",
            "", "example.com", commitOnBlur = false) { input ->
            val d = FilterLists.normalizeDomain(input)
            if (d == null) note("That doesn't look like a domain") else { blocker.setAllowlist(prefs.allowlist + d); settingsDirty = true }
            rerenderListsFocus(null, ADD_ALLOW)
        })
    }

    /** One list: the left part toggles it (whole area is one D-pad stop), then Update and, for added lists, Remove. */
    private fun listRow(id: String, name: String, offText: String, enabled: Boolean, custom: Boolean): View {
        val st = blocker.status[id]
        val updated = blocker.lastUpdated(id)
        val ago = if (updated == 0L) null else android.text.format.DateUtils.getRelativeTimeSpanString(updated).toString().replaceFirstChar { it.lowercase() }
        val subtitle = when {
            !enabled -> offText
            st?.updating == true -> "Updating…"
            st?.error != null -> "Couldn't update (${st.error})" + (ago?.let { " · using the copy from $it" } ?: "")
            ago == null -> "Not downloaded yet"
            else -> "%,d domains · updated %s".format(st?.count ?: 0, ago)
        }
        val pill = TextView(this).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE); textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            minWidth = dp(64)
            setPadding(dp(14), 0, dp(14), 0)
            text = if (enabled) "ON" else "OFF"
            setBackgroundResource(if (enabled) R.drawable.pill_on else R.drawable.pill_off)
        }
        val toggle = rowContainer().apply {
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(textBlock(name, subtitle), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginStart = dp(16) })
            isClickable = true; isFocusable = true
            tag = "list:$id"
            setOnClickListener {
                prefs.filterListsOn = if (enabled) prefs.filterListsOn - id else prefs.filterListsOn + id
                settingsDirty = true
                blocker.load(onDone = rerenderWhenListsDone())
                rerenderListsFocus("list:$id", ADD_LIST)
            }
        }
        fun button(label: String, tag: String, danger: Boolean = false, onClick: (TextView) -> Unit) = TextView(this).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            setTextColor(if (danger) 0xFFFCA5A5.toInt() else android.graphics.Color.WHITE); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(if (danger) R.drawable.btn_danger else R.drawable.toolbar_button)
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true; isFocusable = true
            this.tag = tag
            setOnClickListener { onClick(this) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(16), dp(4))
            addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (enabled) addView(button(if (st?.updating == true) "Updating…" else "Update", "list:$id:update") {
                blocker.load(force = true, only = id, onDone = rerenderWhenListsDone())
                rerenderListsFocus("list:$id:update", ADD_LIST)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(12) })
            if (custom) addView(button("Remove", "list:$id:remove", danger = true) { btn ->
                if (confirmRemoveList != id) { confirmRemoveList = id; btn.text = "Press again"; return@button }
                prefs.customFilterLists = prefs.customFilterLists.filter { FilterLists.customId(it.second) != id }
                prefs.filterListsOn = prefs.filterListsOn - id
                blocker.forget(id)
                settingsDirty = true
                blocker.load(onDone = rerenderWhenListsDone())
                rerenderListsFocus(null, ADD_LIST)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(12) })
        }
    }

    // endregion

    // region Zoom and full-screen fill
    // Page zoom (⋮ → Zoom, Settings → Browsing → Default zoom) and the full-screen fallback for players that
    // can't use real full screen in WebView. Why CSS zoom: see PageZoom. JS side: __sp.zoom / fill / unfill.

    /** Private browsing keeps its zoom changes here only, so no visited host is written to Prefs. */
    private val privateZoom = HashMap<String, Int>()

    /** TV interfaces (YouTube TV) already size themselves to the screen, so they ignore the default zoom. */
    private fun defaultZoomFor(url: String?) = if (isTvSite(url)) PageZoom.DEFAULT else prefs.defaultZoom

    private fun zoomFor(url: String?): Int {
        val host = url?.let { Uri.parse(it).host }
        if (inPrivate) PageZoom.hostKey(host)?.let { privateZoom[it] }?.let { return it }
        return PageZoom.levelFor(host, prefs.siteZoom, defaultZoomFor(url))
    }

    private fun applyZoom(view: WebView = webView, url: String? = view.url) =
        view.evaluateJavascript(PageZoom.script(zoomFor(url)), null)

    /** Applies live and saves at once (per host); no Save button. */
    private fun setZoom(pct: Int) {
        val url = webView.url ?: return
        val host = Uri.parse(url).host
        val key = PageZoom.hostKey(host) ?: return
        if (inPrivate) privateZoom[key] = PageZoom.snap(pct)
        else prefs.siteZoom = PageZoom.withLevel(prefs.siteZoom, host, pct, defaultZoomFor(url))
        applyZoom()
    }

    private fun zoomButton(label: String, tag: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = android.view.Gravity.CENTER; textSize = 18f
        setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
        setBackgroundResource(R.drawable.toolbar_button); setPadding(dp(16), 0, dp(16), 0)
        minWidth = dp(52)
        isClickable = true; isFocusable = true
        this.tag = tag
        setOnClickListener { onClick() }
    }

    private fun zoomValue() = TextView(this).apply {
        gravity = android.view.Gravity.CENTER; textSize = 17f
        setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
        minWidth = dp(84)
    }

    /** ⋮ → Zoom: − / value / + / Reset inside the menu, like Clear browsing data. */
    private fun showZoomMenu() {
        inSubMenu = true
        menuPanel.removeAllViews()
        val url = webView.url
        menuPanel.addView(TextView(this).apply {
            text = "Zoom"
            setTextColor(android.graphics.Color.WHITE); textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(2))
        })
        val note = TextView(this).apply {
            setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
            setPadding(dp(14), 0, dp(14), dp(8))
        }
        menuPanel.addView(note)
        val value = zoomValue()
        lateinit var minus: TextView
        lateinit var plus: TextView
        fun paint() {
            val pct = zoomFor(url)
            value.text = PageZoom.label(pct)
            minus.alpha = if (PageZoom.canStep(pct, -1)) 1f else 0.4f
            plus.alpha = if (PageZoom.canStep(pct, 1)) 1f else 0.4f
            val site = url?.let { prettyHost(it) } ?: "this site"
            note.text = (if (inPrivate) "$site · this private session only" else "Remembered for $site") +
                "\nDefault ${PageZoom.label(defaultZoomFor(url))} in Settings"
        }
        minus = zoomButton("−", "zoom:-") { setZoom(PageZoom.step(zoomFor(url), -1)); paint() }
        plus = zoomButton("+", "zoom:+") { setZoom(PageZoom.step(zoomFor(url), 1)); paint() }
        val reset = zoomButton("Reset", "zoom:reset") { setZoom(defaultZoomFor(url)); paint() }.apply { textSize = 15f }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(10))
            addView(minus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            addView(plus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(14) })
        }
        menuPanel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        paint()
        trapMenuFocus()
        // One row of buttons: ▲/▼ stay on it instead of escaping to the toolbar.
        listOf(minus, plus, reset).forEach { it.nextFocusUpId = it.id; it.nextFocusDownId = it.id }
        plus.requestFocus()
    }

    /** Settings → Browsing: default zoom with − / +, and a reset for sites that have their own level. */
    private fun zoomSettingRow(): View {
        val value = zoomValue()
        val sub = TextView(this).apply { setTextColor(0xFF9CA3AF.toInt()); textSize = 13f; setPadding(0, dp(2), 0, 0) }
        lateinit var minus: TextView
        lateinit var plus: TextView
        lateinit var clear: TextView
        fun paint() {
            val pct = prefs.defaultZoom
            value.text = PageZoom.label(pct)
            minus.alpha = if (PageZoom.canStep(pct, -1)) 1f else 0.4f
            plus.alpha = if (PageZoom.canStep(pct, 1)) 1f else 0.4f
            val n = prefs.siteZoom.size
            sub.text = "For every site without its own zoom (⋮ → Zoom)." + if (n > 0) " $n site${if (n == 1) " has" else "s have"} their own." else ""
            clear.visibility = if (n > 0) View.VISIBLE else View.GONE
        }
        fun change(pct: Int) { prefs.defaultZoom = pct; applyZoom(); paint() }
        minus = zoomButton("−", "zoomdef:-") { change(PageZoom.step(prefs.defaultZoom, -1)) }
        plus = zoomButton("+", "zoomdef:+") { change(PageZoom.step(prefs.defaultZoom, 1)) }
        clear = zoomButton("Reset sites", "zoomdef:reset") {
            prefs.siteZoom = emptyMap(); applyZoom(); paint()
            plus.requestFocus()
        }.apply { textSize = 14f }
        val row = rowContainer().apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Default zoom"; setTextColor(android.graphics.Color.WHITE); textSize = 17f })
                addView(sub)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(minus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(16) })
            addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            addView(plus, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(12) })
        }
        paint()
        return row
    }

    // ---- Full-screen fill: the video covers the window with CSS when real full screen doesn't happen ----

    private var filling = false
    private val FILL_FALLBACK_MS = 900L
    /** Bumped on every request so a late fallback check can't fire on a newer page or request. */
    private var fullscreenGen = 0

    private fun afterFullscreenRequest(res: String?) {
        val fs = PageZoom.parseFs(res)
        val gen = ++fullscreenGen
        val url = webView.url
        when (fs) {
            PageZoom.Fs.FAILED -> enterFill()
            PageZoom.Fs.REQUESTED -> {
                flash("⛶")
                // The page's full-screen view normally appears within a few frames; if not, fill instead.
                root.postDelayed({
                    if (gen == fullscreenGen && url == webView.url && PageZoom.needsFill(fs, customView != null, filling)) enterFill()
                }, FILL_FALLBACK_MS)
            }
            PageZoom.Fs.NO_VIDEO -> Unit
        }
    }

    private fun enterFill() {
        webView.evaluateJavascript("window.__sp && __sp.fill ? __sp.fill() : null") { res ->
            if (res?.trim('"') != "fill" || customView != null) return@evaluateJavascript
            filling = true
            // Watch mode owns the keys: OK / Play-Pause toggle, ◀ ▶ seek, ▲ or Back exit.
            if (!watching) enterWatch(false) else showDock()
            cursor.showRing(null)
            hideBar()
            flash("⛶")
        }
    }

    private fun exitFill() {
        fullscreenGen++
        if (!filling) return
        filling = false
        webView.evaluateJavascript("window.__sp && __sp.unfill && __sp.unfill()", null)
        afterFill()
    }

    /** Fill hid the top bar even with auto-hide off; bring it back for those users. */
    private fun afterFill() {
        if (!prefs.autoHideToolbar) showBar(autoHide = false)
        if (watching) showDock()
    }

    /** The page is the source of truth: it gives up fill when the video goes away, or keeps it across a WebView switch. */
    private fun syncFill(pageFilling: Boolean) {
        if (pageFilling == filling) return
        filling = pageFilling
        if (filling) { if (!watching) enterWatch(false); hideBar() } else afterFill()
    }

    // endregion

    // region Remote control → cursor

    private val jumpMode get() = prefs.navMode == "jump"

    private fun overlayOpen() =
        shortcutsPanel.visibility == View.VISIBLE || settingsPanel.visibility == View.VISIBLE ||
            shieldPanel.visibility == View.VISIBLE || menuOpen() || remotePanel.visibility == View.VISIBLE ||
            historyPanel.visibility == View.VISIBLE || wifiPanel.visibility == View.VISIBLE || suggestOpen() ||
            downloadsPanel.visibility == View.VISIBLE || controlsOpen()

    // ---- Phone remote (LAN web page, paired by QR code) ----

    private val remotePanel: View by lazy { findViewById(R.id.remotePanel) }
    private val remoteServer by lazy { RemoteServer(this, this, { prefs.remoteToken }, { prefs.remoteToken = it }) }

    private fun showPhoneRemote() {
        closeMenu()
        prefs.remoteEnabled = true
        startRemote()
        val url = remoteServer.url()
        val qr = findViewById<android.widget.ImageView>(R.id.remoteQr)
        remoteHelpShown = false
        findViewById<TextView>(R.id.remoteHelpBtn).text = "Help"
        findViewById<TextView>(R.id.remoteTitle).text = "Phone remote"
        findViewById<View>(R.id.remoteUrl).visibility = View.VISIBLE
        if (url == null || !remoteServer.running) {
            remoteRows(listOf("Couldn't start the phone remote" to "Make sure the TV is connected to Wi-Fi or Ethernet, then open Phone remote again."), numbered = false)
            qr.setImageDrawable(null)
            findViewById<TextView>(R.id.remoteUrl).text = ""
        } else {
            renderRemoteSteps()
            // Naming the network needs location permission: ask once, the first time the remote opens.
            if (!hasLocation() && !prefs.askedWifiName) { prefs.askedWifiName = true; requestWifiName() }
            findViewById<TextView>(R.id.remoteUrl).text = url
            qr.setImageBitmap(qrBitmap(url, dp(240)))
        }
        findViewById<View>(R.id.remoteWifi).setOnClickListener { showShareWifi(fromRemote = true) }
        findViewById<View>(R.id.remoteHelpBtn).setOnClickListener { toggleRemoteHelp() }
        val newCode = findViewById<TextView>(R.id.remoteNewCode)
        newCode.text = "New code"
        newCode.setOnClickListener {
            // Second press confirms: it disconnects every paired phone.
            if (newCode.text == "New code") newCode.text = "Press again to unpair all" else unpairPhones()
        }
        remoteTab = "connect"
        listOf(R.id.remoteTabConnect to "connect", R.id.remoteTabDevices to "devices").forEach { (id, tab) ->
            findViewById<View>(id).apply {
                setOnClickListener { showRemoteTab(tab) }
                setOnFocusChangeListener { _, has -> if (has && remoteTab != tab) showRemoteTab(tab) }
            }
        }
        styleRemoteTabs()
        wireRemoteFocus()
        remotePanel.visibility = View.VISIBLE
        onOverlayChanged(findViewById(R.id.remoteHelpBtn))
    }

    /** Step 1 names the TV's Wi-Fi, so homes with several networks pick the right one. */
    private fun renderRemoteSteps() {
        val ssid = currentSsid()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val wired = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val wifiLine = when {
            ssid != null -> "“$ssid”, the network this TV is on"
            wired -> "The Wi-Fi of the router this TV is plugged into"
            hasLocation() && !locationOn() -> "The same Wi-Fi as this TV (turn on Location in TV settings to show its name)"
            else -> "The same Wi-Fi as this TV"
        }
        // Most VPNs (NordVPN, Proton, ...) leave the home network alone and the remote just works. Warn only when
        // the VPN's routes swallow this TV's own subnet, so replies to the phone would vanish into the tunnel.
        val lanIp = remoteServer.address()?.let { runCatching { java.net.InetAddress.getByName(it) }.getOrNull() }
        val vpn = lanIp != null && cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true &&
                cm.getLinkProperties(n)?.routes?.any { it.matches(lanIp) } == true
        }
        if (remoteHelpShown) renderRemoteHelp() else remoteRows(listOf(
            "Join the Wi-Fi" to wifiLine,
            "Scan this code" to "With your phone's camera",
            "Use your phone as the remote" to "In its browser. Bookmark the page: it stays paired.",
        ), numbered = true)
        findViewById<TextView>(R.id.remoteWarn).apply {
            text = "⚠ This TV's VPN routes your home network through its tunnel, so phones can't reach it. Allow LAN access in the VPN app, or exclude mkTV."
            visibility = if (vpn) View.VISIBLE else View.GONE
        }
    }

    private var remoteHelpShown = false

    /** Help swaps the steps for the fixes people actually need; Help again (or Back) shows the steps. */
    private fun toggleRemoteHelp() {
        if (remoteTab != "connect") { remoteTab = "connect"; remoteHelpShown = false; styleRemoteTabs() }
        remoteHelpShown = !remoteHelpShown
        findViewById<TextView>(R.id.remoteHelpBtn).text = if (remoteHelpShown) "Steps" else "Help"
        findViewById<TextView>(R.id.remoteTitle).text = if (remoteHelpShown) "Phone can't open it?" else "Phone remote"
        findViewById<View>(R.id.remoteUrl).visibility = if (remoteHelpShown) View.GONE else View.VISIBLE // help lists it
        if (remoteHelpShown) renderRemoteHelp() else renderRemoteSteps()
        wireRemoteFocus()
    }

    /** "connect" (QR steps / help) or "devices" (who is using the remote). */
    private var remoteTab = "connect"

    private fun showRemoteTab(tab: String) {
        remoteTab = tab
        remoteHelpShown = false
        findViewById<TextView>(R.id.remoteHelpBtn).text = "Help"
        findViewById<TextView>(R.id.remoteTitle).text = "Phone remote"
        findViewById<View>(R.id.remoteUrl).visibility = if (tab == "connect") View.VISIBLE else View.GONE
        if (tab == "connect") renderRemoteSteps() else renderRemoteDevices()
        styleRemoteTabs()
        wireRemoteFocus()
    }

    private fun styleRemoteTabs() {
        val n = remoteServer.devices().size
        listOf(R.id.remoteTabConnect to "connect", R.id.remoteTabDevices to "devices").forEach { (id, tab) ->
            findViewById<TextView>(id).apply {
                val on = remoteTab == tab
                if (tab == "devices") text = if (n > 0) "Devices ($n)" else "Devices"
                setBackgroundResource(if (on) R.drawable.seg_on else R.drawable.seg_off)
                setTextColor(if (on) android.graphics.Color.WHITE else 0xFFD1D5DB.toInt())
                setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
    }

    /** Devices tab: each phone (or computer) using the remote with the current code, newest activity first. */
    private fun renderRemoteDevices() {
        val box = findViewById<LinearLayout>(R.id.remoteSteps)
        box.removeAllViews()
        findViewById<TextView>(R.id.remoteWarn).visibility = View.GONE
        val list = remoteServer.devices()
        if (list.isEmpty()) {
            box.addView(textBlock("No phones connected", "Scan the code on the Connect tab with a phone on the same Wi-Fi."))
            return
        }
        val now = System.currentTimeMillis()
        list.forEachIndexed { i, d ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, if (i == 0) 0 else dp(12), 0, 0)
                addView(android.widget.ImageView(context).apply {
                    setImageResource(R.drawable.ic_phone)
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFF818CF8.toInt())
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                val ago = now - d.lastSeen
                val seen = when {
                    ago < 60_000 -> "active now"
                    ago < 3_600_000 -> "${ago / 60_000} min ago"
                    else -> android.text.format.DateUtils.getRelativeTimeSpanString(d.lastSeen).toString()
                }
                addView(textBlock(d.label, "${d.address} · $seen").apply { setPadding(dp(14), 0, dp(12), 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = "Disconnect"
                    tag = "remotedev"
                    id = View.generateViewId()
                    gravity = android.view.Gravity.CENTER
                    setTextColor(0xFFFCA5A5.toInt()); textSize = 14f
                    setBackgroundResource(R.drawable.btn_danger)
                    setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_close, 0, 0, 0)
                    compoundDrawablePadding = dp(8)
                    compoundDrawableTintList = textColors
                    setPadding(dp(14), 0, dp(14), 0)
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        remoteServer.disconnect(d.address)
                        note("Disconnected ${d.label}. It can connect again after a New code.")
                        renderRemoteDevices(); styleRemoteTabs(); wireRemoteFocus()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
            })
        }
    }

    /** Keep ▲▼◀▶ inside the panel: tabs on top, Disconnect buttons in the middle, the button row at the bottom. */
    private fun wireRemoteFocus() {
        val tabs = listOf(findViewById<View>(R.id.remoteTabConnect), findViewById<View>(R.id.remoteTabDevices))
        val bottom = listOf(findViewById<View>(R.id.remoteHelpBtn), findViewById<View>(R.id.remoteNewCode), findViewById<View>(R.id.remoteWifi))
        val middle = ArrayList<View>().also { findViewById<View>(R.id.remoteSteps).findViewsWithText(it, "Disconnect", View.FIND_VIEWS_WITH_TEXT) }
        val tab = if (remoteTab == "connect") tabs[0] else tabs[1]
        fun row(views: List<View>) = views.forEachIndexed { i, v ->
            v.nextFocusLeftId = views[maxOf(i - 1, 0)].id; v.nextFocusRightId = views[minOf(i + 1, views.size - 1)].id
        }
        row(tabs); row(bottom)
        tabs.forEach { it.nextFocusUpId = it.id; it.nextFocusDownId = (middle.firstOrNull() ?: bottom[0]).id }
        middle.forEachIndexed { i, v ->
            v.nextFocusLeftId = v.id; v.nextFocusRightId = v.id
            v.nextFocusUpId = if (i == 0) tab.id else middle[i - 1].id
            v.nextFocusDownId = if (i == middle.size - 1) bottom[0].id else middle[i + 1].id
        }
        bottom.forEach { it.nextFocusDownId = it.id; it.nextFocusUpId = (middle.lastOrNull() ?: tab).id }
    }

    private fun renderRemoteHelp() {
        val url = remoteServer.url().orEmpty()
        remoteRows(listOf(
            "Browser switched to https://" to "Tap “Continue to site”. In Chrome: Settings → Privacy and security → Always use secure connections → off. A private tab works too.",
            "Same Wi-Fi, not a guest network" to "Phone and TV must be on the same network. Guest Wi-Fi blocks devices from each other.",
            "VPN or ad blocker on the phone" to "Pause it, or allow LAN / local network access in it.",
            "Use it at home" to "Anyone with this code can control mkTV, so use it on your own Wi-Fi, not public networks. New code unpairs every phone.",
            "Type it instead" to url.ifEmpty { "Open Phone remote again to see the address" },
        ), numbered = false)
    }

    /** One row per step: a numbered (or •) badge, a bold line and a quieter detail line. */
    private fun remoteRows(rows: List<Pair<String, String>>, numbered: Boolean) {
        val box = findViewById<LinearLayout>(R.id.remoteSteps)
        box.removeAllViews()
        rows.forEachIndexed { i, (title, detail) ->
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, if (i == 0) 0 else dp(if (numbered) 14 else 10), 0, 0)
                addView(TextView(context).apply {
                    text = if (numbered) "${i + 1}" else "•"
                    gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE); textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    if (numbered) background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7C3AED.toInt(), 0xFFDB2777.toInt()),
                    ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(textBlock(title, detail).apply { setPadding(dp(14), dp(2), 0, 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun closePhoneRemote() {
        if (remotePanel.visibility != View.VISIBLE) return
        remotePanel.visibility = View.GONE
        // opened from Home's button: land back on it
        onOverlayChanged(if (shortcutsPanel.visibility == View.VISIBLE) findViewById(R.id.homeRemote) else null)
    }

    // ---- Share Wi-Fi: "join this network" QR for guests ----

    private val wifiPanel: View by lazy { findViewById(R.id.wifiPanel) }
    private var wifiFromRemote = false
    private var wifiWired = false

    private fun showShareWifi(fromRemote: Boolean = false) {
        closeMenu()
        wifiFromRemote = fromRemote
        remotePanel.visibility = View.GONE
        val name = findViewById<EditText>(R.id.wifiName)
        val pass = findViewById<EditText>(R.id.wifiPass)
        if (!wifiWired) {
            wifiWired = true
            // Every keystroke saves and redraws: no Save button.
            val watcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    prefs.wifiSsid = name.text.toString(); prefs.wifiPassword = pass.text.toString(); renderWifiQr()
                }
            }
            name.addTextChangedListener(watcher); pass.addTextChangedListener(watcher)
            findViewById<View>(R.id.wifiDetect).setOnClickListener { detectWifi(ask = true) }
        }
        name.setText(prefs.wifiSsid); pass.setText(prefs.wifiPassword)
        if (prefs.wifiSsid.isEmpty()) detectWifi(ask = false)
        renderWifiQr()
        wifiPanel.visibility = View.VISIBLE
        wifiPanel.isFocusable = true
        onOverlayChanged(if (prefs.wifiSsid.isEmpty()) findViewById(R.id.wifiDetect) else wifiPanel)
    }

    private fun closeShareWifi() {
        if (wifiPanel.visibility != View.VISIBLE) return
        (currentFocus as? EditText)?.let { hideKeyboard(it) }
        wifiPanel.visibility = View.GONE
        if (wifiFromRemote) showPhoneRemote()
        else onOverlayChanged(if (shortcutsPanel.visibility == View.VISIBLE) findViewById(R.id.homeRemote) else null)
    }

    private fun renderWifiQr() {
        val ssid = prefs.wifiSsid.trim()
        val pw = prefs.wifiPassword
        val problem = WifiQr.passwordProblem(pw)
        val qr = findViewById<android.widget.ImageView>(R.id.wifiQr)
        val ok = ssid.isNotEmpty() && problem == null
        if (ok) qr.setImageBitmap(qrBitmap(WifiQr.text(ssid, pw), dp(240))) else qr.setImageDrawable(null)
        findViewById<View>(R.id.wifiQrEmpty).visibility = if (ok) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.wifiHint).text = problem
            ?: if (ssid.isEmpty() && hasLocation() && !locationOn()) "Android shows the TV's network name only while Location is on (Settings → Privacy → Location). Or type it."
            else "Saved on this TV only, never sent anywhere · Back to close"
    }

    private val locationRequest = 41

    private fun hasLocation() =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestWifiName() = requestPermissions(
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), locationRequest)

    private fun locationOn(): Boolean = android.os.Build.VERSION.SDK_INT < 28 ||
        (getSystemService(LOCATION_SERVICE) as android.location.LocationManager).isLocationEnabled

    /** The connected Wi-Fi's name, or null (no permission, Location off, Ethernet). */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = if (!hasLocation()) null else
        WifiQr.cleanSsid((applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager)?.connectionInfo?.ssid)

    /** Android shows the Wi-Fi name only to apps with location permission (and Location on). */
    private fun detectWifi(ask: Boolean) {
        if (!hasLocation()) { if (ask) requestWifiName(); return }
        val ssid = currentSsid()
        if (ssid != null) {
            findViewById<EditText>(R.id.wifiName).setText(ssid)
            if (ask) findViewById<EditText>(R.id.wifiPass).requestFocus()
        } else if (ask && !locationOn()) {
            note("Android shows the Wi-Fi name only while Location is on. Turn it on here, or type the name.")
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        } else if (ask) note("Couldn't read the network name (is the TV on Ethernet?). Type it instead.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != locationRequest) return
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (remotePanel.visibility == View.VISIBLE) renderRemoteSteps()
        if (wifiPanel.visibility != View.VISIBLE) return
        if (granted) detectWifi(ask = true) else note("No problem: type the network name instead")
    }

    /**
     * QR code with the mkTV logo in the middle. The highest error correction (H, ~30% recoverable)
     * lets phones read it even though the logo covers about 5% of the code.
     */
    private fun qrBitmap(text: String, size: Int): Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1,
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true) // mutable, for the logo
        val c = android.graphics.Canvas(bmp)
        val logo = size * 18 / 100
        val pad = logo / 8
        val l = (size - logo) / 2f
        c.drawRoundRect(l - pad, l - pad, l + logo + pad, l + logo + pad, pad * 1.5f, pad * 1.5f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        androidx.core.content.ContextCompat.getDrawable(this, R.mipmap.ic_launcher)!!.mutate().apply {
            setBounds(l.toInt(), l.toInt(), l.toInt() + logo, l.toInt() + logo)
            draw(c)
        }
        return bmp
    }

    private val heldKeys = HashMap<Int, Runnable>()

    /**
     * Phone keys are dispatched straight into the activity, which skips the step of Android's input
     * pipeline that moves focus between views on arrow keys (only scrolling containers handled ▲▼ by
     * themselves, so ◀▶ between Home's buttons did nothing). Do that focus move here when nothing
     * else consumed the arrow, exactly as a hardware D-pad would.
     */
    private fun sendRemoteKey(e: KeyEvent): Boolean {
        e.source = android.view.InputDevice.SOURCE_DPAD
        if (dispatchKeyEvent(e) || e.action != KeyEvent.ACTION_DOWN) return true
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        return currentFocus?.focusSearch(dir)?.requestFocus(dir) == true
    }

    override fun onKey(key: String, action: String) = runOnUiThread {
        if (key == "home") { if (action != "up") { closeMenu(); closePhoneRemote(); if (prefs.startWithShortcuts) openShortcuts() else load(normalizeInput(prefs.homeUrl)) }; return@runOnUiThread }
        // Apps can't inject volume keys; adjust the media stream directly (FLAG_SHOW_UI shows the TV's own volume bar).
        if (key == "volup" || key == "voldown" || key == "mute") {
            if (action != "up") (getSystemService(AUDIO_SERVICE) as android.media.AudioManager).adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                when (key) { "volup" -> android.media.AudioManager.ADJUST_RAISE; "voldown" -> android.media.AudioManager.ADJUST_LOWER; else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE },
                android.media.AudioManager.FLAG_SHOW_UI)
            return@runOnUiThread
        }
        val code = when (key) {
            "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK; "menu" -> KeyEvent.KEYCODE_MENU
            "playpause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; "ff" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "rew" -> KeyEvent.KEYCODE_MEDIA_REWIND; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "pageup" -> KeyEvent.KEYCODE_PAGE_UP; "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
            else -> return@runOnUiThread
        }
        val now = SystemClock.uptimeMillis()
        when (action) {
            "down" -> {
                heldKeys.remove(code)?.let { root.removeCallbacks(it) }
                val downTime = now
                sendRemoteKey(KeyEvent(downTime, now, KeyEvent.ACTION_DOWN, code, 0))
                // Holding a phone button behaves like holding the remote: key repeats after 450 ms.
                val repeater = object : Runnable {
                    var count = 1
                    override fun run() {
                        val t = SystemClock.uptimeMillis()
                        sendRemoteKey(KeyEvent(downTime, t, KeyEvent.ACTION_DOWN, code, count++, 0, 0, 0, KeyEvent.FLAG_LONG_PRESS.takeIf { count == 2 } ?: 0))
                        root.postDelayed(this, 90)
                    }
                }
                heldKeys[code] = repeater
                root.postDelayed(repeater, 450)
            }
            "up" -> {
                heldKeys.remove(code)?.let { root.removeCallbacks(it) }
                sendRemoteKey(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
            else -> {
                sendRemoteKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
                sendRemoteKey(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            }
        }
    }

    /**
     * Types into whatever has focus: an app text field, or the web page (inputs, YouTube TV search, ...).
     * Live typing sends only the change: Backspace [deletes] times, then the new characters.
     */
    override fun onText(text: String, deletes: Int, clear: Boolean, enter: Boolean) = runOnUiThread {
        val target: View = currentFocus ?: webView
        fun press(code: Int, meta: Int = 0) {
            val t = SystemClock.uptimeMillis()
            target.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
            target.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
        }
        if (clear && target is EditText) target.setText("")
        else {
            // Select-all + delete empties a page's input; the Backspaces cover fields that ignore Ctrl+A (TV-style search boxes).
            if (clear) { press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON); press(KeyEvent.KEYCODE_DEL) }
            repeat(deletes) { press(KeyEvent.KEYCODE_DEL) }
        }
        if (text.isNotEmpty()) {
            val events = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            if (events != null) events.forEach { target.dispatchKeyEvent(it) }
            else if (target is EditText) target.append(text)
            // Accents, emoji and other scripts have no key events: insert them into the page's focused field.
            else webView.evaluateJavascript("document.execCommand('insertText', false, ${org.json.JSONObject.quote(text)})", null)
        }
        if (enter) press(KeyEvent.KEYCODE_ENTER)
    }

    override fun onOpen(url: String) = runOnUiThread {
        if (url.isBlank()) return@runOnUiThread
        val target = normalizeInput(url)
        if (!RemoteInput.openable(target)) { note("The phone can only open web pages"); return@runOnUiThread }
        closeMenu(); closePhoneRemote(); closeShortcuts()
        load(target)
    }

    /** Where arrows would move the pointer, a touchpad drag moves it too; elsewhere the phone sends arrow keys. */
    private fun pointerActive() = !jumpMode && pageActive() && !siteRemote && !watching && customView == null

    override fun onMove(dx: Double, dy: Double) = runOnUiThread {
        if (!pointerActive()) return@runOnUiThread
        // Phone px are tiny next to a TV: about 1.8 dp of pointer per px of finger (Normal) crosses the screen in two swipes.
        val gain = PointerMotion.touchGain(prefs.pointerSpeed) * resources.displayMetrics.density
        cursor.moveBy(dx.toFloat() * gain, dy.toFloat() * gain)
    }

    override fun onMedia(action: String, value: Double) = runOnUiThread {
        when (action) {
            "seek" -> webView.evaluateJavascript("window.__sp && __sp.seekTo($value)", null)
            "fullscreen" -> toggleTheater()
            "captions" -> webView.evaluateJavascript("window.__sp ? __sp.captions() : null") { r ->
                when (r?.trim('"')) { "on" -> flash("CC"); "off" -> flash("CC off") }
            }
            // Values already checked against the allowlists by RemoteInput.mediaValue.
            "speed" -> if (hasPage()) setSpeed(value)
            "sleep" -> { setSleep(value.toInt()); if (!controlsOpen()) showTip("Sleep timer · ${PlaybackSpeed.sleepLabel(sleepMode)}") }
        }
    }

    override fun state(): org.json.JSONObject {
        val latch = java.util.concurrent.CountDownLatch(1)
        val base = java.util.concurrent.atomic.AtomicReference<org.json.JSONObject>()
        val media = java.util.concurrent.atomic.AtomicReference<String?>()
        runOnUiThread {
            base.set(org.json.JSONObject().put("title", webView.title ?: "mkTV").put("url", webView.url ?: "")
                .put("nav", prefs.navMode).put("pointer", pointerActive())
                // Sleep timer: minutes chosen (0 off, -1 end of video) and whole seconds left for a timed one.
                .put("sleep", org.json.JSONObject().put("mode", sleepMode).put("left", (sleepLeftMs() + 999) / 1000)))
            if (!hasPage()) latch.countDown()
            else webView.evaluateJavascript("window.__sp ? __sp.status() : null") { r -> media.set(r); latch.countDown() }
        }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        val o = base.get() ?: org.json.JSONObject().put("title", "mkTV")
        parseJs(media.get())?.takeIf { it.optBoolean("has") }?.let { o.put("media", it) }
        return o
    }

    /** Home lists for the phone, as on the TV's Home screen. Never Recent while private browsing. */
    override fun home(): org.json.JSONObject {
        val latch = java.util.concurrent.CountDownLatch(1)
        val priv = java.util.concurrent.atomic.AtomicBoolean(true)
        runOnUiThread { priv.set(inPrivate); latch.countDown() }
        latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        val hidden = prefs.hiddenSections
        val favs = if ("fav" in hidden) emptyList() else prefs.shortcuts
        val recent = if (priv.get() || "rec" in hidden) emptyList() else prefs.recent.filterNot { r -> favs.any { it.second == r.second } }.take(10)
        fun tiles(list: List<Pair<String, String>>) = RemoteInput.tiles(list.map { (title, url) ->
            val host = Uri.parse(url).host
            RemoteInput.tile(title, url, host, faviconFile(host)?.exists() == true)
        })
        return org.json.JSONObject()
            .put("popular", tiles(if ("pop" in hidden) emptyList() else prefs.popular))
            .put("favourites", tiles(favs))
            .put("recent", tiles(recent))
    }

    override fun icon(host: String): ByteArray? =
        faviconFile(host)?.takeIf { it.isFile && it.length() in 1..512_000 }?.readBytes()

    override fun onPhoneConnected(address: String) = runOnUiThread {
        val pairing = remotePanel.visibility == View.VISIBLE
        // On the Devices tab, show the newcomer in the list instead of closing the panel.
        if (pairing && remoteTab == "devices") { renderRemoteDevices(); styleRemoteTabs(); wireRemoteFocus() } else closePhoneRemote()
        // Every new device is announced, so a phone you don't know about can't drive the TV unnoticed.
        showSnack(R.drawable.ic_phone, if (pairing) "Phone remote connected" else "New phone connected · $address", "Unpair all", reachable = !pairing) { unpairPhones() }
    }

    /** New pairing code: every phone that had the remote loses it until it scans the new QR code. */
    private fun unpairPhones() {
        remoteServer.resetToken()
        if (remotePanel.visibility == View.VISIBLE) showPhoneRemote()
        note("All phones unpaired. Scan the new code to connect again.")
    }

    /** Settings switch: off stops the server now and keeps it off until Phone remote is opened again. */
    private fun setRemoteEnabled(on: Boolean) {
        prefs.remoteEnabled = on
        if (on) startRemote() else remoteServer.stop()
    }

    // ---- ⋮ menu ----

    private val menuPanel: LinearLayout by lazy { findViewById(R.id.menuPanel) }
    private val menuScroll: View by lazy { findViewById(R.id.menuScroll) }
    private fun menuOpen() = menuScroll.visibility == View.VISIBLE

    private fun toggleMenu() { if (menuOpen()) closeMenu() else openMenu() }

    private fun openMenu() {
        shieldPanel.visibility = View.GONE
        inSubMenu = false
        buildMenu()
        menuScroll.visibility = View.VISIBLE
        menuScroll.scrollTo(0, 0)
        onOverlayChanged(firstMenuItem())
    }

    /** Keeps ▲/▼ inside the menu: the first and last focusable rows stop instead of jumping to the toolbar. */
    private fun trapMenuFocus() {
        val rows = mutableListOf<View>()
        fun walk(v: View) { if (v.isFocusable && v.visibility == View.VISIBLE) rows += v else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i)) }
        for (i in 0 until menuPanel.childCount) walk(menuPanel.getChildAt(i)) // the panel itself is clickable (so focusable): start at its rows
        rows.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId(); it.nextFocusLeftId = it.id; it.nextFocusRightId = it.id }
        rows.firstOrNull()?.let { it.nextFocusUpId = it.id }
        rows.lastOrNull()?.let { it.nextFocusDownId = it.id }
        // side-by-side buttons (Cancel / Clear) still move left and right between each other
        rows.zipWithNext().forEach { (x, y) -> if (x.parent === y.parent && x.parent !== menuPanel) { x.nextFocusRightId = y.id; y.nextFocusLeftId = x.id } }
    }

    /** "Add to Favourites" is disabled (not focusable) on Home, so start on the first item that can take focus. */
    private fun firstMenuItem(): View? = (0 until menuPanel.childCount).map { menuPanel.getChildAt(it) }.firstOrNull { it.isFocusable }

    private fun closeMenu() {
        if (!menuOpen()) return
        menuScroll.visibility = View.GONE
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
        item(R.drawable.ic_tabs, "Tabs (${modeTabs().size})", if (inPrivate) "Private" else null) { showTabsMenu() }
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
        item(R.drawable.ic_zoom, "Zoom", PageZoom.label(zoomFor(webView.url)), enabled = hasPage()) { showZoomMenu() }
        item(R.drawable.ic_desktop, "Desktop site", if (prefs.desktopMode) "On" else "Off") {
            prefs.desktopMode = !prefs.desktopMode; applyUserAgent(); if (hasPage()) webView.reload(); buildMenu()
        }
        item(R.drawable.ic_history, "History") { showHistory() }
        item(R.drawable.ic_download, "Downloads") { showDownloads() }
        item(R.drawable.ic_phone, "Phone remote") { showPhoneRemote() }
        item(R.drawable.ic_wifi, "Share Wi-Fi") { showShareWifi() }
        item(R.drawable.ic_delete, "Clear browsing data…") { showClearMenu() }
        item(R.drawable.ic_settings, "Settings") { closeMenu(); showSettings() }
        item(R.drawable.ic_info, "About mkTV", currentVersion) { showAboutMenu() }
        trapMenuFocus()
        if (focusedTag != null) menuPanel.findViewWithTag<View>(focusedTag)?.requestFocus()
    }

    // ---- Tab switcher (inside the ⋮ menu; hold Menu to open it directly) ----

    private fun showTabsMenu(focusTag: String? = null) {
        if (!menuOpen()) openMenu()
        inSubMenu = true
        menuPanel.removeAllViews()
        menuPanel.addView(TextView(this).apply {
            text = if (inPrivate) "Private tabs" else "Tabs"
            setTextColor(android.graphics.Color.WHITE); textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(6))
        })
        menuPanel.addView(rowContainer().apply {
            minimumHeight = dp(52); isClickable = true; isFocusable = true; tag = "tabs:new"
            addView(TextView(context).apply { text = "＋"; setTextColor(android.graphics.Color.WHITE); textSize = 18f; gravity = android.view.Gravity.CENTER },
                LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(context).apply { text = "New tab"; setTextColor(android.graphics.Color.WHITE); textSize = 16f; setPadding(dp(14), 0, 0, 0) })
            setOnClickListener { inSubMenu = false; closeMenu(); showTab(newTab(inPrivate)); openShortcuts() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Last child, so on a short screen the list scrolls instead of pushing the menu off the bottom.
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuPanel.addView(android.widget.ScrollView(this).apply { isFocusable = false; addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        modeTabs().forEach { t ->
            val url = t.currentUrl
            val blank = url.isBlank() || url == "about:blank"
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val main = rowContainer().apply {
                minimumHeight = dp(56); setPadding(dp(14), dp(6), dp(10), dp(6))
                isClickable = true; isFocusable = true; tag = "tab:${t.id}"
                val icon: View = t.icon?.let { bmp -> android.widget.ImageView(context).apply { setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } }
                    ?: if (blank) android.widget.ImageView(context).apply { setImageResource(R.drawable.ic_home) }
                    else siteAvatar(url, t.currentTitle).apply { (this as? TextView)?.textSize = 13f }
                addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
                val title = if (blank) "Home" else t.currentTitle.ifBlank { prettyHost(url) }
                val sub = buildString {
                    append(if (blank) "New tab" else prettyHost(url))
                    if (t === activeTab) append(" · showing")
                }
                addView(textBlock(title, sub).apply {
                    setPadding(dp(12), 0, 0, 0)
                    (getChildAt(0) as TextView).apply { textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                    (getChildAt(1) as TextView).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener {
                    inSubMenu = false; closeMenu()
                    if (t !== activeTab) { showTab(t); if (tabHasPage(activeTab)) closeShortcuts() else openShortcuts() }
                }
            }
            row.addView(main, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "✕"; setTextColor(android.graphics.Color.WHITE); textSize = 16f
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.row_bg)
                isClickable = true; isFocusable = true; tag = "tabx:${t.id}"
                contentDescription = "Close tab"
                setOnClickListener {
                    val mode = modeTabs()
                    val next = mode.getOrNull(mode.indexOf(t) + 1) ?: mode.getOrNull(mode.indexOf(t) - 1)
                    closeTab(t)
                    if (menuOpen() && inSubMenu) showTabsMenu(next?.let { n -> "tabx:${n.id}" })
                }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(4) })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        trapMenuFocus()
        (focusTag?.let { menuPanel.findViewWithTag<View>(it) } ?: menuPanel.findViewWithTag("tab:${activeTab.id}") ?: menuPanel.findViewWithTag<View>("tabs:new"))
            ?.requestFocus()
    }

    // ---- Clear browsing data (inside the ⋮ menu) ----

    private var clearHistory = true
    private var clearCookies = false
    private var clearCache = true
    private var inSubMenu = false

    private fun showClearMenu() {
        inSubMenu = true
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
        check("History", "Back/forward history, History and Recently visited", { clearHistory }) { clearHistory = it }
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
        buttons.addView(button("Cancel", R.drawable.toolbar_button, android.graphics.Color.WHITE) { inSubMenu = false; buildMenu(); firstMenuItem()?.requestFocus() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        buttons.addView(button("Clear", R.drawable.btn_danger, 0xFFFCA5A5.toInt()) { clearData() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(10) })
        menuPanel.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        trapMenuFocus()
        menuPanel.getChildAt(1)?.requestFocus()
    }

    // ---- About (inside the ⋮ menu): version, update, links ----

    private fun showAboutMenu() {
        inSubMenu = true
        menuPanel.removeAllViews()
        menuPanel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            addView(android.widget.ImageView(context).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(textBlock("mkTV", "Version $currentVersion · by MKTVHub").apply { setPadding(dp(14), 0, 0, 0) })
        })
        fun row(icon: Int, title: String, sub: String, onClick: (TextView) -> Unit) {
            val r = rowContainer().apply { minimumHeight = dp(56); isClickable = true; isFocusable = true; tag = "about:$title" }
            r.addView(android.widget.ImageView(this).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            val block = textBlock(title, sub).apply { setPadding(dp(14), 0, 0, 0) }
            r.addView(block, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.setOnClickListener { onClick(block.getChildAt(0) as TextView) }
            menuPanel.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val update = availableUpdate()
        row(R.drawable.ic_update,
            if (checkingUpdate) "Checking…" else if (update != null) "Update to ${update.version}" else "Check for updates",
            when {
                update != null -> "Downloads from GitHub, then Android asks you to install it"
                prefs.lastUpdateCheck == 0L -> "Not checked yet"
                else -> "Up to date · checked " + android.text.format.DateUtils.getRelativeTimeSpanString(prefs.lastUpdateCheck)
            }) { title ->
            if (update != null) startUpdate(update, title)
            else {
                title.text = "Checking…"
                checkForUpdate(force = true) {
                    if (menuOpen() && inSubMenu) { showAboutMenu(); menuPanel.findViewWithTag<View>("about:" + (availableUpdate()?.let { "Update to ${it.version}" } ?: "Check for updates"))?.requestFocus() }
                }
            }
        }
        fun open(url: String) { inSubMenu = false; closeMenu(); closeShortcuts(); load(url) }
        row(R.drawable.ic_home, "Website", "mktvhub.github.io") { open("https://mktvhub.github.io") }
        row(R.drawable.ic_star, "Star mkTV on GitHub", "Free and ad-free: a star helps others find it") { open("https://github.com/MKTVHub/mkTV") }
        row(R.drawable.ic_info, "Help & troubleshooting", "Phone remote, VPN, Wi-Fi name, streaming") { open("https://github.com/MKTVHub/mkTV#help--troubleshooting") }
        row(R.drawable.ic_grid, "Source code & releases", "github.com/MKTVHub/mkTV/releases") { open("https://github.com/MKTVHub/mkTV/releases") }
        menuPanel.addView(TextView(this).apply {
            text = "Free and open source · © MKTVHub"
            setTextColor(0xFF6B7280.toInt()); textSize = 12f
            setPadding(dp(14), dp(8), dp(14), dp(6))
        })
        trapMenuFocus()
        menuPanel.getChildAt(1)?.requestFocus()
    }

    private fun clearData() {
        val what = mutableListOf<String>()
        if (clearHistory) {
            tabs.forEach { it.webView?.clearHistory(); it.state = null } // a sleeping tab then reloads without its back list
            prefs.recent = emptyList()
            prefs.history = emptyList()
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
        inSubMenu = false
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
        // The playback controls sit over the video: no toolbar sliding in over it.
        if (overlayOpen()) { if (!controlsOpen()) showBar(autoHide = false) } else scheduleHideBar(2500)
        webContainer.descendantFocusability =
            if (overlayOpen()) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        // requestFocusFromTouch: a mouse click or tap leaves touch mode on, where a plain
        // requestFocus() fails and the remote's keys would still go to the page behind
        if (focusTarget != null) focusTarget.requestFocusFromTouch() else if (!overlayOpen()) webView.requestFocus()
        refreshCursor()
    }

    private fun applyNavMode() {
        cursor.jumpMode = jumpMode
        applyPointerTuning()
        if (!jumpMode) cursor.showRing(null)
        refreshCursor()
    }

    /** Settings → Navigation → Pointer speed / acceleration, applied live (the frame loop reads these fields). */
    private fun applyPointerTuning() {
        cursor.speedLevel = prefs.pointerSpeed
        cursor.accelerate = prefs.pointerAccel
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

    /** When mkTV last came to the front, and which OK presses started inside it. */
    private var shownAt = 0L
    private val okDown = HashSet<Int>()
    private var menuHeld = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // OK pressed in the launcher to open mkTV (or double-pressed) must not also click the focused
        // Home tile: drop OK in the first moment after coming to the front, and any OK release whose
        // press happened before mkTV was in front.
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (ok && !isKeyboardVisible()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 && SystemClock.uptimeMillis() - shownAt < LAUNCH_GUARD_MS) return true
                okDown += event.keyCode
            } else if (event.action == KeyEvent.ACTION_UP && !okDown.remove(event.keyCode)) return true
        }
        if (isKeyboardVisible()) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        val f = currentFocus
        // After the sleep timer let the screen turn off, any key keeps it on again.
        if (down && !root.keepScreenOn) root.keepScreenOn = true
        // Menu over the playback controls just closes them (one overlay at a time).
        if (controlsOpen() && (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_BOOKMARK)) {
            if (event.action == KeyEvent.ACTION_UP) closeControls()
            return true
        }
        // ⋮ menu from anywhere: page, home screen, toolbar or an open panel. Holding Menu opens the tab switcher.
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_BOOKMARK) {
            if (down && event.repeatCount == 1 && !menuHeld) { menuHeld = true; showTabsMenu() }
            if (event.action == KeyEvent.ACTION_UP) { if (menuHeld) menuHeld = false else toggleMenu() }
            return true
        }
        // Remote's search/mic key, where the system passes it to apps: voice search from anywhere.
        if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (event.action == KeyEvent.ACTION_UP) startVoice()
            return true
        }
        // Toolbar / overlays / bottom bar: plain TV focus navigation.
        if (!pageActive()) {
            if (settingsPanel.visibility == View.VISIBLE && down && settingsKey(f, event.keyCode)) return true
            if (controlsOpen() && down && trapFocus(controls!!, f, event.keyCode)) return true
            // ▼ from the address field goes into its suggestions, not to the page.
            if (suggestOpen() && f === urlField && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) suggestPanel.findViewWithTag<View>("sug:0")?.requestFocus()
                return true
            }
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
                // ▼ past the end of the page reaches a download notice's Open button.
                else if (obj.getString("edge") == "down") findViewById<View>(R.id.snackAction).takeIf { snackReachable && snackbar.visibility == View.VISIBLE && it.visibility == View.VISIBLE }?.requestFocus()
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
            historyPanel.visibility == View.VISIBLE -> findViewById<View>(R.id.historyScroll)
            downloadsPanel.visibility == View.VISIBLE -> findViewById<View>(R.id.downloadsScroll)
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
        if (prefs.autoHideToolbar || filling) root.postDelayed(hideBarRunnable, delay)
    }

    private fun hideBar() {
        if (!barShown || !(canHideBar() || (filling && !overlayOpen()))) return
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
            syncFill(o.optBoolean("fill"))
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
        applySpeed()
        showDock()
    }

    /** Real full screen (the page's own view) or the CSS fill fallback. */
    private val theater get() = customView != null || filling

    private fun toggleTheater() {
        if (customView != null) { exitFullscreen(notifyPage = true); return }
        if (filling) { exitFill(); return }
        // A key press counts as user activation, which the Fullscreen API requires.
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F12))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_F12))
        webView.evaluateJavascript("window.__sp ? __sp.fullscreen() : null") { res -> afterFullscreenRequest(res) }
    }

    private fun exitWatch(silent: Boolean = false) {
        if (!watching) return
        watching = false
        closeControls()
        webView.evaluateJavascript("window.__sp && __sp.clear()", null)
        cursor.showRing(null)
        if (!silent) watchDismissedFor = webView.url
        hideDock()
        refreshCursor()
        if (!silent && jumpMode) jump("none")
    }

    private var okHeld = false

    /** Keys while watching. Vertical feeds (Shorts, TikTok): ▲▼ = previous/next. ◀▶ seek, OK pause, hold OK = controls. */
    private fun handleWatchKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!watchVertical) {
                    // Normal video: ▼ = full screen, ▲ = playback controls (Back leaves full screen / watch mode).
                    if (down && e.repeatCount == 0) {
                        if (e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { if (!theater) toggleTheater() else showDock() }
                        else openControls()
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
            // Pause / play on release, so holding OK can open the controls instead (▲ is "previous" in feeds).
            when {
                down && e.repeatCount == 0 -> okHeld = false
                down && e.repeatCount == 1 -> { okHeld = true; openControls() }
                !down && !okHeld -> togglePlay()
            }
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
                "paused" -> { flash("❚❚"); ctlPlaying = false }
                "playing" -> { flash("▶"); ctlPlaying = true }
            }
            if (controlsOpen()) renderControls()
        }
        if (watching) showDock()
    }

    private val hideDockRunnable = Runnable { hideDock() }

    private fun showDock() {
        val dock = findViewById<LinearLayout>(R.id.watchDock)
        dock.removeAllViews()
        if (controlsOpen()) return
        val hints = buildList {
            if (watchVertical) add("▲ ▼" to "Previous / next")
            else { if (!theater) add("▼" to "Full screen"); add("▲" to "Controls") }
            add("◀ ▶" to "10s"); add("OK" to "Pause / play")
            if (watchVertical) add("Hold OK" to "Controls")
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

    // ---- Playback controls (watch mode): speed per site, ±10 s, sleep timer (pure parts in PlaybackSpeed) ----

    /** Private browsing keeps its speed changes here only, so no visited host is written to Prefs. */
    private val privateSpeed = HashMap<String, Double>()

    private fun speedFor(url: String?): Double {
        val host = url?.let { Uri.parse(it).host }
        if (inPrivate) PageZoom.hostKey(host)?.let { privateSpeed[it] }?.let { return it }
        return PlaybackSpeed.speedFor(host, prefs.siteSpeed)
    }

    /** Tells the page the site's speed; the page applies it to each video as it starts (1× pages are left alone). */
    private fun applySpeed(view: WebView = webView, url: String? = view.url) {
        val s = speedFor(url)
        if (s != PlaybackSpeed.DEFAULT) view.evaluateJavascript(PlaybackSpeed.script(s), null)
    }

    /** Applies at once and is remembered for this site (in memory only while private). */
    private fun setSpeed(v: Double) {
        val s = PlaybackSpeed.snap(v)
        val host = webView.url?.let { Uri.parse(it).host }
        PageZoom.hostKey(host)?.let { key ->
            if (inPrivate) privateSpeed[key] = s else prefs.siteSpeed = PlaybackSpeed.withSpeed(prefs.siteSpeed, host, s)
        }
        ctlSpeed = s
        webView.evaluateJavascript(PlaybackSpeed.script(s), null)
        flash(PlaybackSpeed.label(s))
        if (controlsOpen()) renderControls()
    }

    // Sleep timer: one Handler post, not a loop, so it also fires while the native player is in front.
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sleepMode = PlaybackSpeed.SLEEP_OFF
    private var sleepAt = 0L
    private val sleepFire = Runnable { sleepNow() }
    private val sleepEndCheck = Runnable { checkSleepEnd() }

    private fun sleepLeftMs() = if (sleepMode > 0) (sleepAt - SystemClock.uptimeMillis()).coerceAtLeast(0) else 0L

    private fun setSleep(m: Int) {
        sleepHandler.removeCallbacks(sleepFire)
        sleepHandler.removeCallbacks(sleepEndCheck)
        if (sleepMode == PlaybackSpeed.SLEEP_END && m != PlaybackSpeed.SLEEP_END)
            webView.evaluateJavascript("window.__sp && __sp.sleepEnd && __sp.sleepEnd(false)", null)
        sleepMode = m
        PlayerActivity.sleepAtEnd = m == PlaybackSpeed.SLEEP_END
        when {
            m > 0 -> { sleepAt = SystemClock.uptimeMillis() + m * 60_000L; sleepHandler.postDelayed(sleepFire, m * 60_000L) }
            m == PlaybackSpeed.SLEEP_END -> checkSleepEnd()
        }
        if (controlsOpen()) renderControls()
    }

    /**
     * "End of video": the page pauses itself just before the end (see __sp.sleepEnd); this only re-arms it
     * after navigations and learns that it fired. Checks are timed to the video's remaining time (2–30 s apart).
     */
    private fun checkSleepEnd() {
        if (sleepMode != PlaybackSpeed.SLEEP_END) return
        // The native player reached its end first and used the timer up.
        if (!PlayerActivity.sleepAtEnd) { sleepDone(); return }
        webView.evaluateJavascript("window.__sp && __sp.sleepEnd ? __sp.sleepEnd(true) : null") { res ->
            if (sleepMode != PlaybackSpeed.SLEEP_END) return@evaluateJavascript
            val o = parseJs(res)
            if (o?.optBoolean("fired") == true) { sleepDone(); return@evaluateJavascript }
            val left = o?.optDouble("left", -1.0) ?: -1.0
            sleepHandler.postDelayed(sleepEndCheck, if (left > 0) ((left - 1) * 1000).toLong().coerceIn(2_000, 30_000) else 5_000)
        }
    }

    /** Timed sleep: pause every tab's media and the native player; mkTV stays open. */
    private fun sleepNow() {
        tabs.mapNotNull { it.webView }.forEach { it.evaluateJavascript("window.__sp && __sp.pause && __sp.pause()", null) }
        PlayerActivity.pauseForSleep()
        sleepDone()
    }

    private fun sleepDone() {
        sleepHandler.removeCallbacks(sleepFire)
        sleepHandler.removeCallbacks(sleepEndCheck)
        sleepMode = PlaybackSpeed.SLEEP_OFF
        PlayerActivity.sleepAtEnd = false
        ctlPlaying = false
        // Let the TV's own screen saver / sleep take over; the next key press keeps the screen on again.
        root.keepScreenOn = false
        showSnack(R.drawable.ic_bedtime, "Sleep timer: paused", "Play") { togglePlay() }
        if (controlsOpen()) renderControls()
    }

    private var controls: LinearLayout? = null
    private var ctlSpeed = PlaybackSpeed.DEFAULT
    private var ctlPlaying = true
    /** Live streams have no end, so "End of video" is offered only for videos with a duration. */
    private var ctlLive = false
    private var ctlSleepTitle: TextView? = null

    private fun controlsOpen() = controls?.visibility == View.VISIBLE

    /** ▲ (or hold OK) in watch mode: a small panel over the video. Changes apply at once; Back closes it. */
    private fun openControls() {
        if (controlsOpen()) return
        webView.evaluateJavascript("window.__sp ? __sp.status() : null") { res ->
            if (!watching || controlsOpen()) return@evaluateJavascript
            val o = parseJs(res)?.takeIf { it.optBoolean("has") }
            ctlSpeed = PlaybackSpeed.snap(o?.optDouble("speed", speedFor(webView.url)) ?: speedFor(webView.url))
            ctlPlaying = o?.optBoolean("playing", true) ?: true
            ctlLive = o != null && o.optDouble("d", 0.0) <= 0
            hideDock()
            val panel = controls ?: LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.panel_bg)
                elevation = dp(20).toFloat() // above the page's own full-screen view (and the dock)
                setPadding(dp(20), dp(14), dp(20), dp(16))
                isClickable = true // touches on the panel don't fall through to the page
                root.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })
                controls = this
            }
            panel.visibility = View.VISIBLE
            renderControls()
            onOverlayChanged(panel.findViewWithTag("ctl:play"))
            root.removeCallbacks(controlsTick)
            root.postDelayed(controlsTick, 1000)
        }
    }

    private fun closeControls() {
        val panel = controls ?: return
        if (panel.visibility != View.VISIBLE) return
        root.removeCallbacks(controlsTick)
        panel.visibility = View.GONE
        onOverlayChanged(null)
    }

    /** Remaining sleep time, once a second, only while the panel is open. */
    private val controlsTick = object : Runnable {
        override fun run() {
            if (!controlsOpen()) return
            if (sleepMode > 0) ctlSleepTitle?.text = sleepTitle()
            root.postDelayed(this, 1000)
        }
    }

    private fun sleepTitle() = when (sleepMode) {
        PlaybackSpeed.SLEEP_OFF -> "Sleep timer · Off"
        PlaybackSpeed.SLEEP_END -> "Sleep timer · pauses at the end of this video"
        else -> "Sleep timer · ${PlaybackSpeed.remaining(sleepLeftMs())} left"
    }

    /** Rebuilds the panel in place, keeping focus on the same button. */
    private fun renderControls() {
        val panel = controls ?: return
        val focusTag = currentFocus?.takeIf { isInside(panel, it) }?.tag
        panel.removeAllViews()
        fun heading(t: String) = TextView(this).apply {
            text = t; setTextColor(0xFFD1D5DB.toInt()); textSize = 14f
            setPadding(dp(4), dp(12), 0, dp(6))
        }
        fun chip(icon: Int, label: String, tag: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
            text = iconText(icon, label, 20)
            this.tag = tag
            gravity = android.view.Gravity.CENTER
            setTextColor(if (on) android.graphics.Color.WHITE else 0xFFD1D5DB.toInt()); textSize = 15f
            setTypeface(typeface, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setBackgroundResource(if (on) R.drawable.seg_on else R.drawable.seg_off)
            setPadding(dp(14), 0, dp(14), 0)
            alpha = if (enabled) 1f else 0.4f
            isFocusable = enabled; isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
        }
        fun row(views: List<View>) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { if (i > 0) marginStart = dp(8) }) }
        }

        panel.addView(row(listOf(
            chip(R.drawable.ic_replay, "−10 s", "ctl:back", false) { webView.evaluateJavascript("window.__sp && __sp.seek(-10)", null); flash("⟲ 10") },
            chip(if (ctlPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (ctlPlaying) "Pause" else "Play", "ctl:play", false) { togglePlay() },
            chip(R.drawable.ic_forward_step, "+10 s", "ctl:fwd", false) { webView.evaluateJavascript("window.__sp && __sp.seek(10)", null); flash("10 ⟳") },
        )))
        panel.addView(heading("Speed · ${PlaybackSpeed.label(ctlSpeed)}"))
        panel.addView(row(PlaybackSpeed.LEVELS.map { v ->
            val icon = when { v < 1.0 -> R.drawable.ic_speed_slow; v > 1.0 -> R.drawable.ic_speed_fast; else -> R.drawable.ic_speed }
            chip(icon, PlaybackSpeed.label(v), "ctl:speed:$v", v == ctlSpeed) { setSpeed(v) }
        }))
        panel.addView(heading(sleepTitle()).also { ctlSleepTitle = it })
        panel.addView(row(PlaybackSpeed.SLEEP.map { m ->
            val icon = when (m) { PlaybackSpeed.SLEEP_OFF -> R.drawable.ic_close; PlaybackSpeed.SLEEP_END -> R.drawable.ic_video_end; else -> R.drawable.ic_timer }
            chip(icon, PlaybackSpeed.sleepLabel(m), "ctl:sleep:$m", m == sleepMode,
                enabled = m != PlaybackSpeed.SLEEP_END || !ctlLive || sleepMode == m) { setSleep(m) }
        }))
        if (focusTag != null) panel.findViewWithTag<View>(focusTag)?.takeIf { it.isFocusable }?.requestFocus()
    }

    /** Keeps the D-pad inside [panel]: a move that would leave it is swallowed. */
    private fun trapFocus(panel: ViewGroup, f: View?, code: Int): Boolean {
        val dir = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP; KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        if (f == null || !isInside(panel, f)) { panel.findViewWithTag<View>("ctl:play")?.requestFocus(); return true }
        val next = f.focusSearch(dir)
        return next == null || !isInside(panel, next)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            controlsOpen() -> closeControls()
            customView != null -> exitFullscreen(notifyPage = true)
            filling && !overlayOpen() -> exitFill()
            suggestOpen() && isInside(suggestPanel, currentFocus) -> urlField.requestFocus()
            suggestOpen() -> hideSuggestions()
            wifiPanel.visibility == View.VISIBLE && currentFocus is EditText -> { hideKeyboard(currentFocus as EditText); wifiPanel.requestFocus() }
            wifiPanel.visibility == View.VISIBLE -> closeShareWifi()
            remotePanel.visibility == View.VISIBLE && remoteHelpShown -> toggleRemoteHelp()
            remotePanel.visibility == View.VISIBLE -> closePhoneRemote()
            menuOpen() && inSubMenu -> { inSubMenu = false; buildMenu(); firstMenuItem()?.requestFocus() }
            menuOpen() -> closeMenu()
            watching && theater -> toggleTheater()
            watching -> exitWatch()
            settingsPanel.visibility == View.VISIBLE -> closeSettings()
            historyPanel.visibility == View.VISIBLE -> closeHistory()
            downloadsPanel.visibility == View.VISIBLE -> closeDownloads()
            shieldPanel.visibility == View.VISIBLE -> closeShieldPanel()
            shortcutsPanel.visibility == View.VISIBLE && editingShortcuts && selectedFav != null -> {
                val i = selectedFav!!.index; selectedFav = null; renderShortcuts(); favTile(i)?.requestFocus()
            }
            shortcutsPanel.visibility == View.VISIBLE && editingShortcuts -> { editingShortcuts = false; renderShortcuts(); findViewById<View>(R.id.shortcutsEdit).requestFocus() }
            shortcutsPanel.visibility == View.VISIBLE && editingPopular -> { editingPopular = false; renderShortcuts(); findViewById<View>(R.id.popularEdit).requestFocus() }
            shortcutsPanel.visibility == View.VISIBLE && openFolder >= 0 -> closeFavFolder()
            shortcutsPanel.visibility == View.VISIBLE && hasPage() -> closeShortcuts()
            isInside(toolbar, currentFocus) -> leaveToPage()
            isInside(snackbar, currentFocus) -> hideSnackbar()
            webView.canGoBack() -> webView.goBack()
            // First page of a tab (e.g. a pop-up) with other tabs open: close it and return to the previous tab.
            modeTabs().size > 1 -> { closeTab(activeTab); note("Tab closed") }
            SystemClock.uptimeMillis() - lastBackPress < 2000 -> @Suppress("DEPRECATION") super.onBackPressed()
            else -> { lastBackPress = SystemClock.uptimeMillis(); note("Press Back again to exit") }
        }
    }

    // endregion

    // region Helpers

    /** Turns address-bar input into a URL: adds https:// or searches with the chosen engine. */
    private fun normalizeInput(input: String): String =
        HostRules.stripTracking(WebSearch.normalize(input, WebSearch.engine(prefs.searchEngine)))

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
        // One mkTV (singleTask): a link from another app opens in a new tab, or in this one if it's still blank.
        intent.dataString?.let { closeShortcuts(); val url = normalizeInput(it); if (hasPage()) openInNewTab(url, isPrivate = false) else load(url) }
    }

    override fun onPause() {
        super.onPause()
        val add = pendingTotal.getAndSet(0)
        if (add > 0) prefs.totalBlocked += add
        cursor.releaseAll()
        webView.onPause()
    }

    override fun onResume() {
        shownAt = SystemClock.uptimeMillis(); okDown.clear()
        super.onResume()
        webView.onResume()
        root.keepScreenOn = true // the sleep timer may have let it go
        hideSystemBars()
        blocker.load() // builds the lists once, then refreshes any over a day old; cheap otherwise
    }

    /**
     * The phone remote listens whenever mkTV is on screen (once the user has opened it), with the same
     * port and pairing code, so a phone that already has the remote page open just keeps working.
     * Coming back from the background the old socket can still be closing: retry for a few seconds.
     */
    private fun startRemote(attempt: Int = 0) {
        remoteServer.start()
        if (!remoteServer.running && attempt < 6) root.postDelayed({ if (!isFinishing) startRemote(attempt + 1) }, 700)
    }

    override fun onStart() {
        super.onStart()
        if (prefs.remoteEnabled) startRemote()
        downloads.onStart()
    }

    override fun onStop() {
        super.onStop()
        remoteServer.stop()
        downloads.onStop()
    }

    override fun onDestroy() {
        sleepHandler.removeCallbacksAndMessages(null)
        PlayerActivity.sleepAtEnd = false
        remoteServer.stop()
        tabs.forEach { destroyTab(it) }
        if (multiProfile) runCatching { androidx.webkit.ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
        super.onDestroy()
    }

    // endregion

    companion object {
        /** Settings sidebar, in order: each title must match a section(...) in renderSettings. */
        private val SETTINGS_TABS = listOf(
            "Navigation" to R.drawable.ic_mode_jump,
            "Start screen" to R.drawable.ic_home,
            "Search" to R.drawable.ic_search,
            "Protection" to R.drawable.ic_shield_on,
            "Blocklists" to R.drawable.ic_grid,
            "Privacy" to R.drawable.ic_private,
            "Phone remote" to R.drawable.ic_phone,
            "Browsing" to R.drawable.ic_desktop,
            "Downloads" to R.drawable.ic_download,
            "Updates" to R.drawable.ic_update,
        )
        /** OK presses this soon after mkTV comes to the front are the launcher's, not the user's choice. */
        private const val LAUNCH_GUARD_MS = 900L
        private const val PRIVATE_PROFILE = "private"
        /** Run on a tab as it goes to the background, so its video or music doesn't keep playing unseen. */
        private const val PAUSE_MEDIA_JS =
            "try{Array.prototype.forEach.call(document.querySelectorAll('video,audio'),function(m){try{m.pause()}catch(e){}})}catch(e){}"

        /** A Smart-TV browser UA: YouTube serves its remote-friendly TV interface to it. */
        private const val TV_UA =
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) 85.0.4183.93/6.5 TV Safari/537.36"

        private val DESKTOP_ONLY = setOf("tiktok.com")

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private const val REQ_VOICE = 41
        private const val HISTORY_RENDER_MAX = 150
    }
}
