#!/bin/sh
# Builds the loader: out/fnative (test executable) and out/libfnative.so (APK).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
mkdir -p "$ROOT/out"
SRC="elf.c tls.c tls_slow.S shim.c ucontext.S ucontext_c.c run.c stats.c quickbar.c vibrate.c inputmethod.c"
FLAGS="-O2 -g -Wall -Wextra -Wno-unused-parameter -fno-stack-protector"
cd "$ROOT/loader"
$CC $FLAGS -o "$ROOT/out/fnative" $SRC main.c -ldl -lm -llog
$CC $FLAGS -shared -fPIC -fvisibility=hidden -Wl,-z,max-page-size=16384 -o "$ROOT/out/libfnative.so" $SRC android_main.c -ldl -lm -llog
echo "built $ROOT/out/fnative $ROOT/out/libfnative.so"
