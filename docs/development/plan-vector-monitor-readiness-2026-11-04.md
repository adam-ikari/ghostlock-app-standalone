# 计划：Vector 激活的 rezygisk monitor 就绪判据（2026-11-04）

## 当前状态与基线

| 项 | 值 |
| --- | --- |
| 分支 / 提交 | `main` @ `b53c3750`（`docs: 许可改成 GPL-3.0，脚本路径改到仓库根`） |
| 改动位置 | `src/core/attack/ops.cpp` 的 activate 脚本生成段（monitor 启动 ~L1006，重启段 ~L1179-1213） |
| 现状 | 真机 2026-11-04 12:20 一次「激活 Vector」失败：`state=failed error=zygote injection gate failed`，`/data/adb/rezygisk` 只剩 `init_monitor`，没有 `state.json` |
| 已知问题 | 脚本先起 monitor（pfd #1）→ 校验 `TracerPid(init)==monitor` → **杀掉该 monitor 再起一次**（pfd #2）→ 立即杀 zygote。pfd #2 的 monitor 在 12:20 那次从未产出 `cp64.sock`，也没有 `state.json` |
| 已知可工作路径 | 2026-11-04 12:35 手工执行：杀干净所有 `zygisk-ptrace64` → `setsid sh .../rezygisk/post-fs-data.sh` → 校验 `TracerPid(init)==monitor` → 杀同一次 zygote → 1 秒内出 `state.json` |

`/data/adb/rezygisk` 的三种可观测形态：

| 形态 | 内容 | 含义 |
| --- | --- | --- |
| 健康（12:35 手工，查证） | `cp64.sock`、`init_monitor`、`state.json` | monitor 已布好 64 位注入通道并完成注入 |
| 失败（12:20 脚本，查证） | 只有 `init_monitor` | monitor 起来过，但没布注入通道，也没写 state |
| 无 monitor（12:27，查证） | 空目录 | monitor 已不在 |

## 目标与约束

目标：activate 阶段对 zygote 的一次重启能稳定注入；注入不成立时必须 **不杀 zygote**（fail-closed），并在日志里留下能区分原因的三段时间戳与就绪证据。

非目标：

1. 不改 exploit（W1/W2/W3）、不改 cred 路径 —— `docs/titan2-vector-activation-design-2026-10-28.md` §4 不变量 1 仍然有效。
2. 不改 `reboot` 阶段（`ops.cpp` L347-410）的判据；它已经 fail-closed。
3. 不改 rezygisk / zygisk_vector 模块本体与 `modinstall` 段。
4. 不做持久化安装、不碰 bootloader/verity。
5. 不放宽 L1064-1092 的注入门禁与 maps bypass、L1120-1136 的 stage2 判据。

## 已有事实

| # | 事实 | 证据等级 | 出处 |
| --- | --- | --- | --- |
| 1 | 12:20 那次 monitor 起过两次（17357→17382），`TracerPid(init)=17382` 校验通过 | 查证 | 设备 `.ghostlock_ksu.log`：`restarting rezygisk monitor pid=17357`、`monitor tracing init tracer=17382` |
| 2 | 杀 zygote 90 秒后仍无 `state.json`，`[!] rezygisk state:` 为空 | 查证 | 同上日志末段 |
| 3 | 该次运行后 `/data/adb/rezygisk` 只有 `init_monitor`，无 `cp64.sock` | 查证 | 12:27 设备 `ls -laZ /data/adb/rezygisk` |
| 4 | 健康实例目录里有 `cp64.sock`、`init_monitor`、`state.json`，`TracerPid(init)=25172` | 查证 | 12:36 设备 `ls -laZ` + `/proc/1/status` |
| 5 | 在一次 `pfd` 运行中若已有残留 monitor，新实例不会出现（`mon_after` 仍是 24855） | 查证 | `/data/local/tmp/gl-vector-manual.log` 第一轮 |
| 6 | 杀干净残留 monitor 后用 `setsid` 起一次 `pfd`，杀 zygote 后 1 秒注入成功 | 查证 | `/data/local/tmp/gl-vector-manual2.log`：`state_ok_at=1s`、`maps_zygisk=6` |
| 7 | 杀 zygote 与 monitor 启动之间的间隔：脚本里约 0-1 秒（校验一通过立即杀），手工那次约 3 秒 | 查证 | 日志时间戳；脚本 L1186-1209 无 sleep |
| 8 | 失败原因究竟是「monitor 没布好通道就杀 zygote」（竞态）还是「restart 出来的 monitor 必然不完整」 | **未验证** | 只有一次失败样本，两者都能解释 |

