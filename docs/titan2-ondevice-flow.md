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

## Vector（LSPosed 框架）的激活方法 —— **已验证可用（2026-10-21）**

### 五个环节（顺序错一步就 recovery）

```bash
# ① KernelSU 进内存（利用的副产物）
adb shell 'su -c "/data/adb/ksu/bin/ksud late-load --kmi android14-6.1 --allow-shell"'

# ② 装两个模块（必须走 su 域：root 脚本在 shell 域写不了 /data/adb/modules_update，
#    且要用已安装那份 ksud —— /data/adb/ksu/bin/ksud）
adb shell 'su -c "rm -rf /data/adb/modules/zygisk_vector /data/adb/modules/zygisksu"'
adb shell 'su -c "/data/adb/ksu/bin/ksud module install /data/local/tmp/modules/zygisknext.zip"'
adb shell 'su -c "/data/adb/ksu/bin/ksud module install /data/local/tmp/modules/vector.zip"'

# ③ 再 late-load 一次（把模块 apply 上）
adb shell 'su -c "/data/adb/ksu/bin/ksud late-load --kmi android14-6.1 --allow-shell"'

# ④ 起 ZygiskNext 守护进程（它把注入点挂进 zygote）
adb shell 'su -c "nohup /data/adb/ksu/bin/ksud post-fs-data >/data/local/tmp/pfsd.log 2>&1 &"'
#    等 zygote 真的挂上：znctl status → zygote_states:1（出现 zn-nsdaemon-zygote 进程）

# ⑤ 等 60 秒让注入稳定，再起 Vector daemon（它会重启一次 system_server）
sleep 60
adb shell 'su -c "cd /data/adb/modules/zygisk_vector && (./daemon --system-server-max-retry=3 >/data/local/tmp/vectord.log 2>&1 &)"'
```

### 验证

```bash
adb shell 'su -c "/data/adb/modules/zygisk_vector/cli status"'
#   Framework Version: 2.2
#   Version Code: 3080
#   API Version: 102
#   Enabled Modules: 1
```

### 关键经验（都踩过）

1. **⑤ 之前必须等 60 秒**。不等就会 framework 起来一半挂掉 → 手机重启/进 recovery
   （我们 3 次里 2 次栽在这）。等够之后一次就成。
2. **② 必须先删旧模块目录**：`ksud module install` 覆盖已有模块时报
   `i/o error: No such file or directory`（看着像 bug，其实是旧目录残留）。
3. **② 必须经 su + 用 /data/adb/ksu/bin/ksud**：root 脚本本身在 shell 域（uid 0 但域没变），
   写不了 /data/adb/modules_update（Permission denied）；ksu 域也执行不了 app 目录里的 libksud.so。
4. **不要改 Vector 自带的 service.sh** 去掉 `unshare` 那行 —— 改了就变成开机自启，
   daemon 在 zygote 没注入时反复重启 system_server → 直接进 recovery。
5. **别只杀 zygote64**：本机两个 zygote（zygote64 + webview_zygote），只杀一个会让
   framework 起不来 → recovery。要重启 zygote 就两个一起杀。
6. `znctl status` 里 `zygote_states:0` 但进程里已有 `zn-nsdaemon-zygote` 时，说明
   注入正在做；**等它稳定再动 Vector daemon**。

### 与「一键 root app」的关系

app 只负责 root（利用 + late-load）；**装模块框架这一段已经并进 native 的 root 脚本**
（`src/core/attack/ops.cpp` 的 Titan 2 集成段），时序固化在上面这五步里，
app 只需要把两个模块 zip 摆到 runtime home 的 `modules/` 目录。

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

## 8.1 验证：模块真的能 hook（2026-10-21 已通过）

`apps/xposed-probe/`（新版 libxposed API 102）装上后：

```
$ adb shell 'su -c "/data/adb/modules/zygisk_vector/cli modules enable com.example.titan2probe"'
Enabled: [com.example.titan2probe]
$ adb shell 'su -c "/data/adb/modules/zygisk_vector/cli scope add com.example.titan2probe com.example.titan2probe/0"'
Successfully appended 1 apps to com.example.titan2probe scope.
$ adb shell am start -n com.example.titan2probe/.ProbeActivity
$ adb shell 'su -c "cat /data/data/com.example.titan2probe/files/hook_marker"'
hook fired at 1792579371193
```

