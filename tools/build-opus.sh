#!/bin/sh
# Builds out/libfnopus.so: libopus 1.5.2 (BSD-3, static) + the JNI codec of the walkie-talkie (fnopus/opus_jni.c).
# The source is downloaded once into work/ and checked by its SHA-256 (downloads.xiph.org SHA256SUMS.txt).
# HOST=1: a glibc build for the host tests instead (work/opus-host/libfnopus.so, tests/net-src OpusTest).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
V=1.5.2
SHA=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
SRC="$ROOT/work/opus-$V"
if [ ! -d "$SRC" ]; then
  mkdir -p "$ROOT/work"
  T="$ROOT/work/opus-$V.tar.gz"
  [ -f "$T" ] || curl -fL -o "$T" "https://downloads.xiph.org/releases/opus/opus-$V.tar.gz"
  echo "$SHA  $T" | sha256sum -c -
  tar -xzf "$T" -C "$ROOT/work"
fi
OPTS="-DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DOPUS_BUILD_SHARED_LIBRARY=OFF -DOPUS_BUILD_TESTING=OFF
  -DOPUS_BUILD_PROGRAMS=OFF -DOPUS_INSTALL_PKG_CONFIG_MODULE=OFF -DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF
  -DOPUS_DRED=OFF -DOPUS_DEEP_PLC=OFF -DOPUS_OSCE=OFF"
if [ -n "$HOST" ]; then
  BUILD="$ROOT/work/opus-host"
  if [ ! -f "$BUILD/libopus.a" ]; then
    cmake -S "$SRC" -B "$BUILD" $OPTS -DCMAKE_C_FLAGS="-O2 -fPIC" >/dev/null
    cmake --build "$BUILD" -j4 >/dev/null
  fi
  JH=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}
  cc -O2 -Wall -shared -fPIC -I"$SRC/include" -I"$JH/include" -I"$JH/include/linux" \
    -o "$BUILD/libfnopus.so" "$ROOT/fnopus/opus_jni.c" "$BUILD/libopus.a" -lm
  echo "built $BUILD/libfnopus.so"
  exit 0
fi
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
BUILD="$ROOT/work/opus-build"
if [ ! -f "$BUILD/libopus.a" ]; then
  cmake -S "$SRC" -B "$BUILD" -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API $OPTS -DCMAKE_C_FLAGS="-O2 -fPIC" >/dev/null
  cmake --build "$BUILD" -j4 >/dev/null
fi
mkdir -p "$ROOT/out"
$CC -O2 -Wall -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL -s -I"$SRC/include" \
  -o "$ROOT/out/libfnopus.so" "$ROOT/fnopus/opus_jni.c" "$BUILD/libopus.a" -lm
echo "built $ROOT/out/libfnopus.so"
