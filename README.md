# GhostLock-App

[中文](README_ZH.md)

GhostLock uses CVE-2026-43499 to gain temporary root on supported Android kernels and load KernelSU.

## Use the app

1. Install a root manager APK from [KernelSU](https://github.com/tiann/KernelSU), [BakaSU/ReSukiSU](https://github.com/Baka-SU/BakaSU), or [KowSU](https://github.com/KOWX712/KernelSU).
2. Install the GhostLock APK and open it.
3. Check that your kernel is supported, then tap **Run**.

Support requires an exact `uname -r` match. Check the [supported devices](docs/kernel_profiles/SUPPORTED_DEVICES.md) for your kernel build.

Any root manager that ships `libksud.so` provides `ksud` for module loading. The built-in list (KernelSU, ReSukiSU, BakaSU, KowSU) is auto-detected, and other managers can be picked in **Settings → Root manager**. Without `ksud`, W1/W2 still grant uid 0 but no module is loaded.

## Add a kernel profile

You can import a profile without rebuilding the app:

- **Parse OTA link** accepts a full OTA ZIP URL.
- **Parse local image** accepts `boot.img` and an optional `xbl_config.img`.
- **Import offsets.conf (v2)** accepts a HOCON profile. **Import offsets.json (v1)** converts an older JSON report in the app.

Extractor output is an unverified candidate. The app checks missing or invalid fields before execution. Generating a profile does not prove device support. See the [porting guide](docs/kernel_profiles/README.md) for validation and built-in registration.

See the [MediaTek guide](docs/kernel_profiles/MEDIATEK.md) for image-based address recovery and the rooted-device fallback.

## Command-line tools

<details>
<summary>Run the native binary with an exported profile</summary>

Requires the Android NDK, `make`, Gradle, and ADB. An adb shell run skips the app's seccomp bypass stage.

```sh
make -C src ghostlock
./gradlew exportKernelProfiles
adb push build/native/ghostlock /data/local/tmp/ghostlock
adb push "build/kernel-profiles/<release>.bin" /data/local/tmp/profile.bin
adb shell chmod 755 /data/local/tmp/ghostlock
adb shell /data/local/tmp/ghostlock --load-prebuilt-profile /data/local/tmp/profile.bin
```

</details>

<details>
<summary>Build and run the offset extractor</summary>

Requires Rust. The extractor accepts a boot image, a full OTA ZIP, or an HTTP(S) URL.

```sh
(cd tools/extract_rs && cargo build --release)
build/extract/release/ghostlock-extract boot.img --xbl-config xbl_config.img --format conf --out profile.conf
build/extract/release/ghostlock-extract OTA.zip --format conf --out profile.conf
```

Import `profile.conf` in the app. Omit `--xbl-config` if you do not have that image. The extractor rejects a patched `remove_waiter()` with exit code `6`.

</details>

## Reference

- [Profile schema](docs/kernel_profiles/PROFILE_SCHEMA.md) and [execution defaults](docs/kernel_profiles/defaults.md)
- [Native core](src/core/README.md)
- [Add a component](docs/development/adding-a-component.md), [engineering rules](docs/development/engineering-standards.md), and [writing rules](docs/development/documentation-standards.md)

## Credits and license

本项目整体按 [PolyForm Noncommercial License 1.0.0](LICENSE) 发布：代码公开、可读、可改、可自行编译，**禁止商业用途**；商业授权需单独取得书面许可。这不是 OSI 定义的开源许可（OSI 不允许限制使用领域），按惯例称 source-available。

`src/core/kernelsnitch/` 的 `futex_hash.h`、`kernelsnitch.h`、`timeutils.h`、`utils.h` 移植自
[NebuSec/CyberMeowfia](https://github.com/NebuSec/CyberMeowfia)（Apache-2.0），许可全文见
[`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)，完整归属说明见 [`NOTICE`](NOTICE)。

研究阶段参考过：
[JoinChang/ghostlock-oneplus](https://github.com/JoinChang/ghostlock-oneplus)、
[x-spy/CVE-2026-43499-popsicle](https://github.com/x-spy/CVE-2026-43499-popsicle)。

ZygiskNext、Vector、KernelSU 管理器都不随本仓库或 APK 分发，由用户运行时自行获取；原因见 `NOTICE`。
