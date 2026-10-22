---
id: porting-blockers
title: "Titan 2 移植的阻塞点与开放问题"
category: project
status: active
tags: [titan2, ghostlock, blockers, open-questions]
created: "2026-10-21T03:35:43"
updated: "2026-10-22T18:14:14"
---

<!-- compiled_truth -->
## 阻塞点（按「一旦发生则整条路线作废」的严重度排序）

### B1 内核已修补 CVE-2026-43499 —— **已排除**

- **判据**：extractor 读到已修补的 `remove_waiter()` → **退出码 6**；或内核构建时间晚于补丁合入。
- **实测**：两个候选内核（6.1.145 / 6.1.115，国际版 TEE 固件）都报
  `primitive remove_waiter@... unpatched` → 漏洞仍在，路线成立。
- **回退**：记录内核版本与补丁证据；改走其他临时 root 手段。**不要**在没有判据的情况下反复试。

### B2 拿不到 `boot.img` —— **已排除**

- **判据**：`fastboot fetch boot` 报未知命令，或官方固件包里没有 boot 镜像。
- **实测**：Unihertz 官方固件包（社区镜像 `https://cloud.lichtmetzger.de/d/ccc9017f47a6459d8b37/`，
  `/Firmware/Android {15,16}/{TEE,EEA}/`）里就有 `boot.img` / `vendor_boot.img`；
  用 `scripts/remote-zip-extract.py`（HTTP Range）只下这两个成员（各 64MB），不用整包 3.5GB。
- **回退**：① `fastboot fetch`；② 任意 root 后 dump；③ `--kallsyms` + 手工补布局。

### B3 MTK 物理地址推不出 —— **已排除**

- **判据**：profile 里 `kernel_phys_load` / `kernel_phys_offset` 为 null，或 W1 反复重试到上限后
  报 `Write 1 failed`。
- **实测**：`vendor_boot.img` 头 `kernel_addr = 0x40000000` → `kernel_phys_load`；
  **同一个 `vendor_boot.img` 里带完整 DT**，`/memory@40000000 reg = <0 0x40000000 0 0x40000000>`
  → `kernel_phys_offset = 0x40000000`（DRAM 基址）。不需要 root、不需要 `/proc/iomem`。
- **回退**：app「高级参数覆盖」页手动试值；利用成功后读 `/proc/iomem` 复核。

### B4 路线与内核家族不匹配 —— 仍是开放项（真机阶段）

- **判据**：利用失败 / 内核 panic / 5.x 与 6.x 的 `task_struct` 布局混用。
- **回退**：换 route（`multicast_waiter` ↔ `tcp_zerocopy` ↔ `select_stack`），或换模板重新提几何。
- **当前配置**：内核是 6.1 → `task_struct` 用 6.1 布局（与上游已验证 6.1 profile 逐字段一致）；
  主 route `tcp_zerocopy { compact_waiter = 1 }`（上游 23 个 6.1 profile 全用它，含一台 MTK 设备），
  回落 `select_stack { waiter_shift = 1 }`（本内核 pselect 链推导值）。

### B5 竞态不稳定 —— 仍是开放项（真机阶段）

- **判据**：同一环境连续尝试成功率过低。
- **回退**：降尝试次数、固定大核、降温重试；参考上游 `execution` 预设与各机型 profile 的调优。

### B6 内核没有 BTF —— **已排除**

- **判据**：`/sys/kernel/btf/vmlinux` 不存在（设备端一次判断），或 extractor 输出里
  `task_struct` / `cred` 全是 null。
- **实测**：Titan 2 的 `boot.img` **带 BTF**（extractor 解析出 `rt_mutex_waiter` 布局与
  `sizeof(mm_struct)=0x3C0`），且 6.1 家族宏（`STRUCT_OFFSETS_6_1`）兜底 → 结构体布局可用。
