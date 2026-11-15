package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingTest {
    private val cw = ContinueWatching
    private fun rec(list: List<ContinueWatching.Entry>, url: String, pos: Double, dur: Double = 1200.0, now: Long = 1) =
        cw.record(list, url, "Title", "example.com", pos, dur, now)

    @Test fun recordsLongVideosNewestFirst() {
        var list = rec(emptyList(), "https://a.com/v", 100.0, now = 1)
        list = rec(list, "https://b.com/v", 200.0, now = 2)
        list = rec(list, "https://a.com/v/", 300.0, now = 3) // same page, trailing slash
        assertEquals(listOf("https://a.com/v/", "https://b.com/v"), list.map { it.url })
        assertEquals(300.0, list[0].position, 0.0)
        assertEquals(3L, list[0].time)
    }

    @Test fun skipsShortLiveAndEarly() {
        val empty = emptyList<ContinueWatching.Entry>()
        assertSame(empty, rec(empty, "https://a.com/", 100.0, dur = 179.0)) // under 3 min
        assertSame(empty, rec(empty, "https://a.com/", 100.0, dur = 0.0))   // live
        assertSame(empty, rec(empty, "https://a.com/", 100.0, dur = Double.POSITIVE_INFINITY))
        assertSame(empty, rec(empty, "https://a.com/", 29.0))               // barely started
        assertSame(empty, rec(empty, "about:blank", 100.0))
        // An early sample doesn't wipe a saved position (the video may have restarted before Resume).
        val one = rec(empty, "https://a.com/", 500.0)
        assertSame(one, rec(one, "https://a.com/", 5.0))
    }

    @Test fun finishedRemovesTheEntry() {
        val list = rec(emptyList(), "https://a.com/", 500.0, dur = 1200.0)
        assertTrue(rec(list, "https://a.com/", 1150.0, dur = 1200.0).isEmpty()) // last 60 s
        assertTrue(rec(list, "https://a.com/", 1141.0, dur = 1200.0).isEmpty()) // 95 %+ (and < 60 s left)
        val movie = rec(emptyList(), "https://m.com/", 500.0, dur = 7200.0)
        assertTrue(rec(movie, "https://m.com/", 6900.0, dur = 7200.0).isEmpty()) // past 95 %, 5 min left
        assertEquals(1, rec(movie, "https://m.com/", 6700.0, dur = 7200.0).size)
        assertTrue(cw.isFinished(130.0, 180.0))
        assertFalse(cw.isFinished(100.0, 180.0))
    }

    @Test fun capped() {
        var list = emptyList<ContinueWatching.Entry>()
        for (i in 1..30) list = rec(list, "https://s$i.com/", 100.0, now = i.toLong())
        assertEquals(ContinueWatching.MAX, list.size)
        assertEquals("https://s30.com/", list[0].url)
    }

    @Test fun findAndRemove() {
        val list = rec(rec(emptyList(), "https://a.com/x", 100.0), "https://b.com/", 100.0)
        assertEquals("https://a.com/x", cw.find(list, "https://a.com/x/")?.url)
        assertNull(cw.find(list, "https://a.com/y"))
        assertNull(cw.find(list, null))
        assertEquals(listOf("https://b.com/"), cw.remove(list, "https://a.com/x").map { it.url })
        // The fragment matters: YouTube's TV interface puts the video id there.
        val tv = rec(emptyList(), "https://www.youtube.com/tv#/watch?v=1", 100.0)
        assertNull(cw.find(tv, "https://www.youtube.com/tv#/watch?v=2"))
    }

    @Test fun offerOnlyForSameVideoNotYetResumed() {
        val saved = ContinueWatching.Entry("https://a.com/", "T", "a.com", 754.0, 1200.0, 1)
        assertTrue(cw.shouldOffer(saved, 0.0, 1200.0))
        assertTrue(cw.shouldOffer(saved, 3.0, 1205.0))
        assertFalse(cw.shouldOffer(saved, 750.0, 1200.0)) // the site resumed by itself
        assertFalse(cw.shouldOffer(saved, 0.0, 30.0))     // a pre-roll ad, not the video
        assertFalse(cw.shouldOffer(saved, 0.0, 0.0))      // duration not known yet
        assertTrue(cw.sameVideo(saved, 1210.0))
        assertFalse(cw.sameVideo(saved, Double.NaN))
    }

    @Test fun labels() {
        assertEquals("0:05", cw.clock(5.4))
        assertEquals("12:34", cw.clock(754.0))
        assertEquals("1:02:03", cw.clock(3723.0))
        fun left(pos: Double, dur: Double) = cw.leftLabel(ContinueWatching.Entry("u", "t", "h", pos, dur, 0))
        assertEquals("12 min left", left(480.0, 1200.0))
        assertEquals("1 h 5 min left", left(0.0, 3900.0))
        assertEquals("2 h left", left(0.0, 7200.0))
        assertEquals("Less than a minute left", left(1170.0, 1200.0))
    }

    @Test fun jsonRoundTripAndBadInput() {
        val list = rec(rec(emptyList(), "https://a.com/", 100.5, now = 1), "https://b.com/", 200.0, now = 2)
        assertEquals(list, cw.fromJson(cw.toJson(list)))
        assertTrue(cw.fromJson(null).isEmpty())
        assertTrue(cw.fromJson("not json").isEmpty())
        assertEquals(1, cw.fromJson("""[{"url":"https://a.com/","pos":40,"dur":300,"time":5},{"title":"no url"}]""").size)
    }

    @Test fun homeOrderPutsNewSectionsOnTop() {
        val all = listOf("cw", "pop", "fav", "rec")
        assertEquals(all, HomeSections.merge(emptyList(), all))
        // An order saved before Continue watching existed gets it at the top.
        assertEquals(listOf("cw", "fav", "pop", "rec"), HomeSections.merge(listOf("fav", "pop", "rec"), all))
        // Once the user moved it, their order stays.
        assertEquals(listOf("pop", "cw", "fav", "rec"), HomeSections.merge(listOf("pop", "cw", "fav", "rec"), all))
        // Unknown ids dropped; other missing ids go to the end.
        assertEquals(listOf("cw", "fav", "pop", "rec"), HomeSections.merge(listOf("old", "fav", "pop"), all))
    }
}