第 8 条是本计划要分离的核心。两个假设：

- H1（竞态）：pfd #2 的 monitor 需要时间布 `cp64.sock`，脚本在校验 `TracerPid` 后立刻杀 zygote，通道未就绪 → 丢注入。
- H2（restart 本身）：在已有 monitor 的机器上第二次 `pfd` 起出来的实例不完整（单一实例锁/被 `rm -rf` 打断），即使等待也不会 arm。

`cp64.sock` 是否出现，正好能区分两者：H1 下它会迟到出现，H2 下永不出现。

## 改动清单

### C1：只起一次 monitor，且脱离调用方会话

- 文件：`src/core/attack/ops.cpp`，L1006-1008。
- 改动：把 `run_root "… sh /data/adb/modules/rezygisk/post-fs-data.sh"` 换成等价的 detached 调用（`setsid sh … </dev/null >/dev/null 2>&1`），并在此之前 `kill -9` 掉所有 `pidof zygisk-ptrace64`。
- 理由：事实 5 表明残留 monitor 会让新实例起不来；事实 6 表明 detached + 杀干净能一次成功。`setsid` 同时解掉「monitor 继承调用方 stdio，adb/su 会话结束被牵连」的隐患。
- 注意：这段现在依赖 `run_root` 的域切换（注释 L973-985 说明 app 沙箱看不到 `/data/adb/modules`）。改动必须保留域切换，只在被执行的命令外加 `setsid` 与重定向；不能退化成 app 自己的 shell 直接跑。

### C2：就绪判据加 `cp*.sock`，并在杀 zygote 前复核

- 文件：`src/core/attack/ops.cpp`，L1014-1036（就绪轮询）与 L1179-1213（重启段）。
- 改动：
  1. 就绪条件从 `TracerPid(init)==MP` 扩成 `TracerPid(init)==MP` **且** `ls /data/adb/rezygisk/cp*.sock` 命中；轮询上限保持 30 秒（今天 3 秒内就出现）。
  2. 删掉 L1180-1196 的「杀 monitor 再 pfd 一次」整段，改为：杀 zygote 前紧邻复核 `MP` 仍是同一个 pid、`TracerPid` 仍指向它、`cp*.sock` 仍在；任一不成立则 `STATE=failed; ERROR='rezygisk monitor not armed'`，**不杀 zygote**。
  3. 日志补三行可对时的时间戳：`[*] monitor armed pid=$MP at=$(date +%s)`、`[*] zygote kill at=…`、`[*] injection seen at=…`（第三行在现有注入门禁命中时打）。
- 理由：事实 3/4 给出可观测的「armed」信号；H1 与 H2 都能被 C2 挡住，且 C2 让下一次真机运行能凭时间戳分辨 H1/H2。

### C3（日志，不改行为）

- 文件：同上，L1087-1091 的失败分支。
- 改动：失败时额外落 `ls -laZ /data/adb/rezygisk`、`pidof zygisk-ptrace64`、`TracerPid` 三项。
- 理由：今天定位靠事后手工补查，这三项应该由脚本自己写进 `ksu.log`。

## 数据流/控制流差异

改前（一次 activate）：

