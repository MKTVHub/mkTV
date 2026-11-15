package com.mktvhub.mktv

import com.mktvhub.mktv.TabPolicy.Info
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabsTest {
    private fun live(id: Int, used: Long, private: Boolean = false) = Info(id, live = true, lastUsed = used, isPrivate = private)
    private fun asleep(id: Int, used: Long, private: Boolean = false) = Info(id, live = false, lastUsed = used, isPrivate = private)

    @Test fun nothingDiscardedUnderTheLimit() {
        val tabs = (1..6).map { live(it, it.toLong()) }
        assertEquals(emptyList<Int>(), TabPolicy.toDiscard(tabs, activeId = 6))
    }

    @Test fun oldestLiveTabsAreDiscardedFirstButNeverTheActiveOne() {
        val tabs = listOf(live(1, 1), live(2, 9), live(3, 3), live(4, 4), live(5, 5), live(6, 6), live(7, 7), asleep(8, 0))
        assertEquals(listOf(1), TabPolicy.toDiscard(tabs, activeId = 2))
        // The active tab is the least recently used one: it stays and the next oldest go.
        assertEquals(listOf(3, 4), TabPolicy.toDiscard(tabs, activeId = 1, maxLive = 5))
        // A discarded active tab about to be revived counts towards the limit too.
        assertEquals(listOf(1, 3), TabPolicy.toDiscard(tabs, activeId = 8))
    }

    @Test fun tabCountIsCapped() {
        val tabs = (1..22).map { asleep(it, it.toLong()) }
        assertEquals(listOf(2, 3), TabPolicy.toClose(tabs, activeId = 1))
        assertEquals(emptyList<Int>(), TabPolicy.toClose(tabs.take(20), activeId = 1))
    }

    @Test fun closingReturnsToTheMostRecentTabOfTheSameKind() {
        val tabs = listOf(live(1, 5), live(2, 3), live(3, 9, private = true), live(4, 7, private = true), live(5, 8))
        assertEquals(5, TabPolicy.nextAfterClose(tabs, 2))
        assertEquals(4, TabPolicy.nextAfterClose(tabs, 3))
        assertNull(TabPolicy.nextAfterClose(listOf(live(1, 1), live(2, 2, private = true)), 2))
        assertNull(TabPolicy.nextAfterClose(tabs, 99))
    }

    @Test fun backgroundIsEveryLiveTabButTheActiveOne() {
        val tabs = listOf(live(1, 1), asleep(2, 2), live(3, 3))
        assertEquals(listOf(1), TabPolicy.background(tabs, activeId = 3))
    }

    @Test fun trimLevels() {
        assertFalse(TabPolicy.discardOnTrim(5))   // RUNNING_MODERATE
        assertTrue(TabPolicy.discardOnTrim(10))   // RUNNING_LOW
        assertTrue(TabPolicy.discardOnTrim(15))   // RUNNING_CRITICAL
        assertFalse(TabPolicy.discardOnTrim(20))  // UI_HIDDEN: the user just left
        assertTrue(TabPolicy.discardOnTrim(40))   // BACKGROUND
        assertTrue(TabPolicy.discardOnTrim(80))   // COMPLETE
    }
}
