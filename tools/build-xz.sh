#!/bin/sh
# Builds out/libfnxz.so: liblzma 5.8.4 (0BSD, static) + the JNI decoder used to unpack the game archive.
# The source is downloaded once into work/ and checked by its SHA-256 (as the Factory app's CMake does).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
V=5.8.4
SHA=0014c7886930454fe8bd4228665b51af55eeae560ea135c9c4cd33f55b2591d9
SRC="$ROOT/work/xz-$V"
BUILD="$ROOT/work/xz-build"
if [ ! -d "$SRC" ]; then
  mkdir -p "$ROOT/work"
  T="$ROOT/work/xz-$V.tar.gz"
  [ -f "$T" ] || curl -fL -o "$T" "https://github.com/tukaani-project/xz/releases/download/v$V/xz-$V.tar.gz"
  echo "$SHA  $T" | sha256sum -c -
  tar -xzf "$T" -C "$ROOT/work"
fi
if [ ! -f "$BUILD/liblzma.a" ]; then
  cmake -S "$SRC" -B "$BUILD" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF -DXZ_NLS=OFF -DXZ_TOOL_XZ=OFF -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF \
    -DXZ_TOOL_LZMAINFO=OFF -DXZ_TOOL_SCRIPTS=OFF -DXZ_DOXYGEN=OFF -DCMAKE_C_FLAGS="-O2 -fPIC" >/dev/null
  cmake --build "$BUILD" --target liblzma -j4 >/dev/null
fi
mkdir -p "$ROOT/out"
$CC -O2 -Wall -shared -fPIC -Wl,-z,max-page-size=16384 -I"$SRC/src/liblzma/api" \
  -o "$ROOT/out/libfnxz.so" "$ROOT/fnxz/xz_jni.c" "$BUILD/liblzma.a"
echo "built $ROOT/out/libfnxz.so"
