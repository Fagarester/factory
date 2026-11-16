#!/bin/sh
# Runs Factorio in proot through fnative + fnsdl with the offscreen SDL test build.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
GAME=${GAME:-/root/factorio-test/game/bin/arm64/factorio}
export SDL3_DYNAMIC_API="$ROOT/out/libfnsdl.so"
export FN_SDL_LIBRARY="$ROOT/work/sdl3-host/libSDL3.so.0"
export SDL_VIDEO_DRIVER=offscreen SDL_EGL_LIBRARY=libEGL.so SDL_OPENGL_LIBRARY=libGLESv2.so
exec "$ROOT/out/fnative" "$GAME" -c "${FN_CONFIG:-/tmp/fn-test/config.ini}" --disable-audio "$@"
