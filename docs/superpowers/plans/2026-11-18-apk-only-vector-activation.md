# APK-only Vector Activation Implementation Plan

English | [简体中文](2026-11-18-apk-only-vector-activation_ZH.md)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a never-adb, freshly installed device activate Vector using only the APK and phone taps.

**Architecture:** Remove the private ZIP staging detour; publish bundled modules once to the external files dir. Replace the unreliable `suGranted` activation gate with diagnostics, real retry, and classified failure reporting. Put an explicit unknown-sources permission gate in front of the package installer and resume the pending action after return.

**Tech Stack:** Kotlin, Jetpack Compose, AndroidX Activity Result / FileProvider, JUnit 4, Robolectric, Gradle, NDK make targets.

**Spec:** `docs/development/apk-only-vector-activation-design.md`

## Global Constraints

- Baseline commit: `83833f9c435eaa1a43549e9b11e24750c5266a20`.
- Do not change `src/core/**`, profile, wire, route, or the zygote injection gate.
- Do not bundle `libksud.so`; do not use root `pm install`; do not add Manifest permissions.
- Published module path stays `Android/data/<pkg>/files/modules-published-<ts>`; env var stays `GHOSTLOCK_LSPD_MODULES`.
- FileProvider whitelist stays `cache-path name="component_apk" path="component-apk/"`.
- Manifest keeps `REQUEST_INSTALL_PACKAGES` and `QUERY_ALL_PACKAGES`; it must not gain `MANAGE_EXTERNAL_STORAGE`.
- `REQUEST_INSTALL_PACKAGES` is a declaration, not authorization; check `canRequestPackageInstalls()` at runtime.
- Android 16 Advanced Protection Mode must be explained to the user; do not silently retry `ACTION_VIEW`.
- Keep `strings.xml` and `values-zh/strings.xml` key-for-key identical.
- Manager APK signature verification (`ComponentFetcher.kt:40-44`) is out of scope.
- Commit only if the user asks; otherwise leave changes in the working tree.

## Review Focus

- Fresh install with unknown sources denied: must open settings, not show `module_fetch_kernelsu_no_installer`; pinned by Task 4 `permissionDeniedOpensUnknownSourcesAndKeepsPendingPath` and Task 5 clean-device negative.
- Missing `ksud` or manager: activation must report a missing-component diagnosis, not a fake `su` dialog; pinned by Task 2 `suGrantRowIsDiagnosticOnly` and Task 3 `classifyMissingKsudFromLogTail`.
- Same boot after root: boot_id guard must reject; pinned by Task 2 `statusMatchesOnlySameBootId` and Task 5 clean-device negative.
- Reinstall changing uid: old published dir must not be reused; pinned by Task 1 `publishUsesTimestampedDirectory`.
- Gate failure after zygote restart: must classify as injection-gate failure, not generic success; pinned by Task 3 `classifyInjectionGateTimeout`.

---

### Task 1: Dead-link deletion and single-copy staging

**Files:**
- Modify: `app/src/main/kotlin/com/ghostlock/app/runtime/LspdModuleStaging.kt:19-98`
- Modify: `app/src/main/kotlin/com/ghostlock/app/data/AndroidGhostlockRepository.kt:62-69,178-231,627-628,757-760`
- Modify: `app/src/main/kotlin/com/ghostlock/app/domain/repository/GhostlockRepository.kt:57-65`
- Modify: `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockViewModel.kt:55-70,862-868,1169-1188,1559-1570`
- Create: `app/src/test/kotlin/com/ghostlock/app/runtime/LspdModuleStagingTest.kt`

**Interfaces:**
- Consumes: none.
- Produces:
  - `LspdModuleStaging.publish(context: Context): String?`
  - `internal fun publish(context: Context, zipNames: List<String>, copyAsset: (String, File) -> Boolean): String?`
  - `AndroidGhostlockRepository.publishLspdModules(): String?` now calls the production `publish(context)`.
- Removes: `PrivateDir`, `stageLspdStackModules()`, `LspdStackDirectory` staging use, `GhostlockRepository.stageModuleZip()`, `DocumentRequest.ModuleZip`, `onPickModuleZip()`, `GhostlockViewModel.stageModuleZip()`.

- [ ] **Step 1: Write the failing test**

In `LspdModuleStagingTest.kt`, add:

