# Titan 2 临时 root 的稳定性：失败形态、缺口与测量（2026-10-24）

> 目标不是「能 root 一次」，而是「每次冷启动后都能稳定 root，且失败不变成 panic」。
> 本文只写有证据的失败形态和可验收的改法；没测过的标「待验证」。
> 相关：`docs/titan2-two-step-verified-2026-10-23.md`、`docs/titan2-kernel-panic-2026-10-22.md`。

## 0. 先把「稳定」定义成可测的数

一次冷启动算一次试验。**成功**要同时满足：

1. `su -c 'id -u'` 输出 `0`；
2. `getenforce` = `Enforcing`；
3. LSPosed 栈可查（`cli status` / `state.json` 的 `root` / `pidof zygisk-ptrace64`）；
4. 这一次没有触发内核 panic / 重启。

KPI：N=20 次冷启动的 ①成功率、②panic 次数、③「有 root 没 LSPosed」次数、④耗时 P50/P90。
不看这个数就没法判断改动是不是让稳定性变好。

## 1. 已知失败形态（按严重度）

| 编号 | 形态 | 现有证据 |
| --- | --- | --- |
| F1 | 内核 panic，手机重启 | `docs/titan2-kernel-panic-2026-10-22.md`：`sys.boot.reason=kernel_panic,bug`，`__secure_computing` oops |
| F2 | 有 root 但 LSPosed 没起来（半成功，最容易被当成成功） | 2026-10-22 日志 `ghostlock-ksu-1792661397218.log`：`late-load exit=126`、`timeout: exec ... Permission denied`、`[!] the LSPosed stack stays inactive this run` |
| F3 | 利用本身失败，可重试 | native 的 W1–W3 重试；`run_state` 记录 `w1a..w3d` |
| F4 | 跑完 SELinux 停在 permissive | 同 F2 日志：`policy reload skipped (SELINUX_FIXUP=0); SELinux stays permissive` |
| F5 | 已有 root 再点一次 → 撞 F1 | `vendor/.../cve_2026_43499_backend.cpp:490-498` 的 guard 就是为此加的 |
| F6 | 状态不可见 → 用户重复点 → F5 | UI 只有运行日志，没有「本 boot 已 root」的持久标记 |
| F7 | Vector `vectord` 在 JVM 启动阶段 SIGSEGV，framework 进 recovery | 2026-10-24 17:39 tombstone：`Cmdline: vectord`、`signal 11`、栈顶 `__strncat_chk ← System_specialProperties ← JNI_CreateJavaVM`；设备对照实验确认 cwd 为已删除目录时 `app_process` exit=139，cwd 有效时 exit=0。见 §9 |

F1 的因果链（2026-10-24 真机确认）：W3 的两处盲写落到
`(TIF_SECCOMP=1, seccomp.mode=DISABLED)`，victim 的下一次系统调用在
`kernel/seccomp.c:1349` 的 `default: BUG()` 触发 → MTK mrdump 升级成 panic。
原「root 脚本 re-enforce」归因作废；它与 panic 只是时间相邻。它是**竞态**，不是必现。

## 2. 现在已经有的保护（先别重复造）

- **单趟流程**：exploit、`late-load`、栈安装与 zygote 激活在同一个 root child 内完成。
  `SELINUX_FIXUP=1` 时脚本先 `late-load` 把 su 挂进该 child 的命名空间，随后所有
  `ksud module install` / `post-fs-data` / zygote 重启都走 `su -c`。app 自身永远看不到
  这个 su，所以不能再从 app 发第二步的 `su`。
- **already-root guard**：`handoff_probe.cpp:67` 用 `su -c 'id -u'` 探测，命中就跳过利用。
- **run_state**：`support/run_state.cpp` 记录 `w1a..w3d`，能区分「写到哪一步」。
- **屏幕常亮**：`MainActivity.kt:120`（`FLAG_KEEP_SCREEN_ON`）。
- **单一 root 收尾进程（supervisor）**：脚本由 exploit 的 root child 以 `setsid` 启动，
  zygote 重启不会杀它。它负责模块安装、等待注入、起 Vector daemon、stage2 检查和
  两次 zygote 重启；app 只负责发起一次并读取状态。
- **状态文件**：supervisor 原子写 `files/.ghostlock_status`（`state`/`busy`/`ready`/
  `pid`/`error`/`log`/`boot_id`）。app 每次启动都读它，不依赖 native 的退出码；
  `busy=1` 时禁止第二次执行，`ready=1` 时才显示“软重启”。
- **自动软重启**：stage2 三项全 PASS 后，supervisor 再杀一次 `zygote64`，让框架模块在
  Vector daemon 已就绪后重新加载；不整机重启，`kernelsu.ko` 保持载入。重启后等
  `state.json` 的 `"64": 1` 重新出现，最多 120s，失败写 `state=failed` 并退出。
- **finisher 参数错位（已修）**：root 脚本的 `DEBUG_DIR` 和 finisher 的 `LOG` 两个
  `%s` 顺序与实参顺序相反，finisher 把日志写到了一个目录、把状态写到了 Download。
  这是“软重启按钮不出现 / 没有报告”的根因；619 起 `DEBUG_DIR` 是第 7 个参数，
  finisher `LOG` 是第 8 个。
- **ReZygisk 资产包缺 `customize.sh`（620 修复）**：bundled `rezygisk.zip` 没有
  `customize.sh`，`ksud module install` 只会原样解包，`post-fs-data.sh` 却调用
  `./bin/zygisk-ptrace64`；该文件原本由 `customize.sh` 从
  `lib/arm64-v8a/libzygisk_ptrace.so` 改名生成，因此不存在 → monitor 永不启动。
  同时 `post-fs-data.sh` 里 `sh /data/adb/post-fs-data.d/rezygisk.sh` 在
  `set -e` 下遇到缺失文件会直接退出。两者叠加使 `zrready` 超时，
  `state=failed`，Vector 不激活、finisher 不启动、软重启按钮不出现。
  620 的 zip 已补齐 `bin/zygisk-ptrace64`/`bin/zygiskd64`/`lib64/libzygisk.so`
  （32 位同构），并在启动前 chmod；helper 调用改为存在才执行。
