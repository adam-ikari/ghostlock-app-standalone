# Vector 克隆应用作用域放行计划（2026-11-20）

## Current state and baseline

- GhostLock `main`，基线 `7aff9806 docs(site): update release to v1.2 (651)`。
- 随包 Vector：`app/src/main/assets/modules/vector.zip`，`module.prop` 为 `v2.2 (3080-88f8e1fa-JingMatrix-Vector)`，`versionCode=3080`。
- 上游源码基线：Vector tag `v2.2`，commit `8b8fa04dbd58f73bd5187fc6af02b65459b890cc`，本地展开于 `/tmp/Vector-2.2`。
- 当前行为：
  - `AppRepository.getInstalledApps()` 已从所有用户枚举包，并生成 `AppInfo(packageName, userId)`；
  - `ScopeViewModel.load()` 用 `it.userId == userId` 过滤，模块详情页只列出模块所在 user 的应用；
  - `ConfigCache` 对显式作用域行强制 `userId in moduleUsers`，user 0 模块写向 user 10 目标的行会被丢弃；
  - `ModuleDatabase.setModuleScope()` 已按 `(packageName, userId)` 保存，管理器 apply 也是整表写入，无需改格式。
- 用户确认场景：模块只装在使用者 user 0；目标克隆应用位于 user 10/工作资料，需要在管理器里勾选并生效。

## Goals and constraints

- 目标：模块详情页列出所有 user 的目标实例，行上明确标出 user；仅由 user 0 持有的模块可以显式 hook 任意 user 的目标；作用域表、CLI、备份格式不变。
- 非目标：不把模块自动安装到工作资料；不改 GhostLock native/攻击路径/profile/wire；不改 Vector 作用域数据库 schema 或 AIDL；不改变二级用户专属模块的现有边界。
- 验收边界：以 U10 目标进程加载模块、hook 执行、日志无跨用户拒绝为准。`InjectedModuleService` 的 prefs/files 仍按调用方 UID 的 user 解析，模块自身在 U10 读不到 U0 的模块私有数据；需要该能力属于另一方案，不在本批次内。
- 约束：`restorelib.sh` 不动；不提交/push；远程设备当前 adb 离线，真机验证需在设备恢复后补做，未完成前不得声称通过。

## Approaches

| 方案 | 做法 | 结论 |
|---|---|---|
| A（采纳） | 管理器列出所有 user 的目标并标出 user；daemon 仅对“user 0 持有模块”的显式 scope 行放行跨用户 | 最小改动，满足用户已确认的部署；owner 模块获得显式跨用户能力 |
| B | 把模块同步安装到克隆 user，沿用现有 UI/daemon 边界 | 安全性更保守，但改变“模块只装 user 0”的前提，需要安装迁移和每 user 数据 |
| C | 只放宽 daemon | CLI 可写但管理器仍选不到，不满足需求 |

## Change list

1. `/tmp/Vector-2.2/manager/src/main/kotlin/org/matrix/vector/manager/ui/screens/modules/ScopeViewModel.kt`
   - `load()` 删除 `filter { it.userId == userId }`，改为加载 `appRepository.getInstalledApps()` 全量结果。
   - 更新相邻注释：同包多 user 是不同目标，user 标签由 UI 行承担，不再靠隐藏来去重。
   - 不改 `ScopeTarget(packageName,userId)`、draft/saved 合并、apply、backup/restore。
2. `/tmp/Vector-2.2/manager/src/main/kotlin/org/matrix/vector/manager/ui/screens/modules/ScopeScreen.kt`
   - `AppRow` supporting content 在包名旁显示 `U<userId>`，区分同包的机主/工作资料副本。
   - 不新增过滤器、字符串资源或导航参数；`userId` 仍代表模块身份，不代表目标范围。
3. `/tmp/Vector-2.2/daemon/src/main/kotlin/org/matrix/vector/daemon/data/ConfigCache.kt`
   - 将目标 user 边界改为 `userId !in holders && holders != setOf(0)`。
   - 注释明确：仅当模块持有者集合恰好是 `{0}` 时，显式作用域行允许进入任意 user；混合持有者（如 U0+U10）和二级用户独有模块均保持原边界；self-hook/legacy 派生行仍只 propagates 到 holders。
