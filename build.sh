#!/data/data/com.termux/files/usr/bin/bash
set -e

# --- shared config ---------------------------------------------------
# Point this at an NDK that runs on-device. The official NDK's clang is
# x86_64-only and won't execute directly on an ARM64 Termux install --
# use an aarch64-native build like lzhiyong/termux-ndk, or Termux's own
# clang + the `ndk-sysroot` package if you go that route instead.
: "${NDK:?Set NDK to your Android NDK root, e.g. export NDK=$HOME/android-ndk-r26c}"
# Termux doesn't package android.jar (it's SDK-platform stuff, not NDK),
# so point this at one yourself -- e.g. a platforms/android-34/android.jar
# pulled off an SDK install, or a mirror like Sable/android-platforms.
: "${ANDROID_JAR:?Set ANDROID_JAR to an android.jar for API 34, e.g. export ANDROID_JAR=$HOME/android-34/android.jar}"
ABI=arm64-v8a
API=29
PACKAGE=com.IvyVNC
DEBUG_KEYSTORE_PASS=android

# Optional release-build knobs (all set by the GitHub Actions release workflow):
#   RELEASE=1                 -> force android:debuggable="false"
#   VERSION_NAME / VERSION_CODE -> stamped into the APK manifest
#   KEYSTORE_FILE, KEYSTORE_PASS, KEY_ALIAS, [KEY_PASS], [KEYSTORE_TYPE]
#                             -> sign with your release key instead of the debug one

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILDING="$ROOT/building"
DEPS="$ROOT/code/cfiles/deps"
APP_SRC="$ROOT/app"
STAGING="$BUILDING/app"
BUILT="$ROOT/built"

# first thing to do is cp everything to building
echo "== staging app/ =="
rm -rf "$STAGING"
mkdir -p "$STAGING"
cp "$APP_SRC/AndroidManifest.xml" "$STAGING/"
cp -r "$APP_SRC/res" "$STAGING/"
if [ "${RELEASE:-0}" = "1" ]; then
    sed -i 's/android:debuggable="true"/android:debuggable="false"/' "$STAGING/AndroidManifest.xml"
fi
mkdir -p "$STAGING/assets"
cp -rn "$APP_SRC/assets/." "$STAGING/assets/" 2>/dev/null || true
# lib/ is populated further down by the JNI-bridge compile step -- not
# here, since that step needs the freshly built libvncclient.a first.

# second thing is to take the java file and compile it into dex
# (this actually happens near the bottom, after libivyvnc.so exists --
# the manifest packaging step needs that .so already sitting in
# $STAGING/lib/$ABI/ so aapt2/zip can find it)

# third thing is do the c compiling stuff
echo "== building libvncclient =="
# Always wipe this first: CMake caches CMAKE_SYSTEM_NAME and friends in
# CMakeCache.txt, so re-running with different -D flags on a stale
# build dir silently keeps the OLD configuration instead of applying
# the new one. Cheap to redo the configure step, expensive to debug
# why your flags are being ignored.
rm -rf "$BUILDING/obj/libvncclient"
mkdir -p "$BUILDING/obj"

