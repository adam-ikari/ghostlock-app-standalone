---
id: local-toolchain-gotchas
title: "本机搭建 GhostLock 工具链的坑"
category: reference
status: active
tags: [toolchain, ndk, rust, build]
created: "2026-10-21T03:46:13"
updated: "2026-10-21T04:43:06"
---

<!-- compiled_truth -->
## 1. extractor 的 rustc 版本门槛

`tools/extract_rs/Cargo.toml` 写 `rust-version = "1.97.1"`，本机 stable 是 **1.95.0** → 直接构建会被拒。
解法：用已装的 `nightly-2026-08-20`：

```sh
cargo +nightly-2026-08-20 build --release
```

（`scripts/extract-profile.sh` 里已经这么写，并用 `RUST_TOOLCHAIN` 环境变量兜底。）

## 2. NDK 27 与上游 `PAGE_SIZE` 宏冲突（native 构建失败）

用 NDK 27.1 构建 native payload 会报：

```
core/kernel/constants.hpp:10:36: error: expected unqualified-id
  inline constexpr unsigned long PAGE_SIZE = 1UL << PAGE_SHIFT;
```

原因：NDK 27 的 sysroot `bits/page_size.h` 里 `#define PAGE_SIZE 4096`（条件是 `__NDK_MAJOR__ <= 27`），
宏把上游的 `constexpr` 变量名替换掉了。NDK 26 的 sysroot **没有**这个头文件 → 不冲突。

但 **NDK 26 没有 `aarch64-linux-android35-clang++`**（r26 只到 API 34），而上游 `src/Makefile`
默认 `API ?= 35` → 换 NDK 26 会变成 `No such file or directory`。

结论：**两个本地 NDK 都单独用不了**，正解是上游 CI 的做法——用
[ondk](https://github.com/topjohnwu/ondk) r30.1（自带新 NDK + 可 `build-std` 的 Rust 工具链）：

```sh
curl -fL -o ondk.tar.xz https://github.com/topjohnwu/ondk/releases/download/r30.1/ondk-r30.1-linux.tar.xz
tar -xf ondk.tar.xz -C ondk --strip-components=1
rustup toolchain link ondk "$PWD/ondk/toolchains/rust"
# local.properties 里写 ondk.dir=<ondk 路径>，并让 ANDROID_NDK_HOME 指向 ondk
```

（ondk 内的 NDK 版本 ≥ 28 → 没有 `PAGE_SIZE` 宏；且 `aarch64-linux-android35-clang++` 存在。）

### 已在本机落地（2026-10-21）

- ONDK r30.1 解包在 `/home/gem/android-sdk/ondk-r30.1`（396MB，GitHub release 约 10 分钟）。
- `rustup toolchain link ondk /home/gem/android-sdk/ondk-r30.1/toolchains/rust`
  → `rustc 1.97.1-nightly (8bab26f4f 2026-07-14)`，满足 extractor 的 rust-version。
- `ANDROID_NDK_HOME=/home/gem/android-sdk/ondk-r30.1 make -C src ghostlock` **编译通过**（零告警），
  产物 `vendor/ghostlock-app/build/native/ghostlock`（aarch64，约 1.19MB）。
- `scripts/build-native.sh` 会自动优先用 `ONDK_HOME`（默认 `/home/gem/android-sdk/ondk-r30.1`）。
- 另有一条不用 ONDK 的退路：NDK 27 的 `bits/page_size.h` 自己写了开关
  `__BIONIC_NO_PAGE_SIZE_MACRO`，加上 `-D__BIONIC_NO_PAGE_SIZE_MACRO` 就不定义该宏。
  但上游 Makefile 的 `CXXFLAGS :=` 是**直接赋值**，命令行 `CXXFLAGS+=...` 不会追加（会整条替换），
  要用得先读出原值再拼——不如直接上 ONDK。

## 3. app 的 compileSdk 是 37.2

`app/build.gradle.kts` 用 `compileSdk { version = release(37) { minorApiLevel = 2 } }`，
本机原本只有 platform 31/34/35/36 → 需要 `sdkmanager --install "platforms;android-37.2"`
（外加 `build-tools;37.0.0`）。AGP 9.1.0 会对 compileSdk 37.2 报「tested up to 36.1」的**警告**
（不阻断），必要时在 `gradle.properties` 加 `android.suppressUnsupportedCompileSdk=37.2`。

## 4. gradle 与手工 cargo 会互相抢锁

`./gradlew :app:assembleDebug` 里的 `:buildGhostlockExtract` 与手工 `cargo build` 用同一个
`~/.cargo` package cache，会出现 `Blocking waiting for file lock on package cache`。
做法：先让手工的 host 构建跑完（或反过来），不要同时跑。

## 5. 交叉编译 Rust 给 Android 的 std 装不上

`rustup target add aarch64-linux-android --toolchain nightly-2026-08-20` 在本机会
`tls handshake eof`（static.rust-lang.org 拉不动），多次重试也不行。
解决：用 ONDK 自带的 rust 工具链（gradle 任务在 `ONDK_HOME` 存在时会自动用 `cargo +ondk`）。


## Timeline

- time: 2026-10-21T03:46:13
  kind: decision
  summary: "Created this page: 本机搭建 GhostLock 工具链的坑"
  source: "本项目实测（2026-10-21）"
  affects: [local-toolchain-gotchas]

- time: 2026-10-21T03:46:22
  kind: decision
  summary: "固化本机工具链的三个坑与解法"
  source: "本项目实测（2026-10-21）"
  affects: [local-toolchain-gotchas]

- time: 2026-10-21T04:43:06
  kind: decision
  summary: "记录 ONDK 已就位 + NDK27 的 PAGE_SIZE 官方开关"
  source: "本机实测（2026-10-21）"
  affects: [local-toolchain-gotchas]

- time: 2026-10-21T04:43:06
  kind: evidence
  summary: "本机 ONDK r30.1 就位：native payload 编译通过（零告警），并记录 NDK27 的 __BIONIC_NO_PAGE_SIZE_MACRO 开关"
  source: "本机实测"
  affects: [local-toolchain-gotchas]
