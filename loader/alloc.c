// The game's malloc family from mimalloc (third_party/mimalloc 2.2.4) instead of Android's allocator (Scudo); off in
// 0.195 only (FN_MALLOC=system switches it off):
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
  use_mimalloc = !(m && !strcmp(m, "system"));  // on unless FN_MALLOC=system (off only in 0.195)
  if (use_mimalloc) {
    sys_free = (void (*)(void *))dlsym(RTLD_DEFAULT, "free");
    sys_usable = (size_t (*)(const void *))dlsym(RTLD_DEFAULT, "malloc_usable_size");
    if (!sys_free) use_mimalloc = 0;
  }
  // Freed memory back to the system at once (default: after 10 ms, but only when the same heap allocates again: the
  // game's sprite loader threads end after loading and their freed gigabytes stayed). proot, 2.1.19 with Space Age,
  // 2026-11-26: main menu 3.8 GB -> 1.07 GB (system allocator 1.1 GB); --benchmark 2m_final 600 ticks: 22.6 ms
  // (default) / 22.8 ms (this) / 25.0 ms (system) a tick, peak 3.9 GB / 3.9 GB / 5.1 GB. MIMALLOC_PURGE_DELAY overrides.
  if (use_mimalloc && !getenv("MIMALLOC_PURGE_DELAY")) mi_option_set(mi_option_purge_delay, 0);
  fn_log("allocator: %s%s", use_mimalloc ? "mimalloc" : "system",
         use_mimalloc ? (getenv("MIMALLOC_PURGE_DELAY") ? " (purge delay from the environment)" : " (freed memory returned at once)") : "");
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
