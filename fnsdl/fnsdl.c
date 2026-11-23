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
#include "fngl_prof.h"
#include "vk/fnvk.h"
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

// ---- text input (the game's text fields): FNSDL_TEXT_TRACE=1 logs the first 40 calls of each, to see how the game asks for the
// screen keyboard (a call while already active brings back a keyboard the player has just put away).
static unsigned text_calls[3];  // start, start with properties, stop
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
  if (getenv("FNSDL_TEXT_TRACE") && text_calls[0]++ < 40)
    fprintf(stderr, "[fnsdl] SDL_StartTextInput #%u (active %d, keyboard %d)\n", text_calls[0],
            real.SDL_TextInputActive(w), real.SDL_ScreenKeyboardShown(w));
  report_text(1);
  return real.SDL_StartTextInput(w);
}
static bool SDLCALL fn_StartTextInputWithProperties(SDL_Window *w, SDL_PropertiesID props) {
  if (getenv("FNSDL_TEXT_TRACE") && text_calls[1]++ < 40)
    fprintf(stderr, "[fnsdl] SDL_StartTextInputWithProperties #%u (active %d, keyboard %d)\n", text_calls[1],
            real.SDL_TextInputActive(w), real.SDL_ScreenKeyboardShown(w));
  report_text(1);
  return real.SDL_StartTextInputWithProperties(w, props);
}
static bool SDLCALL fn_StopTextInput(SDL_Window *w) {
  if (getenv("FNSDL_TEXT_TRACE") && text_calls[2]++ < 40)
    fprintf(stderr, "[fnsdl] SDL_StopTextInput #%u\n", text_calls[2]);
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
  else if (fn_upscale_active()) fn_upscale_size(w, h);  // the game renders smaller; fnvk scales at present
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
static void graphics_live(SDL_Window *win) {
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
  const char *test = getenv("FN_UPSCALE_TEST");
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
  if (!real.SDL_GetWindowSizeInPixels(win, &w, &h) || !fn_upscale_apply(w, h)) return;
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

static void crash_report_install(void);
static bool SDLCALL fn_GL_SwapWindow(SDL_Window *win) {
  crash_report_install();  // after the game's crash handler: ours reports first, then calls it
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
      unsigned char *vpx = vulkan ? malloc((size_t)w * h * 4) : NULL;
      if (vpx && fnvk_read_window(w, h, vpx)) {
        char path[512];
        snprintf(path, sizeof path, "%s-%ld.png", shot, frame_no);
        write_png(path, w, h, vpx);
        fprintf(stderr, "[fnsdl] screenshot %s (%dx%d, Vulkan)\n", path, w, h);
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
  limit_fps();
  if (fngl_prof_enabled()) fngl_prof_swap_begin();
  bool r = true;
  if (getenv("FNSDL_CRASH_TEST") && frame_no == 5) *(volatile int *)(uintptr_t)8 = 1;  // tests the crash report
  if (vulkan) {
    if (vk_android)
      fnvk_set_native_window(real.SDL_GetPointerProperty(real.SDL_GetWindowProperties(win ? win : vk_win),
                                                         SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER, NULL));
    fnvk_swap();
  }
  else r = real.SDL_GL_SwapWindow(win);
  if (fngl_prof_enabled()) fngl_prof_swap_end();
  if (up_w) fn_upscale_restore_state();
  if (!vulkan) {
    graphics_live(win);  // live render scale (GL only: Vulkan has no GL context; its scale is set at start)
    limit_frames_in_flight();
  }
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
// FNSDL_EVENT_TRACE=1: the input events the game gets (mouse buttons, focus, screen keyboard) into the log,
// to see whether clicks reach the game (cursor clicks while the screen keyboard is up, 2026-11-19).
static int event_trace = -1;
static void trace_event(const SDL_Event *e) {
  if (event_trace < 0) event_trace = getenv("FNSDL_EVENT_TRACE") != NULL;
  if (!event_trace) return;
  switch (e->type) {
    case SDL_EVENT_MOUSE_BUTTON_DOWN:
    case SDL_EVENT_MOUSE_BUTTON_UP:
      fprintf(stderr, "[fnsdl] event mouse button %d %s at %.0f,%.0f (keyboard %d)\n", e->button.button,
              e->type == SDL_EVENT_MOUSE_BUTTON_DOWN ? "down" : "up", e->button.x, e->button.y,
              real.SDL_ScreenKeyboardShown(real.SDL_GetWindowFromID(e->button.windowID)));
      break;
    case SDL_EVENT_WINDOW_FOCUS_GAINED: fprintf(stderr, "[fnsdl] event window focus gained\n"); break;
    case SDL_EVENT_WINDOW_FOCUS_LOST: fprintf(stderr, "[fnsdl] event window focus lost\n"); break;
    case SDL_EVENT_WINDOW_MOUSE_ENTER: fprintf(stderr, "[fnsdl] event mouse enter\n"); break;
    case SDL_EVENT_WINDOW_MOUSE_LEAVE: fprintf(stderr, "[fnsdl] event mouse leave\n"); break;
    case SDL_EVENT_SCREEN_KEYBOARD_SHOWN: fprintf(stderr, "[fnsdl] event screen keyboard shown\n"); break;
    case SDL_EVENT_SCREEN_KEYBOARD_HIDDEN: fprintf(stderr, "[fnsdl] event screen keyboard hidden\n"); break;
    case SDL_EVENT_TEXT_INPUT: fprintf(stderr, "[fnsdl] event text input\n"); break;
    default: break;
  }
}

static void scale_event(SDL_Event *e) {
  trace_event(e);
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
static void game_poll(void) {
  static void (*poll)(void);
  static int tried;
  if (!tried) {
    tried = 1;
    void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);
    if (h) poll = (void (*)(void))dlsym(h, "fn_game_poll");
  }
  if (poll) poll();
}

static bool SDLCALL fn_PollEvent(SDL_Event *e) {
  game_poll();
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
  game_poll();
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


// ---- crash report: fault address and call chain (library + offset) before the game's own handler runs
#include <signal.h>
#include <unwind.h>
#include <ucontext.h>
static struct sigaction prev_segv, prev_bus;
static int bt_n;
static _Unwind_Reason_Code bt_step(struct _Unwind_Context *c, void *arg) {
  (void)arg;
  uintptr_t pc = _Unwind_GetIP(c);
  if (!pc || bt_n++ > 40) return _URC_END_OF_STACK;
  Dl_info di;
  if (dladdr((void *)pc, &di) && di.dli_fname)
    fprintf(stderr, "[fnsdl]   #%d %s+0x%lx (%s)\n", bt_n, di.dli_fname, (unsigned long)(pc - (uintptr_t)di.dli_fbase),
            di.dli_sname ? di.dli_sname : "?");
  else
    fprintf(stderr, "[fnsdl]   #%d 0x%lx\n", bt_n, (unsigned long)pc);
  return _URC_NO_REASON;
}
static void crash_handler(int sig, siginfo_t *si, void *uc) {
  static int once;
  if (!once++) {
    ucontext_t *u = uc;
    uintptr_t pc = u->uc_mcontext.pc, lr = u->uc_mcontext.regs[30];
    Dl_info di;
    fprintf(stderr, "[fnsdl] signal %d at address %p, pc 0x%lx", sig, si->si_addr, (unsigned long)pc);
    if (dladdr((void *)pc, &di) && di.dli_fname)
      fprintf(stderr, " = %s+0x%lx (%s)", di.dli_fname, (unsigned long)(pc - (uintptr_t)di.dli_fbase),
              di.dli_sname ? di.dli_sname : "?");
    fprintf(stderr, ", lr 0x%lx", (unsigned long)lr);
    if (dladdr((void *)lr, &di) && di.dli_fname)
      fprintf(stderr, " = %s+0x%lx (%s)", di.dli_fname, (unsigned long)(lr - (uintptr_t)di.dli_fbase),
              di.dli_sname ? di.dli_sname : "?");
    fprintf(stderr, "\n");
    _Unwind_Backtrace(bt_step, NULL);
    fflush(stderr);
  }
  struct sigaction *p = sig == SIGBUS ? &prev_bus : &prev_segv;
  if (p->sa_flags & SA_SIGINFO) {
    if (p->sa_sigaction) p->sa_sigaction(sig, si, uc);
  } else if (p->sa_handler && p->sa_handler != SIG_DFL && p->sa_handler != SIG_IGN)
    p->sa_handler(sig);
  else {
    signal(sig, SIG_DFL);
    raise(sig);
  }
}
static void crash_report_install(void) {
  struct sigaction cur;
  if (sigaction(SIGSEGV, NULL, &cur) == 0 && cur.sa_sigaction == crash_handler) return;
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = crash_handler;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigaction(SIGSEGV, &sa, &prev_segv);
  sigaction(SIGBUS, &sa, &prev_bus);
}

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
  crash_report_install();
  fngl_init(real_get_proc);
  OVERRIDE(SDL_GL_SetAttribute, fn_GL_SetAttribute);
  OVERRIDE(SDL_GL_GetAttribute, fn_GL_GetAttribute);
  OVERRIDE(SDL_GL_GetProcAddress, fn_GL_GetProcAddress);
  OVERRIDE(SDL_GL_SwapWindow, fn_GL_SwapWindow);
  OVERRIDE(SDL_StartTextInput, fn_StartTextInput);
  OVERRIDE(SDL_StartTextInputWithProperties, fn_StartTextInputWithProperties);
  OVERRIDE(SDL_StopTextInput, fn_StopTextInput);
  OVERRIDE(SDL_RumbleGamepad, fn_RumbleGamepad);
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
    fprintf(stderr, "[fnsdl] Vulkan renderer: window without OpenGL, own swapchain\n");
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
  fprintf(stderr, "[fnsdl] SDL %s jump table: game %u bytes, ours %zu bytes\n", path, tablesize,
          sizeof(jump_table));
  return 0;
}