- **回退**（仍保留）：找 MTK 公开内核源码自行算布局；或按上游计划做机器码反推。

## 当前进展（2026-10-21）

- ✅ 上游主线已 vendored 为 submodule：`vendor/ghostlock-app`（pin `d2ab286e`）；
  Titan 2 的两份 profile 落在本地分支 `feat/titan2-profile`（`0b9438f`）。
- ✅ 设备事实（离线，从官方固件实测）：SoC = **MT6878**（Dimensity 7300），国际版 = `TEE`，
  内核 = **6.1**，两个候选 `uname -r`：
  `6.1.145-android14-11-gbd17012cc2f7-ab14044926`（固件 ≥1.00.10，含 Android 16 版）、
  `6.1.115-android14-11-g6b18f0b574ab-ab12901745`（固件 1.00 / 1.00.07）。
- ✅ 两份 profile 已生成、已登记 `index.conf`、通过上游 `BuiltinProfilesTest`（零 invalidPaths）、
  导出成 GLK1 `.bin`，并打进 APK（`build/app/outputs/apk/debug/GhostLock-v1.2(581)-arm64-v8a-debug.apk`）。
- ✅ 本机构建环境：Rust stable 1.95 + **nightly-2026-08-20**（extractor 要求 rustc ≥ 1.97.1）、
  **ONDK r30.1**（native/APK 构建，见页 `local-toolchain-gotchas`）、SDK platform 31/34/35/36/37.2、
  JDK 21、adb/fastboot。
- ⏳ **设备仍未接入**：`adb devices` 为空 → 设备端事实（真实 `uname -r`、bootloader 锁定状态、
  SELinux、kallsyms/iomem 可见性）待采集，利用未跑。

## 需要用户配合的事

1. **把 Titan 2 接上 adb**（USB 调试），然后跑 `./scripts/titan2-props.sh`（只读）。
2. 确认**机型变体**（Titan 2 / Titan 2 Elite / Elite Pro）与**是否接受进 fastboot 模式**取镜像。
3. 确认 **bootloader 是否已解锁**——若已解锁，Magisk patch boot 是更短的路，本项目价值下降。
4. 跑利用前确认设备上没有 KernelSU 补丁 / 已加载的 KernelSU 模块（BakaSU / ReSukiSU / KowSU 同理）。


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

- time: 2026-10-21T03:37:13
  kind: decision
  summary: "补充 B6：MTK 无 BTF 内核导致结构体几何无法自动推导"
  source: "上游 docs/analysis/extractor-no-btf-struct-derivation-plan.md（2026-09-30，状态：planned, not implemented）"
  affects: [porting-blockers]

- time: 2026-10-21T04:50:22
  kind: decision
  summary: "更新进展：三条主要阻塞点已解除，只剩真机验证"
  source: "官方固件实测 + extractor 输出 + 构建产物"
  affects: [porting-blockers]

- time: 2026-10-21T04:50:22
  kind: reversal
  summary: "B1/B2/B3/B6 四条阻塞点全部解除（内核未修补、固件可得、MTK 物理地址已定、BTF 存在），只剩真机验证"
  source: "官方固件实测 + extractor 输出"
  affects: [porting-blockers]

- time: 2026-10-21T10:29:05
  kind: evidence
  summary: "B4 路线在本机不再阻塞：tcp_zerocopy 一次命中；LSPosed 栈也已点亮（Vector 2.2 / API 102 / Enabled Modules 1）"
  source: "真机验证"
  affects: [porting-blockers]

- time: 2026-10-21T15:06:25
  kind: evidence
  summary: "B7（新增）：官方 Shizuku 13.6.0 在本机（MediaTek）上 UserService 起不来 —— 上游 issue RikkaApps/Shizuku#1198 的复现设备就是 Unihertz Titan 2 / Android 16 / Shizuku 13.6.0 / UID 2000。OEM 补丁让 makeApplicationInner() 解引用为 null 的 Application.getProcessName() → 辅助进程 NPE 崩溃，bindUserService 永不回调。app 侧已改：超时 3s→10s、超时不再 remove=true 杀进程、UserService 不可用时自动回退直连路径；Shizuku 侧换 thedjchi 分支"
  source: "app 日志 + 上游 issue #1198"
  affects: [porting-blockers, titan2-device]

