package com.mktvhub.mktv

import java.util.Locale

/** Downloads: file names, Content-Disposition, sizes, data: URLs and the saved list. No Android deps. */
object Downloads {

    /**
     * One row of the Downloads panel. [dmId] is the system DownloadManager id (-1 for files mkTV wrote
     * itself from a data: or blob: URL, which have [uri] instead). [private] entries are never saved.
     */
    data class Entry(
        val id: Long,
        val name: String,
        val mime: String,
        val host: String,
        val time: Long,
        val dmId: Long = -1,
        val uri: String? = null,
        val size: Long = -1,
        val private: Boolean = false,
    )

    /** Rows kept in the saved list; the oldest finished ones drop off. */
    const val MAX = 100
    /** blob: and data: downloads are passed through memory, so keep them small. */
    const val INLINE_MAX_BYTES = 20L * 1024 * 1024

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f\\u007f]")
    private const val MAX_NAME = 120

    /** Safe for any file system: no path separators or reserved characters, no leading dots, length capped but extension kept. */
    fun sanitize(name: String?): String {
        var n = (name ?: "").replace(ILLEGAL, "_").trim().trimStart('.').trim()
        n = n.trimEnd('.', ' ')
        if (n.isEmpty() || n.all { it == '_' }) return "download"
        if (n.length > MAX_NAME) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 10) n.substring(dot) else ""
            n = n.substring(0, MAX_NAME - ext.length).trimEnd() + ext
        }
        return n
    }

    /** The file name in a Content-Disposition header: RFC 5987 `filename*` first, then `filename`. */
    fun fileNameFromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        val params = splitParams(cd)
        params["filename*"]?.let { v ->
            // charset'lang'percent-encoded
            val parts = v.split('\'', limit = 3)
            val cs = if (parts.size == 3) parts[0].ifBlank { "UTF-8" } else "UTF-8"
            val encoded = if (parts.size == 3) parts[2] else v
            val decoded = runCatching { percentDecode(encoded, java.nio.charset.Charset.forName(cs)) }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        return params["filename"]?.takeIf { it.isNotBlank() }
    }

    /** `attachment; filename="a;b.txt"; size=3` → lowercase keys, unquoted values. */
    private fun splitParams(cd: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = cd.indexOf(';')
        if (i < 0) return out
        i++
        while (i < cd.length) {
            while (i < cd.length && (cd[i] == ' ' || cd[i] == ';' || cd[i] == '\t')) i++
            val eq = cd.indexOf('=', i)
            if (eq < 0) break
            val key = cd.substring(i, eq).trim().lowercase(Locale.ROOT)
            i = eq + 1
            while (i < cd.length && cd[i] == ' ') i++
            val sb = StringBuilder()
            if (i < cd.length && cd[i] == '"') {
                i++
                while (i < cd.length && cd[i] != '"') {
                    if (cd[i] == '\\' && i + 1 < cd.length) i++
                    sb.append(cd[i]); i++
                }
                i++ // closing quote
                while (i < cd.length && cd[i] != ';') i++
            } else {
                while (i < cd.length && cd[i] != ';') { sb.append(cd[i]); i++ }
            }
            if (key.isNotEmpty() && key !in out) out[key] = sb.toString().trim()
        }
        return out
    }

    /** %XX decoding only (a '+' stays a '+', unlike form decoding). */
    fun percentDecode(s: String, charset: java.nio.charset.Charset = Charsets.UTF_8): String =
        String(percentDecodeBytes(s, charset), charset)

    /** Raw bytes of a percent-encoded string (plain characters are encoded with [charset]). */
    fun percentDecodeBytes(s: String, charset: java.nio.charset.Charset = Charsets.UTF_8): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { bytes.write(hex); i += 3; continue }
            }
            // Copy plain text up to the next '%' in one go so surrogate pairs stay intact.
            val next = s.indexOf('%', i + 1).let { if (it < 0) s.length else it }
            bytes.write(s.substring(i, next).toByteArray(charset))
            i = next
        }
        return bytes.toByteArray()
    }

    /** The last path segment of an http(s) URL, if it looks like a file name. */
    fun fileNameFromUrl(url: String?): String? {
        if (url == null || !(url.startsWith("http://", true) || url.startsWith("https://", true))) return null
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        if (last.isBlank()) return null
        return runCatching { percentDecode(last) }.getOrDefault(last).takeIf { it.isNotBlank() }
    }

    private val MIME_EXT = mapOf(
        "application/vnd.android.package-archive" to "apk",
        "application/pdf" to "pdf", "application/zip" to "zip", "application/json" to "json",
        "application/x-7z-compressed" to "7z", "application/x-rar-compressed" to "rar", "application/gzip" to "gz",
        "text/plain" to "txt", "text/html" to "html", "text/csv" to "csv", "text/calendar" to "ics",
        "image/jpeg" to "jpg", "image/png" to "png", "image/gif" to "gif", "image/webp" to "webp", "image/svg+xml" to "svg", "image/bmp" to "bmp",
        "video/mp4" to "mp4", "video/webm" to "webm", "video/x-matroska" to "mkv", "video/quicktime" to "mov", "video/mp2t" to "ts",
        "audio/mpeg" to "mp3", "audio/mp4" to "m4a", "audio/aac" to "aac", "audio/ogg" to "ogg", "audio/wav" to "wav", "audio/flac" to "flac",
        "application/x-subrip" to "srt", "text/vtt" to "vtt",
    )
    private val EXT_MIME = MIME_EXT.entries.associate { (m, e) -> e to m } + mapOf("jpeg" to "image/jpeg", "htm" to "text/html", "m4v" to "video/mp4")

    fun extensionFor(mime: String?): String? = MIME_EXT[baseMime(mime)]

    /** Best MIME type for [name]: [mime] unless it's the generic one and the extension says more. */
    fun mimeFor(name: String, mime: String?): String {
        val m = baseMime(mime)
        if (m.isNotEmpty() && m != "application/octet-stream" && m != "binary/octet-stream") return m
        return EXT_MIME[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: m.ifEmpty { "application/octet-stream" }
    }

    private fun baseMime(mime: String?) = (mime ?: "").substringBefore(';').trim().lowercase(Locale.ROOT)

    /**
     * File name for a download: Content-Disposition, then the URL, then "download"; sanitised, with an
     * extension from the MIME type when the name has none.
     */
    fun fileName(url: String?, contentDisposition: String?, mime: String?): String {
        val raw = fileNameFromDisposition(contentDisposition) ?: fileNameFromUrl(url)
        var name = sanitize(raw)
        val ext = extensionFor(mime)
        if (ext != null && !name.contains('.')) name = "$name.$ext"
        return name
    }

    /** "name.ext" → "name (2).ext" until [taken] is false. */
    fun uniqueName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (taken("$base ($i)$ext")) i++
        return "$base ($i)$ext"
    }

    /** 0 B, 950 B, 1.2 KB, 34 MB, 1.5 GB (1 KB = 1024 B); empty for unknown sizes. */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return ""
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        val text = if (v < 10) String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0") else Math.round(v).toString()
        return "$text ${units[u]}"
    }

    /** "12 MB of 40 MB", "12 MB" or "" depending on what's known. */
    fun progressText(done: Long, total: Long): String = when {
        total > 0 -> "${formatSize(done.coerceAtLeast(0))} of ${formatSize(total)}"
        done > 0 -> formatSize(done)
        else -> ""
    }

    /** What Open does with a file. */
    enum class Kind { APK, VIDEO, AUDIO, IMAGE, PDF, OTHER }

    fun kindOf(name: String, mime: String?): Kind {
        val m = mimeFor(name, mime)
        return when {
            m == "application/vnd.android.package-archive" || name.endsWith(".apk", true) -> Kind.APK
            m.startsWith("video/") -> Kind.VIDEO
            m.startsWith("audio/") -> Kind.AUDIO
            m.startsWith("image/") -> Kind.IMAGE
            m == "application/pdf" -> Kind.PDF
            else -> Kind.OTHER
        }
    }

    /** A data: URL split into its parts; decoding the payload is left to the caller (Base64 differs by API level). */
    data class DataUrl(val mime: String, val base64: Boolean, val payload: String)

    fun parseDataUrl(url: String): DataUrl? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma).split(';').map { it.trim() }
        val mime = meta.firstOrNull()?.takeIf { it.contains('/') }?.lowercase(Locale.ROOT) ?: "text/plain"
        val base64 = meta.drop(1).any { it.equals("base64", ignoreCase = true) }
        return DataUrl(mime, base64, url.substring(comma + 1))
    }

    /** Rough decoded size of a data: URL payload, to refuse huge ones before decoding. */
    fun decodedSize(d: DataUrl): Long =
        if (d.base64) d.payload.count { !it.isWhitespace() }.toLong() * 3 / 4 else d.payload.length.toLong()

    /** Newest first; the oldest rows beyond [max] are dropped. */
    fun add(list: List<Entry>, e: Entry, max: Int = MAX): List<Entry> = (listOf(e) + list.filterNot { it.id == e.id }).take(max)

    fun remove(list: List<Entry>, id: Long): List<Entry> = list.filterNot { it.id == id }

    fun update(list: List<Entry>, id: Long, change: (Entry) -> Entry): List<Entry> = list.map { if (it.id == id) change(it) else it }

    /** Private entries are never written. */
    fun toJson(list: List<Entry>): String {
        val arr = org.json.JSONArray()
        list.filterNot { it.private }.forEach {
            arr.put(org.json.JSONObject().put("id", it.id).put("n", it.name).put("m", it.mime).put("h", it.host)
                .put("at", it.time).put("dm", it.dmId).put("u", it.uri ?: "").put("s", it.size))
        }
        return arr.toString()
    }

    fun fromJson(json: String?): List<Entry> = runCatching {
        val arr = org.json.JSONArray(json ?: return emptyList())
        (0 until arr.length()).map {
            arr.getJSONObject(it).let { o ->
                Entry(o.getLong("id"), o.getString("n"), o.optString("m"), o.optString("h"), o.optLong("at"),
                    o.optLong("dm", -1), o.optString("u").ifEmpty { null }, o.optLong("s", -1))
            }
        }
    }.getOrDefault(emptyList())
}