# Using CMake's own built-in Android cross-compiling support instead of
# the NDK's build/cmake/android.toolchain.cmake wrapper -- that wrapper
# isn't present in every NDK build (e.g. aarch64-native rebuilds often
# ship just the LLVM toolchain + sysroot, not Google's helper scripts).
# CMAKE_SYSTEM_NAME=Android has been built into CMake since 3.7 and
# auto-detects the compiler under toolchains/llvm/prebuilt/<host-tag>/
# regardless of what that host tag actually is.
cmake -S "$DEPS/libvncclient" -B "$BUILDING/obj/libvncclient" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_SYSTEM_NAME=Android \
    -DCMAKE_SYSTEM_VERSION="$API" \
    -DCMAKE_ANDROID_ARCH_ABI="$ABI" \
    -DCMAKE_ANDROID_NDK="$NDK" \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DLIBVNCSERVER_INSTALL=OFF \
    -DWITH_THREADS=ON \
    -DWITH_ZLIB=ON \
    -DWITH_LZO=OFF \
    -DWITH_JPEG=OFF \
    -DWITH_PNG=OFF \
    -DWITH_SDL=OFF \
    -DWITH_GTK=OFF \
    -DWITH_QT=OFF \
    -DWITH_LIBSSHTUNNEL=OFF \
    -DWITH_GNUTLS=OFF \
    -DWITH_OPENSSL=OFF \
    -DWITH_GCRYPT=OFF \
    -DWITH_SYSTEMD=OFF \
    -DWITH_FFMPEG=OFF \
    -DWITH_WEBSOCKETS=OFF \
    -DWITH_SASL=OFF \
    -DWITH_XCB=OFF \
    -DWITH_EXAMPLES=OFF \
    -DWITH_TESTS=OFF

# only builds the `vncclient` target and its deps -- skips vncserver
# entirely, we don't need it and it would just cost build time
cmake --build "$BUILDING/obj/libvncclient" --target vncclient -j"$(nproc)"

VNCCLIENT_A="$BUILDING/obj/libvncclient/libvncclient.a"
if [ ! -f "$VNCCLIENT_A" ]; then
    echo "expected $VNCCLIENT_A but it's not there -- CMake output layout may have changed upstream"
    exit 1
fi

echo "== compiling our JNI bridge + linking libivyvnc.so =="
mkdir -p "$BUILDING/app/lib/$ABI"

# The folder is named linux-x86_64 regardless of host arch -- these
# Termux NDK rebuilds keep that name for compatibility with tooling
# that hardcodes it, even though the binaries inside are aarch64-native.
# Using the API-level wrapper script (aarch64-linux-android29-clang)
# instead of raw clang + --target=, since the wrapper already bakes in
# the right target/sysroot flags for us.
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
if [ ! -x "$CC" ]; then
    echo "Couldn't find $CC"
    echo "Run: ls \$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/ | grep clang"
    echo "and tell me what it prints so I can fix the CC= path."
    exit 1
fi

"$CC" \
    -shared -fPIC \
    -I "$DEPS/libvncclient/include" \
    -I "$BUILDING/obj/libvncclient/include" \
    "$ROOT/code/cfiles/jni_bridge.c" \
    "$ROOT/code/cfiles/vnc_client.c" \
    "$VNCCLIENT_A" \
    -llog -landroid -lz \
    -o "$BUILDING/app/lib/$ABI/libivyvnc.so"

echo "built: $BUILDING/app/lib/$ABI/libivyvnc.so"

# --- tool checks -------------------------------------------------------
# All of these come from Termux packages: `pkg install aapt2 aapt
# openjdk-17 d8 apksigner`. The apksigner Termux ships now is the real
# Android SDK build-tools apksigner (Java, subcommand-based: `apksigner
# sign --ks ... apk`), not fornwall's old single-purpose rewrite -- it
# does NOT create a keystore or zipalign for you, so both of those are
# handled explicitly below. zipalign comes from the `aapt` package (not
# aapt2); keytool comes from openjdk-17, already required for javac.
for tool in aapt2 zipalign javac keytool d8 apksigner zip; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Couldn't find '$tool' on PATH."
        echo "Run: pkg install aapt2 aapt openjdk-17 d8 apksigner zip"
        exit 1
    fi
done

# fourth thing is to package everything in app
echo "== compiling resources (aapt2 compile) =="
mkdir -p "$BUILDING/gen" "$BUILDING/classes" "$BUILDING/dexout"
aapt2 compile --dir "$STAGING/res" -o "$BUILDING/compiled_res.zip"