```
install/promote → enforce=0 → rm -rf rezygisk → pfd#1 → enforce=1
  → 轮询 TracerPid(init)==MP → (通过) → rm state.json
  → kill MP → pfd#2 → 轮询 TracerPid!=0 → setsid finisher → 杀 zygote
```

改后：

```
install/promote → enforce=0 → kill 所有 zygisk-ptrace64 → rm -rf rezygisk
  → setsid pfd（唯一一次）→ enforce=1
  → 轮询 (TracerPid(init)==MP 且 cp*.sock 存在)
  → 复核三条件 → setsid finisher → 杀 zygote
  → 轮询 state.json / maps
```

不变量（改后必须成立）：

1. 任何时刻最多一个可工作的 `zygisk-ptrace64`；起 monitor 之前先清干净。
2. 杀 zygote 之前必须观测到 `cp*.sock` + `TracerPid(init)==monitor` + monitor 仍存活；任一不满足就不杀。
3. 杀 zygote 仍然只发生一次（`docs/…-design-2026-10-28.md` §4 不变量 4：单次运行最多一次载入/注入尝试）。
4. 失败路径不改变设备状态（不杀进程、不改 enforce）。

## 兼容与回滚

- 只改生成脚本文本，不改 GLK1 profile 字段、不改 wire 格式、不改 Kotlin 契约。
- 回滚：单个 commit，`git revert` 即恢复「pfd 两次」的旧行为；旧行为今天已证明会失败，回滚只用于比对反例。
- 设备侧无需重装模块；脚本每次运行由 native 重新生成。

## 验证矩阵

| 批次 | 内容 | 命令 | 期望 |
| --- | --- | --- | --- |
| B1 | 生成脚本文本正确 | `make -C src native-host-tests` | 全部通过；`sh -n` 检查生成的 activate 脚本 |
| B2 | 构建零告警 | `make -C src ghostlock` | 无新增告警（现有 trigraph 告警除外） |
| B3 | 静态检查 | `make -C src lint-tidy` | 0 findings |
| B4 | 攻击代码形状 | `python3 tools/cmp_disasm.py <baseline> build/native/ghostlock` | 8 个攻击函数 IDENTICAL (strict)；脚本字符串变更不改这些函数的机器码 |
| B5 | 真机 A（已激活态，风险低） | 已 root 且 Vector ready 的机器上点「激活 Vector」 | 命中 L353 快退，`state=ready`，不杀 zygote、不重启 monitor |
| B6 | 真机 B（冷机） | 干净冷启动 → 取 root → 激活 Vector | 日志出现 `monitor armed pid=… at=…` → `zygote kill at=…` → `injection seen at=…`；`state.json` 含 `zygisk_vector` 与 `"64": 1`；`cli status` 打印 Framework Version 2.2 |
| B7 | 真机 C（反例，验证 C2 的 fail-closed） | 手工先起一个 monitor 再点激活 | 要么清干净成功，要么 `rezygisk monitor not armed` 且 zygote 未被杀、`pidof zygote64` 不变 |

B6 若再次失败，凭 B6 日志里 `cp*.sock` 出现时间与 `zygote kill at` 的差值判定 H1/H2，再决定是否追加等待（H1）或改为不 restart（H2）。

## 验证结果（2026-11-04，本地部分）

