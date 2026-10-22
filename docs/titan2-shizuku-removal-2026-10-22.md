# 2026-10-22：彻底删除 Shizuku 依赖

## 范围

**做**：把 app 里所有与 Shizuku 相关的代码与依赖删干净，app 只保留直连路线
（app 自己用 `ProcessBuilder` 拉起 `libghostlock.so`）。

**不做**：不改 native 的攻击代码路径；不改 root 脚本的执行语义；不碰
KernelSU/Vector/ZygiskNext 组件包本身。

## 删掉的东西

| 位置 | 内容 |
| --- | --- |
| `app/build.gradle.kts` | `dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5` |
| `app/src/main/AndroidManifest.xml` | `rikka.shizuku.ShizukuProvider`、`moe.shizuku.privileged.api` 的 `<queries>`、UserService 声明 |
| `app/src/main/kotlin/com/ghostlock/app/shizuku/` | `ShizukuExploitRunner.kt`、`GhostlockUserService.kt`（另 3 个 AIDL 文件） |
| `app/src/main/aidl/.../shizuku/` | `IGhostlockUserService.aidl`、`IGhostlockCallback.aidl`、`IGhostlockStatusCallback.aidl` |
| domain / data / ui | `ShizukuStatus` 枚举、`runExploitWithShizuku()`、`setShizukuEnabled()`、`requestShizukuPermission()`、主页的「通过 Shizuku 执行」开关、状态卡上的授权入口、W3 提示弹窗 |
| profile 格式 | `recommend_shizuku` 字段（native `ProfileMeta` + `binary.cpp`、Kotlin `NativeProfileDocument`、`ProfileResolver` 的已知字段表、80 个内置 `.conf`、`docs/kernel_profiles/templates/*.conf`、Rust 提取器的输出） |

`LspdModuleStaging.kt` 挪到 `com.ghostlock.app.runtime`（它本来与 Shizuku 无关，
只是住在同一个包里）。

## 判据

**算完成**：APK 里查不到任何 Shizuku 痕迹，且 app 仍然能跑利用拿 root。

**算失败**：APK 里还有 `rikka.shizuku.*` 类或 `ShizukuProvider`；或者 app 一装就崩 /
拿不到 root。

## 验证

### 1. 门能红（负对照）

拿删除前的 APK 当坏版本：

```
旧 APK（v1.2(590) release，删除前）：dex 里 shizuku 字符串命中 72 处
  manifest: rikka.shizuku.ShizukuProvider / authorities=com.ghostlock.app.shizuku
           moe.shizuku.privileged.api / moe.shizuku.manager.permission.API_V23
新 APK（v1.2(590) release，删除后）：dex 里 shizuku 字符串命中 0 处
  manifest：无 provider / service 条目
```

即这个检查在坏版本上会亮，在好版本上不亮。

### 2. 编译与测试

- `./gradlew :app:assembleDebug :app:assembleRelease --offline` → BUILD SUCCESSFUL，零告警。
- `./gradlew :app:testDebugUnitTest :profile-core:test --offline` → BUILD SUCCESSFUL。
- native：`make -C src`（NDK r30.1）零告警；
  `src/core/tests/profile_binary_test.cpp` 用 g++-12 单独编译运行 → `profile_binary_test: ok`。
  （`make -C src native-host-tests` 在本机跑不完：本机只有 gcc 11/12，
  `<expected>` 是 C++23、gcc 13 才有；heap_context 等无关测试因此编不过，
  属于既有环境限制，与本次改动无关。）

### 3. profile 格式改动的连带影响

删掉 `recommend_shizuku` 会改 meta 段的字节，`app/src/test/resources/native-doc-golden.sha256`
（51 条内置 profile 的 wire 哈希）全部作废，已重新生成并让
`NativeDocumentEquivalenceTest` 重新变绿。

### 4. 真机

设备 `TTANCN2100002663` / Titan 2 / Android 16。

- 装上新 APK，`pm enable` 后能正常起（`u0_a298` 进程在跑）。
- `run-as` 放组件包 → app 内点「执行」→ 设备自己的 ksu 日志显示：
  `policy fixup rc=0` → `late-load exit=0` → `[+] KernelSU module loaded` →
  `[*] modules dir=/storage/emulated/0/Android/data/com.ghostlock.app/files/modules-published-1792655768092`。
  ⇒ **利用拿 root 成功，组件包发布成功**（这一环此前一直没在真机上看到过）。

## 未完成 / 阻塞

**LSPosed 框架在 app 路线上的端到端激活，本次仍未在真机跑通。**

设备在那条日志之后掉线（boot reason `reboot,userrequested,recovery`），
日志停在模块安装那一步之前。取证结论：

- `persist.sys.boot.reason.history` 只有两条，都是 `reboot,userrequested,recovery`
  （2026-10-22 15:00:41 与 16:07:47）。
- 这两次**之前**跑的都是**不含 LSPosed 步骤**的路线（15:00 那次是 CLI 路线，
  只有 exploit + root 脚本），所以 recovery 重启**不能归因到 LSPosed 激活步骤**。
- pstore（`/sys/fs/pstore/console-ramoops-0`）里那次重启的现场是
  `kernel_restart ← __arm64_sys_reboot ← invoke_syscall ← el0_svc_common`，
  即**用户态进程调了 reboot 系统调用**，同一份 pstore 里没有 Oops。
- 另有一次是真正的内核 oops：
  `Internal error: Oops - BUG: 00000000f2000800`，`pc: __secure_computing+0xc4/0xc8`，
  `lr: syscall_trace_enter+0xe8/0x1ac`，`PHYS_OFFSET: 0x40000000`（与 profile 一致），
  `ipanic_die [mrdump]`。
- 上游 `AGENTS.md` 已记 `KERNEL-PANIC-01`：同构建可 PASS/panic/PASS。

**证据等级**：recovery 重启由用户态 reboot 系统调用发起（查证，pstore 现场）；
发起者是哪个进程（未验证，候选：MTK AEE 的 aee 家族守护进程、
AOSP 的 power 菜单「Reboot to Recovery」路径、AOSP RescueParty）；
`__secure_computing` oops 与利用阶段的因果（未验证，AGENTS.md 要求同构建复现 + 冷机复跑）。

## 顺带修掉的东西

- root 脚本加**硬前置**：杀 zygote 之前必须 `inject_state:1` 且 `modules64` 含
  `zygisk_vector`，不满足就**不杀**（宁可这一轮不激活 LSPosed，也不把设备带进 recovery）。
- 去掉持久化安装 KernelSU 的路线（`ksud install` / `GHOSTLOCK_KSU_PERSIST`），
  用户明确要求不持久化。
