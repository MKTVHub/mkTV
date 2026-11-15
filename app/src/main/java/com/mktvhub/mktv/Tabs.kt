package com.mktvhub.mktv

import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.WebView

/**
 * One browser tab. Only [TabPolicy.MAX_LIVE] tabs keep a WebView; the rest are "discarded" to their
 * saved back/forward state (or just [url]) and reload when shown again. Never persisted: private tabs
 * leave nothing behind once they are closed.
 */
class Tab(val id: Int, val isPrivate: Boolean) {
    var webView: WebView? = null
    /** Last known address and title, kept for the switcher while the tab is discarded. */
    var url: String = ""
    var title: String = ""
    /** The page's favicon as reported by WebView, in memory only. */
    var icon: Bitmap? = null
    /** WebView.saveState of a discarded tab, so Back still works after it reloads. */
    var state: Bundle? = null
    var lastUsed = 0L
    /** Read off the UI thread by the shared WebView clients: only the shown tab counts blocks and streams. */
    @Volatile var active = false
    /** Host of this tab's current page, for redirect and pop-up rules of background tabs too. */
    @Volatile var host: String? = null

    val currentUrl: String get() = webView?.url?.takeIf { it.isNotBlank() } ?: url
    val currentTitle: String get() = webView?.title?.takeIf { it.isNotBlank() } ?: title
    fun info() = TabPolicy.Info(id, webView != null, lastUsed, isPrivate)
}

/** Pure tab rules (no Android), unit-tested in TabsTest. */
object TabPolicy {
    /** WebViews kept alive at once; low-end TVs have little memory and each WebView costs tens of MB. */
    const val MAX_LIVE = 6
    /** Tabs kept at all; beyond this the least recently used one is closed. */
    const val MAX_TABS = 20

    data class Info(val id: Int, val live: Boolean, val lastUsed: Long, val isPrivate: Boolean)

    /** Live tabs to discard so at most [maxLive] WebViews stay alive: least recently used first, never the active one. */
    fun toDiscard(tabs: List<Info>, activeId: Int, maxLive: Int = MAX_LIVE): List<Int> {
        val excess = tabs.count { it.live || it.id == activeId } - maxLive
        if (excess <= 0) return emptyList()
        return tabs.filter { it.live && it.id != activeId }.sortedBy { it.lastUsed }.take(excess).map { it.id }
    }

    /** Tabs to close so at most [maxTabs] exist: least recently used first, never the active one. */
    fun toClose(tabs: List<Info>, activeId: Int, maxTabs: Int = MAX_TABS): List<Int> {
        val excess = tabs.size - maxTabs
        if (excess <= 0) return emptyList()
        return tabs.filter { it.id != activeId }.sortedBy { it.lastUsed }.take(excess).map { it.id }
    }

    /** The tab to show after closing [closedId]: the most recently used other tab of the same kind (private or not). */
    fun nextAfterClose(tabs: List<Info>, closedId: Int): Int? {
        val closed = tabs.firstOrNull { it.id == closedId } ?: return null
        return tabs.filter { it.id != closedId && it.isPrivate == closed.isPrivate }.maxByOrNull { it.lastUsed }?.id
    }

    /** Every background WebView, for when the system is short of memory. */
    fun background(tabs: List<Info>, activeId: Int): List<Int> = tabs.filter { it.live && it.id != activeId }.map { it.id }

    /**
     * onTrimMemory levels that should free background tabs: RUNNING_LOW (10) and RUNNING_CRITICAL (15) while
     * visible, and BACKGROUND (40) or worse once hidden. UI_HIDDEN (20) alone is just "the user left".
     */
    fun discardOnTrim(level: Int) = level in 10..19 || level >= 40
}
