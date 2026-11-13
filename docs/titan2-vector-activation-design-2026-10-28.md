# Titan 2 Vector 激活：可行方案设计（2026-10-28）

设备 `TTANCN2100002663`，内核 `6.1.145-android14-11-gbd17012cc2f7-ab14044926`，
`ro.boot.verifiedbootstate=green`（bootloader 锁定、verity 强制执行）。

## 1. 结论摘要

上周五（10-23）的成功链是 **su → `u:r:ksu:s0`**，不是 exploit 直接干活。今天的
失败点收敛到一个：**`kernelsu.ko` 没有真正进入内核**，因此 `su` 从未出现，
`ksu` 域不可达，`attr/exec` 被拒，整条链退化到 `u:r:kernel:s0`。

`/data/adb` 可写、`/system/bin` 可 exec、SELinux permissive —— 这些都已实测，
所以**不需要修 cred，也不需要常驻 root**。只需把模块真正载入。

同时确认一条硬约束：**任何改动 cred 的系统调用都会立刻 panic**，必须永久禁止。

## 2. 证据基线（本轮实测）

| 观测 | 值 | 含义 |
| --- | --- | --- |
| `Uid` | `0 0 0 0` | uid 侧成功 |
| `Gid` | `1474801080` ×4 | gid 侧为垃圾值（历次各不相同） |
| `Groups` | 空 | group_info 未落地 |
| `CapEff` / `CapBnd` | `0x1ffffffffff` | 全满 |
| `/sys/fs/selinux/enforce` | `0` | permissive |
| `domain` | `u:r:kernel:s0` | 非 ksu 域 |
| `touch /data/adb/ghostlock-vector-managed` | 成功 | **/data/adb 可写** |
| `stat /data/adb` | `700 0 0` | 权限与属主正常 |
| `grep`, `stat`, `unzip`, `readlink` | 均正常执行 | **kernel:s0 下 exec 可用** |
| `insmod` 目标 | `/data/local/tmp/kernelsu.ko`，413296 B | 已就该设备构建 |
| `$KSUD late-load --kmi android14-6.1 --allow-shell` | `exit=0`，随后 `kernelsu=0` | **静默失败** |
| `su` 探测 | `/system/bin/su`、`/system/xbin/su` 均 absent | 模块未载入的必然结果 |
| `attr/exec = u:r:ksu:s0` | `EPERM` | ksu 策略未安装 |

### 2.1 panic 现场（pstore `console-ramoops-0`，10:01）

```
kernel BUG at kernel/cred.c:459!
pc : commit_creds+0x270/0x308
lr : __sys_setresgid+0x184/0x1e0
```

这是 `setresgid(0,0,0)` 直接触发的。原因：exploit 造的 fake cred 只有 uid 侧正确，
`usage`/`group_info` 等字段不是合法对象，`prepare_creds() → commit_creds()`
的 `BUG_ON` 命中。**结论：从用户态"修" cred 这条路不存在。**

## 3. 根因链

```
fake cred 只有 uid 正确（gid 垃圾）
        │
        ├─ 不能走 cred 修复（commit_creds BUG → panic → app 被回滚）  ✗ 已排除
        │
        └─ 只能靠 ksu 域获得正常 cred
                │
                ├─ 需要 kernelsu.ko 真正 insmod
                │       └─ ksud late-load 返回 0 但未载入  ← 当前唯一阻塞
                │
                └─ 载入后：su 出现 → u:r:ksu:s0 → 10-23 已验证的流程
```

`ksud late-load` 静默失败的可能原因（需实测分辨，不预设）：

1. `--kmi android14-6.1` 与 ko 的 vermagic 不匹配，ksud 内部 insmod 失败但退出码未透传。
2. ksud 在 late-load 模式下从自己的内置资源找 `.ko`，不看 `/data/local/tmp`。
3. 内核 `CONFIG_MODULE_SIG_FORCE` 拒绝未签名模块，ksud 吞掉了错误。
4. `finit_module` 需要 `CAP_SYS_MODULE` —— 有，但 seccomp 已确认 `Seccomp: 0`，不是它。

## 4. 设计不变量（永久约束）

1. **禁止** `setresuid` / `setresgid` / `setreuid` / `setregid` / `setgroups` /
   任何走 `prepare_creds → commit_creds` 的调用。见 §2.1 的 panic 证据。
2. 保持 `enforce=0`。不做 `load_policy`，不在 late-load 之前 re-enforce。
3. 每个策略必须有**可观测门禁**（`/proc/modules`、`/proc/1/status` 的 `TracerPid`、
   `state.json`），不能靠退出码判定成功。
4. 单次运行最多一次模块载入尝试，失败即落盘诊断后停，不重试刷设备。
5. 所有诊断写入 `/data/local/tmp/`（跨重启存活、world-writable），不依赖 `run-as`。

## 5. 方案：三阶段 + 门禁

