---
id: temp-root-to-lsposed
title: "从临时 root 到 LSPosed 生效的完整链路"
category: concept
status: active
tags: [lsposed, kernelsu, zygisk, temp-root]
created: "2026-10-21T03:35:20"
updated: "2026-10-21T07:05:10"
---

<!-- compiled_truth -->
## 链路（在 Titan 2 上已逐步验证）

```mermaid
graph LR
    A["GhostLock 利用<br/>uid 0（内存态）"] --> B["KernelSU late-load<br/>ksud late-load --kmi android14-6.1 --allow-shell"]
    B --> C["ZygiskNext 1.5.2<br/>（KernelSU 模块 zygisksu）"]
    C --> D["Vector v2.2（LSPosed 继任者）<br/>（KernelSU 模块 zygisk_vector）"]
    D --> E["LSPosed 模块可装可 hook"]
```

## 已在真机跑通的步骤（2026-10-21）

| 环节 | 命令 / 做法 | 结果 |
| --- | --- | --- |
| 利用 | `adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin` | `[+] child is root!`（uid 0） |
| SELinux 修复 | root 脚本 `load_policy` + fixup | `policy fixup rc=0`，恢复 enforcing |
| KernelSU | 装官方 KernelSU v3.3.1 管理器（`me.weishu.kernelsu`），再跑一次利用 | `ksud late-load --kmi android14-6.1 --allow-shell` → `late-load exit=0`，`/proc/modules` 出现 `kernelsu` |
| shell 即 root | `su -c id` | `uid=0(root) context=u:r:ksu:s0` |
| ZygiskNext | `ksud module install zygisknext.zip` → `ksud post-fs-data` | `zn-daemon` 起来；`znctl status` 显示 `root_status: ✅KernelSU`、`inject_state: 1` |
| Vector/LSPosed | `ksud module install vector.zip` + 装 `manager.apk` → 重启 zygiskd 让它重扫模块 | `znctl status` → `modules64:1,zygisk_vector`；`cli status` → `Framework Version: 2.2 / API Version: 102` |
| 模块管理 | `cli modules ls/enable/disable`、`cli scope add/ls/rm/set` | 可用（`Enabled Modules: 0` = 还没装模块） |

## 关键经验（下次直接照抄）

1. **late-load 的 KMI 要对上**：本机 `uname -r` = `6.1.145-android14-11-…` → KMI = `android14-6.1`；
   KernelSU release 里要有 `lkm-aarch64-android14-6.1_kernelsu.ko`（官方 v3.3.1 有）。
2. **模块是「pending」的**：`ksud module install` 先落到 `/data/adb/modules_update/<id>`，
   要再跑一次 `ksud late-load` 才会搬进 `/data/adb/modules/` 并执行 stage 脚本。
3. **zygiskd 必须在模块装好之后重启**，否则它扫不到新模块（`Modules: 0`）：
   `znctl stop` → 重新跑 `ksud post-fs-data` → `znctl dump-zn` 就能看到 `modules64:1,...`。
4. **zygote 要重起一次**才能吃到注入：`ksud soft-reboot` 或杀 zygote 让 init 重拉。
5. **Vector 的 daemon 别用 `unshare --propagation`**：设备上的 toybox `unshare` 不支持该参数，
   直接 `cd /data/adb/modules/zygisk_vector && ./daemon &` 即可。
6. **宿主（Windows）下载 GitHub release**：`github.com`/`objects.githubusercontent.com` 不通，
   但 `api.github.com` 与 `release-assets.githubusercontent.com` 通 →
   `https://api.github.com/repos/<owner>/<repo>/releases/assets/<id>` + `Accept: application/octet-stream`。
   并发写同一个文件会写坏，下载要串行。
7. **宿主与本机之间传文件**：a2mcp 的 `/xfer/` 路由支持 **PUT 上传**，
   `curl -H 'Authorization: Bearer <token>' -T <file> https://<tunnel>/xfer/<name>`。

## 失效边界

- 临时 root 与 late-load 的 KernelSU 都只在**本次开机**有效；重启后回到原点。
- ZygiskNext / Vector 的模块文件在 `/data/adb/modules/` 里，但 KernelSU 没了就不会被加载。
- 设备当前是**锁屏有密码**状态（`wm dismiss-keyguard` 无效），要动 manager UI 需要用户解锁。


## Timeline

- time: 2026-10-21T03:35:20
  kind: decision
  summary: "Created this page: 从临时 root 到 LSPosed 生效的完整链路"
  source: "上游 ghostlock-app 文档 + KernelSU/LSPosed 生态常识"
  affects: [temp-root-to-lsposed]

- time: 2026-10-21T03:35:34
  kind: decision
  summary: "固化临时 root → LSPosed 的链路与失效边界"
  source: "上游 ghostlock-app 文档 + 生态常识"
  affects: [temp-root-to-lsposed]

- time: 2026-10-21T06:28:27
  kind: decision
  summary: "真机验证整条链路：临时 root → KernelSU late-load → ZygiskNext → Vector(LSPosed) 框架"
  source: "宿主机真机（2026-10-21）"
  affects: [temp-root-to-lsposed]

- time: 2026-10-21T06:28:27
  kind: evidence
  summary: "真机跑通 临时root → KernelSU late-load → ZygiskNext → Vector(LSPosed 2.2) 框架，模块管理 CLI 可用"
  source: "宿主机真机操作 + 逐段日志"
  affects: [temp-root-to-lsposed]

- time: 2026-10-21T07:05:10
  kind: note
  summary: "踩坑：杀 zygote / Vector daemon 重启 system_server 会让 Titan 2 framework 起不来 → Android rescue party → 掉进 recovery（recovery 的 adbd 关着，adb/fastboot 都够不着，只能物理重启）"
  source: "真机两轮实验（2026-10-21）"
  affects: [temp-root-to-lsposed]
