---
slug: flow
title: Key flows
role: key flows
updated: "2026-10-21T03:34:08"
---

# Key flows

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户 / 脚本
    participant D as Titan 2
    participant F as fastboot
    participant E as extract_rs (Rust)
    participant A as ghostlock-app
    participant N as native core
    participant K as KernelSU / ZygiskNext / LSPosed

    U->>D: adb shell（阶段 0）<br/>uname -r / SoC / bootloader 锁定状态 / SELinux
    D-->>U: 设备事实（props 快照）
    U->>F: fastboot reboot bootloader
    F-->>U: fastboot fetch boot, vendor_boot
    U->>E: extract boot.img --vendor-boot vendor_boot.img --format conf
    E-->>U: profile 骨架（含 null 字段）<br/>若内核已修补则直接报错退出(6)
    U->>U: 按模板补齐 null 字段<br/>几何 + 物理地址 + 路线
    U->>A: 装 APK（内置或导入 profile）
    A->>N: 通过 stdin 传 GLK1 二进制 profile
    N->>D: W1 关 SELinux → W2 写凭据 → W3 seccomp 绕过
    N-->>D: uid 0
    N->>K: ksud late-load 装 KernelSU
    K->>K: 装 ZygiskNext → 装 LSPosed 模块
    U->>D: 重启 app，确认 LSPosed 生效
```

## 失败点与回退

| 失败点 | 现象 | 处理 |
| --- | --- | --- |
| 内核已打补丁 | extractor 退出码 6 / 明确提示 patched `remove_waiter()` | 路线作废 → 换其他临时 root 手段（记录否定结论） |
| `fastboot fetch` 不支持 | bootloader 报未知命令 | 改用官方固件包；或先用其他手段取得 root 再 dump |
| 物理地址错 | W1 反复重试到上限后 `Write 1 failed` | 用 app 高级覆盖页改 `kernel_phys_load` / `kernel_phys_offset` 迭代 |
| 路线不适用 | 利用失败或内核 panic | 换 route（`multicast_waiter` / `tcp_zerocopy` / `select_stack`），改 profile 里的路线分支 |
| 竞态不稳 | 偶发成功偶发失败 | 调 `execution`（尝试次数 / 等待 / CPU 绑定），设备降温后再试 |