### 阶段 0：诊断快照（无风险，先跑）

在 uid 0 拿到后立刻执行，全部落 `/data/local/tmp/.ghostlock_probe.txt`：

| 探针 | 目的 |
| --- | --- |
| `cat /proc/sys/kernel/modules_disabled` | 模块加载是否被永久关闭 |
| `cat /proc/sys/kernel/tainted` | 之前 insmod 是否被拒 |
| `ls -l /data/local/tmp/*.ko` + `head -c 512` 的 `vermagic` 字符串 | ko 是否匹配本机 |
| `insmod /data/local/tmp/kernelsu.ko` 的**完整 stderr** | 拿到真实 errno（当前被吞掉） |
| `$KSUD late-load` 的完整 stderr | 同上 |
| `dmesg \| tail -40` | 内核侧拒绝原因 |

`insmod` 是本轮新增的关键尝试：我们有 uid 0、`CapEff` 全满、permissive、
`Seccomp: 0`，`insmod` 的四个前置条件全部满足。**绕开 ksud，直接载。**

### 阶段 1：载入 modules（三策略，按序，每步验门禁）

**门禁 G1**：`grep -q '^kernelsu ' /proc/modules`

| 序 | 策略 | 判定 |
| --- | --- | --- |
| 1 | `insmod /data/local/tmp/kernelsu.ko` | G1 |
| 2 | `$KSUD late-load --kmi android14-6.1 --allow-shell`（完整 stderr 落盘） | G1 |
| 3 | ko 放到 `/data/adb/` 后再 `$KSUD late-load`（排除路径问题） | G1 |

G1 不过 → 落盘 §5.0 的全部诊断，标 `STATE=failed; ERROR='module load refused'`，**停**。
不上机重试。

**门禁 G2**：`/system/bin/su -c 'echo ghostlock-su-ok'` 命中

G2 过了才允许进入阶段 2（此时才第一次有 `u:r:ksu:s0`）。

### 阶段 2：模块安装与应用（10-23 已验证顺序）

G2 之后全部通过 `su -c` 直达 ksu 域，与 10-23 成功路径完全一致：

1. `ksud module install` 装 `zygisk_vector` + `rezygisk`
2. `ksud late-load` 重新载入以应用模块
3. 把 `modules_update/*` 移到 `modules/`（late-load 不触发 promote）
4. `ksud post-fs-data`
5. 重启 zygote。**只重启 `zygote64`**：`ps -A | grep zygote` 实测只有
   `957 zygote64`，无 32 位 zygote，无连带风险。若两者都在，必须一起杀
   （10-21 只杀 64 → recovery）。
6. **等 tracer 挂上**：门禁 G3 通过（`TracerPid` 命中）后才继续。
7. **再等 60 秒**（硬性）。这是 `docs/titan2-recovery-incident-2026-10-22.md:26`
   记录的规则：注入未稳就起 daemon，Vector 的 daemon 会重启 system_server，
   framework 起一半挂掉 → rescue party → recovery。10-22 的 3 次事故里 2 次
   栽在这里，**2026-10-28 本轮又栽了一次**（见 §10）。
8. 60 秒后才起 Vector daemon（`/data/adb/modules/zygisk_vector/daemon`）。

**门禁 G3**：`pidof zygisk-ptrace64` 非空 且 `/proc/1/status` 的
`TracerPid == 该 pid`。本轮实测：`ptrace64=27269`，`init TracerPid: 27269` → 通过。

**门禁 G4**：`/data/adb/rezygisk/state.json` 的 `root` 字段为 `KernelSU`。
本轮实测：**未生成** —— daemon 尚未跑完一轮，此门禁在 daemon 启动后才有意义。

### 阶段 3：Vector 侧验证

```
su -c "/data/adb/modules/zygisk_vector/cli status"
```
期望 `Framework Version: 2.2` / `Version Code: 3080` / `Enabled Modules: 15`。

## 6. 代码改动清单

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `src/core/session/victim_process.cpp` | **删除** cred 修复块（已在本轮删除） | 触发 `cred.c:459` panic |
| `src/core/attack/ops.cpp` `write_root_script` | 新增阶段 0 探针块 | 拿到被吞掉的 insmod errno |
| 同上 | 载入策略改为 §5.1 的三策略序列 + G1 门禁 | ksud 单点静默失败 |
| 同上 | `run_root` 失败时**不要**直接 `return 1` | 上一版改动使安装步骤必然失败；`kernel:s0` 实测可 exec，原地执行是合法的第三选项 |
| 同上 | 每次运行把 pstore 快照到 `/data/local/tmp/` | 已在 fixedB 实现，保留 |
| 同上 | 所有 `%` 在脚本格式串里写 `%%` | `snprintf` 单格式串，漏转义会截断脚本 |

`run_root` 回退顺序修正为：

