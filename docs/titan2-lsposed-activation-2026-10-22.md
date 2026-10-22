# 2026-10-22 真机验证：临时 root + LSPosed 全链路（Unihertz Titan 2 / Android 16 / MediaTek）

> 设备：`TTANCN2100002663`，Titan_2，Android 16，MT6878，6.1.145-android14-11-gbd17012cc2f7-ab14044926。
> 宿主：Windows + Git Bash，adb 走 USB。
> 结果：**一键 root ✅　LSPosed 框架激活 ✅（Framework 2.2 / API 102 / Enabled Modules 12）**

## 一、结论先行

| 环节 | 状态 | 关键证据 |
| --- | --- | --- |
| 利用拿 root | ✅ 一次命中 | `tcp route done=1 calls=1 success=1 status=0 clean=1/1` → `[+] child is root!` |
| adb 路线 = Shizuku UserService | ✅ 同等 | `no app seccomp filter (adb/shell flow); skipping W3` |
| KernelSU late-load | ✅ | `late-load exit=0` → `[+] KernelSU module loaded` |
| 装 ZygiskNext + Vector | ✅ | `Module installed successfully!`（两个） |
| zygote 注入 | ✅ | `zygote_states:1`、`modules64:1,zygisk_vector` |
| Vector daemon | ✅ | `Framework Version: 2.2 / Version Code: 3112 / API Version: 102 / Enabled Modules: 12` |

## 二、完整可用流程（adb 路线，不需要手机上装任何东西）

```bash
# ① 拿 root（adb shell = uid 2000、shell 域、无 seccomp，与 Shizuku UserService 等价）
adb push ghostlock-native /data/local/tmp/ghostlock
adb push profile.bin    /data/local/tmp/profile.bin
adb shell chmod 755 /data/local/tmp/ghostlock
adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin
#    → [+] child is root!

# ② 装模块 + 激活 LSPosed（root 之后全部用 root 权限跑脚本）
adb push vector.zip zygisknext.zip /data/local/tmp/modules/
./scripts/start-lsposed.sh 6.1.145-android14-11-gbd17012cc2f7-ab14044926 .

# ③ 验证
adb shell 'su -c "/data/adb/modules/zygisk_vector/cli status"'
#   Framework Version: 2.2
#   API Version: 102
#   Enabled Modules: 12
```

**为什么不再依赖 Shizuku**：`adb shell` 与 Shizuku UserService 给的是**同一个执行环境**
（uid 2000 / `u:r:shell:s0` / 无 seccomp），native 里有对应分支
`no app seccomp filter (adb/shell flow); skipping W3`。而 Shizuku 官方 13.6.0 在
**MediaTek 机型**上 UserService 根本起不来（见 `docs/titan2-shizuku-userservice-1198.md`），
所以 adb 路线不只是更简单，在这台机器上是**唯一稳的**。

## 三、这一轮踩到并修掉的坑（都在 `b8d6c6c` / `a05eb8b`）

### 1. root script 不继承环境变量（`execl` 不是 `execle`）

`victim_process.cpp` 用
`execl("/system/bin/sh", "sh", script_path, nullptr)` 执行 root script，
**环境变量不会传过去**。所以 `GHOSTLOCK_LSPD_MODULES` 根本到不了脚本里 ——
表现为脚本里 `$GHOSTLOCK_LSPD_MODULES` 恒为空 → 整段 LSPosed 逻辑静默跳过。

修法：`RuntimeConfig` 读环境变量 → 转义（`'` → `'\''`）→ **注入脚本正文**
（和已有的 `ksud_path` 同一套路）。

### 2. 不重启 zygote，注入永远不生效

zygisk 模块是在 **zygote 启动那一刻**被 preload 的。运行时装模块对已经跑着的
zygote 没有任何影响 —— 这就是「`modules64:1,zygisk_vector` 但 system_server 里
没有注入痕迹」的原因。

