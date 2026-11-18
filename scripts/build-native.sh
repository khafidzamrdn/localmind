#!/data/data/com.termux/files/usr/bin/bash
# Builds the llama.cpp JNI bridge for arm64 Android using Termux's clang.
#
# The official Android NDK is not usable inside Termux (its toolchain is
# x86_64-hosted), so the shared library is produced with the Termux clang
# driver, which already targets aarch64-unknown-linux-android24. The resulting
# .so files are committed under app/src/main/jniLibs/arm64-v8a so a normal
# Gradle build does not need the llama.cpp sources.
set -euo pipefail

LLAMA_SRC="${LLAMA_SRC:-$HOME/llama-src/llama.cpp-1.0.0}"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-$PROJECT_DIR/build-native}"
JOBS="${JOBS:-2}"
JNI_LIBS="$PROJECT_DIR/app/src/main/jniLibs/arm64-v8a"

if [ ! -f "$LLAMA_SRC/include/llama.h" ]; then
    echo "llama.cpp source not found at $LLAMA_SRC" >&2
    echo "Set LLAMA_SRC=/path/to/llama.cpp (tag v1.0.0) and retry." >&2
    exit 1
fi

cmake -S "$PROJECT_DIR/app/src/main/cpp" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang \
    -DCMAKE_CXX_COMPILER=clang++ \
    -DLLAMA_SRC="$LLAMA_SRC"

cmake --build "$BUILD_DIR" --target localmind_llama -j "$JOBS"

mkdir -p "$JNI_LIBS"
cp "$BUILD_DIR/liblocalmind_llama.so" "$JNI_LIBS/liblocalmind_llama.so"
# Termux has no static libc++ for this target; the shared runtime must ship
# alongside the library. It only depends on libc, libm and libdl.
cp "$PREFIX/lib/libc++_shared.so" "$JNI_LIBS/libc++_shared.so"

ls -lh "$JNI_LIBS"
