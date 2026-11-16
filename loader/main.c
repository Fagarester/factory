// fnative: runs the official Linux ARM64 Factorio binary on bionic.
// Usage: fnative /path/to/bin/arm64/factorio [factorio args...]
#include <stdio.h>
#include <stdlib.h>

#include "fn.h"

int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr, "usage: %s /path/to/factorio [args...]\n", argv[0]);
    return 2;
  }
  exit(fn_run(argv[1], argc - 1, argv + 1));
}
