#!/usr/bin/env bash
# 构建 GhostLock 原生 payload（CLI 路线用）。
#
#   ./scripts/build-native.sh
#
# 产物：vendor/ghostlock-app/build/native/ghostlock
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
UPSTREAM="$ROOT/vendor/ghostlock-app"
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-/home/gem/android-sdk/ndk/27.1.12297006}
export ANDROID_NDK_ROOT=$ANDROID_NDK_HOME

echo "=== native payload (NDK=$ANDROID_NDK_HOME) ==="
make -C "$UPSTREAM/src" ghostlock
ls -l "$UPSTREAM/build/native/ghostlock"