```kotlin
@Test
fun publishCopiesAssetZipsOnceIntoFreshExternalDirectory() {
    val copied = mutableListOf<String>()
    val dir = LspdModuleStaging.publish(
        context = org.robolectric.RuntimeEnvironment.getApplication(),
        zipNames = listOf("rezygisk.zip", "vector.zip"),
        copyAsset = { name, target -> copied += "$name->${target.name}"; true },
    )
    assertNotNull(dir)
    assertTrue(dir!!.name.startsWith("modules-published-"))
    assertEquals(listOf("rezygisk.zip->rezygisk.zip", "vector.zip->vector.zip"), copied)
}

@Test
fun publishReturnsNullWhenNoAssetZipNames() {
    assertNull(LspdModuleStaging.publish(context, emptyList()) { _, _ -> true })
}

@Test
fun publishUsesTimestampedDirectory() {
    val a = LspdModuleStaging.publish(context, listOf("vector.zip")) { _, _ -> true }
    val b = LspdModuleStaging.publish(context, listOf("vector.zip")) { _, _ -> true }
    assertNotNull(a)
    assertNotNull(b)
    assertNotEquals(a, b)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.runtime.LspdModuleStagingTest'`
Expected: FAIL because the internal overload and direct asset copy do not exist.

- [ ] **Step 3: Implement minimal code**

Rewrite `LspdModuleStaging.publish()` to enumerate `assets/modules/*.zip` in the production wrapper, call the internal overload, create a fresh `modules-published-<timestamp>` dir under `getExternalFilesDir(null)`, copy each zip once, and return the dir path. Delete every dead link listed in Interfaces. Do not touch `GHOSTLOCK_LSPD_MODULES` or `GHOSTLOCK_SELINUX_FIXUP`.

- [ ] **Step 4: Run tests and static check**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.runtime.LspdModuleStagingTest'`
Expected: PASS.

Run: `grep -RIn 'stageModuleZip\|DocumentRequest.ModuleZip\|onPickModuleZip\|PrivateDir\|LspdStackDirectory' app/src/main app/src/test || true`
Expected: no production references; any remaining test reference must be removed in this task.

- [ ] **Step 5: Commit (only if user asked)**

Do not commit unless the user explicitly asks.

---

### Task 2: Preflight contract and detector diagnostic

**Files:**
- Modify: `app/src/main/kotlin/com/ghostlock/app/domain/repository/GhostlockRepository.kt:100-115`
- Modify: `app/src/main/kotlin/com/ghostlock/app/data/AndroidGhostlockRepository.kt:1170-1190`
- Modify: `app/src/main/kotlin/com/ghostlock/app/runtime/ComponentDetector.kt:185-243`
- Modify: `app/src/test/kotlin/com/ghostlock/app/runtime/ComponentDetectorTest.kt`
- Create: `app/src/test/kotlin/com/ghostlock/app/runtime/RootBootGuardTest.kt`

**Interfaces:**
- Consumes: Task 1 `LspdModuleStaging.publish()`.
- Produces:
  - `suspend fun GhostlockRepository.ksudReady(): Boolean`
  - `suspend fun GhostlockRepository.rootEstablishedInThisBoot(): Boolean`
  - `internal object RootBootGuard { fun matches(statusText: String?, currentBootId: String): Boolean }`
  - `ComponentDetector.Status(key = "su_grant", detail = <diagnostic>, ok = <probe result>)`

- [ ] **Step 1: Write the failing tests**

Add to `ComponentDetectorTest.kt`:

```kotlin
@Test
fun suGrantRowIsDiagnosticOnly() {
    val row = ComponentDetector.suGrantDiagnostic(granted = false)
    assertEquals("su_grant", row.key)
    assertFalse(row.ok)
    assertTrue(row.detail!!.contains("diagnostic"))
}
```

Add to `RootBootGuardTest.kt`:

```kotlin
@Test
fun statusMatchesOnlySameBootId() {
    val status = "state=installing\nboot_id=abc-123\n"
    assertTrue(RootBootGuard.matches(status, "abc-123"))
    assertFalse(RootBootGuard.matches(status, "def-456"))
    assertFalse(RootBootGuard.matches(null, "abc-123"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.runtime.ComponentDetectorTest' --tests 'com.ghostlock.app.runtime.RootBootGuardTest'`
Expected: FAIL because the new APIs do not exist.

- [ ] **Step 3: Implement minimal code**

Add `ksudReady()` as a non-throwing wrapper around existing `resolveKsudPath(onLog) != null`. Add `rootEstablishedInThisBoot()` using `RootBootGuard.matches()` over `.ghostlock_status` and `/proc/sys/kernel/random/boot_id`. Add `ComponentDetector.suGrantDiagnostic(granted: Boolean): Status` and include it in `detectAll()` as a diagnostic row. Do not change activation gating here.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.runtime.ComponentDetectorTest' --tests 'com.ghostlock.app.runtime.RootBootGuardTest'`
Expected: PASS.

- [ ] **Step 5: Commit (only if user asked)**

Do not commit unless the user explicitly asks.

---

