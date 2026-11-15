package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsTest {

    @Test fun dispositionQuotedPlainAndExtended() {
        assertEquals("report.pdf", Downloads.fileNameFromDisposition("attachment; filename=\"report.pdf\""))
        assertEquals("report.pdf", Downloads.fileNameFromDisposition("attachment; filename=report.pdf"))
        assertEquals("a;b.txt", Downloads.fileNameFromDisposition("attachment; filename=\"a;b.txt\"; size=3"))
        assertEquals("say \"hi\".txt", Downloads.fileNameFromDisposition("attachment; filename=\"say \\\"hi\\\".txt\""))
        // filename* wins and is percent-decoded
        assertEquals("€ rates.pdf", Downloads.fileNameFromDisposition("attachment; filename=\"EUR rates.pdf\"; filename*=UTF-8''%E2%82%AC%20rates.pdf"))
        assertEquals("naïve.txt", Downloads.fileNameFromDisposition("attachment; FILENAME*=utf-8'en'na%C3%AFve.txt"))
        assertEquals("x.bin", Downloads.fileNameFromDisposition("inline;filename=x.bin"))
    }

    @Test fun dispositionWithoutName() {
        assertNull(Downloads.fileNameFromDisposition(null))
        assertNull(Downloads.fileNameFromDisposition(""))
        assertNull(Downloads.fileNameFromDisposition("attachment"))
        assertNull(Downloads.fileNameFromDisposition("attachment; filename=\"\""))
    }

    @Test fun sanitizeRemovesPathsAndReservedCharacters() {
        assertEquals("_.._etc_passwd", Downloads.sanitize("/../etc/passwd"))
        assertEquals("a_b_c_.txt", Downloads.sanitize("a:b*c?.txt"))
        assertEquals("hidden", Downloads.sanitize(".hidden"))
        assertEquals("tab_name", Downloads.sanitize("tab\tname"))
        assertEquals("download", Downloads.sanitize(""))
        assertEquals("download", Downloads.sanitize(null))
        assertEquals("download", Downloads.sanitize("..."))
        assertEquals("file", Downloads.sanitize("file. "))
    }

    @Test fun sanitizeCapsLengthButKeepsExtension() {
        val long = "a".repeat(300) + ".mp4"
        val s = Downloads.sanitize(long)
        assertEquals(120, s.length)
        assertTrue(s.endsWith(".mp4"))
    }

    @Test fun fileNameFallsBackToUrlThenMime() {
        assertEquals("movie.mp4", Downloads.fileName("https://cdn.example.com/v/movie.mp4?sig=1", null, "video/mp4"))
        assertEquals("my file.zip", Downloads.fileName("https://example.com/my%20file.zip", "", null))
        assertEquals("download.pdf", Downloads.fileName("https://example.com/", null, "application/pdf"))
        assertEquals("getfile.apk", Downloads.fileName("https://example.com/getfile", null, "application/vnd.android.package-archive"))
        assertEquals("download.png", Downloads.fileName("blob:https://example.com/1234-5678", null, "image/png"))
        assertEquals("cd.txt", Downloads.fileName("https://example.com/x.bin", "attachment; filename=cd.txt", "text/plain"))
        assertEquals("download", Downloads.fileName("data:,hello", null, "application/x-unknown"))
    }

    @Test fun mimeAndKind() {
        assertEquals("video/mp4", Downloads.mimeFor("a.mp4", "application/octet-stream"))
        assertEquals("image/png", Downloads.mimeFor("a.jpg", "image/png; charset=binary"))
        assertEquals("application/octet-stream", Downloads.mimeFor("a.xyz", null))
        assertEquals(Downloads.Kind.APK, Downloads.kindOf("app.apk", "application/octet-stream"))
        assertEquals(Downloads.Kind.VIDEO, Downloads.kindOf("a.mkv", null))
        assertEquals(Downloads.Kind.AUDIO, Downloads.kindOf("a", "audio/mpeg"))
        assertEquals(Downloads.Kind.IMAGE, Downloads.kindOf("a.webp", ""))
        assertEquals(Downloads.Kind.PDF, Downloads.kindOf("a.pdf", null))
        assertEquals(Downloads.Kind.OTHER, Downloads.kindOf("a.zip", null))
    }

    @Test fun uniqueNames() {
        val taken = setOf("a.txt", "a (2).txt", "b")
        assertEquals("c.txt", Downloads.uniqueName("c.txt") { it in taken })
        assertEquals("a (3).txt", Downloads.uniqueName("a.txt") { it in taken })
        assertEquals("b (2)", Downloads.uniqueName("b") { it in taken })
    }

    @Test fun sizes() {
        assertEquals("", Downloads.formatSize(-1))
        assertEquals("0 B", Downloads.formatSize(0))
        assertEquals("950 B", Downloads.formatSize(950))
        assertEquals("1 KB", Downloads.formatSize(1024))
        assertEquals("1.5 KB", Downloads.formatSize(1536))
        assertEquals("34 MB", Downloads.formatSize(34L * 1024 * 1024 + 100))
        assertEquals("1.2 GB", Downloads.formatSize((1.2 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1 MB of 4 MB", Downloads.progressText(1L shl 20, 4L shl 20))
        assertEquals("2 KB", Downloads.progressText(2048, -1))
        assertEquals("", Downloads.progressText(0, -1))
    }

    @Test fun dataUrls() {
        val a = Downloads.parseDataUrl("data:image/png;base64,iVBORw0KGgo=")!!
        assertEquals("image/png", a.mime); assertTrue(a.base64); assertEquals("iVBORw0KGgo=", a.payload)
        assertEquals(9L, Downloads.decodedSize(a)) // rough: padding isn't subtracted
        val b = Downloads.parseDataUrl("data:,Hello%2C%20World")!!
        assertEquals("text/plain", b.mime); assertFalse(b.base64)
        assertEquals("Hello, World", Downloads.percentDecode(b.payload))
        val c = Downloads.parseDataUrl("data:text/csv;charset=utf-8,a,b")!!
        assertEquals("text/csv", c.mime); assertEquals("a,b", c.payload)
        assertNull(Downloads.parseDataUrl("https://example.com"))
        assertNull(Downloads.parseDataUrl("data:nocomma"))
    }

    @Test fun percentDecodeKeepsPlusAndBadEscapes() {
        assertEquals("a+b", Downloads.percentDecode("a+b"))
        assertEquals("100%", Downloads.percentDecode("100%"))
        assertEquals("%zz", Downloads.percentDecode("%zz"))
    }

    @Test fun listJsonSkipsPrivateEntries() {
        val a = Downloads.Entry(1, "a.mp4", "video/mp4", "example.com", 10, dmId = 5, size = 100)
        val b = Downloads.Entry(2, "b.png", "image/png", "example.org", 20, uri = "content://x/1")
        val p = Downloads.Entry(3, "secret.pdf", "application/pdf", "private.example", 30, private = true)
        var list = Downloads.add(emptyList(), a)
        list = Downloads.add(list, b)
        list = Downloads.add(list, p)
        assertEquals(listOf(3L, 2L, 1L), list.map { it.id })
        val back = Downloads.fromJson(Downloads.toJson(list))
        assertEquals(listOf(b, a), back)
        assertEquals(listOf(1L), Downloads.remove(listOf(a, b), 2).map { it.id })
        assertEquals("c.mp4", Downloads.update(listOf(a, b), 1) { it.copy(name = "c.mp4") }[0].name)
        assertEquals(emptyList<Downloads.Entry>(), Downloads.fromJson("not json"))
    }

    @Test fun listIsCapped() {
        var list = emptyList<Downloads.Entry>()
        for (i in 1..10L) list = Downloads.add(list, Downloads.Entry(i, "f$i", "", "", i), max = 4)
        assertEquals(listOf(10L, 9L, 8L, 7L), list.map { it.id })
    }
}
