# 计划：app 路线的临时 root 稳定性修复（2026-10-22）

## 问题

app 路线能拿到 root（真机日志：`[+] child is root!` → `[+] KernelSU ready`），
但设备在同一次运行里重启/进 recovery，导致 root 与 LSPosed 激活都拿不住。

## 判据

**算修好**：连续 3 次「解锁 → 点执行」，3 次都出现
`[+] KernelSU ready` + `[*] module install step finished` + `[*] zygote patched=1`
+ `[+] LSPosed stack setup done`，且期间设备不掉线。

**算没修好**：任一次运行里设备重启进 recovery，或激活步骤没跑完。

**算改坏**：exploit 本身失败（拿不到 root），或 `ksud late-load` 失败
（KernelSU 模块没加载），或 LSPosed 框架版本不再是 2.2 / API 102。

## 已有的事实（证据等级：查证）

| 事实 | 出处 |
| --- | --- |
| app 进程带 seccomp filter（`Seccomp=2 Seccomp_filters=1`），shell 域没有 | 设备 `files/.ghostlock_native.log` 第 42 行；native `cve_2026_43499_backend.cpp` 的 `no app seccomp filter (adb/shell flow); skipping W3` 分支 |
| app 路线比 shell 路线多 2 轮内核写（W3b/W3c，各一次 heap spray + PI 竞争） | 同上日志：`heap spray start` 出现 4 次（W1a/W2a/W3b/W3c），shell 路线只有 W1a/W2a |
| oops 是 SELinux LSM 钩子上的 MTK `BUG()` 断言 | pstore：`pc: __secure_computing+0xc4/0xc8`、`lr: syscall_trace_enter`、`BUG: 00000000f2000800`、`ipanic_die [mrdump]` |
| oops 出现在 tap 后约 1 分钟，不在 exploit 的 17 秒里 | oops 所在 boot 的 uptime 1092.77s，该 boot 起点由「设备掉线时刻」反推 |
| 两次 recovery 重启都是用户态 reboot 系统调用，与 LSPosed 步骤无关 | `persist.sys.boot.reason.history` = 两条 `reboot,userrequested,recovery`；其中一次对应的是**不含 LSPosed 步骤**的 CLI 路线 |
| shell 路线也发生过同样的 recovery 重启 | 同上（15:00:41 那条对应 CLI 路线） |

## 候选改动

### C1：W3 之前先探针，探针说「不需要」就跳过

W3 的作用是把 root child 的 seccomp filter 清掉，好让它能 `finit_module` 装 KernelSU 模块。
child 里已经有一个 fork 出来的 `finit_module` 探针（`verify_seccomp_probe_stage`），
判定逻辑是「SIGSYS / EPERM / ENOSYS = 被挡；其它 = 放行」。

改动：在写 TIF_SECCOMP / seccomp.mode **之前**先跑一次同样的探针。
放行 ⇒ 两轮内核写都不需要，直接返回；被挡 ⇒ 走原来的 W3b/W3c。

- 收益：app 路线的内核写轮数从 4 降到 2，和 shell 路线同档。
- 风险：child 的 filter 留着，`su -c` 派生的 worker 也带着 filter。
  这些 worker 只需要跑 shell 命令和 `finit_module`（放行时探针已证明）→ 可接受。
- 门禁：`tools/cmp_disasm.py` 对比 8 个攻击函数（w3 会有**已复核的差异**），
  真机门禁按上面的判据跑。

### C2：不重载 SELinux 策略

root 脚本现在会把 exploit 留下的 permissive 用 `load_policy` 恢复成 enforcing。
策略重载会重新初始化 LSM 钩子，而此时内核里的 cred / 安全上下文已经被 exploit 改过 ——
这正是 `__secure_computing` oops 的形状。

改动：把 fixup 变成可选，默认关（留在 permissive）。exploit 本来就把 SELinux 置成 permissive，
不重载只是**不恢复** enforcing，不是引入新状态；重启即恢复。