```
1. $SU            （ksu 域，最优）
2. attr/exec      （ksu 策略装入后可用）
3. 原地 sh -c     （已实测可 exec，可写 /data/adb）
```

第 3 条必须有，因为它是当前唯一实际可用的一条。

## 7. 验证与回滚

每次运行前记录：`boot_id`、`/proc/uptime`、`modules_disabled`、`tainted`。

- 运行后 `boot_id` 变化 → 判定 panic，读 `/data/local/tmp/.ghostlock_pstore.txt` 的
  `kernel BUG` 行，按 §4 不变量逐条比对，**先修根因再上机**。
- `uptime` 增大且 `boot_id` 不变 → 无 panic，按门禁 G1–G4 定位卡点。
- 任何一次 panic 后，先把 pstore 拷出来再改代码。

## 8. 风险与工时

| 项 | 估时 | 风险 |
| --- | --- | --- |
| 阶段 0 探针 + 三策略载入 | 1 h | 低；只读 + 一次 insmod |
| 若 G1 因签名强制失败 | — | **硬阻塞**：verity green 下无可用规避，需换思路 |
| 阶段 2/3（10-23 已验） | 1–2 h | 中；重启 zygote 有 recovery 风险 |
| 全量真机验证 | 2–3 h | 每次约 3 min，含恢复 |

最坏情况：`CONFIG_MODULE_SIG_FORCE` 开启且 ko 未签名 → `insmod` 与 `ksud late-load`
都会失败，此时本设计在阶段 1 就停住，需要重新评估是否走"无 ksu、纯原地 + 直接起
daemon"的 Track 2。

## 9. 待确认 → 已由 10-28 实测解答

1. ~~ko vermagic 匹配~~ → **不再是问题**：`kernelsu` 已在 `/proc/modules`
   （`225280 0 - Live`），说明模块载入成功过，`ksud late-load` 的 `exit=0` 是真的。
   之前的 `kernelsu=0` 是那一刻还没载入，之后某轮载入成功并保留了下来。
2. ~~`ksud late-load` 打印什么~~ → 已不阻塞，模块已在内存中。
3. ~~adb 断开~~ → 设备桥接在 Windows 宿主（`C:\platform-tools\adb.exe`），
   Linux 侧 `adb` 看不到设备是正常的；所有 adb 操作必须走 Linux shell 的
   `adb`（实际由宿主代理）。

## 10. 2026-10-28 实测进展与本次恢复模式事故

### 10.1 已确认可达的状态（全部实测）

```
/system/bin/su -c id
  → uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0
```

**这是关键突破：`su` 存在且给出 `u:r:ksu:s0` 域，gid 是正常的 0。**
不需要修 cred —— ksu 域本身就给出干净的 cred。这从正面证明了 §3 的根因链判断：
只要模块载入，cred 问题自动消失。

| 项 | 实测值 |
| --- | --- |
| `/proc/modules` | `kernelsu 225280 0 - Live` |
| `su -c id` | `uid=0 gid=0 context=u:r:ksu:s0` |
| `/data/adb/modules/` | `rezygisk`、`zygisk_vector` 均已安装（08:42） |
| `modules_update/` | 空（已 promote 完成） |
| `zygisk-ptrace64` | `pid=27269` |
| `/proc/1/status` `TracerPid` | `27269` → **G3 通过** |
| `/data/adb/rezygisk/state.json` | **不存在** → G4 未过 |
| `cli status` | `Socket Failure: Connection refused` |

结论：**rezygisk 注入层已完全就绪，只差 Vector daemon 没起来。**

### 10.2 本次 recovery 事故（我的失误，可完全避免）

我直接执行了：

```
cd /data/adb/modules/zygisk_vector && nohup ./daemon --system-server-max-retry=3 &
```

设备随即进 recovery。原因正是本文档 §5 阶段 2 第 7 条与
`docs/titan2-recovery-incident-2026-10-22.md:26` 写明的：

> **⑤ 之前必须等 60 秒**。不等就会 framework 起来一半挂掉 → 手机重启/进 recovery
> （我们 3 次里 2 次栽在这）。

当时 tracer（27269）刚挂上不久，注入尚未稳，Vector daemon 启动会重启
system_server，framework 未起完即崩 → rescue party → recovery。

**规则不是参考，是硬门禁。** 违反一次就复现一次。

### 10.3 `service.sh` 的 managed 标记陷阱

`/data/adb/modules/zygisk_vector/service.sh` 开头：

```sh
[ -f /data/adb/ghostlock-vector-managed ] && exit 0
[ -f /data/local/tmp/.ghostlock_vector_managed ] && exit 0
```

`/data/local/tmp/.ghostlock_vector_managed` 由 `ops.cpp` 在 root 脚本里创建，
所以 **`service.sh` 永远不会自己起 daemon** —— 设计上就是让 GhostLock 转完
zygote 之后再起。手动排查时若直接跑 `service.sh`，它必然静默退出，这不是故障。

