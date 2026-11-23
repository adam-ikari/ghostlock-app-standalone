# Vector 克隆应用作用域放行实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Vector 管理器在模块详情页列出所有 user 的目标实例，并让仅由 user 0 持有的模块显式 hook 克隆 user 中的目标应用。

**Architecture:** 修改管理器侧应用列表来源和行标识，保留 `ScopeTarget(packageName,userId)` 与作用域表格式；修改 daemon 的显式作用域行边界，只在持有者集合恰好为 `{0}` 时允许跨 user 展开。重新自签名编译 manager/daemon，替换 GhostLock 内置 `vector.zip` 的两个 APK 与哈希。

**Tech Stack:** Kotlin、Compose、Android Gradle Plugin、Zygisk、KernelSU 模块 zip。

**Spec:** `docs/development/vector-clone-scope-plan.md`

## Global Constraints

- 基线：GhostLock `main` `7aff9806`；Vector tag `v2.2`，commit `8b8fa04dbd58f73bd5187fc6af02b65459b890cc`。
- 目标设备当前 adb 离线；设备门禁未跑不得标 PASS。
- 自签名 keystore 只放 `/tmp`，不入库、不提交；manager 与 daemon 使用同一签名，daemon 内嵌证书随之重建。
- 不提交、不 push，除非用户另行要求。
- 不改 GhostLock native/攻击路径/profile/wire，不触发 `cmp_disasm` 门禁。
- 不改 `restorelib.sh`。

## Review Focus

- 同包多 user 行必须可区分：UI 行显示 `U<userId>`，不能只显示包名。
- 混合持有者（U0+U10）不得获得 `{0}` 独占模块才有的跨 user 放行。
- 二级用户独有模块的现有边界不变。
- `system` 目标仍存 user 0；备份/恢复仍按 `(packageName,userId)`。
- 新签名 manager 无法覆盖安装；升级步骤必须先卸载旧 manager。

---

### Task 1: 管理器列出所有 user 的目标实例

**Files:**
- Modify: `/tmp/Vector-2.2/manager/src/main/kotlin/org/matrix/vector/manager/ui/screens/modules/ScopeViewModel.kt`
- Modify: `/tmp/Vector-2.2/manager/src/main/kotlin/org/matrix/vector/manager/ui/screens/modules/ScopeScreen.kt`

**Interfaces:**
- Consumes: `AppRepository.getInstalledApps(): List<AppInfo>`，每项含 `packageName` 与 `userId`。
- Produces: `filteredApps` 包含所有 user 的 AppInfo；`AppRow` 在包名旁显示 `U<userId>`。

- [ ] **Step 1: 删除 `ScopeViewModel.load()` 中的目标 user 过滤**

定位 `appRepository.getInstalledApps().filter { it.userId == userId }`，改为 `appRepository.getInstalledApps()`；同步更新紧邻注释，说明同包多 user 是不同目标，显示职责交给行 UI。不要改 `ScopeTarget`、draft/saved 合并、`apply()`、backup/restore。

- [ ] **Step 2: 在 `AppRow` 显示 user 标签**

在 `supportingContent` 的包名 `Text` 旁追加 `U${app.userId}` 文本，使用现有 `VectorMono` 与 `onSurfaceVariant` 样式，不新增字符串资源或参数。

- [ ] **Step 3: 编译管理器**

Run: `cd /tmp/Vector-2.2 && ./gradlew :manager:compileReleaseKotlin --console=plain`
Expected: BUILD SUCCESSFUL。

### Task 2: daemon 放行 `{0}` 独占模块的跨 user 显式作用域

**Files:**
- Modify: `/tmp/Vector-2.2/daemon/src/main/kotlin/org/matrix/vector/daemon/data/ConfigCache.kt`

**Interfaces:**
- Consumes: `moduleUsers[modPkg]: Set<Int>`，显式 scope 行 `scopeRow.userId`。
- Produces: 目标 user 边界条件 `userId !in holders && holders != setOf(0)` 时跳过该行。

- [ ] **Step 1: 替换边界条件**

将 `if (userId !in holders) return@forEach` 改为 `if (userId !in holders && holders != setOf(0)) return@forEach`，并更新注释：`{0}` 独占模块允许显式跨 user；混合持有者与二级用户独有模块保持原边界；self-hook/legacy 派生行仍只遍历 holders。

- [ ] **Step 2: 编译 daemon**

Run: `cd /tmp/Vector-2.2 && ./gradlew :daemon:compileReleaseKotlin --console=plain`
Expected: BUILD SUCCESSFUL。

### Task 3: 自签名构建并替换 vector.zip 条目

**Files:**
- Create: `/tmp/vector-selfsigned.jks`（仅临时）
- Modify: `app/src/main/assets/modules/vector.zip` 内 `manager.apk`、`manager.apk.sha256`、`daemon.apk`、`daemon.apk.sha256`

**Interfaces:**
- Consumes: Task 1/2 的源码改动。
- Produces: 新 `vector.zip`；APK 与新自签名证书匹配；zip 内其他条目字节不变。

- [ ] **Step 1: 生成临时自签名 keystore**

Run: `keytool -genkeypair -alias vector -keyalg RSA -keysize 2048 -validity 10000 -keystore /tmp/vector-selfsigned.jks -storepass vector-selfsigned -keypass vector-selfsigned -dname "CN=Vector Self Signed"`
Expected: `/tmp/vector-selfsigned.jks` 存在；口令仅用于本次临时打包，不写入仓库。

- [ ] **Step 2: 用签名属性构建 release APK**

Run: `cd /tmp/Vector-2.2 && ./gradlew -PandroidStoreFile=/tmp/vector-selfsigned.jks -PandroidStorePassword=vector-selfsigned -PandroidKeyAlias=vector -PandroidKeyPassword=vector-selfsigned :manager:assembleRelease :daemon:assembleRelease --console=plain`
Expected: BUILD SUCCESSFUL；`manager/build/outputs/apk/release/*.apk` 由新证书签名；daemon 生成源码内嵌该证书。

- [ ] **Step 3: 替换 zip 条目并更新哈希**

从构建产物复制 manager APK 为 `manager.apk`、daemon APK 为 `daemon.apk`，更新 `manager.apk.sha256` 与 `daemon.apk.sha256`；用 `sha256sum -c` 验证。`module.prop` 与其他条目保持字节不变。

- [ ] **Step 4: 静态核对**

Run: `git status --short && git diff -- app/src/main/assets/modules/vector.zip`
Expected: 仅 `vector.zip` 与计划文档变化；无 native、profile、Kotlin 业务代码变化。

### Task 4: GhostLock 构建验证

**Files:**
- Test: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`

**Interfaces:**
- Consumes: Task 3 产出的 `vector.zip`。
- Produces: 可安装的 GhostLock debug APK，包含新 Vector 模块资产。

- [ ] **Step 1: 运行 GhostLock 单元测试与 assemble**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 2: 记录设备门禁为阻塞项**

设备恢复在线后再执行设计文档批次 4—6；未执行前在进度中明确标为待验证，不写 PASS。
