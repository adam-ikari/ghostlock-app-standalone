---
slug: stack
title: Tech stack
role: tech-stack choices
updated: "2026-10-21T03:33:55"
---

# Tech stack

| 领域 | 候选 | 选定 | 理由 |
| --- | --- | --- | --- |
| 利用实现 | `YuKongA/ghostlock-app` / `wxxsfxyzm/ghostlock-galaxy` / `PeronGH/ghostlock-selinux-disabler` / `NebuSec/CyberMeowfia` | **ghostlock-app**（vendored 为 submodule） | profile 是 HOCON（文本可审阅、可在 app 内覆盖迭代），配套 Rust 偏移提取器原生支持 MediaTek 的 `vendor_boot.img` 物理地址推导；galaxy 路线是三星专用的 C 偏移表 + Qualcomm `xbl_config.img` |
| 偏移提取 | 上游 `tools/extract_rs`（Rust）/ `tools/extract_target.py`（Python，仅高通） | **extract_rs** | Python 脚本绑死高通 xbl_config；Rust 版覆盖 boot/OTA/URL + `--vendor-boot` + `--iomem` |
| 偏移来源 | boot.img 内嵌 kallsyms / `--kallsyms` / OTA zip | **boot.img 内嵌 kallsyms** | 设备上没有 root，拿不到 `/proc/kallsyms`，只能从镜像里恢复 |
| 物理地址 | `vendor_boot.img` 头（MTK）/ `/proc/iomem`（需 root）/ 公式回退 | **`vendor_boot.img` 优先** | MTK 的 DRAM 基址与高通不同（高通 0x80000000 常见，MTK 可能是 0x40000000）；`/proc/iomem` 要 root，拿不到时用 app 内高级参数覆盖页迭代 |
| root 框架 | KernelSU / Magisk / ReSukiSU / BakaSU | **KernelSU（ksud late-load）** | ghostlock-app 直接加载 KernelSU；late-load 模式适配「内存态 root」，重启后失效正好符合临时 root 语义 |
| Zygisk | ZygiskNext（KernelSU 侧模块）/ Magisk 内置 | **ZygiskNext** | KernelSU 不自带 Zygisk；LSPosed 系（ReLSPosed / JingMatrix）需要 Zygisk |
| Hook 框架 | LSPosed 官方（已归档）/ ReLSPosed（JingMatrix） / LSPosed_mod | **ReLSPosed 系** | 官方 LSPosed 停在旧 Android；ReLSPosed 维护 Android 15 支持，需 Zygisk |
| 设备操作 | adb / fastboot | **adb + fastboot** | fastboot `fetch` 只读分区，可在**不解锁**的前提下取 `boot.img` / `vendor_boot.img` |
| 本机工具链 | Rust nightly / NDK 27 / AGP 9.1 / JDK 21 | **Rust nightly-2026-08-20 + NDK 27.1 + JDK 21** | extractor 的 `rust-version ≥ 1.97.1` 高于本机 stable 1.95.0，必须用 nightly |

## 开放项

- Titan 2 内核属于 5.x 还是 6.x 家族尚未确认 → 决定用 `multicast_waiter` 还是
  `tcp_zerocopy` / `select_stack` 路线，以及用哪份 profile 模板。
- `kernel_phys_offset`（DRAM 基址）到底是多少，要等 `vendor_boot.img` 或真机 `/proc/iomem`。
