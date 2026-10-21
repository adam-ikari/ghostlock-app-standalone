---
id: porting-blockers
title: "Titan 2 移植的阻塞点与开放问题"
category: project
status: active
tags: [titan2, ghostlock, blockers, open-questions]
created: "2026-10-21T03:35:43"
updated: "2026-10-21T03:35:55"
---

<!-- compiled_truth -->
## 阻塞点（按「一旦发生则整条路线作废」的严重度排序）

### B1 内核已修补 CVE-2026-43499

- **判据**：extractor 读到已修补的 `remove_waiter()` → **退出码 6**；或内核版本发布日期晚于补丁合入。
- **后果**：漏洞不存在 → GhostLock 路线作废。
- **回退**：记录内核版本与补丁证据；改走其他临时 root 手段（等待/寻找其他利用、解锁刷机、厂商更新后的
  另一条路）。**不要**在没有判据的情况下反复试。

### B2 拿不到 `boot.img`

- **判据**：`fastboot fetch boot` 报未知命令，或官方固件包里没有 boot 镜像。
- **原因**：profile 的符号偏移与结构体布局都来自镜像（内嵌 kallsyms / BTF）；设备上 `/proc/kallsyms`
  与 `/proc/iomem` 需要 root → 鸡生蛋。
- **回退**：① 官方 Unihertz 固件包；② 任何其他临时 root 手段先拿到 root 再 dump；③ 只做
  `--kallsyms` + 手工补结构体布局的路子（工作量最大）。

### B3 MTK 物理地址推不出

- **判据**：profile 里 `kernel_phys_load` / `kernel_phys_offset` 为 null，或 W1 反复重试到上限后
  报 `Write 1 failed`。
- **原因**：DRAM 基址随 SoC/内存布局变（高通常见 `0x80000000`，MTK 可能是 `0x40000000`）；
  `boot.img` 单独给不出 DRAM 基址。
- **回退**：app「高级参数覆盖」页手动试值；利用成功后读 `/proc/iomem` 反推；`vendor_boot.img` 头对齐校验。

### B4 路线与内核家族不匹配

- **判据**：利用失败 / 内核 panic / 5.x 与 6.x 结构体布局混用（`task_struct` 偏移明显不同）。
- **回退**：换 route（`multicast_waiter` ↔ `tcp_zerocopy` ↔ `select_stack`），或换模板重新提几何。

### B5 竞态不稳定

- **判据**：同一环境连续尝试成功率过低。
- **回退**：降尝试次数、固定大核、降温重试；参考上游 `execution` 预设与各机型 profile 的调优。

## 当前进展（2026-10-21）

- ✅ 上游主线已 vendored 为 submodule：`vendor/ghostlock-app`（pin `d2ab286e`）。
- ✅ 本机工具链齐备：Rust stable 1.95 + **nightly-2026-08-20**（extractor 要求 rustc ≥ 1.97.1）、
  NDK 26.1/27.1、SDK platform 31/34/35/36/**37.2**（app compileSdk 37.2）、JDK 21、adb/fastboot。
- ✅ 采集脚本就位：`scripts/titan2-props.sh`（阶段 0）、`scripts/fetch-partitions.sh`（阶段 1）。
- ⏳ **设备未接入**：`adb devices` 为空 → 阶段 0 未开始，`uname -r` 未知。
- ⏳ 提取器与 APK 构建在本机后台进行中（验证工具链，不产出 Titan 2 专用件）。

## 需要用户配合的事

1. **把 Titan 2 接上 adb**（USB 调试），然后跑 `./scripts/titan2-props.sh`。
2. 确认**机型变体**（Titan 2 / Titan 2 Elite / Elite Pro）与**是否接受进 fastboot 模式**取镜像。
3. 确认 **bootloader 是否已解锁**——若已解锁，Magisk patch boot 是更短的路，本项目价值下降。


## Timeline

- time: 2026-10-21T03:35:43
  kind: decision
  summary: "Created this page: Titan 2 移植的阻塞点与开放问题"
  source: "本项目分析（2026-10-21）"
  affects: [porting-blockers]

- time: 2026-10-21T03:35:55
  kind: decision
  summary: "记录阻塞点、判据与对应回退方案"
  source: "本项目分析（2026-10-21）"
  affects: [porting-blockers]
