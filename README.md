# titan2_temp_root — Unihertz Titan 2 临时 root / LSPosed 可行性调查

## 目标

调查 **Unihertz Titan 2**（Android 15，MediaTek 平台）能否在**不解锁 bootloader** 的前提下取得
**临时 root**（内存态，重启即失效），并据此安装、运行 **LSPosed** 模块。

非目标：解锁 bootloader、刷机、永久 root（KernelSU 刷入 boot 镜像那套）。

## 当前思路

用户提出：**让 GhostLock 支持 Titan 2** —— 即为 Titan 2 的内核 `uname -r` 增加一份
GhostLock *kernel profile*。

这条思路成立的前提是三件事，逐条验证中：

1. Titan 2 当前内核**未修补** CVE-2026-43499（extractor 会对已修补内核直接报错退出）；
2. 能取到该内核对应的 `boot.img` / `vendor_boot.img`（解 profile 的输入）；
3. 物理地址（`kernel_phys_load` / `kernel_phys_offset`）能确定（MTK 与高通不同，DRAM 基址也不同）。

## GhostLock 是什么

- **CVE-2026-43499**：Linux 内核 futex 的 use-after-free，自 2011 年起存在于几乎所有内核发行版；
  未打补丁的本地用户可提权到 root。补丁于 2026 年 7 月前后进入各发行版。
- 上游已有可跑的实现，本仓库 vendored 主线是
  [`YuKongA/ghostlock-app`](https://github.com/YuKongA/ghostlock-app)（app 路线，不需 shell 权限，
  内核 profile 用 HOCON 描述，配套 Rust 偏移提取器）。
- **GhostLock 不是通用 root**：它需要该内核的精确几何参数（符号偏移、结构体布局、物理加载地址、
  waiter 布局）。「支持一个设备」= 为其 `uname -r` 写一份 profile 并在真机验证通过。
- 参考实现：
  - [`wxxsfxyzm/ghostlock-galaxy`](https://github.com/wxxsfxyzm/ghostlock-galaxy)（三星，shell/Shizuku 路线，C 语言 offsets 表）
  - [`PeronGH/ghostlock-selinux-disabler`](https://github.com/PeronGH/ghostlock-selinux-disabler)（只关 SELinux）
  - NebuSec/CyberMeowfia、x-spy/CVE-2026-43499-popsicle、JoinChang/ghostlock-oneplus（上游血统）

## 路线图

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| 0 | 取设备事实：`uname -r` / SoC / bootloader 锁定状态 / SELinux / kallsyms 可见性 | ⏳ 待设备接入 |
| 1 | 取 `boot.img` + `vendor_boot.img`（`fastboot fetch`，只读不解锁） | ⏳ 待阶段 0 |
| 2 | 用 Rust extractor 生成 profile 骨架，校验字段完整性 | ⏳ |
| 3 | 补齐物理地址与结构体布局，真机跑通利用 | ⏳ |
| 4 | root 后加载 KernelSU（`ksud` late-load）→ ZygiskNext → LSPosed | ⏳ |
| 5 | 固化：profile 落到 `vendor/ghostlock-app` 并登记 `index.conf`，写复现文档 | ⏳ |

关键风险点（按可能性排序）：

1. **内核已打补丁** → 漏洞不存在，整条路线作废，只能换别的临时 root 手段。
2. **拿不到 `boot.img`** → 无法生成 profile（绕法：fastboot fetch / 官方固件包 / 先取得任意 root）。
3. **MTK 物理地址推导不出** → 需要真机 `/proc/iomem`（依赖 root），或换用 app 内高级参数覆盖页迭代。
4. **真机稳定性** → 利用依赖竞态，MTK 小核/大核调度差异可能让成功率偏低，需要调 `execution` 参数。

## 目录

```
scripts/titan2-props.sh        # 阶段 0：一次性收集设备事实（只读）
scripts/fetch-partitions.sh     # 阶段 1：fastboot fetch boot / vendor_boot
vendor/ghostlock-app/          # 上游主线（git submodule，pin 在 d2ab286e）
docs/                          # 本项目过程记录：profile、验证日志、结论
artifacts/                     # props 快照、boot.img 等大文件（不进 git）
```

## 本机工具链（已确认可用）

- Rust `1.95.0`（stable）+ `nightly-2026-08-20`（extractor 的 `rust-version` 要求 ≥ 1.97.1，需用 nightly）
- Android SDK `/home/gem/android-sdk`：platforms 31/34/35/36（+ 37.2 待装）、build-tools、NDK 26.1 / 27.1
- JDK 21、Python 3.10、adb / fastboot
- extractor 需要 Rust，native payload 需要 NDK

## 复现步骤（阶段 0）

```bash
adb devices                      # 确认设备在线
./scripts/titan2-props.sh | tee artifacts/props-$(date +%F).txt
```

然后把 `uname -r` 那一行填进阶段 2 的 profile 文件名；**release 字符串必须逐字符一致**。
