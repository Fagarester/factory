#!/bin/sh
# Host (proot, bionic) tests of the Vulkan renderer pieces: out/vk-shadertest.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
G="$NDK/sources/third_party/shaderc/third_party/glslang"
[ -f "$ROOT/work/glslang/libglslang.a" ] || "$ROOT/tools/build-glslang.sh"
mkdir -p "$ROOT/out"
"$BIN/aarch64-linux-android$API-clang++" -O1 -g -std=c++17 -c -I"$G" -I"$ROOT/work/glslang/include" \
  -o "$ROOT/work/vk_shader.o" "$ROOT/fnsdl/vk/vk_shader.cpp"
"$BIN/aarch64-linux-android$API-clang" -O1 -g -o "$ROOT/out/vk-shadertest" "$ROOT/tests/vk/shadertest.c" \
  "$ROOT/work/vk_shader.o" "$ROOT/work/glslang/libglslang.a" -static-libstdc++ -lc++abi 2>/dev/null || \
"$BIN/aarch64-linux-android$API-clang++" -O1 -g -o "$ROOT/out/vk-shadertest" -x c "$ROOT/tests/vk/shadertest.c" -x none \
  "$ROOT/work/vk_shader.o" "$ROOT/work/glslang/libglslang.a" -static-libstdc++
echo "built $ROOT/out/vk-shadertest"
