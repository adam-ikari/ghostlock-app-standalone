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

## ⚠️ Vector daemon 自启 = 反复掉进 recovery（已踩，别再开）

Vector 的 daemon 靠「重启 system_server」把服务注进去。**如果 zygote 里没有 ZygiskNext 的注入**，
它注不进去 → 再重启一次 → 无限循环 → framework 起不来 → Android 的 rescue party
把机器送进 **recovery**（recovery 里 adbd 是关的 → adb/fastboot 全都够不着，只能长按电源键）。

现场表现很容易被误判：**手机从 USB 上「消失」、Windows PnP 里也看不到设备** —— 那不是线坏了，
是它在 recovery。

> 我们一度把 Vector 的 `service.sh` 改成开机自启（去掉 `unshare` 那行，因为设备上的 toybox
> `unshare` 不支持 `--propagation`）—— **正是这一改导致后面几次 recovery**。

安全的做法：

- **不要**让 Vector daemon 随 late-load 自动起来：保持模块自带的 `service.sh` 原样
  （它的 `unshare` 在本机会失败 → daemon 不自启 → 不会重启 system_server）。
- 要用 LSPosed 栈时，**按这个顺序**手动来：

  ```sh
  # ① 先让 ZygiskNext 的 zygiskd 起来并注入正在运行的 zygote
  su -c '/data/adb/ksu/bin/znctl stop'
  su -c '/data/adb/ksu/bin/ksud post-fs-data' &      # 等几秒
  # ② 再 late-load（把模块应用上）
  su -c '/data/adb/ksu/bin/ksud late-load --kmi android14-6.1 --allow-shell'
  # ③ 最后手动起 Vector daemon（它会重启一次 system_server；此时 zygote 已被注入 → 一次就成功）
  su -c 'cd /data/adb/modules/zygisk_vector && ./daemon --system-server-max-retry=3 &'
  su -c '/data/adb/modules/zygisk_vector/cli status'
  ```

  顺序反了（新 zygote 没注入 → daemon 注不进 → 循环重启 system_server）就会掉进 recovery。

## ⚠️ 别只杀 zygote64（本机会直接进 recovery）

`kill -9 $(pidof zygote64)` 之后，新的 zygote 确实带上了注入点（`znctl status` → `zygote_states: 1` ✓），
**但 framework 起不来** → rescue party → recovery。原因是本机还有 32 位的 `zygote` 进程，
只杀 64 位那个会让两个 zygote 状态不一致，init 拉起新的也救不回来 framework。

→ 要重启 zygote 就**两个一起杀**（`pidof zygote zygote64`），或者干脆**整机重启**。

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