- 收益：利用之后不再碰 LSM 的重初始化路径。
- 代价：设备本次开机内 SELinux 处于 permissive（任何 app 的越权操作只记日志不拦截）。
  这是一台已经被 root 的测试机，且重启即失效；但属于安全姿态降级，要写进文档与 UI 提示。

### C3（诊断，不改行为）

下次 panic 后立刻抓完整 pstore（含 oops 前后的内核日志），确认 oops 到底发生在
`load_policy`、模块安装，还是别的 cred 变更上。

## 验证顺序

1. C3 的取证：如果设备这次没挂，pstore 就没有新证据；用 C1+C2 的组合跑，
   掉了线就说明「少动内核」这个方向有效，同时 pstore 会留下现场。
2. C1 + C2 一起上（两个开关都由 app 传），跑上面的判据（连续 3 次）。
3. 若 3 次全过，再分别关掉 C1、关掉 C2 各跑一轮，确定哪一项是必要的，
   把不必要的去掉（少改代码、少降级）。

## 不做

- 不改 exploit 的 W1/W2 写 cred 的逻辑（上游核心代码，改动面太大）。
- 不做持久化安装（用户明确要求）。
- 不解锁 bootloader、不刷 boot。

## 执行结果（2026-10-22 18:20 更新）

三轮真机（构建 592）把这三条候选逐个证伪/证实：

| 候选 | 真机结果 | 判定 |
| --- | --- | --- |
| C1 先探针、放行则跳 W3 | `pre-W3 seccomp finit_module probe` 报「被挡」，W3b/W3c 照跑 | 没起作用；保留没坏处，但**不解决 app 路线的稳定性** |
| C2 跳过 SELinux 策略重载 | 脚本不卡了，但 `late-load` 自己会 enforcing，随后 `exec sh: Permission denied` | **方向错**，已把 app 侧默认改回「重载」 |
| C4 去掉 su 的 `-x` stat 探测 | 卡死消失（脚本能跑到底） | **有效**，保留 |
| C3 抓 pstore | 本次 pstore 无 Oops；之前的「掉线」是 su stat 卡住导致 adb 无响应 | 之前的 panic 归因需要重做 |

根本原因见 `docs/titan2-app-route-domain-2026-10-22.md`：root child 在
`u:r:kernel:s0`，enforcing 下不能 exec，而策略重载与 late-load 都会把 SELinux
拉回 enforcing。

因此本计划的「少动内核」方向只对了一半：真正要改的是**执行顺序**（把活干在
permissive 窗口内，late-load 挪到最后），见该文档的路线 P1 / P2。

## 批次记录：P1 实现（构建 593，待真机门禁）

**级别与动机**：M 级（`src/core/attack/ops.cpp` 的 root 脚本正文 + Kotlin 环境变量），
不触 W1/W2/W3 与 race/waiter/payload 路径。动机见上一节的域结论：脚本里的 root child
在 `u:r:kernel:s0`，late-load 之后该域 exec 全被拒，必须把需要 exec 的步骤全部放进
permissive 窗口。

**改动（3 处，全部在 `write_root_script`）**

| 位置 | 改动 |
| --- | --- |
| 启动段的 late-load 决策 + `KSU_READY` 门禁 | `SELINUX_FIXUP=0` 时整段跳过，记 `app route: late-load deferred to the end of the run`，`KSU_ALREADY=0` |
| 栈内 su 探测 | `SELINUX_FIXUP=0` 时不探测 su，写 `/data/local/tmp/.ghostlock_run.sh`（`exec sh -c "$2"`）并把 `$SU` 指向它——栈里既有调用点（`"$SU" -c "..."`）一行不改，只是不再切域 |
| 栈内「apply modules」的第二次 late-load | `SELINUX_FIXUP=1` 才执行；app 路线跳过，避免在起 daemon/杀 zygote 之前回到 enforcing |
| 脚本末尾（栈之后） | `SELINUX_FIXUP=0` 时执行唯一一次 late-load，并记 `kernelsu` 是否进 `/proc/modules` |
| 栈开头 | 已安装的 `/data/adb/ksu/bin/ksud` 不存在时退回 app 包里的 libksud.so，不再直接放弃整段栈 |

