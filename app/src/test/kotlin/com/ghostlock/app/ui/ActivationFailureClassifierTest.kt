package com.ghostlock.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ActivationFailureClassifierTest {
    @Test
    fun classifyMissingKsudFromLogTail() {
        assertEquals(
            ActivationFailure.NO_KSUD,
            ActivationFailureClassifier.classify("activate", 1, "no ksud found (bundled nor manager)"),
        )
    }

    @Test
    fun profileReleaseMismatchIsNotRootNotEstablished() {
        val logTail = listOf(
            "pre-run boot_id=abc-123",
            "profile release mismatch: kernel 6.1.0-test vs profile 6.2.0-test",
        ).joinToString("\n")
        val failure = ActivationFailureClassifier.classify("activate", 1, logTail)
        assertNotEquals(ActivationFailure.ROOT_NOT_ESTABLISHED, failure)
        assertEquals(ActivationFailure.NATIVE_FAILED, failure)
    }

    @Test
    fun classifyInjectionGateTimeout() {
        assertEquals(
            ActivationFailure.INJECTION_GATE_TIMEOUT,
            ActivationFailureClassifier.classify("activate", 1, "injection gate failed after 90s"),
        )
    }
}
