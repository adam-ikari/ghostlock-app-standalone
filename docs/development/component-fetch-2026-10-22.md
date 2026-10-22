# 组件包获取：从上游下载而不是打进 APK（2026-10-22）

## 动机

`vector.zip` / `zygisknext.zip` 不能都随 APK 分发：

| 组件 | 许可证 | 结论 |
| --- | --- | --- |
| Vector v2.2 | GPL-3.0（`vector.zip` 内 `README.md` 的 License 段） | 可以随包分发，需附许可证与源码出处 |
| ZygiskNext 1.5.2 (864) | All rights reserved（v4-0.9.2 起，`zygisknext.zip` 内 `README.md` 的 Copyright Notice） | **不能**：第 2 条 "No Redistribution … including the software as part of another project" |

所以「装完 APK 点一下就全好」只能靠 app 在运行时取件：两个项目都发布
`update.json`，其中的 `zipUrl` 指向 GitHub release 资产：

```
https://lsposed.zip/zygisk-next/update.json
https://raw.githubusercontent.com/JingMatrix/Vector/master/zygisk/update.json
```

下载是用户向权利人取件，不是本项目再分发。

## 改动

| 文件 | 改动 |
| --- | --- |
| `app/src/main/kotlin/com/ghostlock/app/runtime/ComponentFetcher.kt`（新） | `update.json` → `zipUrl` → 下载到 `files/modules/<name>.zip`；下载后校验 `module.prop` 的 `id` 与体积下限 |
| `app/src/main/kotlin/com/ghostlock/app/domain/repository/GhostlockRepository.kt` | 新增 `fetchModulePackages(onLog)`；顺带改掉「两个包都是 GPL-3.0」这句过时注释 |
| `app/src/main/kotlin/com/ghostlock/app/data/AndroidGhostlockRepository.kt` | 实现：逐个组件下载，返回失败标签；单个失败不影响另一个 |
| `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockViewModel.kt` | `onFetchModuleZips()`：IO 线程跑，日志进 app 日志页 |
| `app/src/main/kotlin/com/ghostlock/app/ui/GhostlockUI.kt` + `MainActivity.kt` | 「LSPosed 组件包」上方新增「获取组件包」入口 |
| `app/src/main/res/values*/strings.xml` | 新入口文案；修正「GPL-3.0」的过时说法 |
| `app/src/test/kotlin/com/ghostlock/app/runtime/ComponentFetcherTest.kt`（新） | 7 条：zipUrl 解析（https/缺失/非 https）、模块名校验、体积下限、非 zip 体 |

## 行为差异

1. 用户可选：点「获取组件包」（联网一次）或继续手动选 zip。两者写同一个
   `files/modules/`，后续流程完全一致。
2. **已经存在的包不覆盖**：`fetch` 先按 `module.prop` 的 id 在 `files/modules/*.zip`
   里找，找到就跳过并记日志。理由：上游 `update.json` 指向的 Vector 是 3080，
   而用户手上验证过的是 3112；自动覆盖等于静默降级，会把能用的环境换坏。
3. 下载先落 `.part`，校验通过才替换；校验不过（不是 zip、id 不对、小于 100 KB）
   直接丢弃，磁盘上原来的包不受影响。
4. 同名但不同文件名的重复包：下载成功后按 module id 清掉其它同 id 的 zip
   ——native 脚本会安装目录里**所有** `*.zip`，留着会重复安装同一个模块。
5. 没有网络 / update.json 不可达：日志写明失败标签，流程退回手动选，
   不阻断「一键 root」。

## 验证

- `./gradlew :app:testDebugUnitTest`：93 条全绿（新增 7 条）。
  其中「体积下限」那条一开始是假绿：夹具用全零填充，压缩后不到 100 KB，
  被 `MinZipBytes` 挡下；改成随机字节后才是真校验。
- 真实上游连通性（本机 curl + python 复算）：两个 `update.json` 都取到 `zipUrl`，
  下载后 `module.prop` 分别是 `zygisksu` / `zygisk_vector`，体积 6.7 MB / 9.3 MB，
  与本文件的校验规则一致。
- `:app:assembleDebug` 成功；APK（构建 596）内 `ComponentFetcher` 与
  `module_zips_fetch_*` 字符串都在。

## 已知限制

- **没有整包哈希**：ZygiskNext 的 `update.json` 不含哈希，包内的 `*.sha256` 与包同源，
  只防截断不防篡改。实际信任边界是「HTTPS 到 GitHub」。
- 上游更新后不自动跟进：用户要更新时手动选新 zip，或先删掉 `files/modules/` 里的旧包再点获取。
- 首次获取需要网络；离线设备只能走手动选。

## 未做

