#!/usr/bin/env bash
# 手工构建最小 Xposed 模块 APK（不走 gradle，只要 SDK build-tools + JDK）。
#
#   ./build.sh            → build/xposed-probe.apk
#
# 结构：
#   AndroidManifest.xml      模块声明（xposedmodule meta-data + Launcher activity）
#   assets/xposed_init       入口类
#   java/                    app 代码
#   stubs/                   编译期 Xposed API 桩（不进 APK）
set -euo pipefail

HERE=$(cd -- "$(dirname -- "$0")" && pwd)
SDK=${ANDROID_HOME:-/home/gem/android-sdk}
BT=${BUILD_TOOLS:-$SDK/build-tools/36.0.0}
AJ=$SDK/platforms/android-36/android.jar
OUT=$HERE/build

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/staging"

echo "== 1/5 javac（桩 + app）=="
# JDK 21 不允许 -bootclasspath 配 -target 17；用 --release 17 + android.jar 作 classpath
javac -nowarn --release 17 -cp "$AJ" -d "$OUT/classes" \
    $(find "$HERE/stubs" "$HERE/java" -name '*.java')

echo "== 2/5 d8（只打 app 的类，桩不进 APK）=="
mkdir -p "$OUT/appclasses"
cp -r "$OUT/classes/com" "$OUT/appclasses/"
"$BT/d8" --lib "$AJ" --min-api 31 --output "$OUT/dex" \
    $(find "$OUT/appclasses" -name '*.class')

echo "== 3/5 aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" \
    --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version 31 --target-sdk-version 36 \
    --version-code 1 --version-name 1.0

echo "== 4/5 塞 dex 和 assets =="
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
mkdir -p "$OUT/assets"
cp "$HERE/assets/xposed_init" "$OUT/assets/xposed_init"
cp "$OUT/base.apk" "$OUT/staged.apk"
(cd "$OUT" && zip -q staged.apk classes.dex assets/xposed_init)

echo "== 5/5 对齐 + 签名 =="
"$BT/zipalign" -f 4 "$OUT/staged.apk" "$OUT/aligned.apk"
KS="$OUT/probe.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias probe -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass android -keypass android \
    -dname "CN=Titan2 Xposed Probe" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/xposed-probe.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/xposed-probe.apk" && echo "签名校验通过"
ls -l "$OUT/xposed-probe.apk"
