package com.mktvhub.mktv

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Host blocklist = bundled assets/adhosts.txt + an optional downloaded hosts file.
 * [hosts] is swapped atomically, so WebView's background threads can read it safely.
 */
class AdBlocker(private val context: Context) {
    @Volatile var hosts: Set<String> = emptySet()
        private set

    private val cache get() = File(context.filesDir, "remote_hosts.txt")

    fun isBlocked(host: String?) = HostRules.isBlocked(host, hosts)

    /** When the downloaded list was last saved, or 0 if never. */
    fun lastUpdated(): Long = if (cache.exists()) cache.lastModified() else 0L

    /** Loads local lists, then refreshes the remote list if it is older than a day (or [force]). */
    fun load(remoteUrl: String, force: Boolean = false, onDone: (Int) -> Unit = {}) = thread(name = "blocklist") {
        val bundled = context.assets.open("adhosts.txt").bufferedReader().use { HostRules.parseHosts(it.readText()) }
        hosts = bundled // usable right away; the big cached list takes a few seconds to parse on TV CPUs
        val cached = readCache()
        hosts = bundled + cached
        val stale = !cache.exists() || System.currentTimeMillis() - cache.lastModified() > 24 * 3600_000L
        if (remoteUrl.isNotBlank() && (force || stale)) {
            try {
                val text = download(remoteUrl)
                val remote = HostRules.parseHosts(text)
                if (remote.isNotEmpty()) {
                    cache.writeText(text)
                    hosts = bundled + remote
                }
            } catch (e: Exception) {
                Log.w("AdBlocker", "Blocklist download failed: ${e.message}")
            }
        } else if (remoteUrl.isBlank()) {
            hosts = bundled
        }
        Log.i("AdBlocker", "Blocklist ready: ${hosts.size} hosts")
        onDone(hosts.size)
    }

    private fun readCache(): Set<String> =
        if (cache.exists()) runCatching { HostRules.parseHosts(cache.readText()) }
            .onFailure { Log.w("AdBlocker", "Cached list unreadable", it) }.getOrDefault(emptySet()) else emptySet()

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
