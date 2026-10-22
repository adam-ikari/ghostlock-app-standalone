---
id: app-route-decisions
title: "app 路线的执行顺序决策（临时 root → LSPosed）"
category: project
status: active
tags: [lsposed, kernelsu, selinux, app-route]
created: "2026-10-22T17:53:57"
updated: "2026-10-22T19:47:21"
---

<!-- compiled_truth -->
## 决策（2026-10-22，约束来自真机取证）

### D1 app 路线按 P1 顺序执行，不再走「少写内核」这条路

需要 exec 的步骤全部放在 SELinux permissive 窗口内完成：

装模块（`ksud module install`）→ `post-fs-data`（zygiskd 起来）→ 等 `inject_state:1`
→ 起 Vector daemon → 重启 zygote → **最后**才 `late-load`。

理由：root child 与它的 worker 都在 `u:r:kernel:s0`，enforcing 下 exec
`/system/bin/sh` 与 `/data/adb/ksu/bin/ksud` 全是 Permission denied；
而 `load_policy` 与 `ksud late-load` 都会把 SELinux 拉回 enforcing。
证据见项目文档 `docs/titan2-app-route-domain-2026-10-22.md`。

判据（算修好）：一轮真机出现 `[+] KernelSU module loaded`
+ `[*] module install step finished` + `inject_state:1`
+ LSPosed 管理器 Framework Version 2.2 / API 102，且期间设备不掉线。
若 zygote 重启时注入不成立（因为此时 kernelsu.ko 还没加载），转 D4。

### D2 app 路线里不用 su，也不对 magic mount 做 stat

- `su`：从 kernel 域卡死在 `pipe_read`（`timeout 10` 都杀不掉）；
  同一时刻从 shell 域正常（`adb shell su -c id` → `u:r:ksu:s0`）。卡的是调用者域，不是二进制。
- 任何 `[ -x /system/bin/su ]`：su 是 magic mount，stat 会阻塞整个脚本。
  两次 app 运行都死在这里——日志停在 `modules dir=`，而
  `/data/local/tmp/.ghostlock_modinstall.sh` 的 mtime 未变（heredoc 没执行到）。

### D3 当前可用路径是 CLI（adb）路线

app 路线一律标注为「在修」。分发 README、docs、app 内表述都按这个口径，
不把 app 路线写成已验证。

### D4 P2（late-load 之后再补一次 selinux_state 写）暂不实施

那属于攻击关键路径改动（多一轮内核写，历史上 oops 就在写阶段），
按上游 AGENTS.md 需要：计划文档 + `cmp_disasm` 8 函数 + 真机门禁记录。
只有 P1 被真机判死之后才启动。

### D5 遗留开关的处置

- `GHOSTLOCK_SELINUX_FIXUP`（默认 1）：保留，不启用「跳过重载」（实测跳过更早死）。
- W3 前的 `finit_module` 预探针：保留（真机判「被挡」，无害），但不计入 app 路线解法。
- 两者在 P1 定论后一并清理，避免同一个问题留两套机制。

### D6 不做的事（重申）

- 不在手机上起 adbd 再 adb 自己：手机没有 adb 客户端，adbd 要么要 root（鸡生蛋），
  要么要用户每次开机手动开无线调试 + 配对。
- 不用 Shizuku：已从代码彻底删除，官方 13.6.0 在该机型 UserService 必崩。
- 不做持久化安装（用户明确否决）。

## 真机结果（2026-10-22，构建 593）

P1 顺序在 Titan 2 上跑通：模块框架成功激活（用户口述，未留日志归档）。

- 构建：`GhostLock-v1.2(593)-arm64-v8a-debug.apk`，sha256 `885148e3f1f8…`
- 走的路径正是 D1：`policy reload skipped (SELINUX_FIXUP=0)` → `late-load deferred`
  → 装两个组件包 → `post-fs-data` → `zygisk daemon ready` → 杀 zygote
  → `zygote patched=1` → Vector daemon → **最后** `late-load`
- 结论：app 路线可用，D3 的「app 路线在修」口径作废；路线 B（adb）退为诊断等价路径
- 待补：一次执行日志（`Download/ghostlock-debug-log/`）没归档，门禁记录只有口述结论


## Timeline

- time: 2026-10-22T17:53:57
  kind: decision
  summary: "Created this page: app 路线的执行顺序决策（临时 root → LSPosed）"
  source: "真机 592 三轮取证 2026-10-22"
  affects: [app-route-decisions]

- time: 2026-10-22T17:54:10
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "docs/titan2-app-route-domain-2026-10-22.md；artifacts/2026-10-22-device-dump"
  affects: [app-route-decisions]

