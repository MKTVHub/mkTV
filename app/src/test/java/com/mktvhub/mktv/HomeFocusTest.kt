package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeFocusTest {
    private val rec = listOf("rec:0|https://demo.example.dev/player/", "rec:1|https://example.org/", "rec:2|http://192.0.2.8:8099/")
    private val home = listOf("pop:0|https://www.youtube.com/", "fav:0|https://news.example.com/") + rec

    @Before fun clean() = SiteIcons.reset()

    @Test fun sameTileKeepsFocusForEveryKind() {
        home.forEach { assertEquals(it, HomeFocus.pick(it, home)) }
        assertEquals("favact:name", HomeFocus.pick("favact:name", home + "favact:name"))
        assertEquals("tool:fav:↑  Move up", HomeFocus.pick("tool:fav:↑  Move up", home + "tool:fav:↑  Move up"))
    }

    @Test fun movedSiteIsFollowedInItsRow() {
        // example.org went from second to first in Recently visited.
        val now = listOf("rec:0|https://example.org/", "rec:1|https://demo.example.dev/player/")
        assertEquals("rec:0|https://example.org/", HomeFocus.pick("rec:1|https://example.org/", now))
    }

    @Test fun removedTileGivesItsPlaceToTheNextOneNeverAnotherRow() {
        val now = listOf("pop:0|https://www.youtube.com/", "rec:0|https://demo.example.dev/player/", "rec:1|http://192.0.2.8:8099/")
        assertEquals("rec:1|http://192.0.2.8:8099/", HomeFocus.pick("rec:1|https://example.org/", now))
        assertEquals("rec:1|http://192.0.2.8:8099/", HomeFocus.pick("rec:4|https://gone.example/", now))
        // A favourite that went, with no favourites left: the caller picks the row's own button, not Recently visited.
        assertNull(HomeFocus.pick("fav:0|https://news.example.com/", now))
        assertNull(HomeFocus.pick(null, now))
        assertNull(HomeFocus.pick("favact:name", now))
    }

    @Test fun signatureChangesOnlyWhenTheRowLooksDifferent() {
        val items = listOf("Example Domain" to "https://example.org/", "Player demo" to "https://demo.example.dev/player/")
        val a = HomeFocus.signature("rec", items, listOf(false, true))
        assertEquals(a, HomeFocus.signature("rec", items.toList(), listOf(false, true)))
        assertNotEquals(a, HomeFocus.signature("rec", items, listOf(true, true))) // an icon arrived
        assertNotEquals(a, HomeFocus.signature("rec", items.reversed(), listOf(true, false)))
        assertNotEquals(a, HomeFocus.signature("rec", items, listOf(false, true), state = "editing"))
    }

    @Test fun iconKeysMatchTheSavedFileNames() {
        assertEquals("youtube.com", SiteIcons.key("WWW.YouTube.com"))
        assertEquals("192.0.2.8", SiteIcons.key("192.0.2.8"))
        assertNull(SiteIcons.key("localhost"))
        assertNull(SiteIcons.key(null))
    }

    @Test fun eachHostIsTriedOncePerRunWhateverTheResult() {
        val hosts = listOf("demo.example.dev", "example.org", "www.example.org", "192.0.2.8", "check.example.net", "example.com", "localhost")
        val saved = setOf("example.org")
        assertEquals(listOf("demo.example.dev", "192.0.2.8", "check.example.net", "example.com"), SiteIcons.claim(hosts) { it in saved })
        // Home shown again (or re-rendered after a fetch): failures and successes alike are not fetched again.
        assertEquals(emptyList<String>(), SiteIcons.claim(hosts) { it in saved })
        assertEquals(listOf("new.example"), SiteIcons.claim(hosts + "new.example") { it in saved })
    }

    @Test fun onlyAnIconForATileOnScreenRebuildsHome() {
        val shown = listOf("demo.example.dev", "example.org", null)
        assertFalse(SiteIcons.changesShown(emptyList(), shown))
        assertFalse(SiteIcons.changesShown(listOf("other.example"), shown))
        assertTrue(SiteIcons.changesShown(listOf("example.org"), listOf("www.example.org")))
    }
}
