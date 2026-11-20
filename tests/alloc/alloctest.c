// Host test (aarch64 Linux) of loader/alloc.c: mimalloc for the game's malloc family, libc's blocks handed back.
//   cc -O1 -I loader -I third_party/mimalloc/include -o /tmp/alloctest tests/alloc/alloctest.c loader/alloc.c third_party/mimalloc/src/static.c -lpthread && /tmp/alloctest
#define _GNU_SOURCE
#include "fn.h"

#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void fn_log(const char *fmt, ...) {
  va_list a;
  va_start(a, fmt);
  vprintf(fmt, a);
  va_end(a);
  putchar('\n');
}

int main(void) {
  int fails = 0;
  fn_alloc_init();
  void (*xfree)(void *) = (void (*)(void *))fn_alloc_lookup("free");
  void *(*xmalloc)(size_t) = (void *(*)(size_t))fn_alloc_lookup("malloc");
  void *(*xcalloc)(size_t, size_t) = (void *(*)(size_t, size_t))fn_alloc_lookup("calloc");
  void *(*xrealloc)(void *, size_t) = (void *(*)(void *, size_t))fn_alloc_lookup("realloc");
  int (*xmemalign)(void **, size_t, size_t) = (int (*)(void **, size_t, size_t))fn_alloc_lookup("posix_memalign");
  if (!xfree || !xmalloc || !xcalloc || !xrealloc || !xmemalign) { puts("FAIL lookup"); return 1; }
  char *moved = xrealloc(strdup("libc's block"), 100);  // a foreign block moved in
  if (strcmp(moved, "libc's block")) { puts("FAIL realloc foreign"); fails++; }
  xfree(moved);
  xfree(strdup("libc's block, freed by libc"));
  int *z = xcalloc(1000, sizeof *z);
  for (int i = 0; i < 1000; i++) if (z[i]) { puts("FAIL calloc"); fails++; break; }
  xfree(z);
  for (int i = 0; i < 200000; i++) {
    char *p = xmalloc(i % 4096 + 1);
    p[0] = 1;
    p = xrealloc(p, i % 8192 + 1);
    xfree(p);
  }
  void *al = NULL;
  if (xmemalign(&al, 4096, 100) || ((uintptr_t)al & 4095)) { puts("FAIL memalign"); fails++; }
  xfree(al);
  xfree(NULL);
  setenv("FN_MALLOC", "system", 1);
  fn_alloc_init();
  if (fn_alloc_lookup("malloc")) { puts("FAIL FN_MALLOC=system"); fails++; }
  puts(fails ? "FAILED" : "all OK");
  return fails != 0;
}
