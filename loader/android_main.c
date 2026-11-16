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

  // libfnsdl.so sits next to this library.
  Dl_info info;
  char dir[PATH_MAX], path[PATH_MAX];
  dladdr((void *)SDL_main, &info);
  snprintf(dir, sizeof dir, "%s", info.dli_fname);
  snprintf(path, sizeof path, "%s/libfnsdl.so", dirname(dir));
  setenv("SDL3_DYNAMIC_API", path, 1);
  setenv("FN_SDL_LIBRARY", "libSDL3.so", 1);
  fn_log("SDL3_DYNAMIC_API=%s", path);

  const char *game = argv[1];
  argv[2] = argv[1];  // game argv starts at index 2: [game, args...]
  int r = fn_run(game, argc - 2, argv + 2);
  // The game process (":game") ends with the game: the image cannot be loaded twice.
  exit(r);
}
