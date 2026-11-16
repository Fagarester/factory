// Loads the game binary and runs its main(). Shared by the test executable
// and the Android entry point.
#include <stdlib.h>

#include "fn.h"

extern char **environ;

int fn_run(const char *game, int argc, char **argv) {
  fn_load(&fn_img, game);
  typedef int (*mainfn)(int, char **, char **);
  mainfn game_main = (mainfn)fn_find_symbol(&fn_img, "main");
  if (!game_main) fn_die("symbol main not found");
  argv[0] = fn_img.path;  // the game finds its data relative to argv[0]
  fn_tls_current();
  fn_run_init(&fn_img, argc, argv, environ);
  return game_main(argc, argv, environ);
}
