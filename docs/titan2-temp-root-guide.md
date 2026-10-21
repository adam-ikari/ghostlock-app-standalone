# Titan 2（国际版 / TEE 固件）临时 root 操作手册

> 面向**其他** Titan 2 国际版用户：怎么在不解锁 bootloader、不改内核的前提下拿到临时 root，
> 并把 KernelSU / Zygisk / LSPosed 拉起来。
>
> 本机（`TTANCN2100002663`，Titan 2_V01.00.14）已全程跑通，本文是照着这次实测写下来的。

## 0. 结论速览

| 问题 | 答案 |
| --- | --- |
| 能不能不解锁 bootloader 拿 root？ | **能**。GhostLock（CVE-2026-43499）利用，内核没打补丁时直接拿到 uid 0 |
| 会不会改内核？ | **不会**。利用全程用户态；KernelSU 是**内存里 late-load**，boot 镜像不动，重启即失效 |
| 需要什么？ | stock 内核（没被 KernelSU 改过）+ adb + 一个 KernelSU 系管理器 APK（给 `ksud`） |
| 设备要哪个版本？ | 国际版固件（TEE）。已验证的内核见下表 |
| 风险 | 利用依赖竞态，**手机会热**；跑一次重启就恢复原状 |

## 1. 先确认你的内核（profile 的 key）

`adb shell uname -r` —— 必须和 profile 的 `release` **逐字符一致**。

Titan 2 国际版已知的两个内核（对应固件）：

| 固件版本 | 固件日期（TEE） | `uname -r` |
| --- | --- | --- |
| 1.00 / 1.00.07 | 2025090819 / 2025102716 | `6.1.115-android14-11-g6b18f0b574ab-ab12901745` |
| 1.00.10 及以后（含所有 Android 16 版） | 2025111915 / 2026021022 / 2026042212 | `6.1.145-android14-11-gbd17012cc2f7-ab14044926` |

> 升到 Android 16 **不会**换内核，还是 6.1.145 那份。
> 固件版本在 `Settings → About phone → Build number` 附近能看到（形如 `Titan 2_V01.00.14`）。

如果你机器上的 `uname -r` 不在这两个里（说明厂商又发了新固件），
需要按下面第 3 节自己解一份 profile。

## 2. 路线 A：app 一键（推荐）

