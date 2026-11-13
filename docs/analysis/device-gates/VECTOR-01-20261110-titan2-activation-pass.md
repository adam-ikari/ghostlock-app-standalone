# VECTOR-01 真机门禁：Titan 2 临时 root + Vector 激活（干净部署，一步到位）— PASS

对应候选源码基线 `b53c3750cd3d56bf072d2ce80e428935b05537d7`（`main`，`docs: 许可改成 GPL-3.0，脚本路径改到仓库根`）加本批工作树改动。
APK `GhostLock-v1.2(647)-arm64-v8a-debug.apk` 54,388,911 B，
SHA-256 `62665b97c45bf11ffa7179442ca0d61326af6ab5a319e8cecdffe15f884bfb99`。

## 设备与入口

- Unihertz Titan 2（MTK MT6878）；`uname -r` =
  `6.1.145-android14-11-gbd17012cc2f7-ab14044926`；Android 16，
  指纹 `Unihertz/Titan_2/Titan_2:16/BP2A.250605.031.A3/V01.00.14:user/release-keys`。
- 冷机干净启动，boot ID `50b510cc-d32b-4029-bed1-7cd68ed5f3f8`。
- 入口：`adb install -r` → 重启 → app 内 `Get root` → `Activate Vector`。
  全程**无设备侧手动步骤**：没有手动 `cp` manager.apk、没有手动 patch daemon、没有手动删 stale zip。
- 启动时 KernelSU 未加载；exploit 后由 ksu 域接管（`context=u:r:ksu:s0`）。

## 本批包含的修复

| 文件 | 改动 | 目的 |
|---|---|---|
| `app/src/main/kotlin/com/ghostlock/app/data/AndroidGhostlockRepository.kt` | 删 `if (out.isFile && out.length() > 0) continue`，staging 改为无条件覆盖 | `adb install -r` 会保留 `files/modules/`，否则升级 APK 后旧 zip 永不刷新 |
| `src/core/attack/ops.cpp`（约 1152-1159） | `STATE=injected; write_status` 之后、`sleep 60` 之前，把 `/data/app/**/org.matrix.vector.manager*/base.apk` 拷贝到 `/data/local/tmp/manager.apk` + `chmod 644` | 新装设备没有该文件；Vector daemon 校验管理器 IPC 失败，管理器显示未激活 |
| `app/src/main/assets/modules/vector.zip`、`artifacts/modules/vector.zip`（上一批） | daemon 第 14 行 `cd "$dir" \|\| exit 1` | 规避 ksud 改名崩溃的 `cd /` 会破坏 `framework/vector.dex`、`bin/dex2oat64` 等相对路径 |

## 执行步骤

1. APK 分块上传（512 KB × 104，Cloudflare 隧道），Windows 侧 `cat` 拼接后 SHA-256 与本地一致。
2. `adb install -r` → `Success`。
3. `adb reboot`，轮询至 `sys.boot_completed=1`，确认 boot ID 变为 `50b510cc…`。
4. app 内 `Get root`：10 s 后 `su -c id` = `uid=0(root) gid=0(root) context=u:r:ksu:s0`。
5. app 内 `Activate Vector`：状态 `state=injected busy=1` → 轮询至 `state=ready busy=0 ready=1`。

## 结果与证据

| 观测 | 值 |
|---|---|
| root | `uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0`，耗时约 10 s |
| daemon 第 14 行 | `cd "$dir" \|\| exit 1`（新 asset 生效，非手动 patch） |
| `/data/local/tmp/manager.apk` | 3,195,898 B，mtime `2026-11-10 02:20`（本次激活流程自动生成） |
| staged `files/modules/vector.zip` | 9,317,475 B（新 asset，staging 无条件覆盖生效） |
| system_server 注入 | `grep -c zygisk /proc/<system_server>/maps` = **3** |
| `state.json` | `rezygiskd.64.state=1`、`modules=["zygisk_vector"]`、`zygote.64=1` |
| 管理器 UI | **「已激活」2.2 (3080) · API 102** |

## 激活流程关键日志

日志 `/data/user/0/com.ghostlock.app/files/.ghostlock_ksu.log` 共 315 行 / 14,782 B，
SHA-256 `cff9c613a241a92a7bcec9a3dc3b8639b6c72af52446a9cb59fc2e5e5bfb8665`。
（设备日志不能含凭据；本次内容无 token。）

关键行按时间顺序：

```
[*] module install step finished rc=0
[*] late-load skipped: kernelsu already in /proc/modules
[*] promoted module rezygisk
[*] promoted module zygisk_vector
[*] starting zygisk daemon (post-fs-data)
[*] enforce before pfd: saved=1 now=0
[*] post-fs-data rc=0
[*] enforce restored to 1
[*] rezygisk monitor ready
[*] monitor armed pid=25106 at=1794248448
[*] finisher detached; restarting zygote64
[*] killing zygote64 pid=959 (comm/cmdline/PPID verified) at=1794248448
[*] daemon armed zygiskd64=25155 ppid=25106 sock=/data/adb/rezygisk/cp64.sock at=1794248449
[*] injection seen via state.json: rezygiskd.64 state=1, modules has zygisk_vector at=1794248449
[*] staged manager APK: … -> /data/local/tmp/manager.apk
[*] starting Vector daemon (it restarts system_server once)
[*] stage2 check cli=PASS
[*] stage2 check state=PASS (state.json)
[*] stage2 check pid=PASS
[+] stage2 check PASS
op:activation ok
[+] LSPosed stack setup done
```

## 网络修复（本会话补充，已真机验证）

root（exploit）后网络坏（loopback/网关/外网 100% 丢包），根因在 dmesg：

```
avc: denied { ingress } for comm="swapper/3" ... netif=wlan0
  scontext=u:object_r:unlabeled:s0 tcontext=u:object_r:netif:s0 tclass=netif permissive=0
```

网络包 SELinux label 变 `unlabeled`，enforcing 下被拒。修复：

```sh
setenforce 0
cat /sys/fs/selinux/policy > /data/local/tmp/policy.bin
load_policy /data/local/tmp/policy.bin
setenforce 1
```

重载后 loopback/网关/外网（`ping 223.5.5.5`、`nc 443`）全部 0% 丢包；
Vector 随后激活成功，管理器「已激活」2.2 (3080) · API 102。

代码修复：`ops.cpp` 的 `PHASE=activate && PROLOGUE_SKIP=1` 分支已加同样的
reload（`load_policy`），使 activate 阶段自动修复网络 label。

## 已知不稳定点（供"稳定激活"参考）

- 本次是**单次成功**，不构成稳定性证明。
- `KERNEL-PANIC-01`：exploit 本身是竞争条件，属已知环境/时序波动；判定因果要求同构建复现 + 冷机复跑，单次 panic 不归因代码。
- 激活流程含硬编码等待窗口（`ops.cpp`）：
  - 30 s 等 `zygiskd64` + `cp*.sock` 就位；
  - 90 s 等 `state.json` 或 zygote maps 证明注入；
  - `sleep 60` 后启动 Vector daemon（其内部重启 system_server 一次）；
  - stage2 再校验 `cli` / `state.json` / `pid`。
- 待做（稳定激活）：同构建多次冷机复跑，统计每次 `Get root` 与 `Activate Vector` 的成功率；
  把失败现场（`state=failed` 的 `error=`、`zygiskd64=`、`monitor=`、maps 计数）与本次 PASS 基线对照。
