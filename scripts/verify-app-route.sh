#!/usr/bin/env bash
# app 路线的端到端验收：装 app → 放组件包 → 跑 → 验证 LSPosed 激活。
#
#   ./scripts/verify-app-route.sh <release> [apk]
#
# 前提：
#   - 手机已**解锁**（没解锁时 CE 存储没建好，侧载的 app 起不来，
#     报 "Activity class ... does not exist"，看着像 APK 坏了，其实是没解锁）
#   - app 装完要 `pm enable`（新装的应用默认是禁用的）
#   - 组件包在 app 的 files/modules/ 里（app 会发布到 external files dir 交给 native）
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE=${1:?用法: verify-app-route.sh <release> [apk]}
APK=${2:-$(ls -t "$ROOT"/build/app/outputs/apk/release/GhostLock-*-arm64-v8a-release.apk 2>/dev/null | head -1)}
ADB=${ADB:-adb}
SERIAL=${SERIAL:-}
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"
PKG=com.ghostlock.app
MODULES=/data/local/tmp/ghostlock-modules
VEC=/storage/emulated/0/Android/data/$PKG/files/modules-published

sh_() { "$ADB" shell "$@"; }

echo "=== 0. 前置检查 ==="
LOCK=$(sh_ "dumpsys user 2>/dev/null | grep -o 'RUNNING_LOCKED' | head -1")
if [ -n "$LOCK" ]; then
  echo "!! 设备处于锁定状态（RUNNING_LOCKED）" >&2
  echo "   CE 存储没建好，侧载的 app 起不来。先解锁手机。" >&2
  exit 1
fi
echo "设备已解锁 ✓"

echo "=== 1. 安装 app ==="
[ -f "$APK" ] || { echo "!! 缺 APK：$APK" >&2; exit 1; }
"$ADB" install -r "$APK" 2>&1 | tail -1
sh_ "pm enable $PKG" 2>&1 | tail -1
sh_ am start -n $PKG/.ui.MainActivity 2>&1 | tail -1
sleep 6
if ! sh_ "ps -A 2>/dev/null | grep -q $PKG"; then
  echo "!! app 没起来（先确认已解锁、已 pm enable）" >&2
  exit 1
fi
echo "app 已启动 ✓"

echo "=== 2. 放组件包 ==="
sh_ "mkdir -p $MODULES"
for z in vector zygisknext; do
  "$ADB" push "$ROOT/artifacts/modules/$z.zip" "$MODULES/$z.zip" >/dev/null 2>&1 \
    || sh_ "cp $MODULES/$z.zip $MODULES/$z.zip" 2>/dev/null
done
APP_UID=$(sh_ "dumpsys package $PKG | grep -oE 'appId=[0-9]+' | head -1 | cut -d= -f2")
[ -n "$APP_UID" ] || { echo "!! 拿不到 app uid" >&2; exit 1; }
sh_ "su -c 'mkdir -p /data/data/$PKG/files/modules'" 2>/dev/null || true
for z in vector zygisknext; do
  sh_ "su -c 'cp $MODULES/$z.zip /data/data/$PKG/files/modules/$z.zip && chown $APP_UID:$APP_UID /data/data/$PKG/files/modules/$z.zip'" 2>/dev/null || true
done
sh_ "su -c 'ls -l /data/data/$PKG/files/modules/'" 2>/dev/null | head -4

echo "=== 3. 点执行（在 app 界面按「执行」按钮）==="
echo "   （或者用 app 的自动化接口；这里先确认 app 起来了）"
echo "   → 手动点 app 里的「执行」按钮"

echo "=== 4. 验收（跑完后执行）==="
cat <<EOF
检查：
  adb shell 'su -c "cat /data/data/$PKG/files/ghostlock-ksu-*.log" | tail -20'
  应出现：child is root! / KernelSU ready / module install step finished /
          zygote patched=1 / zygisk modules: modules64:1,zygisk_vector /
          LSPosed stack setup done
  adb shell 'su -c "/data/adb/modules/zygisk_vector/cli status"'
  应出现：Framework Version: 2.2 / API Version: 102
EOF