| 批次 | 结果 | 证据 | 等级 |
| --- | --- | --- | --- |
| B1 | **环境阻塞**：`fatal error: expected: No such file or directory` | 本机只有 g++ 11.4 与 clang 14，libstdc++ 无 `<expected>`；同一命令在基线 worktree（HEAD `b53c3750`）报同一错误 | 查证 |
| B2 | 通过 | `make -C src ghostlock` 只余 `ops.cpp:534:64 trigraph`，为这次改动之前就有的告警 | 查证 |
| B3 | 与基线一致（各 1 条既有 finding） | `make -C src lint-tidy` 在基线与改动后都只报 `ops.cpp:534 trigraph`；本次改动新增 0 条 | 查证 |
| B4 | 6 个可解析目标指令序列一致 | `tools/cmp_disasm.py` 基线（HEAD 构建）vs 候选：仅 literal pool 偏移（`#1259`→`#2320` 一类）与 objdump 符号注解不同；归一化 `#N` 与注解后 6/6 IDENTICAL（`owner_thread` 69、`waiter_thread` 935、`consumer_thread` 179、`run_main_route_threads` 413、`do_kernel5_fake_lock_route` 173、`do_one_write` 126 条指令） | 查证 |
| B4 附注 | `multicast_owner_worker`、`multicast_waiter_worker` 在两个二进制里都 MISSING | `cmp_disasm.py` 输出 `base=False cur=False`，是工具目标名与现符号名不一致，基线即如此 | 查证 |
| B4 附注 | 差异字节是同一字符串换了位置 | 基线 `0x1DF07` 与候选 `0x1E0E9` 处字节都是 `1b 5b 33 33 6d 5b 2a 5d 20`（`\x1b[33m[*] `） | 查证 |

B1 未通过不是改动引起，但**尚未用真正的 C++23 主机编译器跑过单测**，这一项在合并前仍需在具备该工具链的机器上补跑。

### 真机首次运行（2026-11-04 14:58，B5 + 部分 B6）

APK 用本地构建产物安装（sha256 `834750a8…c4e944`，含新代码），app 路径点「激活 Vector」两次：

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| B5：`state.json` 完好 | 命中快退，`state=ready`、`updated` 刷新、monitor 25172 与 zygote 30388 均未变 | `.ghostlock_ksu.log`：`activation already verified; no reinstall, no zygote restart` |
| B6′：手工 `rm state.json` 后点按钮 | 走完整流程，**新代码生效且 fail-closed 成立**：`killing stale monitor 25172` → `post-fs-data rc=0` → 30 秒轮询 → `[!] rezygisk not ready … NOT restarting zygote`，`monitor=9515 init_tracer=9515 sock=none`，zygote 30388 未被杀 | 同上日志 L298-346；`ls -la /data/adb/rezygisk` 只有 `init_monitor` |

生成的脚本本体已从设备取回并校验：`sh -n` 通过，含 `killing stale monitor`、`monitor armed pid`、`cp*.sock` 判据，不含 `restarting rezygisk monitor`。

**新事实（推翻 H1/H2 二分）**：失败与「restart」无关。同一状态下单独手工起一次 `setsid sh post-fs-data.sh` 照样只得到 `init_monitor`、没有 `cp64.sock`；期间发现一个 12:35 遗留的 `zygiskd64`（pid 25203，PPID 1）。杀掉残留 `zygiskd64` 与 monitor 后再起 pfd，仍然是 `init_monitor` 独存、且没有新的 `zygiskd64` 进程。

即：14:58 那次脚本重装了 rezygisk 模块（`/data/adb/modules/rezygisk` 全部文件 mtime 14:58），此后 monitor 能 attach init 但**不再 spawn `zygiskd64`、不再建 `cp64.sock`**；12:35 成功时同一个模块目录能建。差异原因**未验证**（候选：重装后的模块目录/mount 形态、`bin/arm64-v8a/` 与顶层 `bin/zygiskd64` 的路径选择、或 monitor 的 exec 在 ksu 域与 init 域下行为不同）。

对改动的影响：C2 的就绪判据与 fail-closed 在真机按设计工作（这是本次唯一被真机证实的结论）；C1 的「杀干净」目前只清 `zygisk-ptrace64`，是否需要同时清 `zygiskd64` 尚无充分证据，**不要据此再加代码**。

### 暂停时的设备状态（2026-11-04 15:0x）

- `zygote64` 30388 仍在跑，内存里 6 段 zygisk 映射，Vector 仍 active；
- 但 `/data/adb/rezygisk` 只剩 `init_monitor`，**没有可工作的 monitor**，也没有 `state.json`；
- 结论：下一次 zygote 重启（软重启/重启机器/系统更新）会丢掉 Vector，直到重新跑一次激活。

