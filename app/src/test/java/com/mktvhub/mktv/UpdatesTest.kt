package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun parsesVersions() {
        assertEquals(listOf(1, 0, 1), Updates.parseVersion("v1.0.1"))
        assertEquals(listOf(1, 2, 10), Updates.parseVersion("1.2.10-beta"))
        assertEquals(listOf(2), Updates.parseVersion("V2"))
        assertEquals(emptyList<Int>(), Updates.parseVersion("latest"))
    }

    @Test fun comparesNumerically() {
        assertTrue(Updates.isNewer("v1.0.2", "1.0.1"))
        assertTrue(Updates.isNewer("1.0.10", "1.0.9"))
        assertTrue(Updates.isNewer("1.1", "1.0.9"))
        assertTrue(Updates.isNewer("2.0.0", "1.99"))
        assertTrue(Updates.isNewer("1.0.1", "1.0"))
        assertFalse(Updates.isNewer("v1.0.1", "1.0.1"))
        assertFalse(Updates.isNewer("1.0", "1.0.0"))
        assertFalse(Updates.isNewer("1.0.0", "1.0.1"))
        assertFalse(Updates.isNewer("1.0.1", "1.0.1-debug"))
        assertFalse(Updates.isNewer("garbage", "1.0.1"))
    }

    @Test fun dueOncePerDay() {
        val day = Updates.CHECK_INTERVAL_MS
        assertTrue(Updates.due(0, 1_000_000_000_000))
        assertFalse(Updates.due(1_000, 1_000 + day - 1))
        assertTrue(Updates.due(1_000, 1_000 + day))
        assertTrue(Updates.due(5_000, 1_000)) // clock went backwards
    }

    private val release = """
        {"tag_name":"v1.0.2","name":"mkTV 1.0.2","draft":false,"prerelease":false,
         "assets":[
           {"name":"mkTV-1.0.2.apk","size":10,"browser_download_url":"https://github.com/MKTVHub/mkTV/releases/download/v1.0.2/mkTV-1.0.2.apk"},
           {"name":"mkTV.apk","size":4973601,"browser_download_url":"https://github.com/MKTVHub/mkTV/releases/download/v1.0.2/mkTV.apk"}
         ]}
    """.trimIndent()

    @Test fun parsesRelease() {
        val r = Updates.parseRelease(release)!!
        assertEquals("1.0.2", r.version)
        assertEquals("https://github.com/MKTVHub/mkTV/releases/download/v1.0.2/mkTV.apk", r.apkUrl)
        assertEquals(4973601L, r.size)
    }

    @Test fun rejectsUnusableReleases() {
        assertNull(Updates.parseRelease(release.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(Updates.parseRelease(release.replace("\"draft\":false", "\"draft\":true")))
        assertNull(Updates.parseRelease(release.replace("\"mkTV.apk\"", "\"other.apk\"")))
        assertNull(Updates.parseRelease(release.replace("v1.0.2\",\"name", "nightly\",\"name")))
        assertNull(Updates.parseRelease(release.replace("https://github.com/MKTVHub/mkTV/releases/download/v1.0.2/mkTV.apk", "http://example.com/mkTV.apk")))
        assertNull(Updates.parseRelease("""{"message":"API rate limit exceeded"}"""))
        assertNull(Updates.parseRelease("not json"))
    }
}
