---
layout: doc
title: GhostLock — Android 临时 root
titleTemplate: false
---
# GhostLock

**Android 临时 root，一键执行。** 利用 CVE-2026-43499 内核 futex use-after-free，在不解锁 bootloader、不刷 boot 镜像的前提下取得临时 root，并在内存中 late-load KernelSU。重启即失效，无残留。

<a class="dl-btn" href="apk/GhostLock-v1.2(647)-arm64-v8a-release.apk" download>⬇ 下载 GhostLock v1.2 (APK, 24.7 MB)</a>

::: danger 下载前必读
本软件利用内核漏洞提权，可能导致设备 panic、重启或数据丢失。**仅限对自己拥有或已获授权的设备使用，禁止商用。**
下载或使用即表示你已阅读并同意下方的[免责声明与用户协议](#免责声明与用户协议)。
:::


## 它做什么

| 能力 | 说明 |
| --- | --- |
| 临时 root | 用户态利用内核漏洞提权到 uid 0，不改内核、不动 boot 镜像 |
| KernelSU late-load | 提权后内存加载 KernelSU，可挂载 ZygiskNext / Vector(LSPosed) 模块 |
| 重启即恢复 | 所有改动只在本次开机有效，重启后完全回到原状 |
| 运行前检查 | app 内逐项校验内核匹配、profile 合法性、`ksud` 是否存在，不满足时禁用运行按钮 |

## 下载

| 项 | 值 |
| --- | --- |
| 版本 | v1.2 (versionCode 647) |
| 架构 | arm64-v8a |
| 大小 | 24.7 MB |
| 系统要求 | Android 12+（minSdk 31） |
| 包名 | `com.ghostlock.app` |
| 许可 | GPL-3.0（内置 ReZygisk / Vector / KernelSU 管理器） |

校验：

```bash
sha256sum GhostLock-v1.2\(647\)-arm64-v8a-release.apk
# a4aec86a2938156db36f4a25bcd4f8e62d56e3d0efb0994a3a519c3ecccf66db
```

## 使用步骤

1. **确认内核版本** — `adb shell uname -r`，必须与内置 profile 逐字符一致。不匹配则无法运行。
2. **装 KernelSU 系管理器** — [KernelSU](https://github.com/tiann/KernelSU) v3.3.1+（提供 `ksud`）。不装也能拿到 root，但不会加载模块。
3. **安装并打开 GhostLock** — 主页运行前检查面板会逐项校验，全部绿灯后点 **Run**。
4. **等待** — 利用是内核竞态，可能需重试；手机会发热，凉机成功率更高。
5. **验证** — `adb shell su -c id` 应返回 `uid=0(root) context=u:r:ksu:s0`。

要挂 Xposed 模块，再装 ZygiskNext + Vector(LSPosed 2.2)，装完需再次 `ksud late-load` 并重启 zygiskd。

## 支持设备

需要精确的 `uname -r` 匹配。内置 profile 涵盖上游已验证设备，含 **Unihertz Titan 2**（国际版 TEE 固件，内核 `6.1.145-android14-11-gbd17012cc2f7-ab14044926` 与 `6.1.115-android14-11-g6b18f0b574ab-ab12901745`）。

完整列表见上游 [SUPPORTED_DEVICES.md](https://github.com/YuKongA/ghostlock-app/blob/main/docs/kernel_profiles/SUPPORTED_DEVICES.md)。

内核不匹配？app 内可用 **Parse OTA link** / **Parse local image** 从 `boot.img` 生成候选 profile，或导入已有 `offsets.conf`。生成的 profile 是未验证候选，需真机跑通才算支持。

## 风险与边界

::: warning
- **只在本次开机有效**：重启后 root 与 KernelSU 全部消失，`/data/adb/modules/` 的文件虽保留但不会被加载。
- **不是永久 root**：要长期 root 只能解锁 bootloader + 刷 boot 镜像，不在本项目范围内。
- **内核竞态风险**：利用依赖 futex 竞态，失败可能导致内核 panic 或重启。切勿在关键场景下使用。
- **内核已打补丁则无效**：2026-07 后编译的内核大多含 CVE-2026-43499 修复，extractor 会直接报错退出。
:::

## 许可

GhostLock 整体按 **GNU General Public License v3.0** 发布：可自由使用、修改、再分发，包括商业用途，条件是衍生作品同样以 GPL-3.0 发布并提供对应源码。ReZygisk、Vector、KernelSU 管理器与 `libksud.so` 同为 GPL-3.0，随 APK 一同分发，归属与源码位置见仓库的 `NOTICE`。ZygiskNext 自 v4-0.9.2 起禁止再分发，本项目已改用 ReZygisk。

上游项目：[YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app)

## 免责声明与用户协议

GhostLock 是安全研究工具，利用公开披露的内核漏洞（CVE-2026-43499）演示 Android 提权路径。下载、安装或使用本软件，即表示你已阅读并同意以下三条。

**一、风险自担。** 利用依赖内核竞态，可能导致设备发热、卡死、内核 panic、意外重启或数据丢失。本软件按「现状」提供，不作任何担保；作者与贡献者不对上述任何损害承担责任。你自行承担全部风险。

**二、仅限自有或授权设备。** 只能对自己拥有或已获明确授权的设备使用；未经授权访问他人设备违法。利用获得的 root 仅限本次开机有效，重启即失效。许可证不限制使用领域，上述限制是对使用行为的风险提示与合规要求，不构成许可证的附加条件。

**三、第三方组件独立授权。** ReZygisk、Vector(LSPosed)、KernelSU 管理器随本软件分发，均为 GPL-3.0，归属与源码位置见 `NOTICE`；这些组件的行为、安全性与合法性由各自上游负责。ZygiskNext 不随本软件分发。

本协议以中文为准，作者保留修改权，修改自发布于本页起生效；继续使用即视为接受。争议友好协商，协商不成由作者所在地法院管辖。

---

许可全文：[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html)。疑问请通过 [GitHub Issues](https://github.com/YuKongA/ghostlock-app/issues) 联系。

<style>
.dl-btn{display:inline-flex;align-items:center;gap:8px;margin:20px 0 28px;padding:0 28px;height:50px;border-radius:10px;font-weight:600;font-size:16px;background:var(--vp-button-brand-bg);color:var(--vp-button-brand-text);border:1px solid var(--vp-button-brand-border);text-decoration:none;transition:background .2s}
.dl-btn:hover{background:var(--vp-button-brand-hover-bg);color:var(--vp-button-brand-hover-text)}
</style>
