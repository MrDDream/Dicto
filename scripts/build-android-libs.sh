#!/usr/bin/env bash
# Compile le cœur Rust (avec whisper.cpp) pour Android et génère les classes Kotlin.
#
#   scripts/build-android-libs.sh            # arm64-v8a + x86_64, release
#   ABIS="arm64-v8a" scripts/build-android-libs.sh
#   PROFILE=debug scripts/build-android-libs.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/env.sh"

ABIS="${ABIS:-arm64-v8a x86_64}"
PROFILE="${PROFILE:-release}"
FEATURES="${FEATURES:-whisper,sherpa}"
MIN_SDK=29
APP="$ROOT/android/app"

cd "$ROOT/core"

targets=()
for abi in $ABIS; do targets+=(-t "$abi"); done
release_flag=()
[[ "$PROFILE" == "release" ]] && release_flag=(--release)

echo "==> libdicto_ffi.so pour : $ABIS ($PROFILE, features : $FEATURES)"
cargo ndk "${targets[@]}" -P "$MIN_SDK" -o "$APP/src/main/jniLibs" \
    build "${release_flag[@]}" -p dicto-ffi --features "$FEATURES"

echo "==> libc++_shared.so (whisper.cpp est en C++)"
SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
for abi in $ABIS; do
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android ;;
        armeabi-v7a) triple=arm-linux-androideabi ;;
        x86_64) triple=x86_64-linux-android ;;
        x86) triple=i686-linux-android ;;
    esac
    cp "$SYSROOT/$triple/libc++_shared.so" "$APP/src/main/jniLibs/$abi/"
done

if [[ "$FEATURES" == *sherpa* ]]; then
    echo "==> sherpa-onnx et onnxruntime (versions officielles précompilées)"
    for abi in $ABIS; do
        case "$abi" in
            arm64-v8a) triple=aarch64-linux-android ;;
            armeabi-v7a) triple=armv7-linux-androideabi ;;
            x86_64) triple=x86_64-linux-android ;;
            x86) triple=i686-linux-android ;;
        esac
        for lib in libsherpa-onnx-c-api.so libonnxruntime.so; do
            # sherpa-onnx-sys copie les bibliothèques à côté de la nôtre.
            src="target/$triple/$PROFILE/$lib"
            [[ -f "$src" ]] || { echo "introuvable : $src" >&2; exit 1; }
            cp "$src" "$APP/src/main/jniLibs/$abi/"
        done
    done
fi

echo "==> Classes Kotlin (UniFFI)"
# Dossier de compilation séparé : ne pas écraser la bibliothèque de la machine utilisée par les tests.
cargo build -q -p dicto-ffi --features cli --target-dir target/bindgen
./target/bindgen/debug/uniffi-bindgen generate \
    --library target/bindgen/debug/libdicto_ffi.so \
    --language kotlin --no-format \
    --out-dir "$APP/src/main/java"

du -h "$APP"/src/main/jniLibs/*/libdicto_ffi.so
