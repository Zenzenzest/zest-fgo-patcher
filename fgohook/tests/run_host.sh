#!/usr/bin/env bash
# Host tests for the pure native helpers (no Android toolchain needed).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${TMPDIR:-/tmp}/fgohook-host-tests"

mkdir -p "$OUT"

c++ -std=c++17 -Wall -Wextra -I"$HERE/../include" \
    "$HERE/host/test_localization.cpp" -o "$OUT/test_localization"
"$OUT/test_localization"
