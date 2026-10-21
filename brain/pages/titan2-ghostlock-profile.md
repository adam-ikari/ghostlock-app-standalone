---
id: titan2-ghostlock-profile
title: "Titan 2 的 GhostLock profile（已生成，待真机验证）"
category: project
status: active
tags: [titan2, ghostlock, profile, mediatek]
created: "2026-10-21T04:34:00"
updated: "2026-10-21T04:49:49"
---

<!-- compiled_truth -->
## 状态

**profile 已生成并通过上游 app 单测（`BuiltinProfilesTest`：每个内置 profile 必须零 invalidPaths），
但还没在真机跑过 → 按上游规矩不能标「已支持」。**

已落地（`vendor/ghostlock-app`，工作树改动，未提交）：

- `app/src/main/assets/kernel_profiles/6.1.145-android14-11-gbd17012cc2f7-ab14044926.conf`
- `app/src/main/assets/kernel_profiles/6.1.115-android14-11-g6b18f0b574ab-ab12901745.conf`
- `index.conf` 登记了这两条
- `./gradlew :profile-core:exportKernelProfiles` 导出 72 份 GLK1 `.bin`（含这两份）

## 几何（两个 release 只有符号偏移不同）

| 字段 | 值 | 来源 |
| --- | --- | --- |
| `kernel_major` | 6 | |
| `kernel_phys_load` | `0x40000000` | `vendor_boot.img` 头 `kernel_addr` |
| `kernel_phys_offset` | `0x40000000` | `vendor_boot.img` 内 DT `/memory@40000000` |
| `task_struct.*` | 与上游已验证 6.1 profile **逐字段一致** | 6.1 家族宏 + BTF |
| `cred.*` | `copy_size=136, caps_offset=48, caps_count=5, usage_value=1, caps_value=-1` | `credential-6x.conf` |
| `kernelsnitch` | `collisions=4, mm_struct_sz=1024` | 6.1 家族（1024 = SLUB stride，不是 BTF 的 0x3C0） |
| 符号偏移 | 6.1.145：`init_task=33551872` `init_cred=33626728` `selinux_enforcing=35886048` …；6.1.115：`init_task=33355264` … | 镜像内嵌 kallsyms（101934 符号，pre-6.4 布局） |

## 漏洞原语

- 6.1.145：`remove_waiter@0x100a5fc` **未修补**（`still uses current`）
- 6.1.115：`remove_waiter@0xffc6bc` **未修补**
- → 两条 release 都有 CVE-2026-43499，路线可行。

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

## 离线获取固件的方法（可复用）

- Unihertz 官方固件在 Google Drive，社区镜像（Seafile）：
  `https://cloud.lichtmetzger.de/d/ccc9017f47a6459d8b37/`，目录 `/Firmware/Android {15,16}/{TEE,EEA}/`。
  `TEE` = 国际版，`EEA` = 欧洲版。
- 固件包 3.5GB；用 `scripts/remote-zip-extract.py`（HTTP Range，只取中央目录 + 指定成员）
  只下 `boot.img` + `vendor_boot.img`（各约 64MB）。
- **`vendor_boot.img` 里除了头还有完整 DT**（本机偏移 `0x19ac040`）→ 不用 root 就能拿到
  `/memory@40000000` 的 DRAM 基址/大小，绕开 extractor 的「MiB 对齐不足以证明 DRAM 基址」限制。


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
