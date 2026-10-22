#!/usr/bin/env bash
# 在**已经拿到 root** 的设备上装 ZygiskNext + Vector(LSPosed) 并激活 LSPosed 框架。
#
#   ./scripts/start-lsposed.sh <uname-r> [module-dir]
#
# 前置：
#   - 已用 root 跑过利用（scripts/run-exploit.sh），KernelSU 已 late-load 进内存；
#     `su -c id -u` 应返回 0。
#   - module-dir 里放两个 zip：zygisknext.zip、vector.zip（默认 $ROOT/artifacts/modules）。
#
# ── 完整时序（2026-10-22 在 Unihertz Titan 2 / Android 16 上真机验证通过）──
#
#   ① 装模块（su 域，用**已安装那份** /data/adb/ksu/bin/ksud；
#      装之前先删旧模块目录，旧目录残留会让 install 报
#      "i/o error: No such file or directory"）
#   ② late-load 一次 → KernelSU 进内存，shell 用户拿到 su，
#      同时跑各模块的 post-fs-data.sh（生成 /data/adb/zygisksu/modules_info）
#   ③ 杀掉 zygote64 → init 重启它 → ZygiskNext 的 injector 接管新 zygote
#      （新 zygote 带 -Xzygote 参数，且 Vector 的 zygisk 模块被 preload）
#      **必须重启 zygote**：模块是在 zygote 启动那一刻被注入的，
#      运行时装模块不会影响已经跑着的 zygote。
#   ④ 等 60 秒让注入稳定（不等 → Vector daemon 会重启 system_server，
#      framework 起来一半挂掉 → rescue party → recovery）
#   ⑤ 起 Vector daemon（**必须用绝对路径**！）
#
# ── 三个踩过的坑（都写在下面脚本里了）──
#   1. `daemon` 脚本内部用 `dir="${0%/*}"` 定位自己的目录，**要求 $0 是绝对路径**。
#      用相对路径（`cd $DIR && ./daemon`）会得到 dir="."，于是
#      -Djava.class.path=daemon.apk 是相对路径 →
#      UnsatisfiedLinkError: Expecting an absolute path of the library
#      → daemon 静默退出。
#   2. toybox 的 unshare **不支持 --propagation slave**（Vector 自带的 service.sh
#      用的就是它）→ 只能退化成 `unshare -m`。
#   3. zygisksu 模块目录里的 `disable` 标记会让它整个不加载
#      （znctl status 显示 modules64:1 但 zn 模块为 0）→ 装完必须确认没有 disable。
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "$0")/.." && pwd)
RELEASE=${1:?用法: start-lsposed.sh <uname-r> [module-dir]}
MODDIR=${2:-$ROOT/artifacts/modules}
ADB=${ADB:-adb}
SERIAL=${SERIAL:-}
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"

DEV_MODULES=/data/local/tmp/modules
KSU_KSUD=/data/adb/ksu/bin/ksud
ZNCTL=/data/adb/ksu/bin/znctl
VECTOR_DIR=/data/adb/modules/zygisk_vector
KSU_KMI=android14-6.1

sh_() { "$ADB" shell "$@"; }
# su 域执行：模块安装必须走 su（root 脚本所在域写不了 /data/adb/modules_update）。
su_() { "$ADB" shell "su -c '$*'"; }
# 带超时的 su：KernelSU 刚 late-load 之后，su helper 可能阻塞等 service 稳定，
# 挂住的调用会拖死整个流程（2026-10-22 真机踩到）。
su_t() { "$ADB" shell "su -c 'timeout 30 $*'"; }

echo "=== 前置检查 ==="
cat <<'WARN'
!! 这个脚本会**杀 zygote64**（让 ZygiskNext 注入新 zygote）。
!! 反复执行会反复杀 zygote，framework 起不来 → rescue party → 进 recovery
!! （recovery 里 adbd 关着，只能长按电源键）。
!! 一次跑完即可；中途失败请重启手机再来，不要重复执行。
WARN
ROOT_UID=$(sh_ "su -c id -u" 2>/dev/null | tr -d '\r')
if [ "$ROOT_UID" != "0" ]; then
  echo "!! 当前没有 root（su -c id -u = '$ROOT_UID'）" >&2
  echo "   先跑 scripts/run-exploit.sh $RELEASE 拿到 root" >&2
  exit 1
fi
echo "root ok"

