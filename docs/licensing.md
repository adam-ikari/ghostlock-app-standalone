# 许可与第三方组件

## 结论：app **不内置**任何第三方模块包

上游 GhostLock app 是 **Apache-2.0**（`vendor/ghostlock-app/LICENSE`）。
Zygisk / LSPosed 生态的组件是 **GPL-3.0**（Vector/JingMatrix 经 GitHub API 确认为 GPL-3.0；
ZygiskNext 的仓库未声明 SPDX id，以其仓库 LICENSE 为准）。

把 GPL-3.0 的二进制打进 Apache-2.0 的 app → 组合作品只能按 GPL-3.0 发行
（GPL 的传染性覆盖整个 app）→ **不能再以 Apache-2.0 贡献回上游**。

所以：**去掉内置**（`app/src/main/assets/modules/*.zip` 已删除，APK 从 37.7MB 回到 21.7MB），
改成**用户自己提供**：

1. app 设置里新增「**LSPosed 组件包**」一行（`module_zips_label`）→ 打开系统文件选择器；
2. 选中的 zip 被复制到 app 的 runtime home 的 `modules/` 目录
   （`GhostlockRepository.stageModuleZip()`，接口带默认实现，测试 fake 不受影响）；
3. 利用成功后，native 的 root 脚本按顺序装它们（`su -c /data/adb/ksu/bin/ksud module install`），
   目录通过环境变量 `GHOSTLOCK_LSPD_MODULES` 传给它（两条运行路径都指同一处 ✓）。

**用户要准备什么**（官方 release，自行下载）：

| 组件 | 来源 | 许可 |
| --- | --- | --- |
| ZygiskNext 1.5.2 | https://github.com/Dr-TSNG/ZygiskNext/releases | 见其仓库 |
| Vector 2.2（LSPosed 系） | https://github.com/JingMatrix/Vector/releases | GPL-3.0 |
| KernelSU 管理器 | https://github.com/tiann/KernelSU/releases | GPL-3.0 |

放在手机的「下载」目录，app 里点「LSPosed 组件包」选它即可。

> 设备装第三方模块后由 **KernelSU / 模块管理器** 管理（`/data/adb/modules`），app 只是搬运用户
> 自己的文件 —— 不构成再分发 ✓。

## 本仓库自己的东西

| 产出 | 许可 |
| --- | --- |
| `apps/xposed-probe/`（探针模块） | 我们自己的代码；编译期链接 `io.github.libxposed:api`（**Apache-2.0** ✓，compileOnly，不进 APK） |
| `apps/titan2-temproot` 的 profile / 脚本 / 文档 | 本仓库自有 |
| `vendor/ghostlock-app` 的改动 | 沿用上游 Apache-2.0（仅追加我们自己的代码 ✓，保留原许可声明 ✓） |

## 探针模块的两个许可无关但很坑的点

1. 新版 libxposed 元数据必须在 APK **根目录** `META-INF/xposed/`（放 `assets/` 框架认不出来）。
2. keystore 要固定（`apps/xposed-probe/probe.keystore`，不进 build/），否则重建后签名变化 → 覆盖安装失败。
