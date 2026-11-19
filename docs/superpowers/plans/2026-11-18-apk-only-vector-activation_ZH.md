# 仅 APK 激活 Vector 实施计划

[English](2026-11-18-apk-only-vector-activation.md) | 简体中文

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让从未 adb 的全新设备只靠 APK 与手机点按即可激活 Vector。

**Architecture:** 删除私有 ZIP 暂存绕路，模块一次性发布到外部 files dir。把不可靠的 `suGranted` 激活闸门替换为诊断、真实重试与失败分类。在包安装器前加显式未知来源权限闸门，并在返回后续跑 pending 动作。

**Tech Stack:** Kotlin、Jetpack Compose、AndroidX Activity Result / FileProvider、JUnit 4、Robolectric、Gradle、NDK make 目标。

**Spec:** `docs/development/apk-only-vector-activation-design_ZH.md`

## Global Constraints

- 基线提交：`83833f9c435eaa1a43549e9b11e24750c5266a20`。
- 不改 `src/core/**`、profile、wire、route 或 zygote 注入门禁。
- 不内置 `libksud.so`；不用 root `pm install`；不新增 Manifest 权限。
- 模块发布路径保持 `Android/data/<pkg>/files/modules-published-<ts>`；环境变量保持 `GHOSTLOCK_LSPD_MODULES`。
- FileProvider 白名单保持 `cache-path name="component_apk" path="component-apk/"`。
- Manifest 保留 `REQUEST_INSTALL_PACKAGES` 与 `QUERY_ALL_PACKAGES`；不得新增 `MANAGE_EXTERNAL_STORAGE`。
- `REQUEST_INSTALL_PACKAGES` 是声明而非授权；运行时必须检查 `canRequestPackageInstalls()`。
- Android 16 Advanced Protection Mode 必须向用户说明；不得静默重试 `ACTION_VIEW`。
- `strings.xml` 与 `values-zh/strings.xml` 保持 key 一一对应。
- 管理器 APK 签名校验（`ComponentFetcher.kt:40-44`）不在本批范围。
- 仅在用户明确要求时提交；否则改动留在工作树。

## Review Focus

- 全新安装且未知来源被拒：必须跳设置页，不得显示 `module_fetch_kernelsu_no_installer`；由任务 4 的 `permissionDeniedOpensUnknownSourcesAndKeepsPendingPath` 与任务 5 干净设备负向验证钉住。
- 缺少 `ksud` 或管理器：激活必须报告缺组件诊断，不得弹出伪 `su` 对话框；由任务 2 的 `suGrantRowIsDiagnosticOnly` 与任务 3 的 `classifyMissingKsudFromLogTail` 钉住。
- 同一次开机已 root：boot_id guard 必须拒绝；由任务 2 的 `statusMatchesOnlySameBootId` 与任务 5 干净设备负向验证钉住。
- 重装导致 uid 变化：旧发布目录不得复用；由任务 1 的 `publishUsesTimestampedDirectory` 钉住。
- zygote 重启后门禁失败：必须归类为注入门禁失败，不得泛化成功；由任务 3 的 `classifyInjectionGateTimeout` 钉住。

---

### 任务 1：删除死链与单次复制

**Files:**
- Modify: `app/src/main/kotlin/com/ghostlock/app/runtime/LspdModuleStaging.kt:19-98`
- Modify: `app/src/main/kotlin/com/ghostlock/app/data/AndroidGhostlockRepository.kt:62-69,178-231,627-628,757-760`
- Modify: `app/src/main/kotlin/com/ghostlock/app/domain/repository/GhostlockRepository.kt:57-65`
- Modify: `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockViewModel.kt:55-70,862-868,1169-1188,1559-1570`
- Create: `app/src/test/kotlin/com/ghostlock/app/runtime/LspdModuleStagingTest.kt`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `LspdModuleStaging.publish(context: Context): String?`
  - `internal fun publish(context: Context, zipNames: List<String>, copyAsset: (String, File) -> Boolean): String?`
  - `AndroidGhostlockRepository.publishLspdModules(): String?` 现在调用生产版 `publish(context)`。
