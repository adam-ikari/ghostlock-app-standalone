---
layout: doc
title: GhostLock — Android 临时 root
titleTemplate: false
---
# GhostLock

**Android 临时 root，一键执行。** 利用 CVE-2026-43499 内核 futex use-after-free，在不解锁 bootloader、不刷 boot 镜像的前提下取得临时 root，并在内存中 late-load KernelSU。重启即失效，无残留。

<a class="dl-btn" href="apk/GhostLock-v1.2(648)-arm64-v8a-release.apk" download>⬇ 下载 GhostLock v1.2 (APK, 24.6 MB)</a>

:::: danger 下载前必读
本软件利用内核漏洞提权，可能导致设备 panic、重启或数据丢失。**仅限对自己拥有或已获授权的设备使用，禁止商用。**
下载或使用即表示你已阅读并同意下方的[免责声明与用户协议](#免责声明与用户协议)。
::::

## 它做什么

| 能力 | 说明 |
| --- | --- |
| 临时 root | 用户态利用内核漏洞提权到 uid 0，不改内核、不动 boot 镜像 |
| 全离线 | Vector、ReZygisk、KernelSU 管理器全部内置在 APK 中，无需下载 |
| KernelSU late-load | 提权后内存加载 KernelSU，可挂载 Zygisk / LSPosed 模块 |
| 重启即恢复 | 所有改动只在本次开机有效，重启后完全回到原状 |
| 运行前检查 | app 内逐项校验内核匹配、profile 合法性、`ksud` 是否存在 |

## 下载

| 项 | 值 |
| --- | --- |
| 版本 | v1.2 (versionCode 648) |
| 架构 | arm64-v8a |
| 大小 | 24.6 MB |
| 系统要求 | Android 12+（minSdk 31） |
| 包名 | `com.ghostlock.app` |
| 许可 | GPL-3.0（内置 ReZygisk / Vector / KernelSU 管理器） |

校验：

```bash
sha256sum GhostLock-v1.2\(648\)-arm64-v8a-release.apk
# c8a573c4a375947b7385f1b82922d938e47575dbfa90b13b7b2af2e7c876a81a
```

## 使用教程

### 界面一览

<img src="/screenshot.png" alt="GhostLock 主界面" style="width:100%;max-width:360px;margin:16px auto;display:block;border-radius:12px;border:1px solid var(--vp-c-border)" />

### 安装组件

**Vector 和 KernelSU 管理器都需要安装。** 两个都是 APK，首次使用时在 app 内点对应按钮，系统会弹出安装确认。全部内置，无需联网下载。

**① 安装 KernelSU 管理器**（必需）

主页点击「安装 KernelSU 管理器」→ 系统弹出安装界面，确认安装。这是加载 Zygisk / LSPosed 模块的前提：不装也能拿到 root，但模块无法加载。

**② 安装 Vector 管理器**（可选）

主页点击「安装 Vector 管理器」→ 从内置 Vector zip 提取 APK，系统弹出安装界面。安装后可在 Vector 中启用 Xposed 模块。

### 执行提权

打开 GhostLock，主页「运行前检查」面板会逐项校验内核、profile 和管理器。全部满足后，按顺序操作：

- **获取 root**（必需）— 运行提权，几秒完成，不会重启设备。
- **激活 Vector**（必需）— 自动部署内置 ReZygisk + Vector 模块并加载，系统就绪后自动继续。需要先完成「获取 root」。过程中**屏幕会闪烁两次**（第二次前间隔较长），属正常现象，请耐心等待，不要退出 app。
- **软重启**（可选）— 仅重启 zygote，屏幕短暂闪烁，Vector 保持激活。一切正常可跳过。

::: tip 激活过程说明
激活 Vector 时屏幕会**闪烁两次**：第一次是注入 zygote，第二次是 Vector daemon 重启 system_server。两次之间有一段等待（系统重启完成即自动继续，通常 1–2 分钟内结束），属正常流程，保持 app 前台即可。
:::

::: tip 网络中断说明
**获取 root 后可能断网**（概率不低），**激活 Vector 成功后网络恢复**。这是正常现象——如果第 1 步后无法上网，请继续执行第 2 步，不要重启设备。
:::

三步完成后，root 和 Vector 即生效。重启设备后全部恢复原状，无残留。

## 支持设备

内置 profile 涵盖上游已验证设备，含 **Unihertz Titan 2**。完整列表见仓库 [kernel_profiles/index.conf](https://github.com/adam-ikari/ghostlock-app-standalone/blob/main/app/src/main/assets/kernel_profiles/index.conf)。

## 许可

GPL-3.0。内置组件：ReZygisk（GPL-3.0）、Vector/LSPosed（GPL-3.0）、KernelSU 管理器（GPL-3.0）。ZygiskNext 不随本软件分发。

## 免责声明与用户协议

GhostLock 是安全研究工具，利用公开披露的内核漏洞（CVE-2026-43499）演示 Android 提权路径。下载、安装或使用本软件，即表示你已阅读并同意以下三条。

**一、风险自担。** 利用依赖内核竞态，可能导致设备发热、卡死、内核 panic、意外重启或数据丢失。本软件按「现状」提供，不作任何担保；作者与贡献者不对上述任何损害承担责任。你自行承担全部风险。

**二、仅限自有或授权设备。** 只能对自己拥有或已获明确授权的设备使用；未经授权访问他人设备违法。利用获得的 root 仅限本次开机有效，重启即失效。许可证不限制使用领域，上述限制是对使用行为的风险提示与合规要求，不构成许可证的附加条件。

**三、第三方组件独立授权。** ReZygisk、Vector(LSPosed)、KernelSU 管理器随本软件分发，均为 GPL-3.0，归属与源码位置见 `NOTICE`；这些组件的行为、安全性与合法性由各自上游负责。ZygiskNext 不随本软件分发。

本协议以中文为准，作者保留修改权，修改自发布于本页起生效；继续使用即视为接受。争议友好协商，协商不成由作者所在地法院管辖。

---

许可全文：[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html)。疑问请通过 [GitHub Issues](https://github.com/adam-ikari/ghostlock-app-standalone/issues) 联系。

<style>
.dl-btn{display:inline-flex;align-items:center;gap:8px;margin:20px 0 28px;padding:0 28px;height:50px;border-radius:10px;font-weight:600;font-size:16px;background:var(--vp-button-brand-bg);color:var(--vp-button-brand-text);border:1px solid var(--vp-button-brand-border);text-decoration:none;transition:background .2s}
.dl-btn:hover{background:var(--vp-button-brand-hover-bg);color:var(--vp-button-brand-hover-text)}
</style>