- **Vector `daemon`/`cli` 无执行位（620 修复）**：`vector.zip` 的 `customize.sh`
  用 `SKIPUNZIP=1` 自己解包，但不设权限；KernelSU 对这类模块不会补 755，
  `daemon`/`cli` 落地为 644。root script 直接 exec 会 `Permission denied`，
  stage2 的 `cli status` 也失败 → `state=failed`，同样看不到软重启。620 在
  `ksud module install` 之后统一 `chmod 755`。
- **错误可见性（621）**：app 在 native 前写 `.ghostlock_run_started`；下次启动若
  没有 supervisor 状态，就显示“上次运行未留下状态”或“上次运行以重启结束”。
  root/finisher 的 `EXIT` trap、已知失败点、5 分钟 busy 超时、120 秒 ready
  心跳超时都写 `error=`；失败时把 `pfsd.log`、`vectord.log`、`state.json`、
  模块 bin 列表追加到本次 ksu 日志，UI 同时显示 `激活失败：<reason>`。
- **第二阶段门禁**：`inject_state:1` 且 `modules64` 含 `zygisk_vector` 才杀 zygote，否则不杀。
- **app 路线的 exec 顺序**：`SELINUX_FIXUP=0` 时所有需要 exec 的动作都在 late-load 之前，
  late-load 放最后（`src/core/attack/ops.cpp:405-410`）。10-22 的 126 就是旧顺序踩出来的。
- **ETXTBSY 修复**：已是 root 时不再 `su -c` 套一层（`ops.cpp:525-530`）。

## 3. 缺口与改法（按「先防 panic，再防半成功，再谈成功率」排序）

### P0-1 「模块已加载、su 没挂上」的 guard —— 本机不可观测，降为 best-effort

**2026-10-24 真机结果：这一路在 Titan 2 上无效。** `/sys/module/kernelsu` 连 root
都看不到（`No such file or directory`，但 `/proc/modules` 有 `kernelsu ... Live`）；
`untrusted_app` 读 `/proc/modules` 被 SELinux 拒绝（avc，`permissive=0`）。
三态探针因此返回 `Unknown`，`run_setup` 维持旧行为，等于没加。

背景：`ksu_root_owned()` 只探 `su -c 'id -u'`。10-22 日志里出现过
`kernelsu loaded but su missing` 的中间态：模块在内存里，su 还没 mount 进 namespace。
这时 `su` 探测返回非 0，guard 放行，exploit 再跑一遍 → 可能撞 F1。

原改法（已改码，保留）：preflight 加模块判据，`Loaded` 且 su 不通就拒绝；`Unknown`
只告警。`kernelsu_module_presence()` 在 `handoff_probe.cpp`，三态
（Absent / Loaded / Unknown）；`handoff_probe_test` 覆盖四种状态与
sysfs→`/proc/modules` 回退顺序；NDK aarch64 构建通过。

真机结论：不要把 P0-1 当作防 F5 的保证。同 boot 二次运行只能靠 ledger（P0-2），
启动时再读 `ro.boot.bootreason` 做第二道提示。

### P0-2 重试策略要按 boot ledger 判定，不能盲目重跑

利用失败后自动重跑是 月虹 的做法（exit `1/137` 重试一次），但**只有内核还没被写坏时才安全**。

改法：

- 新建独立 `run_ledger.json`（不要复用 `run_state` 遥测）：`boot_id`、`phase`、
  `status`、`started_at`、`last_step`，每次写都 fsync；
- 运行前先读旧 ledger，再决定是否 reset：
  - `boot_id` 相同且 `phase=exploit` 且 `status in {running, done, failed}` → 拒绝再跑利用，
    提示重启；允许只跑第二阶段；
  - `boot_id` 不同 → 允许，这是新的冷启动；
  - `boot_id` 读不到 → exploit fail-closed，stage2 仍允许；
- `boot_id` 用 `/proc/sys/kernel/random/boot_id`（本机 0444、`run-as` 可读），不用 uptime；
- `rootMarkerValid()` 改成存 `boot_id` + uptime，只有 boot_id 相同才算有效；
- `StatusDisabled` 只影响 `run_state` 遥测，**不得**删除 ledger。

12:16 的 run 已经把 panic run 的 `run_state` 覆盖成全 `not_start`，这是 ledger 必须独立的
直接证据；`w1a..w3d` 只能当诊断，不能当安全判据。

判据：单测覆盖同 boot 拒绝 / 不同 boot 允许 / boot_id 缺失 fail-closed / stage2 放行。

### P0-3 半成功没有终检（F2）

现在第二阶段的日志会写「the LSPosed stack stays inactive this run」，但 UI 没有把它变成失败。

改法：第二阶段结束跑三条只读检查，逐项显示 PASS/FAIL：

```
su -c '/data/adb/modules/zygisk_vector/cli status'
su -c 'cat /data/adb/rezygisk/state.json'   # 期望 "root":"KernelSU"
su -c 'pidof zygisk-ptrace64'
```

失败时提供「只重跑第二阶段」按钮（安全，不碰 exploit），并把 `stack_ok` 写进本 boot 的
run_state。月虹的 `GHOSTLOCK_ACTIVATION_STATUS` 文件是同类做法：让脚本留下
`0/1/未运行` 三态，app 不用猜。

判据：拿掉一个模块 zip 强制第二阶段失败 → UI 显示 FAIL，不出现「完成」。

### P1-1 SELinux 看门狗（F4）

每次运行结束（成功、异常、被杀）都读一次 `getenforce`。`Permissive` 就出红色横幅，
按钮「恢复 SELinux」用已拿到的 root 执行 `setenforce 1`（或 `echo 1 > /sys/fs/selinux/enforce`），
**不要**为此重跑 exploit。

判据：手动 `setenforce 0` 后点恢复 → 回到 Enforcing；日志有记录。

### P1-2 内置 ksud 已经有了；缺口是「可复现地进构建」

先纠正上一版：**我们的 app 内置了 ksud，两层**（查证）：

- `app/src/main/jniLibs/arm64-v8a/libksud.so`（4,990,848 B，sha256 `4f7d5fd6…`）会装到
  `nativeLibraryDir`，`resolveKsudPath()` 第一优先用它（`AndroidGhostlockRepository.kt:1237`），
  脚本里就是 `KSUD='%s'`（`ops.cpp:241`）。
