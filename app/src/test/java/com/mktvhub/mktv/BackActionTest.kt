package com.mktvhub.mktv

import com.mktvhub.mktv.BackAction.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class BackActionTest {
    private val never = Long.MAX_VALUE

    private fun decide(
        homeShown: Boolean = false, homeFromBack: Boolean = false, hasPage: Boolean = true,
        toolbar: Boolean = false, snackbar: Boolean = false, canGoBack: Boolean = false,
        otherTabs: Boolean = false, sincePrompt: Long = never,
    ) = BackAction.decide(homeShown, homeFromBack, hasPage, toolbar, snackbar, canGoBack, otherTabs, sincePrompt)

    @Test fun pageWithHistoryGoesBack() {
        assertEquals(Action.GO_BACK, decide(canGoBack = true))
        assertEquals(Action.GO_BACK, decide(canGoBack = true, otherTabs = true))
    }

    @Test fun startOfHistoryOpensHomeInsteadOfTheExitPrompt() {
        assertEquals(Action.OPEN_HOME, decide())
        // Even right after an old prompt: opening Home is never an exit.
        assertEquals(Action.OPEN_HOME, decide(sincePrompt = 500))
        // A blank tab with nothing on it also lands on Home.
        assertEquals(Action.OPEN_HOME, decide(hasPage = false))
    }

    @Test fun firstPageOfAPopUpTabClosesTheTab() {
        assertEquals(Action.CLOSE_TAB, decide(otherTabs = true))
    }

    @Test fun homeReachedByBackAsksToExitAndNeverReturnsToThePage() {
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, homeFromBack = true))
        // History behind the page doesn't matter (Home only opens this way at its start), nor do other tabs.
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, homeFromBack = true, canGoBack = true, otherTabs = true))
    }

    @Test fun secondBackWithinTwoSecondsExits() {
        assertEquals(Action.EXIT, decide(homeShown = true, homeFromBack = true, sincePrompt = 0))
        assertEquals(Action.EXIT, decide(homeShown = true, homeFromBack = true, sincePrompt = 1_999))
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, homeFromBack = true, sincePrompt = 2_000))
    }

    @Test fun homeWithNoPageAsksToExit() {
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, hasPage = false))
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, hasPage = false, otherTabs = true))
        assertEquals(Action.EXIT, decide(homeShown = true, hasPage = false, sincePrompt = 300))
    }

    @Test fun homeOpenedDeliberatelyOverAPageReturnsToIt() {
        assertEquals(Action.CLOSE_HOME, decide(homeShown = true))
        assertEquals(Action.CLOSE_HOME, decide(homeShown = true, canGoBack = true, sincePrompt = 100))
    }

    @Test fun toolbarAndSnackbarKeepTheirOwnBack() {
        assertEquals(Action.LEAVE_TOOLBAR, decide(toolbar = true, canGoBack = true))
        assertEquals(Action.HIDE_SNACKBAR, decide(snackbar = true, canGoBack = true))
        // Deliberate Home over a page closes first, as before.
        assertEquals(Action.CLOSE_HOME, decide(homeShown = true, toolbar = true))
    }

    /** Home hides the page, so the toolbar's Back focuses Home's first tile rather than the page behind it. */
    @Test fun toolbarBackOnHomeThatCoversThePageGoesToHome() {
        assertEquals(Action.FOCUS_HOME, decide(homeShown = true, homeFromBack = true, toolbar = true))
        assertEquals(Action.FOCUS_HOME, decide(homeShown = true, homeFromBack = true, toolbar = true, canGoBack = true, sincePrompt = 500))
        // Home with no page behind it: the same.
        assertEquals(Action.FOCUS_HOME, decide(homeShown = true, hasPage = false, toolbar = true))
        // Never an exit prompt from the toolbar; the next Back (on Home) asks.
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, homeFromBack = true))
    }

    /** Page at the start of its history → Home → prompt → exit, with no bounce back to the page. */
    @Test fun fullSequenceFromPageToExit() {
        assertEquals(Action.OPEN_HOME, decide())
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, homeFromBack = true))
        assertEquals(Action.EXIT, decide(homeShown = true, homeFromBack = true, sincePrompt = 800))
    }

    @Test fun slowSecondBackPromptsAgain() {
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, homeFromBack = true))
        assertEquals(Action.PROMPT_EXIT, decide(homeShown = true, homeFromBack = true, sincePrompt = 2_500))
        assertEquals(Action.EXIT, decide(homeShown = true, homeFromBack = true, sincePrompt = 400))
    }
}
