---
id: titan2-ghostlock-profile
title: "Titan 2 的 GhostLock profile（已生成，待真机验证）"
category: project
status: active
tags: [titan2, ghostlock, profile, mediatek]
created: "2026-10-21T04:34:00"
updated: "2026-10-21T06:02:57"
---

<!-- compiled_truth -->
## 状态：**真机已验证**（2026-10-21，宿主机的 Titan 2）

临时 root 拿到了，profile 被设备精确匹配。剩下的是「临时 root → LSPosed」那一段
（KernelSU late-load + ZygiskNext + Xposed 框架）。

## 真机事实

| 项 | 值 |
| --- | --- |
| 设备 | `TTANCN2100002663`，Unihertz **Titan 2**，`Titan 2_V01.00.14`（Android **16**，BP2A.250605.031.A3） |
| `uname -r` | `6.1.145-android14-11-gbd17012cc2f7-ab14044926` → 命中本项目 profile（逐字符一致） |
| bootloader | `ro.boot.flash.locked=1`、`verifiedbootstate=green`（**全程锁定，没刷任何东西**） |
| SELinux | 利用前 `Enforcing`；利用中被置 permissive，随后 policy 修复成功并恢复 enforcing |
| 预先状态 | 无 root（无 `su`、`/data/adb` 不可读），只有 Shizuku |
| SoC | mt6878 / MT6878（Dimensity 7300） |

## 跑的东西

- native payload：上游 pre-release 的 `ghostlock`（241,400 B，sha256 `6890fd2a…`）
  —— 与**本项目本地构建 + strip 的产物 sha256 完全一致**（构建可复现）。
- profile：本项目导出的 GLK1 `.bin`（1,955 B，sha256 `3b7a1916…`）。
- 命令：`adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin`

## 结果（第一次运行即成功）

- route `tcp_zerocopy` 一次命中：`tcp route done=1 calls=1 success=1 status=0 clean=1/1`，没回落到 `select_stack`；
- `[+] SELinux permissive`（W1 写成功）→ `[+] child is root!`（W2 写 cred 成功，child uid=0）；
- adb shell 无 seccomp → W3 按预期跳过；
- `init_cred` direct-map 地址 `ffffff8002011a68` = 镜像偏移 `0x2011a68` | `0xffffff8000000000`（MTK linear map）；
- 利用自己抓到的 `/proc/iomem`：`40000000-4807ffff : System RAM`、`40010000-41bbffff : Kernel code`
  → **DRAM 基址 0x40000000**，独立验证 `kernel_phys_load = kernel_phys_offset = 0x40000000`；
- `policy fixup rc=0`（SELinux 恢复 enforcing）；
- 临时 root 由 `root_child` 前端持有（native 存活期间有效），并把 `/proc/iomem` 缓存到
  `/data/local/tmp/.ghostlock_iomem`；
- 设备上没有 KernelSU 系管理器 → `ksud missing; cannot late-load` → `KernelSU module load pending`。

## 几何（两个 release 只有符号偏移不同）

| 字段 | 值 | 来源 |
| --- | --- | --- |
| `kernel_major` | 6 | |
| `kernel_phys_load` | `0x40000000` | `vendor_boot.img` 头 `kernel_addr`（真机 iomem 已复核） |
| `kernel_phys_offset` | `0x40000000` | `vendor_boot.img` 内 DT `/memory@40000000`（真机 iomem 已复核） |
| `task_struct.*` | 与上游已验证 6.1 profile **逐字段一致** | 6.1 家族宏 + BTF |
| `cred.*` | `copy_size=136, caps_offset=48, caps_count=5, usage_value=1, caps_value=-1` | `credential-6x.conf` |
| `kernelsnitch` | `collisions=4, mm_struct_sz=1024` | 6.1 家族（1024 = SLUB stride，不是 BTF 的 0x3C0） |
| 符号偏移 | 6.1.145：`init_task=33551872` `init_cred=33626728` `selinux_enforcing=35886048` … | 镜像内嵌 kallsyms（101934 符号，pre-6.4 布局） |

## 漏洞原语

- 6.1.145：`remove_waiter@0x100a5fc` **未修补**（真机跑通已实证）
- 6.1.115：`remove_waiter@0xffc6bc` 未修补（未上机验证）

## route 决策（与 extractor 的建议不同，理由记录在此）

extractor 建议 `select_stack`（高置信度：本内核 pselect/futex 栈几何可推导，
`shift=1`，原始 `shift=3` 会让 waiter 顶到 fd_set 末字、`wake_state` 越界，extractor 自动减 2）。
但最终取：

```hocon
route    { tcp_zerocopy { compact_waiter = 1 } }
fallback { to = "select_stack", route { select_stack { waiter_shift = 1 } } }
```

理由：
1. 上游 **23 个 6.1 内置 profile 全部**用 `tcp_zerocopy { compact_waiter = 1 }`，其中有 MTK 设备，
   已真机验证；
2. native 的回落方向**只有** `tcp_zerocopy → select_stack`（`src/core/race/threads.cpp`：
   "TCP route cleanly failed; used Select Stack fallback"），select_stack 当主路就没有回落；
3. 两条路都用上：主路走成熟路径，失败才回落到本内核推导出的 select_stack。
   → 真机结果：主路一次命中，回落没用上。

## 下一步（临时 root → Xposed）

1. 装 KernelSU 系管理器 APK（官方 KernelSU v3.3.1 即可，其 release 里有
   `lkm-aarch64-android14-6.1_kernelsu.ko`，正好对上本机 KMI `android14-6.1`）；
2. 重跑利用 → root 脚本执行 `ksud late-load --kmi android14-6.1 --allow-shell`
   → 内存加载 KernelSU，shell 用户获得 root；
3. 装 ZygiskNext（`Dr-TSNG/ZygiskNext`）+ Xposed 框架（Android 16 用 `JingMatrix/Vector`，LSPosed 改名）
   到 `/data/adb/modules`；
4. 重新 `ksud late-load` / `ksud soft-reboot` 让模块 stage 执行、Zygisk 注入生效。

## 离线获取固件的方法（可复用）

- Unihertz 官方固件在 Google Drive，社区镜像（Seafile）：
  `https://cloud.lichtmetzger.de/d/ccc9017f47a6459d8b37/`，目录 `/Firmware/Android {15,16}/{TEE,EEA}/`。
  `TEE` = 国际版，`EEA` = 欧洲版。
- 固件包 3.5GB；用 `scripts/remote-zip-extract.py`（HTTP Range，只取中央目录 + 指定成员）
  只下 `boot.img` + `vendor_boot.img`（各约 64MB）。
- **`vendor_boot.img` 里除了头还有完整 DT**（本机偏移 `0x19ac040`）→ 不用 root 就能拿到
  `/memory@40000000` 的 DRAM 基址/大小，绕开 extractor 的「MiB 对齐不足以证明 DRAM 基址」限制。
- 宿主（Windows，GitHub 被墙）下载 GitHub release 资产的可用姿势：
  `api.github.com/repos/<owner>/<repo>/releases/assets/<id>` + `Accept: application/octet-stream`
  （`github.com` 与 `objects.githubusercontent.com` 不通，`api.github.com` 与
  `release-assets.githubusercontent.com` 通）。


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
