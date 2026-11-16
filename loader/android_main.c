// Entry point for SDLActivity: SDL_main(argc, argv) in libfnative.so.
// argv[1] = game binary, argv[2] = log file, argv[3..] = game arguments.
#include <android/log.h>
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
  const char *game = argv[1];
  argv[first - 1] = argv[1];  // game argv: [game, args...]
  int r = fn_run(game, argc - (first - 1), argv + (first - 1));
  // The game process (":game") ends with the game: the image cannot be loaded twice.
  exit(r);
}
