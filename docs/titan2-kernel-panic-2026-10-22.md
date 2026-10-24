# 2026-10-22 真机：内核 panic 的根因（不是 LSPosed 注入）

## 结论

关机/重启的元凶是**内核 panic**，不是 framework 崩溃、不是 LSPosed 注入、不是 recovery。

根因是 **W3 的 seccomp 两位盲写落到不一致状态**（`TIF_SECCOMP=1` +
`seccomp.mode=DISABLED`），2026-10-24 的 pstore 以
`kernel BUG at kernel/seccomp.c:1349` + `Comm: ghostleaf_01234` 确认；
原「SELinux re-enforce」归因已作废（见下文）。

设备证据（`/sys/fs/pstore/console-ramoops-0`）：

```
[ 1092.769533][T12837] Internal error: Oops - BUG: 00000000f2000800 [#1] PREEMPT SMP
[ 1092.778816][T12837]  ipanic_die+0x20/0x34 [mrdump]
[ 1092.778529][T12837] pc : [0xffffffdfa7bd7828] __secure_computing+0xc4/0xc8
[ 1092.778536][T12837] lr : [0xffffffdfa7a1ba00] syscall_trace_enter+0xe8/0x1ac
[ 1092.778524][T12837] PHYS_OFFSET: 0x40000000
```

系统侧：

```
sys.boot.reason  = kernel_panic,bug
ro.boot.bootreason = kernel_panic
```

## 怎么读这条

| 线索 | 含义 |
| --- | --- |
| `__secure_computing` | **seccomp** 的系统调用入口检查，不是 SELinux/LSM 权限检查 |
| `kernel BUG at kernel/seccomp.c:1349` | `switch (current->seccomp.mode)` 的 `default: BUG()`（2026-10-24 复现补全） |
| `BUG: 00000000f2000800` | MTK 内核里的 `BUG()` 断言 |
| `ipanic_die ... [mrdump]` | MediaTek 的 panic 处理器把 oops 升级成 panic 并落 pstore |
| `PHYS_OFFSET: 0x40000000` | 与我们 profile 的 `kernel_phys_offset` 一致 ✓ |

**因果链（2026-10-24 修正）**：W3 先把 victim 的 `thread_info.flags` 清零（清
TIF_SECCOMP），再把 `seccomp.mode` 清零。两次独立盲写；若 flags 写漏、mode 写落，
victim 处于 `(TIF_SECCOMP=1, mode=DISABLED)`。它的下一次系统调用在
`__secure_computing()` 的 `default` 分支 `BUG()`。2026-10-24 的 pstore 补全了
`kernel BUG at kernel/seccomp.c:1349` 和 `Comm: ghostleaf_01234`，与这条路径逐字对得上。
证据与时间线见 `artifacts/2026-10-24-panic-seccomp/README.md`。

**作废的旧归因**：本文 2026-10-22 写的「exploit 改写内核内存 → root 脚本又把
SELinux 重新 enforce → 撞上被篡改的状态」是**误判**。`__secure_computing` 不检查
SELinux 权限；re-enforce 与 panic 只是时间相邻。10-22 的 pstore 缺少
`kernel/seccomp.c:1349` 这一行，当时没有把它和 W3 的两处写联系起来。

panic 发生在利用阶段（W3 之后的探针/唤醒），与「装模块 / 杀 zygote /
Vector daemon」无关；这一点原结论成立。

这也与上游 `AGENTS.md` 记的 `KERNEL-PANIC-01` 一致：

> `KERNEL-PANIC-01` 是已知环境/时序问题：同构建可 PASS/panic/PASS，
> 判定因果要求同构建复现 + 冷机复跑。

## 由此确定的两条设计原则

1. **「一键 root」不碰 framework**：杀 zygote / 重启 system_server / 起 Vector daemon
   这些动作都会让内核在一个**已被篡改**的状态上继续工作，风险叠加。
   现在杀 zygote 之前有硬前置（`inject_state:1` 且 `modules64` 含 `zygisk_vector`），
   不满足就**不杀** —— 宁可这一轮不激活 LSPosed，也不冒险。
2. **不做持久化**：持久化 KernelSU（`ksud install`）会把 root 变成长期的，
   且 OTA 换内核后要重装；用户明确要求不持久化。

## 同时查清的一个「假故障」

多次出现 `am start` 报 `Activity class ... does not exist`、包像是没装好 —— 真实原因是
**手机重启后处于锁屏状态**（`dumpsys user` = `0=RUNNING_LOCKED`），CE 存储
（`ceDataInode=0`）没建立，侧载的 app 起不来。**每次重启后必须先解锁手机。**
