package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageZoomTest {
    @Test fun stepsUpAndDownWithinLevels() {
        assertEquals(110, PageZoom.step(100, 1))
        assertEquals(90, PageZoom.step(100, -1))
        assertEquals(200, PageZoom.step(200, 1))
        assertEquals(75, PageZoom.step(75, -1))
        assertEquals(100, PageZoom.step(100, 0))
    }

    @Test fun oddValuesSnapToNearestLevel() {
        assertEquals(125, PageZoom.snap(130))
        assertEquals(75, PageZoom.snap(10))
        assertEquals(200, PageZoom.snap(500))
        assertEquals(150, PageZoom.step(130, 1))
    }

    @Test fun canStepStopsAtEnds() {
        assertFalse(PageZoom.canStep(200, 1))
        assertFalse(PageZoom.canStep(75, -1))
        assertTrue(PageZoom.canStep(100, 1))
        assertTrue(PageZoom.canStep(100, -1))
    }

    @Test fun hostKeyDropsWwwAndCase() {
        assertEquals("example.com", PageZoom.hostKey("WWW.Example.com"))
        assertEquals("news.example.com", PageZoom.hostKey("news.example.com"))
        assertEquals("example.com", PageZoom.hostKey("example.com."))
        assertNull(PageZoom.hostKey(""))
        assertNull(PageZoom.hostKey(null))
        assertNull(PageZoom.hostKey("www."))
    }

    @Test fun levelForFallsBackToDefault() {
        val sites = mapOf("example.com" to 150)
        assertEquals(150, PageZoom.levelFor("www.example.com", sites, 100))
        assertEquals(125, PageZoom.levelFor("other.org", sites, 125))
        assertEquals(125, PageZoom.levelFor(null, sites, 125))
    }

    @Test fun choosingTheDefaultRemovesTheOverride() {
        var sites = PageZoom.withLevel(emptyMap(), "www.example.com", 150, 100)
        assertEquals(mapOf("example.com" to 150), sites)
        sites = PageZoom.withLevel(sites, "example.com", 100, 100)
        assertTrue(sites.isEmpty())
        assertEquals(sites, PageZoom.withLevel(sites, null, 150, 100))
    }

    @Test fun parseAndSerializeRoundTrip() {
        val sites = mapOf("example.com" to 150, "a.org" to 75)
        assertEquals(sites, PageZoom.parse(PageZoom.serialize(sites)))
        assertEquals(mapOf("ok.com" to 125), PageZoom.parse(setOf("ok.com=125", "bad", "=100", "x.com=abc", "y.com=900", "www.ok.com=125")))
    }

    @Test fun scriptUsesSnappedLevel() {
        assertEquals("window.__sp && window.__sp.zoom && __sp.zoom(125)", PageZoom.script(124))
        assertEquals("125 %", PageZoom.label(125))
    }

    @Test fun fillOnlyWhenNativeFullScreenDidNotShow() {
        assertEquals(PageZoom.Fs.REQUESTED, PageZoom.parseFs("\"on\""))
        assertEquals(PageZoom.Fs.FAILED, PageZoom.parseFs("\"fail\""))
        assertEquals(PageZoom.Fs.NO_VIDEO, PageZoom.parseFs("null"))
        assertEquals(PageZoom.Fs.NO_VIDEO, PageZoom.parseFs(null))
        assertTrue(PageZoom.needsFill(PageZoom.Fs.REQUESTED, nativeShown = false, filling = false))
        assertTrue(PageZoom.needsFill(PageZoom.Fs.FAILED, nativeShown = false, filling = false))
        assertFalse(PageZoom.needsFill(PageZoom.Fs.REQUESTED, nativeShown = true, filling = false))
        assertFalse(PageZoom.needsFill(PageZoom.Fs.FAILED, nativeShown = false, filling = true))
        assertFalse(PageZoom.needsFill(PageZoom.Fs.NO_VIDEO, nativeShown = false, filling = false))
    }
}
