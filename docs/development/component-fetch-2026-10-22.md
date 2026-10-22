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
