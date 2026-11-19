package com.ghostlock.app.ui

import com.ghostlock.app.R
import com.ghostlock.app.data.ManagerScanner
import com.ghostlock.app.domain.model.CpuPair
import com.ghostlock.app.domain.model.DebugSettings
import com.ghostlock.app.domain.model.KernelSnapshot
import com.ghostlock.app.domain.model.OffsetCandidate
import com.ghostlock.app.domain.model.OffsetImportResult
import com.ghostlock.app.domain.model.ParseResult
import com.ghostlock.app.domain.model.ProfileConfig
import com.ghostlock.app.domain.model.UserProfileFile
import com.ghostlock.app.domain.repository.GhostlockRepository
import com.ghostlock.app.domain.repository.ProfileConfigController
import com.ghostlock.app.runtime.ComponentDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActivationFlowTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun activateFrameworkRequiresVectorManager() = runTest {
        val repo = FakeRepository(
            suGranted = true,
            ksudReady = true,
            rootEstablished = true,
            vectorManagerInstalled = false,
        )
        val vm = GhostlockViewModel(repo)
        initialize(vm, repo)

        vm.onActivateFramework()
        withTimeout(5_000) {
            while (vm.state.value.dialogType != DialogType.MANAGERS) yield()
        }

        assertEquals(0, repo.runExploitCalls)
    }

    @Test
    fun activateFrameworkRequiresSuGrant() = runTest {
        val repo = FakeRepository(suGranted = false, ksudReady = true, rootEstablished = true)
        val vm = GhostlockViewModel(repo)
        initialize(vm, repo)

        vm.onActivateFramework()
        withTimeout(5_000) {
            while (vm.state.value.dialogType != DialogType.SU_AUTH) yield()
        }

        assertEquals(1, repo.suGrantedCalls)
        assertEquals(0, repo.runExploitCalls)
        assertEquals(R.string.su_auth_title, vm.state.value.dialogTitleRes)
        assertEquals(R.string.su_auth_required_message, vm.state.value.dialogMessageRes)
    }

    @Test
    fun activateFrameworkRunsWhenSuGranted() = runTest {
        val repo = FakeRepository(suGranted = true, ksudReady = true, rootEstablished = true)
        val vm = GhostlockViewModel(repo)
        initialize(vm, repo)

        vm.onActivateFramework()
        repo.activated.await()

        assertEquals(1, repo.runExploitCalls)
        assertEquals(1, repo.activatePhaseRuns)
    }

    @Test
    fun retrySuAuthorizationRerunsActivatePhase() = runTest {
        val repo = FakeRepository(suGranted = true, ksudReady = true, rootEstablished = true)
        val vm = GhostlockViewModel(repo)
        initialize(vm, repo)
        repo.resetCounters()

        vm.onRetrySuAuthorization()
        repo.activated.await()

        assertEquals(1, repo.activatePhaseRuns)
    }

    @Test
    fun activateFailureShowsDiagnosticAndSuAuthDialog() = runTest {
        val repo = FakeRepository(
            suGranted = true,
            ksudReady = true,
            rootEstablished = true,
            runExploitResult = 1,
            logLines = listOf("no ksud found (bundled nor manager)"),
        )
        val vm = GhostlockViewModel(repo, stringResolver = { res -> "diag:$res" })
        initialize(vm, repo)

        vm.onActivateFramework()
        repo.activated.await()
        withTimeout(5_000) {
            while (vm.state.value.setupError == null) yield()
        }

        assertEquals(DialogType.SU_AUTH, vm.state.value.dialogType)
        assertTrue(vm.state.value.setupError!!.startsWith("diag:"))
        org.junit.Assert.assertNotEquals("activate phase failed", vm.state.value.setupError)
    }

    @Test
    fun installedContinueResumesPendingAction() = runTest {
        val repo = FakeRepository(suGranted = false, ksudReady = true, rootEstablished = true)
        val vm = GhostlockViewModel(repo)
        initialize(vm, repo)
        repo.resetCounters()

        vm.onInstallerReturned(PendingInstallAction.KERNELSU_MANAGER)
        vm.onContinuePendingInstall()
        repo.fetched.await()

        assertEquals(1, repo.fetchKernelSuManagerContinuations)
    }

    private suspend fun initialize(vm: GhostlockViewModel, repo: FakeRepository) {
        vm.initialize()
        withTimeout(5_000) {
            while (vm.state.value.deviceName.isEmpty()) yield()
        }
        repo.resetCounters()
    }
}

