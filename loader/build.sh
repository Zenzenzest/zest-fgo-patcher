#!/usr/bin/env bash
# Regenerates app/src/main/assets/fgo/loader.mpe from libLoader.java.
#
# The .mpe is a raw DEX merged into the patched APK by the Morphe patcher, so it
# is checked in. Run this after editing the loader.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
JAVAC="$JAVA_HOME/bin/javac"

BUILD_TOOLS="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1)"
D8="${BUILD_TOOLS%/}/d8"
ANDROID_JAR="${PLATFORM%/}/android.jar"
OUT="$ROOT/app/src/main/assets/fgo/loader.mpe"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes" "$tmp/dex"

"$JAVAC" -source 8 -target 8 -classpath "$ANDROID_JAR" \
    -d "$tmp/classes" "$HERE/src/com/loadLib/libLoader.java"
"$D8" --min-api 21 --lib "$ANDROID_JAR" \
    --output "$tmp/dex" "$tmp/classes/com/loadLib/"*.class
cp "$tmp/dex/classes.dex" "$OUT"

echo "wrote $OUT"