1. 装一个 **KernelSU 系管理器 APK**（提供 `ksud`）：
   [KernelSU](https://github.com/tiann/KernelSU)（包名 `me.weishu.kernelsu`）、
   [BakaSU / ReSukiSU](https://github.com/Baka-SU/BakaSU)、[KowSU](https://github.com/KOWX712/KernelSU) 都行。
   - 选 **v3.3.1 或更新**：release 里有 `lkm-aarch64-android14-6.1_kernelsu.ko`，正好对上 Titan 2 的 KMI。
2. 装 **「GhostLock Titan 2 版」**（本仓库构建：上游 GhostLock + Titan 2 的 profile + 运行前检查面板；
   上游正式版内置这份 profile 后就通用）。
3. 手机若装了 Shizuku，先把 Shizuku 起起来（它就是给 app 一个 shell 身份跑利用，省掉 W3 阶段，更快）。
4. 打开 app → 主页有 **「运行前检查」** 面板，逐项列出依赖与系统条件：
   内核是否受支持 / profile 是否匹配且合法 / Shizuku 是否已装、是否在运行并已授权 /
   是否装了带 `ksud` 的 KernelSU 管理器。
   **必须满足的项不满足时按钮是灰的**（app 会说明缺什么）；可选项（如 KernelSU 管理器）
   不满足时仍能跑，只是拿完 root 不会加载模块。
5. 日志（`Download/ghostlock-debug-log/<时间>/*.log.txt`）里应该看到：

   ```
   [+] resolved profile loaded: 6.1.145-android14-11-gbd17012cc2f7-ab14044926
   [*] soc: mtk; kernel_phys_load=0x40000000
   [+] p0 profile phys_offset=0x40000000 kernel_phys_load=0x40000000
   [+] SELinux permissive
   [+] child is root!
   [+] KernelSU ready
   ```

   出现 `child is root!` = 临时 root 成功；`KernelSU ready` = KernelSU 已内存加载。

6. 之后 `adb shell su -c id` 就是 `uid=0(root) context=u:r:ksu:s0`。

> app 只负责 root：LSPosed 模块由**独立的模块管理器 app**（Vector / LSPosed）安装与管理。

## 3. 路线 B：命令行（脚本化 / 无人值守）

```bash
# 1) 准备两个文件
#    ghostlock      = 上游 pre-release 的 native payload（aarch64，241400 字节）
#    profile.bin    = 该 uname -r 对应的 GLK1 profile（1955 字节）
#    本仓库里：vendor/ghostlock-app/build/native/ghostlock
#              vendor/ghostlock-app/build/kernel-profiles/<uname-r>.bin

adb push ghostlock /data/local/tmp/ghostlock
adb push profile.bin /data/local/tmp/profile.bin
adb shell chmod 755 /data/local/tmp/ghostlock

# 2) 跑（shell 用户身份，无需 root；adb shell 没有 seccomp 过滤，W3 自动跳过）
adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin
```

一次性脚本（宿主上 `C:/work/titan2/oneclick-root.sh`）就是这三条 + 等 25 秒 + 打印 `su -c id`。

**自己解一份 profile**（固件更新过、`uname -r` 不在表里时）：

```bash
# ① 取官方固件（社区镜像，免设备）
./scripts/fetch-firmware.sh 2025111915        # 国际版 TEE 固件 → artifacts/fw-TEE-2025111915/
#    里面就有 boot.img / vendor_boot.img

# ② 解偏移
./scripts/extract-profile.sh \
    artifacts/fw-TEE-2025111915/boot.img \
    artifacts/fw-TEE-2025111915/vendor_boot.img
#    → profile.conf（候选种子）+ analysis.txt

# ③ 补两个物理地址（Titan 2 固定值，DT 里读出来的）
#    kernel_phys_load   = 1073741824   # 0x40000000（vendor_boot.img 头的 kernel_addr）
#    kernel_phys_offset = 1073741824   # 0x40000000（vendor_boot.img 里 DT: /memory@40000000）
#    route { tcp_zerocopy { compact_waiter = 1 } }
#    fallback { to = "select_stack", route { select_stack { waiter_shift = 1 } } }
```

`analysis.txt` 里出现 `primitive remove_waiter@... unpatched` 才说明这个内核**还带漏洞**；
如果报「已修补」，extractor 退出码 6 → 这条路作废。

## 4. 然后：Zygisk + LSPosed（要 Xposed 框架时）

临时 root 只是起点；要 hook 自己的 app，还需要 Zygisk + Xposed 框架：

```bash
# ZygiskNext（Zygisk 的独立实现）
su -c '/data/adb/ksu/bin/ksud module install /data/local/tmp/zygisknext.zip'
# Vector v2.2（LSPosed 的继任者，Android 15/16 用它）
su -c '/data/adb/ksu/bin/ksud module install /data/local/tmp/vector.zip'
adb install -r manager.apk

# 关键：模块装完要再 late-load 一次，并重启 zygiskd 让它扫到新模块
su -c '/data/adb/ksu/bin/ksud late-load --kmi android14-6.1 --allow-shell'
su -c '/data/adb/ksu/bin/znctl stop'
su -c '/data/adb/ksu/bin/ksud post-fs-data' &

# Vector daemon（它自己负责把服务注进 system_server）
su -c 'cd /data/adb/modules/zygisk_vector && (./daemon --system-server-max-retry=3 &)'

# 管理模块
su -c '/data/adb/modules/zygisk_vector/cli modules ls'
su -c '/data/adb/modules/zygisk_vector/cli modules enable <模块包名>'
su -c '/data/adb/modules/zygisk_vector/cli scope add <模块包名> <目标包名>/0'
```

验证框架活着：

```bash
su -c '/data/adb/modules/zygisk_vector/cli status'
#   Framework Version: 2.2
#   API Version: 102
#   Enabled Modules: N
```

## 5. 踩过的坑（照着避）

1. **别杀 zygote**。`kill -9 $(pidof zygote64)` 会让 framework 起重失败，
   Android 的 rescue party 会直接把机器送进 **recovery**；而 Titan 2 的 recovery 里
   adbd 是关的 → adb/fastboot 都够不着，只能长按电源键重启回来。
   正确做法：让 Vector daemon 自己重启 system_server。
2. **模块是 pending 的**：`ksud module install` 只放到 `/data/adb/modules_update/`，
   要再 `ksud late-load` 才会搬进 `/data/adb/modules/` 并跑 stage 脚本。
3. **zygiskd 必须在模块装好之后重启**，否则它扫不到新模块（`znctl dump-zn` 显示 `Modules: 0`）。
4. **Vector 的 daemon 别用 `unshare --propagation`**：设备上的 toybox `unshare` 没这个参数，
   会直接报错；去掉 `unshare` 直接跑 `./daemon &` 就行。
5. **有 KernelSU 在就别再跑利用**：native 会主动跳过（`KernelSU already has root; skipping exploit`），
   因为被 KernelSU 改过的内核会干扰利用。要重来先卸载 KernelSU 模块。
6. **手机会热**：利用是竞态，跑之前让机器凉一点，成功率更高。
7. **GitHub 下载**（在墙内）：`github.com` 不通，用
   `https://api.github.com/repos/<owner>/<repo>/releases/assets/<id>` + `Accept: application/octet-stream`。

## 6. 失效边界（重要）

- 临时 root 与 late-load 的 KernelSU **只在本次开机有效**，重启即失效。
- `/data/adb/modules/` 里的模块文件会留下来，但 KernelSU 没了就不会被加载。
- 要长期 root 只能解锁 bootloader + 刷 boot 镜像（本项目的非目标）。

## 7. 已知未完成项

- **LSPosed 模块的 API 兼容**：Vector v2.2 把传统 Xposed API 做了重定位（包名被混淆成
  `bckAHJD.Cf.osS.jp.iM.i.*`），按标准 `de.robv.android.xposed` 编译的模块会被加载但抛
  `AbstractMethodError`（见 `apps/xposed-probe/` 与设备上 `/data/adb/lspd/log/modules_*.log`）。
  框架**注入本身是成功的**（日志有 `Loading Vector/Xposed for <pkg>`）；
  要写模块得改用新的 libxposed API（`io.github.libxposed:api:102`，Maven Central 有）。
