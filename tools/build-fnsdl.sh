#!/bin/sh
# Builds out/libfnsdl.so: SDL3 dynamic API entry + GL layer + Vulkan renderer (fnsdl/vk, glslang from the NDK).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang"
SDL="$ROOT/third_party/SDL3"
G="$NDK/sources/third_party/shaderc/third_party/glslang"
O="$ROOT/work/obj"
mkdir -p "$ROOT/out" "$O"
[ -f "$ROOT/fnsdl/fngl_stubs.h" ] || "$ROOT/tools/gen-fngl-stubs.sh"
[ -f "$ROOT/work/glslang/libglslang.a" ] || "$ROOT/tools/build-glslang.sh"
OBJS=
for f in fnsdl.c fngl.c fngl_tex.c tex/etc2.c tex/cache.c fn_upscale.c fngl_prof.c fngl_prof.S vk/vk_core.c vk/vk_gl.c; do
  o="$O/$(echo "$f" | tr / _).o"
  $CC -c -O2 -g -Wall -fPIC -fvisibility=hidden -I"$SDL/include" -I"$SDL/src" -o "$o" "$ROOT/fnsdl/$f" &
  OBJS="$OBJS $o"
done
"${CC}++" -c -O2 -g -Wall -fPIC -fvisibility=hidden -std=c++17 -I"$G" -I"$ROOT/work/glslang/include" \
  -o "$O/vk_shader.o" "$ROOT/fnsdl/vk/vk_shader.cpp" &
wait
"${CC}++" -shared -Wl,-z,max-page-size=16384 -Wl,--no-undefined -o "$ROOT/out/libfnsdl.so" $OBJS "$O/vk_shader.o" \
  "$ROOT/work/glslang/libglslang.a" -static-libstdc++ -ldl -lGLESv3 -lvulkan -lz
echo "built $ROOT/out/libfnsdl.so"
