# Titan 2 Xposed 探针模块

一个**最小**的 Xposed/LSPosed 模块，用来证明「这台设备上 LSPosed 模块能被装上、能加载、能 hook」。

## 它做什么

- `assets/xposed_init` → 入口类 `com.example.titan2probe.HookEntry`
  （传统 Xposed API：实现 `IXposedHookLoadPackage`）。
- hook 自己 app 的 `Activity.onCreate`：用 app 的 classLoader 拿到真正的类再挂钩子，
  hook 成功后往 app 的 `filesDir/hook_marker` 写一行标记。
- `ProbeActivity` 读这个标记，界面直接显示「hook 生效 / 未生效」。
- manifest 里带 `xposedmodule=true` meta-data，框架据此把它认成 Xposed 模块。

## 构建（不走 gradle，只要 SDK build-tools + JDK）

```bash
./apps/xposed-probe/build.sh        # → apps/xposed-probe/build/xposed-probe.apk
```

步骤：`javac`（桩 + app）→ `d8`（只打 app 的类，Xposed API 桩**不进 APK**，运行时由框架提供）
→ `aapt2 link` → 塞 `classes.dex` + `assets/xposed_init` → `zipalign` → `apksigner`。

`stubs/de/robv/android/xposed/*` 是编译期桩，签名后 APK 里只有 app 自己的类。

## 装上并验证

```bash
adb install -r apps/xposed-probe/build/xposed-probe.apk

# 框架侧：启用模块 + 把目标 app 加进作用域
su -c '/data/adb/modules/zygisk_vector/cli modules enable com.example.titan2probe'
su -c '/data/adb/modules/zygisk_vector/cli scope add com.example.titan2probe/0'

# 跑一下，看 logcat
adb shell am start -n com.example.titan2probe/com.example.titan2probe.ProbeActivity
adb logcat -d | grep titan2-probe
```

界面上的「状态：hook 生效」就是最终结论；`filesDir/hook_marker` 里是 hook 触发的时间戳。

> 前提：设备上已经有 ZygiskNext + Vector(LSPosed) 框架在跑
> （见 `docs/titan2-ghostlock-profile.md` §8），并且 zygote 已经重起过一次（注入只发生在新 fork 的进程上）。