- Removes: `PrivateDir`、`stageLspdStackModules()`、`LspdStackDirectory` 暂存用法、`GhostlockRepository.stageModuleZip()`、`DocumentRequest.ModuleZip`、`onPickModuleZip()`、`GhostlockViewModel.stageModuleZip()`。

- [ ] **Step 1: Write the failing test**

在 `LspdModuleStagingTest.kt` 中加入：

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

运行：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.runtime.LspdModuleStagingTest'`
预期：FAIL，内部重载与直接复制尚不存在。

- [ ] **Step 3: Implement minimal code**

重写 `LspdModuleStaging.publish()`：生产 wrapper 枚举 `assets/modules/*.zip`，调用内部重载，在 `getExternalFilesDir(null)` 下新建 `modules-published-<timestamp>` 目录，每个 zip 只复制一次并返回目录路径。删除 Interfaces 中列出的所有死链。不要改 `GHOSTLOCK_LSPD_MODULES` 或 `GHOSTLOCK_SELINUX_FIXUP`。

- [ ] **Step 4: Run tests and static check**

运行：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.runtime.LspdModuleStagingTest'`
预期：PASS。

运行：`grep -RIn 'stageModuleZip\|DocumentRequest.ModuleZip\|onPickModuleZip\|PrivateDir\|LspdStackDirectory' app/src/main app/src/test || true`
预期：生产代码无残留引用；若测试中出现残留引用，在本任务删除。

- [ ] **Step 5: Commit (only if user asked)**

仅在用户明确要求时提交。

---

### 任务 2：前置检查契约与探测器诊断

**Files:**
- Modify: `app/src/main/kotlin/com/ghostlock/app/domain/repository/GhostlockRepository.kt:100-115`
- Modify: `app/src/main/kotlin/com/ghostlock/app/data/AndroidGhostlockRepository.kt:1170-1190`
- Modify: `app/src/main/kotlin/com/ghostlock/app/runtime/ComponentDetector.kt:185-243`
- Modify: `app/src/test/kotlin/com/ghostlock/app/runtime/ComponentDetectorTest.kt`
- Create: `app/src/test/kotlin/com/ghostlock/app/runtime/RootBootGuardTest.kt`

**Interfaces:**
- Consumes: 任务 1 的 `LspdModuleStaging.publish()`。
- Produces:
  - `suspend fun GhostlockRepository.ksudReady(): Boolean`
  - `suspend fun GhostlockRepository.rootEstablishedInThisBoot(): Boolean`
  - `internal object RootBootGuard { fun matches(statusText: String?, currentBootId: String): Boolean }`
  - `ComponentDetector.Status(key = "su_grant", detail = <diagnostic>, ok = <probe result>)`

- [ ] **Step 1: Write the failing tests**

在 `ComponentDetectorTest.kt` 中加入：

```kotlin
@Test
fun suGrantRowIsDiagnosticOnly() {
    val row = ComponentDetector.suGrantDiagnostic(granted = false)
    assertEquals("su_grant", row.key)
    assertFalse(row.ok)
    assertTrue(row.detail!!.contains("diagnostic"))
}
```

在 `RootBootGuardTest.kt` 中加入：

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

运行：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.runtime.ComponentDetectorTest' --tests 'com.ghostlock.app.runtime.RootBootGuardTest'`
预期：FAIL，新 API 尚不存在。

- [ ] **Step 3: Implement minimal code**

`ksudReady()` 作为现有 `resolveKsudPath(onLog) != null` 的非抛出包装。`rootEstablishedInThisBoot()` 用 `RootBootGuard.matches()` 读取 `.ghostlock_status` 与 `/proc/sys/kernel/random/boot_id`。新增 `ComponentDetector.suGrantDiagnostic(granted: Boolean): Status` 并加入 `detectAll()` 的诊断行。本任务不改激活闸门。

