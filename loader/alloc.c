// The game's malloc family from mimalloc (third_party/mimalloc 2.2.4) instead of Android's allocator (Scudo):
// measured in FNative Lab (LAB.md there; 8 Elite, a megabase, two runs): 4–6 % less CPU time per game tick, about
// +1 % UPS near the 60 cap, more on bases that do not reach 60. The launcher's settings switch sets FN_MALLOC=mimalloc / system (it wins over env.txt).
// Memory the game frees may come from libc itself (strdup, getcwd(NULL), scandir…): such blocks go back to libc.
#include "fn.h"

#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

#include "../third_party/mimalloc/include/mimalloc.h"

static int use_mimalloc;
static void (*sys_free)(void *);
static size_t (*sys_usable)(const void *);

static void a_free(void *p) {
  if (!p) return;
  if (mi_is_in_heap_region(p)) mi_free(p);
  else sys_free(p);
}

static void *a_malloc(size_t n) { return mi_malloc(n); }
static void *a_calloc(size_t n, size_t s) { return mi_calloc(n, s); }
static int a_posix_memalign(void **out, size_t align, size_t n) { return mi_posix_memalign(out, align, n); }

static void *a_realloc(void *p, size_t n) {
  if (!p || mi_is_in_heap_region(p)) return mi_realloc(p, n);
  size_t old = sys_usable ? sys_usable(p) : 0;  // libc's block: moved into mimalloc
  void *q = mi_malloc(n);
  if (!q) return NULL;
  memcpy(q, p, old < n ? old : n);
  sys_free(p);
  return q;
}

void fn_alloc_init(void) {
  const char *m = getenv("FN_MALLOC");
  use_mimalloc = !(m && !strcmp(m, "system"));
  if (use_mimalloc) {
    sys_free = (void (*)(void *))dlsym(RTLD_DEFAULT, "free");
    sys_usable = (size_t (*)(const void *))dlsym(RTLD_DEFAULT, "malloc_usable_size");
    if (!sys_free) use_mimalloc = 0;
  }
  fn_log("allocator: %s", use_mimalloc ? "mimalloc" : "system");
}

// The game's allocator imports, or NULL (then the shim and bionic answer).
void *fn_alloc_lookup(const char *name) {
  if (!use_mimalloc) return NULL;
  if (!strcmp(name, "malloc")) return (void *)a_malloc;
  if (!strcmp(name, "free")) return (void *)a_free;
  if (!strcmp(name, "calloc")) return (void *)a_calloc;
  if (!strcmp(name, "realloc")) return (void *)a_realloc;
  if (!strcmp(name, "posix_memalign")) return (void *)a_posix_memalign;
  return NULL;
}
