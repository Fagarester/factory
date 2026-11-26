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
  char addr[32];  // the proot tests in libfnsdl (FN_TEST_MINIMIZE) read the tick counter here: no libfnative.so in proot
  snprintf(addr, sizeof addr, "%p", (void *)&fn_tick_count);
  setenv("FN_TICK_COUNTER", addr, 1);
  int r = fn_run(argv[1], argc - 1, argv + 1);
  fn_log("game ticks counted: %llu", (unsigned long long)fn_tick_count);
  exit(r);
}
