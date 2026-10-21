---
slug: background
title: Project background
role: project background
updated: "2026-10-21T03:33:55"
---

# Project background

## 要解决的问题

Unihertz Titan 2（Android 15，MediaTek 平台）**没有解锁 bootloader**，所以「patch boot 镜像刷
Magisk / KernelSU」这条常规路径不可用。目标是在**不刷机**的前提下拿到**临时 root**
（内存态、重启即失效），并据此把 **LSPosed** 跑起来。

## 为什么「临时 root」还不等于「能装 LSPosed」

LSPosed 是注入 `app_process` 的框架模块，需要两样东西同时具备：

1. 一个能加载内核模块、或等价提供 Zygisk 能力的 root；
2. 一个可写的 `/data/adb/modules` 目录，且模块能被真正加载。

因此「临时 root」只是第一步，还要把 KernelSU（负责 late-load 管理模块）与 Zygisk 实现
一起在**本次开机窗口内存活**——重启后这些都会消失。

## 关键约束

- **不解锁 bootloader**：一旦解锁，Magisk 直接 patch boot 即可，本项目就没有存在意义。
- **重启即失效**：成果只在本次开机窗口有效 → 每次开机都要重跑利用链。
- **风险可控**：几何参数写错会破坏内核内存（白屏 / 重启 / 变砖），所以必须小步验证、
  每次只改一个变量。

## 目标用户

本机用户本人：日常在 Titan 2 上做输入法 / 终端 / 遥控器类小工具（兄弟项目
`hwkbd_ime`、`term-ime`、`titan_ir_remote`），需要 Xposed 类的 hook 能力来增强这些 app。

## 判定标准

- **成立**：Titan 2 上跑通 GhostLock → 拿到 root → 加载 LSPosed → 至少一个模块实际生效。
- **不成立**：内核已修补 CVE-2026-43499，或找不到稳定可用的利用路线 → 记录否定结论与替代方案
  （例如刷机解锁、等待厂商更新、或换其他临时 root 手段）。

## 非目标

- 解锁 bootloader / 刷机 / 永久 root
- 修改厂商固件
- 把利用做成通用工具（只服务 Titan 2 这一台）