### Task 3: ViewModel activation control flow

**Files:**
- Modify: `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockViewModel.kt:869-892,974-989,1006-1042,1050-1120,1244-1300`
- Create: `app/src/test/kotlin/com/ghostlock/app/ui/ActivationFailureClassifierTest.kt`
- Create: `app/src/test/kotlin/com/ghostlock/app/ui/ActivationFlowTest.kt`

**Interfaces:**
- Consumes: Task 2 `ksudReady()`, `rootEstablishedInThisBoot()`, `suGrantDiagnostic()`.
- Produces:
  - `enum class PendingInstallAction { NONE, KERNELSU_MANAGER, VECTOR_MANAGER }`
  - `enum class ActivationFailure { NONE, NO_KSUD, ROOT_NOT_ESTABLISHED, NATIVE_FAILED, INJECTION_GATE_TIMEOUT, UNKNOWN }`
  - `internal object ActivationFailureClassifier { fun classify(phase: String, exitCode: Int, logTail: String): ActivationFailure }`
  - `fun onInstallerReturned(pending: PendingInstallAction)`
  - `fun onContinuePendingInstall()`
  - `GhostlockEffect.InstallApk(path: String, pending: PendingInstallAction)`

- [ ] **Step 1: Write the failing tests**

In `ActivationFailureClassifierTest.kt`:

```kotlin
@Test
fun classifyMissingKsudFromLogTail() {
    assertEquals(
        ActivationFailure.NO_KSUD,
        ActivationFailureClassifier.classify("activate", 1, "no ksud found (bundled nor manager)"),
    )
}

@Test
fun classifyInjectionGateTimeout() {
    assertEquals(
        ActivationFailure.INJECTION_GATE_TIMEOUT,
        ActivationFailureClassifier.classify("activate", 1, "injection gate failed after 90s"),
    )
}
```

In `ActivationFlowTest.kt`, use a test fake implementing `GhostlockRepository` and assert:

```kotlin
@Test
fun activateFrameworkDoesNotCallSuGrantedGate() = runTest {
    val repo = FakeRepository(suGranted = false, ksudReady = true, rootEstablished = true)
    val vm = GhostlockViewModel(repo)
    vm.onActivateFramework()
    assertEquals(0, repo.suGrantedCalls)
    assertEquals(1, repo.runExploitCalls)
}

@Test
fun retrySuAuthorizationRerunsActivatePhase() = runTest {
    val repo = FakeRepository(suGranted = false, ksudReady = true, rootEstablished = true)
    val vm = GhostlockViewModel(repo)
    vm.onRetrySuAuthorization()
    assertEquals(1, repo.activatePhaseRuns)
}

@Test
fun installedContinueResumesPendingAction() = runTest {
    val repo = FakeRepository(suGranted = false, ksudReady = true, rootEstablished = true)
    val vm = GhostlockViewModel(repo)
    vm.onInstallerReturned(PendingInstallAction.KERNELSU_MANAGER)
    vm.onContinuePendingInstall()
    assertEquals(1, repo.fetchKernelSuManagerContinuations)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.ui.ActivationFailureClassifierTest' --tests 'com.ghostlock.app.ui.ActivationFlowTest'`
Expected: FAIL because the types and handlers do not exist.

- [ ] **Step 3: Implement minimal code**

Add the enums and classifier in `GhostlockViewModel.kt`. Remove the `suGranted` early return in `onActivateFramework()`. Change `onRetrySuAuthorization()` to refresh then call `onActivateFramework()`. In `runExploit()`, set `activationFailure` from the classifier and keep `suGranted` only as a UI field. In `refreshSnapshot()`, publish `ksudReady()` and `rootEstablishedInThisBoot()` diagnostics. Store the pending install action and implement both installer-return handlers.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.ui.ActivationFailureClassifierTest' --tests 'com.ghostlock.app.ui.ActivationFlowTest'`
Expected: PASS.

- [ ] **Step 5: Commit (only if user asked)**

Do not commit unless the user explicitly asks.

---

### Task 4: UI and install permission entry

**Files:**
- Modify: `app/src/main/kotlin/com/ghostlock/app/ui/MainActivity.kt:118,140-166`
- Modify: `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockUI.kt:181-199,754-812,788-808,901-906`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Create: `app/src/test/kotlin/com/ghostlock/app/ui/InstallPermissionGateTest.kt`
- Create: `app/src/test/kotlin/com/ghostlock/app/ui/StringsPairTest.kt`