- [ ] **Step 4: Run tests**

运行：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.runtime.ComponentDetectorTest' --tests 'com.ghostlock.app.runtime.RootBootGuardTest'`
预期：PASS。

- [ ] **Step 5: Commit (only if user asked)**

仅在用户明确要求时提交。

---

### 任务 3：ViewModel 激活控制流

**Files:**
- Modify: `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockViewModel.kt:869-892,974-989,1006-1042,1050-1120,1244-1300`
- Create: `app/src/test/kotlin/com/ghostlock/app/ui/ActivationFailureClassifierTest.kt`
- Create: `app/src/test/kotlin/com/ghostlock/app/ui/ActivationFlowTest.kt`

**Interfaces:**
- Consumes: 任务 2 的 `ksudReady()`、`rootEstablishedInThisBoot()`、`suGrantDiagnostic()`。
- Produces:
  - `enum class PendingInstallAction { NONE, KERNELSU_MANAGER, VECTOR_MANAGER }`
  - `enum class ActivationFailure { NONE, NO_KSUD, ROOT_NOT_ESTABLISHED, NATIVE_FAILED, INJECTION_GATE_TIMEOUT, UNKNOWN }`
  - `internal object ActivationFailureClassifier { fun classify(phase: String, exitCode: Int, logTail: String): ActivationFailure }`
  - `fun onInstallerReturned(pending: PendingInstallAction)`
  - `fun onContinuePendingInstall()`
  - `GhostlockEffect.InstallApk(path: String, pending: PendingInstallAction)`

- [ ] **Step 1: Write the failing tests**

在 `ActivationFailureClassifierTest.kt` 中：

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

在 `ActivationFlowTest.kt` 中，使用实现 `GhostlockRepository` 的测试 fake，断言：

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

运行：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.ui.ActivationFailureClassifierTest' --tests 'com.ghostlock.app.ui.ActivationFlowTest'`
预期：FAIL，新类型与 handler 尚不存在。

- [ ] **Step 3: Implement minimal code**

在 `GhostlockViewModel.kt` 中加入枚举与 classifier。删除 `onActivateFramework()` 中 `suGranted` 提前返回。`onRetrySuAuthorization()` 刷新后调用 `onActivateFramework()`。`runExploit()` 用 classifier 设置 `activationFailure`，`suGranted` 仅保留为 UI 字段。`refreshSnapshot()` 发布 `ksudReady()` 与 `rootEstablishedInThisBoot()` 诊断。保存 pending 安装动作并实现两个返回 handler。

- [ ] **Step 4: Run tests**

运行：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.ui.ActivationFailureClassifierTest' --tests 'com.ghostlock.app.ui.ActivationFlowTest'`
预期：PASS。

- [ ] **Step 5: Commit (only if user asked)**

仅在用户明确要求时提交。

---

### 任务 4：UI 与安装权限入口

**Files:**
- Modify: `app/src/main/kotlin/com/ghostlock/app/ui/MainActivity.kt:118,140-166`
- Modify: `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockUI.kt:181-199,754-812,788-808,901-906`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Create: `app/src/test/kotlin/com/ghostlock/app/ui/InstallPermissionGateTest.kt`
- Create: `app/src/test/kotlin/com/ghostlock/app/ui/StringsPairTest.kt`

**Interfaces:**
- Consumes: 任务 3 的 `PendingInstallAction`、`onInstallerReturned()`、`onContinuePendingInstall()`、`GhostlockEffect.InstallApk(path, pending)`。
- Produces:
  - `internal object InstallPermissionGate { fun decide(canRequest: Boolean, fileExists: Boolean): InstallGateAction }`
  - `internal enum class InstallGateAction { OPEN_INSTALLER, REQUEST_UNKNOWN_SOURCES, MISSING_FILE }`
  - `DialogType.INSTALLED_CONTINUE`
  - `GhostlockActions.onContinuePendingInstall()`

