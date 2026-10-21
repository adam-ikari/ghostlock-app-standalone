---
id: titan2-device
title: "Titan 2 设备事实（已知与待确认）"
category: project
status: active
tags: [titan2, device, mediatek]
created: "2026-10-21T03:34:50"
updated: "2026-10-21T15:06:39"
---

<!-- compiled_truth -->
## 已确认（证据：官方固件镜像实测，2026-10-21）

来源：Unihertz 官方固件包（经社区镜像 `https://cloud.lichtmetzger.de/d/ccc9017f47a6459d8b37/`
分发的 Unihertz 官方 Google Drive 固件）。用 `scripts/remote-zip-extract.py`（HTTP Range
部分下载）取出 `boot.img` / `vendor_boot.img` 后直接解析得到。

- **SoC**：MediaTek **MT6878**（Dimensity 7300），工程号 `g71v78c2k`，平台 `MT6878`。
  固件包内 `MT6878_Android_scatter.xml` 明写 `<platform>MT6878</platform>`。
- **区域固件**：`TEE` = 国际版/全球版，`EEA` = 欧洲版。**本项目目标机跑国际版（TEE）固件**。
- **内核 release（= `uname -r` 候选，逐字符）**：
  - 固件 1.00 / 1.00.07（2025090819、2025102716）→ `6.1.115-android14-11-g6b18f0b574ab-ab12901745`
    （编译于 2025-01-10）
  - 固件 1.00.10（2025111915）→ `6.1.145-android14-11-gbd17012cc2f7-ab14044926`（编译于 2025-09-04）
  - 固件 1.00.12/1.00.14（Android 16，2026021022/2026042212）→ 与 1.00.10 **同一个内核**
    `6.1.145-android14-11-gbd17012cc2f7-ab14044926`（升 Android 16 没换内核）
  - ⇒ 目标机 `uname -r` 只有两个候选值，最终以 `adb shell uname -r` 为准。
- **内核家族 = 6.1**（不是 5.x），走 `tcp_zerocopy` route（6.1 有 `waiter_compact` 紧凑布局）。
- **内核编译时间 2025-01/2025-09**，早于 2026-07 的 CVE-2026-43499 补丁潮 → 漏洞大概率仍在，
  以 extractor 的退出码 6 判定为准（尚未跑）。
- **`boot.img` 格式**：AOSP boot **v4** 头（`header_size=0x630`、`header_version=4`），
  内核载荷在 `align(0x630,4096)=0x1000` 处，**MTK LZ4 legacy 帧**（magic `0x184C2102`），
  解开后是 arm64 Image（`text_offset=0`）。
- **`vendor_boot.img`**：`VNDRBOOT` 头、`header_version=4`，`kernel_addr = 0x40000000`
  → `kernel_phys_load = 0x40000000`。**`kernel_phys_offset`（DRAM 基址）**对齐模式不足以证明
  （MiB 对齐既可能是基址本身，也可能是基址上若干 MiB），extractor 会留 `null` → 需 `/proc/iomem`
  或 `tools/mtk-phys` 补。

## 已知（来自兄弟项目 brain 与公开资料，尚未从本机直读）

- **机型**：Unihertz Titan 2 系列——复古全键盘方屏手机（官方有 Titan 2 / Titan 2 Elite，
  2026 年起还有 Elite Pro）。本机用的是其中一款。
- **系统**：**Android 15**（兄弟项目 `hwkbd_ime` 的 brain 页 `tt2-hardware-constraints`
  明确写「Titan 2（Android 15）」）。注：官方已放出 Android 16 固件（内核不变）。
- **屏幕**：方屏，逻辑分辨率 **600×600**（兄弟项目 `titan_ir_remote` README），
  带红外发射（`ConsumerIrManager`）。
- **物理键盘**：4 行 QWERTY，首行 6 个特殊键（Shift / Sym / Back / Recent / Fn / Alt）；
  Back、Recent 会吃掉 `KEYCODE_BACK` / `KEYCODE_APP_SWITCH`；Sym 的 keycode 未知；
  Fn 需在系统里设成 Ctrl 才能被输入法捕获。
- **相关兄弟项目**：`hwkbd_ime`（物理键盘输入法）、`term-ime`（终端输入法）、
  `titan_ir_remote`（红外遥控）、`titan_dial`（电话）。这解释了本项目的动机：
  需要 Xposed 类 hook 能力来增强这些自研 app。
- **本机有配套 AVD**：`~/.android/avd/titan2.avd`，手写 config.ini 模拟 Titan 2 屏幕网格
  （`hw.lcd.width/height=1440`、`density=400` ⇒ 576dp 宽）。两个坑：AOSP Launcher3 在方形屏上
  必崩（`calculateHotseatBorderSpace` 除以零）必须 disable；事后 `wm size` 改分辨率会让系统瘫掉。

## 待确认（必须从设备本身取，见 `scripts/titan2-props.sh`）

1. **精确 `uname -r`** —— profile 的 key，逐字符敏感（候选见上）。
2. **当前固件版本号**（1.00 / 1.00.07 / 1.00.10 / Android 16 版）→ 决定上面哪个候选。
3. **bootloader 是否锁定** —— 若已解锁，Magisk patch boot 即可，本项目前提改变。
4. **SELinux 状态**（enforcing/permissive）、`kptr_restrict`、是否暴露 `/proc/config.gz` 与 BTF。
5. **MTK DRAM 基址（`kernel_phys_offset`）** —— 需 `/proc/iomem`（依赖 root）或换用
   app 内高级参数覆盖页迭代。

## 记录的边界

- 「已确认」条目全部来自**官方固件镜像内的镜像文件解析**，不是从本机设备直读；
  设备接入后用 `scripts/titan2-props.sh` 的快照补齐剩余项。


## Timeline

- time: 2026-10-21T03:34:50
  kind: decision
  summary: "Created this page: Titan 2 设备事实（已知与待确认）"
  source: "兄弟项目 hwkbd_ime 的 brain（tt2-hardware-constraints）+ 公开资料"
  affects: [titan2-device]

- time: 2026-10-21T03:35:02
  kind: decision
  summary: "固化 Titan 2 已知设备事实与待确认项"
  source: "兄弟项目 hwkbd_ime 的 brain + 公开资料 + 本项目 scripts/titan2-props.sh 待执行"
  affects: [titan2-device]

- time: 2026-10-21T04:24:25
  kind: decision
  summary: "用官方固件镜像坐实 SoC/内核版本/分区格式，替换凭兄弟项目转述的猜测"
  source: "官方固件包（社区镜像）里的 boot.img / vendor_boot.img 实测 + extractor 前置分析"
  affects: [titan2-device]

- time: 2026-10-21T04:24:25
  kind: evidence
  summary: "从官方固件包（国际版 TEE）解出 boot.img/vendor_boot.img，坐实 MT6878、6.1 内核 release 候选与 kernel_phys_load=0x40000000"
  source: "lichtmetzger.de 固件镜像 + scripts/remote-zip-extract.py + 手工解析 boot/vendot_boot 头"
  affects: [titan2-device]

- time: 2026-10-21T15:06:39
  kind: evidence
  summary: "本机 Shizuku 状态实测：Shizuku 13.6.0.r1086（官方版），ADB 模式 UID 2000，SELinux u:r:shell:s0，perm 已授权、ping 正常 —— 但 UserService 辅助进程在 MediaTek 上必崩（Shizuku#1198）。官方 13.6.0 在本机不可用，需换 thedjchi 分支"
  source: "app 侧 Shizuku 状态 dump（2026-10-21）"
  affects: [titan2-device]
