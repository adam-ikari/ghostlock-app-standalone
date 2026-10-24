# Titan 2：app 两按钮流程真机跑通（2026-10-23）

设备 `TTANCN2100002663`，`6.1.145-android14-11-gbd17012cc2f7-ab14044926`，
KernelSU 管理器 3.3.1，Vector 2.2/3080，ReZygisk 1.0.0/515。
构建 `GhostLock-v1.2(608)`，sha256 `8435f2456891a80c20203eb20639bce657869cc8afb3b781730145bf96efb89c`。

## 结果

| 步骤 | 触发条件 | 真机结果 |
| --- | --- | --- |
| `执行` | 无 root | `ksud late-load` 载入 kernelsu.ko，`su -c id` → `u:r:ksu:s0` |
| `激活 LSPosed` | 已有 root | 两个模块装好，zygote 重启，Vector 起来 |

收官证据：

```
Framework Version: 2.2
Version Code: 3080
API Version: 102
Enabled Modules: 15
```

`/data/adb/rezygisk/state.json` 的 `root` 为 `KernelSU`，`zygisk-ptrace64` 在跑。

## 关键设计：有 root 时走 su，不再跑利用

没有 root 时按 P1 顺序跑利用，`late-load` 放最后——这个顺序不能动，因为
late-load 会把 SELinux 拉回 enforcing，此后 root child（`u:r:kernel:s0`）
任何 exec 都是 Permission denied，连 `echo 0 > /sys/fs/selinux/enforce` 也救不回来。

所以第二步不能复用第一条路径。实际做法：

1. app 调 native，native **先写栈脚本**，然后发现 `KernelSU already has root` 直接退出
   （已有 root 再跑利用会踩 re-enforce 路径 panic）。
2. app 执行 `su -c "sh /data/user/0/com.ghostlock.app/files/.ghostlock_root.sh"`。
   su 给的是 `u:r:ksu:s0`，与 adb 诊断路线同一个域，`ksud module install`、
   重启 zygote、起 daemon 全部可 exec。

对应实现：

- `src/core/session/backend/cve_2026_43499_backend.cpp`：`write_root_script()`
  移到 `ksu_root_owned()` guard 之前。
- `AndroidGhostlockRepository`：`rooted = stageComponents && (rootAvailable || deviceHasRoot())`；
  为真时不设 `GHOSTLOCK_SELINUX_FIXUP=0`（走 CLI 顺序），native 退出后
  `runRootScriptViaSu()`。
- `rootAvailable` 由 UI 状态传入（`alreadyRooted || rootEstablished`）。

## 踩过的坑

### 1. 脚本缓冲区 12 288 太小 → 写 0 字节

脚本长到 12 327 字节后 `snprintf` 截断，`files/.ghostlock_root.sh` 变 0 字节。
症状和利用失败完全一样：没有 ksu 日志、没有 su、界面毫无反应。
现为 24 576，且「写 0 字节」必须当硬错误看。

### 2. 单按钮 worker（setcon）会让内核 panic

设想在 permissive 窗口里 fork 一个 worker，把 `/proc/self/attr/current` 写成
`u:r:shell:s0`，等 late-load 完再干活。域切换本身成功：

```
[*] worker domain=u:r:shell:s0
[*] late-load exit=0
[!] KernelSU module not loaded
[*] worker released
→ kernel_panic,bug → recovery
```

即「多一个 shell 域 root 进程与 ksud late-load 并发」会打内核 BUG。全量回滚。

### 3. app 自己的 su 探测不可信

`ComponentDetector.moduleActive()` 用 `su -c id -u` 与 `/proc/modules`，
从 app 进程（untrusted_app）两者都可能拿不到，于是检测卡误报
「KernelSU module 没激活」，第二步按钮被灰掉。即使
`/data/adb/ksu/.allowlist` 里已有 `com.ghostlock.app` 也一样。

结论：能不能点由「本会话已建立 root」决定，不要靠探测。

### 4. 日志停在 `killing zygote64` 是正常现象

zygote 重启会杀掉 app 进程，`ghostlock-ksu-*.log` 的尾部就断在那里。
判定成功要看 Vector CLI 与 `state.json`。

### 5. 已有 root 时别硬跑利用

`run_setup` 的 `ksu_root_owned()` guard 就是为了防止这件事：
在已有 root 的机器上跑，PI route 第一次写就会 panic。

## 复现步骤

1. 冷启动设备（清掉内核模块状态）。
2. 装 APK，打开 app，5 项检查全绿。
3. 点 `执行`，等约 60 s，日志出现 `[+] KernelSU module loaded`。
4. 点 `激活 LSPosed`，等约 40 s。
5. 验证：

```bash
adb shell su -c "/data/adb/modules/zygisk_vector/cli status"
adb shell su -c "cat /data/adb/rezygisk/state.json"
adb shell su -c "pidof zygisk-ptrace64"
```

## 2026-10-23 18:2x：一键版又踩到两个新坑

### su 连续调用报 Text file busy

日志：

```
[*] starting zygisk daemon (post-fs-data)
.../ghostlock_root.sh[320]: /system/bin/su: Text file busy
[!] rezygisk not ready ...; NOT restarting zygote
```

`/system/bin/su` 与 `ksud` 是同一个二进制；脚本在前一步刚用 `su -c` 起了
`ksud post-fs-data`，紧接着再 `su -c` 跑门禁脚本就拿到 ETXTBSY，
门禁永远失败 → 不重启 zygote。

修法：脚本此时**已经是 root**（`id -u` = 0）且走的是 CLI 顺序
（`SELINUX_FIXUP=1`），根本不需要 su 做域切换。让 `$SU` 指向
`/data/local/tmp/.ghostlock_run.sh`（`exec sh -c "$2"`），所有调用点
原地执行。

### pstore 抓不到 panic 栈

`kernel_panic,bug` 之后 `/sys/fs/pstore/` 只有一个 `console-ramoops-0`，
内容是**正常关机**日志（14 秒时关机），没有 BUG/Call trace。
MTK 这代的 panic 不落 console-ramoops，取证还得另找路子（例如
`last_kmsg` 分区或 vendor 的 `bootreason`）。

### 一键流程的两个阶段

`执行` 一次跑完：

1. phase 1：利用 + 末尾 late-load（已有 root 时 native 自己跳过）
2. phase 2：native 写出带模块的脚本后退出，app 用 `su -c "sh <script>"` 执行

app 被 zygote 重启杀掉时，收尾靠 `setsid` 的 finisher 继续。