- time: 2026-10-22T18:00:29
  kind: decision
  summary: "D7：无 PC 是硬约束（手机在用户手上，宿主机不接手机）。因此：app 路线是唯一交付形态，CLI/adb 路线只能当诊断工具或临时方案；任何 app 侧改动都必须在手机上自助完成验证（app 内日志 + 导出到 Download/），不能依赖 adb 读日志；未验证的构建不得标成可用。同时保留「inject_state:1 + modules64 含 zygisk_vector 才允许杀 zygote」的硬前置——没有 PC 时设备进 recovery 就等于不可恢复"
  source: "用户当场说明（2026-10-22）：手机在我手上，没有 PC"
  affects: [app-route-decisions, porting-blockers]

- time: 2026-10-22T18:14:14
  kind: decision
  summary: "D1 已落地为代码：root 脚本在 SELINUX_FIXUP=0（= 用户选了组件包）时按 P1 顺序跑——跳过启动段的 late-load，$SU 指向本地包装脚本 /data/local/tmp/.ghostlock_run.sh（exec sh -c \"$2\"）从而不切域、不碰 su，栈内第二次 late-load 也跳过，唯一一次 late-load 放在整段栈之后。已安装 ksud 缺失时退回 app 包里的 libksud.so。构建 593（sha256 885148e3…），本机证据：sh -n 通过 + 抽脚本用桩整轮跑出 D1 顺序 + cmp_disasm 8 函数与基线 592 指令流一致（仅地址布局差，同源重建与基线 byte-identical 作对照）。真机门禁待用户手机执行"
  source: "ghostlock-app 566a8f4；docs/development/plan-app-route-stability-2026-10-22.md 批次记录"
  affects: [app-route-decisions, temp-root-to-lsposed]

- time: 2026-10-22T19:36:07
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: "用户真机结果 2026-10-22（构建 593）"
  affects: [app-route-decisions]

- time: 2026-10-22T19:37:06
  kind: decision
  summary: "D8 组件包不入 APK：APK 里不带 vector.zip/zygisknext.zip（assets/modules 为空，代码注释写明两者 GPL-3.0 + ZygiskNext v4-0.9.2 起禁再分发，而 app 是 Apache-2.0）。所以「只装 APK」= 一键临时 root；要激活模块还得首次在 app 里选一次两个 zip（cacheDocument 复制进 files/modules，之后每次运行不用再选）。若要做自用全内置包，把 zip 放回 app/src/main/assets/modules 即可——stageLspdStackModules 会自动复制，不需要改代码，只是那种包不能对外分发"
  source: "app/src/main/kotlin/.../LspdModuleStaging.kt、GhostlockViewModel.kt 注释、APK 内无 assets/modules"
  affects: [app-route-decisions]

- time: 2026-10-22T19:39:49
  kind: decision
  summary: "D9 组件包能不能打进 APK：ZygiskNext **不能**——包内 README 的 Copyright Notice 第 2 条写明 \"No Redistribution ... including the software as part of another project\"（v4-0.9.2 起 All rights reserved，当前 1.5.2/864）。Vector 是 GPL-3.0，打包合法（需附许可证与源码出处），但它是模块 zip（含 daemon.apk），管理器是独立 APK，用户可自装。要保住「装完点一下」的体验，合规做法是 app 内从上游官方 release 下载（update.json: lsposed.zip/zygisk-next/update.json、raw.githubusercontent.com/JingMatrix/Vector/master/zygisk/update.json 都指向 GitHub releases），用户自己从权利人处取件，不是我们分发"
  source: "zygisknext.zip README.md 版权声明、vector.zip README.md License 段、两个 update.json"
  affects: [app-route-decisions]

- time: 2026-10-22T19:47:21
  kind: decision
  summary: "D10 组件包取件方式落地：app 新增「获取组件包」——从两个上游 update.json（lsposed.zip/zygisk-next/update.json、JingMatrix/Vector master/zygisk/update.json）取 zipUrl，下载到 files/modules/，校验 module.prop id（zygisksu / zygisk_vector）与 >=100KB 体积，先写 .part 再替换。规则：已有同 id 包不覆盖（上游 Vector 3080 vs 手上验证的 3112，静默降级会换坏环境）；下载成功后清掉同 id 的其它 zip（native 会装目录里所有 .zip）。构建 596，单测 93 条全绿（新增 7 条）。限制：没有整包哈希，信任边界是 HTTPS 到 GitHub"
  source: "ghostlock-app 5670e58；docs/development/component-fetch-2026-10-22.md"
  affects: [app-route-decisions]
