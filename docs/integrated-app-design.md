# 整合 app 设计：Titan 2 一键临时 root + LSPosed 栈

> 目标：**一个 app，一次点击**，全程不接 PC。
> 上层：GhostLock（利用 + KernelSU late-load）；下层：ZygiskNext + Vector(LSPosed)。

## 1. 为什么不复用上游 GhostLock app

上游 app 已经能做「点执行 → 临时 root + KernelSU late-load」，但：

- 不会装 ZygiskNext / Vector（LSPosed）模块；
- 不会处理本机特有的坑（toybox `unshare` 不支持 `--propagation` → Vector daemon 起不来）；
- 模块的启用/作用域要靠另一个 app（Vector 管理器）。

所以本项目做一个**整合 app**：上游 native + 我们的 profile + 模块 zip + 一键编排。

## 2. 架构

```
┌──────────────────────────────────────────────────────────┐
│ Titan2TempRoot app（本仓库，手工构建，不依赖 gradle）       │
│  assets/profile.bin        ← 本机 GLK1 profile             │
│  assets/modules/*.zip     ← zygisknext.zip / vector.zip   │
│  lib/arm64-v8a/libghostlock.so ← 上游 native（Apache-2.0）│
└───────────────┬──────────────────────────────────────────┘
                │ 1) 把 native/profile/zip 放到 app 的 filesDir
                │ 2) exec native --load-prebuilt-profile filesDir/profile.bin
                ▼
        native（root_child 前端）
                │ 利用 → uid 0 → root 脚本（以 root 跑）
                │   SELinux 修复 → ksud late-load → KernelSU 加载
                │   ★ 新增：装 modules/*.zip → 修 Vector service.sh
                │            → 再 late-load → 重启 zygiskd → 拉起 Vector daemon
                ▼
        ZygiskNext（Zygisk API）→ Vector/LSPosed 2.2（Xposed 框架）
```

### 为什么把「装模块」放进 native 的 root 脚本

root 脚本是**唯一**在利用成功后天然就拥有 uid 0 的执行点：

- app 自己不是 root（KernelSU 的 `allow_shell` 只放开 shell 用户）；
- 外部 helper app 每次重启都要重新起（不满足「一键」）；
- native 的 root child 已经把 cred 改成 root，脚本本身就是 root。

改动性质：**只改脚本模板里的字符串**，不改任何攻击代码。
验证门槛按上游要求：`tools/cmp_disasm.py` 对 8 个攻击函数必须 IDENTICAL，
外加真机门禁（冷机、单 route、KernelSU 未加载）。

## 3. root 脚本新增段（伪代码）

```sh
MODZIP_DIR="$HOME_DIR/modules"          # app 预先放好的 zip（app 的 filesDir 下）
if [ -d "$MODZIP_DIR" ]; then
  for z in "$MODZIP_DIR"/*.zip; do
    "$KSUD" module install "$z"        # 装 ZygiskNext / Vector
  done
  # Vector 的 service.sh 用了 unshare --propagation → toybox 不支持 → 改掉
  # （否则 late-load 的 service 阶段起不来 daemon，框架连不上）
  { echo '#!/system/bin/sh'; echo 'MODDIR=$(dirname "$0")'; ... } \
      > /data/adb/modules/zygisk_vector/service.sh
  "$KSUD" late-load --kmi "$KMI" --allow-shell   # 让新模块的 stage 跑起来
  /data/adb/ksu/bin/znctl stop                    # zygiskd 重启，扫到新模块
  "$KSUD" post-fs-data & ; sleep 6
  "$KSUD" services ; "$KSUD" boot-completed
  cd /data/adb/modules/zygisk_vector && ./daemon --system-server-max-retry=3 &
  /data/adb/modules/zygisk_vector/cli status      # 报告框架状态
fi
```

## 4. app 的行为

| 按钮 | 动作 |
| --- | --- |
| 一键临时 root | stage 文件 → 跑 native → 流式显示 native 输出 → 显示「临时 root / KernelSU / LSPosed 栈」三段状态 |
| 只装 LSPosed 模块 | 不跑利用，只提示（模块要在 late-load 之后才生效，所以还是走上面那条） |
| 状态 | 读 `/data/adb/modules/zygisk_vector/cli status`（需要 root → 由脚本把结果写进日志，app 读日志） |

app 只做「编排 + 显示」，不碰利用逻辑。

## 5. 一次性前提

- 设备上要有 KernelSU 系管理器 APK（`ksud` 的来源）；没有的话 app 会提示装哪个。
  （v3.3.1+ 才有 `android14-6.1_kernelsu.ko`。）
- 设备要能装两个 app（GhostLock 整合 app + KernelSU 管理器）。

## 6. 失效边界

- 临时 root 与 KernelSU 都是内存态，**重启即失效**；重启后再点一次 app 即可。
- `/data/adb/modules/` 里的模块文件会保留，第二次 late-load 会自动 apply。
