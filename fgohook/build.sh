#!/usr/bin/env bash
# Builds libfgohook.so for the ABIs FGO ships.
#
# DobbyHook is imported at runtime from the donor's libavalon.so (which links
# Dobby statically and exports DobbyHook), so there is no hook library to build.
#
# Requirements:
#   NDK - Android NDK r27c (override with NDK=/path/to/ndk)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${NDK:-/tmp/ndk/android-ndk-r27c}"
OUT="$HERE/out"

for abi in arm64-v8a armeabi-v7a; do
    case "$abi" in
        arm64-v8a)   target=aarch64-linux-android21 ;;
        armeabi-v7a) target=armv7a-linux-androideabi21 ;;
        *) continue ;;
    esac

    mkdir -p "$OUT/$abi"
    "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/${target}-clang++" \
        -shared -fPIC -O2 -std=c++17 -fvisibility=hidden -static-libstdc++ \
        "$HERE/fgohook.cpp" \
        -llog -ldl \
        -o "$OUT/$abi/libfgohook.so"
    echo "built $OUT/$abi/libfgohook.so"
done