另外 `service.sh` 用 `unshare --propagation slave -m`，而设备上的
toybox unshare（0.8.12-android）**不支持 `--propagation`**：

```
unshare: Unknown option 'propagation'
```

所以任何"手动起 daemon"的尝试都不能照抄 `service.sh` 那一行。

### 10.4 恢复后的正确序列（勿跳步）

```
1. su -c id                                  → 必须 ksu:s0
2. pidof zygisk-ptrace64                     → 必须非空
3. awk '/TracerPid/{print $2}' /proc/1/status → 必须等于上一步
4. 等 60 秒                                   ← 硬性，不可省
5. su -c 'cd /data/adb/modules/zygisk_vector && ./daemon ...'（不带 unshare）
6. 等 20 秒，再 su -c '.../cli status'
```

第 4 步是全部事故的唯一共同原因。省掉它，前面 3 步做得再对也会进 recovery。

## 11. `modules: []` 的真正根因：安装时跳过了 `customize.sh`（2026-10-28 晚，全离线核实）

### 11.1 结论

`ops.cpp` 的模块安装用 `/system/bin/unzip -o "$z"` **整包解压**，从不执行模块自带的
`customize.sh`。Vector 的 `customize.sh` 是 `SKIPUNZIP=1`，三件事**只在脚本里**发生：

| # | 脚本行为 | 裸 unzip 的后果 |
| --- | --- | --- |
| 1 | `mkdir zygisk/`，从 `lib/<abi>/libzygisk.so` 生成 `zygisk/<abi>.so` | `zygisk/` 不存在 |
| 2 | `bin/<abi>/dex2oat` → `bin/dex2oat32|64`、`liboat_hook32|64.so` | 留在 `bin/<abi>/` 下 |
| 3 | `sed` 替换 `daemon.apk` / `dex2oat` 内的 32 字节 anti-detection 常量 `5291374ceda0aef7c5d86cd2a4f6a3ac` | 常量原样保留 |

`zygisk/` 缺失 → rezygisk 扫不到 vector → `state.json` 的 `modules` 恒为 `[]` →
注入 zygote 时无模块可载。

### 11.2 为什么 rezygisk 裸 unzip 却能用

`rezygisk.zip` 自带**预重命名的最终名副本**：`bin/zygisk-ptrace64`、`bin/zygiskd64`、
`lib64/libzygisk.so`。所以 monitor 能起——这正是「monitor 在跑但 vector 没加载」
看起来矛盾的原因。`vector.zip` 没有这种副本，只有 `lib/<abi>/libzygisk.so`。

### 11.3 修正上一版判断

Vector **确实**走标准 zygisk 协议（`zygisk/<abi>.so`），因此 `ops.cpp:911` 要求
`state.json` 含 `zygisk_vector` 的 gate 判据是**正确的，不该改**。坏的只是安装步骤。

### 11.4 离线验证（主机模拟，零设备风险）

用 Magisk 环境 shim 驱动**真实的** `customize.sh`（不是手抄片段），产物核对：

| 断言 | 结果 |
| --- | --- |
| `zygisk/arm64-v8a.so` sha256 == `lib/arm64-v8a/libzygisk.so` | ✅ |
| `zygisk/armeabi-v7a.so` sha256 == `lib/armeabi-v7a/libzygisk.so` | ✅ |
| `bin/dex2oat32` / `dex2oat64` / `liboat_hook32.so` / `liboat_hook64.so` 生成 | ✅ |
| `daemon.apk` magic 计数 4 → 0，sha 变化，尺寸不变（3325741） | ✅ |
| `dex2oat64` 与源 `bin/arm64-v8a/dex2oat` 差异仅 28 字节，全落在 magic 区段 | ✅ |

### 11.5 代码改动

`ops.cpp` 在 `MODINSTEOF` 之前新增 `.ghostlock_customize.sh` 生成块（Magisk 环境
shim：`ui_print` / `abort` / `grep_prop` / `set_perm*` / `getprop` / `extract`），
安装循环在整包 unzip 之后按条件调用：

```sh
if grep -q 'SKIPUNZIP' <mod>/customize.sh && [ -d <mod>/lib ]; then
  sh /data/local/tmp/.ghostlock_customize.sh <mod> "$z"
fi
```

只在模块同时具备 `SKIPUNZIP` 与 `lib/<abi>/` 时触发，**rezygisk 完全不受影响**。

两个已踩的坑：

1. `API` 必须给非空默认值（34）。Vector 的 `[ "$API" -ge 29 ]` 在空值上会报
   `Illegal number` 并跳过整段 dex2oat 提取。
2. shim 内的 `getprop` 必须转发到 `/system/bin/getprop`，不能自己编值，
   否则 abilist 推导出错、只产出 `x86.so`。

### 11.6 上机时的停机点（新增 G0）