echo "== linking resources + manifest (aapt2 link) =="
# Produces the unsigned base APK (manifest + resources.arsc + res/ + assets/)
# and generates R.java for the java compile step below.
aapt2 link \
    -o "$BUILDING/unsigned-base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$STAGING/AndroidManifest.xml" \
    -A "$STAGING/assets" \
    --java "$BUILDING/gen" \
    --min-sdk-version "$API" \
    --target-sdk-version 34 \
    ${VERSION_CODE:+--version-code "$VERSION_CODE"} \
    ${VERSION_NAME:+--version-name "$VERSION_NAME"} \
    "$BUILDING/compiled_res.zip"

echo "== compiling java (javac) =="
# -source/-target 8: d8 wants class files it recognizes, and pinning to
# 8 sidesteps surprises from whatever javac version Termux ships.
javac \
    -source 8 -target 8 -nowarn \
    -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
    -d "$BUILDING/classes" \
    "$ROOT/code/MainActivity.java" \
    "$BUILDING/gen/${PACKAGE//.//}/R.java"

echo "== dexing (d8) =="
d8 \
    --output "$BUILDING/dexout" \
    --lib "$ANDROID_JAR" \
    --min-api "$API" \
    $(find "$BUILDING/classes" -name '*.class')

echo "== assembling unsigned apk =="
cp "$BUILDING/unsigned-base.apk" "$STAGING/unsigned.apk"
cp "$BUILDING/dexout/classes.dex" "$STAGING/classes.dex"
(
    cd "$STAGING"
    zip -q unsigned.apk classes.dex
    zip -qr unsigned.apk "lib/$ABI/libivyvnc.so"
)

echo "== zipaligning (zipalign) =="
# The real apksigner (unlike fornwall's) doesn't zipalign for you --
# has to happen before signing, or the APK ships with unaligned zip
# entries. -p also page-aligns the .so we bundle to a 4096-byte
# boundary, which newer Android wants for 16KB-page-size devices.
zipalign -f -p 4 "$STAGING/unsigned.apk" "$STAGING/aligned.apk"

echo "== signing (apksigner) =="
mkdir -p "$ROOT/code/keystore" "$BUILT"
# Debug keystore lives under code/keystore/ so it persists across builds
# instead of getting regenerated (and re-prompting) every run. Unlike
# fornwall's old tool, the real apksigner won't create one on first use,
# so we do that ourselves with keytool if it's not there yet.
if [ -n "${KEYSTORE_FILE:-}" ]; then
    # Release signing (CI): key comes from the environment, never the repo.
    : "${KEYSTORE_PASS:?KEYSTORE_FILE is set but KEYSTORE_PASS is not}"
    : "${KEY_ALIAS:?KEYSTORE_FILE is set but KEY_ALIAS is not}"
    KEYSTORE="$KEYSTORE_FILE"
    STORE_PASS="$KEYSTORE_PASS"
    KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"   # PKCS12 usually uses one password
    KS_TYPE_ARGS=(--ks-type "${KEYSTORE_TYPE:-PKCS12}")
    echo "signing with release key ($KEYSTORE, alias $KEY_ALIAS)"
else
    KEYSTORE="$ROOT/code/keystore/debug.keystore"
    KEY_ALIAS=androiddebugkey
    STORE_PASS="$DEBUG_KEYSTORE_PASS"
    KEY_PASS="$DEBUG_KEYSTORE_PASS"
    KS_TYPE_ARGS=()
    if [ ! -f "$KEYSTORE" ]; then
        echo "no debug keystore yet, generating one at $KEYSTORE"
        keytool -genkeypair -v \
            -keystore "$KEYSTORE" \
            -storepass "$STORE_PASS" \
            -keypass "$KEY_PASS" \
            -alias "$KEY_ALIAS" \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=IvyVNC Debug,O=Android,C=US"
    fi
fi

apksigner sign \
    --ks "$KEYSTORE" \
    "${KS_TYPE_ARGS[@]}" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --out "$BUILT/IvyVNC.apk" \
    "$STAGING/aligned.apk"

# fifth is to move the built apk to built/
# (apksigner already wrote straight to $BUILT above, nothing left to move)
echo "built: $BUILT/IvyVNC.apk"