- 没做下载进度条（22 MB 的 debug 包网络慢时只有一个日志行）。
- 没做「检查更新」（同一套 `update.json` 可以支持，但当前没有需求）。

## KernelSU 管理器：下载归 app，安装归系统安装器（2026-10-22 追加，含一次撤销）

> **撤销（2026-10-22，同日）**：下面第一版做法是「拿到 root 后脚本 `pm install` 静默安装」，
> 已作废。理由：静默安装必须依赖 root，而这套流程的 root 正是它要服务的东西——一旦利用失败，
> 管理器也装不上；更关键的是「root 在后台悄悄装 app」本身不该是默认行为，安装 app 是用户的决定。
> 现行做法回到 597e：app 下载，弹系统安装器，用户确认一次。`ops.cpp` 已 `git checkout` 回到 HEAD，
> 攻击路径不再有任何改动，也就不再需要 cmp_disasm 与真机门禁。作废代码：`pm install` 段、
> 签名不一致时 `pm uninstall` 重试、脚本缓冲区 12288→16384、`GHOSTLOCK_HOME` 取件路径、
> `InstallApk` effect 删除。以下原文保留作记录。

### 原方案（已作废）

#### 为什么单独处理

### 为什么单独处理

三个组件里只有管理器必须变成「已安装的 app」：root 脚本从
`/data/app/**/lib/arm64/libksud.so` 取 ksud，`late-load` 没有它就无从谈起。
另外两个是 zip，进 `files/modules/` 即可。

#### 取件

- 上游：`https://api.github.com/repos/tiann/KernelSU/releases/latest`，
  取资产里的 `-release.apk`（`parseManagerApkUrl`），当前 v3.3.1 / 13,637,206 B
- 校验：`verifyManagerApk` 要求 ≥1 MB 且 zip 内有 `AndroidManifest.xml` + `classes.dex`
- 落盘：`filesDir/component-apk/kernelsu-manager.apk`
  （不是 cacheDir：root 脚本按 `$HOME_DIR` 找它，缓存可能被系统清掉）

#### 安装（已作废的 `src/core/attack/ops.cpp` 段）

在 KSUD 扫描之后、模块安装之前插入一段，仅在「找不到任何 libksud.so」时执行：

```sh
KSU_APK="$HOME_DIR/component-apk/kernelsu-manager.apk"
if [ ! -x "$KSUD" ] && [ -f "$KSU_APK" ]; then
  PM=/system/bin/pm; [ -x "$PM" ] || PM=pm
  timeout 90 "$PM" install -r "$KSU_APK" >>"$LOG" 2>&1
  # 重新按五个包名扫 libksud.so，再退回 /data/adb/ksu/bin/ksud
fi
```

- 已装管理器的设备整段跳过，行为与改动前一致
- `timeout 90` 防止包管理服务卡住吃掉唯一的 permissive 窗口
- 脚本缓冲区从 12288 提到 16384：实测新脚本 12128 字节，距旧上限只剩 160 字节，
  路径再长一点就会触发 `root script too long`

#### 验证（针对已作废的静默安装）

- 分支桩测（主机，`pm` 用桩替换，记录参数）：无管理器 → 记录
  `pm-called: install -r <staged>` 且 `pm install exit=0`；已装管理器 → 整段不执行、log 不产生
- 脚本 `sh -n` 通过（按 `snprintf` 字面量还原出 12128 字节）
- `cmp_disasm` 对 HEAD 重建的基线：7 个函数全为 LAYOUT-SHIFT，
  指令数/助记符一致，只有地址操作数注解差异；`MISSING multicast_*` 在基线自比时同样出现（工具属性）。
  全量指令数 37354 → 37352，差异不在被比对的函数内
- 单测 99 条全绿（新增 3 条：资产挑选、无 APK 返回 null、manifest/dex/过小/非 zip 四拒）
- **真机门禁未做**：改的是攻击脚本，按规范需要一次冷机跑。
  本次改动在已有管理器的设备上整段跳过，风险面为零；首次装机路径仍需真机确认

#### 未做

- 静默安装失败时（签名冲突、包管理拒绝）只写日志，没有面向用户的结论行
- 新装的管理器不会被自动打开，用户要自己点图标进去看模块

### 现行做法（597h）

- 取件同前，落盘回到 `cacheDir/component-apk/kernelsu-manager.apk`
- `verifyManagerApk` 不变（≥1 MB 且 zip 内有 manifest + classes.dex）
- 下载完发 `GhostlockEffect.InstallApk`，`MainActivity` 用 `FileProvider` 的 content URI
  + `ACTION_VIEW` + `application/vnd.android.package-archive` 打开系统安装器
- 签名不一致时由系统安装器自己报错（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），app 不替用户卸载任何东西
