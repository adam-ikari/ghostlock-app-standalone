# 完全脱离 PC 的「一键临时 root」流程

> 目标：**手机自己搞定**，不接 PC、不用别的设备。
> 前提：下面的 app 与文件已经装/放进手机（一次性准备）。

## 一次性准备

手机上要有：

| 东西 | 作用 | 怎么来的 |
| --- | --- | --- |
| **「GhostLock Titan 2 版」** | 一键触发利用 + KernelSU late-load；主页有「运行前检查」面板 | 本仓库构建（上游 + Titan 2 profile）；上游正式版内置 profile 后直接用正式版 |
| **KernelSU 系管理器 APK**（官方 KernelSU v3.3.1，`me.weishu.kernelsu`） | 提供 `ksud`（late-load 要用） | 官方 release；**必须 v3.3.1+**，它才有 `android14-6.1` 的 `kernelsu.ko` |
| **Vector 管理器 app**（`org.matrix.vector.manager`） | 装/启用 LSPosed 模块、设作用域 | 随 Vector 模块 zip 里的 `manager.apk` |
| `ZygiskNext-v1.5.2.zip` / `Vector-v2.2.zip` | 放进手机存储（如 `/sdcard/Download/`），供管理器从本地安装 | 官方 release |
| **Shizuku**（可选但推荐） | 让 app 以 shell 身份跑利用 → 跳过 W3 阶段，更快更稳 | 装好后用「无线调试」起一次 |

## 每次开机：一步

1. 打开 **「GhostLock Titan 2 版」** → 先看「运行前检查」面板（缺什么会写清楚）→ 点 **执行**。
2. 等十几秒，日志里出现：

   ```
   [+] child is root!
   [+] KernelSU ready
   ```

   → 临时 root 有了，KernelSU 已在内存里加载（`su` 可用）。

3. 如果要 LSPosed：打开 **Vector 管理器** → 模块列表里应该已经能看到 ZygiskNext / Vector
   （它们在 `/data/adb/modules/`，第 2 步的 late-load 会自动 apply 并跑 stage 脚本）→
   启用模块、选作用域 → 装自己的模块。

> 顺序很重要：**先**利用 + late-load（它负责把 ZygiskNext/Vector 跑起来），**后**用管理器装/启用模块。
> 装了新模块后再点一次 GhostLock 的「执行」也可以（late-load 会重新 apply）。

## Vector（LSPosed 框架）的激活方法 —— 实测结论

### 四个环节

```
① KernelSU 进内存
   su -c 'ksud late-load --kmi android14-6.1 --allow-shell'
   → /proc/modules 出现 kernelsu

② ZygiskNext 守护进程起来
   su -c 'rm -f /data/adb/zygisksu/znctx; cd /data/adb/modules/zygisksu && (./bin/zygiskd daemon &)'
   验证：znctl status → modules64:1,zygisk_vector

③ zygote 带上注入点
   正常情况：守护进程起来后它自己处理（zygote_states 会变成 1）
   必要时：重启 zygote（两个都要杀！只杀 zygote64 本机直接进 recovery）
   验证：znctl status → zygote_states ≥ 1

④ Vector daemon 起来（它靠重启 system_server 把 VectorService 注进去）
   su -c 'cd /data/adb/modules/zygisk_vector && (./daemon --system-server-max-retry=3 &)'
   验证：/data/adb/modules/zygisk_vector/cli status → Framework Version: 2.2 / API Version: 102
```

### 顺序反了就是 recovery

④ 必须在 ③ 成功之后：否则 daemon 注不进去 → 反复重启 system_server → framework 起不来 →
rescue party → **recovery**（recovery 里 adbd 关着，adb/fastboot 全够不着，只能长按电源键）。

### 实测记录（2026-10-21，3 次尝试）

| 尝试 | ② | ③ | ④ | 结果 |
| --- | --- | --- | --- | --- |
| 14:20 | ✅ | ✅（zygote 当时是刚 boot 出来的） | ✅ | **成功**：framework 活着，`cli status` 正常，探针模块被识别（`Loading legacy module ...`） |
| 16:33 | ✅ | ⚠️ 手工只杀 zygote64 | — | framework 没起来 → recovery |
| 17:2x | ✅（`zygote_states: 1`） | ✅（守护进程自己挂上的） | ✅ | `cli status` 正常（Framework 2.2 / API 102），**但随后 framework 没起来** → 又一次 recovery |

**结论**：Vector 本身能在本机激活（成功那次是真的活了，模块被框架识别），
但 **④ 的 system_server 重启在本机很不稳**：3 次里 2 次把 framework 弄挂。
怀疑与「被注入的是不是刚 boot 出来的 zygote」有关（成功那次 zygote 是新的）。

→ 实践建议：**要稳定用 LSPosed 栈，应该在一次干净重启后立刻按 ①②③④ 走**，
   让守护进程赶在 zygote 之前就位；或者干脆接受「Vector 激活 = 一次框架重启」的代价。

### 与「一键 root app」的关系

app 只负责 root（利用 + late-load），**不碰 LSPosed 栈**：
Vector 的激活属于独立模块管理器（Vector / LSPosed）那条线的事。
如果只要临时 root，**不要**起 ④ —— 那一步是本机最不稳的动作。

## 验证

```sh
# 用 Vector 管理器 app 或任何 root shell 都能看
/data/adb/ksu/bin/znctl status          # root_status: ✅KernelSU / modules64:1,zygisk_vector
/data/adb/modules/zygisk_vector/cli status   # Framework Version: 2.2 / API Version: 102
```

## 还是需要 PC 的部分

只有「一次性把文件弄到手机上」这一步可以省不掉（APK / zip 只能用 PC 或浏览器的下载来搬），
其余全部可以在手机上完成。仓库里 `docs/titan2-temp-root-guide.md` 里的命令行路线
（`adb push` + `adb shell`）是给有 PC 的人用的等价物。