4. `app/src/main/assets/modules/vector.zip`
   - 用同一份新生成的自签名 keystore（仅放 `/tmp`，不入库）签署重建的 `manager.apk` 与 `daemon.apk`；daemon 内嵌的 manager 证书随新签名一起重建。
   - 用这两个 APK 替换原文件，并更新对应 `.sha256`；`module.prop`、版本号、其余文件保持不变。
   - 设备升级时新签名无法覆盖安装 manager：先卸载 `org.matrix.vector.manager`，再从新 zip 安装；daemon 随模块目录整体替换。回滚同样需卸载 manager 后装回原包。

## Data-flow/control-flow differences

- Before：`packageName/userId` 列表 → 按模块 userId 过滤 → 用户勾选 → daemon 检查目标 user 必须是 module holder → 非 holder 行丢弃。
- After：`packageName/userId` 全量列表 → UI 显示 `U<userId>` → 用户勾选任意 user 的目标 → daemon 仅在持有者集合不等于 `{0}` 且目标 user 不在持有者集合中时丢弃。
- 不变量：作用域表仍由 `(packageName,userId)` 唯一标识；apply 仍是整表替换；`system` 仍存 user 0；target 解析仍由 `getPackageInfoWithComponents(..., userId)` 完成，不存在的 user/包不会生成进程作用域；非 legacy 模块仍由 Zygisk 注入目标进程，模块自身 user 0 app 负责框架 API。

## Compatibility and rollback

- 向后兼容：已有 user 0 目标行不变；备份恢复的 `(package,user)` 行不变；二级用户持有模块的原边界不变。
- 回滚：恢复 `vector.zip` 内原来的 `manager.apk`/`daemon.apk` 与对应 `.sha256`，其他资产不动；源码改动在 `/tmp/Vector-2.2`，可直接丢弃该工作树重建。

## Verification matrix

| 批次 | 命令/条件 | 期望 |
|---|---|---|
| 1. 源码编译 | 在 `/tmp/Vector-2.2` 用新建自签名 keystore 运行 `:manager`、`daemon` 构建 | BUILD SUCCESSFUL；两个 APK 证书相同且为新自签名 |
| 2. 静态核对 | `git diff` 检查 Vector 三处源码 + zip 内 4 个条目（2 APK、2 sha256） | 无其他文件变化；`.sha256` 与新 APK 匹配；`module.prop` 未变 |
| 3. GhostLock 构建 | `./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain` | BUILD SUCCESSFUL |
| 4. 设备门禁（待设备在线） | 卸载旧 manager，激活新 zip，模块详情页勾选 `U10` 目标，apply 后启动克隆应用，查看 Vector 日志 | 勾选后 scope 行存在；克隆应用进程加载模块；hook 执行；无跨用户拒绝日志 |
| 5. 批量动作（待设备在线） | 对同包 `U0`/`U10` 两行执行推荐作用域、全选、清除，并做一次备份恢复 | 多 user 行按 `U<id>` 正确写入/清除，恢复后行不变 |
| 6. 回归（待设备在线） | 仅二级用户持有、及 U0+U10 混合持有的模块选择非 holder 目标；user 0 目标选择照常 | 非 `{0}` 持有者集合的跨用户目标仍被拒绝；原目标正常 |

设备在线前只能完成第 1—3 项；第 4—6 项必须记录设备、commit、日志文件名，未跑不得标 PASS。

## Explicit preservation

- 不改 GhostLock native、`src/core/**`、profile、route、wire、Kotlin↔Native 契约，避免触发 `cmp_disasm` 与真机攻击门禁。
- 不改 Vector `ModuleDatabase` schema、`ScopeTarget` AIDL、CLI 参数、备份 JSON 结构。
- 不改 `module.prop` 的版本字段、不重新签名 bootloader/OTA 相关资产。
- 不改 `restorelib.sh`。

## Progress

- [x] 场景、边界与方案确认（用户选择 A；跨用户放行限定为持有者恰好 `{0}`）
- [x] 设计文档写出并按独立 builder check 修订
- [x] 签名方案确认（用户要求自签名）
- [ ] 用户认可设计
- [ ] 批次 1：Vector 源码修改与自签名编译
- [ ] 批次 2：替换 vector.zip 条目并校验
- [ ] 批次 3：GhostLock 单元/构建验证
- [ ] 批次 4—6：设备门禁（待 adb 恢复）