**整条链闭环**：GhostLock 利用（uid 0）→ KernelSU late-load → ZygiskNext（zygote 注入）
→ Vector/LSPosed 2.2（API 102）→ 模块被加载 → hook 真的执行了。

### 探针模块的两个坑（自己写的模块要注意）

1. **新版 libxposed 元数据必须在 APK 根目录**：`META-INF/xposed/{java_init.list,module.prop,scope.list}`，
   放 `assets/META-INF/xposed/` 框架**认不出来**（日志里是 `Failed to find path of <pkg>`）。
2. **keystore 要固定**：每次构建重新生成 keystore → 签名变了 → 覆盖安装失败
   （`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。脚本里用 `apps/xposed-probe/probe.keystore`（不进 build/）。
3. **Vector 把传统 Xposed API 重定位了**（包名被混淆成 `bckAHJD.Cf.osS.jp.iM.i.*`）→
   按标准 `de.robv.android.xposed` 写的模块会被加载但抛 `AbstractMethodError`。
   → 写模块要用**新版** `io.github.libxposed.api`（Maven Central 有 `io.github.libxposed:api:102.0.0`）。

## 8.2 管理器显示「Vector 异常」（Mismatched）的排查

框架状态枚举里有 `Mismatched`：**框架在跑，但这个管理器跟它对不上话**（binder 的
`IManagerService` 代次不同 → 每个事务都失败）。

现场（本机）：
- 18:28 管理器进程被注入了 ✓，但 18:37 日志出现
  `ipc: daemon transaction failed` + `framework: setting the status notification to true failed`
  → 管理器首页显示「Vector 异常」。
- 根因：手机上那个 `org.matrix.vector.manager` 不是**当前框架模块自带的那份**（代次/构建不一致）。
- 修法：用模块目录里那份重装（注意 `adb pull` 读不到 `/data/adb/...`，要用
  `adb exec-out su -c 'cat <path>' > out.apk` 才不会混进 shell 的噪声把 apk 弄坏）：

  ```bash
  adb exec-out su -c 'cat /data/adb/modules/zygisk_vector/manager.apk' > vec-mgr.apk
  adb install vec-mgr.apk
  ```

  重装后日志里不再出现 `ipc: daemon transaction failed` ✓。

> 另外：管理器首页还会刷 `store/update: ... unavailable` —— 那是**网络**问题
> （本机解析不了 backup.modules.lsposed.org / github），与框架注入无关。

## 8.3 【未解决】管理器仍显示「Vector 异常」，但框架功能是好的

**现象（2026-10-21 18:47 之后仍然存在）**：

| 维度 | 状态 |
| --- | --- |
| 探针模块 hook | ✅ 真的触发了（`hook_marker` 有时间戳） |
| `cli status` | ✅ `Framework 2.2 / API 102 / Enabled Modules: 1` |
| `cli modules ls` | ✅ 探针模块 `enabled` |
| system_server 注入 | ✅ 日志 `Injected Vector framework into system_server` |
| **管理器首页** | ❌ 仍显示 **「Vector 异常」**（= 状态枚举里的 `Mismatched`） |

**已排除的原因**：

- daemon 挂了 → 排除（`cli status` 有应答 ✓）
- 框架没注进 system_server → 排除（日志有注入成功 ✓）
- 模块没被加载 → 排除（模块列表 + hook 触发 ✓）
- 只是网络问题 → 排除（`store/update unavailable` 只影响商店/更新那两行 ✗ 不影响状态判定）
- 管理器 APK 与框架代次不一致 → 已用模块自带那份重装过（`adb exec-out su -c 'cat .../manager.apk'`），
  重装后日志里不再出现 `ipc: daemon transaction failed` ✓，**但界面仍显示异常** ✗

**当前判断**：管理器的框架状态判定与实际不一致（Mismatched 是「能连上但事务失败」的兜底态），
怀疑是**状态缓存 / 状态通知通道**这条路径的问题（`framework: setting the status notification to true failed`
那行在 18:37 出现过），而不是注入本身。**功能不受影响**（模块照常生效），
管理器 UI 的状态显示是 cosmetic 问题，后续再调。

**复现/观察命令**：

```bash
adb shell 'su -c "/data/adb/modules/zygisk_vector/cli status"'
adb shell 'su -c "/data/adb/modules/zygisk_vector/cli modules ls"'
adb shell 'su -c "grep -iE \"VectorManager|ipc:|injected\" /data/adb/lspd/log/verbose_*.log | tail -20"'
adb shell 'su -c "cat /data/data/<module>/files/hook_marker"'
```
