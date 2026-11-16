# Environnement de build Dicto. À sourcer : `source scripts/env.sh`
# Les outils Android sont dans ~/android-tools (surchargeable par variables d'environnement).

ANDROID_TOOLS="${ANDROID_TOOLS:-$HOME/android-tools}"
export JAVA_HOME="${JAVA_HOME:-$ANDROID_TOOLS/jdk21}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_TOOLS/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/29.0.14206865}"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"

CMAKE_DIR="$(ls -d "$ANDROID_HOME"/cmake/*/bin 2>/dev/null | sort -V | tail -1)"
export PATH="$JAVA_HOME/bin:$CMAKE_DIR:$ANDROID_HOME/platform-tools:$HOME/.cargo/bin:$PATH"

# bindgen (whisper-rs-sys) a besoin de libclang : celui du NDK suffit.
export LIBCLANG_PATH="${LIBCLANG_PATH:-$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/lib}"
