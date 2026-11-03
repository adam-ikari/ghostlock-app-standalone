package com.ghostlock.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `navigationPath` is the single description of the back stack. A legal page
 * that does not show up here simply never opens, so pin the layering.
 */
class NavigationPathTest {
    private fun path(vararg extras: GhostlockScreen) = listOf(GhostlockScreen.Main) + extras

    @Test
    fun `main only when advanced is closed`() {
        assertEquals(path(), navigationPath(GhostlockUiState()))
    }

    @Test
    fun `about comes after advanced`() {
        assertEquals(
            path(GhostlockScreen.Advanced, GhostlockScreen.About),
            navigationPath(GhostlockUiState(advancedScreenVisible = true, aboutVisible = true)),
        )
    }

    @Test
    fun `a legal document layers on top of about`() {
        assertEquals(
            path(
                GhostlockScreen.Advanced,
                GhostlockScreen.About,
                GhostlockScreen.Legal(LegalDocument.Notice),
            ),
            navigationPath(
                GhostlockUiState(
                    advancedScreenVisible = true,
                    aboutVisible = true,
                    legalDocument = LegalDocument.Notice,
                ),
            ),
        )
    }
}
