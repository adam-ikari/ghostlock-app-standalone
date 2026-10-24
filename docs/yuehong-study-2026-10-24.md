# 月虹提权助手（YueHong）研究 —— 2026-10-24

> 研究对象：`roro.stellar.yuehong` v1.5.8（versionCode 158），开源快照
> [`qq2070006042-create/yuehong-privilege-assistant`](https://github.com/qq2070006042-create/yuehong-privilege-assistant)
> 提交 `2a7d8ba`（2026-10-15），281 star。本文所有结论均可在该仓库对应文件里复核；
> 涉及真机的部分标为「未验证」。

## 0. 结论摘要

| 问题 | 结论 | 判据 |
| --- | --- | --- |
| 它是什么 | 一个 APK 装下三条临时提权链：GhostLock、Stellar（内置无线调试自激活）、DirtyFrag | `README.md`；`assistant/`、`manager/`、`server/` 三个模块 |
| 与我们同源吗 | GhostLock 部分同源（YuKongA/ghostlock-app，Apache-2.0），但是**较旧的 C 版**；我们的 `vendor/ghostlock-app` 已重构成 C++ 且多出 `kernel_phys_offset` | `third_party/ghostlock/src/core/main.c` vs 我们 `vendor/.../src/core/memory/address_space.cpp` |
| 开箱支持 Titan 2 吗 | **不**。内置 50 个内核 release，没有 `6.1.145-android14-11-gbd17012cc2f7-ab14044926` | `assistant/.../ghostlock/SupportedKernels.java` |
| 能靠导入 `offsets.json` 补上吗 | 能补内核偏移，但**补不了 MTK DRAM 基址**：它的 `P0_PHYS_OFFSET` 编译期固定 `0x80000000`，没有 `kernel_phys_offset` 字段 | `third_party/ghostlock/src/core/target.h:10`、`offsets_json.c:218` |
| 给它的 JSON 怎么写才等价于我们验证过的 profile | `kernel_phys_load` 写 **0x80000000**（不是真实的 0x40000000），delta 才是 0 | 见 §4.2；alias 公式 `(load + off - P0_PHYS_OFFSET) \| PAGE_OFFSET` |
| 有没有第二条路 | **没有**：DirtyFrag 要 6.5+ 的 `MSG_SPLICE_PAGES` 零拷贝 splice 路径，Titan 2 的 6.1.145 构建和 ACK `android14-6.1` HEAD 都没有 → 写原语不存在，和补丁无关 | 见 §4.3 |
| 值得借的吗 | 有四条：ksud 从管理器 APK 取 `libksud.so`、`late-load --package-name`、激活脚本的「permissive 窗口内做完 + 收尾再 enforcing」顺序、offsets.json 的导入/导出分发 | 见 §5 |

## 1. 应用形态

- 包名 `roro.stellar.yuehong`，minSdk 28 / targetSdk 37，仅 `arm64-v8a`。
- 三个 Gradle 模块：`assistant`（月虹自己的 UI 与业务，Android Library）、`manager`（最终打包的宿主 APK，含 Stellar 的无线调试自激活、DirtyFrag 原生链）、`server`/`api`/`shizuku`（Stellar 服务端、AIDL、Shizuku 兼容层）。
- 启动时做「服务端签名公告 + 客户端版本 + 频道授权」校验（`StartupVerificationApi.kt`）；联网接口要求**官方签名**，证书 SHA-256 硬编码在 `shell/ClientSecurity.kt:16`（`c1158510...dc86`）。自编译重签的包一连网就抛 `SecurityException`。
- 公开源码**不含** `server.properties`、运营域名、公钥、key ID、keystore（`OPEN_SOURCE_RELEASE.md`）。所以这份源码能构建，但联网能力要自己配服务端；本地解析 `boot.img` / `vendor_boot.img` 不依赖服务端。

## 2. 三条提权链

### 2.1 GhostLock 模式（与我们同源，Android 6.1/6.6/6.12）

流程（`assistant/.../ghostlock/GhostLockActivity.java`）：

1. 判断当前 `uname -r` 是否在内置表（50 条）或已导入的 `offsets.json` 里有同名字段；
2. 没有就把 `libghostlock.so` 当可执行文件从 `nativeLibraryDir` 直接 `ProcessBuilder` 启动（app 进程域，不需要 shell）；
3. 通过环境变量把激活脚本路径传进去：`GHOSTLOCK_ACTIVATION_SCRIPT`、`YHROOT_KSU_PACKAGE`、`GHOSTLOCK_CORE` / `GHOSTLOCK_CONSUMER_CORE`（按大核频率自动配对，可手选）；
4. native 拿到 uid 0 后，以 root child 执行 `yhroot_ksu_activate.sh`；
5. 失败按 `1 / 137 / -1` 重试一次（新进程，offsets 与 CPU 对不变）。

激活脚本（`assistant/src/main/assets/yhroot_ksu_activate.sh`，关键行 116-117）：

```sh
"$KSUD" late-load --kmi "$KMI" --allow-shell --package-name "$KSU_PACKAGE"
```

KMI 由 `uname -r` 推：`android14` + `6.1` → `android14-6.1`，与我们在 `scripts/start-lsposed.sh` 里写死的 `KSU_KMI` 一致。

ksud 的来源（`KernelSuManagerSelection.kt` + 脚本 `discover_ksud()`）：

- app 先扫描所有已安装应用，找 `nativeLibraryDir/libksud.so` 或 APK/split 里的 `lib/arm64-v8a/libksud.so`；
- 默认 `me.weishu.kernelsu`，页面上可换成 ReSukiSU / KowSU 等带 `libksud.so` 的管理器；
- 脚本里再兜底扫 `/data/app/*/*/lib/arm64/libksud.so`，从 APK 路径反查包名。

这比我们「依赖 `/data/adb/ksu/bin/ksud` 已存在」更宽：只要装了管理器就有 ksud，不需要它先被激活过。

脚本后半段的顺序值得注意：

```
late-load → 等 /proc/modules 出现 kernelsu（50×0.1s）→ echo 0 > enforce
→ load_policy 重试 10 次 → 清 /data/local/tmp → echo 1 > enforce → 退出
```

这正是我们在 `docs/titan2-two-step-verified-2026-10-23.md` 里用真机换来的顺序：
**需要 exec 的动作全放在 permissive 窗口里，`late-load` 之后再 `load_policy`，恢复 enforcing 之前把临时文件清掉，恢复后立刻退出，不再 exec。**
他们没走我们的弯路（先 late-load 再想 exec），说明这个顺序是这类利用的共性，不是 Titan 2 特例。

两个副作用要知道：

- 脚本会 `rm -rf /data/local/tmp/*`（包括 `.[!.]*`），不是只清自己的文件；
- `load_policy` 10 次都失败时**保持 permissive** 并 `exit 1`，屏幕上看是「提权失败」，实际 SELinux 已经关了。

### 2.2 Stellar 模式（不依赖外部 Shizuku）

`manager` 里带了完整的 ADB 客户端：mDNS 发现无线调试端口（`AdbMdns.kt`）、配对（`AdbPairingClient.kt` / `AdbPairingService.kt`）、然后用 `libstellar.so --apk=...` 起一个 shell uid 的服务端（`startup/command/Starter.kt`）。之后 `Stellar.newProcess()` 在 uid 2000 / `u:r:shell:s0` 里跑命令。

- 它**没有**用 Shizuku 的 `ServiceStarter`，所以不受我们记录的 Shizuku#1198（MTK `makeApplicationInner()` NPE）影响；`shizuku/` 只是兼容层。
- 提权载荷是 119 个预编译 `LD_PRELOAD` `.so`（`assistant/src/main/jniLibs/arm64-v8a/libksu_*.so` + `assets/bundled_payloads.json`），按品牌/机型/内核版本列目录，**必须用户手动选**，`fileFor()` 逐字节校验 size + SHA-256 后才执行。没有「按设备自动挑载荷」。
- 目录里没有 Titan 2 / MT6878 的条目；`libksu_other_*` 是「内核版本未知」的泛用条目，不适用于我们。

### 2.3 DirtyFrag 模式（CVE-2026-43284；Titan 2 不适用，见 §4.3）

- `manager/src/main/jni/dfroot/` 是上游 [mitschud/DirtyFrag](https://github.com/mitschud/DirtyFrag) v1.15：xfrm-ESP 的 page-cache 写，改 `libc++.so` 落 shellcode → `insmod dfroot.ko` → `call_usermodehelper` 起 bootstrap。
- 八个 KMI 模块：`android12-5.10 / 13-5.10 / 13-5.15 / 14-5.15 / **14-6.1** / 15-6.6 / 16-6.12 / 17-6.18`。
- 我们的设备 `6.1.145-android14-11` 对应 `android14-6.1.ko`，**名字对得上**。CVE-2026-43284 的影响区间是 `< 6.1.171`（上游公告），6.1.145 名义在范围内；MTK 是否回填补丁、1.15 在 MTK 上的 `call_usermodehelper` 重启问题（1.16 才修）都没验证。
- 触发需要 app 自己申请 `IpSecManager.UdpEncapsulationSocket` + SPI + AES-CBC transform（`DfRootRunner.java:84-100`），不需要 shell。

## 3. 与我们仓库的对比

| 维度 | 本仓库（Titan 2） | 月虹 v1.5.8 |
| --- | --- | --- |
| GhostLock 代码版本 | C++ 重构版（`vendor/ghostlock-app` `238fabca`），有 `kernel_phys_offset` | C 版（`third_party/ghostlock`），无该字段 |
| 执行入口 | app 直连 + CLI/`adb` 两条 | app 直连（GhostLock）/ Stellar uid 2000 / DirtyFrag |
| 设备支持方式 | 自建 profile，打进 APK 的 `kernel_profiles/` | 内置 50 条 offsets.h + 用户导入 `offsets.json` |
| ksud 来源 | 已激活管理器的 `/data/adb/ksu/bin/ksud` | 从管理器 APK 里取 `libksud.so`（更早可用） |
| late-load 参数 | `--kmi android14-6.1 --allow-shell` | 多一个 `--package-name <管理器包名>` |
| 提权后的活 | app 用 `su -c` 跑栈脚本（ksu 域） | 激活脚本在 permissive 窗口里做完，收尾后退出 |
| 已 root 保护 | native 侧 `KernelSU already has root; skipping exploit` | **没看到**（见 §6） |
| 网络/运营 | 无 | 官方签名 pin + 自建服务端；OTA 自动解析只在官方包可用 |

## 4. Titan 2 能不能用月虹

### 4.1 开箱：不能

`SupportedKernels.java` 的 50 条里没有 `bd17012cc2f7` 也没有 `ab14044926`（grep 整个仓库零命中）。
点「执行」会直接报 `no upstream built-in or OTA-parsed offsets match the current kernel`。

### 4.2 导入 `offsets.json`：能补偏移，补不了 DRAM 基址

它的 JSON 解析器（`third_party/ghostlock/src/core/offsets_json.c:218`）只认这些键：
`release`、`kernel_phys_load`、`pselect_waiter_shift`、`compact_waiter`、`mm_struct_sz`、
`symbols{off_*}`、`struct_fields{task_*}`。我们 profile 里的 `cred_*`、`kernelsnitch_collisions`
它不读（cred 几何用 `target.h` 的编译期默认值）。

关键在 `kernel_phys_load` 的语义。它的地址换算（`util.c:200-206`）是：

```
alias = (kernel_phys_load + (image - KIMAGE_TEXT_BASE) - P0_PHYS_OFFSET) | PAGE_OFFSET
P0_PHYS_OFFSET = 0x80000000   // 编译期常量，target.h:10
```

Titan 2 的 `kernel_phys_load` 和 DRAM 基址**都是** `0x40000000`，我们希望括号里等于 `off`（delta 0）。
若把真实值 `0x40000000` 填进去，delta 变成 `-0x40000000`，所有 alias 偏移 1GB —— 必炸。
填 `0x80000000` 才让 delta 为 0，等价于我们验证过的 profile（我们的 fork 用 `kernel_phys_offset = 0x40000000`
显式表达同一件事）。

所以给月虹的 JSON 用 `artifacts/profiles/titan2-6.1.145.offsets-yuehong.json`（`kernel_phys_load = 0x80000000`），
不要用提取器原样输出（`...offsets.json`，真值 0x40000000，那是给我们自己的 app 用的）。
JSON 的 `symbols` / `task_*` 键名已用脚本对照 `offsets_json.c` 的 `g_symbol_map` / `g_task_map` 逐条核过，无多余键。

即使这样，**仍属未验证**：月虹的 `libghostlock.so` 是按它自己那份 C 版源码编译的，6.1 的 tcp_zerocopy 路线、
kernelsnitch 的 `DIRECT_MAP_END` 默认值、MTK 的 `detect_soc()`（读 `ro.board.platform=mt6878`）都要在真机上过一遍。

### 4.3 DirtyFrag：不可用（已排除，与补丁无关）

`dfroot-android14-6.1.ko` 名字对得上，但这条链在 Titan 2 上**根本跑不起来**：

- DirtyFrag 的写原语要求 `splice(file → UDP socket)` 走零拷贝，把 page-cache 页直接挂成 skb frag。
  这条 `MSG_SPLICE_PAGES` 快路径是 6.5 才进主线的（`splice_to_socket()` / `skb_splice_from_iter()`）。
- 设备构建对应的内核 commit `bd17012cc2f7`（`Makefile` 里 `SUBLEVEL = 145`，与 uname 一致）中，
  `net/ipv4/ip_output.c`、`net/socket.c`、`include/linux/skbuff.h` 三个文件里
  `MSG_SPLICE_PAGES` / `skb_splice_from_iter` / `splice_to_socket` **全部为 0 次命中**；
  ACK `android14-6.1` HEAD 同样为 0。
- 所以这不是「6.1.145 还没打 2026-05 的补丁」，而是 6.1 这条分支**没有漏洞依赖的这条数据通路**。
  上游 issue 里 Debian 12（6.1）也是同一个结论；DirtyFrag FAQ 把 6.1 上的失败列成
  「accidental mitigation (typically seen on kernel 6.1)」。
- 顺带：月虹打包的是 1.15，v1.16 的 release note 明说 `UMH_WAIT_EXEC` 的改动是
  「the other half of the MTK reboot」——即使原语可用，1.15 在 MTK 上也会重启。

判据（本次）：源码快照三条 grep 全 0 + 构建时间 `Thu Sep 4 04:14:43 UTC 2025`，早于修复提交
`f4c50a4034e6`（2026-05）八个月。**结论：不要为 Titan 2 去试这个模式**，失败代价是 page cache
被污染 + 重启，收益为零。

## 5. 可以直接借走的四条

1. **ksud 取件的两种做法**：月虹 从已装管理器 APK 里取（扫 `nativeLibraryDir/libksud.so`，再 zip 读
   `lib/arm64-v8a/libksud.so`，`chmod 755` 后执行）。我们的 app 已经更进一步：`libksud.so` 和
   `kernelsu-manager.apk` 直接内置，`resolveKsudPath()` 第一优先用内置件、不依赖已装管理器。
   差别不在做法，而在构建——我们的两个内置件还没进 git，clean clone 会退化回「找管理器」；
   要补的是 provenance + sha256 + 打包门禁，不是再学月虹取管理器那份。
2. **`late-load --package-name <pkg>`**：我们在 `start-lsposed.sh` 里只给了 `--allow-shell`，加上 `--package-name`
   能把管理器包名一起写进内核模块，省掉「su 能用但管理器不认」的一类问题。
3. **激活脚本的收尾顺序**：permissive 窗口内完成所有 exec → `load_policy` → 清临时文件 → `echo 1 > enforce` → 立刻退出。
   别在恢复 enforcing 之后再 exec（我们的 D2 教训）。
4. **`offsets.json` 作为分发格式**：一个内核一条 JSON，导入即生效，不用重打包 APK。我们的 app 已经支持，
   但对外只发了 profile/APK；把 Titan 2 的 JSON 一起发出去，别的 GhostLock 前端（含月虹）就能直接用。

## 6. 风险与可信度

- **签名 pin + 闭源服务端**：所有联网路径先 `requireOfficial()`，证书写死；自编译版本不能借它的 OTA 自动解析。
  反过来说，官方包的行为无法从源码完全复现（服务端下发什么、频道怎么授权，只有协议代码）。
- **119 个预编译载荷 + 两个 native `.so`**：源码里有校验和目录，但载荷本身不可复现构建；执行的是别人编译的
  root 提权代码，只适合在一次性设备上试。
- **没有「已 root 就别再跑」的保护**：`GhostLockActivity` 里没有 `/proc/modules` 或 `su` 检查，脚本只在
  late-load 前 `grep kernelsu` 决定是否跳过加载，**native 利用仍会再跑一遍**。我们在
  `docs/titan2-two-step-verified-2026-10-23.md` 记过：已有 root 时再跑会踩 re-enforce 路径 panic。
  在 Titan 2 上重复点「执行」前先重启。（这是从我们的事故推的，未在月虹上复现。）
- **清空 `/data/local/tmp`**：GhostLock 激活脚本会 `rm -rf` 整个目录；别把要留的东西放那里。
- **许可**：顶层 MPL-2.0，GhostLock / KSuRoot / Shizuku 部分 Apache-2.0，DirtyFrag 按上游文件头（`dfroot.c` 是 GPL）
  单独归属，不并入 MPL。若要把它的代码搬进我们仓库，DirtyFrag 那部分是 GPL，不能直接收。

## 7. 待验证清单（按性价比）

1. 装官方 APK（或自编译），把 `artifacts/profiles/titan2-6.1.145.offsets-yuehong.json` 通过「导入 offsets.json」导入，
   确认顶部状态变成受支持 → 点执行。判据：`logcat` 出现 `offsets imported from offsets.json`，或原生日志
   `p0 kernel_phys_load=0000000080000000 delta=0000000000000000`。
2. 若 GhostLock 失败，看 exit code：`3/4` = 路线/offsets 不可行，`5` = kallsyms 恢复失败，`6` = 内核已修补（不该出现），
   `1/137` = 竞态或内存压力（它会自动重试一次）。
3. ~~DirtyFrag 模式~~ **不试**：§4.3 已排除（6.1 没有 `MSG_SPLICE_PAGES` 快路径），
   试了只会污染 page cache / 重启，没有成功可能。
4. 成功后确认 SELinux 状态（`getenforce`），失败但脚本跑到一半时它可能停在 permissive。

## 附：给月虹的 offsets.json（Titan 2，6.1.145）

已落盘 `artifacts/profiles/titan2-6.1.145.offsets-yuehong.json`（artifacts 不进 git）。内容：

```json
[
  {
    "btf_size": 5643682,
    "compact_waiter": 1,
    "kernel_phys_load": 2147483648,
    "kimage_text_base": 18446743798965862400,
    "mm_struct_sz": 1024,
    "pselect_waiter_shift": 1,
    "release": "6.1.145-android14-11-gbd17012cc2f7-ab14044926",
    "struct_fields": {
      "cred_caps": 40,
      "cred_securebits": 36,
      "cred_security": 120,
      "cred_uid": 4,
      "seccomp_filter": 8,
      "seccomp_filter_count": 4,
      "seccomp_mode": 0,
      "struct_mm_struct": 960,
      "struct_page_compound_head": 8,
      "struct_page_size": 64,
      "struct_page_type": 48,
      "struct_slab_cache": 24,
      "task_atomic_flags": 1520,
      "task_comm": 2120,
      "task_cred": 2104,
      "task_normal_prio": 140,
      "task_pi_blocked_on": 2384,
      "task_pi_lock": 2340,
      "task_pi_top_task": 2376,
      "task_pi_waiters": 2360,
      "task_pid": 1584,
      "task_prio": 132,
      "task_real_cred": 2096,
      "task_sched_task_group": 840,
      "task_seccomp": 2304,
      "task_tasks": 1360,
      "task_tgid": 1588,
      "waiter_lock": 56,
      "waiter_pi_tree": 24,
      "waiter_task": 48,
      "waiter_tree": 0,
      "waiter_wake_state": 64,
      "waiter_ww_ctx": 80
    },
    "symbols": {
      "off_init_cred": 33626728,
      "off_init_task": 33551872,
      "off_root_task_group": 35550592,
      "off_security_hook_heads": 22781208,
      "off_selinux_blob_sizes": 22783016,
      "off_selinux_enforcing": 35886048,
      "off_slide_boot_id": 36021336,
      "off_slide_loggers_0_1": 33499416,
      "off_slide_nfulnl_logger": 33499592
    }
  }
]
```

来源命令（可复现）：

```bash
./vendor/ghostlock-app/build/extract/release/ghostlock-extract \
  artifacts/fw-TEE-2025111915/boot.img \
  --vendor-boot artifacts/fw-TEE-2025111915/vendor_boot.img \
  --format json --out /tmp/opencode/t2-extract.json
# 再把 kernel_phys_load 从 1073741824 改成 2147483648（见 §4.2）
```

## 参考

- 源码：<https://github.com/qq2070006042-create/yuehong-privilege-assistant>（`2a7d8ba`，v1.5.8）
- 官网：<https://yhyun.asia/>；包名 `roro.stellar.yuehong`
- 上游：Stellar `roro2239/Stellar`、GhostLock-App `YuKongA/ghostlock-app`、KSuRoot `hmascs/KSuRoot`、
  DirtyFrag `mitschud/DirtyFrag`（CVE-2026-43284）
- 本项目相关：`docs/titan2-ghostlock-profile.md`、`docs/titan2-two-step-verified-2026-10-23.md`、
  `scripts/start-lsposed.sh`、`vendor/ghostlock-app/app/src/main/assets/kernel_profiles/`
