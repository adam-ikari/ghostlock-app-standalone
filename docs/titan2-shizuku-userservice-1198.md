# Titan 2 上 Shizuku UserService 起不来（Shizuku#1198 / MediaTek）

> 2026-10-21 22:5x。手机不在宿主上时抓到的 app 侧日志。

## 现象

app 里勾了「Run via Shizuku」，点执行后：

```
<s> status=READY
<s> ping=true
<s> uid=2000
<s> version=13
<s> serviceVersion=13
<s> patch=5
<s> perm=0
<s> selinux=u:r:shell:s0
<s> pkg=13.6.0.r1086.2650830c
<b> binding UserService
<s> error: Shizuku UserService did not connect within 3s
<s> retrying UserService connection (1/3)
... （共 3 次，每次都精确 3 秒）
<s> error: Shizuku UserService did not connect after 3 attempts
<k> result: exploit failed (exit code=1)
```

要点：

- Shizuku 服务端**完全正常** —— READY、uid=2000、perm=0（已授权）、ping 通；
- 失败发生在**绑 UserService**，利用**根本没开始跑**（没有任何 native 日志）；
- 三次都**精确 3 秒** = 代码里的 `CONNECT_TIMEOUT_MS = 3_000L`；
- **没有任何 app 侧异常**，说明不是 app 的逻辑错，是对面进程死了。

## 根因（上游已知 bug）

Shizuku 13.6.0 的 UserService 跑在一个由 `app_process` 拉起的辅助进程里
（`moe.shizuku.starter.ServiceStarter`），该进程**从不调用
`ActivityThread.bindApplication()`**。

MediaTek 给 `LoadedApk.makeApplicationInner()` 打了 OEM 补丁：

```java
if (!mApplicationInfo.isSystemApp() && !mApplicationInfo.isInstantApp() && !mPackageName.equals("android")) {
    String procName = Application.getProcessName();
    if (procName.equals(mPackageName) && appContext != null) {
        ResOptExtFactory.getInstance().makeResOptExt().preloadRes(appContext, getResources());
    }
}
```

`Application.getProcessName()` → `ActivityThread.currentProcessName()` →
`am.mBoundApplication` 为 null → 返回 **null** → `procName.equals(mPackageName)`
**NPE**。

辅助进程在 `makeApplication()` 里就崩了，**在 app 自己的代码执行之前**，
所以 app 侧只能看到「bind 一直没有回调」。

- 上游 issue：**RikkaApps/Shizuku#1198**（标题原为「User services don't work on
  Xiaomi HyperOS/MIUI」，后被 vvb2060 改名为「User services don't work on
  **MediaTek devices**」）
- 该 issue 里 2026-10 的复现设备写明：**Unihertz Titan 2 (MediaTek), Android 16 /
  SDK 36, Shizuku 13.6.0 versionCode 1086, ADB 模式 UID 2000** —— 与本机完全一致。
- 同一 issue 也有 Infinix XOS 16（#19）、Lenovo Tab M11（MTK）、Pixel 10、OPPO A72x
  等机型同样报 `bindUserService` 超时。

## 为什么重试也没用（app 侧第二个 bug）

超时后原代码调用 `Shizuku.unbindUserService(args, connection, true)`。
第三个参数 `remove=true` 的语义是**销毁远端服务进程**。于是：

1. 第一次 bind → 服务端开始拉起辅助进程；
2. 3 秒到了 → app 认为失败 → **把正在启动的进程杀掉**；
3. 第二次 bind → 从零再冷启一次 → 又在 3 秒被砍；
4. 三次全灭。

即使把超时调大，只要 `remove=true` 还在，每次重试都是一次新的冷启动。

## 处置

### app 侧（已提交 `26aa27c`）

- 握手超时 `3s → 10s`：首次 bind 要拉起辅助进程 + 加载 app 的 dex，冷启动常常 >3s；
- 超时改用 `unbindUserService(..., remove=false)`：**不杀**已在启动的服务进程，
  让重试复用同一个热进程；
- UserService 不可用时返回独立退出码 `EXIT_USER_SERVICE_UNAVAILABLE = 2`，
  repository 检测到就**自动回退到直连路径**（`runExploitBinary`，不依赖任何辅助进程），
  并在日志里打印 Shizuku#1198 提示，而不是整轮判失败。

### Shizuku 侧（用户侧操作）

官方 13.6.0 在本机不可用，换带兜底修复的社区分支：

- `thedjchi/Shizuku` `v13.6.0.r1318-thedjchi`（上游 master 里也有
  `server-shared: Fall back to package context when makeApplication() fails`，
  但还没进正式发布版）
- 分发包里已附带：`shizuku-thedjchi.apk`（3.2MB）
- 步骤：卸载官方 Shizuku → 装这个 → 无线调试起一次 → 重新授权 app

## 结论

**这不是 Titan 2 的问题，是 Shizuku 13.6.0 在 MediaTek 上的已知 bug**，
且上游 issue 里的复现设备就是 Titan 2。app 侧的两处改动让它在这类机器上
不再卡死（能自动走直连路径），换 thedjchi 分支则两条路都能用。
