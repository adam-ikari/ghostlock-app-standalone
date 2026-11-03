# profile 合并的共享状态污染（2026-10-22）

## 现象

把上游 `main` 合进来之后，两条单测变红：

```
ExporterAgreementTest: 6.1.138-android14-11-g0c3d559bcd85-ab14529422: exporter differs from the app
    arrays first differed at element [1371]; expected:<15> but was:<25>
ProfileMigrationEquivalenceTest: fixture must cover every current 6.x builtin
```

差的那一个字节落在 `execution.stages.w1_attempts`：app 端算出 15，exporter 算出 25。

## 根因

`ProfileMerger.resolveMerged()` 把传入的 `tuningExecution` **直接**放进 defaults，而
`deepMergeValues()` 是原地合并：

```kotlin
val defaults = valueMapOf("release" to deviceRelease).apply {
    if (tuningExecution != null) put("execution", tuningExecution)   // 共享引用
}
...
deepMergeValues(defaults, builtin)                                  // 原地改 defaults
```

于是某个 profile 自带的 `execution` 块会写进这份共享 map：`6.1.134-android14-11-maybe-dirty.conf`
里 `w1_attempts = 25`，从它之后的每个 profile 都继承 25。`fillRouteExecutionDefaults()` 里
`routes[route] = defaults` 同样把共享的 route 预设挂到结果上，后续往分支里写字段会回写预设。

为什么只有 exporter 错、app 对：exporter 解析一次 `execution-tuning.conf` 就复用给全部 75 个
profile（`ProfileExporter.main`），先污染后全中；app 每次解析都重新读资产
（`AndroidProfileConfigController.readExecutionTuning()`），拿到的永远是新 map。

## 影响文件

| 文件 | 改动 |
| --- | --- |
| `profile-core/src/main/kotlin/com/ghostlock/app/data/profile/ProfileMerger.kt` | 唯一代码改动：合并前复制共享的 tuning / route 预设 |
| `app/src/main/assets/kernel_profiles/{5.10.236-android12-9-00014-gb97c62c4e7d1-ab13436420,6.1.78-android14-11-gb6577b760481-ab12075023,6.6.30-android15-8-g6b55ce738535-4k}.conf` | 删掉上游新 profile 里已废弃的 `recommend_shizuku`（本分支 schema 已删该键，留着 `exportKernelProfiles` 校验直接失败） |
| `app/src/test/resources/remote-main-6x-offsets.json` | 补上游新增的两个 6.x profile 的 legacy 条目（65 → 67） |
| `app/src/test/kotlin/com/ghostlock/app/data/ProfileMigrationEquivalenceTest.kt` | fixture 条目数断言 65 → 67 |

## 行为差异

- exporter 产出的 GLK1 文档现在与 app 端逐字节一致。此前从 `6.1.134-android14-11-maybe-dirty`
  之后的每个 profile，exporter 都带着被污染的 25。
- **app 端行为不变**：它本来就每次重新读 `execution-tuning.conf`，`w1_attempts` 一直是 15。
- 设备侧数值没有变化：本次不碰攻击路径，也不改任何 profile 的字段值。

## 上游状态

上游 `main` 的 `index.conf` 顺序同样是 `6.1.134-…-maybe-dirty` 在 `6.1.138-…g0c3d559bcd85`
之前，它的 `remote-main-6x-offsets.json`（63 条）也缺这两个新 profile，所以上游这棵树同样是红的。
本修复可以单独提 PR 给上游。

## 验证

```sh
./gradlew :profile-core:exportKernelProfiles        # 75 profile(s)
./gradlew :app:testDebugUnitTest                    # 99 条全绿
./gradlew :app:assembleRelease                      # GhostLock-v1.2(603)-arm64-v8a-release.apk（2,727,882 B）
apksigner verify --print-certs <apk>                # V3.0 Signer: CN=GhostLock Release
make -C src                                          # NDK 交叉编译
```

- native 二进制重建后 sha256 与合并前一致：`247c40b920856934ce7cb7940a53cbad05fef16c9892d24bdbe4d68dda1a2dec`
  （`git diff f6b469e main -- src/ tools/` 为空，native 源码未变）
- `make -C src native-host-tests` 在本机跑不了：系统 g++ 11.4 / clang 14 都不支持 `<expected>`
  （C++23），需要 GCC 13+ 或 Clang 16+。本批次未改 native 代码，不影响结论。
- `cmp_disasm` 未跑：攻击路径未触碰，没有可比对的二进制差异。

## 未做

- 真机门禁：设备未接宿主，本批次不触碰攻击路径，但 603 换了签名（设备上那个 593 是 debug 证书），
  装机前先卸载旧包，之后按常规流程复跑一次真机门禁。
