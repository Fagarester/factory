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
#include "fn_upscale.h"

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
  int vmaj, vmin;
  fngl_reported_version(&vmaj, &vmin);
  if (attr == SDL_GL_CONTEXT_MAJOR_VERSION) *value = vmaj;
  if (attr == SDL_GL_CONTEXT_MINOR_VERSION) *value = vmin;
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

// The game streams vertices with GL_MAP_UNSYNCHRONIZED_BIT and no fences: it
// relies on the driver not letting the CPU run frames ahead (desktop
// behaviour). Android queues more frames, so data of frames still on the GPU
// got overwritten (wrong atlas on sprites under load). Wait for the GPU here.
// FN_FRAMES_IN_FLIGHT: frames the CPU may be ahead (default 2, 0 = off).
static void limit_frames_in_flight(void) {
  static GLsync fences[8];
  static unsigned idx;
  static int n = -1;
  if (n < 0) {
    const char *e = getenv("FN_FRAMES_IN_FLIGHT");
    n = e ? atoi(e) : 2;
    if (n > 7) n = 7;
    fprintf(stderr, "[fnsdl] frames in flight: %d\n", n);
  }
  if (n == 0) return;
  fences[idx % 8] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
  GLsync old = fences[(idx + 8 - n) % 8];
  if (idx >= (unsigned)n && old) {
    glClientWaitSync(old, GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull);
    glDeleteSync(old);
    fences[(idx + 8 - n) % 8] = NULL;
  }
  idx++;
}

// ---- ADPF performance hint (Android 13+): tell the CPU governor the frame
// target (60 fps) and the real work time of every frame, so CPU clocks go up
// when frames are late and down when there is headroom. FN_ADPF=0 disables,
// FN_ADPF_FPS sets the target. Threads: the render thread plus the busiest
// game threads, re-selected every 10 s.
#include <dirent.h>
#include <sys/types.h>
#include <unistd.h>

typedef void *(*hint_get_manager_fn)(void);
typedef void *(*hint_create_fn)(void *, const int32_t *, size_t, int64_t);
typedef int (*hint_report_fn)(void *, int64_t);
typedef int (*hint_set_threads_fn)(void *, const pid_t *, size_t);
static hint_report_fn hint_report;
static hint_set_threads_fn hint_set_threads;
static void *hint_session;
static int adpf_state;  // 0 not tried, 1 active, -1 off/unavailable
static int64_t adpf_target;
static double adpf_last_swap_end;

