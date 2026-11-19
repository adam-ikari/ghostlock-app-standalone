package com.ghostlock.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootBootGuardTest {
    @Test
    fun statusMatchesOnlySameBootId() {
        val status = "state=installing\nboot_id=abc-123\n"
        assertTrue(RootBootGuard.matches(status, "abc-123"))
        assertFalse(RootBootGuard.matches(status, "def-456"))
        assertFalse(RootBootGuard.matches(null, "abc-123"))
    }
}
