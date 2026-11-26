#!/bin/sh
# Builds out/libtexcompress.so: the PNG texture shrinker (palette PNG with fewer colors, ×2 / ×5) ported from the
# Factory app (fntex/, the user's own code), for "Производительность" → "Сжатие текстур игры". zlib from the NDK.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
mkdir -p "$ROOT/out"
$CC -O2 -Wall -shared -fPIC -s -Wl,-z,max-page-size=16384 -o "$ROOT/out/libtexcompress.so" \
  "$ROOT/fntex/texcompress.c" "$ROOT/fntex/texcompress_jni.c" -lz
echo "built $ROOT/out/libtexcompress.so"
