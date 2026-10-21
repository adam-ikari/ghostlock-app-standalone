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

## 一次性的小修：Vector 的 service.sh

设备上的 toybox `unshare` 不支持 `--propagation`，Vector 模块自带的 `service.sh` 会直接报错，
daemon 起不来（表现为：管理器连不上框架）。用 root 改一次即可（改完永久有效，模块文件在 `/data` 上）：

```sh
su -c 'cat > /data/adb/modules/zygisk_vector/service.sh' <<'EOF'
MODDIR=${0%/*}
cd "$MODDIR" || exit
./daemon --system-server-max-retry=3 &
EOF
```

改完之后，**每次 late-load 都会自动把 Vector daemon 拉起来**，整条链在手机上闭环。

（这一步需要 root —— 用 GhostLock app 点一次「执行」就有 root 了；
  改文件本身也可以用手机上的 Shizuku + 任意终端/文件管理器完成。）

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
