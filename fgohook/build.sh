#!/usr/bin/env bash
# Builds libfgohook.so for the ABIs FGO ships.
#
# Requires the Android NDK (override with NDK=/path/to/ndk).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${NDK:-/tmp/ndk/android-ndk-r27c}"
OUT="$HERE/out"

for abi in arm64-v8a armeabi-v7a; do
    build="$HERE/build-$abi"
    cmake -S "$HERE" -B "$build" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-21 \
        -DCMAKE_BUILD_TYPE=Release >/dev/null
    cmake --build "$build" -j"$(nproc)"

    mkdir -p "$OUT/$abi"
    cp "$build/libfgohook.so" "$OUT/$abi/libfgohook.so"
    echo "built $OUT/$abi/libfgohook.so"
done
