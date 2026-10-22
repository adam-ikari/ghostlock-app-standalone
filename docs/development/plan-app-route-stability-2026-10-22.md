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
