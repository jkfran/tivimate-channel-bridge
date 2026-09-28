#!/usr/bin/env bash
# Build a signed debug APK for the TiviMate Channel Bridge accessibility service
# using only the Android SDK command-line build-tools (no Gradle).
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
API=34
BT_VER="${BT_VER:-34.0.0}"
BT="$SDK/build-tools/$BT_VER"
ANDROID_JAR="$SDK/platforms/android-$API/android.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home}"
PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")"
SRC=app/src/main
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 21 --target-sdk-version "$API" \
  --version-code 1 --version-name 1.0

echo "[3/6] javac"
find "$SRC/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -d "$OUT/classes" -classpath "$ANDROID_JAR" \
  -source 17 -target 17 @"$OUT/sources.txt"

echo "[4/6] d8 -> dex"
CLASSES=$(find "$OUT/classes" -name '*.class')
"$BT/d8" --lib "$ANDROID_JAR" --min-api 21 --output "$OUT/dex" $CLASSES

echo "[5/6] add dex + align"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
KS="$OUT/debug.keystore"
if [ ! -f keystore/debug.keystore ]; then
  mkdir -p keystore
  keytool -genkeypair -keystore keystore/debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TiviMate Bridge Debug,O=jkfran,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks keystore/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/tivimate-channel-bridge.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/tivimate-channel-bridge.apk" && echo "OK"
echo "APK: $OUT/tivimate-channel-bridge.apk"
