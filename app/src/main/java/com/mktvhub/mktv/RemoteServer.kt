package com.mktvhub.mktv

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny LAN-only HTTP server that turns a phone browser into a remote.
 * Every API call must carry the pairing token (from the QR code), so other devices on the
 * network can't drive the TV just by guessing the port.
 */
class RemoteServer(private val context: Context, private val listener: Listener, private val savedToken: () -> String? = { null }, private val saveToken: (String) -> Unit = {}) {

    interface Listener {
        fun onKey(key: String, action: String)
        fun onText(text: String, enter: Boolean)
        fun onOpen(url: String)
        fun state(): JSONObject
        fun onPhoneConnected()
    }

    /** Kept across restarts, so a paired phone's remote page keeps working when mkTV comes back. */
    val token: String by lazy {
        savedToken()?.takeIf { it.length == 10 && it.all { c -> c in ALPHABET } }
            ?: SecureRandom().let { r -> (1..10).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("") }.also(saveToken)
    }
    var port = PORT
        private set
    private var server: ServerSocket? = null
    private var announced = false
    private val page by lazy { context.assets.open("remote.html").bufferedReader().use { it.readText() } }

    val running get() = server?.isClosed == false

    fun start() {
        if (running) return
        val s = (0 until 5).firstNotNullOfOrNull { i -> runCatching {
            // reuseAddress so coming back from the background gets the same port (and the QR code stays valid).
            ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(PORT + i)) }
        }.getOrNull() } ?: return
        server = s
        port = s.localPort
        thread(name = "remote-accept", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(name = "remote-conn", isDaemon = true) { runCatching { handle(client) }.onFailure { Log.w(TAG, "request failed", it) } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** This TV's private IPv4 address on Wi-Fi or Ethernet. A VPN's tunnel address (also private) is never used: phones can't reach it. */
    fun address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && !isTunnel(it.name) }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun isTunnel(name: String) = TUNNEL_PREFIXES.any { name.startsWith(it) }

    private fun sameToken(given: String?): Boolean =
        given != null && java.security.MessageDigest.isEqual(given.toByteArray(), token.toByteArray())

    fun url(): String? = address()?.let { "http://$it:$port/?t=$token" }

    private fun handle(socket: Socket) = socket.use { sock ->
        sock.soTimeout = 10_000
        val input = BufferedInputStream(sock.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val length = headers["content-length"]?.toIntOrNull()?.coerceAtMost(64 * 1024) ?: 0
        val body = if (length > 0) ByteArray(length).also { var read = 0; while (read < length) { val n = input.read(it, read, length - read); if (n < 0) break; read += n } }.toString(Charsets.UTF_8) else ""

        val authed = sameToken(query["t"]) || sameToken(headers["x-token"])
        val out = sock.getOutputStream()
        fun respond(code: Int, type: String, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
            out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        }
        if (!authed) {
            respond(403, "text/html", "<h2 style='font-family:sans-serif'>Scan the QR code shown on your TV (⋮ → Phone remote).</h2>")
            return
        }
        if (!announced) { announced = true; listener.onPhoneConnected() }
        val json = runCatching { if (body.isNotBlank()) JSONObject(body) else JSONObject() }.getOrDefault(JSONObject())
        when {
            method == "GET" && (path == "/" || path == "/index.html") -> respond(200, "text/html", page)
            method == "GET" && path == "/api/state" -> respond(200, "application/json", listener.state().toString())
            method == "POST" && path == "/api/key" -> {
                listener.onKey(json.optString("key"), json.optString("action", "press")); respond(200, "application/json", "{\"ok\":true}")
            }
            method == "POST" && path == "/api/text" -> {
                listener.onText(json.optString("text"), json.optBoolean("enter")); respond(200, "application/json", "{\"ok\":true}")
            }
            method == "POST" && path == "/api/open" -> {
                listener.onOpen(json.optString("url")); respond(200, "application/json", "{\"ok\":true}")
            }
            else -> respond(404, "application/json", "{\"ok\":false}")
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            if (buf.size() > 8192) return null
            buf.write(b)
        }
    }

    companion object {
        private val TUNNEL_PREFIXES = listOf("tun", "ppp", "wg", "ipsec", "vpn", "rmnet", "ccmni", "dummy")
        const val PORT = 8765
        private const val TAG = "RemoteServer"
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}
