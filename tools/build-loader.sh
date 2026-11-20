#!/bin/sh
# Builds the loader: out/fnative (test executable) and out/libfnative.so (APK).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
mkdir -p "$ROOT/out"
SRC="elf.c tls.c tls_slow.S shim.c ucontext.S ucontext_c.c run.c stats.c quickbar.c vibrate.c inputmethod.c alloc.c"
FLAGS="-O2 -g -Wall -Wextra -Wno-unused-parameter -fno-stack-protector"
# mimalloc (third_party/mimalloc, one unit, no global malloc override: only the game's imports, loader/alloc.c)
$CC -O3 -g -fPIC -fvisibility=hidden -DNDEBUG -DMI_DEBUG=0 -I"$ROOT/third_party/mimalloc/include" \
  -c -o "$ROOT/out/mimalloc.o" "$ROOT/third_party/mimalloc/src/static.c"
cd "$ROOT/loader"
$CC $FLAGS -o "$ROOT/out/fnative" $SRC "$ROOT/out/mimalloc.o" main.c -ldl -lm -llog
$CC $FLAGS -shared -fPIC -fvisibility=hidden -Wl,-z,max-page-size=16384 -o "$ROOT/out/libfnative.so" $SRC "$ROOT/out/mimalloc.o" android_main.c -ldl -lm -llog
echo "built $ROOT/out/fnative $ROOT/out/libfnative.so"