安装段之后、`pfd` 之前增加门禁 **G0**：

```sh
ls /data/adb/modules/zygisk_vector/zygisk/arm64-v8a.so
```

G0 不过就直接 `STATE=failed`，**绝不进入 kill zygote 段**。这条是本轮缺失的
停机点：此前 gate 排在 kill zygote 之后，崩溃已经发生才判定失败，等于没拦。

### 11.7 最终实现（绑定 Vector 布局，不模拟安装器）

第一版实现是「跑真实 `customize.sh` + 40 行 Magisk 环境 shim」。已废弃，原因：

1. shim 的复杂度是**永久**的，与上游是否变化无关。
2. 一个 1287 行的 C++ 文件里出现第 4 个 heredoc shell 程序，review 成本高。
3. 嵌在 `snprintf` 格式串里导致的问题实测踩了 5 个：注释里的 `"` 截断字符串、
   `%` 要写 `%%`、`$API` 空值时 `[ -ge 29 ]` 报错、`$LOG` 未继承导致日志静默丢失、
   零构建期语法检查。

改为 `.ghostlock_vector_layout.sh`（约 30 行），只做 Vector 的三件事，逐条对齐
`customize.sh`：

| 步骤 | 绑定依据 |
| --- | --- |
| `lib/<abi>/libzygisk.so` → `zygisk/<abi>.so`，按 `ro.product.cpu.abilist` 逐 ABI | `mkdir -p "$MODPATH/zygisk"` 是 Vector 独有签名 |
| `bin/<abi>/dex2oat`、`liboat_hook.so` → `bin/dex2oat32\|64`、`liboat_hook32\|64.so` | 64 位优先；32 位仅当 ABI 不同 |
| `daemon.apk` 全替换 / `dex2oat` 首替换 `5291374c…` | 原脚本 `tr -dc 'a-z0-9' \| head -c 32`，字符集照抄 |

**触发判据**：模块 `customize.sh` 含 `mkdir -p "$MODPATH/zygisk"`。实测 vector
命中 1 次、rezygisk 命中 0 次，rezygisk 的预置副本路径不受影响。

**顺带修掉的既有 bug**：modinstall 脚本经 `run_root` 作为独立 `sh` 进程执行，
不继承调用方的 `$LOG`，因此所有 `>>"$LOG"` 都是空重定向、日志全部静默丢失。
已在 modinstall 内补 `LOG=` 定义。

**构建验证**：`libghostlock.so` 内 `ghostlock_vector_layout` 出现 2 处
（生成点 + 调用点）。

### 11.8 离线验证结果

| 断言 | 结果 |
| --- | --- |
| 布局产物与真实 `customize.sh` 产物逐项一致（6 个路径） | ✅ |
| `zygisk/<abi>.so` 与 `lib/<abi>/libzygisk.so` 字节等同 | ✅ |
| `daemon.apk` magic 4 → 0，尺寸不变，替换串 32 字符 `[a-z0-9]` | ✅ |
| 完整 modinstall E2E：`EXIT=0`，`vector layout rc=0` | ✅ |
| G0 反向：故意破坏判据 → `EXIT=3`，zygote 未被触碰 | ✅ |

上机脚本：`scripts/verify-vector-layout-ondevice.sh`，在装 APK 并点 apply 之后
运行，直接判定 `zygisk/*.so`、反检测常量、`state.json` 三项。

## 12. 真机验证：G0 + 完整激活通过（2026-11-02）

### 12.1 环境

| 项 | 值 |
| --- | --- |
| 设备 | `TTANCN2100002663`，Android 16 / API 36，`arm64-v8a` |
| boot_id | `da4eeb8b-78e5-4c5f-9d6a-5f4f562327f8` |
| APK | build 647，`sha256 0890bebc…`，与本地 `GhostLock-v1.2(647)-arm64-v8a-debug.apk` 逐字节相同 |
| `libghostlock.so` | APK 内 `sha256 4643f1db…`，含 `ghostlock_vector_layout` 2 处 |
| 启动方式 | 干净冷启动（kernelsu 未载、无 su、SELinux enforcing） |

### 12.2 G0 布局在真机 toybox 下通过

把 `vector.zip` 解到 `/data/local/tmp/vztest/zygisk_vector`（**必须解进模块目录本身**，
解到父目录会让所有 `[ -f ]` 静默失败），执行 `.ghostlock_vector_layout.sh`：

| 断言 | 结果 |
| --- | --- |
| `zygisk/arm64-v8a.so` sha256 == `lib/arm64-v8a/libzygisk.so` | ✅ `8642b0ed…` |
| `bin/dex2oat64`、`bin/liboat_hook64.so` 生成 | ✅ 232008 B / 3456232 B |
| `daemon.apk` 反检测 magic `5291374c…` 计数 | ✅ 0 |
| `dex2oat64` 内 magic 计数 | ✅ 0 |