恢复顺序（2026-11-05 已按下面「根因」修正并执行成功，见下节）。

## 根因（2026-11-05 真机判定，推翻上一节的 H1/H2 猜测）

一次受控实验，设备同一启动（zygote 30388 已带旧 Vector 映射），按顺序取三个时刻：

| 时刻 | 观测 |
| --- | --- |
| 杀光 `zygisk-ptrace64`/`zygiskd64` 后跑 `setsid sh post-fs-data.sh`，3 s | monitor 2102 在跑、`TracerPid(/proc/1)=2102`；`/data/adb/rezygisk` 只有 `init_monitor`，**无 `zygiskd64`、无 `cp64.sock`** |
| `kill -9 $(pidof zygote64)`（30388）后 8 s | monitor 2102 自己 spawn 出 `zygiskd64` 2118（PPID=2102），`cp64.sock` 出现 |
| 同上 | `state.json` = `rezygiskd.64.state=1, modules=["zygisk_vector"], zygote.64=1`；新 zygote 2115 有 6 段 zygisk 映射（Vector 已注入） |

结论：**`zygiskd64` 由 monitor 在它启动一个 zygote 时 spawn，`cp64.sock` 随之出现**。热机状态下（zygote 已在跑）单独跑 pfd 永远不会先有 socket；monitor 不是在启动时无条件起 daemon。模块脚本体没有问题（`post-fs-data.sh` 只 `cd $MODDIR && ./bin/zygisk-ptrace64 monitor &`），`zygiskd64` 单独手工运行也能正常建 socket（2026-11-05 10:01 验证）。

对 C2 的直接影响：**「杀 zygote 前必须先看到 `cp64.sock`」这个判据在这条路径上不可满足**。2026-11-04 15:00 那次 fail-closed 不是模块坏了，而是判据顺序错了——它实际上把一次可成功的激活挡在门外。可用的等价判据是「pfd 之后 `TracerPid(/proc/1)==monitor`」，socket 与 daemon 放到杀 zygote 之后再验。

对 C1 的影响：pfd 里 `rm -rf /data/adb/rezygisk` 会 unlink 掉旧 socket，而旧 `zygiskd64` 继续持有该 inode（12:35 遗留的 25203 即此状态）。C1 只杀 `zygisk-ptrace64` 会留下这种「活着但不可达」的 daemon，是否把 `zygiskd64` 一并列入清理需要单独设计，不并入现有改动。

设备结果：2026-11-05 10:02 恢复完成，zygote 2115（新 pid）+ monitor 2102 + `zygiskd64` 2118 + `cp64.sock` + 6 段映射。

### B5 复跑（2026-11-05 11:20，经 a2mcp 桥执行）

在同一 boot（`10138c54-…`）上，以 `su -c 'sh /data/user/0/com.ghostlock.app/files/.ghostlock_root.sh'` 跑 14:58 生成的那份脚本（`PHASE='activate'`，34210 B / 790 行）：

| 项 | 观测 |
| --- | --- |
| 退出码 | `RC=0`，耗时约 1 秒 |
| 路径 | 命中快退：日志 `[+] KernelSU already loaded` → `[*] prologue skipped; selinux enforce=1` → `[*] activation already verified; no reinstall, no zygote restart`（`grep -c` = 1） |
| 状态文件 | `.ghostlock_status` 由昨天 14:58 的 `state=failed ready=0` 变为 `state=ready busy=0 ready=1`，`run_id=1793848818` |
| 进程不变量 | `zygisk-ptrace64` 2102（PPID 1）、`zygiskd64` 2118（PPID 2102）、`zygote64` 2115、`vectord` 27296 —— 跑前跑后同一组 pid，zygote 未被杀 |
| 目录不变量 | `/data/adb/rezygisk` 仍是 10:02 的 `cp64.sock` + `init_monitor` + `state.json`（186 B），L556 的 `rm -rf` 与 L563 的杀 monitor 均未执行 |
| enforce | 全程 `Enforcing`，脚本内 `SELINUX_FIXUP='0'` 未切 enforcing |

