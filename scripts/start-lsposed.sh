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
# 本脚本的命令序列与 docs/titan2-ondevice-flow.md 里**真机验证过**的流程逐条一致：
#   ① KernelSU 进内存（利用的副产物）→ shell 用户拿到 su
#   ② su 域：先删旧模块目录，再逐个装模块（用已安装那份 /data/adb/ksu/bin/ksud）
#   ③ su 域：再 late-load 一次（apply 模块 + 跑 stage 脚本）
#   ④ su 域：起 ZygiskNext 守护进程 → 它把注入点挂进 zygote
#   ⑤ 等 zygote 真的挂上（znctl status → zygote_states:1）
#   ⑥ 再等 60 秒让注入稳定，然后才起 Vector daemon
#      （它靠重启 system_server 注入服务；注入没稳就重启 → framework 起不来
#        → rescue party → 进 recovery。recovery 里 adbd 关着，只能长按电源键。）
#
# 顺序错一步就会掉进 recovery，**不要改这个顺序**。
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

sh_() { "$ADB" shell "$@"; }
# su 域执行：模块安装必须走 su（root 脚本所在域写不了 /data/adb/modules_update）。
su_() { "$ADB" shell "su -c '$*'"; }

echo "=== 前置检查 ==="
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

echo "=== ② 装模块（su 域；先删旧目录）==="
# 旧目录残留会让 ksud module install 报 "i/o error: No such file or directory"。
su_ "rm -rf /data/adb/modules/zygisk_vector /data/adb/modules/zygisksu"
for z in zygisknext.zip vector.zip; do
  echo "  安装 $z"
  su_ "$KSU_KSUD module install $DEV_MODULES/$z"
done

echo "=== ③ 再 late-load 一次（apply 模块 + 跑 stage）==="
su_ "$KSU_KSUD late-load --kmi android14-6.1 --allow-shell"

echo "=== ④ 起 ZygiskNext 守护进程 ==="
su_ "nohup $KSU_KSUD post-fs-data >/data/local/tmp/pfsd.log 2>&1 &"

echo "=== ⑤ 等 zygote 挂上（最多 60s）==="
ZGONE=0
for i in $(seq 1 60); do
  if su_ "$ZNCTL status" 2>/dev/null | grep -q 'zygote_states:1'; then
    ZGONE=1
    echo "  zygote 已挂上（${i}s）"
    break
  fi
  sleep 1
done
if [ "$ZGONE" != "1" ]; then
  echo "!! zygote 没挂上，**不**起 Vector daemon（起了会进 recovery）" >&2
  echo "   查 $DEV_MODULES/pfsd.log 与 ksud 日志" >&2
  exit 1
fi

echo "=== ⑥ 等 60 秒让注入稳定 ==="
# 少了这 60 秒：Vector daemon 会重启 system_server，framework 起来一半挂掉 →
# rescue party → recovery（我们 3 次里 2 次栽在这）。
sleep 60

echo "=== 起 Vector daemon（它会重启一次 system_server）==="
su_ "cd /data/adb/modules/zygisk_vector && (./daemon --system-server-max-retry=3 >/data/local/tmp/vectord.log 2>&1 &)"
sleep 8

echo "=== 验证 ==="
su_ "/data/adb/modules/zygisk_vector/cli status" || true
su_ "$ZNCTL status" || true

cat <<'EOF'

完成。Vector 管理器里应该能看到 ZygiskNext / Vector，
装上你自己的模块后启用并选作用域即可。
EOF
