package com.mktvhub.mktv

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Parental controls, pure logic (no Android deps): PIN hashing and checking, wrong-PIN lockout,
 * the unlock window, blocked-site matching and SafeSearch URL rewriting. The number pad is [PinPad].
 */
object Parental {
    const val PIN_LENGTH = 4
    /** Wrong PINs in a row before the pad makes you wait [LOCKOUT_MS]. */
    const val MAX_TRIES = 5
    const val LOCKOUT_MS = 30_000L
    /** A correct PIN keeps mkTV unlocked this long (or until it goes to the background). */
    const val UNLOCK_MS = 5 * 60_000L
    /** Modest on purpose: TV CPUs are slow, and a 4-digit PIN has only 10,000 values anyway. */
    const val ITERATIONS = 12_000

    // What the PIN can protect (Prefs.pinGuards). All on when a PIN is set.
    const val GUARD_SETTINGS = "settings"
    const val GUARD_PRIVATE = "private"
    const val GUARD_DOWNLOADS = "downloads"
    const val GUARD_REMOTE = "remote"
    val GUARDS = listOf(GUARD_SETTINGS, GUARD_PRIVATE, GUARD_DOWNLOADS, GUARD_REMOTE)

    // ---- PIN ----

    fun validPin(pin: String): Boolean = pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018) on top of Mac, which every Android version has:
     * SecretKeyFactory only offers "PBKDF2WithHmacSHA256" from Android 8, and mkTV runs on 7.
     */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int, bytes: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val out = ByteArray(bytes)
        var block = 1
        var pos = 0
        while (pos < bytes) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, bytes - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; block++
        }
        return out
    }

    /** What Prefs stores: "pbkdf2-sha256:<iterations>:<salt hex>:<hash hex>". Never the PIN itself. */
    fun record(pin: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): String =
        "pbkdf2-sha256:$iterations:${hex(salt)}:${hex(pbkdf2(pin, salt, iterations))}"

    /** True if [pin] matches [record]. A missing or damaged record matches nothing. */
    fun verify(pin: String, record: String?): Boolean {
        val parts = record?.split(':') ?: return false
        if (parts.size != 4 || parts[0] != "pbkdf2-sha256") return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return false
        val salt = unhex(parts[2]) ?: return false
        val want = unhex(parts[3]) ?: return false
        if (!validPin(pin) || want.isEmpty()) return false
        return MessageDigest.isEqual(pbkdf2(pin, salt, iterations, want.size), want) // constant time
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun unhex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
    }

    // ---- Wrong-PIN lockout and the unlock window ----

    /** Wrong tries so far and, after [MAX_TRIES], the wall-clock time until which the pad refuses input. */
    data class Lockout(val fails: Int = 0, val until: Long = 0)

    fun afterWrong(s: Lockout, now: Long): Lockout =
        if (s.fails + 1 >= MAX_TRIES) Lockout(0, now + LOCKOUT_MS) else Lockout(s.fails + 1, s.until)

    /** Milliseconds still to wait; never more than [LOCKOUT_MS], so a clock set backwards can't lock the pad for good. */
    fun lockedFor(s: Lockout, now: Long): Long = (s.until - now).coerceIn(0, LOCKOUT_MS)

    fun triesLeft(s: Lockout): Int = MAX_TRIES - s.fails

    /** [unlockedAt] and [now] come from a monotonic clock (elapsedRealtime); 0 means locked. */
    fun stillUnlocked(unlockedAt: Long, now: Long): Boolean = unlockedAt > 0 && now >= unlockedAt && now - unlockedAt < UNLOCK_MS

    // ---- Blocked sites ----

    /** "https://www.Example.com/x" → "example.com"; null if it isn't a domain. */
    fun normalizeSite(input: String): String? {
        val d = FilterLists.normalizeDomain(input) ?: return null
        return if (d.startsWith("www.") && d.indexOf('.', 4) > 0) d.removePrefix("www.") else d
    }

    /** True if [host] is a blocked site or one of its subdomains. */
    fun isBlocked(host: String?, sites: Set<String>): Boolean = HostRules.isBlocked(host, sites)

    /** The calm page shown instead of a blocked site (main frame only; other requests just get nothing). */
    fun blockedPage(host: String?): String {
        val h = (host ?: "This site").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Blocked by parental controls</title><style>
html,body{margin:0;height:100%;background:#101217;color:#E5E7EB;font-family:sans-serif}
body{display:flex;align-items:center;justify-content:center;text-align:center}
h1{font-size:34px;font-weight:600;margin:24px 0 12px}p{font-size:20px;color:#9CA3AF;margin:6px 0}
</style></head><body><div>
<svg width="72" height="72" viewBox="0 0 24 24"><path fill="#818CF8" d="M18,8h-1V6c0-2.76-2.24-5-5-5S7,3.24,7,6v2H6c-1.1,0-2,0.9-2,2v10c0,1.1,0.9,2,2,2h12c1.1,0,2-0.9,2-2V10C20,8.9,19.1,8,18,8zM12,17c-1.1,0-2-0.9-2-2s0.9-2,2-2s2,0.9,2,2S13.1,17,12,17zM15.1,8H8.9V6c0-1.71,1.39-3.1,3.1-3.1s3.1,1.39,3.1,3.1V8z"/></svg>
<h1>Blocked by parental controls</h1><p>$h is blocked on this TV.</p><p>Press Back to go back.</p>
</div></body></html>"""
    }

    // ---- SafeSearch ----

    private val GOOGLE_HOST = Regex("""^(www\.)?google\.([a-z]{2,3}|co\.[a-z]{2}|com\.[a-z]{2})$""")

    /**
     * Forces the engine's strict filter on a search address: Google safe=active, Bing adlt=strict,
     * DuckDuckGo kp=1. Replaces a weaker value already there; anything else comes back unchanged.
     * (YouTube's Restricted Mode needs a request header or cookie on every request, so it isn't covered.)
     */
    fun safeSearch(url: String): String {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return url
        val rest = url.substringAfter("://")
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.')
        val path = rest.substring(authority.length).takeWhile { it != '?' && it != '#' }.ifEmpty { "/" }
        return when {
            GOOGLE_HOST.matches(host) && path == "/search" -> withParam(url, "safe", "active")
            (host == "bing.com" || host.endsWith(".bing.com")) && path.endsWith("/search") -> withParam(url, "adlt", "strict")
            (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) && hasParam(url, "q") -> withParam(url, "kp", "1")
            else -> url
        }
    }

    private fun queryOf(url: String): String {
        val q = url.indexOf('?')
        val hash = url.indexOf('#')
        if (q < 0 || (hash in 0 until q)) return ""
        return if (hash < 0) url.substring(q + 1) else url.substring(q + 1, hash)
    }

    private fun hasParam(url: String, key: String) =
        queryOf(url).split('&').any { it.substringBefore('=') == key && it.substringAfter('=', "").isNotEmpty() }

    /** Sets [key]=[value] in the query, once, keeping the other parameters and the #fragment. */
    fun withParam(url: String, key: String, value: String): String {
        val hash = url.indexOf('#')
        val fragment = if (hash < 0) "" else url.substring(hash)
        val noFrag = if (hash < 0) url else url.substring(0, hash)
        val q = noFrag.indexOf('?')
        val base = if (q < 0) noFrag else noFrag.substring(0, q)
        val params = (if (q < 0) "" else noFrag.substring(q + 1)).split('&').filter { it.isNotEmpty() }
        val same = params.filter { it.substringBefore('=') == key }
        if (same.size == 1 && same[0] == "$key=$value") return url
        val kept = params.filter { it.substringBefore('=') != key } + "$key=$value"
        return base + "?" + kept.joinToString("&") + fragment
    }
}
