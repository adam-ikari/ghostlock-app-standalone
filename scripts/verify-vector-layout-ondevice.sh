#!/usr/bin/env bash
# On-device check for the Vector layout fix in ops.cpp.
#
# Run AFTER installing the debug APK and pressing apply in the app.
# Answers one question: did the installer actually produce zygisk/<abi>.so,
# and does rezygisk now report a non-empty module list?
#
# Usage: ./verify-vector-layout-ondevice.sh [serial]

set -uo pipefail
SER="${1:-}"
ADB=(adb)
[ -n "$SER" ] && ADB=(adb -s "$SER")

pass=0; fail=0
chk() { # chk <label> <shell-test-output>
  if [ "$2" = "0" ] || [ "$2" = "ok" ]; then
    printf '  \033[32mOK\033[0m   %s\n' "$1"; pass=$((pass+1))
  else
    printf '  \033[31mFAIL\033[0m %s\n' "$1"; fail=$((fail+1))
  fi
}

"${ADB[@]}" wait-for-device
"${ADB[@]}" root >/dev/null 2>&1

echo "== module payload =="
ZSO=$("${ADB[@]}" shell 'ls /data/adb/modules/zygisk_vector/zygisk/*.so 2>/dev/null | wc -l' | tr -d '\r')
echo "  zygisk/*.so count: $ZSO"
chk "zygisk_vector ships zygisk/<abi>.so" "$([ "${ZSO:-0}" -ge 1 ] && echo 0 || echo 1)"

for f in bin/dex2oat64 bin/liboat_hook64.so; do
  has=$("${ADB[@]}" shell "[ -f /data/adb/modules/zygisk_vector/$f ] && echo ok" | tr -d '\r')
  chk "zygisk_vector has $f" "${has:-missing}"
done

echo "== anti-detection constant =="
MAGIC=5291374ceda0aef7c5d86cd2a4f6a3ac
LEFT=$("${ADB[@]}" shell "grep -c $MAGIC /data/adb/modules/zygisk_vector/daemon.apk 2>/dev/null || echo 0" | tr -d '\r')
echo "  daemon.apk occurrences left: $LEFT"
chk "daemon.apk constant replaced" "$([ "${LEFT:-1}" = "0" ] && echo 0 || echo 1)"

echo "== rezygisk view =="
echo "  state.json:"
"${ADB[@]}" shell 'cat /data/adb/rezygisk/state.json 2>/dev/null' | tr -d '\r' | sed 's/^/    /'
MODS=$("${ADB[@]}" shell 'grep -o "zygisk_vector" /data/adb/rezygisk/state.json 2>/dev/null | head -1' | tr -d '\r')
chk "state.json lists zygisk_vector" "${MODS:-absent}"

echo
echo "pass=$pass fail=$fail"
[ "$fail" -eq 0 ] || { echo "Not done. Collect: adb shell cat /data/user/0/com.ghostlock.app/files/.ghostlock_ksu.log"; exit 1; }
echo "Vector layout is live. Next: confirm the app reports READY, then reboot once and re-check state.json."
