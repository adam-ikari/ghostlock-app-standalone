---
id: titan2-ghostlock-profile
title: "Titan 2 的 GhostLock profile（已生成，待真机验证）"
category: project
status: active
tags: [titan2, ghostlock, profile, mediatek]
created: "2026-10-21T04:34:00"
updated: "2026-10-21T08:09:26"
---

<!-- compiled_truth -->
## 状态

**profile 与临时 root 都已在真机验证通过**；app 侧按「只负责 root」的边界定稿。

## app 侧改动（上游 GhostLock，分支 `feat/titan2-profile`）

| 改动 | 位置 | 说明 |
| --- | --- | --- |
| Titan 2 profile | `app/src/main/assets/kernel_profiles/*.conf` | 6.1.145 / 6.1.115 两份 + `index.conf` 登记 |
| 设备表 | `docs/kernel_profiles/SUPPORTED_DEVICES{,_ZH}.md` | 加 Unihertz Titan 2 一行 |
| **运行前检查面板** | `GhostlockUI.kt` / `GhostlockViewModel.kt` / `GhostlockModels.kt` | 主页新增卡片，逐项列出：内核受支持 / profile 匹配且合法 / Shizuku 已装 / Shizuku 在运行且已授权 / 已装带 `ksud` 的 KernelSU 管理器。**必须项不满足 → 运行按钮灰**；可选项（KernelSU 管理器）不满足仍可跑 |
| 应用名 | `res/values{,-zh}/strings.xml` | `GhostLock Titan 2` / `GhostLock Titan 2 版` |
| 传统导入夹具 | `app/src/test/resources/remote-main-6x-offsets.json` + `ProfileMigrationEquivalenceTest` | 补两份 profile 的 v1 等价夹具，数量 63 → 65 |

**边界（用户明确要求）**：

- app **只负责 root**（利用 + SELinux 修复 + KernelSU late-load）。
- **Shizuku 是独立 app**；**模块管理器（Vector/LSPosed）也是独立 app**；
  app 里**不做下载功能**，只做检查与提示。
- 因此：早期那版「在 root 脚本里装 ZygiskNext/Vector」的 native 补丁**已回退**
  （`src/core/attack/ops.cpp` 恢复上游原样，攻击路径零改动）。

## 构建与验证

```bash
# 宿主/本机
cd vendor/ghostlock-app
ONDK_HOME=/home/gem/android-sdk/ondk-r30.1 ANDROID_NDK_HOME=$ONDK_HOME \
  ./gradlew :app:assembleDebug :app:testDebugUnitTest
# → build/app/outputs/apk/debug/GhostLock-v1.2(581)-arm64-v8a-debug.apk
#   87 项单测全过（含 profile 传统导入等价性）
```

`recommend_shizuku` 保持 **0**：v1 传统导入格式没有这个字段，
写成 1 会让「builtin vs legacy import」的等价性测试失败；
Shizuku 依赖改由**运行前检查面板**提示（更准确，不破坏上游协议）。

## 宿主与手机之间的传文件

a2mcp 的 `/xfer/` 路由支持 **PUT 上传**（`curl -H 'Authorization: Bearer <token>' -T <file> <tunnel>/xfer/<name>`）：

- 单次 21MB 会被 Cloudflare **524** 掐掉 → 拆成 4MB × 6 块分别 PUT，宿主上 `cat` 合并，md5 校验。
- 目标名已存在时 PUT 返回 **403** → 换个文件名。

## 本机（Windows 宿主）侧的注意点

- GitHub 直连不通 → 用 `api.github.com/repos/<o>/<r>/releases/assets/<id>` + `Accept: application/octet-stream`。
- 同一文件别并发写（curl + 续传会写坏）。
- 手机的 USB 链路会掉（Windows 里 PnP 都看不到设备），掉线后只能物理重插；
  掉线期间 adb/fastboot 都够不着手机。


## Timeline

- time: 2026-10-21T04:34:00
  kind: decision
  summary: "Created this page: Titan 2 的 GhostLock profile（已生成，待真机验证）"
  source: "官方固件镜像 + tools/extract_rs + 上游 app 单测"
  affects: [titan2-ghostlock-profile]

- time: 2026-10-21T04:34:00
  kind: decision
  summary: "固化 Titan 2 profile 的几何、route 决策与验证状态"
  source: "extractor 输出 + 内置 profile 对照 + BuiltinProfilesTest"
  affects: [titan2-ghostlock-profile]

- time: 2026-10-21T04:34:00
  kind: decision
  summary: "生成 Titan 2 两份 kernel profile（6.1.145 / 6.1.115），route 取 tcp_zerocopy 主路 + select_stack 回落，通过上游单测，待真机验证"
  source: "extractor + vendor_boot 头/DT + BuiltinProfilesTest"
  affects: [titan2-ghostlock-profile]

- time: 2026-10-21T04:49:49
  kind: evidence
  summary: "APK 构建通过：含 libghostlock.so / libextract.so 与两份 Titan 2 profile；native payload 零告警编译通过"
  source: "ONDK r30.1 + gradle assembleDebug"
  affects: [titan2-ghostlock-profile]

- time: 2026-10-21T06:02:57
  kind: decision
  summary: "真机已验证：临时 root 拿到（tcp_zerocopy 一次命中），剩 KernelSU late-load → Xposed"
  source: "宿主机 adb 真机运行（2026-10-21）"
  affects: [titan2-ghostlock-profile]

- time: 2026-10-21T06:02:57
  kind: evidence
  summary: "真机验证通过：Titan 2（Android 16）上 tcp_zerocopy 一次命中，SELinux 置 permissive + cred 写 uid 0 成功，临时 root 拿到；iomem 复核 DRAM 基址 0x40000000"
  source: "宿主机 adb 真机运行 + 上游 pre-release native + 本项目 GLK1 profile"
  affects: [titan2-ghostlock-profile]

- time: 2026-10-21T06:08:54
  kind: evidence
  summary: "KernelSU late-load 成功：内存加载 android14-6.1_kernelsu.ko，shell 用户拿到 su（uid=0, u:r:ksu:s0），SELinux 恢复 enforcing"
  source: "宿主机真机：装官方 KernelSU v3.3.1 管理器 + 重跑利用 + ksud late-load --kmi android14-6.1 --allow-shell"
  affects: [titan2-ghostlock-profile]

- time: 2026-10-21T08:09:26
  kind: decision
  summary: "app 侧定稿：只负责 root + 运行前检查面板；Shizuku/模块管理器都是独立 app"
  source: "上游 app 改动 + gradle 构建 + 87 项单测"
  affects: [titan2-ghostlock-profile]

- time: 2026-10-21T08:09:26
  kind: decision
  summary: "app 侧定稿：只负责 root；新增运行前检查面板（内核/profile/Shizuku/KernelSU 管理器）；改名 GhostLock Titan 2 版；回退 native 的 LSPosed 栈补丁"
  source: "上游 app 改动 + gradle 构建 + 单测"
  affects: [titan2-ghostlock-profile]
