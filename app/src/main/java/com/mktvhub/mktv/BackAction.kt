package com.mktvhub.mktv

/**
 * What Back does once no overlay, panel or Home edit mode wants it (pure, tested in BackActionTest).
 * Back walks a page's history; at its start it opens mkTV Home, and Back on that Home asks
 * "Press Back again to exit" rather than returning to the page (no page ↔ Home loop).
 * Home opened deliberately over a page (Home button, ⋮, the phone) still closes back to the page.
 * The toolbar's Back returns to what's on screen below it: the page, or Home's first tile while Home covers it.
 */
object BackAction {
    enum class Action { CLOSE_HOME, FOCUS_HOME, LEAVE_TOOLBAR, HIDE_SNACKBAR, GO_BACK, CLOSE_TAB, OPEN_HOME, PROMPT_EXIT, EXIT }

    /** A second Back within this long of "Press Back again to exit" leaves mkTV. */
    const val EXIT_WINDOW_MS = 2_000L

    /**
     * [homeFromBack]: Home is showing because Back opened it at the start of a page's history.
     * [otherTabs]: another tab of the same kind (normal / private) is open to fall back to.
     * [sinceExitPromptMs]: time since "Press Back again to exit" was shown (large if never).
     */
    fun decide(
        homeShown: Boolean,
        homeFromBack: Boolean,
        hasPage: Boolean,
        toolbarFocused: Boolean,
        snackbarFocused: Boolean,
        canGoBack: Boolean,
        otherTabs: Boolean,
        sinceExitPromptMs: Long,
    ): Action {
        val exit = if (sinceExitPromptMs in 0 until EXIT_WINDOW_MS) Action.EXIT else Action.PROMPT_EXIT
        return when {
            homeShown && hasPage && !homeFromBack -> Action.CLOSE_HOME
            // Home reached by Back (or with no page) hides the page: focus goes to Home, not the hidden page.
            toolbarFocused && homeShown -> Action.FOCUS_HOME
            toolbarFocused -> Action.LEAVE_TOOLBAR
            snackbarFocused -> Action.HIDE_SNACKBAR
            // Home with nothing behind it, or reached by Back: the next step out is leaving mkTV.
            homeShown -> exit
            canGoBack -> Action.GO_BACK
            // First page of a tab (e.g. a pop-up) with other tabs open: close it and return to the previous tab.
            otherTabs -> Action.CLOSE_TAB
            else -> Action.OPEN_HOME
        }
    }
}
