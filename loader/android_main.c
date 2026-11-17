// Entry point for SDLActivity: SDL_main(argc, argv) in libfnative.so.
// argv[1] = game binary, argv[2] = log file, argv[3..] = game arguments.
#define _GNU_SOURCE
#include <android/log.h>
#include <errno.h>
#include <sched.h>
#include <dlfcn.h>
#include <libgen.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "fn.h"

__attribute__((visibility("default"))) int SDL_main(int argc, char **argv) {
  if (argc < 3) {
    __android_log_print(ANDROID_LOG_ERROR, "fnative", "SDL_main: need <game> <log> [args]");
    return 2;
  }
  // Loader and game stderr/stdout go to the log file.
  if (freopen(argv[2], "w", stderr)) setvbuf(stderr, NULL, _IOLBF, 0);
  dup2(fileno(stderr), 1);

  // Optional env.txt next to the log: KEY=VALUE lines for test switches.
  {
    char envpath[PATH_MAX], logdir[PATH_MAX];
    snprintf(logdir, sizeof logdir, "%s", argv[2]);
    snprintf(envpath, sizeof envpath, "%s/env.txt", dirname(logdir));
    FILE *f = fopen(envpath, "r");
    if (f) {
      char line[1024];
      while (fgets(line, sizeof line, f)) {
        line[strcspn(line, "\r\n")] = 0;
        char *eq = strchr(line, '=');
        if (line[0] == '#' || !eq) continue;
        *eq = 0;
        setenv(line, eq + 1, 1);
        fn_log("env.txt: %s=%s", line, eq + 1);
      }
      fclose(f);
    }
  }

  // libfnsdl.so sits next to this library.
  Dl_info info;
  char dir[PATH_MAX], path[PATH_MAX];
  dladdr((void *)SDL_main, &info);
  snprintf(dir, sizeof dir, "%s", info.dli_fname);
  snprintf(path, sizeof path, "%s/libfnsdl.so", dirname(dir));
  setenv("SDL3_DYNAMIC_API", path, 1);
  setenv("FN_SDL_LIBRARY", "libSDL3.so", 1);
  fn_log("SDL3_DYNAMIC_API=%s", path);

  // Launcher settings: "--fn-env KEY=VALUE" pairs right after the log path.
  // Applied after env.txt, so a setting in the UI wins over the text field.
  int first = 3;
  while (first + 1 < argc && !strcmp(argv[first], "--fn-env")) {
    char *kv = argv[first + 1], *eq = strchr(kv, '=');
    if (eq) {
      *eq = 0;
      setenv(kv, eq + 1, 1);
      fn_log("setting: %s=%s", kv, eq + 1);
      *eq = '=';
    }
    first += 2;
  }
  // FN_ANGLE=1: the system ANGLE (GLES over Vulkan) instead of the GPU's GLES
  // driver, if the app may load it; otherwise the normal driver is used.
  {
    const char *angle = getenv("FN_ANGLE");
    if (angle && !strcmp(angle, "1")) {
      static const char egl_path[] = "/system/lib64/libEGL_angle.so", gles_path[] = "/system/lib64/libGLESv2_angle.so";
      void *e = dlopen(egl_path, RTLD_NOW | RTLD_LOCAL), *g = e ? dlopen(gles_path, RTLD_NOW | RTLD_LOCAL) : NULL;
      if (e && g) {
        setenv("SDL_EGL_LIBRARY", egl_path, 1);
        setenv("SDL_OPENGL_LIBRARY", gles_path, 1);
        setenv("FN_GLES_LIBRARY", gles_path, 1);
        fn_log("ANGLE: using %s", gles_path);
      } else {
        fn_log("ANGLE: not available (%s), using the GPU's GLES driver", dlerror());
      }
    }
  }

  // FN_CPU_MASK=<hex>: run the game only on these cores. Set on this thread
  // before the game starts, so every thread the game creates inherits it.
  {
    const char *mask = getenv("FN_CPU_MASK");
    unsigned long long bits = mask && *mask ? strtoull(mask, NULL, 16) : 0;
    if (bits) {
      cpu_set_t set;
      CPU_ZERO(&set);
      for (int c = 0; c < 64; c++)
        if (bits >> c & 1) CPU_SET(c, &set);
      int r = sched_setaffinity(0, sizeof set, &set);
      fn_log("cores: mask 0x%llx -> %s", bits, r == 0 ? "set" : strerror(errno));
    }
  }

  const char *game = argv[1];
  argv[first - 1] = argv[1];  // game argv: [game, args...]
  int r = fn_run(game, argc - (first - 1), argv + (first - 1));
  // The game process (":game") ends with the game: the image cannot be loaded twice.
  exit(r);
}
