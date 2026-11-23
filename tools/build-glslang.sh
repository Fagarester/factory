#!/bin/bash
# Builds work/glslang/libglslang.a (GLSL -> SPIR-V, no HLSL, no optimizer) from the glslang copy in the NDK.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
NDK=${NDK:-/root/android/sdk/ndk/29.0.14206865}
API=${API:-29}
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android$API-clang++"
G="$NDK/sources/third_party/shaderc/third_party/glslang"
OUT="$ROOT/work/glslang"
mkdir -p "$OUT/obj" "$OUT/include/glslang"
python3 -I "$G/build_info.py" "$G" -i "$G/build_info.h.tmpl" -o "$OUT/include/glslang/build_info.h"
SRC="glslang/OSDependent/Unix/ossource.cpp OGLCompilersDLL/InitializeDll.cpp glslang/CInterface/glslang_c_interface.cpp
glslang/GenericCodeGen/CodeGen.cpp glslang/GenericCodeGen/Link.cpp $(cd "$G" && ls glslang/MachineIndependent/*.cpp glslang/MachineIndependent/preprocessor/*.cpp)
SPIRV/GlslangToSpv.cpp SPIRV/InReadableOrder.cpp SPIRV/Logger.cpp SPIRV/SpvBuilder.cpp SPIRV/SpvPostProcess.cpp SPIRV/SpvTools.cpp
SPIRV/doc.cpp SPIRV/disassemble.cpp"
OBJS=
for f in $SRC; do
  o="$OUT/obj/$(echo "$f" | tr / _).o"
  OBJS="$OBJS $o"
  [ "$o" -nt "$G/$f" ] && continue
  echo "$o"
  "$CXX" -c -O2 -fPIC -fvisibility=hidden -std=c++17 -I"$G" -I"$G/glslang/MachineIndependent" -I"$OUT/include" \
    -o "$o" "$G/$f" &
  while [ "$(jobs -r | wc -l)" -ge "${JOBS:-6}" ]; do sleep 0.3; done
done
wait
rm -f "$OUT/libglslang.a"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar" rcs "$OUT/libglslang.a" $OBJS
echo "built $OUT/libglslang.a"
