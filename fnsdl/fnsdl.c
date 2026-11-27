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
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "fngl.h"
#include "fngl_prof.h"
#include "vk/fnvk.h"
#include "fn_upscale.h"
#include "fnlog.h"

#ifdef FN_LOG
void fn_sdl_log(const char *tag, const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fprintf(stderr, "[%s] ", tag);
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
}
#endif

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

// ---- text input (the game's text fields)
// tells libfnative (the app) whether the game types now: the app brings the keyboard back only then
static void report_text(int active) {
  static void (*text_input)(int);
  static int tried;
  if (!tried) {
    tried = 1;
    void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
    if (h) text_input = (void (*)(int))dlsym(h, "fn_text_input");
  }
  if (text_input) text_input(active);
}
// The game's rumble with keyboard and mouse: the loader lets the game vibrate without an active gamepad
// (loader/vibrate.c), so SDL_RumbleGamepad comes with none. It goes to the app's unseen gamepad "FNative Gamepad"
// (gear menu switch), whose rumble the app plays on the phone's vibrator; nothing when it is not plugged in.
static SDL_Gamepad *fnative_pad(void) {
  static SDL_Gamepad *pad;
  if (pad && real.SDL_GamepadConnected(pad)) return pad;
  if (pad) real.SDL_CloseGamepad(pad);
  pad = NULL;
  int n = 0;
  SDL_JoystickID *ids = real.SDL_GetGamepads(&n);
  for (int i = 0; ids && i < n && !pad; i++) {
    const char *name = real.SDL_GetGamepadNameForID(ids[i]);
    if (name && strstr(name, "FNative")) pad = real.SDL_OpenGamepad(ids[i]);
  }
  real.SDL_free(ids);
  return pad;
}

static bool SDLCALL fn_RumbleGamepad(SDL_Gamepad *g, Uint16 low, Uint16 high, Uint32 ms) {
  if (!g) g = fnative_pad();
  return g ? real.SDL_RumbleGamepad(g, low, high, ms) : false;
}

static bool SDLCALL fn_StartTextInput(SDL_Window *w) {
  report_text(1);
  return real.SDL_StartTextInput(w);
}
static bool SDLCALL fn_StartTextInputWithProperties(SDL_Window *w, SDL_PropertiesID props) {
  report_text(1);
  return real.SDL_StartTextInputWithProperties(w, props);
}
static bool SDLCALL fn_StopTextInput(SDL_Window *w) {
  report_text(0);
  return real.SDL_StopTextInput(w);
}

// Vulkan renderer: the window framebuffer follows the real window size. On Android (vk_android) the window has
// no OpenGL at all: fnvk presents with its own swapchain and the SDL_GL_* context functions are stand-ins.
static SDL_Window *vk_win;
static int vk_android;

static void vk_window_size(int *w, int *h) {
  SDL_Window *win = vk_android ? vk_win : real.SDL_GL_GetCurrentWindow();
  if (!win || !real.SDL_GetWindowSizeInPixels(win, w, h)) *w = *h = 0;
  // the real size: vk_window.c applies the render scale (the game renders smaller, the upscale pass scales)
}

static SDL_Window *SDLCALL fn_vk_CreateWindow(const char *t, int w, int h, SDL_WindowFlags f) {
  SDL_Window *win = real.SDL_CreateWindow(t, w, h, f & ~SDL_WINDOW_OPENGL);
  if (win) vk_win = win;
  return win;
}
static SDL_Window *SDLCALL fn_vk_CreateWindowWithProperties(SDL_PropertiesID p) {
  real.SDL_SetBooleanProperty(p, SDL_PROP_WINDOW_CREATE_OPENGL_BOOLEAN, false);
  Sint64 fl = real.SDL_GetNumberProperty(p, SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, 0);
  if (fl & SDL_WINDOW_OPENGL) real.SDL_SetNumberProperty(p, SDL_PROP_WINDOW_CREATE_FLAGS_NUMBER, fl & ~SDL_WINDOW_OPENGL);
  SDL_Window *win = real.SDL_CreateWindowWithProperties(p);
  if (win) vk_win = win;
  return win;
}
static SDL_GLContext SDLCALL fn_vk_GL_CreateContext(SDL_Window *w) {
  if (w) vk_win = w;
  return (SDL_GLContext)&vk_win;
}
static bool SDLCALL fn_vk_GL_MakeCurrent(SDL_Window *w, SDL_GLContext c) {
  (void)w, (void)c;
  return true;
}
static SDL_Window *SDLCALL fn_vk_GL_GetCurrentWindow(void) { return vk_win; }
static SDL_GLContext SDLCALL fn_vk_GL_GetCurrentContext(void) { return (SDL_GLContext)&vk_win; }
static bool SDLCALL fn_vk_GL_DestroyContext(SDL_GLContext c) {
  (void)c;
  return true;
}
static bool SDLCALL fn_vk_GL_SetSwapInterval(int i) {
  (void)i;
  return true;
}
static bool SDLCALL fn_vk_GL_GetSwapInterval(int *i) {
  if (i) *i = 1;
  return true;
}
static bool SDLCALL fn_vk_GL_LoadLibrary(const char *p) {
  (void)p;
  return true;
}
static void SDLCALL fn_vk_GL_UnloadLibrary(void) {}
static bool SDLCALL fn_vk_GL_ExtensionSupported(const char *e) {
  (void)e;
  return false;
}