- `app/src/main/assets/kernelsu-manager.apk`（13,637,206 B，sha256 `b4a059ac…`，v3.3.1）由
  `ComponentFetcher.fetchManagerApk()` 离线安装，和内置 ksud 版本对齐。

第二阶段仍走 `/data/adb/ksu/bin/ksud` 是**有意的**：ksu 域 exec 不了 app 私有目录里的
`libksud.so`（`ops.cpp:472` 注释）。10-22 的 126 是顺序问题（`SELINUX_FIXUP=0` 时 late-load
先跑，后续 exec 被拒），不是 ksud 来源问题；当前代码已把 exec 全部前置、late-load 收尾。

真正缺口（查证）：这两个文件在工作树里是 `??`（未跟踪、也没被 `.gitignore` 排除），而
10-23 验证用的 release 608（10-22 22:36 构建）里两件都没有；debug 609（10-23 19:48）才有。
10-22 日志里 ksud 显示为 `/data/app/.../me.weishu.kernelsu.../libksud.so`，就是内置件缺失后的
回退路径。**clean clone / CI / release 构建会静默退化成「找已安装管理器」。**

改法：

- 把两个内置件纳入构建：提交（或 git-lfs / 构建脚本按 sha256 拉取），CI 增加一条
  `unzip -l app-release.apk | grep -E 'lib/arm64-v8a/libksud.so|assets/kernelsu-manager.apk'` 门禁；
- 日志打印 chosen ksud 的路径、sha256 和管理器版本；
- `late-load` 补 `--package-name`（这条仍然成立）；
- 不要把第二阶段改成 app 私有 `libksud.so`（域不允许）。

判据：`git clean -xdff` 后重新构建，APK 里两件都在；卸掉已装管理器，第一阶段仍能跑；
第二阶段在 late-load 之后仍走 `/data/adb/ksu/bin/ksud`。

### P1-3 panic 取证：pstore 有栈，但 app 读不到

2026-10-24 复核：`/sys/fs/pstore/console-ramoops-0` 有完整 oops（262132 B，
sha256 `9bd5f60221d5f6c0dcbe484398793d5f0faac5ddcd1f40b2247f12324f3cc1f0`），
`su -c cat` 可读。10-22 文档说「只有正常关机日志」是因为当时没有在 panic 后以 root
取，或取的不是这一份；不能再说「MTK pstore 抓不到栈」。`untrusted_app` 读
`/sys/fs/pstore` 被拒（`Permission denied`），app 不能直接取证。

改法：

- app 启动读 `getprop ro.boot.bootreason` + ledger；`kernel_panic` 且同 boot
  已尝试过 → 明确提示「上次运行触发内核 panic，本 boot 不再跑利用」，不能只弹 NOTICE；
- 有 root 后（第二阶段）用 `su -c cat /sys/fs/pstore/console-ramoops-0` 把 pstore
  复制到 app 私有目录，保存 `sha256` 和 `boot_id`，供下次分析；
- 归档格式与 `artifacts/2026-10-24-panic-seccomp/` 一致。

判据：人为制造一次已知 panic 的冷启动 → 下次启动 UI 能指出 bootreason、上次 ledger
的 `last_step` 和 pstore 路径；没有 root 时至少显示 bootreason。

### P2-1 系统前置条件

panic 文档里还有一个假故障：重启后锁屏时 CE 存储没起，app 起不来。把前置条件显式化：

- `sys.boot_completed=1` 且解锁完成；
- 开机后至少等 60 秒（LSPosed 注入窗口的既有经验）；
- 电量 > 20%（低于就提示，不拦）；
- 不满足就不让点「执行」，而不是点了以后失败。

### P2-2 建立测量

- 每次冷启动一条记录：`run_id / boot_id / uname / 开始结束时间 / w1..w3 / exit / enforce 前后 /
  stack_ok / bootreason`；
- N=20，一次只改一个变量（例如「加 W3 直接探针」前后各 10 次）；
- 失败后必须冷启动，不热重试（`KERNEL-PANIC-01` 的既有结论）。

## 4. 明确不做

- **DirtyFrag**：6.1 没有 `MSG_SPLICE_PAGES` 零拷贝 splice 路径（设备 commit 与 ACK HEAD 都没有），
  原语不存在；试它只会污染 page cache。见 `docs/yuehong-study-2026-10-24.md` §4.3。
- **持久化 root**：用户明确要求不持久化；`ksud install` 还会在 OTA 换内核后留下错配。
- **已有 root 再跑利用**：F5，直接换「只跑第二阶段」。
- **在锁屏/开机未稳定时跑**：先解锁 + 等 60 秒。

## 5. 思想实验：P0-1 的状态机与残余风险（2026-10-24）

设定：`M` = 内核里 `kernelsu` 已加载；`S` = app 进程里 `su -c 'id -u'` 返回 0；
`P` = 新探针结果；`F` = `force_attack_test` 持久开关。

| # | M | S | P | F | 实际行为 | panic |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 0 | 0 | Absent | 0 | 正常跑利用 | 无（正常路径） |
| 2 | 1 | 1 | 任意 | 0 | su guard 先返回 `Done`，走第二阶段 | 无 |
| 3 | 1 | 0 | Loaded | 0 | `Failed`，不跑利用 | 无（目标状态） |
| 4 | 1 | 0 | Unknown | 0 | 告警后照旧跑利用 | **有，残余** |
| 5 | 0 | 1 | 任意 | 0 | `Done`，走第二阶段（可能失败） | 无 |
| 6 | 1 | 0/1 | 任意 | 1 | 强制跑利用 | **有，残余** |
| 7 | 探针后变 1 | 0 | Absent | 0 | 照旧跑利用 | **有，残余（竞态）** |
| 8 | 1 | 0（瞬时） | Loaded | 0 | `Failed`，误报 | 无，代价是重试 |

**2026-10-24 真机判定（覆盖结论 3）**：`/sys/module/kernelsu` 连 root 都看不到；
`untrusted_app` 读 `/proc/modules` 被 avc 拒绝（`permissive=0`）。所以 `P=Unknown`
是本机常态，表里的第 4 行不是残余而是**主路径**：P0-1 在这台设备上等于没加。
防 F5 只能靠 ledger（P0-2）；模块探针保留为其他设备的 best-effort。