- [ ] **Step 1: Write the failing tests**

在 `InstallPermissionGateTest.kt` 中：

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

在 `StringsPairTest.kt` 中：

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

运行：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.ui.InstallPermissionGateTest' --tests 'com.ghostlock.app.ui.StringsPairTest'`
预期：FAIL，权限闸门与新字符串 key 尚不存在。

- [ ] **Step 3: Implement minimal code**

`MainActivity.installApk()` 在创建 URI 前调用 `InstallPermissionGate.decide()`。`REQUEST_UNKNOWN_SOURCES` 时启动本包的 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`（`package:<packageName>`），保存 pending 路径/动作并返回。`onResume()` 中若权限已授予且 pending 路径存在，重试安装，然后调用 `viewModel.onInstallerReturned(pending)`。仅在已安装完成后显示 `INSTALLED_CONTINUE`，按钮调用 `onContinuePendingInstall()`。仅在权限已授予后的 `ActivityNotFoundException` 显示 `module_fetch_kernelsu_no_installer`。改写 SU_AUTH 与 MANAGERS 文案，Vector 管理器降为可选。新增成对字符串 `install_unknown_sources_required`、`install_advanced_protection_hint`、`install_installed_continue`、`manager_vector_optional_hint`、`su_auth_diagnostic_message`。

- [ ] **Step 4: Run tests**

运行：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.ui.InstallPermissionGateTest' --tests 'com.ghostlock.app.ui.StringsPairTest'`
预期：PASS。

- [ ] **Step 5: Commit (only if user asked)**

仅在用户明确要求时提交。

---

### 任务 5：全量验证

**Files:**
- Modify: 无。
- Test: 既有测试套件与真机门禁证据。
- Create: 不新增源码文件；终端记录门禁输出。

**Interfaces:**
- Consumes: 任务 1-4。
- Produces: 与 Spec 验证矩阵一致的证据。

- [ ] **Step 1: Run Android and host gates**

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
make -C src native-host-tests
make -C src ghostlock
make -C src lint-tidy
```

预期：全部 PASS；NDK 构建零告警；lint-tidy 0 findings。

- [ ] **Step 2: Run static contract checks**

```sh
grep -RIn 'stageModuleZip\|DocumentRequest.ModuleZip\|onPickModuleZip\|PrivateDir' app/src/main || true
grep -RIn 'canRequestPackageInstalls' app/src/main/kotlin/com/ghostlock/app/ui/MainActivity.kt
grep -RIn 'MANAGE_EXTERNAL_STORAGE' app/src/main/AndroidManifest.xml || true
grep -RIn 'component-apk/' app/src/main/res/xml/file_paths.xml
```

预期：无死链行；一处权限检查；无存储权限；FileProvider 路径未变。

- [ ] **Step 3: Record shape-comparison decision**

`python3 tools/cmp_disasm.py <baseline-binary> build/native/ghostlock` 可跳过，因为没有改动攻击路径文件。在门禁证据中记录「may skip」及原因。未运行则不得声称 IDENTICAL。

- [ ] **Step 4: Run phone-only smoke and clean-device negative**

暖机设备手机-only 冒烟：安装 APK → 从 app 安装 KernelSU 管理器 → 授予 `su` → 获取 root → 激活 Vector → 用 Vector CLI 与 `state.json` 验证。全程不用 adb。
干净设备负向：从未 adb、全新安装、无管理器、无授权、未知来源被拒、Android 16 Advanced Protection Mode 可用。预期跳设置页、成对文案、不出现 `module_fetch_kernelsu_no_installer`、`ksudReady()` 与 `rootEstablishedInThisBoot()` 未就绪时不激活。记录暖机设备不能证明首装失败点。

- [ ] **Step 5: Commit (only if user asked)**

仅在用户明确要求时提交。
