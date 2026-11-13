---
layout: doc
title: GhostLock — Android 临时 root
titleTemplate: false
---
# GhostLock

**Android 临时 root，一键执行。** 利用 CVE-2026-43499 内核 futex use-after-free，在不解锁 bootloader、不刷 boot 镜像的前提下取得临时 root，并在内存中 late-load KernelSU。重启即失效，无残留。

<a class="dl-btn" href="apk/GhostLock-v1.2(648)-arm64-v8a-release.apk" download>⬇ 下载 GhostLock v1.2 (648) — Titan 2 专用 (APK, 24.6 MB)</a>

<p style="text-align:center;color:var(--vp-c-text-2);font-size:14px;margin-top:-4px">版本 <strong>v1.2 (versionCode 648)</strong> · 构建日期 2026-11-13 · 仅适配 <strong>Unihertz Titan 2</strong></p>

:::: danger 下载前必读
**本版本只适配 Unihertz Titan 2**（内核 `6.1.145-android14-11-gbd17012cc2f7-ab14044926`）。native 按 `uname -r` 精确匹配 profile，机型或内核版本不符时 app 会在「运行前检查」处拒绝运行，不会执行 exploit。

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
| 支持机型 | **仅 Unihertz Titan 2** |
| 内核要求 | `6.1.145-android14-11-gbd17012cc2f7-ab14044926`（精确匹配） |
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
- **激活 Vector**（必需）— 自动部署内置 ReZygisk + Vector 模块并加载，系统就绪后自动继续。需要先完成「获取 root」。过程中**屏幕会闪烁两次**，第二次通常在第一次之后 **10 秒左右**出现（系统重启完成即继续，不必等待固定时长），属正常现象，请保持 app 前台。
- **软重启**（可选）— 仅重启 zygote，屏幕短暂闪烁，Vector 保持激活。一切正常可跳过。

::: tip 激活过程说明
激活 Vector 时屏幕会**闪烁两次**：第一次是注入 zygote，第二次是 Vector daemon 重启 system_server。

两次之间不再固定等待 60 秒：程序轮询 `system_server` 是否已换成新进程、binder 的 activity 服务是否就绪、并稳定 5 秒，满足即继续。实测第二次闪烁在第一次之后 **约 10 秒**出现；设备较慢或后台负载高时可能略长，最长会自动兜底（约 2 分钟）后继续。全程保持 app 前台即可。
:::

::: tip 网络中断说明
**获取 root 后可能断网**（概率不低），**激活 Vector 成功后网络恢复**。这是正常现象——如果第 1 步后无法上网，请继续执行第 2 步，不要重启设备。
:::

三步完成后，root 和 Vector 即生效。重启设备后全部恢复原状，无残留。

## 模块使用

激活完成后，root 与 Xposed 框架都在本次开机内可用。两类模块分别由 KernelSU 管理器和 Vector 管理器管理。

### Xposed / LSPosed 模块（Vector 管理器）

1. 打开 **Vector 管理器**（桌面图标，或 GhostLock 主页里的「安装 Vector 管理器」按钮）。
2. 进入底部的**模块**页，列表里勾选要启用的模块。
3. 点开模块，在**作用域**里勾选它需要生效的目标应用（不勾作用域模块不会注入任何应用）。
4. 保存后，按管理器提示**重启目标应用**；若提示需要重启框架，用 GhostLock 的「软重启（可选）」即可，无需整机重启。

检查是否生效：重新打开目标应用，模块通常会在应用内显示自己的界面或开关；Vector 管理器模块页也会显示该模块已启用。

### KernelSU 模块

1. 打开 **KernelSU 管理器**，进入**模块**页。
2. 点「安装模块」，从本地选择 zip 文件（GhostLock 已内置 ReZygisk 与 Vector，一般不需要再手动安装）。
3. 新安装的模块通常需要重启才生效。此处**只能用 GhostLock 的「软重启」**：临时 root 下整机重启会让全部改动失效，需要重新执行「获取 root」和「激活 Vector」。

::: warning 临时 root 的限制
本方案的 root 只在本次开机有效，**整机重启后全部清除**：KernelSU、Vector、已启用的模块和作用域都会消失。重启后请重新执行「获取 root」→「激活 Vector」，模块配置再由管理器重新启用。

不要安装会改写系统分区、替换 boot 镜像或依赖持久化挂载的模块——它们在临时 root 下要么无效，要么会导致开机异常。
:::

## 支持设备

**本版本（v1.2 / 648）只随附 Unihertz Titan 2 的 profile**，内核
`6.1.145-android14-11-gbd17012cc2f7-ab14044926`。

profile 目录里可能还保留其他上游已验证机型的文件，但它们不属于本次发布范围，
也未在 Titan 2 之外的设备上验证。native 按 `uname -r` 精确匹配：机型或内核版本
不符时，「运行前检查」会直接拒绝，不会进入 exploit。

完整 profile 索引见仓库
[kernel_profiles/index.conf](https://github.com/adam-ikari/ghostlock-app-standalone/blob/main/app/src/main/assets/kernel_profiles/index.conf)。

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