**结论 1（查证）**：顺序路径上，`M=1,S=0` 被拦；`M=1,S=1` 在 su 判据就停；`M=0` 不受影响。
拦截只有 `stat`、读 `/proc/modules`、`su` 探测，都是只读，不新增写或 SELinux 转换。

**结论 2（推断）**：`M=1,S=0` 是现实状态，不是纯理论。exploit 的 root child 不走 su，
第一次成功后管理器可能还没授权 app 的 `su`；这时 Kotlin 的 `rooted` 为 false，再点执行
就会回到利用。10-22 的 4 份日志里没有这个状态（126 那次 `S=1`，失败在 exec 顺序），
所以这是机制推断，不是已观测。

**结论 3（已被真机判定为 Unknown）**：前提是 `P=Loaded`。2026-10-24 实测
`/sys/module/kernelsu` 与 `/proc/modules` 对 app 都不可读，`P=Unknown`，
guard 退化成旧行为（见上）。

**结论 4（残余风险，按后果排序）**：

1. `F=1` 会持久绕过两个 guard：`restoreForceAttackTest()` 在启动时从 SharedPreferences
   读回，开关一次之后每次启动都生效。这是最容易被忽略的 panic 路径；建议改成一次性
   （跑完清零，或启动时清零）。
2. `P=Unknown`（结论 3）。
3. 探针与 W1 之间的竞态：需要并发 late-load 才会触发。UI 的 `running` 挡住同进程双击，
   可疑来源只剩 phase-2 的 `setsid` finisher。概率低，后果最大；要彻底关掉需要 native
   与 root script 共享一把 run 锁。

**结论 5（不会更坏）**：唯一新增代价是误报——`S` 瞬时不稳（管理器弹窗/超时）而 `M=1`
时拦一次；授权 su 或重启即可恢复，不写内核。

**下一步判据**：真机覆盖试验已经做完，结论是 `P=Unknown`（结论 3），所以 P0-1 不能
作为 gate；先做 §7 的 W3 直接探针和 P0-2 的 boot ledger，再检查 `F` 开关改成一次性。
P0-3 的终检写成有界轮询（daemon 起得慢），失败只允许重跑第二阶段，不再跑利用。

## 6. 第一性原理：panic 到底从哪来（2026-10-24）

### 6.1 系统模型

- 这里的「root」不是服务，是**一次冷启动内**由一串盲写产生的内核状态：cred + seccomp +
  KernelSU 模块。重启即消失。
- 利用链没有内核读原语。每一步只能「写 → 用副作用探测」，探测本身也可能触发故障。
- 稳定性 = `P(不 panic) × P(到达终态)`，按冷启动计。panic 是吸收态（重启、丢数据），
  所以先压 `P(panic)`，再谈成功率。

### 6.2 因果已确认：W3 的 seccomp 两位盲写

2026-10-24 的真机 pstore 把这条从推断变成确认（设备、sha256、完整行见
`artifacts/2026-10-24-panic-seccomp/README.md`）：

- `kernel BUG at kernel/seccomp.c:1349!`，`Comm: ghostleaf_01234`；
- `pc : __secure_computing+0xc4`，`lr : syscall_trace_enter+0xe8`；
- `seccomp.c:1349` 是 `switch (current->seccomp.mode)` 的 `default: BUG()`；
- `SECCOMP_MODE_DISABLED=0` 走 default：系统调用入口认为有 seccomp（TIF_SECCOMP
  置位），但 mode 已被写成 0。

W3 先清 `thread_info.flags`（TIF_SECCOMP），再清 `seccomp.mode`；两次独立盲写。
flags 漏写 + mode 落 → 坏对；child 的下一次系统调用就 BUG。触发点是 W3 之后的
`verify_seccomp_probe_stage()`：它写 `"F"` 唤醒停在 `read()` 的 child，child
执行 fork/finit_module，第一个系统调用撞上。10-22 文档把这次归因成 root 脚本
re-enforce，是时间相邻造成的误判；pstore 的 PC/LR 都在 seccomp 路径，不在
`avc_has_perm`/`security_*`。

已安装 609 的 `libghostlock.so` 同时含 `TIF_SECCOMP`（3 处）和 `(again)`（2 处），
说明 panic 发生时**两次 flags 写已经在跑**（p²）。p² 仍会撞坏对，这是 10-24 的
关键新事实。

B（已有模块再跑）仍无对应的 pstore；它是次因，但 P0-1 现在也看不到它（见 6.4）。

### 6.3 可控量（按杠杆排序）

1. **W3 改成确定性验证，而不是再赌一次写**。现有 forked probe 不能验证 flags：
   mode 还是 FILTER 时，`copy_seccomp()` 会把 TIF_SECCOMP 再发给子进程，所以它
   只反映 mode，不反映 victim 的 flags（代码注释里的 36 次 `0xfffffffd` 就是证据）。
   可以做的是加一条 **victim 直接探针**：child 自己调 `finit_module(0,0,0)`，
   此时 mode 仍是 FILTER。
   - flags 已清 → 调用真正进内核，返回 EFAULT/EBADF，child 活着 → 再写 mode=0；
   - flags 没清 → 过滤器拦截；现有 forked probe 被 SIGSYS 杀掉（`0xfffffffd`），
     说明 action 是 KILL，直接探针会死掉这个 victim，但**不会 panic**（此时两位
     仍然一致：TIF_SECCOMP=1, mode=FILTER）。父进程看到 child 死，换新 child 重跑。
   这样 `mode=0` 只在 flags 被确认清掉之后才写，坏对不会被构造出来。代价是漏写时
   损失一个 victim、多一轮 W2/W3；换掉的是 p^n 的尾部风险。
   待确认：app 的 seccomp 对 `finit_module` 的 action 是否确实 KILL（现有探针的
   `0xfffffffd` 指向 KILL）；以及 `KILL_PROCESS` 是否只杀 victim 进程（victim 是
   fork 出来的独立进程，不是 app 的线程，预计只杀它）。
2. **在验证之前，p³ 只是止血**。已改码的 `w3d`（mode 后、探针前再补一次 flags 写）
   把坏对从 p² 降到 p³，但 609 的 p² 已经真机 panic，说明 p 不小、或三次写不独立；
   它不能替代 1。