static SDL_FunctionPointer SDLCALL fn_GL_GetProcAddress(const char *name) {
  static int vk_ready;
  if (!vk_ready && (vk_ready = 1) && fnvk_active()) fnvk_set_window_size_fn(vk_window_size);
  return (SDL_FunctionPointer)fngl_get_proc(name);
}

// ---- frame hook: FPS log and screenshots (FN_SHOT=/path/prefix, FN_SHOT_FRAMES=100,300)
#include <GLES3/gl32.h>
#include <time.h>
#include <errno.h>
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
    FNSDL_LOG("fnsdl", "frames in flight: %d", n);
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

// The session runs only while the game ticks: while a map loads (one thread, minutes, no ticks) the reported frame
// work is the loading screen's ~2 ms, and the governor then kept the loading thread on the small cores at low clocks
// (9300+, 2026-11-24: level.dat 66 s with the hint, 32 s without; in play the hint gave 46 FPS against 30). Started
// after 3 s of ticks, closed after 1 s without (FN_ADPF=0: never). Without the loader's tick count: after 300 frames.
typedef void (*hint_close_fn)(void *);
static hint_close_fn hint_close;
static uint64_t (*game_ticks)(void);

static int adpf_ticking(double now) {
  static int tried;
  static uint64_t last_ticks;
  static double changed, ticking_since;
  if (!tried) {
    tried = 1;
    void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
    game_ticks = (uint64_t (*)(void))(h ? dlsym(h, "fn_game_ticks") : dlsym(RTLD_DEFAULT, "fn_game_ticks"));
  }
  if (!game_ticks) return frame_no >= 300 ? 2 : 0;
  uint64_t t = game_ticks();
  if (t != last_ticks) {
    if (!changed || now - changed > 1.0) ticking_since = now;
    last_ticks = t;
    changed = now;
  }
  if (!changed || now - changed > 1.0) return 0;  // no ticks for 1 s: loading, paused
  return now - ticking_since >= 3.0 ? 2 : 1;      // 2: ticking for 3 s
}

