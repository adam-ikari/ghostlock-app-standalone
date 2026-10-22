# 2026-10-22 真机事故：v1.2(586) 的「一键 root」把设备带进了 recovery

## 现象

手机（Unihertz Titan 2 / MediaTek / Android 16）上装 v1.2(586) + 换了 Shizuku 后点
「一键 root」，**手机直接关机**，重启后进了 recovery（按电源键才启动回来）。

app 侧与 native 侧**都没有任何 reboot / shutdown 调用**（全仓库 grep 过），
所以不是软件主动关机，而是 **framework 崩溃 → rescue party → recovery**。

## 根因

app 里带的是 native `479cf57`。那版 root 脚本相对**真机验证过的手写流程**
（`docs/titan2-ondevice-flow.md`）有四处偏离：

| # | 真机验证过（手写） | v1.2(586) 的 native | 后果 |
|---|---|---|---|
| A | late-load **共 2 次** | **3 次**（多了 pre-modules） | 重复加载内核模块，状态不一致 |
| B | `rm -rf` 旧模块目录：**装之前删一次** | 放进 for 循环，**每个 zip 删一次** | 删掉正在被注入进程使用的目录 |
| C | late-load / post-fs-data **全部走 `su -c`**（ksu 域） | **直接执行**（shell 域） | 域不对 → 模块 apply 不生效 |
| D | — | `su -c "... install $z"`，**`$z` 没引号** | 路径含空格/通配符就散架 |

其中 C 与「等 60 秒再起 Vector daemon」共同决定了 Vector daemon 会不会在
**注入没稳**的时候重启 system_server —— 那正是文档里记的 recovery 成因：

> **⑤ 之前必须等 60 秒**。不等就会 framework 起来一半挂掉 → 手机重启/进 recovery
> （我们 3 次里 2 次栽在这）。

`docs/review-2026-10-21.md` 第 67 行早就写了这件事：

> 真机门禁：有 ✓（多次利用成功 + Vector 激活 + 模块 hook 触发），
> 但那是**设备上的 stack3/stack4**，不是仓库里这一版 ✗ → 同步后要重跑一次门禁。

**我把这个「同步后要重跑门禁」当成了可以先发版的小事，直接发了 APK。这是我的失误。**

## 修复（v1.2(587）

1. **那段脚本逐条重写成与手机验证过的手写流程一致**：
   - `rm -rf` 旧目录**只删一次**，且在装模块之前；
   - 装模块走 `su -c` + **已安装那份** `/data/adb/ksu/bin/ksud`，路径**带引号**；
   - apply 的 `late-load` **只执行一次**；
   - `post-fs-data` 走 `su -c`；
   - 等 zygote 最多 60s；挂上后**再等 60s**才起 Vector daemon。
2. **整个 LSPosed 段改为可选**：只有 app 显式设置 `GHOSTLOCK_LSPD_MODULES`
   （= 用户在设置里选了组件包）才执行。否则「一键 root」完全不碰模块栈 ——
   装 Vector 会重启 system_server，这个风险不该由一次普通的一键 root 承担。
3. **拿不到 zygote 就不起 Vector daemon**：宁可这次不激活 LSPosed，
   也不冒把设备带进 recovery 的险。
4. app 侧新增 `LspdModuleStaging`：把用户选的组件包发布到
   `/data/local/tmp/ghostlock-modules`（app 私有目录 shell 域读不到），
   直连路径与 Shizuku 路径共用；Shizuku 路径此前**漏了**组件包 staging。

## 验证

- 桩环境（假 `su` / `ksud` / `znctl`）跑通完整 root script，日志与手写流程逐条一致：
  ```
  [*] removing stale module dirs
  [*] installing module .../vector.zip          ← su 域 + 已安装 ksud + 路径带引号
  [*] late-load (apply modules)                 ← 只 late-load 一次
  [*] starting zygisk daemon (post-fs-data)     ← su 域
  [*] zygote patched=1
  [*] waiting 60s for the zygote injection to settle
  [*] starting Vector daemon (it restarts system_server once)
  == vector cli status ==
  Framework Version: 2.2
  API Version: 102
  ```
  以及 zygote 拿不到时的保护分支：
  ```
  [!] zygote not patched; skipping Vector daemon to avoid the system_server restart loop
  ```
- gradle 构建通过 + 单测通过。
- **仍缺真机门禁**（手机不在手边）：新 native 必须先在设备上跑一次完整流程。

## 另一条独立结论：adb shell ≡ Shizuku UserService

`adb shell` 与 Shizuku UserService 给的是**同一个执行环境**：

| | uid | SELinux 域 | seccomp |
|---|---|---|---|
| `adb shell` | 2000 | `u:r:shell:s0` | 无 |
| Shizuku UserService | 2000 | `u:r:shell:s0` | 无（UserService 自己断言 `Seccomp: 0`） |

native 里也有对应代码（`cve_2026_43499_backend.cpp`）：

```
pr_success("no app seccomp filter (adb/shell flow); skipping W3\n");
```

所以「利用阶段」**不需要 Shizuku**：`scripts/run-exploit.sh` 用 adb shell 跑，
和真机验证过的路线完全一致；而 root 之后要装模块、起 Vector daemon，
root 脚本本身就是 uid 0，**用 root 权限跑脚本**即可（`scripts/start-lsposed.sh`）。

Shizuku 在这台 MediaTek 上反而是**坏掉的**（官方 13.6.0 的 UserService 起不来，
RikkaApps/Shizuku#1198），所以 adb 路线不只是更简单，还是当前唯一稳的。
