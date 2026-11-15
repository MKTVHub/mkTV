package com.mktvhub.mktv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {
    private val google = WebSearch.engine("google")
    private val ddg = WebSearch.engine("duckduckgo")

    @Test fun unknownEngineFallsBackToGoogle() {
        assertEquals("google", WebSearch.engine("nope").id)
        assertEquals("google", WebSearch.engine(null).id)
        assertEquals("startpage", WebSearch.engine("startpage").id)
    }

    @Test fun normalizeKeepsAddresses() {
        assertEquals("https://example.com/a?b=1", WebSearch.normalize(" https://example.com/a?b=1 ", google))
        assertEquals("http://192.168.1.2:8080/", WebSearch.normalize("http://192.168.1.2:8080/", google))
        assertEquals("about:blank", WebSearch.normalize("about:blank", google))
        assertEquals("about:blank", WebSearch.normalize("   ", google))
    }

    @Test fun normalizeAddsHttps() {
        assertEquals("https://example.com", WebSearch.normalize("example.com", google))
        assertEquals("https://localhost:3000", WebSearch.normalize("localhost:3000", google))
    }

    @Test fun normalizeSearchesWithTheChosenEngine() {
        assertEquals("https://www.google.com/search?q=cats%20and%20dogs", WebSearch.normalize("cats and dogs", google))
        assertEquals("https://duckduckgo.com/?q=cats", WebSearch.normalize("cats", ddg))
        assertEquals("https://www.google.com/search?q=c%2B%2B%20%26%20rust%3F", WebSearch.normalize("c++ & rust?", google))
        assertEquals("https://search.brave.com/search?q=v1.2%20notes", WebSearch.normalize("v1.2 notes", WebSearch.engine("brave")))
    }

    @Test fun looksLikeUrl() {
        assertTrue(WebSearch.looksLikeUrl("bbc.co.uk"))
        assertTrue(WebSearch.looksLikeUrl("https://x"))
        assertFalse(WebSearch.looksLikeUrl("weather today"))
        assertFalse(WebSearch.looksLikeUrl("news"))
        assertFalse(WebSearch.looksLikeUrl(""))
    }

    @Test fun suggestUrlEncodes() {
        assertEquals("https://duckduckgo.com/ac/?q=big%20cats&type=list", WebSearch.suggestUrl(" big cats ", ddg))
        assertEquals("https://api.bing.com/osjson.aspx?form=OSDJAS&query=a%26b", WebSearch.suggestUrl("a&b", WebSearch.engine("bing")))
    }

    @Test fun parsesOpenSearchResponses() {
        // Google adds extra arrays/objects after the list.
        val g = """["weath",["weather","weather radar","weather","  "],[],{"google:suggestsubtypes":[[512]]}]"""
        assertEquals(listOf("weather", "weather radar"), WebSearch.parseSuggestions(g))
        val d = """["a",["a1","a2","a3","a4","a5","a6","a7","a8"]]"""
        assertEquals(6, WebSearch.parseSuggestions(d).size)
        assertEquals(listOf("a1", "a2"), WebSearch.parseSuggestions(d, max = 2))
    }

    @Test fun badSuggestionResponsesGiveNothing() {
        assertEquals(emptyList<String>(), WebSearch.parseSuggestions(""))
        assertEquals(emptyList<String>(), WebSearch.parseSuggestions("<html>"))
        assertEquals(emptyList<String>(), WebSearch.parseSuggestions("""["q"]"""))
        assertEquals(emptyList<String>(), WebSearch.parseSuggestions("""{"s":[]}"""))
    }

    @Test fun displayUrl() {
        assertEquals("example.com/a", WebSearch.displayUrl("https://www.example.com/a/"))
        assertEquals("example.com", WebSearch.displayUrl("http://example.com"))
    }

    @Test fun localMatchesRankAndDedupe() {
        val entries = listOf(
            "The Weather Channel" to "https://weather.com/",
            "BBC Weather" to "https://www.bbc.co.uk/weather",
            "Local news" to "https://news.example.com/",
            "Weather duplicate" to "https://weather.com",
            "Wikipedia" to "https://en.wikipedia.org/",
        )
        val m = WebSearch.localMatches("weath", entries)
        assertEquals("https://weather.com/", m[0].second) // address starts with the query
        assertEquals("https://www.bbc.co.uk/weather", m[1].second) // title word starts with it
        assertEquals(2, m.size) // duplicate address dropped
        assertEquals(listOf("https://en.wikipedia.org/"), WebSearch.localMatches("wiki", entries).map { it.second })
        assertEquals(emptyList<Pair<String, String>>(), WebSearch.localMatches("  ", entries))
        assertEquals(1, WebSearch.localMatches("e", entries, max = 1).size)
    }
}
