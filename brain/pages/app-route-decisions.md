---
id: app-route-decisions
title: "app 路线的执行顺序决策（临时 root → LSPosed）"
category: project
status: active
tags: [lsposed, kernelsu, selinux, app-route]
created: "2026-10-22T17:53:57"
updated: "2026-10-22T17:54:10"
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
