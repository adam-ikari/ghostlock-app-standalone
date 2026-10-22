# 2026-10-22 真机：内核 panic 的根因（不是 LSPosed 注入）

## 结论

关机/重启的元凶是**内核 panic**，不是 framework 崩溃、不是 LSPosed 注入、不是 recovery。

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
| `__secure_computing` | LSM/SELinux 的安全计算钩子，每次系统调用都要走 |
| `BUG: 00000000f2000800` | MTK 内核里的 `BUG()` 断言 |
| `ipanic_die ... [mrdump]` | MediaTek 的 panic 处理器把 oops 升级成 panic 并落 pstore |
| `PHYS_OFFSET: 0x40000000` | 与我们 profile 的 `kernel_phys_offset` 一致 ✓ |

**因果链**：exploit 改写内核内存（cred / 安全上下文）→ 此刻 root 脚本又把 SELinux
重新 enforce（`echo 1 > /sys/fs/selinux/enforce`）→ 正好有进程在走
`__secure_computing` 权限检查 → 撞上被篡改的状态 → 内核 oops → MTK mrdump
把它升级成 panic → 手机重启。

**所以**：panic 发生在**利用/root 阶段**，与「装模块 / 杀 zygote / Vector daemon」
这些 LSPosed 步骤无关。之前把它归因成「Vector daemon 重启 system_server →
rescue party → recovery」是**错的**（`kernel_panic,bug` 已经排除了那条路）。

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