3. **每次冷启动只允许一次干净尝试**。这是不依赖内核可见性的判据：只要本轮已经完成过任何
   W 步，就不允许在同一 boot 再跑一次利用。冷启动是唯一可保证的干净初态。
   现状是基础设施已有、但没有执行：`RunStateCodec` 的步骤文件是 **fsync** 的，
   `maybeReportInterruptedRun()` 也会在启动时弹「上次被中断」，但
   `resetRunState()` 在下一次运行开始时把证据覆盖掉，弹窗只是 NOTICE，不拦执行。
   10-24 12:16 的 run 就把 panic run 的状态覆盖成全 `not_start`，实证了这一点。
   boot 身份用 `/proc/sys/kernel/random/boot_id`（本机 0444，run-as 可读），
   **不要只用 uptime**：`rootMarkerValid()` 现在的 `uptime >= marked` 在重启后
   uptime 超过旧值时会误判成同一 boot。
4. **初态探针**（P0-1）：本机实测 `/sys/module/kernelsu` 连 root 都看不到
   （`No such file or directory`，但 `/proc/modules` 有 `kernelsu ... Live`）；
   `untrusted_app` 读 `/proc/modules` 被 SELinux 拒绝（avc，`permissive=0`）。
   所以 P0-1 在真机上返回 `Unknown`，等于没加；只能靠 `su -c id` 和 ledger。
5. **成功终检**（P0-3）：属于 `P(到达终态)` 那一项，和 panic 无关，排在后面。

### 6.4 哪些是继承的假设

- 「re-enforce 导致 panic」：已被 10-24 pstore 推翻（BUG 在 `seccomp.c:1349` 的
  `default`，PC/LR 都在 seccomp 路径）。
- 「p² 够用」：609 已含两次 flags 写（二进制里 `(again)`），仍在 10-24 12:14
  panic；不成立。
- 「三次写独立，p³ 就安全」：未验证；写失败可能是系统性的（同一 route/leaf），
  重复不会把尾部风险压到零。
- 「模块探针一定可见」：本机 `/sys/module/kernelsu` 连 root 都看不到；
  `untrusted_app` 读 `/proc/modules` 被 avc 拒绝。P0-1 真机返回 Unknown。
- 「uptime 能区分 boot」：`rootMarkerValid()` 的 `uptime >= marked` 在重启后
  uptime 超过旧值时会误判成同一 boot；必须用 `boot_id`。
- 「run_state 是安全 ledger」：它只是每次运行的遥测；12:16 的 run 把 panic run
  覆盖成全 `not_start`，且 ACK 超时会 `clearRunState()` 删除文件。
- 「多一条提权路线更稳」：DirtyFrag 原语不存在，不成立。

### 6.5 奥卡姆与顺序

最简机制：**panic = W3 的两次盲写留下 `(TIF_SECCOMP=1, mode=0)`；下一次系统调用在
`seccomp.c:1349` 的 `default: BUG()`**。10-24 的 pstore 已经确认，不再是假设。顺序：

1. **W3 直接探针**（待实现）：victim 自己在 mode 仍是 FILTER 时调一次
   `finit_module(0,0,0)`；只有返回正常 errno（flags 已清）才写 mode=0。
   漏写时 victim 被过滤器杀死，换新 child 重跑，不构造坏对。
2. **boot ledger**：用 `/proc/sys/kernel/random/boot_id`（不是 uptime），reset 之前
   检查，同 boot 已启动过 W 步且未到终态就拒绝再跑；第二阶段（已有 root）可以重试。
3. **现有 p³ 只作过渡**。`w3d` 修复写已加，但它有三个弱点：三次写独立性未证；
   修复写在 `complete("w3c")` / `enter("w3d")` 两次 status ACK 之后，每次 ACK
   是 app 侧 fsync 往返，真正的危险窗口被拉长；最严重的是 **repair 路由失败后
   代码 `continue`，下一轮失败会走 `park_retry_child`**，而 `park` 里的
   `open()`/`write()` 就是坏对 child 的下一次系统调用——这一唤醒直接 BUG。
   repair 失败必须改成 `SIGKILL`（`do_exit` 不经过系统调用入口）或走直接探针，
   不能继续 park/探针。
4. P0-1 在本机不可见，保留但不计入；P0-3 终检；`force_attack_test` 改一次性。

**推翻条件**：

- 直接探针上线后仍出现 `seccomp.c:1349` → 说明「探针成功 ⇒ flags=0」不成立，或
  mode 写另有问题，停止叠加写、回去查探针语义。
- 某次 panic 的 fsync 记录在探针已报 `child seccomp filter bypassed` 之后 →
  该次不是 W3 探针，转查 cred 写 / late-load。
- 漏写时直接探针没有杀死 victim 而是返回了正常 errno → 说明 action 不是 KILL，
  验证逻辑要按 errno 区分，不能按「child 是否死」判断。

## 7 W3 直接探针：设计、实现与真机验证

### 7.1 为什么 p³ 不够

p³ 只是在「flags 写漏」的概率上再乘一次 p，仍然是**盲写**：`attack_write` 返回成功
不等于写落地（没有读原语），修复写也可能静默漏掉，随后 `verify_seccomp_probe_stage()`
一唤醒 child 就 BUG。609 的 p² 已真机 panic，说明 p 不小。

更糟的是 repair 的**失败路径**：repair 路由失败后 `continue`，下一轮若仍失败，链会
`park_retry_child` 发 `'P'`；child 从 `read()` 返回后执行 `open()`/`write()`，这些
就是坏对之后的下一次系统调用，直接 BUG。也就是说 p³ 把「立即 panic」换成了「多数
情况修好、少数情况延迟到 park 时 panic」，没有把尾部风险去掉。

`SIGKILL` 本身走 `do_exit`，不经过系统调用入口；但**谁能发**这个信号还没验证：
parent 是 `untrusted_app`，child 已被 W2 改成 `u:r:kernel:s0`，SELinux 的
`process:signal` 很可能拒绝。若拒绝，坏对 child 只能靠「保持 pipe 打开、永不唤醒」
隔离；app 进程一死，pipe 关闭，child 的 `_exit` 又是系统调用 → BUG。
所以不能把 SIGKILL 当兜底；直接探针是唯一不依赖「杀掉坏对 child」的方案。

### 7.2 协议

child 新增命令 `'D'`（由 victim 自己调用，不 fork）：

