package com.mktvhub.mktv

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File

/**
 * Downloads, Android side: the confirm prompt, the system DownloadManager for http(s), data: and blob:
 * files saved by mkTV itself, live status, Open / Remove / Delete and the Downloads panel's rows.
 * Pure helpers (names, sizes, the saved list) are in [Downloads].
 *
 * Where files go, with no storage permission:
 * - Android 10+ (API 29+): the shared Downloads folder (DownloadManager public dir, MediaStore for data:/blob:).
 * - Android 7–9: mkTV's own external folder (Android/data/com.mktvhub.mktv/files/Download), because the
 *   shared folder would need WRITE_EXTERNAL_STORAGE there. Uninstalling mkTV deletes those files.
 *
 * Private browsing: entries live in memory only and are dropped when private browsing closes; the files stay.
 */
class DownloadCenter(private val activity: Activity, private val prefs: Prefs, private val panel: View, private val host: Host) {

    interface Host {
        fun note(msg: String)
        /** A download started in this session has finished. */
        fun downloaded(e: Downloads.Entry)
        fun panelOpen(): Boolean
    }

    private enum class State { RUNNING, DONE, FAILED, GONE }
    private data class Live(val status: Int, val done: Long, val total: Long, val reason: Int, val localUri: String?)

    private val dm: DownloadManager? by lazy { activity.getSystemService(DownloadManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var saved: List<Downloads.Entry> = prefs.downloads
    private var privateList: List<Downloads.Entry> = emptyList()
    /** Latest DownloadManager status by DownloadManager id. */
    private val live = HashMap<Long, Live>()
    /** DownloadManager ids whose finish should be announced (started or seen running in this session). */
    private val watching = HashSet<Long>()
    private var started = false
    private var ticking = false
    private var blobSeq = 0

    fun entries(): List<Downloads.Entry> = (privateList + saved).sortedByDescending { it.time }

    // ---- Starting a download ----

    /** WebView's DownloadListener, already on the UI thread. [cookie] comes from the WebView's own (possibly private) cookie jar. */
    fun offer(wv: WebView, isPrivate: Boolean, url: String, userAgent: String?, contentDisposition: String?, mime: String?, length: Long, cookie: String?) {
        val referer = wv.url?.takeIf { it.startsWith("http") }
        val pageSite = referer?.let { Uri.parse(it).host?.removePrefix("www.") } ?: ""
        when (url.substringBefore(':').lowercase()) {
            "http", "https" -> {
                val name = Downloads.fileName(url, contentDisposition, mime)
                val site = Uri.parse(url).host?.removePrefix("www.") ?: pageSite
                confirm(name, length, site, isPrivate, onNo = {}) {
                    enqueue(url, name, mime, userAgent, cookie, referer, site, isPrivate, length)
                }
            }
            "data" -> {
                val d = Downloads.parseDataUrl(url) ?: return cantSave()
                val size = Downloads.decodedSize(d)
                if (size > Downloads.INLINE_MAX_BYTES) return cantSave(tooBig = true)
                val name = Downloads.fileName(null, contentDisposition, d.mime)
                confirm(name, size, pageSite, isPrivate, onNo = {}) {
                    val bytes = decode(d) ?: return@confirm cantSave()
                    saveInline(bytes, name, d.mime, pageSite, isPrivate)
                }
            }
            "blob" -> {
                // Fetch right away: sites often revoke the blob: URL just after the click.
                val key = startBlob(wv, url)
                val name = Downloads.fileName(null, contentDisposition, mime)
                val site = Uri.parse(url.removePrefix("blob:")).host?.removePrefix("www.") ?: pageSite
                confirm(name, length, site, isPrivate, onNo = { dropBlob(wv, key) }) {
                    pullBlob(wv, key) { dataUrl, tooBig ->
                        val d = dataUrl?.let { Downloads.parseDataUrl(it) }
                        val bytes = d?.let { decode(it) }
                        if (d == null || bytes == null) { cantSave(tooBig); return@pullBlob }
                        val finalName = if (name.contains('.')) name else Downloads.extensionFor(d.mime)?.let { "$name.$it" } ?: name
                        saveInline(bytes, finalName, d.mime, site, isPrivate)
                    }
                }
            }
            else -> cantSave()
        }
    }

    private fun cantSave(tooBig: Boolean = false) = host.note(
        if (tooBig) "This download is made by the page itself and is over ${Downloads.formatSize(Downloads.INLINE_MAX_BYTES)}, too big to save on TV."
        else "This site's download can't be saved on TV.",
    )

    private val whereText get() = if (Build.VERSION.SDK_INT >= 29) "Saved to the TV's Downloads folder."
        else "Saved in mkTV's own folder on the TV. Uninstalling mkTV deletes it."

    private fun confirm(name: String, size: Long, site: String, isPrivate: Boolean, onNo: () -> Unit, onYes: () -> Unit) {
        val details = listOf(Downloads.formatSize(size), site).filter { it.isNotBlank() }.joinToString(" · ")
        val msg = buildString {
            append(name)
            if (details.isNotEmpty()) append("\n").append(details)
            append("\n\n").append(whereText)
            if (isPrivate) append("\n\nYou're in private browsing. The file stays on the TV after private browsing closes; mkTV only forgets it from its Downloads list.")
        }
        var answered = false
        val d = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (isPrivate) "Download in private browsing?" else "Download this file?")
            .setMessage(msg)
            .setPositiveButton("Download") { _, _ -> answered = true; onYes() }
            .setNegativeButton("Cancel") { _, _ -> answered = true; onNo() }
            .setOnDismissListener { if (!answered) { answered = true; onNo() } }
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
        d.show()
    }

    private fun enqueue(url: String, name: String, mime: String?, ua: String?, cookie: String?, referer: String?, site: String, isPrivate: Boolean, size: Long) {
        val m = Downloads.mimeFor(name, mime)
        val dm = dm ?: return host.note("This TV has no download service, so the file can't be saved.")
        val finalName: String
        val dmId = try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setMimeType(m)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Same cookies, user agent and Referer as the page, so signed-in and hotlink-protected downloads work.
            if (!cookie.isNullOrBlank()) req.addRequestHeader("Cookie", cookie)
            if (!ua.isNullOrBlank()) req.addRequestHeader("User-Agent", ua)
            if (referer != null) req.addRequestHeader("Referer", referer)
            if (Build.VERSION.SDK_INT >= 29) {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                finalName = Downloads.uniqueName(name) { File(dir, it).exists() }
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalName)
            } else {
                val dir = ownDir() ?: throw IllegalStateException("no external files dir")
                finalName = Downloads.uniqueName(name) { File(dir, it).exists() }
                req.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, finalName)
            }
            dm.enqueue(req)
        } catch (e: Exception) {
            // Disabled Download Manager app, no storage, or an address it refuses.
            host.note("This TV's download service couldn't start the download.")
            return
        }
        val e = Downloads.Entry(newId(), finalName, m, site, System.currentTimeMillis(), dmId = dmId, size = size, private = isPrivate)
        addEntry(e)
        watching += dmId
        host.note("Downloading $finalName · ⋮ → Downloads")
        kick()
    }

    private fun ownDir(): File? = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.apply { mkdirs() }

    private fun newId(): Long {
        var id = System.currentTimeMillis()
        val all = entries().map { it.id }.toSet()
        while (id in all) id++
        return id
    }

    private fun addEntry(e: Downloads.Entry) {
        if (e.private) privateList = Downloads.add(privateList, e)
        else { saved = Downloads.add(saved, e); prefs.downloads = saved }
        if (host.panelOpen()) renderPanel()
    }

    private fun removeEntry(id: Long) {
        privateList = Downloads.remove(privateList, id)
        if (saved.any { it.id == id }) { saved = Downloads.remove(saved, id); prefs.downloads = saved }
    }

    private fun changeEntry(id: Long, change: (Downloads.Entry) -> Downloads.Entry) {
        privateList = Downloads.update(privateList, id, change)
        if (saved.any { it.id == id }) { saved = Downloads.update(saved, id, change); prefs.downloads = saved }
    }

    /** Private browsing closed: forget its downloads (the files stay) and don't announce them later. */
    fun dropPrivate() {
        privateList.forEach { watching -= it.dmId }
        privateList = emptyList()
        if (host.panelOpen()) renderPanel()
    }

    // ---- data: and blob: (bytes come from the page, written by mkTV) ----

    private fun decode(d: Downloads.DataUrl): ByteArray? = runCatching {
        if (d.base64) android.util.Base64.decode(if ('%' in d.payload) Downloads.percentDecode(d.payload) else d.payload, android.util.Base64.DEFAULT)
        else Downloads.percentDecodeBytes(d.payload)
    }.getOrNull()

    /**
     * Reads the blob in the page as a base64 data: URL into a window variable. There is no JS bridge:
     * Kotlin polls it with evaluateJavascript and copies it out in chunks, then deletes it.
     */
    private fun startBlob(wv: WebView, url: String): String {
        val key = "__mkTvDl" + (++blobSeq)
        val js = "(function(u,k,cap){var o={s:'busy'};window[k]=o;try{var x=new XMLHttpRequest();x.open('GET',u,true);x.responseType='blob';" +
            "x.onload=function(){var b=x.response;if(!b){o.s='err';return}if(b.size>cap){o.s='big';return}" +
            "var r=new FileReader();r.onload=function(){o.d=String(r.result);o.s='ok'};r.onerror=function(){o.s='err'};r.readAsDataURL(b)};" +
            "x.onerror=function(){o.s='err'};x.send()}catch(e){o.s='err'}})(" +
            org.json.JSONObject.quote(url) + ",'" + key + "'," + Downloads.INLINE_MAX_BYTES + ");void 0"
        runCatching { wv.evaluateJavascript(js, null) }
        return key
    }

    private fun dropBlob(wv: WebView, key: String) { runCatching { wv.evaluateJavascript("try{delete window['$key']}catch(e){};void 0", null) } }

    private fun pullBlob(wv: WebView, key: String, done: (dataUrl: String?, tooBig: Boolean) -> Unit) {
        var waited = 0
        fun poll() {
            wv.evaluateJavascript("(function(o){return o?JSON.stringify({s:o.s,l:o.d?o.d.length:0}):null})(window['$key'])") { res ->
                val o = jsString(res)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
                when (o?.optString("s")) {
                    "busy" -> if (waited < 60_000) { waited += 250; handler.postDelayed({ poll() }, 250) } else { dropBlob(wv, key); done(null, false) }
                    "ok" -> readChunks(wv, key, o.optInt("l"), StringBuilder(), done)
                    "big" -> { dropBlob(wv, key); done(null, true) }
                    else -> { dropBlob(wv, key); done(null, false) }
                }
            }
        }
        runCatching { poll() }.onFailure { done(null, false) }
    }

    private fun readChunks(wv: WebView, key: String, length: Int, sb: StringBuilder, done: (String?, Boolean) -> Unit) {
        if (sb.length >= length) { dropBlob(wv, key); done(sb.toString(), false); return }
        wv.evaluateJavascript("(function(o){return o&&o.d?o.d.substr(${sb.length},$CHUNK):''})(window['$key'])") { res ->
            val part = jsString(res)
            if (part.isNullOrEmpty()) { dropBlob(wv, key); done(null, false) }
            else { sb.append(part); readChunks(wv, key, length, sb, done) }
        }
    }

    /** evaluateJavascript hands back a JSON value; our snippets return strings. */
    private fun jsString(res: String?): String? =
        if (res.isNullOrEmpty() || res == "null") null else runCatching { org.json.JSONTokener(res).nextValue() as? String }.getOrNull()

    private fun saveInline(bytes: ByteArray, name: String, mime: String, site: String, isPrivate: Boolean) {
        val m = Downloads.mimeFor(name, mime)
        kotlin.concurrent.thread(name = "download-save") {
            val result = runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    val resolver = activity.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, m)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                    try {
                        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
                    // MediaStore renames on a clash ("name (1).ext"); show the real name.
                    val real = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: name
                    real to uri.toString()
                } else {
                    val dir = ownDir()!!
                    val file = File(dir, Downloads.uniqueName(name) { File(dir, it).exists() })
                    file.writeBytes(bytes)
                    file.name to Uri.fromFile(file).toString()
                }
            }.getOrNull()
            activity.runOnUiThread {
                if (result == null) { host.note("Couldn't save $name on the TV."); return@runOnUiThread }
                val e = Downloads.Entry(newId(), result.first, m, site, System.currentTimeMillis(), uri = result.second, size = bytes.size.toLong(), private = isPrivate)
                addEntry(e)
                host.downloaded(e)
            }
        }
    }

    // ---- Status ----

    fun onStart() { started = true; kick() }

    fun onStop() { started = false; ticking = false; handler.removeCallbacks(tick) }

    /** Polls while something is downloading or the panel is open (no broadcast receiver needed). */
    fun kick() {
        if (!started || ticking) return
        ticking = true
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val active = refresh()
            if (host.panelOpen()) updatePanel()
            if (started && (active || host.panelOpen())) handler.postDelayed(this, 700) else ticking = false
        }
    }

    /** Reads every tracked download's status; announces finished ones. True while any is still going. */
    private fun refresh(): Boolean {
        val dm = dm ?: return false
        val ids = entries().filter { it.dmId >= 0 }.map { it.dmId }
        if (ids.isEmpty()) return false
        val seen = HashSet<Long>()
        runCatching {
            dm.query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                val iId = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val iStatus = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val iDone = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTotal = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iReason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val iLocal = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    seen += id
                    live[id] = Live(c.getInt(iStatus), c.getLong(iDone), c.getLong(iTotal), c.getInt(iReason), if (iLocal >= 0) c.getString(iLocal) else null)
                }
            }
        }.onFailure { return false }
        ids.filter { it !in seen }.forEach { live[it] = Live(STATUS_GONE, 0, -1, 0, null) }
        var active = false
        entries().filter { it.dmId >= 0 }.forEach { e ->
            val l = live[e.dmId] ?: return@forEach
            when (l.status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    // The download service may have renamed the file on a clash; keep the real name and size.
                    val real = l.localUri?.let { Uri.parse(it).lastPathSegment }?.takeIf { it.isNotBlank() } ?: e.name
                    if (real != e.name || (l.total > 0 && l.total != e.size)) changeEntry(e.id) { it.copy(name = real, size = if (l.total > 0) l.total else it.size) }
                    if (watching.remove(e.dmId)) host.downloaded(e.copy(name = real))
                }
                DownloadManager.STATUS_FAILED -> if (watching.remove(e.dmId)) host.note("Download failed: ${e.name}")
                STATUS_GONE -> watching.remove(e.dmId)
                else -> { active = true; watching += e.dmId }
            }
        }
        return active
    }

    private fun stateOf(e: Downloads.Entry): State {
        if (e.dmId < 0) return State.DONE
        return when (live[e.dmId]?.status) {
            DownloadManager.STATUS_SUCCESSFUL -> State.DONE
            DownloadManager.STATUS_FAILED -> State.FAILED
            STATUS_GONE -> State.GONE
            else -> State.RUNNING
        }
    }

    // ---- Open / Remove / Delete ----

    private fun fileProviderUri(f: File): Uri? = runCatching {
        androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.files", f)
    }.getOrNull()

    /** content: URI another app (or the player) can be granted; null if the file is gone. */
    private fun uriFor(e: Downloads.Entry): Uri? {
        e.uri?.let { u ->
            if (!u.startsWith("file:")) return Uri.parse(u)
            val f = File(Uri.parse(u).path ?: return null)
            return if (f.exists()) fileProviderUri(f) else null
        }
        if (e.dmId < 0 || stateOf(e) != State.DONE) return null
        if (Build.VERSION.SDK_INT < 29) {
            // Android 7–9: the file is in mkTV's own folder, shared through mkTV's FileProvider.
            live[e.dmId]?.localUri?.let { Uri.parse(it).path }?.let { File(it) }?.takeIf { it.exists() }?.let { return fileProviderUri(it) }
        }
        return runCatching { dm?.getUriForDownloadedFile(e.dmId) }.getOrNull()
    }

    fun open(e: Downloads.Entry, chooser: Boolean = false) {
        val uri = uriFor(e) ?: return host.note("${e.name} is no longer on the TV.")
        val mime = Downloads.mimeFor(e.name, e.mime)
        when (Downloads.kindOf(e.name, mime)) {
            Downloads.Kind.APK -> install(uri)
            Downloads.Kind.VIDEO, Downloads.Kind.AUDIO ->
                if (chooser) view(uri, mime, chooser = true)
                else activity.startActivity(Intent(activity, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_URL, uri.toString())
                    .putExtra(PlayerActivity.EXTRA_TITLE, e.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            else -> view(uri, mime, chooser)
        }
    }

    /** No resolveActivity check: package visibility (API 30+) would hide most viewers. */
    private fun view(uri: Uri, mime: String, chooser: Boolean) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(if (chooser) Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else intent)
        } catch (e: ActivityNotFoundException) {
            host.note("No app on this TV opens this kind of file.")
        } catch (e: SecurityException) {
            host.note("Android didn't let another app open this file.")
        }
    }

    /** Like the in-app update install, minus the "signed like mkTV" check: any app's APK is fine here. */
    private fun install(uri: Uri) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            host.note("Allow mkTV to install apps, then press Open again")
            try {
                activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            } catch (e: ActivityNotFoundException) {
                host.note("Allow mkTV in Settings → Apps → Security & restrictions → Unknown sources, then press Open again")
            }
            return
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            host.note("This TV has no app installer")
        }
    }

    /** Deletes the file (or cancels a running download) and its row. */
    fun delete(e: Downloads.Entry) {
        if (e.dmId >= 0) runCatching { dm?.remove(e.dmId) } // removes the file too
        e.uri?.let { u ->
            runCatching { if (u.startsWith("file:")) File(Uri.parse(u).path!!).delete() else activity.contentResolver.delete(Uri.parse(u), null, null) }
        }
        watching -= e.dmId
        removeEntry(e.id)
    }

    /** Drops the row; the file stays. Failed or vanished downloads also leave the system's list (no file to keep). */
    fun removeFromList(e: Downloads.Entry) {
        val st = stateOf(e)
        if (e.dmId >= 0 && (st == State.FAILED || st == State.GONE)) runCatching { dm?.remove(e.dmId) }
        watching -= e.dmId
        removeEntry(e.id)
    }

    // ---- Panel ----

    private val content: LinearLayout by lazy { panel.findViewById(R.id.downloadsContent) }
    private val clearBtn: TextView by lazy { panel.findViewById(R.id.downloadsClear) }
    /** Row state when last drawn: a change of state (buttons differ) redraws, otherwise text updates in place. */
    private var drawn: List<Pair<Long, State>> = emptyList()
    private var confirmClear = false
    private var confirmDelete: Long? = null

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun onPanelShown() {
        confirmClear = false; confirmDelete = null
        clearBtn.setOnClickListener {
            if (!confirmClear) { confirmClear = true; clearBtn.text = "Press again to clear"; return@setOnClickListener }
            confirmClear = false
            entries().filter { stateOf(it) != State.RUNNING }.forEach { removeFromList(it) }
            renderPanel()
            firstView?.requestFocus()
        }
        renderPanel()
        kick()
    }

    private var firstView: View? = null

    /** First row (or the empty-state card) for initial focus. */
    fun firstFocus(): View? = firstView

    fun renderPanel() {
        refresh()
        val focusTag = activity.currentFocus?.tag as? String
        val focusId = focusTag?.takeIf { it.startsWith("dl:") }?.split(':')?.getOrNull(1)?.toLongOrNull()
        val oldIndex = drawn.indexOfFirst { it.first == focusId }
        content.removeAllViews()
        val list = entries()
        drawn = list.map { it.id to stateOf(it) }
        clearBtn.text = if (confirmClear) "Press again to clear" else "Clear list"
        clearBtn.visibility = if (list.any { stateOf(it) != State.RUNNING }) View.VISIBLE else View.GONE
        content.addView(TextView(activity).apply {
            text = "$whereText Remove keeps the file; Delete removes it from the TV."
            setTextColor(0xFF9CA3AF.toInt()); textSize = 13f
            setPadding(dp(4), dp(4), dp(4), dp(12))
        })
        if (list.isEmpty()) {
            content.addView(TextView(activity).apply {
                text = "No downloads yet. Files you download from websites appear here."
                setTextColor(0xFF9CA3AF.toInt()); textSize = 16f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(24), dp(24), dp(24), dp(24))
                isFocusable = true // so focus has somewhere to be and Back still closes
                id = View.generateViewId()
                tag = EMPTY_TAG
                nextFocusUpId = id; nextFocusDownId = id; nextFocusLeftId = id; nextFocusRightId = id
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            firstView = content.findViewWithTag(EMPTY_TAG)
            if (focusTag == null || focusTag.startsWith("dl")) firstView?.requestFocus()
            return
        }
        val rows = list.map { e -> row(e).also { content.addView(it.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }) }.second }
        // ▲▼ keep the same column (row, then its buttons), ◀▶ stay in the row; the ends stop instead of escaping to the toolbar.
        rows.forEachIndexed { i, items ->
            items.forEachIndexed { k, v ->
                v.nextFocusLeftId = if (k == 0) v.id else items[k - 1].id
                v.nextFocusRightId = if (k == items.size - 1) v.id else items[k + 1].id
                v.nextFocusUpId = if (i == 0) (if (clearBtn.visibility == View.VISIBLE) clearBtn.id else v.id) else rows[i - 1][minOf(k, rows[i - 1].size - 1)].id
                v.nextFocusDownId = if (i == rows.size - 1) v.id else rows[i + 1][minOf(k, rows[i + 1].size - 1)].id
            }
        }
        clearBtn.nextFocusDownId = rows[0][0].id
        clearBtn.nextFocusUpId = clearBtn.id; clearBtn.nextFocusLeftId = clearBtn.id; clearBtn.nextFocusRightId = clearBtn.id
        // Keep focus on the same row and button after a redraw (or the row that took its place).
        firstView = rows[0][0]
        if (focusId != null) {
            val k = focusTag.split(':').getOrNull(2)?.toIntOrNull() ?: 0
            val idx = list.indexOfFirst { it.id == focusId }
            if (idx >= 0) (rows[idx].getOrNull(k) ?: rows[idx][0]).requestFocus()
            else rows[oldIndex.coerceIn(0, rows.size - 1)][0].requestFocus() // its row went: the one that took its place
        } else if (focusTag == EMPTY_TAG) rows[0][0].requestFocus()
    }

    /** Progress and status text change in place so focus and scrolling don't jump. */
    private fun updatePanel() {
        val list = entries()
        if (list.map { it.id to stateOf(it) } != drawn) { renderPanel(); return }
        list.forEach { e ->
            content.findViewWithTag<TextView>("dlStatus:${e.id}")?.text = statusText(e)
            content.findViewWithTag<ProgressBar>("dlBar:${e.id}")?.let { bar -> paintBar(bar, e) }
        }
    }

    private fun paintBar(bar: ProgressBar, e: Downloads.Entry) {
        val l = live[e.dmId]
        val total = l?.total?.takeIf { it > 0 } ?: e.size
        bar.isIndeterminate = total <= 0 || l == null || l.status == DownloadManager.STATUS_PENDING
        if (!bar.isIndeterminate) bar.progress = ((l!!.done.coerceAtLeast(0) * 1000) / total).toInt().coerceIn(0, 1000)
    }

    private fun statusText(e: Downloads.Entry): String {
        val parts = mutableListOf<String>()
        if (e.private) parts += "Private"
        val l = live[e.dmId]
        when (stateOf(e)) {
            State.RUNNING -> {
                val total = l?.total?.takeIf { it > 0 } ?: e.size
                when (l?.status) {
                    DownloadManager.STATUS_PAUSED -> parts += "Paused" + when (l.reason) {
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> ", waiting for the network"
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> ", waiting for Wi-Fi"
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> ", will retry"
                        else -> ""
                    }
                    DownloadManager.STATUS_RUNNING -> {
                        if (total > 0) parts += "${(l.done.coerceAtLeast(0) * 100 / total).coerceIn(0, 100)}%"
                        Downloads.progressText(l.done, total).takeIf { it.isNotEmpty() }?.let { parts += it }
                        if (parts.isEmpty() || (parts.size == 1 && e.private)) parts += "Downloading…"
                    }
                    else -> parts += "Waiting to start…"
                }
            }
            State.DONE -> {
                Downloads.formatSize(l?.total?.takeIf { it > 0 } ?: e.size).takeIf { it.isNotEmpty() }?.let { parts += it }
                if (e.host.isNotBlank()) parts += e.host
                parts += History.dayLabel(e.time, System.currentTimeMillis()) + " " + android.text.format.DateFormat.getTimeFormat(activity).format(java.util.Date(e.time))
            }
            State.FAILED -> {
                parts += "Failed" + when (val r = l?.reason ?: 0) {
                    DownloadManager.ERROR_INSUFFICIENT_SPACE -> ": not enough space on the TV"
                    DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> ": the connection dropped"
                    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> ": too many redirects"
                    in 400..599 -> ": the site answered $r"
                    else -> ""
                }
                if (e.host.isNotBlank()) parts += e.host
            }
            State.GONE -> { parts += "Deleted from the TV"; if (e.host.isNotBlank()) parts += e.host }
        }
        return parts.joinToString(" · ")
    }

    private fun button(label: String, danger: Boolean = false, onClick: (TextView) -> Unit) = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(if (danger) 0xFFFCA5A5.toInt() else 0xFFD1D5DB.toInt()); textSize = 14f
        setBackgroundResource(if (danger) R.drawable.btn_danger else R.drawable.toolbar_button)
        setPadding(dp(14), 0, dp(14), 0)
        id = View.generateViewId()
        isClickable = true; isFocusable = true
        setOnClickListener { onClick(this) }
    }

    /** One download: the row itself opens it, then its buttons. Returns the row and its focusable parts, in order. */
    private fun row(e: Downloads.Entry): Pair<View, List<View>> {
        val state = stateOf(e)
        val kind = Downloads.kindOf(e.name, e.mime)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundResource(R.drawable.row_bg)
            id = View.generateViewId()
            isClickable = true; isFocusable = true
            tag = "dl:${e.id}:0"
            contentDescription = e.name
            setOnClickListener {
                when (state) {
                    State.DONE -> open(e)
                    State.RUNNING -> host.note("Still downloading. Open it when it's done.")
                    State.FAILED -> host.note("This download failed. Try it again from the website.")
                    State.GONE -> host.note("This file was deleted from the TV.")
                }
            }
        }
        row.addView(ImageView(activity).apply {
            setImageResource(when (kind) { Downloads.Kind.VIDEO, Downloads.Kind.AUDIO -> R.drawable.ic_play; Downloads.Kind.APK -> R.drawable.ic_update; else -> R.drawable.ic_download })
            alpha = if (state == State.DONE || state == State.RUNNING) 1f else 0.5f
        }, LinearLayout.LayoutParams(dp(26), dp(26)))
        val text = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(8), 0)
            addView(TextView(activity).apply {
                this.text = e.name; setTextColor(android.graphics.Color.WHITE); textSize = 17f
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            addView(TextView(activity).apply {
                this.text = statusText(e)
                setTextColor(if (state == State.FAILED) 0xFFFCA5A5.toInt() else if (e.private) 0xFFC4B5FD.toInt() else 0xFF9CA3AF.toInt())
                textSize = 13f; setPadding(0, dp(2), 0, 0)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                tag = "dlStatus:${e.id}"
            })
            if (state == State.RUNNING) addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF818CF8.toInt())
                indeterminateTintList = progressTintList
                tag = "dlBar:${e.id}"
                paintBar(this, e)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val buttons = mutableListOf<TextView>()
        when (state) {
            State.RUNNING -> buttons += button("Cancel") { delete(e); renderPanel() }
            State.DONE -> {
                if (kind == Downloads.Kind.VIDEO || kind == Downloads.Kind.AUDIO) buttons += button("Open with…") { open(e, chooser = true) }
                buttons += button("Remove") { removeFromList(e); renderPanel() }
                buttons += button(if (confirmDelete == e.id) "Press again to delete" else "Delete", danger = true) { b ->
                    if (confirmDelete != e.id) { confirmDelete = e.id; b.text = "Press again to delete"; return@button }
                    confirmDelete = null
                    delete(e); renderPanel()
                    host.note("Deleted ${e.name}")
                }
            }
            State.FAILED, State.GONE -> buttons += button("Remove") { removeFromList(e); renderPanel() }
        }
        buttons.forEachIndexed { k, b ->
            b.tag = "dl:${e.id}:${k + 1}"
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginStart = dp(8) })
        }
        return row to (listOf<View>(row) + buttons)
    }

    companion object {
        /** Our own marker for "no longer in DownloadManager" (deleted elsewhere or cleared). */
        private const val STATUS_GONE = -1
        /** Characters of the base64 data: URL copied per evaluateJavascript call. */
        private const val CHUNK = 1_000_000
        private const val EMPTY_TAG = "dlEmpty"
    }
}
