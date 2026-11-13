# CPP-SYSSRV-01 真机门禁：system_server 就绪轮询替代固定 60s — PASS

对应工作树改动（`src/core/attack/ops.cpp`，未提交），基线二进制
`/tmp/ghostlock_pre`（同 make 流程、`sleep 60` 版本）。

门禁执行用包：`GhostLock-v1.2(647)-arm64-v8a-release.apk` 24,657,351 B，
SHA-256 `9aa055f6a279ebc625d628503f9c3e913dcad511c1b91019ec7cccd05feb16ce`。
最终发布包：`GhostLock-v1.2(648)-arm64-v8a-release.apk` 24,657,351 B，
SHA-256 `c8a573c4a375947b7385f1b82922d938e47575dbfa90b13b7b2af2e7c876a81a`
（`versionCode 648`，`versionName` 仍为 GL 的 `1.2`）。
两者 `lib/arm64-v8a/libghostlock.so` 字节相同
（SHA-256 `4b8e72e73c316ef310fbb1451a4f99174fd2d2bc0590e7317dcbed4252c0c638`），
差异仅在清单 `versionCode`，门禁结论适用于发布包。

## 设备与入口

- Unihertz Titan 2（MTK MT6878）；`uname -r` =
  `6.1.145-android14-11-gbd17012cc2f7-ab14044926`；Android 16。
- 冷机干净启动：`adb reboot` 后确认 `getenforce` = `Enforcing`、
  `su -c id` = `su: inaccessible or not found`、`pidof vectord`/`zygiskd64` 为空。
- 入口：`adb install -r` → app 内 `获取 root` → `激活 Vector`。
- CPU 对：`main=4 consumer=5`（profile 固定）；route 为 `tcp_zerocopy`。

## 本批包含的修复

| 文件 | 改动 | 目的 |
|---|---|---|
| `src/core/attack/ops.cpp`（~1266-1284） | 启动时记录 `SM_PRE=$(pidof system_server)` | 提供“旧实例”判据基线 |
| `src/core/attack/ops.cpp`（~1226-1241） | `sleep 60` → 有界轮询：`pid 非空 && pid != SM_PRE && service check activity | grep found && 同 pid 稳定 5s`，上限 120s，超时告警后继续 | 缩短第二次闪烁前等待；避免与晚就绪的框架竞争 |
| `src/core/attack/ops.cpp`（1342） | `SM_PRE="$SM_PRE" setsid sh .ghostlock_zrfinish.sh` | 轮询运行在 detached finisher 子脚本中，必须显式传递基线 |

## 执行步骤

1. APK 经 Cloudflare 隧道分块（256 KB × 95）上传，Windows 侧 `base64 -d` 后
   SHA-256 与本地一致；`adb install -r` → `Success`。
2. `adb reboot`，轮询至 `sys.boot_completed=1`；确认干净启动（见上）。
3. app 内 `获取 root`：约 30 s 后 `su -c id` = `uid=0(root) … context=u:r:ksu:s0`，
   状态 `state=root busy=0`。
4. app 内 `激活 Vector`：状态 `state=injected busy=1` → 轮询至 `state=ready busy=0 ready=1`。

## 结果与证据

| 观测 | 值 |
|---|---|
| root | `uid=0(root) gid=0(root) context=u:r:ksu:s0`，约 30 s |
| 旧实例基线 | `[*] system_server pre-restart pid=1240` |
| zygote 重启 | `[*] killing zygote64 pid=957 (comm/cmdline/PPID verified)` |
| 就绪判据 | `[*] system_server ready: pid=10606 pre=1240 stable=5s waited=15s` |
| 新 pid 与基线不同 | 10606 ≠ 1240 ✓（旧实例不可通过） |
| 等待时长 | **15 s**（固定等待版本为 60 s，缩短 45 s） |
| 后续流程 | `starting Vector daemon` → `stage2 check cli/state/pid=PASS` → `op:activation ok` → `[+] LSPosed stack setup done` |
| 最终状态 | `state=ready busy=0 ready=1 error=` |

日志：`/data/user/0/com.ghostlock.app/files/.ghostlock_ksu.log`（318 行）。
关键行：2（pre-restart）、305（kill zygote）、306-307（daemon armed / injection seen）、
309（ready）、311-317（stage2 + ok）。

### 首次验证暴露的缺陷（已修复并复测）

第一轮（未传 `SM_PRE`）日志为
`system_server ready: pid=28995 pre=none stable=5s waited=15s`：
轮询本身有效，但基线为空使判据退化为“任意非空 pid”，旧实例可能被误判为就绪。
补传环境变量后复测得到 `pre=1240`，判据完整。

## 结论

PASS。轮询在真实激活流程中把第二次闪烁前的固定 60 s 等待缩短到 15 s，且
“新实例 + binder activity + 稳定 5 s + 与旧 pid 不同”四条判据同时成立。
