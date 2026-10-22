# app 路线（不依赖任何外部 helper app）的架构结论 —— 2026-10-22 真机验证

设备：Unihertz Titan 2 / Android 16 (SDK 36) / MediaTek MT6878。
目标约束：**每次开机之后运行 app 就能 root + 注入模块框架**。

## 一、不再依赖 Shizuku

| | `adb shell` | Shizuku UserService（已移除） |
| --- | --- | --- |
| uid | 2000 | 2000 |
| SELinux 域 | `u:r:shell:s0` | `u:r:shell:s0` |
| seccomp | 无 | 无（UserService 自己断言 `Seccomp: 0`） |

**两者是同一个执行环境**，native 里有对应分支：

```
no app seccomp filter (adb/shell flow); skipping W3
```

而 Shizuku 官方 13.6.0 在 **MediaTek** 机型上 UserService 根本起不来
（`RikkaApps/Shizuku#1198`，复现设备就是 Unihertz Titan 2 / Android 16 / UID 2000），
所以依赖 Shizuku = 依赖一个坏掉的东西。**app 走自己的直连路线**。

2026-10-22 后续：Shizuku 依赖已**彻底删除**（gradle 依赖、UserService + AIDL、
执行入口、UI 开关、profile 里的 `recommend_shizuku` 字段全部移除），
app 只剩直连路线。证据见 `docs/titan2-shizuku-removal-2026-10-22.md`。

## 二、app 路线各环节的权限事实（都是实测）

| 环节 | 在哪个域 | 结论 |
| --- | --- | --- |
| 利用（native 拿 uid 0） | app 域 → root | ✅ 实测 `[+] child is root!`、`[+] KernelSU ready` |
| 写 `/data/adb/modules_update` | **必须 ksu 域** | shell 域 Permission denied；要用 `su -c`（late-load 后 su 被挂进 app 自己的 namespace） |
| 读组件包 | **ksu 域读不了 app 私有目录** | `/data/data/<pkg>/` 是 `app_data_file`，ksu 域读不到 → 组件包要放 **external files dir**（`getExternalFilesDir`），FUSE 路径任何域可读 |
| `ps -A` | shell 域 | ❌ 会遍历所有进程 cmdline 而卡住，判定改用 `znctl status` |
| 组件包路径传递 | root script 用 `execl` 执行 | ❌ **不继承环境变量** → 路径必须由 `RuntimeConfig` 读环境变量后**注入脚本正文** |

## 三、app 路线唯一的坑：late-load 被跳过时 su 不存在

root script 原本有：

```sh
if grep -q kernelsu /proc/modules; then
  KSU_ALREADY=1
  echo "[*] kernelsu already loaded; skipping late-load"
```

**late-load 正是把 su 挂进当前 namespace 的那一步**。KernelSU 已在内存（但 su 没挂进
app 的 namespace）时跳过 late-load → 后面段里找不到 su → 整段静默跳过。

修法：kernelsu 已加载但 `/system/bin/su` 不存在时，**补跑一次 late-load**。

## 四、组件包发布路径

```
app 私有 filesDir/modules/          （用户用文件选择器选进来的）
        ↓ app publish（app 域）
external files dir/modules-published/   （FUSE 路径，ksu 域可读）
        ↓ native root script（su → ksu 域）
ksud module install
```

## 五、真机验证结果

| 环节 | 结果 |
| --- | --- |
| app 内 native 拿 root | ✅ `[+] KernelSU ready`、界面 `exit code=0` |
| 装 ZygiskNext + Vector | ✅ `Module installed successfully!`（两个） |
| 杀 zygote → ZygiskNext injector 接管 | ✅ `inject zygote <pid> succeed` |
| zygisk 模块 preload | ✅ `zygote_states:1`、`modules64:1,zygisk_vector` |
| LSPosed 栈激活 | ✅ `[+] LSPosed stack setup done`、`cli status` → Framework 2.2 / API 102 |

## 六、两个与本项目无关但会挡路的设备现象

1. **app 装完是 `enabled=0`**，需要 `pm enable com.ghostlock.app`（或用户在
   「设置 → 应用」里启用）才能启动。
2. **手机重启后未解锁** → `dumpsys user` 显示 `0=RUNNING_LOCKED`，CE 存储未就绪
   （`ceDataInode=0`）→ **sideload 的 app 无法启动**，报
   `Activity class ... does not exist`（看起来像 APK 坏了，其实是设备没解锁）。
   **开机后先解锁手机，再打开 app。**

## 七、这一天的完整验证链（含三次误判的教训）

### 验证顺序

1. `adb shell` 路线（host 上跑 native）：**全部通过**
   - 利用一次命中 → root → KernelSU → 装模块 → 杀 zygote → 注入 →
     `zygote_states:1` / `modules64:1,zygisk_vector` → Vector daemon 起来 →
     `cli status` = Framework 2.2 / API 102 / Enabled Modules 12
2. app 路线（app 进程里跑 native）：**root 成功**（`[+] KernelSU ready`、界面 `exit code=0`），
   LSPosed 段**手动执行 root script 时也全部通过**（同样的 `zygote_states:1` /
   `modules64:1,zygisk_vector` / `LSPosed stack setup done`）

### 三次误判（都是环境问题，不是代码问题，但都暴露了真实的约束）

| # | 现象 | 真因 | 结论 |
| --- | --- | --- | --- |
| 1 | 手动跑 root script 成功，app 自动跑却停在 `modules dir=` 之后 | app 那次 `KSU_ALREADY=1`（KernelSU 已在内存）→ **跳过 late-load** → late-load 才是把 su 挂进 namespace 的那一步 → 段里找不到 su | 补一条：kernelsu 已加载但 su 缺失时**补跑 late-load** |
| 2 | `ksud module install` 报 `/data/adb/ksu/bin is not a regular directory` | app 目录里的 libksud.so 切到 ksu 域后，**看不到 /data/adb/ksu/bin**（那是 KernelSU 管理器 app 的 mount） | 装模块用**已安装那份** `/data/adb/ksu/bin/ksud`；post-fs-data 用 app 目录那份（它才有 logcat 权限） |
| 3 | `am start` 报 `Activity class ... does not exist` | **手机重启后没解锁**：`dumpsys user` = `0=RUNNING_LOCKED`，`ceDataInode=0`（CE 存储没建）→ 侧载 app 起不来 | 每次重启后**先解锁**再开 app；`pm enable` 也需要 |

### 两条路线的对照

| | adb shell 路线 | app 路线 |
| --- | --- | --- |
| 谁提供 su | adbd 的 shell（一直有） | late-load 挂进 app 的 namespace（需本次运行做过 late-load） |
| 装模块 | `su -c`（adb shell 里有 su ✓） | `su -c`（late-load 后才有 ✓） |
| 组件包可见性 | 任何域可读（`/data/local/tmp`） | app 自己的 external files dir（FUSE，ksu 域可读 ✓） |
| 结论 | ✅ 稳定 | ✅ 稳定（需先解锁 + pm enable） |