快退的四条判据（`state.json` 存在、含 `zygisk_vector`、含 `"64": 1`、`cli status` 打印 `Framework Version`）在跑前已逐条独立验过，因此破坏性分支（L556/L563/L762）不可达是**先验证再执行**的结论，不是事后推断。

附带发现（不影响本次结论）：日志末行 `[*] debug dump written to /storage/emulated/0/Download/ghostlock-debug-log/20261104-145855` 用的是 14:58 生成时烘进脚本的目录名，所以这次复跑把 debug dump 写进了昨天那个目录，而不是新开一个时间戳目录。这是 native 生成 `DEBUG_DIR` 的时机问题，属独立小缺陷，未在本次改动范围内。


## 新方案（2026-11-05）：把就绪判据拆成杀 zygote 前后两段

### 依据

根因一节证明 `cp64.sock` 由 monitor 在「启动一个 zygote」时 spawn 的 `zygiskd64` 创建（查证，2026-11-05 受控实验）。现行 C2 把它放在杀 zygote 之前判，等于要求一个只有杀完 zygote 才会出现的信号先出现；2026-11-04 15:00 的 fail-closed 就是这样挡掉了一次本来可成功的激活。

拆段之后，每段只判该时刻真实存在的信号：

| 时段 | 可观测信号 | 判据 |
| --- | --- | --- |
| pfd 之后、杀 zygote 之前 | monitor 进程、`TracerPid(/proc/1)` | monitor 存活，且 `TracerPid == monitor` |
| 杀 zygote 之后 | `zygiskd64`、`cp64.sock`、新 zygote 的 maps、`state.json` | 30 秒内出现 `zygiskd64` 与 `cp64.sock`；90 秒内 state.json 三字段命中或新 zygote maps 含 `zygisk_vector` |

### C4：判据改位（攻击路径，L 级）

文件：`src/core/attack/ops.cpp`（改动都在生成脚本文本里）。

1. **pfd 就绪轮询**（现 L1027-1057，锚点 `while [ $i -lt 30 ]`）：条件从「`TracerPid(init)==MP` 且 `cp*.sock` 命中」改为「`MP` 非空且 `TracerPid(init)==MP`」，轮询上限 30 秒不变。`ZSOCK` 继续采集，只写日志当诊断，不参与判定。
2. **杀前复核**（现 L1207-1215，锚点 `rezygisk monitor not armed`）：删掉 `CS` 条件，保留 `MP` 非空、`TP == MP`（即仍是同一个 pid 在 trace init）。不成立时报 `rezygisk monitor not armed`，**不杀 zygote**。
3. **杀后门禁**（现 L1084-1114 的 finisher，锚点 `zygote injection gate failed`）：在现有 state.json / maps 判据之外增加两个前置条件，且两段轮询分开：
   - 段 A（30 秒）：等 `DP=$(pidof zygiskd64)` 与 `CS=$(ls /data/adb/rezygisk/cp*.sock | head -1)` 同时非空。超时则 `STATE=failed; ERROR='rezygisk daemon not armed'`，落 `ls -laZ /data/adb/rezygisk`、`ps -A -o PID,PPID,NAME,ARGS | grep zygisk`、`TracerPid`，不启动 Vector daemon。
   - 段 B（90 秒，沿用现有循环）：state.json 三字段命中，或新 zygote maps 含 `zygisk_vector`；两条路径都要求 `DP`/`CS` 仍非空。
   - maps 快退路径加 `DP`/`CS` 的理由：若 zygote 实际没被杀掉，旧 maps 仍在，只验 maps 会假成功（推断，来自本段代码的路径分析，尚无真机样本）。
