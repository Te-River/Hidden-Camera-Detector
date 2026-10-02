#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# ===== 0. 环境与工具解析（允许环境变量覆盖） =====
REPO="$(cd "$(dirname "$0")" && pwd)"
BUILD="$REPO/build"
OUT_APK="$BUILD/HiddenCameraDetector.apk"
AAPT2="${AAPT2:-$(command -v aapt2 || true)}"
D8="${D8:-$(command -v d8 || true)}"
APKSIGNER="${APKSIGNER:-$(command -v apksigner || true)}"
JAVAC="${JAVAC:-$(command -v javac || true)}"
KEYTOOL="${KEYTOOL:-$(command -v keytool || true)}"
CLANG="${CLANG:-$(command -v clang || true)}"
PYTHON="${PYTHON:-$(command -v python || true)}"
for t in AAPT2 D8 APKSIGNER JAVAC KEYTOOL CLANG PYTHON; do
  [ -n "${!t}" ] || { echo "缺少工具: $t（可用环境变量 $t=... 覆盖）"; exit 1; }
done
JAVA_HOME_DIR="$(dirname "$(dirname "$(readlink -f "$JAVAC")")")"   # jni.h 所在 JDK

mkdir -p "$BUILD"

# ===== 0b. android.jar 自动下载（仅首次；缓存于 build/ 不随清理重下） =====
ANDROID_JAR="$BUILD/android.jar"
if [ ! -f "$ANDROID_JAR" ]; then
  echo ">> 下载 android.jar (platform-36_r02)…"
  curl -fL --retry 3 -o "$BUILD/platform-36_r02.zip" \
    "https://dl.google.com/android/repository/platform-36_r02.zip"
  rm -rf "$BUILD/unpack"
  unzip -oq "$BUILD/platform-36_r02.zip" -d "$BUILD/unpack"
  ANDROID_JAR_SRC="$(find "$BUILD/unpack" -name android.jar | head -n1)"
  [ -n "$ANDROID_JAR_SRC" ] || { echo "解包后未找到 android.jar"; exit 1; }
  cp "$ANDROID_JAR_SRC" "$ANDROID_JAR"
fi

# 清理中间产物（保留 android.jar / platform zip / keystore）
rm -rf "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$BUILD/lib" "$BUILD/unpack" \
       "$BUILD/res.zip" "$BUILD/base.apk" "$BUILD/unsigned.apk"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$BUILD/lib/arm64-v8a"

# ===== 1. clang 编译 libirscan.so =====
"$CLANG" -shared -fPIC -O2 \
  --target=aarch64-unknown-linux-android24 \
  -Wl,-z,max-page-size=16384 \
  -I"$JAVA_HOME_DIR/include" -I"$JAVA_HOME_DIR/include/linux" \
  -o "$BUILD/lib/arm64-v8a/libirscan.so" \
  "$REPO/jni/irscan.c"

# ===== 2. aapt2 compile =====
"$AAPT2" compile --dir "$REPO/res" -o "$BUILD/res.zip"

# ===== 3. aapt2 link（base.apk + R.java） =====
"$AAPT2" link -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$REPO/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 --target-sdk-version 35 \
  --version-code 1 --version-name "1.0" \
  "$BUILD/res.zip"

# ===== 4. javac（--release 11） =====
find "$REPO/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" --release 11 -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

# ===== 5. d8（--min-api 26） =====
find "$BUILD/classes" -name '*.class' -print0 | xargs -0 "$D8" \
  --release --min-api 26 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex"

# ===== 6. python zipfile 注入 dex + so =====
"$PYTHON" - "$BUILD/base.apk" "$BUILD/dex/classes.dex" \
  "$BUILD/lib/arm64-v8a/libirscan.so" "$BUILD/unsigned.apk" <<'EOF'
import sys, zipfile
base, dex, so, out = sys.argv[1:5]
with zipfile.ZipFile(base) as zin, \
     zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():          # 保留原 compress_type（resources.arsc 保持 STORED）
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, 'classes.dex')
    zout.write(so, 'lib/arm64-v8a/libirscan.so')
EOF

# ===== 7. keytool 生成 keystore（不存在才生成，跨构建复用保签名一致） =====
KS="$BUILD/hcd.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias hcd -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass hcd2026 -keypass hcd2026 \
    -dname "CN=HiddenCameraDetector, OU=Dev, O=HCD, L=Beijing, ST=Beijing, C=CN"
fi

# ===== 8. apksigner 签名（V3，无需 zipalign） =====
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:hcd2026 --ks-key-alias hcd --key-pass pass:hcd2026 \
  --min-sdk-version 26 --out "$OUT_APK" "$BUILD/unsigned.apk"

# ===== 9. 打印产物路径与体积（不自动安装，由用户自行安装） =====
echo ">> 构建完成："
ls -l "$OUT_APK"
du -h "$OUT_APK"
echo ">> APK 路径：$OUT_APK"
echo ">> 构建流程不会自动安装，请自行安装该 APK。"
