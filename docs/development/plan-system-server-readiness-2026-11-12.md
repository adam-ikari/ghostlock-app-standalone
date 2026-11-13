# 计划：activate 阶段用 system_server 就绪轮询替代固定等待

日期：2026-11-12
级别：L（触及攻击关键路径 `src/core/attack/ops.cpp` 的 activate 流程时序）

## 动机

activate 阶段在注入 zygote 后固定 `sleep 60` 才启动 Vector daemon（daemon 会重启
system_server，即第二次花屏）。固定等待有两个问题：

- system_server 提前就绪时白等，整体激活时间被拉长，用户看到“第二次闪烁间隔很长”。
- system_server 延迟就绪时 60s 可能不够，daemon 与框架启动竞争。

## 改动

把 `sleep 60` 换成有界就绪轮询，条件同时满足才继续：

1. `pidof system_server` 非空；
2. pid 与脚本启动时记录的 `SM_PRE` 不同（旧实例不能通过判据）；
3. `service check activity` 返回 `found`（binder 已注册）；
4. 同一 pid 连续稳定 5 秒（排除重启中的 pid 抖动）。

上限 120s，超时写 warning 日志（含 pid/pre/stable/waited）后继续原流程（daemon 启动
本身还有 `--system-server-max-retry=3`）；成功时写
`system_server ready: pid=... pre=... stable=...s waited=...s`。

`SM_PRE` 在脚本启动、任何 zygote 重启之前采集一次，因此 root / reboot 阶段也会多一次
`pidof`（无副作用）。

轮询实际运行在 detached finisher 子脚本（`.ghostlock_zrfinish.sh`）中，而 `SM_PRE`
在主脚本里，因此启动 finisher 时必须显式传递：`SM_PRE="$SM_PRE" setsid sh …`。
2026-11-13 真机首次验证发现漏传，日志出现 `pre=none`（判据退化为“任意非空 pid”但
等待仍然生效）；已补传，待复测确认 `pre=` 为真实 pid。

未改动：注入 zygote 的杀进程顺序、daemon 启动参数、后续 `sleep 20` 与 CLI 轮询。

## 影响文件

- `src/core/attack/ops.cpp`（仅 activate 脚本字符串）

## 验证

| 项 | 结果 |
| --- | --- |
| NDK 构建 `make -C src ghostlock` | 通过，零告警 |
| 8 个攻击函数指令形状对比 | 逐函数比对：指令条数一致、去立即数后逐条一致（仅字面量池整体位移 464B，因脚本字符串变长） |
| `make -C src lint-tidy` | 0 findings（全部 suppressed 在非用户代码） |
| `:app:assembleRelease` / `:app:testDebugUnitTest` | BUILD SUCCESSFUL |
| 真机门禁（冷机干净启动，KSU 未加载） | **PASS**：`system_server ready: pid=10606 pre=1240 stable=5s waited=15s`；等待 60s→15s；新 pid≠旧 pid；`state=ready`。记录见 `docs/analysis/device-gates/CPP-SYSSRV-01-20261113-titan2-readiness-poll-pass.md` |

基线二进制：`/tmp/ghostlock_pre`（同一 make 流程、`sleep 60` 版本，
与 gradle 产物 sha 一致）。比对脚本：`tools/cmp_disasm.py` + 去立即数逐条比对。

## 待办

**冷启动门禁**（已完成，2026-11-13）：PASS，见
`docs/analysis/device-gates/CPP-SYSSRV-01-20261113-titan2-readiness-poll-pass.md`。
日志：`system_server ready: pid=10606 pre=1240 stable=5s waited=15s`。

**改进第 2 项**（待执行）：daemon 启动后的 `sleep 20`（`ops.cpp` 轮询块之后、`STATE=daemon` 之前）
同样改为新 pid 判据轮询；需要另跑一轮冷启动门禁验证。