private class FakeRepository(
    private val suGranted: Boolean,
    private val ksudReady: Boolean,
    private val rootEstablished: Boolean,
    private val vectorManagerInstalled: Boolean = true,
    private val runExploitResult: Int = 0,
    private val logLines: List<String> = emptyList(),
) : GhostlockRepository {
    val activated = CompletableDeferred<Unit>()
    val fetched = CompletableDeferred<Unit>()
    var suGrantedCalls = 0
        private set
    var runExploitCalls = 0
        private set
    var activatePhaseRuns = 0
        private set
    var fetchKernelSuManagerContinuations = 0
        private set

    fun resetCounters() {
        suGrantedCalls = 0
        runExploitCalls = 0
        activatePhaseRuns = 0
        fetchKernelSuManagerContinuations = 0
    }

    override suspend fun snapshot(): KernelSnapshot = KernelSnapshot(
        deviceName = "test-device",
        kernelRelease = "6.1.0-test",
        socName = "test-soc",
        kernelSupported = true,
        cpuPairs = listOf(CpuPair(0, 1)),
        cpuPairLabels = listOf("0,1"),
        selectedCpuPair = 0,
        safeModeEnabled = false,
    )

    override fun selectCpuPair(index: Int) = Unit
    override fun setSafeModeEnabled(enabled: Boolean) = Unit
    override fun setForceAttackTest(enabled: Boolean) = Unit
    override fun selectedManagerPackage(): String? = null
    override fun setSelectedManagerPackage(pkg: String?) = Unit

    override suspend fun installedManagers(): List<ManagerScanner.Candidate> = listOf(
        ManagerScanner.Candidate("com.topjohnwu.magisk", "KernelSU", hasKsud = true, builtin = false),
        ManagerScanner.Candidate("org.matrix.vector.manager", "Vector", hasKsud = false, builtin = false),
    )

    override suspend fun importOffsets(documents: Map<String, String>): OffsetImportResult = error("unused")
    override suspend fun confirmImport(documents: Map<String, String>): OffsetImportResult = error("unused")
    override suspend fun parseSource(
        input: String,
        xblPath: String?,
        uefiPath: String?,
        vendorBootPath: String?,
        overwrite: Boolean,
        onLog: (String) -> Unit,
    ): ParseResult = error("unused")

    override suspend fun readDocument(uri: String): String = error("unused")
    override suspend fun cacheDocument(uri: String, fileName: String): String = error("unused")

    override suspend fun fetchKernelSuManager(onLog: (String) -> Unit): String? {
        fetchKernelSuManagerContinuations++
        fetched.complete(Unit)
        return "/tmp/kernelsu-manager.apk"
    }

    override suspend fun fetchVectorManager(onLog: (String) -> Unit): String? = error("unused")
    override suspend fun vectorManagerInstalled(): Boolean = vectorManagerInstalled
    override suspend fun detectComponents(): List<ComponentDetector.Status> = emptyList()

    override suspend fun suGranted(): Boolean {
        suGrantedCalls++
        return suGranted
    }

    override suspend fun deviceHasRoot(): Boolean = rootEstablished
    override suspend fun ksudReady(): Boolean = ksudReady
    override suspend fun rootEstablishedInThisBoot(): Boolean = rootEstablished
    override suspend fun vectorReady(): Boolean = false
    override suspend fun publishOffsets(candidate: OffsetCandidate): String = error("unused")
    override suspend fun userProfiles(): List<UserProfileFile> = emptyList()
    override suspend fun deleteUserProfile(name: String): Boolean = error("unused")
    override suspend fun renameUserProfile(name: String, newName: String): String? = error("unused")
    override suspend fun exportUserProfile(name: String): String = error("unused")
    override suspend fun convertUserProfile(name: String): String? = error("unused")
    override suspend fun beginEditSession(name: String?): ProfileConfig? = null
    override fun editSessionController(): ProfileConfigController? = null
    override fun editSessionIsLive(): Boolean = false
    override fun editSessionTarget(): String? = null
    override suspend fun commitEditSession(): Boolean = error("unused")
    override suspend fun saveEditSessionInPlace(): Boolean = error("unused")
    override suspend fun saveEditSessionAsNew(): String? = error("unused")
    override suspend fun exportEditSession(): String = error("unused")
    override fun editSessionRelease(): String? = null
    override fun endEditSession() = Unit
    override fun profileController(): ProfileConfigController = FakeProfileController
    override suspend fun debugSettings(): DebugSettings = DebugSettings()
    override fun setDebugExportEnabled(enabled: Boolean) = Unit
    override fun setDebugExportLocation(location: String) = Unit
    override fun setDebugKernelLogEnabled(enabled: Boolean) = Unit

    override suspend fun runExploit(pair: CpuPair, onLog: (String) -> Unit, phase: String): Int {
        runExploitCalls++
        logLines.forEach(onLog)
        if (phase == "activate") {
            activatePhaseRuns++
            activated.complete(Unit)
        }
        return runExploitResult
    }

    override suspend fun lastRunStuckStep(): String? = null
    override fun close() = Unit
}

