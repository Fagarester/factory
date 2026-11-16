#!/bin/sh
# Builds the bionic loader executable: out/fnative
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-30}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
mkdir -p "$ROOT/out"
SRC="elf.c tls.c tls_slow.S shim.c ucontext.S ucontext_c.c main.c"
cd "$ROOT/loader"
$CC -O2 -g -Wall -Wextra -Wno-unused-parameter -fno-stack-protector -o "$ROOT/out/fnative" $SRC -ldl -lm
echo "built $ROOT/out/fnative"
