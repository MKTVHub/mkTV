package com.mktvhub.mktv

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Host blocklist = bundled assets/adhosts.txt + the filter lists turned on in Settings (see [FilterLists]),
 * each downloaded at most once a day and cached in files/lists/<id>.txt. Minus the user's allowlist.
 * [hosts] and [allowed] are swapped atomically, so WebView's background threads can read them safely.
 */
class AdBlocker(private val context: Context, private val prefs: Prefs) {
    @Volatile var hosts: Set<String> = emptySet()
        private set
    @Volatile var allowed: Set<String> = prefs.allowlist
        private set
    /** True when the lists together went over [FilterLists.MAX_DOMAINS] and the rest was left out. */
    @Volatile var capped = false
        private set

    /** Per-list state shown in Settings. [count] is from the last parse of the cached copy. */
    data class Status(val count: Int = 0, val error: String? = null, val updating: Boolean = false)
    val status = ConcurrentHashMap<String, Status>()

    /** One worker: loads and updates never race, the newest build always wins. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "blocklist") }
    private val dir get() = File(context.filesDir, "lists").apply { mkdirs() }
    private var builtFrom: List<String>? = null
    /** Last automatic download attempt per list, so a dead URL or no network isn't retried on every resume. */
    private val attempted = ConcurrentHashMap<String, Long>()

    init {
        // Before filter lists the one downloaded list lived here; keep it as StevenBlack's copy instead of downloading again.
        val legacy = File(context.filesDir, "remote_hosts.txt")
        val adopt = prefs.migrateLegacyBlocklist()
        if (legacy.exists()) {
            val target = cacheFile("sb-unified")
            if (adopt && !target.exists()) legacy.renameTo(target) else legacy.delete()
        }
    }

    fun isBlocked(host: String?) = FilterLists.isBlocked(host, hosts, allowed)

    fun cacheFile(id: String) = File(dir, "$id.txt")

    /** When this list's copy was last saved, or 0 if never. */
    fun lastUpdated(id: String): Long = cacheFile(id).let { if (it.exists()) it.lastModified() else 0L }

    fun setAllowlist(v: Set<String>) { prefs.allowlist = v; allowed = v }

    /**
     * Rebuilds the set if the enabled lists changed, then downloads lists whose copy is over a day old
     * (all of them with [force], or just [only]). Failures keep the old copy. [onDone] runs on the worker thread.
     */
    fun load(force: Boolean = false, only: String? = null, onDone: (Int) -> Unit = {}) {
        val sources = FilterLists.sources(prefs.filterListsOn, prefs.customFilterLists)
        sources.filter { only == null || it.id == only }.filter { force || due(it.id) }
            .forEach { status[it.id] = (status[it.id] ?: Status()).copy(updating = true) }
        worker.execute {
            try {
                if (builtFrom != sources.map { it.id }) rebuild(sources)
                var changed = false
                for (s in sources) {
                    if (only != null && s.id != only) continue
                    if (!force && !due(s.id)) continue
                    attempted[s.id] = System.currentTimeMillis()
                    changed = update(s) || changed
                }
                if (changed) rebuild(sources)
            } catch (e: Throwable) {
                Log.w("AdBlocker", "Blocklist load failed", e)
            } finally {
                sources.forEach { s -> status[s.id]?.let { if (it.updating) status[s.id] = it.copy(updating = false) } }
            }
            Log.i("AdBlocker", "Blocklist ready: ${hosts.size} hosts")
            onDone(hosts.size)
        }
    }

    /** Deletes a removed list's copy (call after taking it out of Prefs). */
    fun forget(id: String) {
        status.remove(id)
        worker.execute { cacheFile(id).delete() }
    }

    /** Copy over a day old (or missing), and no automatic attempt in the last few hours. */
    private fun due(id: String): Boolean {
        val f = cacheFile(id)
        val now = System.currentTimeMillis()
        val stale = !f.exists() || now - f.lastModified() > FilterLists.REFRESH_MS
        return stale && now - (attempted[id] ?: 0L) > FilterLists.RETRY_MS
    }

    private fun rebuild(sources: List<FilterLists.Source>) {
        val set = HashSet<String>(if (hosts.size > 16) hosts.size * 2 else 1024)
        context.assets.open("adhosts.txt").bufferedReader().useLines { FilterLists.parse(it, set) }
        if (hosts.isEmpty()) hosts = HashSet(set) // usable right away; big lists take a few seconds to parse on TV CPUs
        for (s in sources) {
            val f = cacheFile(s.id)
            if (!f.exists()) continue
            try {
                val n = f.bufferedReader().useLines { FilterLists.parse(it, set) }
                status[s.id] = (status[s.id] ?: Status()).copy(count = n)
            } catch (e: Exception) {
                Log.w("AdBlocker", "Cached list ${s.id} unreadable", e)
            }
        }
        capped = set.size >= FilterLists.MAX_DOMAINS
        hosts = set
        builtFrom = sources.map { it.id }
    }

    /** Downloads [s] to a temp file and swaps it in only if it parses to at least one domain. */
    private fun update(s: FilterLists.Source): Boolean {
        val tmp = File(dir, "${s.id}.tmp")
        try {
            download(s.url, tmp)
            val n = tmp.bufferedReader().useLines { FilterLists.count(it) }
            if (n == 0) error("No domains found")
            if (!tmp.renameTo(cacheFile(s.id))) error("Couldn't save")
            status[s.id] = (status[s.id] ?: Status()).copy(count = n, error = null)
            return true
        } catch (e: Throwable) {
            Log.w("AdBlocker", "List ${s.id} download failed: ${e.message}")
            status[s.id] = (status[s.id] ?: Status()).copy(error = e.message ?: e.javaClass.simpleName)
            return false
        } finally {
            tmp.delete()
        }
    }

    private fun download(url: String, to: File, hops: Int = 0) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // HttpURLConnection doesn't follow http <-> https redirects by itself.
            if (conn.responseCode in 300..399 && hops < 5) {
                val next = conn.getHeaderField("Location")?.let { URL(URL(url), it).toString() }
                if (next != null && (next.startsWith("https://") || next.startsWith("http://"))) return download(next, to, hops + 1)
            }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > FilterLists.MAX_BYTES) error("List too big")
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