- time: 2026-10-22T00:38:43
  kind: reversal
  summary: "B8（新增并解除）：v1.2(586) 内置的 native（479cf57）跑一键 root 把设备带进 recovery —— 根因是该版 root 脚本相对真机验证过的手写流程有四处偏离（多一次 late-load、rm -rf 放进循环、没走 su 域、路径没引号），且「等 60 秒再起 Vector daemon」的保护被这些偏离破坏。教训：review-2026-10-21.md 已写明「同步后要重跑门禁」，我跳过门禁就发了版。已修：v1.2(587) 逐条对齐手写流程 + LSPosed 段改为仅在用户选了组件包时才执行"
  source: "真机事故 + 桩环境全流程验证"
  affects: [porting-blockers]

- time: 2026-10-22T08:33:16
  kind: evidence
  summary: "设备重启取证：boot reason 是用户态 reboot 系统调用（reboot,userrequested,recovery），pstore 现场 kernel_restart←__arm64_sys_reboot；另有一次真 oops 落在 __secure_computing（MTK BUG 断言）"
  source: "pstore /sys/fs/pstore/console-ramoops-0 + getprop persist.sys.boot.reason.history（2026-10-22）"
  affects: [porting-blockers, temp-root-to-lsposed]

- time: 2026-10-22T17:48:21
  kind: reversal
  summary: "B9（新增）：app 路线的 root child 落在 u:r:kernel:s0，不是能做事的域。利用把 cred 换成 init_cred 后上下文就是 kernel；enforcing 下 kernel 域连 /system/bin/sh 都 exec 不了（dmesg: exec sh: Permission denied），su 也卡死（脚本阻塞在 pipe_read）。而策略重载 load_policy 与 ksud late-load 都会把 SELinux 从 permissive 拉回 enforcing —— 所以 app 路线在 late-load 之后必然做不完模块安装。对照：su 从 shell 域正常（adb shell su -c id → u:r:ksu:s0），CLI 路线之所以成立就是因为脚本由 adb shell 进程执行并 su 进 ksu 域。之前把 app 路线两次「掉线」归因成内核 oops 是错的：本次 pstore 无 Oops，实际是 [ -x /system/bin/su ] 的 stat 卡在 magic mount 上导致 adb 无响应"
  source: "dmesg.txt scontext=u:r:kernel:s0（pid 17443/25426）+ files/ghostlock-ksu-1792661397218.log + 宿主 adb shell su 对照；artifacts/2026-10-22-device-dump"
  affects: [porting-blockers, temp-root-to-lsposed]

- time: 2026-10-22T17:54:21
  kind: decision
  summary: "B9 的处置已定：先跑 P1（把需要 exec 的步骤放进 permissive 窗口，late-load 挪到最后），不先动攻击关键路径。当前对外口径是「CLI 路线可用、app 路线在修」，见 [[app-route-decisions]]"
  source: brain/app-route-decisions.md
  affects: [porting-blockers]

- time: 2026-10-22T18:14:14
  kind: evidence
  summary: "无 PC 交付通道已通：手机浏览器可直接从隧道 https://essentials-compiler-email-asin.trycloudflare.com/ 下 APK（593 试验版），日志回传走 /upload.html（本机 127.0.0.1:8477 上的 dist-server.py，POST /upload 存到 dist/uploads/）。往返各验证过一次：GET 593 返回 200，POST 一个文件后在 dist/uploads/ 出现"
  source: "本机验证 2026-10-22"
  affects: [app-route-decisions]
