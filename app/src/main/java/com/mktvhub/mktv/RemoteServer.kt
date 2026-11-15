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
        /** Live typing: press Backspace [deletes] times (or clear the field), then type [text]. */
        fun onText(text: String, deletes: Int, clear: Boolean, enter: Boolean)
        fun onOpen(url: String)
        /** Touchpad drag in pointer mode, in phone CSS px. */
        fun onMove(dx: Double, dy: Double)
        fun onMedia(action: String, value: Double)
        fun state(): JSONObject
        fun home(): JSONObject
        /** The TV's saved favicon (PNG) for [host], already validated by [RemoteInput.iconHost]. */
        fun icon(host: String): ByteArray?
        /** First request from a device at [address] since the code was last made; once per device. */
        fun onPhoneConnected(address: String)
    }

    /** Kept across restarts, so a paired phone's remote page keeps working when mkTV comes back. 10-character codes from 1.1.0 stay valid. */
    @Volatile var token: String = savedToken()?.takeIf { (it.length == TOKEN_LENGTH || it.length == 10) && it.all { c -> c in ALPHABET } } ?: newToken()
        private set

    private fun newToken() = SecureRandom().let { r -> (1..TOKEN_LENGTH).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("") }.also(saveToken)

    /** Unpairs every phone: old links and bookmarks stop working; the next phone scans the new QR code. */
    fun resetToken() {
        token = newToken()
        devices.clear()
        blocked.clear()
    }
    var port = PORT
        private set
    private var server: ServerSocket? = null
    /** A device that used the remote with the current code (one per network address). */
    class Device(val address: String, @Volatile var label: String, @Volatile var lastSeen: Long)

    private val devices = java.util.concurrent.ConcurrentHashMap<String, Device>()
    /** Disconnected addresses: refused until the next New code. */
    private val blocked = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Most recently active first. */
    fun devices(): List<Device> = devices.values.sortedByDescending { it.lastSeen }

    /** Every phone shares the one code, so a single phone is shut out by its address until a New code is made. */
    fun disconnect(address: String) {
        blocked += address
        devices.remove(address)
    }
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
        // Phone browsers often try https:// first (Chrome's HTTPS-Upgrades / "Always use secure
        // connections"). This server only speaks plain http on the LAN, so answer a TLS handshake
        // at once with a fatal TLS alert: the browser sees a clean TLS failure and falls back to
        // http:// instead of hanging until our read timeout and showing "can't be reached".
        input.mark(1)
        if (input.read() == TLS_HANDSHAKE) {
            runCatching { sock.getOutputStream().apply { write(TLS_ALERT_HANDSHAKE_FAILURE); flush() } }
            return
        }
        input.reset()
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

        val ip = sock.inetAddress?.hostAddress
        val authed = ip !in blocked && (sameToken(query["t"]) || sameToken(headers["x-token"]))
        val out = sock.getOutputStream()
        fun respond(code: Int, type: String, bytes: ByteArray) {
            val status = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 415 -> "Unsupported Media Type"; else -> "Error" }
            out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(bytes)
            out.flush()
        }
        fun respond(code: Int, type: String, text: String) = respond(code, "$type; charset=utf-8", text.toByteArray(Charsets.UTF_8))
        val ok = "{\"ok\":true}"
        if (!authed) {
            respond(403, "text/html", "<h2 style='font-family:sans-serif'>Scan the QR code shown on your TV (⋮ → Phone remote).</h2>")
            return
        }
        if (ip != null) {
            val label = RemoteInput.deviceLabel(headers["user-agent"])
            val now = System.currentTimeMillis()
            val known = devices.putIfAbsent(ip, Device(ip, label, now))
            if (known == null) listener.onPhoneConnected(ip) else { known.lastSeen = now; known.label = label }
        }
        // JSON-only POSTs: a cross-site HTML form can't send this content type without a CORS preflight.
        if (method == "POST" && headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
            respond(415, "application/json", "{\"ok\":false}")
            return
        }
        val json = runCatching { if (body.isNotBlank()) JSONObject(body) else JSONObject() }.getOrDefault(JSONObject())
        when {
            method == "GET" && (path == "/" || path == "/index.html") -> respond(200, "text/html", page)
            method == "GET" && path == "/api/state" -> respond(200, "application/json", listener.state().toString())
            method == "GET" && path == "/api/home" -> respond(200, "application/json", listener.home().toString())
            method == "GET" && path == "/api/icon" -> {
                val png = RemoteInput.iconHost(query["h"])?.let { listener.icon(it) }
                if (png == null) respond(404, "application/json", "{\"ok\":false}") else respond(200, "image/png", png)
            }
            method == "POST" && path == "/api/key" -> {
                val key = RemoteInput.key(json.optString("key"))
                if (key == null) respond(400, "application/json", "{\"ok\":false}")
                else { listener.onKey(key, RemoteInput.action(json.optString("action"))); respond(200, "application/json", ok) }
            }
            method == "POST" && path == "/api/text" -> {
                listener.onText(RemoteInput.text(json.optString("text")), RemoteInput.deletes(json.optInt("del")),
                    json.optBoolean("clear"), json.optBoolean("enter"))
                respond(200, "application/json", ok)
            }
            method == "POST" && path == "/api/open" -> {
                val url = json.optString("url").trim()
                if (url.isEmpty() || url.length > RemoteInput.MAX_URL) respond(400, "application/json", "{\"ok\":false}")
                else { listener.onOpen(url); respond(200, "application/json", ok) }
            }
            method == "POST" && path == "/api/move" -> {
                listener.onMove(RemoteInput.move(json.optDouble("dx", 0.0)), RemoteInput.move(json.optDouble("dy", 0.0)))
                respond(200, "application/json", ok)
            }
            method == "POST" && path == "/api/media" -> {
                val action = json.optString("action")
                // A missing "value" is NaN, so speed / sleep without one is rejected rather than read as 0.
                val value = RemoteInput.mediaValue(action, json.optDouble("to", 0.0), json.optDouble("value", Double.NaN))
                if (value == null) respond(400, "application/json", "{\"ok\":false}")
                else { listener.onMedia(action, value); respond(200, "application/json", ok) }
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
        /** First byte of every TLS record carrying a handshake (a ClientHello). */
        private const val TLS_HANDSHAKE = 0x16
        /** TLS 1.0 alert record: fatal (2), handshake_failure (40). */
        private val TLS_ALERT_HANDSHAKE_FAILURE = byteArrayOf(0x15, 0x03, 0x01, 0x00, 0x02, 0x02, 0x28)
        private val TUNNEL_PREFIXES = listOf("tun", "ppp", "wg", "ipsec", "vpn", "rmnet", "ccmni", "dummy")
        const val PORT = 8765
        private const val TAG = "RemoteServer"
        private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
        /** 26 characters from 31: about 128 bits. */
        private const val TOKEN_LENGTH = 26
    }
}
