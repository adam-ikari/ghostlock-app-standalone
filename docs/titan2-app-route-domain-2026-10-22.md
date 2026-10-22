# app 路线为什么做不完 LSPosed：root child 落在 kernel 域（2026-10-22）

## 结论（查证）

app 路线拿得到 root，但拿不到**能做事的域**。利用把 root child 的 cred 换成
`init_cred` 后，进程的 SELinux 上下文是 **`u:r:kernel:s0`**。kernel 域在
enforcing 下几乎不能 exec 任何东西，而 app 路线的两个动作都会把 SELinux 从
permissive 拉回 enforcing：

1. root 脚本的 SELinux 策略重载（`load_policy`）——加载策略即设 enforcing；
2. `ksud late-load`——模块 init 会重新 enforcing（脚本自己的注释就写了这点）。

于是脚本只要走到「重载策略」或「late-load」之后，任何 `sh` / `ksud` / `su`
都失败或卡死。

CLI（adb）路线没有这个问题：脚本由 **adb shell 进程（uid 2000, shell 域, 无 seccomp）**
执行，用 `su` 切进 `u:r:ksu:s0` 再干活；`su` 从 shell 域是允许的。

## 证据

### 1. root child 与它的 worker 都在 kernel 域

`artifacts/2026-10-22-device-dump/x/data/local/tmp/dmesg.txt`：

```
[  134.688176] avc: denied { use } for comm="ghostleaf_01234" path="pipe:[112382]"
               scontext=u:r:kernel:s0 tcontext=u:r:untrusted_app:s0:c42,... tclass=fd permissive=1
[  143.707288] avc: denied { write } for comm="ghostleaf_01234"
               path="/data/data/com.ghostlock.app/files/.ghostlock_native.log"
               scontext=u:r:kernel:s0 ... permissive=1
[  144.364351] KernelSU: tp_marker: unmark process: pid:25426, uid: 0, comm:sh
```

`ghostleaf_01234` = 利用的 victim child（日志 `handoff: child=17443`），
`sh` pid 25426 = `handoff: root shell worker pid=25426`。两者 scontext 都是 kernel。

### 2. 一旦 enforcing，kernel 域 exec 直接 EACCES

跑 `SELINUX_FIXUP=0`（跳过策略重载）那一轮，
`files/ghostlock-ksu-1792661397218.log`：

```
[*] policy reload skipped (SELINUX_FIXUP=0)
[*] late-load exit=0
[+] KernelSU module loaded
[*] modules dir=.../modules-published-1792661397165
timeout: exec sh: Permission denied
[*] module install step finished
timeout: exec /data/adb/ksu/bin/ksud: Permission denied
[*] late-load exit=126
[!] zygisk daemon not ready (...); NOT restarting zygote
```

late-load 之后 SELinux 已回到 Enforcing（`getenforce` = `Enforcing`，
`dmesg` 里后续拒绝 Log 的 `permissive=0`），kernel 域连 `/system/bin/sh` 都 exec 不了。

### 3. `su` 从 kernel 域卡死；从 shell 域正常

- app 路线：`timeout 10 /system/bin/su -c 'echo ghostlock-su-ok'` 卡住 35s 以上，
  脚本阻塞在 `pipe_read`（`/proc/32754/wchan`），`timeout` 也杀不掉。
- 同一时刻从宿主 `adb shell timeout 8 su -c id` → `uid=0(root) ... context=u:r:ksu:s0`，rc=0。

所以卡的不是 su 二进制，是**调用者域**。

### 4. 更早那两次「掉线」不是内核 panic

`/sys/fs/pstore/console-ramoops-0`（本次 dump 取的这份）里没有任何
`Oops` / `__secure_computing` / `Kernel panic`。之前的两次「设备消失」是脚本
卡在 `[ -x /system/bin/su ]` 这个 stat 上（su 是 magic mount，stat 会阻塞），
adb 跟着不响应，看起来像掉线。

## 已改掉的东西（本日构建 592）

