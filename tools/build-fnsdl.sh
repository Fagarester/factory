#!/bin/sh
# Builds out/libfnsdl.so: SDL3 dynamic API entry + GL layer.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
SDL="$ROOT/third_party/SDL3"
mkdir -p "$ROOT/out"
$CC -O2 -g -Wall -shared -fPIC -fvisibility=hidden -Wl,-z,max-page-size=16384 -I"$SDL/include" -I"$SDL/src" \
  -o "$ROOT/out/libfnsdl.so" "$ROOT/fnsdl/fnsdl.c" "$ROOT/fnsdl/fngl.c" "$ROOT/fnsdl/fngl_tex.c" "$ROOT/fnsdl/tex/etc2.c" "$ROOT/fnsdl/tex/cache.c" "$ROOT/fnsdl/fn_upscale.c" -ldl -lGLESv3 -lz
echo "built $ROOT/out/libfnsdl.so"
