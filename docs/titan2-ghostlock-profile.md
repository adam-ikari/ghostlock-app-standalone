# Titan 2 的 GhostLock profile 移植记录

> 目标：给 **Unihertz Titan 2**（MediaTek MT6878 / Dimensity 7300，国际版 TEE 固件）
> 写一份可用的 GhostLock **kernel profile**，用来拿临时 root，再挂 KernelSU / ZygiskNext / LSPosed。
> 目标 profile 落在 `vendor/ghostlock-app/app/src/main/assets/kernel_profiles/`。

## 1. 结论摘要

| 项 | 值 | 证据 |
| --- | --- | --- |
| SoC | MediaTek **MT6878**（Dimensity 7300），工程号 `g71v78c2k` | 固件内 `MT6878_Android_scatter.xml`、DT `model = MT6878` |
| 内核家族 | **6.1**（android14 GKI），`STRUCT_OFFSETS_6_1` | extractor 判定：`kernel_family STRUCT_OFFSETS_6_1 (verified Android family)` |
| 目标 release | `6.1.145-android14-11-gbd17012cc2f7-ab14044926` | 固件 1.00.10（国际版 2025111915）内核 banner；Android 16 固件同内核 |
| 备选 release | `6.1.115-android14-11-g6b18f0b574ab-ab12901745` | 固件 1.00 / 1.00.07（2025090819、2025102716） |
| **CVE-2026-43499** | **未修补**（`remove_waiter` 仍走原逻辑） | extractor：`primitive remove_waiter@0x100a5fc unpatched`（6.1.115 为 `0xffc6bc`） |
| `kernel_phys_load` | `0x40000000` | `vendor_boot.img` 头 `kernel_addr` |
| `kernel_phys_offset`（DRAM 基址） | `0x40000000` | `vendor_boot.img` 内 DT：`/memory@40000000 reg = <0x0 0x40000000 0x0 0x40000000>` |
| route | `tcp_zerocopy`（`compact_waiter = 1`），回落 `select_stack`（`waiter_shift = 1`） | 见 §4 |

> 设备端还没接上，`uname -r` 的**最终**值要用 `adb shell uname -r` 确认；两个候选都做了 profile。

## 2. 固件从哪来（不需要设备、不需要解锁）

Unihertz 官方把固件放在 Google Drive，社区做了镜像（Seafile）：

```
https://cloud.lichtmetzger.de/d/ccc9017f47a6459d8b37/
  /Firmware/Android 15/TEE/2025111915_g71v78c2k_dfl_tee.zip   # 国际版 1.00.10
  /Firmware/Android 15/TEE/2025102716_g71v78c2k_dfl_tee.zip   # 国际版 1.00.07
  /Firmware/Android 15/TEE/2025090819_g71v78c2k_dfl_tee.zip   # 国际版 1.00
  /Firmware/Android 16/TEE/2026021022_...zip, 2026042212_...zip
```

固件包 **3.5GB**，但解 profile 只需要 `boot.img` + `vendor_boot.img`。
`scripts/remote-zip-extract.py` 用 HTTP Range 只取中央目录 + 需要的成员（几十 MB）：

```bash
./scripts/fetch-firmware.sh --list          # 列出镜像上的固件
./scripts/fetch-firmware.sh 2025111915      # 取 boot.img / vendor_boot.img 到 artifacts/
```

`TEE` = 国际版/全球版，`EEA` = 欧洲版；本项目目标机是**国际版（TEE）**。

## 3. 提取

```bash
(cd vendor/ghostlock-app/tools/extract_rs && cargo build --release)   # 需要 nightly（rust-version ≥ 1.97.1）
EX=vendor/ghostlock-app/build/extract/release/ghostlock-extract

$EX artifacts/fw-TEE-2025111915/boot.img \
   --vendor-boot artifacts/fw-TEE-2025111915/vendor_boot.img \
   --format conf --out profile.conf
```

关键输出：

```
info: vendor_boot; kernel_phys_load=0x40000000 (MiB-aligned but not provably the DRAM base; ...)
info: kallsyms layout pre-6.4 recovered (101934 symbols)
info: (CVE-2026-43499 primitive present (remove_waiter@0x100a5fc still uses current)
info: pselect chain __arm64_sys_pselect6->core_sys_select ... waiter=0x98 shift=1 (derived 3 - 2)
info: sizeof(mm_struct)=0x3C0 (MM_STRUCT_SZ=0x500 in src/core/common.h)
```

镜像格式（自己确认过，方便以后换机器）：

