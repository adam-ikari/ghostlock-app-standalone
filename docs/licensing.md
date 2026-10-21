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

## ⚠️ 子模块历史里还留着内置过的那两个 GPL 包

子模块分支 `feat/titan2-profile` 的**第 4 个提交**（`feat(app): 一键装模块框架…`）曾把
`vector.zip` / `zygisknext.zip` 直接放进 `app/src/main/assets/modules/`（16MB），
虽然**最后一个提交**已经把它们删掉（最终树是干净的 ✓），但**历史里仍然带着 GPL-3.0 的二进制**。

→ **往上游推之前建议重写这段历史**（把那两个 blob 从提交里剔除），否则 fork 的历史里
仍算「分发」GPL 包：

```bash
cd vendor/ghostlock-app
# 例：用 git filter-repo 剔除 assets/modules 下的 zip（会改写 04 号提交及其后继）
git filter-repo --path app/src/main/assets/modules --invert-paths
git log --oneline -6
```

或者：只把最终状态做成 patch 推（`git format-patch` 的最后一两个提交不含 zip ✓，
但那样会丢掉中间的实验过程 ✗）。

## 推送清单

两个仓库**都没有可推的远端**：

- 项目仓库 `titan2_temp_root`：`git remote -v` 为空 ✗（本地 init 的）
- 子模块 `vendor/ghostlock-app`：唯一远端是上游 `https://github.com/YuKongA/ghostlock-app` ✗
  （我们是 fork 关系，不能直接 push；需要一个自己的 fork 作为 `origin`）

已备好的推送物料（`artifacts/patches/`，**不入 git**，大文件别提交）：

| 文件 | 用途 |
| --- | --- |
| `ghostlock-app-titan2.bundle` | 子模块分支的完整历史（`git clone` 后 `git pull` 即可恢复） |
| `titan2_temp_root.bundle` | 项目仓库 master 的完整历史 |
| `0001..0006-*.patch` | 子模块 6 个提交的 patch（可直接 `git am` 到上游 main 上提 PR ⚠️ 含 20MB 的 zip 提交，见上） |
