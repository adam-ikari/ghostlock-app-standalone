#!/usr/bin/env bash
# 从 boot.img（+ vendor_boot.img）生成 GhostLock kernel profile。
#
#   ./scripts/extract-profile.sh [boot.img] [vendor_boot.img]
#
# 前置：vendor/ghostlock-app/tools/extract_rs 已能构建（本脚本会自动构建）。
# 产物：artifacts/profiles/<release>.conf   （HOCON profile，可直接导入 app）
#       artifacts/analysis/<release>.txt     （内核分析报告：家族 / 路线建议 / waiter 布局）
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
BOOT=${1:-"$ROOT/artifacts/partitions/boot.img"}
VENDOR=${2:-"$ROOT/artifacts/partitions/vendor_boot.img"}
EXTRACT_DIR="$ROOT/vendor/ghostlock-app/tools/extract_rs"
BIN="$EXTRACT_DIR/target/release/ghostlock-extract"
TOOLCHAIN=${RUST_TOOLCHAIN:-nightly-2026-08-20}

[ -f "$BOOT" ] || { echo "!! 找不到 boot.img: $BOOT" >&2; exit 1; }

if [ ! -x "$BIN" ]; then
  echo "=== 构建提取器（toolchain=$TOOLCHAIN）==="
  (cd "$EXTRACT_DIR" && cargo "+$TOOLCHAIN" build --release)
fi

mkdir -p "$ROOT/artifacts/profiles" "$ROOT/artifacts/analysis"

# 先出分析报告：内核家族、waiter 布局、可用路线、是否已打补丁
REPORT="$ROOT/artifacts/analysis/$(basename "$BOOT").txt"
echo "=== 内核分析 → $REPORT ==="
if ! "$BIN" "$BOOT" --analysis 2>&1 | tee "$REPORT"; then
  echo "!! 提取器报错（退出码非 0）→ 先看报告：内核可能已修补 CVE-2026-43499" >&2
  exit 1
fi

# 再出 profile
OUT="$ROOT/artifacts/profiles/profile.conf"
echo
echo "=== 生成 profile → $OUT ==="
ARGS=(--format conf --out "$OUT")
[ -f "$VENDOR" ] && ARGS+=(--vendor-boot "$VENDOR")
"$BIN" "$BOOT" "${ARGS[@]}"
echo

# 报告未填字段（null 才是「未取得」，0 会被当成真实值）
echo "=== 仍为 null 的字段（需要人工补 / 真机反推）==="
if grep -n "= null" "$OUT"; then
  echo "^^ 这些字段必须在 app 的「高级参数覆盖」页补上，或用其他来源补齐"
else
  echo "（无）"
fi
echo
echo "=== profile 头部 ==="
head -20 "$OUT"
echo
echo "下一步：把 $OUT 导入 app（Import offsets.conf (v2)），或改名成 <uname-r>.conf 放进"
echo "  vendor/ghostlock-app/app/src/main/assets/kernel_profiles/ 并登记 index.conf。"