private object FakeProfileController : ProfileConfigController {
    override suspend fun load(release: String, pair: CpuPair): ProfileConfig =
        ProfileConfig(release = release, hasProfile = true)

    override suspend fun updateGeneral(
        release: String,
        pair: CpuPair,
        values: Map<String, Long>,
    ): ProfileConfig = error("unused")

    override suspend fun updateAdvanced(
        release: String,
        pair: CpuPair,
        values: Map<String, Long>,
    ): ProfileConfig = error("unused")

    override suspend fun reset(release: String, pair: CpuPair): ProfileConfig = error("unused")
    override suspend fun resetGeneral(release: String, pair: CpuPair): ProfileConfig = error("unused")
    override suspend fun resetAdvanced(release: String, pair: CpuPair): ProfileConfig = error("unused")
    override suspend fun clearSelectedCpus(release: String, pair: CpuPair): ProfileConfig = error("unused")
    override suspend fun updateRoute(release: String, pair: CpuPair, route: String?): ProfileConfig = error("unused")
    override suspend fun updateFallback(
        release: String,
        pair: CpuPair,
        fallbackTo: String?,
    ): ProfileConfig = error("unused")

    override suspend fun export(release: String, pair: CpuPair, documentUri: String): Boolean = error("unused")
    override suspend fun saveModified(release: String, pair: CpuPair): Boolean = error("unused")
    override suspend fun builtinReleases(): List<String> = emptyList()
    override fun activeBuiltinRelease(): String? = null
    override fun activeUserProfile(): String? = null
    override suspend fun selectUserProfile(
        name: String?,
        deviceRelease: String,
        pair: CpuPair,
    ): ProfileConfig = error("unused")

    override fun onUserProfileRenamed(oldName: String, newName: String) = Unit
    override fun onUserProfileDeleted(name: String) = Unit
    override suspend fun selectBuiltin(
        release: String?,
        deviceRelease: String,
        pair: CpuPair,
    ): ProfileConfig = error("unused")

    override fun nativeDocument(config: ProfileConfig): ByteArray? = null
}
