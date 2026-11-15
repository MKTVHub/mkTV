package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputTest {
    @Test fun keys() {
        assertEquals("volup", RemoteInput.key("volup"))
        assertEquals("pagedown", RemoteInput.key("pagedown"))
        assertNull(RemoteInput.key("power"))
        assertNull(RemoteInput.key(null))
        assertEquals("press", RemoteInput.action("bogus"))
        assertEquals("down", RemoteInput.action("down"))
    }

    @Test fun textAndDeletes() {
        assertEquals(1000, RemoteInput.text("a".repeat(5000)).length)
        assertEquals("", RemoteInput.text(null))
        assertEquals(0, RemoteInput.deletes(-5))
        assertEquals(1000, RemoteInput.deletes(99_999))
    }

    @Test fun move() {
        assertEquals(12.5, RemoteInput.move(12.5), 0.0)
        assertEquals(400.0, RemoteInput.move(1e9), 0.0)
        assertEquals(-400.0, RemoteInput.move(-1e9), 0.0)
        assertEquals(0.0, RemoteInput.move(Double.NaN), 0.0)
        assertEquals(0.0, RemoteInput.move(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test fun seek() {
        assertEquals(90.0, RemoteInput.seekTo(90.0)!!, 0.0)
        assertNull(RemoteInput.seekTo(-1.0))
        assertNull(RemoteInput.seekTo(Double.NaN))
        assertNull(RemoteInput.seekTo(1e12))
    }

    @Test fun openable() {
        assertTrue(RemoteInput.openable("https://example.com/"))
        assertTrue(RemoteInput.openable("HTTP://example.com"))
        assertFalse(RemoteInput.openable("javascript:alert(1)"))
        assertFalse(RemoteInput.openable("file:///data/data"))
        assertFalse(RemoteInput.openable("intent://x#Intent;end"))
        assertFalse(RemoteInput.openable("data:text/html,hi"))
        assertFalse(RemoteInput.openable("http:example.com"))
        assertFalse(RemoteInput.openable("https://" + "a".repeat(3000)))
        assertFalse(RemoteInput.openable(null))
    }

    @Test fun iconHost() {
        assertEquals("youtube.com", RemoteInput.iconHost("www.YouTube.com"))
        assertEquals("music.youtube.com", RemoteInput.iconHost("music.youtube.com"))
        assertNull(RemoteInput.iconHost("../../shared_prefs/prefs.xml"))
        assertNull(RemoteInput.iconHost("a..b"))
        assertNull(RemoteInput.iconHost("a/b.com"))
        assertNull(RemoteInput.iconHost("localhost"))
        assertNull(RemoteInput.iconHost(null))
    }

    @Test fun tile() {
        val t = RemoteInput.tile("YouTube", "https://www.youtube.com/", "www.youtube.com", hasIcon = true)
        assertEquals("Y", t.getString("letter"))
        assertEquals("youtube.com", t.getString("icon"))
        assertTrue(t.getString("color").startsWith("#"))
        assertEquals("", RemoteInput.tile("x", "https://x.org", "x.org", hasIcon = false).getString("icon"))
    }
}