app 侧 `AndroidGhostlockRepository` 在发布组件包目录的同时设
`GHOSTLOCK_SELINUX_FIXUP=0`，即「选了组件包就走 app 路线」。

**验证证据（本机，无真机）**

1. `sh -n` 通过。脚本用「按字符串字面量提取 + 去 C 注释」的抽取器取出
   （注意 `"$MODDIR"/*.zip` 里的 `/*` 会被朴素的注释剥离吃掉，抽取器必须按字符串状态机扫）。
2. 把脚本抽出来在主机上整轮跑（stub `ksud` / `znctl` / `setenforce` / `id` / `uname`），
   `SELINUX_FIXUP=0`，顺序与 D1 一致：
   `policy reload skipped` → `late-load deferred` → `module install exit=0`（两个包）
   → `starting zygisk daemon` → `zygisk daemon ready` → `killing zygote64 pid=...`
   → `zygote patched=1` → `starting Vector daemon` → `LSPosed stack setup done`
   → `late-load (last step)`。同一次模拟先抓到两个真问题：早期 late-load 未跳过、
   `$SU` 包装脚本未被 exec（后者是模拟环境缺 `/system/bin/sh` 所致）。
3. `cmp_disasm.py`（基线 `592` 的 `build/native/ghostlock`，sha256 `215f4ef5…`）：
   8 个攻击函数全部 `LAYOUT-SHIFT`，指令数与助记符一致，仅注解地址操作数随布局变化
   （`owner_thread` 69 / `waiter_thread` 892 / `consumer_thread` 177 /
   `run_main_route_threads` 417 / `do_kernel5_fake_lock_route` 174 / `do_one_write` 126，
   与基线完全相同）；对照实验：同一份源码不带本次改动重建 → 与基线 byte-identical，
   说明工具链确定性没问题，差异确实只来自新增的脚本字符串。`MISSING
   multicast_owner_worker/multicast_waiter_worker` 在基线自比时同样出现，是本目标的
   工具属性，不是本次回归。
4. `:app:assembleDebug` 成功；APK 内 `lib/arm64-v8a/libghostlock.so` 含新增字符串。

**待办**：真机门禁（构建 593）由用户手机执行，日志经
`Download/ghostlock-debug-log/` 导出后回传；判据是 `late-load` 出现在最后且
LSPosed 管理器显示 Framework 2.2 / API 102。若 zygote 重启时注入不成立
（此时 kernelsu.ko 尚未加载），才启动 P2。

## 真机门禁结果（2026-10-22，构建 593）— PASS

用户手机（Titan 2 / Android 16 / MT6878）执行 `ghostlock-593d-debug.apk`
（sha256 `885148e3f1f8…`），**模块框架成功激活**。

证据等级：用户口述结论，**没有归档当次日志**（手机与开发机不在一处，日志未回传）。
所以这条记录只证明「这一条路径在真机上成立」；具体每一步的日志仍需补一份才能
当作完整门禁证据链。要补的话：`Download/ghostlock-debug-log/<时间>/` 里
`*.log.txt` 全上传一次即可。

结论与后续：

- P1 顺序（模块 → `post-fs-data` → `zygisk daemon ready` → 杀 zygote →
  `zygote patched=1` → Vector daemon → 最后 `late-load`）在真机成立，
  不需要再启动 P2（late-load 之后补写 `selinux_state` 的 L 级方案保持冻结）。
- D3 口径变更：app 路线不再是「在修」，它是**主交付形态**；adb 路线退为诊断用途。
- 遗留开关（`GHOSTLOCK_SELINUX_FIXUP` 默认值、W3 前 `finit_module` 预探针）
  现在可以进入清理批次：app 路线显式传 `SELINUX_FIXUP=0`，CLI 路线才用 1。