- `boot.img` = AOSP boot **v4** 头（`header_size=0x630`、`header_version=4`），
  内核载荷在 `align(0x630, 4096) = 0x1000`，**MTK LZ4 legacy 帧**（magic `0x184C2102`），
  解出来是 arm64 Image（`text_offset = 0`）。
- `vendor_boot.img` = `VNDRBOOT` 头 `header_version=4`，`kernel_addr = 0x40000000`；
  **DT 也在这个文件里**（偏移 `0x19ac040`），`/memory@40000000` 给出 DRAM 基址与大小。

## 4. 三个需要判断的点

### 4.1 `kernel_phys_offset`（DRAM 基址）不能只靠对齐推

extractor 的 `vendor_boot::classify` 只在 `kernel_addr & 0xFFFFF == 0x80000`（arm64 512KB 文本对齐）
时**证明** DRAM 基址；本机是 MiB 对齐（`0x40000000`），它会拒绝猜测、留 `null`。
这是对的：MiB 对齐既可能是「基址本身」，也可能是「基址上若干 MiB」。

本机的定论来自 **DT**：`vendor_boot.img` 里的 `/memory@40000000 reg = <0x0 0x40000000 0x0 0x40000000>`
→ DRAM 基址 `0x40000000`、大小 1GiB。与上游另一台 MTK 6.1 设备（Motorola Edge 60 Fusion，
`6.1.145-android14-11-g9b69cc399ae1-ab14819715`）的 `kernel_phys_load = kernel_phys_offset = 0x40000000` 一致。

真机阶段还会用 `/proc/iomem`（`tools/mtk-phys`）复核一次。

### 4.2 route：`tcp_zerocopy` 主路 + `select_stack` 回落

extractor 建议 `select_stack`（高置信度，因为它从本内核推导出 pselect/futex 栈几何：`shift=1`），
但上游 **23 个 6.1 内置 profile 全部用 `tcp_zerocopy { compact_waiter = 1 }`**，其中包含一台 MTK 设备，
且 native 的回落方向**只有** `tcp_zerocopy → select_stack`（`threads.cpp`：
"TCP route cleanly failed; used Select Stack fallback"）。

所以最终取「两边都要」：

```hocon
route   { tcp_zerocopy { compact_waiter = 1 } }
fallback { to = "select_stack", route { select_stack { waiter_shift = 1 } } }
```

`waiter_shift = 1` 是本内核 pselect 链推出来的（原始 `shift=3` 会把 waiter 顶到 fd_set 最后一个可用字，
`wake_state` 会落到拷贝范围外，extractor 因此减 2）。

### 4.3 `mm_struct_sz = 1024` 不是 BTF 的 `sizeof`

extractor 从 BTF 量到 `sizeof(mm_struct) = 0x3C0`，但 profile 里写 `1024`：
report.rs 注释说明「0x400 是设备的 SLUB stride，不是 BTF sizeof」；上游 6.1 profile 也都是 1024。保持一致。

## 5. 产出

新增 profile（已登记进 `index.conf`）：

- `6.1.145-android14-11-gbd17012cc2f7-ab14044926.conf`
- `6.1.115-android14-11-g6b18f0b574ab-ab12901745.conf`

结构体布局（`task_struct`）与上游已验证的 6.1 profile **逐字段一致**
（`cred=2104`、`pi_lock=2340`、`pi_waiters=2360`、`pi_blocked_on=2384`、`seccomp=2304`、`sched_task_group=840` …），
符号偏移（`init_task` / `init_cred` / `selinux_enforcing` …）来自本镜像内嵌 kallsyms。

## 6. 还没做完的（真机门禁）

按上游规矩，**没在真机跑通就不算「已支持」**。设备接入后按顺序做：

1. `adb shell uname -r` → 确认到底用 6.1.145 还是 6.1.115 那份 profile。
2. `adb shell getenforce`、`ro.boot.flash.locked`、`/proc/kallsyms`、`/proc/iomem` 可见性
   （`./scripts/titan2-props.sh` 一次收齐）。
3. 装 app（`./gradlew :app:installDebug`），跑一次利用，**冷机、固定 CPU 对、单 route**。
4. 看 `Download/ghostlock-debug-log/<时间>/*.log.txt` 里是否出现
   `soc: mtk; kernel_phys_load=0x40000000` 与 `phys_offset=0x40000000`，以及 W1/W2/W3 写验证。
5. 拿到 uid 0 后再走 KernelSU(ksud late-load) → ZygiskNext → LSPosed（见 brain 页 `temp-root-to-lsposed`）。

> 注意：跑利用前设备上不能有 KernelSU 补丁或已加载的 KernelSU 模块（BakaSU / ReSukiSU / KowSU 同理）。