```c
errno = 0;
long r = syscall(__NR_finit_module, 0, 0, 0);
uint32_t out = (r == 0) ? 0 : (uint32_t)errno;
write(p.uid_write.get(), &out, sizeof(out));
```

parent 的 W3 每轮：

```text
1. flags 写（保留现在的一到两次；写失败则本轮失败）
2. 发 'D'
   - read(uid_write, 4) != 4      -> child 被 SIGSYS 杀死：mark_child_exited，换新 child
   - seccomp_probe_blocked(code)  -> flags 仍为 1；mode 仍是 FILTER，安全；重写 flags 再试
   - 其它 errno                    -> flags=0 已确认，进入 3
3. 写 mode=0
4. 用现有 forked probe 验证 mode
   - allowed -> seccomp_ok=1
   - blocked -> mode 写没落；此时 (flags=0, mode=FILTER) 自洽，重写 mode 再验
5. 重试耗尽：child 仍是 (flags=1, mode=FILTER)，状态一致；能 SIGKILL 就清理，
   杀不掉就留在 FILTER 状态，绝不写 mode、绝不 park 后唤醒
```

不变量：

- **mode 写只在 flags 被直接探针确认之后**。坏对 `(1,0)` 无法被构造。
- flags 未确认时 mode 仍是 FILTER；最坏情况是 victim 被过滤器杀死，不是内核 BUG。
- flags=0 后即使 mode 写漏，`(0,FILTER)` 也是合法状态；forked probe 会报 blocked，
  可以重试，不会 panic。
- 直接探针与 pre-W3 探针使用**同一个 syscall 和参数**（`finit_module(0,0,0)`），
  保证过滤器的 action 一致；若担心 fd 0 在 flags=0 时阻塞，可改用 `-1`，但必须
  先用 forked probe 确认过滤器对 `-1` 也是同样的拦截。

### 7.3 与 w3d 的关系

直接探针落地后，`w3d`（mode 后 repair）可以删除：顺序从
`flags → mode → repair → probe` 改成 `flags → direct probe → mode → forked probe`。
在直接探针落地前，**不要用 w3d 版本跑真机门禁**；如果一定要保留过渡版本，repair
失败分支必须改成 `SIGKILL`，不能 `continue` 到 park。

### 7.4 boot ledger 与 stage2 终检（已实现，真机待验）

实现位置：`app/src/main/kotlin/com/ghostlock/app/data/RunLedgerCodec.kt`、
`RunLedgerStore.kt`、`AndroidGhostlockRepository.kt`。

- 新文件 `.ghostlock_run_ledger.json`，独立于 `run_state`；写入用
  temp + `fd.sync()` + rename。`run_state` 的 reset 不碰 ledger。
- schema（v1）：
  ```json
  {
    "schema_version": 1,
    "boot_id": "…",
    "exploit": {"status": "done", "started_at": 0, "finished_at": 0, "last_step": "w3d"},
    "stage2":  {"status": "failed", "started_at": 0, "finished_at": 0, "checks": "fail"},
    "updated_at": 0
  }
  ```
  用嵌套 `exploit` / `stage2` 而不是单个 `phase`：stage2 更新不能抹掉 exploit
  尝试记录，否则 stage2 重试后同一 boot 又会被允许跑利用。
- 运行前先读旧 ledger，**再**决定是否 reset：
  - 当前 `boot_id` 读不到 → exploit `Block`（fail-closed）；stage2 仍 `Allow`。
  - ledger 不存在 → `Allow`。
  - ledger 存在但损坏、schema 不支持、或 ledger 自己的 `boot_id` 为空 → exploit
    `Block`（fail-closed）。
  - `boot_id` 相同且 `exploit.status != not_started` → `Block`，提示重启；stage2
    始终 `Allow`，失败只重跑第二阶段。
  - `boot_id` 不同 → `Allow`；这是新的冷启动，旧 ledger 被新 boot 的记录替换。
  - stage2 遇到**损坏**的 ledger 时仍然允许运行（它不碰内核写路径），但写入的
    ledger 必须把 `exploit.status` 记为 `failed`（`RunLedgerCodec.freshBlocked`），
    不能重置成 `not_started`。否则「损坏 ledger → 跑 stage2 → 再跑 exploit」会在
    同一 boot 放行利用，fail-closed 就失效了。对应测试
    `stage2 after an unreadable ledger keeps the exploit blocked`。
- `boot_id` 用 `/proc/sys/kernel/random/boot_id`（本机 0444、`run-as` 可读），
  不用 uptime。
- `rootMarkerValid()` 改成读 marker 第一行的 `boot_id`；只有与当前 boot 相同才算
  有效。旧 uptime-only marker 视为无效。
- `StatusDisabled` 只影响 `run_state` 遥测，**不得**删除 ledger。
- 12:16 的 run 已经把 panic run 的 `run_state` 覆盖成全 `not_start`，这是 ledger
  必须独立的直接证据。

stage2 终检（`src/core/attack/ops.cpp` 的 detached finisher）：

- zygote 重启后由 root finisher 跑三条只读检查：
  - `/data/adb/modules/zygisk_vector/cli status` 输出 `Framework Version`；
  - `/data/adb/rezygisk/state.json` 含 `KernelSU`、`zygisk_vector`、`"64": 1`；
  - `pidof zygisk-ptrace64` 非空。
- 每项写 `stage2 check <name>=PASS|FAIL`，汇总写 `stage2 check PASS|FAIL`；
  `awaitRootScript()` 看到 `stage2 check FAIL` 返回 1，ledger 的 stage2 记
  `failed`。
- 复核 15:42 / 15:56 的 ksu log 发现：finisher 里原本写的是
  `LOG='__LOG__'` 字面量，finisher 的输出没有进 per-run ksu log；两份 log 都停在
  `killing zygote64`，没有 `zygote patched=1` / `LSPosed stack setup done`。
  现已改成 `LOG='%s'` 并把 `ksu_log_path` 加进 `snprintf` 参数；新 native 已编译。
  这是「有 root 没 LSPosed」无法从 app 日志判断的一个具体原因。

验证状态：

- Kotlin：`./gradlew :app:testDebugUnitTest --tests 'com.ghostlock.app.data.RunLedgerCodecTest'`
  通过（往返、同 boot 拒绝、新 boot 允许、boot_id 缺失 fail-closed、stage2 放行、
  stage2 不覆盖 exploit、损坏 ledger、stage2 遇损坏 ledger 时 exploit 仍为 failed）。
