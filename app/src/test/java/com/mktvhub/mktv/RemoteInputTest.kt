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

    @Test fun mediaActions() {
        val nan = Double.NaN
        assertEquals(93.0, RemoteInput.mediaValue("seek", 93.0, nan)!!, 0.0)
        assertNull(RemoteInput.mediaValue("seek", -5.0, nan))
        assertEquals(0.0, RemoteInput.mediaValue("fullscreen", 0.0, nan)!!, 0.0)
        assertEquals(0.0, RemoteInput.mediaValue("captions", 0.0, nan)!!, 0.0)
        assertNull(RemoteInput.mediaValue("eject", 0.0, 1.0))
        assertNull(RemoteInput.mediaValue(null, 0.0, 1.0))
        assertNull(RemoteInput.mediaValue("", 0.0, 1.0))
    }

    @Test fun mediaSpeedAllowlist() {
        listOf(0.75, 1.0, 1.25, 1.5, 2.0).forEach { assertEquals(it, RemoteInput.mediaValue("speed", 0.0, it)!!, 0.0) }
        assertNull(RemoteInput.mediaValue("speed", 0.0, Double.NaN)) // no value sent
        assertNull(RemoteInput.mediaValue("speed", 0.0, 1.1))
        assertNull(RemoteInput.mediaValue("speed", 0.0, 16.0))
        assertNull(RemoteInput.mediaValue("speed", 0.0, -1.0))
        assertNull(RemoteInput.mediaValue("speed", 0.0, 0.0))
        assertNull(RemoteInput.mediaValue("speed", 0.0, Double.POSITIVE_INFINITY))
    }

    @Test fun mediaSleepAllowlist() {
        listOf(0, 15, 30, 60, -1).forEach { assertEquals(it.toDouble(), RemoteInput.mediaValue("sleep", 0.0, it.toDouble())!!, 0.0) }
        assertNull(RemoteInput.mediaValue("sleep", 0.0, Double.NaN))
        assertNull(RemoteInput.mediaValue("sleep", 0.0, 45.0))
        assertNull(RemoteInput.mediaValue("sleep", 0.0, 15.5))
        assertNull(RemoteInput.mediaValue("sleep", 0.0, -2.0))
        assertNull(RemoteInput.mediaValue("sleep", 0.0, 1e12))
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

    @Test fun deviceLabels() {
        assertEquals("Pixel 8 Pro · Chrome", RemoteInput.deviceLabel("Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro Build/AP1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"))
        assertEquals("Android phone · Chrome", RemoteInput.deviceLabel("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"))
        assertEquals("iPhone · Safari", RemoteInput.deviceLabel("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"))
        assertEquals("Mac · Firefox", RemoteInput.deviceLabel("Mozilla/5.0 (Macintosh; Intel Mac OS X 14.0; rv:120.0) Gecko/20100101 Firefox/120.0"))
        assertEquals("Device", RemoteInput.deviceLabel(null))
    }

    @Test fun favouriteTarget() {
        assertTrue(RemoteInput.favouriteTarget("add", false))
        assertTrue(RemoteInput.favouriteTarget("add", true)) // already saved: stays saved
        assertFalse(RemoteInput.favouriteTarget("remove", true))
        assertFalse(RemoteInput.favouriteTarget("remove", false))
        assertTrue(RemoteInput.favouriteTarget("toggle", false))
        assertFalse(RemoteInput.favouriteTarget(null, true))
    }
}
