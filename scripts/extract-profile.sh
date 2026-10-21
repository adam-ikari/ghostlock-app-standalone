#!/usr/bin/env bash
# 从 boot.img（+ vendor_boot.img）生成 GhostLock kernel profile 与内核分析报告。
#
#   ./scripts/extract-profile.sh [boot.img] [vendor_boot.img] [输出目录]
#
# 前置：vendor/ghostlock-app/tools/extract_rs 能构建（本脚本会自动构建）。
# 产物：<输出目录>/profile.conf   （HOCON profile，可直接导入 app）
#       <输出目录>/analysis.txt   （内核分析报告：家族 / 路线建议 / waiter 布局 / 是否已打补丁）
#
# 注意：extractor 的输出只是**候选种子**——取不到的字段写显式 null（不是 0）。
# app 导入后会校验并把 null 记进 invalidPaths 阻止执行，真机跑通前不算「已支持」。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
UPSTREAM="$ROOT/vendor/ghostlock-app"
BOOT=${1:-"$ROOT/artifacts/partitions/boot.img"}
VENDOR=${2:-"$ROOT/artifacts/partitions/vendor_boot.img"}
OUT_DIR=${3:-"$ROOT/artifacts/titan2"}
EXTRACT_DIR="$UPSTREAM/tools/extract_rs"
TOOLCHAIN=${RUST_TOOLCHAIN:-nightly-2026-08-20}

# 仓库把 cargo 产物放在 build/extract/（CARGO_TARGET_DIR），不是 target/
BIN="$UPSTREAM/build/extract/release/ghostlock-extract"
[ -x "$BIN" ] || BIN="$EXTRACT_DIR/target/release/ghostlock-extract"

[ -f "$BOOT" ] || { echo "!! 找不到 boot.img: $BOOT" >&2; exit 1; }
mkdir -p "$OUT_DIR"

if [ ! -x "$BIN" ]; then
  echo "=== 构建提取器（toolchain=$TOOLCHAIN）==="
  (cd "$EXTRACT_DIR" && cargo "+$TOOLCHAIN" build --release)
  BIN="$UPSTREAM/build/extract/release/ghostlock-extract"
  [ -x "$BIN" ] || BIN="$EXTRACT_DIR/target/release/ghostlock-extract"
fi

# 先出分析报告：内核家族、waiter 布局、可用路线、漏洞原语是否还在
REPORT="$OUT_DIR/analysis.txt"
echo "=== 内核分析 → $REPORT ==="
set +e
"$BIN" "$BOOT" ${VENDOR:+--vendor-boot "$VENDOR"} --analysis 2>&1 | tee "$REPORT"
rc=${PIPESTATUS[0]}
set -e
if [ "$rc" -eq 6 ]; then
  echo "!! 提取器退出码 6：内核的 remove_waiter() 已修补 → CVE-2026-43499 不存在，这条路作废。" >&2
  exit 6
fi

# 再出 profile
OUT="$OUT_DIR/profile.conf"
echo
echo "=== 生成 profile → $OUT ==="
"$BIN" "$BOOT" ${VENDOR:+--vendor-boot "$VENDOR"} --format conf --out "$OUT"

echo
echo "=== 仍为 null 的字段（需人工补 / 真机反推）==="
if grep -n "= null" "$OUT"; then
  echo "^^ 这些字段要在 app 的「高级参数覆盖」页补上，或换来源补齐"
else
  echo "（无）"
fi
echo
echo "=== 关键字段 ==="
grep -nE '^(release|kernel_phys_load|kernel_phys_offset) ' "$OUT" || true
echo
echo "下一步：把 $OUT 改名成 <uname-r>.conf 放进"
echo "  $UPSTREAM/app/src/main/assets/kernel_profiles/ 并登记 index.conf。"