必须杀掉 zygote64 让 init 重启，ZygiskNext 的 injector 才会接管新 zygote
（日志里能看到 `run zygote-injector for <pid>` → `inject zygote <pid> succeed`，
新 zygote 的启动参数带 `-Xzygote`）。

> `webview_zygote` 是 `zygote64` 的子进程，杀 zygote64 会一起带走它。
> **两个都杀**（只杀一个 framework 起不来 → recovery）。

### 3. Vector daemon 用相对路径会静默退出

Vector 的 `daemon` 是 shell 脚本，内部用 `dir="${0%/*}"` 定位自己的目录。
用相对路径（`cd $DIR && ./daemon`）→ `dir="."` → `-Djava.class.path=daemon.apk`
变成相对路径 → 直接崩：

```
java.lang.UnsatisfiedLinkError: Expecting an absolute path of the library:
  ./daemon.apk!/lib/arm64-v8a/libdaemon.so
```

**必须用绝对路径**：`unshare -m /data/adb/modules/zygisk_vector/daemon ...`。

顺带：Vector 自带的 `service.sh` 用 `unshare --propagation slave`，
**toybox 的 unshare 不支持 `--propagation`**（只支持 `[-imnpuUr]`），
得退化成 `unshare -m`。

### 4. su 刚被注入时会挂住

KernelSU 是 late-load **之后**才把 `/system/bin/su` 注入进去的，而且注入后
su helper 可能阻塞等 KernelSU service 稳定 —— 挂住的调用会拖死整个 root script
（表现为日志停在某一行不动）。

修法：
- su 路径直接查 `/system/bin/su`、`/system/xbin/su` 并**等待出现**（最多 15s），
  不用 `command -v`；
- 所有 su 调用统一包一层 `timeout 30`。

### 5. shell 域里 `ps -A` 会卡住

`ps -A` 要遍历所有进程的 cmdline，在 shell 域里非常慢（实测一次调用能吃掉
几十秒）。判定 zygisk daemon 是否起来改用 `/proc` 扫描。

### 6. 每次轮询都 fork su 也不现实

等 zygote 注入时若每次都 `su -c "znctl status"`，90 次轮询根本跑不完。
改成**一次 su 调用内轮询**（辅助脚本写到 `/data/local/tmp/.ghostlock_znwait.sh`，
在 ksu 域里循环，主流程读结果）。

### 7. 组件包要放哪

- app 只能写自己的 filesDir（`/data/local/tmp` 是 shell 用户的）；
- root（ksu 域）**能读 app 私有目录**（实测 `ls /data/data/<pkg>/files/...` 通）；
- 但 **shell 域读不了** app 私有目录 → 所以 native 脚本里列举 zip 和
  `module install` 都要放进 `su -c`（ksu 域）里做。

## 四、app 侧改动（对应 `b8d6c6c` / `a05eb8b`）

- `LspdModuleStaging`：把用户选的组件包发布到 app 私有目录下的
  `files/modules-published`（app 写得了吗 ✓，root 读得到吗 ✓）；
- **没选组件包就完全不设 `GHOSTLOCK_LSPD_MODULES`** → native 跳过整段 LSPosed 逻辑
  → **「一键 root」永远不会因为它把设备带进 recovery**（这是 10-22 事故的根治）；
- native 的 root script 恢复成**只有 root + late-load**（上游原样），
  LSPosed 栈作为可选段接在后面。

## 五、这次的失误（记录下来）

- 我为了定位卡点**反复手动执行 root script**，每次都会杀一次 zygote。
  反复杀 zygote 最终让 framework 起不来 → **进 recovery**。
  **不要手动重复执行会杀 zygote 的脚本**；要复现就重启手机再来一次。
- 2026-10-21 那次「一键 root 关机进 recovery」的根因同样是这里：
  那版 native 从没上过真机，我却发了版（`docs/titan2-recovery-incident-2026-10-22.md`）。
