#!/usr/bin/env bash
# Pull boot.img / vendor_boot.img off the phone via fastboot (read-only, no unlock).
#
#   fastboot reboot bootloader
#   ./scripts/fetch-partitions.sh
#
# `fastboot fetch` only reads the partition; it does not write it, so it works on a
# locked bootloader on most AOSP-derived bootloaders (MediaTek included).
set -u

FASTBOOT=${FASTBOOT:-fastboot}
OUT=${OUT:-artifacts/partitions}
mkdir -p "$OUT"

for part in boot vendor_boot; do
  echo "=== fetching $part ==="
  if "$FASTBOOT" fetch "$part" "$OUT/$part.img"; then
    ls -l "$OUT/$part.img"
  else
    echo "!! fastboot fetch $part failed (bootloader may not implement fetch)"
  fi
done

echo
echo "=== fastboot vars (for reference) ==="
"$FASTBOOT" getvar all 2>&1 | head -60