4. **时间戳**：保留 `[*] monitor armed pid=… at=…`，新增 `[*] daemon armed zygiskd64=… ppid=… sock=… at=…` 与 `[*] injection seen at=…`。四段时间戳（monitor armed / zygote kill / daemon armed / injection seen）能把「monitor 没起」「zygote 没死」「daemon 没起」「注入没成」分开。

不改：注入门禁 L1064-1092、stage2 判据 L1120-1136、reboot 段 L347-410、rezygisk / zygisk_vector 模块本体。

### C5：清理范围加 `zygiskd64`（独立一批，不进本次提交）

文件：同上，现 L1012-1015 的清理循环。

pfd 里的 `rm -rf /data/adb/rezygisk` 会 unlink 旧 socket，旧 `zygiskd64` 继续持有 inode（12:35 遗留的 25203 即此状态，查证），表现为「进程活着但 socket 不可达」。清理顺序改为：先杀 `zygisk-ptrace64`，再杀 `zygiskd64`，最后 `rm -rf`。

与 C4 分开提交：C4 有根因实验支撑，C5 目前只有一条现场观察（12:35 的 25203），且 2026-11-04 15:00 杀过残留 `zygiskd64` 后 pfd 仍然没建 socket，说明它至少不是充分条件。先单独跑一轮冷机确认残留会复现，再决定是否保留。

### 数据流差异

改前（C2）：

```
pfd → 轮询 (TracerPid + cp*.sock) → 热机永不满足 → fail-closed，不杀 zygote
```

改后（C4）：

```
pfd → 轮询 TracerPid==monitor → 杀前复核 → 杀 zygote
     → 段 A: zygiskd64 + cp64.sock（≤30 s）
     → 段 B: state.json 三字段 或 新 zygote maps（≤90 s）
     → 启动 Vector daemon
```

改后不变量：

1. 杀 zygote 之前只判 monitor 是否在 trace init；任一条件不成立就不杀。
2. 杀 zygote 之后 30 秒内必须同时看到 `zygiskd64` 与 `cp64.sock`，90 秒内必须看到 state.json 三字段或新 zygote 的 zygisk 映射；否则报失败、留现场、不启动 Vector daemon。
3. 单次运行仍然只杀一次 zygote（设计文档 §4 不变量 4）。

### 验证矩阵（新增批次）

| 批次 | 内容 | 命令 / 操作 | 期望 |
| --- | --- | --- | --- |
| B8 | 主机单测 | `make -C src native-host-tests` | 本机仍缺 `<expected>`（g++ 11.4，2026-11-06 复核），换机补跑 |
| B9 | 构建 + lint + 攻击形状 | `make -C src ghostlock`、`make -C src lint-tidy`、`python3 tools/cmp_disasm.py <baseline> build/native/ghostlock` | 0 新增告警 / 0 新增 finding / 6 个可解析函数 IDENTICAL |
| B10 | 真机冷机（正向） | 冷启动 → 取 root → 激活 Vector | 日志按序出现 `monitor armed`、`killing zygote64`、`daemon armed zygiskd64=… sock=…`、`injection seen`；`state.json` 含 `zygisk_vector` 与 `"64": 1`；`cli status` 打印 `Framework Version 2.2` |
| B11 | 真机负向（杀前） | 把 pfd 换成 `false`，或先手工起一个 `zygisk-ptrace64` 占住单实例槽 | `rezygisk monitor not armed`，`pidof zygote64` 不变 |
| B12 | 真机负向（杀后） | B10 运行中，在 `monitor armed` 之后、`killing zygote64` 之前 `kill -9` monitor | 不出现 `daemon armed`；`rezygisk daemon not armed`；Vector daemon 未启动 |

B10 失败时按时间戳定位：有 `monitor armed` 无 `killing zygote64` 说明杀前复核误挡；有 `killing zygote64` 无 `daemon armed` 说明 monitor 没接住新 zygote，回看 `pfsd.log` 与模块 `bin/` 下的 exec 路径。

### 本地验证（2026-11-06）