**Interfaces:**
- Consumes: Task 3 `PendingInstallAction`, `onInstallerReturned()`, `onContinuePendingInstall()`, `GhostlockEffect.InstallApk(path, pending)`.
- Produces:
  - `internal object InstallPermissionGate { fun decide(canRequest: Boolean, fileExists: Boolean): InstallGateAction }`
  - `internal enum class InstallGateAction { OPEN_INSTALLER, REQUEST_UNKNOWN_SOURCES, MISSING_FILE }`
  - `DialogType.INSTALLED_CONTINUE`
  - `GhostlockActions.onContinuePendingInstall()`

- [ ] **Step 1: Write the failing tests**

In `InstallPermissionGateTest.kt`:

```kotlin
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
fun missingApkShowsMissingFileNotNoInstaller() {
    assertEquals(
        InstallGateAction.MISSING_FILE,
        InstallPermissionGate.decide(canRequest = true, fileExists = false),
    )
}
```

In `StringsPairTest.kt`:

```kotlin
@Test
fun stringsKeysMatchAcrossLocales() {
    val en = parseStringNames("src/main/res/values/strings.xml")
    val zh = parseStringNames("src/main/res/values-zh/strings.xml")
    assertEquals(en, zh)
}

@Test
fun advancedProtectionCopyExistsInBothLocales() {
    assertTrue(readString("src/main/res/values/strings.xml", "install_advanced_protection_hint").isNotBlank())
    assertTrue(readString("src/main/res/values-zh/strings.xml", "install_advanced_protection_hint").isNotBlank())
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.ui.InstallPermissionGateTest' --tests 'com.ghostlock.app.ui.StringsPairTest'`
Expected: FAIL because the gate and new string keys do not exist.

- [ ] **Step 3: Implement minimal code**

In `MainActivity.installApk()`, use `InstallPermissionGate.decide()` before creating the URI. For `REQUEST_UNKNOWN_SOURCES`, start `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` with `package:<packageName>`, store pending path/action, and return. In `onResume()`, if permission is now granted and a pending path exists, retry installation, then call `viewModel.onInstallerReturned(pending)`. Show `INSTALLED_CONTINUE` only with a completed install; its button calls `onContinuePendingInstall()`. Show `module_fetch_kernelsu_no_installer` only for `ActivityNotFoundException` after permission is granted. Rewrite SU_AUTH and MANAGERS copy; make Vector manager optional. Add paired strings `install_unknown_sources_required`, `install_advanced_protection_hint`, `install_installed_continue`, `manager_vector_optional_hint`, `su_auth_diagnostic_message`.

- [ ] **Step 4: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.ui.InstallPermissionGateTest' --tests 'com.ghostlock.app.ui.StringsPairTest'`
Expected: PASS.

- [ ] **Step 5: Commit (only if user asked)**

Do not commit unless the user explicitly asks.

---

### Task 5: Full verification

**Files:**
- Modify: none.
- Test: existing suites plus device gate evidence.
- Create: no source files; write gate output to the terminal record.

**Interfaces:**
- Consumes: Tasks 1-4.
- Produces: verification evidence matching the Spec verification matrix.

- [ ] **Step 1: Run Android and host gates**

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
make -C src native-host-tests
make -C src ghostlock
make -C src lint-tidy
```

Expected: all PASS; NDK build zero warnings; lint-tidy 0 findings.

- [ ] **Step 2: Run static contract checks**

```sh
grep -RIn 'stageModuleZip\|DocumentRequest.ModuleZip\|onPickModuleZip\|PrivateDir' app/src/main || true
grep -RIn 'canRequestPackageInstalls' app/src/main/kotlin/com/ghostlock/app/ui/MainActivity.kt
grep -RIn 'MANAGE_EXTERNAL_STORAGE' app/src/main/AndroidManifest.xml || true
grep -RIn 'component-apk/' app/src/main/res/xml/file_paths.xml
```

Expected: no dead-link lines; one permission check; no storage permission; FileProvider path unchanged.

- [ ] **Step 3: Record shape-comparison decision**

`python3 tools/cmp_disasm.py <baseline-binary> build/native/ghostlock` may be skipped because no attack-path file changed. Record "may skip" with this reason in the gate evidence. Do not claim IDENTICAL without running it.

- [ ] **Step 4: Run phone-only smoke and clean-device negative**

Phone-only smoke on warm device: install APK, install KernelSU manager from app, grant `su`, get root, activate Vector, verify Vector CLI and `state.json`. No adb.
Clean-device negative: never-adb fresh install with no manager, no grant, unknown sources denied, and Android 16 Advanced Protection Mode available. Expect settings deep link, paired copy, no `module_fetch_kernelsu_no_installer`, no activation before `ksudReady()` and `rootEstablishedInThisBoot()`. Record that the warm device cannot prove the first-install failure point.

- [ ] **Step 5: Commit (only if user asked)**

Do not commit unless the user explicitly asks.
