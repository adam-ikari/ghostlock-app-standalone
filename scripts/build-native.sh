#!/usr/bin/env bash
# 构建 GhostLock 原生 payload（CLI 路线用）。
#
#   ./scripts/build-native.sh
#
# 产物：build/native/ghostlock
#
# NDK 选择顺序：
#   1) $ONDK_HOME            —— 上游 CI 用的 ONDK r30.x（自带 rust 工具链，推荐）
#   2) $ANDROID_NDK_HOME     —— 普通 NDK
#   3) $ROOT/local.properties 的 ndk.dir
#
# 注意：NDK 27/28 的 bionic 头会 #define PAGE_SIZE 4096，和上游
# src/core/kernel/constants.hpp 里的 constexpr PAGE_SIZE 撞名 → 编译失败。
# 上游 CI 用 ONDK r30（r30 的 bionic 已经不再定义该宏），所以优先用 ONDK。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
UPSTREAM="$ROOT"
ONDK_DEFAULT=/home/gem/android-sdk/ondk-r30.1

if [ -z "${ONDK_HOME:-}" ] && [ -d "$ONDK_DEFAULT" ]; then
  export ONDK_HOME="$ONDK_DEFAULT"
fi

if [ -n "${ONDK_HOME:-}" ]; then
  export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ONDK_HOME}"
elif [ -z "${ANDROID_NDK_HOME:-}" ] && [ -f "$UPSTREAM/local.properties" ]; then
  ndk=$(sed -n 's/^ndk\.dir=//p' "$UPSTREAM/local.properties" | tail -1)
  [ -n "$ndk" ] && export ANDROID_NDK_HOME="$ndk"
fi
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-$ANDROID_NDK_HOME}"

echo "=== native payload (NDK=$ANDROID_NDK_HOME) ==="
make -C "$UPSTREAM/src" ghostlock
ls -l "$UPSTREAM/build/native/ghostlock"