| 改动 | 状态 | 结论 |
| --- | --- | --- |
| W3 前加 finit_module 预探针，放行则跳过 W3 | 已实现 | 真机上探针报「被挡」，W3 照跑 → 没起作用，也没坏处 |
| `GHOSTLOCK_SELINUX_FIXUP=0` 跳过策略重载 | 已实现（默认 1） | **方向错**：跳过重载后 late-load 照样 enforcing，反而更早死 |
| 去掉 su 的 `-x` stat 探测，改成执行式探测 | 已实现 | 解决了卡死（脚本能跑完），但暴露出 kernel 域 exec 被拒 |
| late-load 决策不再 stat `/system/bin/su` | 已实现 | 同上 |

脚本语法、括号平衡在每轮构建前都用去注释的字符串提取 + `sh -n` 验过。

## 下一步的两条路（都需要真机）

### 路线 P1：把活全干在 permissive 窗口里，late-load 挪到最后

利用后 SELinux 是 permissive，此时 kernel 域什么都能做。把顺序改成：

1. 装模块（`ksud module install`，permissive → 允许 exec）
2. `ksud post-fs-data` → zygiskd 起来
3. 等 `inject_state:1`
4. 起 Vector daemon
5. 重启 zygote（此时注入由 zygiskd 接管；`kill` 不需要 exec）
6. 最后才 `late-load`（它会 enforcing，但之后不再需要 exec）

风险：zygote 重启时 kernelsu.ko 还没加载，ZygiskNext 的注入是否仍成立未知。
判据：`inject_state:1` 与 LSPosed 管理器显示的 Framework Version 2.2 / API 102。

### 路线 P2：late-load 之后再补一次 SELinux 写（攻击关键路径改动）

native 在脚本报告 late-load 完成后，再跑一次 W1（只写 selinux_state 的
permissive 位），把 permissive 拿回来，然后继续执行。

代价：多一轮内核写（历史上 oops 就出现在写阶段），属于 L 级改动，
按上游 AGENTS.md 需要：计划文档 + `cmp_disasm` 8 函数 + 真机门禁记录。

### 不建议的方向

- 让 app 自己起 adbd 再 `adb shell` 自己：手机上没有 adb 客户端，adbd 要么要 root
  （鸡生蛋），要么要用户每次开机手动开无线调试 + 配对。
- 通过 Shizuku 拿 shell 域：已从代码里彻底删除，且官方 13.6.0 在该机型必崩。

## 本次取回的现场

`artifacts/2026-10-22-device-dump/`：

- `glk-dump2.tgz`：app 私有目录全量（4 份 root 脚本日志、`.ghostlock_native.log`、
  `.ghostlock_root.sh`、run_state）、`dmesg.txt`、`getprop.txt`、`modules.txt`、
  `enforce.txt`、`pstore-ls.txt`、`sys/fs/pstore/*`
- `logcat.txt`：整段 logcat

（`glk-dump3.tgz` 传输失败，内容与 dump2 重叠，只有 `/data/adb/modules` 列表缺失。）

## 决策（2026-10-22）

同一份记录也在 brain `app-route-decisions`。

**D1 先跑 P1，不再走「少写内核」这条路。**
需要 exec 的步骤全部放进 SELinux permissive 窗口：装模块 → `post-fs-data`
（zygiskd 起来）→ 等 `inject_state:1` → 起 Vector daemon → 重启 zygote →
**最后**才 `late-load`。判据：一轮真机出现 `[+] KernelSU module loaded`
+ `[*] module install step finished` + `inject_state:1` + LSPosed 管理器
Framework 2.2 / API 102，且设备不掉线。若 zygote 重启时注入不成立
（此时 kernelsu.ko 尚未加载），才转 P2。

**D2 app 路线不用 su，也不对 magic mount 做 stat。**
su 从 kernel 域卡死在 `pipe_read`（`timeout` 杀不掉），从 shell 域正常；
`[ -x /system/bin/su ]` 会阻塞整个脚本（两次 app 运行都死在这里）。

**D3 当前对外口径：CLI 路线可用，app 路线在修。**
分发 README、文档、app 内表述都按此写，不把 app 路线写成已验证。

**D4 P2 冻结。** late-load 之后补一次 `selinux_state` 写属于多一轮内核写，
按上游 AGENTS.md 要计划文档 + `cmp_disasm` 8 函数 + 真机门禁，只有 P1 判死才启动。

**D5 遗留开关在 P1 定论后清理。** `GHOSTLOCK_SELINUX_FIXUP` 默认 1（不启用跳过）；
W3 前的 `finit_module` 预探针保留但不算解法；避免同一问题留两套机制。