- native：`make -C src ghostlock` 通过；`assembleDebug` 通过，APK 内
  `lib/arm64-v8a/libghostlock.so` 与 `build/native/ghostlock` 一致。
- 真机：ledger 首次放行、同 boot 二次拒绝、stage2 重试、终检 PASS/FAIL 尚未在本
  APK 上复测。

### 7.5 真机已有证据与代价（12:09 成功 run）

`/sdcard/Download/ghostlock-debug-log/20261024-120905/ghostlock-direct-0.log.txt`：

- `pre-W3 seccomp finit_module probe = 0xfffffffd` → 过滤器用 SIGSYS 杀探针，
  action 是 KILL，不是 ERRNO；
- W3 共 4 次 attempt，每次 flags×2 + mode；前 3 次 `seccomp finit_module probe =
  0xfffffffd`（mode 写没落，probe 报 blocked），第 4 次 `= 0x16`（EINVAL 22）并通过；
- 说明 **mode 写会漏**，现有 forked probe 能抓到并重试；但 **flags 写漏没有观测**。
  12:14 那次 `ghostlock-direct-0.log.txt` 是 0 字节（panic 当场），pstore 的坏对
  就是 flags 漏 + mode 落的那一次。

代价：直接探针在 flags 漏时会被 KILL，victim 死（不 panic），要重做 W2/W3。
12:09 日志里每次 attempt 的 prepare/route 约 0.8–0.9 s；如果 flags 漏率很高，
成功率会下降。先在同一批日志里统计 flags/mode 的漏写率；也可以后续找一个被
`RET_ERRNO`（不是 KILL）拦截的 syscall 做非致死探针。这个代价是明确、可测的；
panic 不可恢复。

### 7.6 验收

- Kotlin：ledger codec 往返；同 boot 拒绝；不同 boot 允许；boot_id 缺失
  fail-closed；stage2 放行；stage2 更新不覆盖 exploit。探针协议用固定向量覆盖
  `allowed` / `EPERM` / `SIGSYS` 三种返回。
- 真机：先 5 次冷启动基线（panic/root/LSPosed/耗时），再 A/B 直接探针 10 次冷启动。
  成功判据：`seccomp.c:1349` panic = 0；root+LSPosed 不低于基线；每次运行在
  `Download/ghostlock-debug-log/<时间>/` 留 `profile.conf`/`profile.bin`/日志。
- 反例：直接探针上线后仍出现 `seccomp.c:1349` → 立即停，回查「探针成功 ⇒ flags=0」
  与过滤器 action，不再叠加写。

## 8 2026-10-24 15:41 冷启动：直接探针 + Vector 激活（真机）

设备 `boot_id=507b645b-81fc-4999-8690-6bca31f23e32`，运行前后 uptime 从 1 min 到 10 min，
`dmesg | tail -120` 没有 `seccomp.c:1349`、`BUG:`、panic。**这一轮没有重启内核。**

### 8.1 直接探针（查证）

`/sdcard/Download/ghostlock-debug-log/20261024-154132/ghostlock-direct-0.log.txt`：

```text
[+] child is root!
[*] pre-W3 seccomp finit_module probe = 0xfffffffd
[*] direct seccomp flags probe = 0x16
[*] W3: seccomp.mode attempt 1/6
[*] seccomp finit_module probe = 0x16
[+] child seccomp filter bypassed (finit_module errno=22)
[+] child seccomp fully bypassed (forked workers run filter-free)
[+] KernelSU module loaded
[+] KernelSU ready
[*] native exited code=0
```

判读：

- `0xfffffffd` 是 `-3`（SIGSYS 杀死探针），与 12:09 的 pre-W3 语义一致；
- `direct seccomp flags probe = 0x16`（EINVAL=22）说明 flags 已清、调用进了内核；
- 随后 mode 写第一次就落地，`seccomp finit_module probe = 0x16`，没有进入 6 次重试；
- 这一轮没有出现「flags 漏写 + mode 落地」的坏对，也没有 panic。

### 8.2 Vector 模块（查证 + 用户确认）

`/sdcard/Download/ghostlock-debug-log/20261024-154219/ghostlock-direct-1.log.txt` 是同一 boot
的第二阶段（模块安装）：

```text
- Vector version v2.2 (3080-88f8e1fa-JingMatrix-Vector)
- Device platform: arm64 (armeabi-v7a / arm64-v8a)
- Extracting root module files
- Verified module.prop
- Verified action.sh
- Verified service.sh
```

`/data/adb/modules/zygisk_vector/module.prop`：`version=v2.2 (3080-88f8e1fa-JingMatrix-Vector)`。
`vectord` 两个进程（22717、29765）；manager 主页显示 `Vector / 已激活 / 2.2 (3080) · API 102`。
用户确认「成功激活了模块」。模块目录整目录时间戳都是 15:42，先前临时改过的 `daemon`
和 `daemon.bak` 已被模块安装覆盖；只剩一个 0 字节的 `/data/local/tmp/vectord.log`，可删。

### 8.3 限制（推断 / 未验证）

- `system_server` 24297 是 15:41 第一阶段重启 zygote 时起来的，Vector 模块 15:42 才安装。
  如果 manager 的详情页仍显示「系统框架 未注入」，机制是**模块安装晚于 zygote 启动**，
  不是模块安装失败；App 级 hook 可用，system_server 级 hook 需要「装模块后再重启 zygote」。
- 没有再重启 zygote/system_server 去验证 system_server 注入：10-24 手动 `kill -9
  system_server` 曾进入 crash loop，只靠重启恢复。这个实验未做，不能写成已解决。
- 新 lib 目前有 2 次冷启动记录（15:19 的 boot 与 15:41 的 `507b645b`），都拿到 root、
  都没有 panic；N=2 远小于 §0 的 N=20，**不能给成功率**，只能说直接探针没有复现 F1。

### 8.4 下一步

1. 装 §7.4 的新 debug APK，先验证 ledger 首次放行、同 boot 二次拒绝、stage2 重试、
   `stage2 check PASS/FAIL` 出现在 per-run ksu log；再跑 1–3 次冷启动，记录
   `boot_id`、`pre-W3`/`direct flags`/`mode attempt`、root、Vector、耗时。仍然每次
   只改一个变量。
