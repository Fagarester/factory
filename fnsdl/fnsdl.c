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

// ---- frame hook: FPS log and screenshots (FN_SHOT=/path/prefix, FN_SHOT_FRAMES=100,300)
#include <GLES3/gl32.h>
#include <time.h>
#include <zlib.h>

static void write_png(const char *path, int w, int h, const unsigned char *rgba) {
  size_t raw_len = (size_t)(w * 4 + 1) * h;
  unsigned char *raw = malloc(raw_len);
  for (int y = 0; y < h; y++) {  // flip: GL origin is bottom-left
    raw[(size_t)y * (w * 4 + 1)] = 0;
    memcpy(raw + (size_t)y * (w * 4 + 1) + 1, rgba + (size_t)(h - 1 - y) * w * 4, (size_t)w * 4);
  }
  uLongf zlen = compressBound(raw_len);
  unsigned char *z = malloc(zlen);
  compress2(z, &zlen, raw, raw_len, 6);
  FILE *f = fopen(path, "wb");
  if (!f) { free(raw); free(z); return; }
  static const unsigned char sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
  fwrite(sig, 1, 8, f);
  unsigned char ihdr[13] = {(unsigned char)(w >> 24), (unsigned char)(w >> 16), (unsigned char)(w >> 8), (unsigned char)w,
                            (unsigned char)(h >> 24), (unsigned char)(h >> 16), (unsigned char)(h >> 8), (unsigned char)h,
                            8, 6, 0, 0, 0};
  struct { const char *type; const unsigned char *data; uLong len; } chunks[] = {
      {"IHDR", ihdr, 13}, {"IDAT", z, zlen}, {"IEND", NULL, 0}};
  for (int i = 0; i < 3; i++) {
    unsigned char len[4] = {(unsigned char)(chunks[i].len >> 24), (unsigned char)(chunks[i].len >> 16),
                            (unsigned char)(chunks[i].len >> 8), (unsigned char)chunks[i].len};
    fwrite(len, 1, 4, f);
    fwrite(chunks[i].type, 1, 4, f);
    if (chunks[i].len) fwrite(chunks[i].data, 1, chunks[i].len, f);
    uLong crc = crc32(0, (const Bytef *)chunks[i].type, 4);
    if (chunks[i].len) crc = crc32(crc, chunks[i].data, chunks[i].len);
    unsigned char c[4] = {(unsigned char)(crc >> 24), (unsigned char)(crc >> 16), (unsigned char)(crc >> 8), (unsigned char)crc};
    fwrite(c, 1, 4, f);
  }
  fclose(f);
  free(raw);
  free(z);
}

static long frame_no;
static double fps_t0;
static long fps_frames;
static double now_s(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec + ts.tv_nsec * 1e-9;
}

static bool SDLCALL fn_GL_SwapWindow(SDL_Window *win) {
  frame_no++;
  const char *shot = getenv("FN_SHOT"), *frames = getenv("FN_SHOT_FRAMES"), *every = getenv("FN_SHOT_EVERY");
  static double next_shot;
  int take = 0;
  if (shot && frames) {
    char key[32];
    snprintf(key, sizeof key, ",%ld,", frame_no);
    char list[256];
    snprintf(list, sizeof list, ",%s,", frames);
    take = strstr(list, key) != NULL;
  }
  if (shot && every) {
    double t = now_s();
    if (!next_shot) next_shot = t + atof(every);
    if (t >= next_shot) { take = 1; next_shot = t + atof(every); }
  }
  if (take) {
    {
      int w = 0, h = 0;
      real.SDL_GetWindowSizeInPixels(win, &w, &h);
      GLint fbo;
      glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &fbo);
      glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
      glPixelStorei(GL_PACK_ALIGNMENT, 4);
      unsigned char *px = malloc((size_t)w * h * 4);
      glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
      glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)fbo);
      char path[512];
      snprintf(path, sizeof path, "%s-%ld.png", shot, frame_no);
      write_png(path, w, h, px);
      free(px);
      fprintf(stderr, "[fnsdl] screenshot %s (%dx%d)\n", path, w, h);
    }
  }
  double t = now_s();
  if (!fps_t0) fps_t0 = t;
  fps_frames++;
  if (t - fps_t0 >= 5.0) {
    fprintf(stderr, "[fnsdl] frame %ld: %.1f fps\n", frame_no, fps_frames / (t - fps_t0));
    fps_t0 = t;
    fps_frames = 0;
  }
  return real.SDL_GL_SwapWindow(win);
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
  // Desktop builds export SDL_DYNAPI_entry; the Android build has dynapi
  // compiled out, so there the table is filled from the exported functions.
  dynapi_entry_fn entry = (dynapi_entry_fn)dlsym(h, "SDL_DYNAPI_entry");
  if (entry && !getenv("FNSDL_NO_ENTRY")) {  // FNSDL_NO_ENTRY: test the Android path
    if (entry(apiver, &real, sizeof real) < 0) return -1;
  } else {
    int missing = 0;
#define SDL_DYNAPI_PROC(rc, fn, params, args, ret) \
    if (!(real.fn = (rc(SDLCALL *) params)dlsym(h, #fn))) missing++;
#include "dynapi/SDL_dynapi_procs.h"
#undef SDL_DYNAPI_PROC
    fprintf(stderr, "[fnsdl] table filled by dlsym, %d functions not exported\n", missing);
  }
  if (tablesize > sizeof real) {
    fprintf(stderr, "[fnsdl] game table %u bytes > ours %zu\n", tablesize, sizeof real);
    return -1;
  }
  memcpy(table, &real, tablesize);
  fngl_init(real_get_proc);
  OVERRIDE(SDL_GL_SetAttribute, fn_GL_SetAttribute);
  OVERRIDE(SDL_GL_GetAttribute, fn_GL_GetAttribute);
  OVERRIDE(SDL_GL_GetProcAddress, fn_GL_GetProcAddress);
  OVERRIDE(SDL_GL_SwapWindow, fn_GL_SwapWindow);
  fprintf(stderr, "[fnsdl] SDL %s jump table: game %u bytes, ours %zu bytes\n", path, tablesize,
          sizeof(jump_table));
  return 0;
}