[ -d "$MODDIR" ] || { echo "!! 缺模块目录：$MODDIR" >&2; exit 1; }
for z in zygisknext.zip vector.zip; do
  [ -f "$MODDIR/$z" ] || { echo "!! 缺模块包：$MODDIR/$z" >&2; exit 1; }
done

echo "=== 推送模块包 ==="
sh_ "mkdir -p $DEV_MODULES"
for z in zygisknext.zip vector.zip; do
  "$ADB" push "$MODDIR/$z" "$DEV_MODULES/$z" >/dev/null
done

echo "=== ① 装模块（su 域；先删旧目录）==="
# 旧目录残留会让 ksud module install 报 "i/o error: No such file or directory"。
su_t "rm -rf /data/adb/modules/zygisk_vector /data/adb/modules/zygisksu"
for z in zygisknext.zip vector.zip; do
  echo "  安装 $z"
  su_t "$KSU_KSUD module install $DEV_MODULES/$z"
done

echo "=== 检查 disable 标记（坑 3）==="
for m in zygisksu zygisk_vector; do
  if su_t "test -f /data/adb/modules/$m/disable" 2>/dev/null | grep -q .; then
    echo "  移除 $m 的 disable 标记"
    su_t "rm -f /data/adb/modules/$m/disable"
  fi
done

echo "=== ② late-load（apply 模块 + 跑 post-fs-data）==="
su_t "$KSU_KSUD late-load --kmi $KSU_KMI --allow-shell"
sleep 8

echo "=== ③ 重启 zygote（让 ZygiskNext 注入新 zygote）==="
# 模块是在 zygote 启动那一刻被 preload 的；不重启 zygote 就永远注不进。
OLD_ZYGOTE=$(sh_ "pidof zygote64" 2>/dev/null | tr -d '\r' | awk '{print $1}')
if [ -n "$OLD_ZYGOTE" ]; then
  echo "  杀 zygote64 pid=$OLD_ZYGOTE（init 会重启它，webview_zygote 是它的子进程）"
  su_ "kill -9 $OLD_ZYGOTE"
else
  echo "  zygote64 不在？直接继续"
fi

echo "  等 zygote 挂上（最多 90s）==="
ZGONE=0
for i in $(seq 1 90); do
  OUT=$(su_t "$ZNCTL status" 2>/dev/null | tr -d '\r')
  if echo "$OUT" | grep -q 'zygote_states:1'; then
    ZGONE=1
    echo "  zygote 已挂上（${i}s）"
    break
  fi
  sleep 1
done
if [ "$ZGONE" != "1" ]; then
  echo "!! zygote 没挂上，**不**起 Vector daemon（起了会进 recovery）" >&2
  echo "   查 ksud post-fs-data 的日志与 znctl status" >&2
  exit 1
fi
# Vector 的 zygisk 模块也要在模块列表里（modules64:1,zygisk_vector）
MODS=$(su_t "$ZNCTL status" 2>/dev/null | tr -d '\r' | grep '^modules64:' | head -1)
echo "  $MODS"
if ! echo "$MODS" | grep -q 'zygisk_vector'; then
  echo "!! zygisk 模块没进列表（$MODS），Vector daemon 起不来" >&2
  exit 1
fi

echo "=== ④ 等 60 秒让注入稳定 ==="
# 少了这 60 秒：Vector daemon 会重启 system_server，framework 起来一半挂掉 →
# rescue party → recovery（我们 3 次里 2 次栽在这）。
sleep 60

echo "=== ⑤ 起 Vector daemon（绝对路径，坑 1）==="
# 必须绝对路径：daemon 脚本用 ${0%/*} 定位自己，相对路径会让
# -Djava.class.path=daemon.apk 变成相对路径 → UnsatisfiedLinkError → 静默退出。
su_t "unshare -m $VECTOR_DIR/daemon --system-server-max-retry=3 >/data/local/tmp/vectord.log 2>&1 &"
# toybox 的 unshare 不支持 --propagation slave（Vector 自带 service.sh 用的就是它），
# 退化成 -m 即可（坑 2）。
sleep 20

echo "=== 验证 ==="
su_t "$VECTOR_DIR/cli status" || true
su_t "$ZNCTL status" | head -12 || true

cat <<'EOF'

完成。Vector 管理器里应该能看到 ZygiskNext / Vector，
装上你自己的模块后启用并选作用域即可。
EOF
