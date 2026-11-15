package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class HistoryTest {
    private fun e(url: String, title: String = url, time: Long = 0) = History.Entry(title, url, time)

    @Test fun addPutsNewestFirstAndDedupes() {
        var list = emptyList<History.Entry>()
        list = History.add(list, e("https://a.com/", time = 1))
        list = History.add(list, e("https://b.com/", time = 2))
        list = History.add(list, e("https://a.com", time = 3)) // same page, no trailing slash
        assertEquals(listOf("https://a.com", "https://b.com/"), list.map { it.url })
        assertEquals(3L, list[0].time)
    }

    @Test fun addIsCapped() {
        var list = emptyList<History.Entry>()
        for (i in 1..10) list = History.add(list, e("https://s$i.com/"), max = 4)
        assertEquals(4, list.size)
        assertEquals("https://s10.com/", list[0].url)
    }

    @Test fun removeAndRetitle() {
        val list = listOf(e("https://a.com/", "a.com"), e("https://b.com/"))
        assertEquals(listOf("https://b.com/"), History.remove(list, "https://a.com").map { it.url })
        assertEquals("Site A", History.retitle(list, "https://a.com/", "Site A")[0].title)
        assertSame(list, History.retitle(list, "https://a.com/", " "))
        assertSame(list, History.retitle(list, "https://zzz.com/", "Z"))
    }

    @Test fun searchNeedsEveryWord() {
        val list = listOf(
            e("https://www.bbc.co.uk/news", "BBC News - Home"),
            e("https://weather.com/", "Weather forecast"),
            e("https://example.com/news/weather", "Example"),
        )
        assertEquals(3, History.search(list, "").size)
        assertEquals(listOf("https://www.bbc.co.uk/news", "https://example.com/news/weather"), History.search(list, "NEWS").map { it.url })
        assertEquals(listOf("https://example.com/news/weather"), History.search(list, "news  weather").map { it.url })
        assertEquals(listOf("https://www.bbc.co.uk/news"), History.search(list, "bbc.co.uk").map { it.url })
        assertEquals(0, History.search(list, "nothing").size)
    }

    @Test fun dayLabels() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = 1_763_208_000_000L // Saturday 15 November 2025, 12:00 UTC
        val hour = 3_600_000L
        assertEquals("Today", History.dayLabel(now - 11 * hour, now, utc, Locale.UK))
        assertEquals("Yesterday", History.dayLabel(now - 13 * hour, now, utc, Locale.UK))
        assertEquals("Thursday 13 November", History.dayLabel(now - 48 * hour, now, utc, Locale.UK))
        assertEquals("Monday 30 December 2024", History.dayLabel(now - 320 * 24 * hour, now, utc, Locale.UK))
    }

    @Test fun jsonRoundTrip() {
        val list = listOf(e("https://a.com/", "A \"quoted\" title", 42), e("https://b.com/", "", 7))
        assertEquals(list, History.fromJson(History.toJson(list)))
        assertEquals(emptyList<History.Entry>(), History.fromJson(null))
        assertEquals(emptyList<History.Entry>(), History.fromJson("not json"))
    }
}
