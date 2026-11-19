package com.ghostlock.app.ui

import com.ghostlock.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPermissionGateTest {
    @Test
    fun permissionDeniedOpensUnknownSourcesAndKeepsPendingPath() {
        assertEquals(
            InstallGateAction.REQUEST_UNKNOWN_SOURCES,
            InstallPermissionGate.decide(canRequest = false, fileExists = true),
        )
    }

    @Test
    fun permissionGrantedLaunchesInstaller() {
        assertEquals(
            InstallGateAction.OPEN_INSTALLER,
            InstallPermissionGate.decide(canRequest = true, fileExists = true),
        )
    }

    @Test
    fun unknownSourcesToastIncludesAdvancedProtectionHint() {
        val messageRes = InstallPermissionGate.unknownSourcesMessageRes()
        assertTrue(R.string.install_unknown_sources_required in messageRes)
        assertTrue(R.string.install_advanced_protection_hint in messageRes)
    }

    @Test
    fun missingApkShowsMissingFileNotNoInstaller() {
        assertEquals(
            InstallGateAction.MISSING_FILE,
            InstallPermissionGate.decide(canRequest = true, fileExists = false),
        )
    }
}
