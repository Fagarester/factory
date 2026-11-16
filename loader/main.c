// fnative: runs the official Linux ARM64 Factorio binary on bionic.
// Usage: fnative /path/to/bin/arm64/factorio [factorio args...]
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "fn.h"

extern char **environ;

int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr, "usage: %s /path/to/factorio [args...]\n", argv[0]);
    return 2;
  }
  fn_load(&fn_img, argv[1]);
  typedef int (*mainfn)(int, char **, char **);
  mainfn game_main = (mainfn)fn_find_symbol(&fn_img, "main");
  if (!game_main) fn_die("symbol main not found");

  int gargc = argc - 1;
  char **gargv = argv + 1;
  gargv[0] = fn_img.path;
  fn_tls_current();
  fn_run_init(&fn_img, gargc, gargv, environ);
  exit(game_main(gargc, gargv, environ));
}
