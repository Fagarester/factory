// SDL3 dynamic API entry for Factorio running on bionic.
//
// Factorio's static SDL calls SDL_DYNAPI_entry() of the library named in
// SDL3_DYNAMIC_API. We fill its jump table from the real SDL3 (Android build)
// and replace only the few entries that decide which GL context is created and
// where GL functions come from. Every other SDL call goes straight to SDL3.
#include <SDL3/SDL.h>
#define SDL_MAIN_NOIMPL
#include <SDL3/SDL_main.h>
#include <SDL3/SDL_vulkan.h>
#include "core/SDL_core_unsupported.h"
#include "video/SDL_video_unsupported.h"
#include <jni.h>
// Windows-only type, appears in the jump table on every platform.
typedef bool(SDLCALL *SDL_WindowsMessageHook)(void *userdata, void *msg);
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "fngl.h"

// Same X-macro as SDL_dynapi.c builds its table with.
#define SDL_DYNAPI_PROC(rc, fn, params, args, ret) rc(SDLCALL *fn) params;
typedef struct {
#include "dynapi/SDL_dynapi_procs.h"
} jump_table;
#undef SDL_DYNAPI_PROC

typedef Sint32 (*dynapi_entry_fn)(Uint32, void *, Uint32);

static jump_table real;  // real SDL3 functions

static bool SDLCALL fn_GL_SetAttribute(SDL_GLAttr attr, int value) {
  switch (attr) {
    case SDL_GL_CONTEXT_PROFILE_MASK:
      fngl_note_requested_profile(value);
      value = SDL_GL_CONTEXT_PROFILE_ES;
      break;
    case SDL_GL_CONTEXT_MAJOR_VERSION: value = 3; break;
    case SDL_GL_CONTEXT_MINOR_VERSION: value = 2; break;
    case SDL_GL_CONTEXT_FLAGS: value &= ~SDL_GL_CONTEXT_FORWARD_COMPATIBLE_FLAG; break;
    default: break;
  }
  return real.SDL_GL_SetAttribute(attr, value);
}

static bool SDLCALL fn_GL_GetAttribute(SDL_GLAttr attr, int *value) {
  bool r = real.SDL_GL_GetAttribute(attr, value);
  if (!r || !value) return r;
  // Report the desktop context the game asked for.
  if (attr == SDL_GL_CONTEXT_PROFILE_MASK) *value = SDL_GL_CONTEXT_PROFILE_CORE;
  if (attr == SDL_GL_CONTEXT_MAJOR_VERSION) *value = 3;
  if (attr == SDL_GL_CONTEXT_MINOR_VERSION) *value = 3;
  return r;
}

static SDL_FunctionPointer SDLCALL fn_GL_GetProcAddress(const char *name) {
  return (SDL_FunctionPointer)fngl_get_proc(name);
}

static void *real_get_proc(const char *name) { return (void *)real.SDL_GL_GetProcAddress(name); }

#define OVERRIDE(field, fn)                                                   \
  do {                                                                        \
    if (offsetof(jump_table, field) + sizeof(void *) <= tablesize)            \
      ((jump_table *)table)->field = fn;                                      \
  } while (0)

SDL_DECLSPEC Sint32 SDLCALL SDL_DYNAPI_entry(Uint32 apiver, void *table, Uint32 tablesize) {
  const char *path = getenv("FN_SDL_LIBRARY");
  if (!path) path = "libSDL3.so";
  void *h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!h) {
    fprintf(stderr, "[fnsdl] dlopen %s: %s\n", path, dlerror());
    return -1;
  }
  dynapi_entry_fn entry = (dynapi_entry_fn)dlsym(h, "SDL_DYNAPI_entry");
  if (!entry) return -1;
  // Our own copy of the full table, then the game's (possibly shorter) one.
  if (entry(apiver, &real, sizeof real) < 0) return -1;
  if (entry(apiver, table, tablesize) < 0) return -1;
  fngl_init(real_get_proc);
  OVERRIDE(SDL_GL_SetAttribute, fn_GL_SetAttribute);
  OVERRIDE(SDL_GL_GetAttribute, fn_GL_GetAttribute);
  OVERRIDE(SDL_GL_GetProcAddress, fn_GL_GetProcAddress);
  fprintf(stderr, "[fnsdl] SDL %s jump table: game %u bytes, ours %zu bytes\n", path, tablesize,
          sizeof(jump_table));
  return 0;
}