2. 若要做 system_server 注入，单独设计「模块安装后再重启 zygote」的实验，并先准备
   crash loop 的恢复路径；不和冷启动 A/B 混在一起。

## 9 2026-10-24 17:39 冷启动：vectord 因已删除 cwd 崩溃 → recovery

这一轮不是 W3 seccomp panic，也不是 `system_server` 自己崩：Vector 的 `vectord` 在
JVM 启动阶段拿到空 `user.dir`，`strncat` 空指针，SIGSEGV 后 framework 进 recovery。

### 9.1 证据

- dropbox tombstone（17:39:06）：`Cmdline: vectord`、`uid: 0`、`signal 11 (SIGSEGV)`、
  `Cause: null pointer dereference`；栈顶
  `__strncat_chk ← System_specialProperties ← System.initUnchangeableSystemProperties
  ← JNI_CreateJavaVM`。
- 设备对照实验（同一份 `daemon.apk`，不跑 exploit）：
  - cwd 指向已删除目录 → `app_process` exit=139（同款 SIGSEGV）；
  - cwd=`/data/local/tmp` → exit=0。
  证明崩溃条件是 cwd，不是 `daemon.apk` 或 JVM 本身。
- 时序：`ksud late-load` 的 service 阶段先执行了模块自带的 `service.sh`，它从模块目录
  起 daemon；此时 `/data/adb/modules_update → modules` 正在迁移，cwd 变成已删除目录。
  根脚本 finisher 原本要等 `zygote patched=1`、sleep 30 后再起 daemon，但被
  `service.sh` 抢跑。日志停在 `killing zygote64`，没有 `zygote patched=1`、没有
  stage2 check；ledger `stage2.status=running`、`checks=unknown`。

### 9.2 修复（只动模块资产与 phase 2，不动 W1–W3）

1. `vector.zip/daemon`：在 shebang 后把 `dir` 解析成绝对路径，并 `cd / || exit 1`。
   `dir` 解析为空时也直接退出，避免带错误 classpath 启动。
2. `vector.zip/service.sh`：顶部检查 `/data/adb/ghostlock-vector-managed`，存在就
   `exit 0`，GhostLock 管理期间模块自己的 service 不抢跑。
3. `vector.zip/customize.sh`：安装时若存在
   `/data/local/tmp/.ghostlock_vector_managed`，就 `touch
   /data/adb/ghostlock-vector-managed`。
4. `src/core/attack/ops.cpp`：模块安装前 `touch
   /data/local/tmp/.ghostlock_vector_managed`；finisher 启动 daemon 后
   `rm -f /data/adb/ghostlock-vector-managed`。
5. 重新生成 `daemon.sha256` / `service.sh.sha256` / `customize.sh.sha256`，
   重打 `assets/modules/vector.zip`（`artifacts/modules/vector.zip` 同步同样修复）。

### 9.3 本地验证（已做）

- 两个 zip 的 `unzip -t` 通过；三个脚本 `sh -n` 通过；三个 `.sha256` 与实际内容一致。
- daemon cwd 冒烟：绝对路径从已删除 cwd 启动 → 走到 `exec`（cwd 已是 `/`）；
  `$0` 为相对路径且 cwd 已删除 → 在 JVM 前 exit 1。
- service.sh 标记冒烟：marker 存在 → 不启动 daemon；marker 不存在 → 启动。
- `:app:assembleRelease` 与 `:app:assembleDebug` 通过；APK 内
  `assets/modules/vector.zip` 的 sha256 与源文件一致：
  `c9959497c8cd252fd5cb825ec090962a8bb178c9a0f98d10091540c6091e594b`。
  Release APK sha256 `597018312924467e067dadd431b11ff2815471de291bce56a646af816d5dfd36`，
  Debug APK sha256 `3738b2b5d5fde4e1d2f7c39796a99ad7975fbeab0583e2c4903d375b7921a6b4`。

### 9.4 真机验收（待验证）

冷启动跑一轮，逐项确认：

1. dropbox/tombstone 不再出现 `vectord` 的 SIGSEGV；
2. per-run ksu log 出现 `zygote patched=1`、`stage2 check PASS`；
3. 设备不再进 recovery；
4. ledger `stage2.status=done`、`checks=pass`。

未完成真机冷启动前，这一项只能记为「本地修复完成，设备验收待跑」。

### 9.5 真机第一次复跑（2026-10-24 18:24）：修复被旧缓存绕过

装完 debug APK 后冷启动、跑第二轮，设备在 18:25 再次进 recovery。证据链：

- `files/modules/vector.zip` 仍是 2026-10-24 12:16 的旧包
  （9,316,843 B，sha256 `9ee832…`），不是新 APK 里的 9,317,285 B
  （sha256 `c99594…`）。`stageLspdStackModules` 对已存在且非空的
  `files/modules/<name>.zip` 直接 `continue`，所以换 APK 不会替换用户上次选中的包。
- 因此本轮 `customize.sh` 仍是旧版，没有创建 `/data/adb/ghostlock-vector-managed`；
  `service.sh`/`daemon` 也仍是旧版。
- dropbox 在 `18:24:46.505` 又出现一条 `Cmdline: vectord`、`signal 11`、
  `Cause: null pointer dereference` 的 tombstone；同一时间 stage2 日志刚装完
  Vector、停在 `killing zygote64`。这是旧 daemon 的同一个空指针崩溃，不是新修复的回归。
- 新 native 的 root 脚本本身已带 marker（`.ghostlock_root.sh` 第 226/292 行），
  但旧 `customize.sh` 不消费它。

处置：从已安装 APK 的 `assets/modules/vector.zip` 解出新包，`adb push` 到
`/data/local/tmp/vector-fixed.zip`，再用
`run-as com.ghostlock.app cp … files/modules/vector.zip` 覆盖缓存；旧包备份为
`files/modules/vector.zip.bak`。覆盖后设备内缓存 sha256 = `c99594…`，与 APK 内一致。

下一轮必须同时观察两件事：

1. `vectord` tombstone 不再新增（证明 marker/`cd /` 生效）；
2. `zygote patched=1` 与 `stage2 check PASS` 出现（证明杀 zygote 后注入成功）。

若第 1 条通过、第 2 条仍失败，剩下的 blocker 是 ReZygisk 注入门禁，不是 Vector 的 cwd。