static void adpf_frame(double work_start, double work_end) {
  if (adpf_state < 0) return;
  int ticking = adpf_ticking(work_end);
  if (adpf_state == 1 && !ticking) {
    if (hint_close) hint_close(hint_session);
    hint_session = NULL;
    adpf_state = 0;
    FNSDL_LOG("fnsdl", "ADPF paused: no game ticks (map loading or pause)");
    return;
  }
  if (adpf_state == 0) {
    if (ticking < 2) return;
    static int loaded;
    if (!loaded) {
      loaded = 1;
      const char *e = getenv("FN_ADPF");
      if (e && !strcmp(e, "0")) { adpf_state = -1; FNSDL_LOG("fnsdl", "ADPF off (FN_ADPF=0)"); return; }
    }
    void *lib = dlopen("libandroid.so", RTLD_NOW);
    hint_get_manager_fn get = lib ? (hint_get_manager_fn)dlsym(lib, "APerformanceHint_getManager") : NULL;
    hint_create_fn create = lib ? (hint_create_fn)dlsym(lib, "APerformanceHint_createSession") : NULL;
    hint_report = lib ? (hint_report_fn)dlsym(lib, "APerformanceHint_reportActualWorkDuration") : NULL;
    hint_set_threads = lib ? (hint_set_threads_fn)dlsym(lib, "APerformanceHint_setThreads") : NULL;
    hint_close = lib ? (hint_close_fn)dlsym(lib, "APerformanceHint_closeSession") : NULL;
    void *mgr = get ? get() : NULL;
    const char *fps = getenv("FN_ADPF_FPS");
    adpf_target = (int64_t)(1e9 / (fps ? atof(fps) : 60.0));
    pid_t tids[8];
    size_t n = pick_threads(tids);
    hint_session = (mgr && create && hint_report) ? create(mgr, tids, n, adpf_target) : NULL;
    adpf_state = hint_session ? 1 : -1;
    FNSDL_LOG("fnsdl", "ADPF %s: target %.2f ms, %zu threads", hint_session ? "on" : "unavailable",
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

// The FPS limit (gear menu -> Performance, libfnative's fn_fps_limit; FN_FPS_LIMIT=N without it): the frame waits
// before its swap. The frame's work time for the performance hints is taken before the wait.
static void limit_fps(void) {
  static int (*get)(void);
  static int tried, env_limit;
  static double next;
  if (!tried) {
    tried = 1;
    void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
    if (h) get = (int (*)(void))dlsym(h, "fn_fps_limit");
    const char *e = getenv("FN_FPS_LIMIT");
    env_limit = e ? atoi(e) : 0;
  }
  int limit = get ? get() : env_limit;
  if (limit <= 0) {
    next = 0;
    return;
  }
  double period = 1.0 / limit, now = now_s();
  if (next == 0 || now - next > period) next = now;  // a start, a change, or far behind: no burst
  next += period;
  if (next > now) {
    double d = next - now;
    struct timespec ts = {(time_t)d, (long)((d - (time_t)d) * 1e9)};
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {}
  }
}

// Live graphics changes (gear menu "Графика"): libfnative keeps the request (fn_graphics_*), applied after a swap;
// the game then gets window size events and makes its buffers again. FN_UPSCALE_TEST=frame:scale,... tests it.
static void graphics_live(SDL_Window *win, int vulkan) {
  static int (*seq)(void);
  static float (*scale)(void);
  static int (*gsr)(void);
  static int tried, last_seq;
  if (!tried) {
    tried = 1;
    void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
    if (h) {
      seq = (int (*)(void))dlsym(h, "fn_graphics_seq");
      scale = (float (*)(void))dlsym(h, "fn_graphics_scale");
      gsr = (int (*)(void))dlsym(h, "fn_graphics_gsr");
    }
  }
  if (seq && scale && gsr && seq() != last_seq) {
    last_seq = seq();
    fn_upscale_request(scale(), gsr());
  }
  static const char *test;
  static int test_read;
  if (!test_read) test_read = 1, test = getenv("FN_UPSCALE_TEST");  // once: not a lookup every frame
  if (test) {
    for (const char *p = test; *p;) {
      long f = strtol(p, (char **)&p, 10);
      float sc = *p == ':' ? strtof(p + 1, (char **)&p) : 1.0f;
      if (f == frame_no) fn_upscale_request(sc, 1);
      while (*p && *p != ',') p++;
      if (*p == ',') p++;
    }
  }
  int w = 0, h = 0;
  if (!real.SDL_GetWindowSizeInPixels(win, &w, &h) || !(vulkan ? fn_upscale_apply_values() : fn_upscale_apply(w, h)))
    return;
  // the size the game sees changed: as a resized window (the event filter scales the sizes)
  int pw = 0, ph = 0;
  real.SDL_GetWindowSize(win, &pw, &ph);
  SDL_Event e;
  memset(&e, 0, sizeof e);
  e.type = SDL_EVENT_WINDOW_RESIZED;
  e.window.windowID = real.SDL_GetWindowID(win);
  e.window.data1 = pw;
  e.window.data2 = ph;
  real.SDL_PushEvent(&e);
  e.type = SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED;
  e.window.data1 = w;
  e.window.data2 = h;
  real.SDL_PushEvent(&e);
}

static int background_swap(void);  // the background run (below, by the event poll)
static void bg_note_context(void);
static bool SDLCALL fn_GL_SwapWindow(SDL_Window *win) {
  if (background_swap()) return true;
  bg_note_context();
  double work_end = now_s();
  if (fngl_prof_enabled()) fngl_prof_frame();
  int up_w = 0, up_h = 0;
  int vulkan = fnvk_active();
  if (!vulkan && fn_upscale_active() && real.SDL_GetWindowSizeInPixels(win, &up_w, &up_h)) {
    fn_upscale_begin(up_w, up_h);  // follows window size changes
    fn_upscale_save_state();
    fn_upscale_present(up_w, up_h);
  }
  frame_no++;
  static const char *shot, *frames, *every;  // test screenshots (proot): read once, not every frame
  static int shot_read;
  if (!shot_read) shot_read = 1, shot = getenv("FN_SHOT"), frames = getenv("FN_SHOT_FRAMES"), every = getenv("FN_SHOT_EVERY");
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
      unsigned char *vpx = vulkan ? malloc((size_t)w * h * 4) : NULL;
      if (vpx && fnvk_read_window(w, h, vpx)) {
        char path[512];
        snprintf(path, sizeof path, "%s-%ld.png", shot, frame_no);
        write_png(path, w, h, vpx);
        FNSDL_LOG("fnsdl", "screenshot %s (%dx%d, Vulkan)", path, w, h);
      }
      free(vpx);
    }
    if (!vulkan) {
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
      FNSDL_LOG("fnsdl", "screenshot %s (%dx%d)", path, w, h);
    }
  }
  limit_fps();
  if (fngl_prof_enabled()) fngl_prof_swap_begin();
  bool r = true;
  if (vulkan) {
    if (vk_android)
      fnvk_set_native_window(real.SDL_GetPointerProperty(real.SDL_GetWindowProperties(win ? win : vk_win),
                                                         SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER, NULL));
    fnvk_swap();
  }
  else r = real.SDL_GL_SwapWindow(win);
  if (fngl_prof_enabled()) fngl_prof_swap_end();
  if (up_w) fn_upscale_restore_state();
  if (win || vk_win) graphics_live(win ? win : vk_win, vulkan);  // live render scale (Vulkan: values only)
  if (!vulkan) limit_frames_in_flight();
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
  if (!fn_upscale_active()) return;
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
// libfnative's work on the game's main thread (the input method switch, loader/inputmethod.c): its event polling
// The game's sound quieter while the walkie-talkie talks (NetChat: libfnative's fn_game_volume, 1 = as set in the
// game). The game's playback devices are noted when opened; their SDL gain follows the target in small steps on each
// event poll (~0.25 s from 1 to 0.3 at 60 FPS). The walkie-talkie plays on its own AudioTrack: not affected.
#define GAME_AUDIO_MAX 8
static SDL_AudioDeviceID game_audio[GAME_AUDIO_MAX];
static int game_audio_n;
static float game_gain = 1.0f;

static void note_game_audio(SDL_AudioDeviceID requested, SDL_AudioDeviceID id) {
  if (!id || requested == SDL_AUDIO_DEVICE_DEFAULT_RECORDING) return;
  for (int i = 0; i < game_audio_n; i++)
    if (game_audio[i] == id) return;
  if (game_audio_n < GAME_AUDIO_MAX) game_audio[game_audio_n++] = id;
  else game_audio[GAME_AUDIO_MAX - 1] = id;
  FNSDL_LOG("fnsdl", "game audio device %u", (unsigned)id);
  if (game_gain < 0.999f) real.SDL_SetAudioDeviceGain(id, game_gain);
}
static SDL_AudioDeviceID SDLCALL fn_OpenAudioDevice(SDL_AudioDeviceID devid, const SDL_AudioSpec *spec) {
  SDL_AudioDeviceID id = real.SDL_OpenAudioDevice(devid, spec);
  note_game_audio(devid, id);
  return id;
}
static SDL_AudioStream *SDLCALL fn_OpenAudioDeviceStream(SDL_AudioDeviceID devid, const SDL_AudioSpec *spec,
                                                         SDL_AudioStreamCallback cb, void *ud) {
  SDL_AudioStream *st = real.SDL_OpenAudioDeviceStream(devid, spec, cb, ud);
  if (st) note_game_audio(devid, real.SDL_GetAudioStreamDevice(st));
  return st;
}
static void game_audio_live(void) {
  static float (*target)(void);
  static int tried;
  if (!tried) {
    tried = 1;
    void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
    target = (float (*)(void))(h ? dlsym(h, "fn_game_volume") : dlsym(RTLD_DEFAULT, "fn_game_volume"));
  }
  if (!target || !game_audio_n) return;
  float t = target(), g = game_gain;
  if (t == g) return;
  g = t > g ? (g + 0.05f > t ? t : g + 0.05f) : (g - 0.05f < t ? t : g - 0.05f);
  game_gain = g;
  int ok = 0;
  for (int i = 0; i < game_audio_n; i++) ok += real.SDL_SetAudioDeviceGain(game_audio[i], g);
  if (g == t) FNSDL_LOG("fnsdl", "game audio gain %.2f on %d of %d devices", g, ok, game_audio_n);
}

// Running in the background (gear menu "Производительность" → "Работать в фоне"; libfnative's fn_app_background /
// fn_background_run, set by GameActivity on pause/resume). SDL is told not to block on pause (SDL_ANDROID_BLOCK_ON_PAUSE=0
// in SDL_DYNAPI_entry, OpenGL ES only); we do it ourselves when the setting is off — the main thread waits as SDL did.
// With it on, the game goes on: SDL lets the GL context go (its window surface is gone), so the context is made
// current again on a small pbuffer (or none), the game keeps drawing there and SwapWindow only paces it to 30 FPS.
// The game's sound is paused in the background either way; SDL's own resume makes the window surface current again.
#include <EGL/egl.h>
static int bg_block_set;  // SDL_ANDROID_BLOCK_ON_PAUSE=0 was set: the waiting is ours
static int bg_audio_paused, bg_gl;
static EGLDisplay bg_dpy = EGL_NO_DISPLAY;
static EGLContext bg_ctx = EGL_NO_CONTEXT;
static EGLSurface bg_surf = EGL_NO_SURFACE;
static struct {
  EGLContext (*GetCurrentContext)(void);
  EGLDisplay (*GetCurrentDisplay)(void);
  EGLSurface (*GetCurrentSurface)(EGLint);
  EGLBoolean (*MakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
  EGLBoolean (*QueryContext)(EGLDisplay, EGLContext, EGLint, EGLint *);
  EGLBoolean (*ChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
  EGLSurface (*CreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint *);
} egl;
static int (*bg_get)(void), (*bg_run_get)(void);

static int bg_fns(void) {
  static int tried, ok;
  if (tried) return ok;
  tried = 1;
  void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
  bg_get = (int (*)(void))(h ? dlsym(h, "fn_app_background") : dlsym(RTLD_DEFAULT, "fn_app_background"));
  bg_run_get = (int (*)(void))(h ? dlsym(h, "fn_background_run") : dlsym(RTLD_DEFAULT, "fn_background_run"));
  void *e = dlopen("libEGL.so", RTLD_NOW);
  if (e) {
    egl.GetCurrentContext = (EGLContext (*)(void))dlsym(e, "eglGetCurrentContext");
    egl.GetCurrentDisplay = (EGLDisplay (*)(void))dlsym(e, "eglGetCurrentDisplay");
    egl.GetCurrentSurface = (EGLSurface (*)(EGLint))dlsym(e, "eglGetCurrentSurface");
    egl.MakeCurrent = (EGLBoolean (*)(EGLDisplay, EGLSurface, EGLSurface, EGLContext))dlsym(e, "eglMakeCurrent");
    egl.QueryContext = (EGLBoolean (*)(EGLDisplay, EGLContext, EGLint, EGLint *))dlsym(e, "eglQueryContext");
    egl.ChooseConfig = (EGLBoolean (*)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *))dlsym(e, "eglChooseConfig");
    egl.CreatePbufferSurface = (EGLSurface (*)(EGLDisplay, EGLConfig, const EGLint *))dlsym(e, "eglCreatePbufferSurface");
  }
  ok = bg_get && bg_run_get && egl.GetCurrentContext && egl.GetCurrentDisplay && egl.GetCurrentSurface && egl.MakeCurrent
       && egl.QueryContext && egl.ChooseConfig && egl.CreatePbufferSurface;
  return ok;
}

/* The game's context, noted at each swap while the window shows: made current again in the background. */
static void bg_note_context(void) {
  if (!bg_block_set || !bg_fns() || bg_gl) return;
  EGLContext c = egl.GetCurrentContext();
  if (c != EGL_NO_CONTEXT) {
    bg_ctx = c;
    bg_dpy = egl.GetCurrentDisplay();
  }
}

static void bg_pause_audio(int pause) {
  if (pause == bg_audio_paused) return;
  bg_audio_paused = pause;
  for (int i = 0; i < game_audio_n; i++) {
    if (pause) real.SDL_PauseAudioDevice(game_audio[i]);
    else real.SDL_ResumeAudioDevice(game_audio[i]);
  }
}

/* SDL gave the context up (its pause) and has not taken it back (its resume needs the window surface and focus,
 * which come after the activity's onResume). */
static int bg_context_gone(void) {
  return bg_ctx != EGL_NO_CONTEXT && egl.GetCurrentContext() == EGL_NO_CONTEXT;
}

static void bg_keep_running(void);
static int bg_on_pbuffer(void);
static void background_live(void) {
  if (!bg_block_set || !bg_fns()) return;
  int bg = bg_get();
  bg_pause_audio(bg);
  if (bg_run_get()) {
    // whenever SDL has let the context go (its pause), it goes on the pbuffer — also when the app is already back
    // but SDL's resume has not come yet (quick back-and-forth: SDL needs the surface and focus first)
    bg_keep_running();
    return;
  }
  if (!bg && !bg_context_gone()) return;
  // as SDL's own pause: the main thread waits until the app is back AND SDL has the context again (SDL_PumpEvents
  // waits up to 100 ms for Android's lifecycle events, so SDL's pause and resume run from here)
  FNSDL_LOG("fnsdl", "background: paused");
  while ((bg_get() || bg_context_gone()) && !bg_run_get()) real.SDL_PumpEvents();
  FNSDL_LOG("fnsdl", "background: back");
  bg_pause_audio(bg_get());
  if (bg_run_get() && bg_get()) bg_keep_running();  // the setting was switched on meanwhile
}

static void bg_keep_running(void) {
  if (!bg_context_gone()) return;  // SDL holds it (the window) or it is on the pbuffer already
  if (bg_surf == EGL_NO_SURFACE) {
    EGLint id = 0, n = 0;
    EGLConfig cfg;
    egl.QueryContext(bg_dpy, bg_ctx, EGL_CONFIG_ID, &id);
    EGLint want[] = {EGL_CONFIG_ID, id, EGL_NONE}, size[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    if (egl.ChooseConfig(bg_dpy, want, &cfg, 1, &n) && n == 1) bg_surf = egl.CreatePbufferSurface(bg_dpy, cfg, size);
  }
  // no pbuffer for the window's config: no surface at all (EGL_KHR_surfaceless_context); framebuffer 0 then draws nothing
  if (egl.MakeCurrent(bg_dpy, bg_surf, bg_surf, bg_ctx)) {
    bg_gl = 1;
    FNSDL_LOG("fnsdl", "background: running, context on %s", bg_surf != EGL_NO_SURFACE ? "a pbuffer" : "no surface");
  } else FNSDL_LOG("fnsdl", "background: the context cannot be made current");
}

/* The game's context is on our pbuffer now (not the window's surface). */
static int bg_on_pbuffer(void) {
  if (!bg_gl) return 0;
  if (egl.GetCurrentContext() == bg_ctx && egl.GetCurrentSurface(EGL_DRAW) == bg_surf) return 1;
  bg_gl = 0;  // SDL's resume made the window surface current again (or let the context go: made current again)
  FNSDL_LOG("fnsdl", "background: window back");
  return 0;
}

/* SwapWindow in the background: nothing to show; the frame is paced to 30 FPS. Factorio does only 1-2 ticks a frame
 * (0.177 at ~5 FPS: 4.8 ticks/s); at 30 FPS it keeps 60 UPS (the FPS limit of "Производительность", measured). 1 = done. */
static int background_swap(void) {
  if (bg_block_set && bg_fns() && bg_run_get()) bg_keep_running();  // SDL's pause came in a call other than ours
  if (!bg_on_pbuffer()) return 0;
  static double next;
  double now = now_s();
  if (next < now - 1.0 / 30) next = now;
  next += 1.0 / 30;
  if (next > now) {
    double d = next - now;
    struct timespec ts = {(time_t)d, (long)((d - (time_t)d) * 1e9)};
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {}
  }
  return 1;
}

/* FN_TEST_MINIMIZE=start:seconds (a test, proot): the game is told its window is minimized from start (seconds after
 * the first poll) for that long, then restored; frames and game ticks per second are logged every 2 s. */
static void test_minimize(void) {
  static int state = -1;
  static double t0, start, len, last;
  static long last_frames;
  static uint64_t last_ticks;
  static volatile uint64_t *counter;
  if (state == -2) return;
  if (state == -1) {
    const char *e = getenv("FN_TEST_MINIMIZE");
    if (!e || sscanf(e, "%lf:%lf", &start, &len) != 2) {
      state = -2;
      return;
    }
    const char *c = getenv("FN_TICK_COUNTER");  // the proot loader's counter (loader/main.c)
    counter = c ? (volatile uint64_t *)strtoull(c, NULL, 16) : NULL;
    t0 = last = now_s();
    state = 0;
  }
  double t = now_s() - t0;
  static int focused;
  const char *fa = getenv("FN_TEST_FOCUS_AT");  // proot's offscreen window has no focus: given at that second
  if (!focused && fa && t >= atof(fa)) {
    int n = 0;
    SDL_Window **w = real.SDL_GetWindows(&n);
    if (w && n > 0) {
      SDL_Event ev;
      memset(&ev, 0, sizeof ev);
      ev.type = SDL_EVENT_WINDOW_FOCUS_GAINED;
      ev.window.windowID = real.SDL_GetWindowID(w[0]);
      real.SDL_PushEvent(&ev);
      FNSDL_LOG("fnsdl", "test: focus given at %.0f s", t);
    }
    real.SDL_free(w);
    focused = 1;
  }
  int want = t >= start && t < start + len ? 1 : t >= start + len ? 2 : 0;
  if (want != state) {
    int n = 0;
    SDL_Window **w = real.SDL_GetWindows(&n);
    if (w && n > 0) {
      SDL_Event ev;
      memset(&ev, 0, sizeof ev);
      ev.type = want == 1 ? SDL_EVENT_WINDOW_MINIMIZED : SDL_EVENT_WINDOW_RESTORED;
      ev.window.windowID = real.SDL_GetWindowID(w[0]);
      real.SDL_PushEvent(&ev);
      if (want == 1) {  // as Android: focus goes too
        ev.type = SDL_EVENT_WINDOW_FOCUS_LOST;
        real.SDL_PushEvent(&ev);
      } else {
        ev.type = SDL_EVENT_WINDOW_FOCUS_GAINED;
        real.SDL_PushEvent(&ev);
      }
      FNSDL_LOG("fnsdl", "test: window %s at %.0f s", want == 1 ? "minimized" : "restored", t);
    }
    real.SDL_free(w);
    state = want;
  }
  if (now_s() - last >= 2 && counter) {
    double d = now_s() - last;
    uint64_t k = *counter;
    FNSDL_LOG("fnsdl", "test: %s %.1f frames/s, %.1f ticks/s", state == 1 ? "MINIMIZED" : "normal   ",
            (frame_no - last_frames) / d, (k - last_ticks) / d);
    last = now_s();
    last_frames = frame_no;
    last_ticks = k;
  }
}

static void game_poll(void) {
  test_minimize();
  game_audio_live();
  static void (*poll)(void);
  static int tried;
  if (!tried) {
    tried = 1;
    void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
    if (h) poll = (void (*)(void))dlsym(h, "fn_game_poll");
  }
  if (poll) poll();
}

/* Running on in the background: SDL_PollEvent would wait up to 100 ms for Android's lifecycle each call while SDL
 * is paused (the game polls every frame: the main loop crawled — 0.177 log: 4.8 game ticks/s). The queue is read
 * without waiting; Android's events are pumped every 2 s in the background (a swipe away = SDL's quit; 100 ms each) and
 * every 200 ms once the app is back (SDL's resume comes then). */
static bool bg_poll(SDL_Event *e) {
  static double last_pump;
  double t = now_s();
  if (t - last_pump >= (bg_get() ? 2.0 : 0.2)) {
    last_pump = t;
    real.SDL_PumpEvents();
  }
  SDL_Event tmp;
  return real.SDL_PeepEvents(e ? e : &tmp, 1, e ? SDL_GETEVENT : SDL_PEEKEVENT, SDL_EVENT_FIRST, SDL_EVENT_LAST) > 0;
}

/* Running on in the background, the game is not told it is minimized or in the background (it might hold back). */
static int bg_hide_event(const SDL_Event *e) {
  if (!bg_block_set || !bg_fns() || !bg_run_get() || !bg_get()) return 0;
  // FN_BG_MINIMIZE=1 (env.txt, an experiment): the game is told it is minimized — it then draws nothing itself
  // (proot: 0 frames/s on a map, 60 in the menu); whether its ticks go on there is to be seen in fnative.log
  static int minimize = -1;
  if (minimize < 0) {
    const char *m = getenv("FN_BG_MINIMIZE");
    minimize = m && *m == '1';
  }
  if (minimize && (e->type == SDL_EVENT_WINDOW_MINIMIZED || e->type == SDL_EVENT_WINDOW_HIDDEN)) return 0;
  switch (e->type) {
    case SDL_EVENT_WINDOW_MINIMIZED:
    case SDL_EVENT_WINDOW_HIDDEN:
    case SDL_EVENT_WINDOW_OCCLUDED:
    case SDL_EVENT_WINDOW_FOCUS_LOST:
    case SDL_EVENT_WILL_ENTER_BACKGROUND:
    case SDL_EVENT_DID_ENTER_BACKGROUND:
      return 1;
    default:
      return 0;
  }
}

static bool SDLCALL fn_PollEvent(SDL_Event *e) {
  game_poll();
  bool r;
  for (;;) {
    r = bg_on_pbuffer() ? bg_poll(e) : real.SDL_PollEvent(e);
    background_live();
    if (r && e && bg_hide_event(e)) continue;
    break;
  }
  if (r && e) scale_event(e);
  return r;
}
static bool SDLCALL fn_WaitEvent(SDL_Event *e) {
  bool r = real.SDL_WaitEvent(e);
  if (r && e) scale_event(e);
  return r;
}
static bool SDLCALL fn_WaitEventTimeout(SDL_Event *e, Sint32 ms) {
  game_poll();
  bool r = real.SDL_WaitEventTimeout(e, ms);
  background_live();
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
    FNSDL_LOG("fnsdl", "dlopen %s: %s", path, dlerror());
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
    FNSDL_LOG("fnsdl", "table filled by dlsym, %d functions not exported", missing);
  }
  if (tablesize > sizeof real) {
    FNSDL_LOG("fnsdl", "game table %u bytes > ours %zu", tablesize, sizeof real);
    return -1;
  }
  memcpy(table, &real, tablesize);
  if (!fnvk_active() && !strcmp(real.SDL_GetPlatform(), "Android")) {
    setenv("SDL_ANDROID_BLOCK_ON_PAUSE", "0", 1);  // background_live waits instead (or lets the game run on)
    bg_block_set = 1;
  }
  fngl_init(real_get_proc);
  OVERRIDE(SDL_GL_SetAttribute, fn_GL_SetAttribute);
  OVERRIDE(SDL_GL_GetAttribute, fn_GL_GetAttribute);
  OVERRIDE(SDL_GL_GetProcAddress, fn_GL_GetProcAddress);
  OVERRIDE(SDL_GL_SwapWindow, fn_GL_SwapWindow);
  OVERRIDE(SDL_StartTextInput, fn_StartTextInput);
  OVERRIDE(SDL_StartTextInputWithProperties, fn_StartTextInputWithProperties);
  OVERRIDE(SDL_StopTextInput, fn_StopTextInput);
  OVERRIDE(SDL_RumbleGamepad, fn_RumbleGamepad);
  OVERRIDE(SDL_OpenAudioDevice, fn_OpenAudioDevice);
  OVERRIDE(SDL_OpenAudioDeviceStream, fn_OpenAudioDeviceStream);
  // the event functions: scaling, the trace, and libfnative's main-thread work (game_poll)
  OVERRIDE(SDL_PollEvent, fn_PollEvent);
  OVERRIDE(SDL_WaitEvent, fn_WaitEvent);
  OVERRIDE(SDL_WaitEventTimeout, fn_WaitEventTimeout);
  OVERRIDE(SDL_PeepEvents, fn_PeepEvents);
  if (fnvk_active() && !strcmp(real.SDL_GetPlatform(), "Android")) {
    vk_android = 1;
    fnvk_set_window_size_fn(vk_window_size);
    OVERRIDE(SDL_CreateWindow, fn_vk_CreateWindow);
    OVERRIDE(SDL_CreateWindowWithProperties, fn_vk_CreateWindowWithProperties);
    OVERRIDE(SDL_GL_CreateContext, fn_vk_GL_CreateContext);
    OVERRIDE(SDL_GL_MakeCurrent, fn_vk_GL_MakeCurrent);
    OVERRIDE(SDL_GL_GetCurrentWindow, fn_vk_GL_GetCurrentWindow);
    OVERRIDE(SDL_GL_GetCurrentContext, fn_vk_GL_GetCurrentContext);
    OVERRIDE(SDL_GL_DestroyContext, fn_vk_GL_DestroyContext);
    OVERRIDE(SDL_GL_SetSwapInterval, fn_vk_GL_SetSwapInterval);
    OVERRIDE(SDL_GL_GetSwapInterval, fn_vk_GL_GetSwapInterval);
    OVERRIDE(SDL_GL_LoadLibrary, fn_vk_GL_LoadLibrary);
    OVERRIDE(SDL_GL_UnloadLibrary, fn_vk_GL_UnloadLibrary);
    OVERRIDE(SDL_GL_ExtensionSupported, fn_vk_GL_ExtensionSupported);
    FNSDL_LOG("fnsdl", "Vulkan renderer: window without OpenGL, own swapchain");
  }
  if (!fnvk_active() || fn_upscale_active()) {
    // GL: always, the render scale can be switched on while the game runs (at 100 % these change nothing);
    // Vulkan: only with a render scale from the start (the present scales, bilinear)
    OVERRIDE(SDL_GetWindowSizeInPixels, fn_GetWindowSizeInPixels);
    OVERRIDE(SDL_GetWindowSize, fn_GetWindowSize);
    OVERRIDE(SDL_GetMouseState, fn_GetMouseState);
    OVERRIDE(SDL_WarpMouseInWindow, fn_WarpMouseInWindow);
    if (!fnvk_active()) {  // the GL upscaler
      OVERRIDE(SDL_GL_CreateContext, fn_GL_CreateContext);
      OVERRIDE(SDL_GL_MakeCurrent, fn_GL_MakeCurrent);
    }
  }
  FNSDL_LOG("fnsdl", "SDL %s jump table: game %u bytes, ours %zu bytes", path, tablesize,
          sizeof(jump_table));
  return 0;
}