### 12.3 完整激活门禁逐项通过

app 的「激活 Vector」按钮在本轮不可用（见 12.4），因此用 app 自己生成的脚本、通过
`su` 手动执行同一份 `PHASE=activate` 脚本：

```
adb shell su -c 'setsid sh /data/user/0/com.ghostlock.app/files/.ghostlock_root.sh'
```

日志 `/data/user/0/com.ghostlock.app/files/.ghostlock_ksu.log` 的实际顺序：

| 门禁 | 观测 | 结果 |
| --- | --- | --- |
| G0 布局 | `vector layout rc=0`，`zygisk/arm64-v8a.so` 存在 | ✅ |
| 模块提升 | `MODINSTEOF` 后 `/data/adb/modules/zygisk_vector` 完整 | ✅ |
| pfd | `post-fs-data rc=0`，`enforce restored to 1` | ✅ |
| 监控 | `monitor tracing init tracer=12713`（等于 `pidof zygisk-ptrace64`） | ✅ |
| zygote 重启 | 只杀一次 `zygote64 pid=958` | ✅ |
| 注入门禁 | `state.json` 含 `zygisk_vector` 且 `"64": 1` | ✅ |
| 60 秒等待 | `STATE=injected` 后 `sleep 60`（08:39:16 → 08:40:16 状态不变） | ✅ |
| daemon | `vectord` 启动，等 20 秒 | ✅ |
| stage2 | `cli=PASS state=PASS pid=PASS` | ✅ |

### 12.4 最终状态（独立复核）

```
/data/user/0/com.ghostlock.app/files/.ghostlock_status
  state=ready busy=0 ready=1 boot_id=da4eeb8b…

su -c '/data/adb/modules/zygisk_vector/cli status'
  Framework Version: 2.2
  Version Code: 3080
  API Version: 102
  Enabled Modules: 14

/data/adb/rezygisk/state.json
  root: KernelSU
  rezygiskd.64.state: 1
  rezygiskd.64.modules: ["zygisk_vector"]
  zygote.64: 1

ps
  zygisk-ptrace64 12713 (PPID 1)
  vectord         18740 (PPID 12716)
  zygote64        21065 (PPID 1)
```

`Enabled Modules: 14` 是 Vector 自己数出的 Xposed 模块数，10-23 的 15 是同一台设备上
另一个模块集，不是本链缺项。

### 12.5 app 侧阻塞：修复状态（2026-11-02 晚）

| # | 阻塞 | 状态 | 证据 |
| --- | --- | --- | --- |
| 1 | `PHASE=root` 携带 `GHOSTLOCK_LSPD_MODULES`，提前 late-load 后脚本失去写/exec 能力 | **已修（native）** | `write_root_script` 对 `phase=="root"` 强制空列表 |
| 2 | 已 root 时 activate/reboot 被守卫直接拒绝，走不到脚本 | **已修（native）** | 守卫改为 `su -c "sh <script>"` 并以其退出码退出 |
| 3 | app uid 未授权，无法 exec `su` | **待用户授权** | `run-as … su` → `ENOENT`；`com.ghostlock.app` 不在 allowlist |
| 4 | `vectorActive` 只在内存，app 重开显示未激活 | **已修（Kotlin，见 §12.8）** | 冷启动由 `.ghostlock_status` 复算：`state=ready` + `ready=1` + `boot_id` 匹配当前 boot |
| 5 | KernelSU 管理器被禁用（`enabled=3`），授权 UI 不存在 | **已恢复** | `pm enable me.weishu.kernelsu` → `enabled=1` |

#### 12.5.1 改动与构建

- `src/core/attack/ops.cpp`：`write_root_script()` 在 `phase == "root"` 时把
  `LSPD_MODULES` 参数强制成空串，不依赖 Kotlin 侧是否传值。
- `src/core/attack/ops.cpp`：activate 脚本在 `STATE=root` 之后、安装分支之前加
  「已激活」快退：`state.json` 含 `zygisk_vector`、含 `"64": 1`，且
  `cli status` 打印 `Framework Version` → `state=ready`，直接 `exit 0`，
  不安装、不杀 zygote。
- `src/core/session/backend/cve_2026_43499_backend.cpp`：already-rooted 守卫在
  `phase` 为 `activate`/`reboot` 时 fork + `execl("/system/bin/su", "su", "-c",
  "sh <script>")`，`waitpid` 后 `std::exit(code)`；`phase=root` 仍按原样拒绝。
- 构建：`make -C src ghostlock` 通过（只有既有的 trigraph 警告）。
- 产物：stripped `510752 B`，`sha256 bca232db…`，已热替换到
  `/data/app/~~sZ9hOG…/lib/arm64/libghostlock.so`，`system:system 755
  u:object_r:apk_data_file:s0`。

#### 12.5.2 真机验证（不需要 root 授权）

