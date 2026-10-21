#!/usr/bin/env bash
# 取官方固件（国际版 TEE）里的 boot.img / vendor_boot.img，不需要设备、不需要解锁。
#
#   ./scripts/fetch-firmware.sh [build-date]      # 例如 2025111915
#   ./scripts/fetch-firmware.sh --list             # 列出镜像上可用的固件
#
# 固件来源：Unihertz 官方 Google Drive 固件，社区镜像（Seafile）转存。
# 固件包 3.5GB，但解 profile 只需要 boot.img / vendor_boot.img，
# 所以用 scripts/remote-zip-extract.py 走 HTTP Range 只取这几个成员。
set -eu

SHARE=${SHARE:-ccc9017f47a6459d8b37}          # lichtmetzger.de 的固件镜像
BASE=${BASE:-https://cloud.lichtmetzger.de/d/$SHARE/files/}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT_ROOT=${OUT_ROOT:-artifacts}
REGION=${REGION:-TEE}                          # TEE=国际版/全球版, EEA=欧洲版

list_dir() {                                   # $1 = 目录路径（URL 编码前）
  local path
  path=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$1")
  curl -sS "https://cloud.lichtmetzger.de/api/v2.1/share-links/$SHARE/dirents/?path=$path" |
    python3 -c "
import json,sys
d=json.load(sys.stdin)
for e in d.get('dirent_list',[]):
    print(e.get('file_name') or e.get('folder_name'))
"
}

if [ "${1:-}" = "--list" ]; then
  echo "=== /Firmware/Android 15/$REGION ==="
  list_dir "/Firmware/Android 15/$REGION"
  echo "=== /Firmware/Android 16/$REGION ==="
  list_dir "/Firmware/Android 16/$REGION"
  exit 0
fi

BUILD=${1:-2025111915}                          # 默认：Android 15 固件 1.00.10
for major in 15 16; do
  name=$(list_dir "/Firmware/Android $major/$REGION" | grep -E "^${BUILD}_.*\.zip$" | head -1 || true)
  if [ -n "$name" ]; then
    ANDROID=$major
    ZIP=$name
    break
  fi
done

if [ -z "${ZIP:-}" ]; then
  echo "!! 没找到固件 $BUILD（$REGION）。用 --list 看可用版本。" >&2
  exit 1
fi

OUT="$OUT_ROOT/fw-$REGION-$BUILD"
mkdir -p "$OUT"
URL="${BASE}?p=%2FFirmware%2FAndroid%20${ANDROID}%2F${REGION}%2F${ZIP}&dl=1"

echo "=== $ZIP -> $OUT ==="
"$HERE/remote-zip-extract.py" "$URL" "$OUT" boot.img vendor_boot.img
ls -l "$OUT"
echo
echo "下一步："
echo "  (cd vendor/ghostlock-app/tools/extract_rs && cargo build --release)"
echo "  vendor/ghostlock-app/build/extract/release/ghostlock-extract \\"
echo "      $OUT/boot.img --vendor-boot $OUT/vendor_boot.img \\"
echo "      --format conf --out $OUT/profile.conf"
