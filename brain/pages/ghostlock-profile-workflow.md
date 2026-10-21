---
id: ghostlock-profile-workflow
title: "GhostLock kernel profile 的生成与校验流程"
category: reference
status: active
tags: [ghostlock, profile, mediatek, workflow]
created: "2026-10-21T03:35:02"
updated: "2026-10-21T03:35:14"
---

<!-- compiled_truth -->
## 官方流程（上游 `docs/kernel_profiles/README.md`）

1. **取 `uname -r`** —— 保留完整字符串，任何字符差异都会导致匹配失败。
2. **按内核家族选模板**：`5.x` / `6.1` / `6.6` / `6.12`（`docs/kernel_profiles/templates/`）。
3. **复制模板** → `app/src/main/assets/kernel_profiles/<uname-r>.conf`（**文件名必须等于 release**）。
4. **提取偏移**：

   ```sh
   (cd tools/extract_rs && cargo build --release)
   build/extract/release/ghostlock-extract boot.img --format conf --out profile.conf
   ```

   - 可选输入：`--xbl_config.img`（高通）、`--vendor-boot vendor_boot.img`（**MediaTek**）、
     `--iomem iomem.txt`、`--kallsyms <file>`、`--route <name>`。
   - 输出是**候选种子**：不可得的值写显式 `null`（**绝不能写 0 充数**），
     app 会把缺失/非法字段记进 `invalidPaths` 并**阻止执行**。
   - extractor 遇到**已修补** `remove_waiter()` 的内核会**直接报错退出（退出码 6）** →
     这本身就是「内核是否带漏洞」的判据。
   - `pselect_waiter_shift` 与 `off_slide_loggers_0_1` 由内置 arm64 反汇编器推出；
     符号偏移来自镜像内嵌 kallsyms；结构体布局来自 BTF（5.x 还会推导凭据引用修复与多播几何）。
5. **逐字段核对** —— 必填 `off_*` 不能为 0；task/cred 布局必须来自同一镜像。
6. **`execution` 调优只在有设备证据时改**，否则保持共享默认。
7. **登记 `index.conf`**（`{ release, file }`），然后 `jq . index.conf` 校验 + 构建。
8. **真机验证** —— 反复测、固定 CPU、单路线、降温后再测；没通过真机验证不得标注为已支持。

## MediaTek 特殊项（`docs/kernel_profiles/MEDIATEK.md`）

- profile 需要 `kernel_phys_load` **和** `kernel_phys_offset`：
  - `kernel_phys_load`：内核物理加载地址（bootloader 选定）。
  - `kernel_phys_offset`：**DRAM 基址 / linear-map PHYS_OFFSET**。编译期默认 `0x80000000`，
    **MTK 可能是 `0x40000000`** → 不能套用默认值。
  - 来源优先级：`vendor_boot.img` 头（自带 load 地址，对齐能证明时还能推出 DRAM 基址）
    > `/proc/iomem`（需 root）> 公式回退。
  - `boot.img` **单独无法**提供 DRAM 基址。
- 上游提供 `tools/mtk-phys/` 脚本：需 root shell，从 `/proc/iomem` + `/proc/kallsyms` 读出两个值，
  再在 app 的「高级参数覆盖」页填入（不必重编 APK）。
- **警告**：跑利用前不要让内核带上 KernelSU 补丁或已加载 KernelSU 模块（BakaSU / ReSukiSU / KowSU
  同理），会干扰利用；脚本只检查 root 权限，kernel 是否有补丁要自己确认。

## 必填字段矩阵（PROFILE_SCHEMA 第 3 节）

三条 route 共同必填：`offset.init_task` / `init_cred` / `root_task_group` / `selinux_enforcing`；
`task_struct.prio` / `pi_lock` / `pi_waiters` / `pi_blocked_on` / `cred` / `seccomp`；
`kernel_major ∈ {5,6}`；`cred.copy_size` / `caps_count` 与凭据模板边界。

- `route.select_stack` 额外必填 `waiter_shift`（0 合法）。
- `route.tcp_zerocopy` 额外必填 `compact_waiter`。
- `route.multicast_waiter` 额外必填 `waiter_off`(>0) / `buffer_size` / `task_offset` / `lock_offset` /
  `compact_waiter` / `offset.empty_zero_page` / `kernelsnitch.mm_struct_sz` / `cred.ref_count`(>0)。

## 三条 route 的取舍

- profile **只能有一条** route 分支；可另外声明 `fallback.to`（当前实现只支持
  `tcp_zerocopy` → `select_stack` 回退）。
- 5.x 内置 profile 用 `multicast_waiter`；6.x 用 `tcp_zerocopy`（部分回落 `select_stack`）。
- 路线不适用时表现是利用失败 / 内核 panic → 换 route 分支重试。

## 现场迭代（不用重编 APK）

app 内两层覆盖：**通用参数覆盖**（`execution.*`：尝试次数 / 等待 / CPU 选择）与
**高级覆盖**（任意数值路径，含 `route` / `fallback.to` / 物理地址）。覆盖按 release 保存，
优先级高于内置 profile。导出配置可拿到合并后的完整 HOCON。

## 传输格式

app → native 走 **GLK1 v2 二进制**（stdin），不是 JSON：magic `0x0D000721`、version 2、
`release` 文本、section 表。CLI 运行用 `--load-prebuilt-profile <bin>`。


## Timeline

- time: 2026-10-21T03:35:02
  kind: decision
  summary: "Created this page: GhostLock kernel profile 的生成与校验流程"
  source: "上游文档 docs/kernel_profiles/*、PROFILE_SCHEMA.md、MEDIATEK.md"
  affects: [ghostlock-profile-workflow]

- time: 2026-10-21T03:35:14
  kind: decision
  summary: "固化 profile 生成/校验流程与 MediaTek 特殊项"
  source: "上游 docs/kernel_profiles/*"
  affects: [ghostlock-profile-workflow]