用 app 自己的 GLK1 profile（需 4 字节大端长度前缀）在 `run-as` 下直接跑新 native：

| 断言 | 结果 |
| --- | --- |
| `PHASE=activate` 脚本生成 | ✅ 33668 B，`sh -n` 通过 |
| 快退片段在脚本第 352–360 行 | ✅ |
| `PHASE=root` 且调用方传了模块目录 | ✅ 头部 `LSPD_MODULES=''`，33576 B，`sh -n` 通过 |
| 已 root 守卫命中 | ✅ `running the activate script through su` |
| 未授权时 su 失败且不跑利用 | ✅ `execl su failed errno=2`，`exit=127` |
| 运行后 Vector 状态 | ✅ `state=ready busy=0 ready=1`，三个 pid 不变 |

最后一次错误测试（`GHOSTLOCK_HOME` 被外层 shell 提前展开成 `/`）确实让利用跑了一轮，
但它在 route 阶段干净失败、没有拿到 root，boot_id / pid / Vector 状态都没变；
这不是新代码的路径，正确设置 `GHOSTLOCK_HOME` 后守卫每次都命中。

### 12.6 结论与剩余步骤

布局（G0）与完整激活链已在真机跑通，Vector active 且稳定；app 侧的两个 native
阻塞已修并热推。剩下只有一步需要用户在设备上完成：

1. 解锁屏幕；
2. 打开 KernelSU 管理器（已重新启用，`me.weishu.kernelsu` v3.3.1）；
3. Superuser 列表里给 `GhostLock` 授权；
4. 回到 GhostLock 点「激活 Vector」。

预期：守卫命中 → `su` 执行脚本 → 快退分支 `state=ready`、`exit 0`，不安装、
不重启 zygote，app 把 `vectorActive` 置真。之后 `vectorActive` 的内存态问题
（第 4 项）再单独处理。

### 12.7 当前状态注记（2026-11-04）

同机（boot_id `10138c54-…`，Android 16）上 app 点「激活 Vector」一次失败：
`state=failed error=zygote injection gate failed`，`/data/adb/rezygisk` 只剩
`init_monitor`，无 `cp64.sock`、无 `state.json`；该次脚本先起 monitor（17357）、
杀重启（17382），校验到 `TracerPid(init)=17382` 后立即杀 zygote 9526。

同一 boot 内手工跑「杀干净全部 `zygisk-ptrace64` → `setsid` 起一次
`post-fs-data.sh` → 校验 `TracerPid(init)` → 杀一次 zygote」成功：1 秒出
`state.json`（`KernelSU` / `rezygiskd.64.state=1` / `modules=[zygisk_vector]` /
`zygote.64=1`），新 zygote 带 6 段 zygisk 映射；`vectord` 起来后 `cli status` 报
Framework Version 2.2 / 14 个模块。再跑 app 生成的 `.ghostlock_root.sh` 命中
L353 快退，`.ghostlock_status` 变 `state=ready busy=0 ready=1`。

所以 §12.6 的「用户点按钮即可」在 11-04 这个 boot 上不成立，阻塞点在脚本的
monitor 重启段，不在 su 授权。改动计划见
`docs/development/plan-vector-monitor-readiness-2026-11-04.md`。

> 本节「阻塞点在脚本的 monitor 重启段」的归因已被 2026-11-05 的受控实验推翻：
> 真正的问题是 C2 就绪判据的顺序（`cp64.sock` 只在 monitor 启动 zygote 后才由
> 其 spawn 的 `zygiskd64` 创建），详见该计划文档「根因」节。

## 12.8 `vectorActive` 的持久化恢复（2026-11-05，M 级）

### 动机

真机 11-05 复核：设备侧 Vector 完全在位（monitor 2102 追 init、`zygiskd64` 2118、
`cp64.sock` + `state.json`、新 zygote 2115 带 6 段映射、`cli status` 报 Framework
Version 2.2 / 14 模块），`.ghostlock_status` 也是 `state=ready busy=0 ready=1`，
但 app 第 2 步显示「未激活」。原因是这一步的 `done` 绑的是纯内存字段
（`GhostlockUI.kt:962` ← `GhostlockUiState.vectorActive`，初值 `false`），
而它唯一的写入点在「本次会话内 activate 跑成功」的 `finally` 里
（`GhostlockViewModel.kt:1099`）。第 1 步的 root 状态有持久化恢复
（`ComponentDetector.rootedInThisBoot()` 读 `boot_id`），第 2 步没有，所以 app
一重开就退回未激活，并连带把第 3 步「软重启」按钮锁死
（`GhostlockUI.kt:972` 的 `enabled` 依赖 `state.vectorActive`）。

### 影响文件

