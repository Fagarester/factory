// Game functions replaced at run time by libfnsdl (the native Vulkan renderer, fnsdl/nvk): the function's first
// instruction becomes a branch to a trampoline that jumps to the replacement. Virtual and direct calls both land
// there. Done after loading (the code is read-only and executable by then): the pages are made writable for the
// write and executable again. Exported for libfnsdl (dlsym), like fn_stat_frame.
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "fn.h"

static int set_prot(void *p, size_t n, int prot) {
  uintptr_t page = (uintptr_t)sysconf(_SC_PAGESIZE);
  uintptr_t a = (uintptr_t)p & ~(page - 1), e = ((uintptr_t)p + n + page - 1) & ~(page - 1);
  return mprotect((void *)a, e - a, prot);
}

// Address of a game symbol (mangled name), 0 when the game has none.
__attribute__((visibility("default"))) void *fn_game_symbol(const char *name) {
  return (void *)fn_find_symbol(&fn_img, name);
}

// Size in bytes of a game symbol (st_size), 0 when unknown.
__attribute__((visibility("default"))) size_t fn_game_symbol_size(const char *name) {
  for (size_t i = 0; i < fn_img.nsyms; i++) {
    const Elf64_Sym *s = &fn_img.symtab[i];
    if (s->st_value && !strcmp(fn_img.strtab + s->st_name, name)) return s->st_size;
  }
  return 0;
}

// Calls fn(name, address, size, ctx) for every defined game symbol whose name starts with `prefix`
// (local symbols too: lambdas, static functions). Returns the number of calls.
__attribute__((visibility("default"))) int fn_game_symbols(const char *prefix,
                                                           void (*fn)(const char *, void *, size_t, void *), void *ctx) {
  size_t n = strlen(prefix);
  int count = 0;
  for (size_t i = 0; i < fn_img.nsyms; i++) {
    const Elf64_Sym *s = &fn_img.symtab[i];
    const char *name = fn_img.strtab + s->st_name;
    if (s->st_value && !strncmp(name, prefix, n)) {
      fn(name, fn_img.base + s->st_value, s->st_size, ctx);
      count++;
    }
  }
  return count;
}

// The game function at `fn` jumps to `to` from now on. Returns 0 on success; *orig (if not NULL) gets a callable
// copy of the old function (its first instruction moved to a trampoline), or NULL when that instruction is
// pc-relative and cannot be moved.
__attribute__((visibility("default"))) int fn_game_redirect_at(void *fn, void *to, void **orig) {
  fn_image *img = &fn_img;
  if (orig) *orig = NULL;
  if ((uint8_t *)fn < img->base || (uint8_t *)fn >= img->base + img->span) return -1;
  if (img->tramp_used + 48 > img->tramp_size) return -2;
  uint32_t *site = fn, w = *site;
  int pc_rel = (w & 0x1f000000) == 0x10000000 || (w & 0x7c000000) == 0x14000000 || (w & 0x7e000000) == 0x34000000 ||
               (w & 0x7e000000) == 0x36000000 || (w & 0xff000010) == 0x54000000 || (w & 0x3b000000) == 0x18000000;
  size_t at = (img->tramp_used + 7) & ~(size_t)7;
  uint32_t *t = (uint32_t *)(img->tramp + at);
  if (set_prot(t, 48, PROT_READ | PROT_WRITE)) return -3;
  // t[0..3]: ldr x16, lit; br x16; lit = to. t[4..5]: the old function: its first instruction, b fn+4.
  t[0] = 0x58000050u;  // ldr x16, #8
  t[1] = 0xd61f0200u;  // br x16
  *(uint64_t *)&t[2] = (uint64_t)(uintptr_t)to;
  t[4] = w;
  intptr_t d = (intptr_t)((uintptr_t)site + 4 - (uintptr_t)&t[5]);
  t[5] = 0x14000000u | ((uint32_t)(d >> 2) & 0x03ffffff);
  img->tramp_used = at + 24;
  __builtin___clear_cache((char *)t, (char *)(t + 6));
  set_prot(t, 48, PROT_READ | PROT_EXEC);
  if (set_prot(site, 4, PROT_READ | PROT_WRITE)) return -4;
  intptr_t e = (intptr_t)((uintptr_t)t - (uintptr_t)site);
  __atomic_store_n(site, 0x14000000u | ((uint32_t)(e >> 2) & 0x03ffffff), __ATOMIC_RELEASE);  // b trampoline
  __builtin___clear_cache((char *)site, (char *)(site + 1));
  set_prot(site, 4, PROT_READ | PROT_EXEC);
  if (orig && !pc_rel) *orig = &t[4];
  return 0;
}

__attribute__((visibility("default"))) int fn_game_redirect(const char *name, void *to, void **orig) {
  void *fn = fn_game_symbol(name);
  if (!fn) {
    fn_log("redirect: no %s in the game", name);
    if (orig) *orig = NULL;
    return -1;
  }
  return fn_game_redirect_at(fn, to, orig);
}

// Writes a pointer into the game's read-only data (a vtable slot).
__attribute__((visibility("default"))) int fn_game_poke(void *at, void *value) {
  if (set_prot(at, 8, PROT_READ | PROT_WRITE)) return -1;
  *(void **)at = value;
  return set_prot(at, 8, PROT_READ);
}