static long thread_cpu_ticks(pid_t tid) {
  char path[64], buf[512];
  snprintf(path, sizeof path, "/proc/self/task/%d/stat", tid);
  FILE *f = fopen(path, "r");
  if (!f) return 0;
  size_t n = fread(buf, 1, sizeof buf - 1, f);
  fclose(f);
  buf[n] = 0;
  char *p = strrchr(buf, ')');
  if (!p) return 0;
  unsigned long ut = 0, st = 0;
  // fields after ")": state(3) ... utime(14) stime(15)
  if (sscanf(p + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %lu %lu", &ut, &st) != 2) return 0;
  return (long)(ut + st);
}

// Render thread first, then up to 7 other threads with the most CPU time.
static size_t pick_threads(pid_t *out) {
  pid_t self = gettid();
  struct { pid_t tid; long t; } top[7] = {{0}};
  DIR *d = opendir("/proc/self/task");
  if (d) {
    struct dirent *e;
    while ((e = readdir(d))) {
      pid_t tid = atoi(e->d_name);
      if (tid <= 0 || tid == self) continue;
      long t = thread_cpu_ticks(tid);
      for (int i = 0; i < 7; i++) {
        if (t > top[i].t) {
          memmove(&top[i + 1], &top[i], sizeof top[0] * (6 - i));
          top[i].tid = tid;
          top[i].t = t;
          break;
        }
      }
    }
    closedir(d);
  }
  size_t n = 0;
  out[n++] = self;
  for (int i = 0; i < 7; i++) if (top[i].tid && top[i].t > 0) out[n++] = top[i].tid;
  return n;
}

static void adpf_frame(double work_start, double work_end) {
  if (adpf_state < 0) return;
  if (adpf_state == 0) {
    if (frame_no < 300) return;  // let loading finish first
    const char *e = getenv("FN_ADPF");
    if (e && !strcmp(e, "0")) { adpf_state = -1; fprintf(stderr, "[fnsdl] ADPF off (FN_ADPF=0)\n"); return; }
    void *lib = dlopen("libandroid.so", RTLD_NOW);
    hint_get_manager_fn get = lib ? (hint_get_manager_fn)dlsym(lib, "APerformanceHint_getManager") : NULL;
    hint_create_fn create = lib ? (hint_create_fn)dlsym(lib, "APerformanceHint_createSession") : NULL;
    hint_report = lib ? (hint_report_fn)dlsym(lib, "APerformanceHint_reportActualWorkDuration") : NULL;
    hint_set_threads = lib ? (hint_set_threads_fn)dlsym(lib, "APerformanceHint_setThreads") : NULL;
    void *mgr = get ? get() : NULL;
    const char *fps = getenv("FN_ADPF_FPS");
    adpf_target = (int64_t)(1e9 / (fps ? atof(fps) : 60.0));
    pid_t tids[8];
    size_t n = pick_threads(tids);
    hint_session = (mgr && create && hint_report) ? create(mgr, tids, n, adpf_target) : NULL;
    adpf_state = hint_session ? 1 : -1;
    fprintf(stderr, "[fnsdl] ADPF %s: target %.2f ms, %zu threads\n", hint_session ? "on" : "unavailable",
            adpf_target / 1e6, n);
    if (!hint_session) return;
  }
  if (work_start > 0) hint_report(hint_session, (int64_t)((work_end - work_start) * 1e9));
  if (hint_set_threads && frame_no % 600 == 0) {
    pid_t tids[8];
    size_t n = pick_threads(tids);
    hint_set_threads(hint_session, tids, n);
  }
}

// Frame times for the HUD (libfnative.so in the APK; absent in the proot test).
static void report_frame(double t) {
  static void (*stat_frame)(double);
  static int tried;
  static double last;
  if (!tried) {
    tried = 1;
    void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
    if (h) stat_frame = (void (*)(double))dlsym(h, "fn_stat_frame");
  }
  if (stat_frame && last > 0) stat_frame((t - last) * 1000.0);
  last = t;
}

static bool SDLCALL fn_GL_SwapWindow(SDL_Window *win) {
  double work_end = now_s();
  int up_w = 0, up_h = 0;
  if (fn_upscale_active() && real.SDL_GetWindowSizeInPixels(win, &up_w, &up_h)) {
    fn_upscale_begin(up_w, up_h);  // follows window size changes
    fn_upscale_save_state();
    fn_upscale_present(up_w, up_h);
  }
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
  bool r = real.SDL_GL_SwapWindow(win);
  if (up_w) fn_upscale_restore_state();
  limit_frames_in_flight();
  // Work time of this frame: from the end of the previous swap to this one.
  adpf_frame(adpf_last_swap_end, work_end);
  adpf_last_swap_end = now_s();
  report_frame(adpf_last_swap_end);
  return r;
}

// ---- render scale: the game sees a smaller window (fn_upscale.c draws the
// offscreen frame into the real one at SwapWindow)
static float sc(void) { return fn_upscale_factor(); }

static bool SDLCALL fn_GetWindowSizeInPixels(SDL_Window *win, int *w, int *h) {
  bool r = real.SDL_GetWindowSizeInPixels(win, w, h);
  if (r) fn_upscale_size(w, h);
  return r;
}
static bool SDLCALL fn_GetWindowSize(SDL_Window *win, int *w, int *h) {
  bool r = real.SDL_GetWindowSize(win, w, h);
  if (r) fn_upscale_size(w, h);
  return r;
}
static void scale_event(SDL_Event *e) {
  float k = sc();
  switch (e->type) {
    case SDL_EVENT_WINDOW_RESIZED:
    case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
      fn_upscale_size(&e->window.data1, &e->window.data2);
      break;
    case SDL_EVENT_MOUSE_MOTION:
      e->motion.x *= k; e->motion.y *= k; e->motion.xrel *= k; e->motion.yrel *= k;
      break;
    case SDL_EVENT_MOUSE_BUTTON_DOWN:
    case SDL_EVENT_MOUSE_BUTTON_UP:
      e->button.x *= k; e->button.y *= k;
      break;
    case SDL_EVENT_MOUSE_WHEEL:
      e->wheel.mouse_x *= k; e->wheel.mouse_y *= k;
      break;
    default: break;
  }
}
static bool SDLCALL fn_PollEvent(SDL_Event *e) {
  bool r = real.SDL_PollEvent(e);
  if (r && e) scale_event(e);
  return r;
}
static bool SDLCALL fn_WaitEvent(SDL_Event *e) {
  bool r = real.SDL_WaitEvent(e);
  if (r && e) scale_event(e);
  return r;
}
static bool SDLCALL fn_WaitEventTimeout(SDL_Event *e, Sint32 ms) {
  bool r = real.SDL_WaitEventTimeout(e, ms);
  if (r && e) scale_event(e);
  return r;
}
static int SDLCALL fn_PeepEvents(SDL_Event *ev, int n, SDL_EventAction action, Uint32 minType, Uint32 maxType) {
  int r = real.SDL_PeepEvents(ev, n, action, minType, maxType);
  if (ev && action != SDL_ADDEVENT)
    for (int i = 0; i < r; i++) scale_event(&ev[i]);
  return r;
}
static SDL_MouseButtonFlags SDLCALL fn_GetMouseState(float *x, float *y) {
  SDL_MouseButtonFlags r = real.SDL_GetMouseState(x, y);
  if (x) *x *= sc();
  if (y) *y *= sc();
  return r;
}
static void SDLCALL fn_WarpMouseInWindow(SDL_Window *win, float x, float y) {
  real.SDL_WarpMouseInWindow(win, x / sc(), y / sc());
}
static void upscale_begin(void) {
  SDL_Window *win = real.SDL_GL_GetCurrentWindow();
  int w = 0, h = 0;
  if (win && real.SDL_GetWindowSizeInPixels(win, &w, &h) && w > 0 && h > 0) fn_upscale_begin(w, h);
}
static SDL_GLContext SDLCALL fn_GL_CreateContext(SDL_Window *win) {
  SDL_GLContext c = real.SDL_GL_CreateContext(win);
  if (c) upscale_begin();
  return c;
}
static bool SDLCALL fn_GL_MakeCurrent(SDL_Window *win, SDL_GLContext ctx) {
  bool r = real.SDL_GL_MakeCurrent(win, ctx);
  if (r && win && ctx) upscale_begin();
  return r;
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
  if (fn_upscale_active()) {
    OVERRIDE(SDL_GetWindowSizeInPixels, fn_GetWindowSizeInPixels);
    OVERRIDE(SDL_GetWindowSize, fn_GetWindowSize);
    OVERRIDE(SDL_PollEvent, fn_PollEvent);
    OVERRIDE(SDL_WaitEvent, fn_WaitEvent);
    OVERRIDE(SDL_WaitEventTimeout, fn_WaitEventTimeout);
    OVERRIDE(SDL_PeepEvents, fn_PeepEvents);
    OVERRIDE(SDL_GetMouseState, fn_GetMouseState);
    OVERRIDE(SDL_WarpMouseInWindow, fn_WarpMouseInWindow);
    OVERRIDE(SDL_GL_CreateContext, fn_GL_CreateContext);
    OVERRIDE(SDL_GL_MakeCurrent, fn_GL_MakeCurrent);
  }
  fprintf(stderr, "[fnsdl] SDL %s jump table: game %u bytes, ours %zu bytes\n", path, tablesize,
          sizeof(jump_table));
  return 0;
}