| 文件 | 改动 |
| --- | --- |
| `app/src/main/kotlin/com/ghostlock/app/runtime/ComponentDetector.kt` | 新增纯函数 `vectorActivated(statusFile, currentBootId)` 与 `vectorReady(context)` |
| `app/src/main/kotlin/com/ghostlock/app/domain/repository/GhostlockRepository.kt` | 新增 `suspend fun vectorReady(): Boolean` |
| `app/src/main/kotlin/com/ghostlock/app/data/AndroidGhostlockRepository.kt` | 实现：`withContext(Dispatchers.IO)` 调探测器 |
| `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockViewModel.kt` | `refreshSnapshot()` 复算并写入 `vectorActive` |
| `app/src/test/kotlin/com/ghostlock/app/runtime/ComponentDetectorTest.kt` | 4 条新用例 |

### 判据与行为差异

判据三条同时成立才算激活：`state=ready`、`ready=1`、`boot_id` 等于当前 boot。

与 `rootedInThisBoot()` 不同，这里**必须**看 `state`：脚本只在验证过注入的路径上
写 `ready`，中途死亡的 supervisor 会留下 `starting`/`installing`/`soft_reboot`，
那些都不能点亮这一步。

| 场景 | 改前 | 改后 |
| --- | --- | --- |
| 冷启动，文件 `ready` 且同 boot | 未激活（需再点一次） | 已激活，第 3 步可用 |
| 冷启动，文件来自上一个 boot | 未激活 | 未激活（冷启动必然丢注入，正确） |
| 会话内 activate 成功 | 已激活 | 已激活（脚本 `exit 0` 前已写 `ready`，两条路径结论一致） |
| activate 失败 | 保持上一次内存值 | 未激活（文件已是 `failed`，方向更保守） |
| 软重启成功 | 已激活 | 已激活（reboot 段 L408 同样写 `STATE=ready; READY=1`） |

残余假阳性：文件 `ready` 但注入已实际丢失（如 monitor 掉线后 zygote 重启）。此时
第 3 步可点，但 reboot 阶段自带 fail-closed 判据（`src/core/attack/ops.cpp` L347-410，
本次未改），设备不会被推向无注入状态；显示层不做二次实时探测，因为 untrusted_app
读不到 `/data/adb`，加探测只会引入新的授权依赖。

不改：native 脚本、`.ghostlock_status` 字段与写法、`.ghostlock_run_started` 语义、
L347-410 reboot 段。

### 验证

| # | 项 | 命令 | 结果 |
| --- | --- | --- | --- |
| V1 | 新用例 | `./gradlew :app:testDebugUnitTest --tests '*ComponentDetectorTest*'` | 新增 4 条全过（同 boot ready、跨 boot ready、`failed`/`installing`、`state` 与 `ready` 不一致/文件缺失/`boot_id` 为 null） |
| V2 | 全量单测 | `./gradlew :app:testDebugUnitTest` | 130 条，3 条失败，均为既有失败（见下） |
| V3 | 构建 | `./gradlew :app:assembleDebug` | `BUILD SUCCESSFUL`，无 Kotlin 告警；产物 `GhostLock-v1.2(647)-arm64-v8a-debug.apk`，`sha256 9dc40d86a900…` |
| V4 | 真机 UI | 装新 APK → 冷启动 app（不动进程与状态文件） | 待做 |

V2 的 3 条失败与本次改动无关，且已确认在改动之前就存在：前两条用 HEAD
（`b53c3750`）独立 worktree 复跑，失败用例名逐字相同；第三条
（`ComponentFetcherTest > the bundled components are the ones the root script installs`）
只读 `ComponentFetcher.Defaults`，该文件在工作树中未被改动（`git status` 不含它），
故同一断言在 HEAD 必然同结果。

这三条指向的是暂存组件识别的既有问题，本次**只记录线索，不下结论**（未验证）：
`Defaults` 第一个组件是 `key=zygisknext` / `label=ZygiskNext` / `moduleId=zygisksu` /
`fileName=zygisknext.zip`，而 `app/src/main/assets/modules/` 里实际打的是
`rezygisk.zip`，测试则期望 `["rezygisk", "zygisk_vector"]`；同时
`stagedVersion()` 只看 `<filesDir>/modules/<fileName>`，全仓没有任何把
`assets/modules/*.zip` 复制到 `filesDir` 的代码（`grep assets/modules` 无命中），
「bundled 即视为已暂存」这条断言所依赖的机制在代码里找不到。是否影响真机的
检测卡显示与模块安装，需单独取证，属独立缺陷，不在本次改动内，未顺手修。

另：`./gradlew :app:lintDebug` 报 1 error / 6 warnings，唯一 error 在
`app/src/main/AndroidManifest.xml:6`（`QUERY_ALL_PACKAGES`，`ManagerScanner`
扫描已装管理器所需），改动涉及的四个 Kotlin 文件在 lint 报告里零命中。该项不在
本仓库对 app 改动的既定门槛（`assembleDebug` + `testDebugUnitTest`）内，仅作额外取证。

