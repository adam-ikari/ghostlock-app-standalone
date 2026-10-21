#!/usr/bin/env bash
# 手工构建最小 Xposed 模块 APK（新版 libxposed API），不走 gradle。
#
#   ./build.sh            → build/xposed-probe.apk
#
# 结构：
#   AndroidManifest.xml                 模块声明（xposedmodule meta-data + Launcher activity）
#   assets/META-INF/xposed/java_init.list   新版模块入口类
#   assets/META-INF/xposed/scope.list       静态作用域
#   assets/META-INF/xposed/module.prop      模块元信息
#   java/                                 app 代码（用 io.github.libxposed.api）
#   stubs/                                传统 Xposed API 桩（保留参考，不进 APK）
#
# 依赖：libxposed API 102（io.github.libxposed:api:102.0.0，Maven Central）
#   API_JAR=/path/to/api-102.0.0.aar
set -euo pipefail

HERE=$(cd -- "$(dirname -- "$0")" && pwd)
SDK=${ANDROID_HOME:-/home/gem/android-sdk}
BT=${BUILD_TOOLS:-$SDK/build-tools/36.0.0}
AJ=$SDK/platforms/android-36/android.jar
OUT=$HERE/build
API_JAR=${API_JAR:-$HERE/lib/libxposed-api-102.aar}
ANNOT_JAR=${ANNOT_JAR:-$HOME/.gradle/caches/modules-2/files-2.1/androidx.annotation/annotation-jvm/1.9.1/b17951747e38bf3986a24431b9ba0d039958aa5f/annotation-jvm-1.9.1.jar}

[ -f "$API_JAR" ] || { echo "!! 找不到 libxposed API：$API_JAR" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/appclasses" "$OUT/api"

echo "== 1/6 解 API aar =="
(cd "$OUT/api" && unzip -q "$API_JAR" classes.jar)

echo "== 2/6 javac（app + API 桩）=="
CP="$AJ:$OUT/api/classes.jar:$ANNOT_JAR"
javac -nowarn --release 17 -cp "$CP" -d "$OUT/classes" \
    $(find "$HERE/stubs" "$HERE/java" -name '*.java')

echo "== 3/6 d8（只打 app 的类；API 由框架提供，不进 APK）=="
cp -r "$OUT/classes/com" "$OUT/appclasses/"
"$BT/d8" --lib "$AJ" --min-api 31 --output "$OUT/dex" \
    $(find "$OUT/appclasses" -name '*.class')

echo "== 4/6 aapt2 link =="
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" \
    --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version 31 --target-sdk-version 36 \
    --version-code 1 --version-name 1.0

echo "== 5/6 塞 dex 和模块元信息 =="
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
mkdir -p "$OUT/assets/META-INF/xposed"
cp "$HERE/assets/META-INF/xposed/"* "$OUT/assets/META-INF/xposed/"
cp "$OUT/base.apk" "$OUT/staged.apk"
(cd "$OUT" && zip -q staged.apk classes.dex assets/META-INF/xposed/java_init.list \
    assets/META-INF/xposed/scope.list assets/META-INF/xposed/module.prop)

echo "== 6/6 对齐 + 签名 =="
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
unzip -l "$OUT/xposed-probe.apk" | grep -E "assets|classes.dex"
ls -l "$OUT/xposed-probe.apk"