| 项 | 结果 | 证据 |
| --- | --- | --- |
| B8 主机单测 | 环境阻塞 | `make -C src native-host-tests` → `core/support/native_result.hpp:6:10: fatal error: expected: No such file or directory`；本机 `g++ 11.4.0`，与基线同一阻塞 |
| B9 构建 | 通过（clean 重建） | 唯一告警 `ops.cpp:534 trigraph ignored`，改动前既有 |
| B9 lint | 0 新增 | 基线与候选都只有 `ops.cpp:534:64: error: trigraph ignored [clang-diagnostic-trigraphs,-warnings-as-errors]` |
| B9 攻击形状 | 6/6 逐条相同（归一化后） | `cmp_disasm.py` 原始判 FAIL：脚本变长让 literal pool 整体平移，`#1936→#3520` 一类立即数不同；把符号注解、绝对地址、`#N` 归一后 6 个函数完全一致，指令数 69 / 935 / 179 / 413 / 173 / 126，与 11-04 基线记录相同。两个 `MISSING` 在基线与候选都出现，是工具目标名不带匿名 namespace |
| 生成脚本语法 | 通过 | 从 `ops.cpp` 的 snprintf 字面量重建 activate 脚本（822 行），`sh -n` 通过；杀前判据已不含 `cp*.sock` 项，脚本含 `daemon armed`、`injection seen`、`rezygisk daemon not armed` |

基线构建必须与候选同一个 NDK：本机 `local.properties` 指定 `27.1.12297006`，用它编出的 `waiter_thread` 是 935 条；换 NDK r28 后同一函数变成 961 条，形状对比就不再说明改动本身。

### 风险与回滚

- 攻击路径改动，按 §1.3 跑满 B8-B12。B8 本机跑不了时在汇报里标注，不当作通过。
- 回滚：C4 一个 commit，`git revert` 回到 C2 行为。C2 在热机上会误挡，回滚只用于对照反例。
- 真机风险集中在 B10：zygote 被杀后若 daemon 没起，设备暂时没有 Vector，重新激活即可恢复，不涉及 bootloader 或持久化。


## 明确保留

| 保留项 | 理由 |
| --- | --- |
| L1064-1092 注入门禁与 maps bypass | 2026-11-02 起就是真机验证过的判据 |
| L1120-1136 stage2 的 state/maps 双判据 | 同上 |
| L347-410 reboot 阶段 | 已 fail-closed，且本次问题不在它 |
| L973-1001 pfd 的 enforce 切换与 pre-clean 探针 | 解决的是域与 DAC 问题，与本次竞态无关 |
| rezygisk / zygisk_vector 模块本体 | 上游产物，不改 |

## 进度

- [x] 现场取证（事实 1-7）
- [x] 计划文档
- [x] 用户认可（C1+C2+C3）
- [x] 代码改动（C1/C2/C3）
- [x] B2-B4
- [ ] B1（本机缺 C++23 主机工具链，需换机补跑）
- [x] B5（快退）/ B6′（fail-closed 行为符合代码，但判据本身需改）
- [x] 根因判定（2026-11-05：socket 由 monitor 在启动 zygote 时 spawn 的 daemon 创建）
- [x] 新方案文档（2026-11-06：C4 判据改位 / C5 清理范围，见「新方案」节）
- [x] 用户认可 C4（2026-11-05 提议后回「继续」）
- [x] C4 代码（2026-11-06）
- [ ] B8（本机缺 C++23 主机工具链，需换机补跑）
- [x] B9（构建 / lint / cmp_disasm 归一化后 6/6，见「本地验证」）
- [x] B10（2026-11-10 冷机正向 PASS：`docs/analysis/device-gates/VECTOR-01-20261110-titan2-activation-pass.md`；四段时间戳齐全，`daemon armed` 与 `injection seen` 均在杀 zygote 后 1 秒内出现）
- [ ] B11 / B12（两个负向）
- [ ] C5 冷机取证后再决定
- [ ] B6（冷机）/ B7（人为 fail-closed 负向测试）
