---
id: titan2-device
title: "Titan 2 设备事实（已知与待确认）"
category: project
status: active
tags: [titan2, device, mediatek]
created: "2026-10-21T03:34:50"
updated: "2026-10-21T03:35:02"
---

<!-- compiled_truth -->
## 已知（证据较强）

- **机型**：Unihertz Titan 2 系列——复古全键盘方屏手机（Unihertz 官方有 Titan 2 / Titan 2 Elite
  两个版本，2026 年起还有 Elite Pro）。本机用的是其中一款。
- **系统**：**Android 15**（来源：兄弟项目 `hwkbd_ime` 的 brain 页 `tt2-hardware-constraints`
  明确写「Titan 2（Android 15）」，且记录了 Android 15 窗口接口变化）。
- **屏幕**：方屏，逻辑分辨率 **600×600**（来源：兄弟项目 `titan_ir_remote` 的 README
  「适配600x600逻辑分辨率屏幕的红外遥控器应用」），带红外发射（`ConsumerIrManager`）。
- **平台**：**MediaTek**（来源：兄弟项目 `hwkbd_ime` 的 brain 提到系统里的
  `MTK A85ShortcutFunction`；A85 平台名对应 mt6893 / Dimensity 1200 一系）。
- **物理键盘**：4 行 QWERTY，首行 6 个特殊键（Shift / Sym / Back / Recent / Fn / Alt）；
  Back、Recent 会吃掉 `KEYCODE_BACK` / `KEYCODE_APP_SWITCH`；Sym 的 keycode 未知；
  Fn 需在系统里设成 Ctrl 才能被输入法捕获。
- **相关兄弟项目**：`hwkbd_ime`（物理键盘输入法）、`term-ime`（终端输入法）、
  `titan_ir_remote`（红外遥控）、`titan_dial`（电话）。这解释了本项目的动机：
  需要 Xposed 类 hook 能力来增强这些自研 app。
- **本机有配套 AVD**：`~/.android/avd/titan2.avd`，是**手写 config.ini** 模拟 Titan 2 屏幕网格
  （`hw.lcd.width/height=1440`、`density=400` ⇒ 576dp 宽）。两个坑（来自 `hwkbd_ime` 的 brain）：
  AOSP Launcher3 在正方形屏上必崩（`calculateHotseatBorderSpace` 除以零）必须 disable；
  事后用 `wm size` 改分辨率会让系统瘫掉、Activity 起不来。

## 待确认（必须从设备本身取，见 `scripts/titan2-props.sh`）

1. **精确 `uname -r`** —— profile 的 key，逐字符敏感。
2. **具体机型变体与 SoC**（Titan 2 / Titan 2 Elite / Elite Pro；Dimensity 7300/7400/8400 或 1200），
   以及 `ro.board.platform`、`ro.hardware`。
3. **内核家族（5.x 还是 6.x）** —— 决定用哪条 route、哪份模板、结构体布局从哪来。
4. **bootloader 是否锁定** —— 若已解锁，Magisk patch boot 即可，本项目的前提改变。
5. **内核是否已修补 CVE-2026-43499** —— 由 extractor 直接判定（已修补则退出码 6）。
6. **MTK DRAM 基址（`kernel_phys_offset`）** 与内核物理加载地址。
7. SELinux 状态（enforcing/permissive）、`kptr_restrict`、是否暴露 `/proc/config.gz` 与 BTF。

## 记录的边界

- 本页所有「已知」条目都来自兄弟项目的 brain 与公开资料，**不是**从本机设备直接读到的；
  设备接入后用 `scripts/titan2-props.sh` 的快照覆盖本页，并按 [[porting-blockers]] 推进。


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
