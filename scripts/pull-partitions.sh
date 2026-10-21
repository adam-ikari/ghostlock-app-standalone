#!/usr/bin/env bash
# 取 boot.img / vendor_boot.img：先试 adb 直读块设备（很多机型 shell 有读权限），
# 再退回 fastboot fetch（只读，不解锁）。
#
#   ./scripts/pull-partitions.sh
#
# 产物：artifacts/partitions/boot.img, vendor_boot.img
set -u

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/artifacts/partitions"
mkdir -p "$OUT"
ADB=${ADB:-adb}
FASTBOOT=${FASTBOOT:-fastboot}
SERIAL=${SERIAL:-}
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"

pull_via_adb() {
  local part=$1
  echo "=== adb 直读 /dev/block/by-name/$part ==="
  if "$ADB" shell "cat /dev/block/by-name/$part" > "$OUT/$part.img" 2>/dev/null &&
     [ -s "$OUT/$part.img" ]; then
    ls -l "$OUT/$part.img"
    return 0
  fi
  rm -f "$OUT/$part.img"
  echo "（shell 无读权限）"
  return 1
}

pull_via_fastboot() {
  local part=$1
  echo "=== fastboot fetch $part ==="
  if "$FASTBOOT" fetch "$part" "$OUT/$part.img" 2>&1; then
    ls -l "$OUT/$part.img"
    return 0
  fi
  rm -f "$OUT/$part.img"
  echo "（bootloader 不支持 fetch）"
  return 1
}

for part in boot vendor_boot; do
  if ! pull_via_adb "$part"; then
    pull_via_fastboot "$part" || true
  fi
done

echo
echo "=== 结果 ==="
ls -l "$OUT" 2>/dev/null
for f in "$OUT"/*.img; do
  [ -e "$f" ] || continue
  echo "--- $f ---"
  file "$f" 2>/dev/null || true
done
