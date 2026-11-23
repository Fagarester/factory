// Maps a glibc aarch64 PIE (Factorio) into a bionic process.
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "fn.h"

#define TRAMP_SIZE (1u << 20)

fn_image fn_img;

static uintptr_t page_down(uintptr_t v, size_t pg) { return v & ~(uintptr_t)(pg - 1); }
static uintptr_t page_up(uintptr_t v, size_t pg) { return (v + pg - 1) & ~(uintptr_t)(pg - 1); }

static int prot_of(uint32_t f) {
  return ((f & PF_R) ? PROT_READ : 0) | ((f & PF_W) ? PROT_WRITE : 0) | ((f & PF_X) ? PROT_EXEC : 0);
}

static const Elf64_Shdr *find_section(const fn_image *img, const char *name) {
  const Elf64_Ehdr *eh = (const Elf64_Ehdr *)img->file;
  const Elf64_Shdr *sh = (const Elf64_Shdr *)(img->file + eh->e_shoff);
  const char *names = (const char *)img->file + sh[eh->e_shstrndx].sh_offset;
  for (int i = 0; i < eh->e_shnum; i++)
    if (!strcmp(names + sh[i].sh_name, name)) return &sh[i];
  return NULL;
}

static int cmp_sym(const void *a, const void *b) {
  const Elf64_Sym *x = *(const Elf64_Sym *const *)a, *y = *(const Elf64_Sym *const *)b;
  return x->st_value < y->st_value ? -1 : x->st_value > y->st_value;
}

static void load_symtab(fn_image *img) {
  const Elf64_Shdr *st = find_section(img, ".symtab");
  if (!st) return;
  const Elf64_Ehdr *eh = (const Elf64_Ehdr *)img->file;
  const Elf64_Shdr *sh = (const Elf64_Shdr *)(img->file + eh->e_shoff);
  img->symtab = (const Elf64_Sym *)(img->file + st->sh_offset);
  img->nsyms = st->sh_size / sizeof(Elf64_Sym);
  img->strtab = (const char *)img->file + sh[st->sh_link].sh_offset;
  img->funcs = malloc(img->nsyms * sizeof(*img->funcs));
  for (size_t i = 0; i < img->nsyms; i++) {
    const Elf64_Sym *s = &img->symtab[i];
    if (ELF64_ST_TYPE(s->st_info) == STT_FUNC && s->st_value) img->funcs[img->nfuncs++] = s;
  }
  qsort(img->funcs, img->nfuncs, sizeof(*img->funcs), cmp_sym);
}

uintptr_t fn_find_symbol(const fn_image *img, const char *name) {
  for (size_t i = 0; i < img->nsyms; i++) {
    const Elf64_Sym *s = &img->symtab[i];
    if (s->st_value && !strcmp(img->strtab + s->st_name, name)) return (uintptr_t)img->base + s->st_value;
  }
  return 0;
}

const Elf64_Sym *fn_symbol_at(const fn_image *img, uintptr_t addr) {
  if (addr < (uintptr_t)img->base || addr >= (uintptr_t)img->base + img->span || !img->nfuncs) return NULL;
  uintptr_t v = addr - (uintptr_t)img->base;
  size_t lo = 0, hi = img->nfuncs;
  while (hi - lo > 1) {
    size_t mid = (lo + hi) / 2;
    if (img->funcs[mid]->st_value <= v) lo = mid; else hi = mid;
  }
  const Elf64_Sym *s = img->funcs[lo];
  return s->st_value <= v ? s : NULL;
}

// Stub for imports nobody provides: logs the name on call.
static void unresolved_call(const char *name) { fn_die("call of unresolved import %s", name); }

static void *make_unresolved_stub(fn_image *img, const char *name) {
  if (img->tramp_used + 32 > img->tramp_size) fn_die("trampoline area full");
  uint32_t *p = (uint32_t *)(img->tramp + img->tramp_used);
  uint64_t *lit = (uint64_t *)(p + 4);
  img->tramp_used += 32;
  p[0] = 0x58000090;  // ldr x16, #16 -> lit[0]
  p[1] = 0x580000a0;  // ldr x0, #20  -> lit[1]
  p[2] = 0xd61f0200;  // br x16
  p[3] = 0xd503201f;  // nop
  lit[0] = (uint64_t)(uintptr_t)unresolved_call;
  lit[1] = (uint64_t)(uintptr_t)name;
  return p;
}

static void *resolve(fn_image *img, const Elf64_Sym *sym, const char *name, int *missing) {
  void *p = fn_game_lookup(name);  // sendto (network ping), execv (restart) — restart.c
  if (!p) p = fn_alloc_lookup(name);  // mimalloc for the malloc family (alloc.c)
  if (!p) p = fn_shim_lookup(name);
  if (!p) p = fn_bionic_lookup(name);
  if (p) return p;
  if (ELF64_ST_BIND(sym->st_info) == STB_WEAK) return NULL;
  fn_log("unresolved import: %s", name);
  (*missing)++;
  return make_unresolved_stub(img, name);
}

static void relocate(fn_image *img) {
  Elf64_Rela *rela = NULL, *jmprel = NULL;
  size_t relasz = 0, jmprelsz = 0;
  const Elf64_Sym *dynsym = NULL;
  const char *dynstr = NULL;
  for (Elf64_Dyn *d = img->dyn; d->d_tag != DT_NULL; d++) {
    switch (d->d_tag) {
      case DT_RELA: rela = (Elf64_Rela *)(img->base + d->d_un.d_ptr); break;
      case DT_RELASZ: relasz = d->d_un.d_val; break;
      case DT_JMPREL: jmprel = (Elf64_Rela *)(img->base + d->d_un.d_ptr); break;
      case DT_PLTRELSZ: jmprelsz = d->d_un.d_val; break;
      case DT_SYMTAB: dynsym = (const Elf64_Sym *)(img->base + d->d_un.d_ptr); break;
      case DT_STRTAB: dynstr = (const char *)(img->base + d->d_un.d_ptr); break;
    }
  }
  int missing = 0;
  size_t counts[2] = {0, 0};
  for (int pass = 0; pass < 2; pass++) {
    Elf64_Rela *r = pass ? jmprel : rela;
    size_t n = (pass ? jmprelsz : relasz) / sizeof(Elf64_Rela);
    for (size_t i = 0; i < n; i++) {
      uint64_t *where = (uint64_t *)(img->base + r[i].r_offset);
      uint32_t type = ELF64_R_TYPE(r[i].r_info), si = ELF64_R_SYM(r[i].r_info);
      const Elf64_Sym *sym = si ? &dynsym[si] : NULL;
      switch (type) {
        case R_AARCH64_NONE: break;
        case R_AARCH64_RELATIVE: *where = (uint64_t)(img->base + r[i].r_addend); break;
        case R_AARCH64_GLOB_DAT:
        case R_AARCH64_JUMP_SLOT:
        case R_AARCH64_ABS64: {
          uint64_t v;
          if (sym->st_shndx != SHN_UNDEF) v = (uint64_t)(img->base + sym->st_value);
          else v = (uint64_t)(uintptr_t)resolve(img, sym, dynstr + sym->st_name, &missing);
          *where = v + (type == R_AARCH64_ABS64 ? r[i].r_addend : 0);
          break;
        }
        default: fn_die("unsupported relocation type %u at 0x%llx", type, (unsigned long long)r[i].r_offset);
      }
      counts[pass]++;
    }
  }
  fn_log("relocations: %zu dyn, %zu plt, %d unresolved", counts[0], counts[1], missing);
}

int fn_load(fn_image *img, const char *path) {
  size_t pg = (size_t)getpagesize();
  if (!realpath(path, img->path)) fn_die("realpath %s: %s", path, strerror(errno));
  int fd = open(img->path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) fn_die("open %s: %s", img->path, strerror(errno));
  struct stat st;
  fstat(fd, &st);
  img->file_size = st.st_size;
  img->file = mmap(NULL, img->file_size, PROT_READ, MAP_PRIVATE, fd, 0);
  if (img->file == MAP_FAILED) fn_die("mmap file: %s", strerror(errno));

  const Elf64_Ehdr *eh = (const Elf64_Ehdr *)img->file;
  if (memcmp(eh->e_ident, ELFMAG, SELFMAG) || eh->e_machine != EM_AARCH64 || eh->e_type != ET_DYN)
    fn_die("%s: not an aarch64 PIE", img->path);
  const Elf64_Phdr *ph = (const Elf64_Phdr *)(img->file + eh->e_phoff);

  uintptr_t lo = UINTPTR_MAX, hi = 0;
  for (int i = 0; i < eh->e_phnum; i++) {
    if (ph[i].p_type != PT_LOAD) continue;
    if (ph[i].p_vaddr < lo) lo = ph[i].p_vaddr;
    if (ph[i].p_vaddr + ph[i].p_memsz > hi) hi = ph[i].p_vaddr + ph[i].p_memsz;
  }
  lo = page_down(lo, pg);
  hi = page_up(hi, pg);
  img->span = hi - lo;
  img->tramp_size = TRAMP_SIZE;
  uint8_t *res = mmap(NULL, img->span + img->tramp_size, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
  if (res == MAP_FAILED) fn_die("reserve %zu bytes: %s", img->span, strerror(errno));
  img->base = res - lo;
  img->tramp = res + img->span;
  if (mprotect(img->tramp, img->tramp_size, PROT_READ | PROT_WRITE)) fn_die("mprotect tramp: %s", strerror(errno));

  // Every PT_LOAD goes to anonymous memory and is filled with pread: works for
  // files on noexec mounts (/sdcard) and app data files that may not be
  // mapped executable. Writable first: relocations and the TLS patch need it.
  for (int i = 0; i < eh->e_phnum; i++) {
    const Elf64_Phdr *p = &ph[i];
    if (p->p_type != PT_LOAD) continue;
    uintptr_t seg = page_down(p->p_vaddr, pg);
    uintptr_t mem_end = page_up(p->p_vaddr + p->p_memsz, pg);
    void *m = mmap(img->base + seg, mem_end - seg, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_FIXED | MAP_ANONYMOUS, -1, 0);
    if (m == MAP_FAILED) fn_die("mmap segment %d: %s", i, strerror(errno));
    size_t done = 0;
    while (done < p->p_filesz) {
      ssize_t r = pread(fd, img->base + p->p_vaddr + done, p->p_filesz - done, (off_t)(p->p_offset + done));
      if (r <= 0) fn_die("read segment %d: %s", i, r ? strerror(errno) : "short file");
      done += (size_t)r;
    }
  }
  close(fd);

  for (int i = 0; i < eh->e_phnum; i++) {
    const Elf64_Phdr *p = &ph[i];
    switch (p->p_type) {
      case PT_PHDR: img->phdr = (const Elf64_Phdr *)(img->base + p->p_vaddr); break;
      case PT_DYNAMIC: img->dyn = (Elf64_Dyn *)(img->base + p->p_vaddr); break;
      case PT_GNU_EH_FRAME: img->eh_frame_hdr = p->p_vaddr; break;
      case PT_TLS:
        img->tls_vaddr = p->p_vaddr; img->tls_filesz = p->p_filesz;
        img->tls_memsz = p->p_memsz; img->tls_align = p->p_align;
        break;
      case PT_LOAD:
        if (p->p_flags & PF_X) { img->exec_lo = p->p_vaddr; img->exec_hi = p->p_vaddr + p->p_memsz; }
        break;
    }
  }
  if (!img->phdr) img->phdr = (const Elf64_Phdr *)(img->base + eh->e_phoff);
  img->phnum = eh->e_phnum;
  if (!img->dyn) fn_die("no PT_DYNAMIC");

  load_symtab(img);
  fn_tls_setup(img);
  fn_alloc_init();
  fn_restart_init();
  fn_shim_init(img);
  relocate(img);
  img->tls_sites = fn_tls_patch(img, img->base, img->exec_lo, img->exec_hi);
  // UPS counter for the HUD: the first function found that runs once per
  // game tick (names change between game versions; FN_TICK_SYMBOL overrides).
  static const char *const tick_symbols[] = {
    "_ZN8Scenario6updateEv",        // 2.1.19
    "_ZN8Scenario10updateStepEv",   // 2.1.25 (Scenario::update inlined)
    "_ZN3Map14updateEntitiesEb",    // fallback, also once per tick in 2.1.25
  };
  const char *forced = getenv("FN_TICK_SYMBOL");
  int hooked = forced ? fn_tick_hook(img, forced) == 0 : 0;
  for (size_t i = 0; !forced && !hooked && i < sizeof tick_symbols / sizeof tick_symbols[0]; i++)
    hooked = fn_tick_hook(img, tick_symbols[i]) == 0;
  if (!hooked) fn_log("UPS counter: no known tick function in this game version");
  fn_quickbar_init(img);
  fn_vibrate_init(img);
  fn_inputmethod_init(img);
  fn_restart_hooks(img);  // the game's update being installed (restart.c)

  // Final protections: segment flags, then RELRO read-only.
  for (int i = 0; i < eh->e_phnum; i++) {
    const Elf64_Phdr *p = &ph[i];
    if (p->p_type != PT_LOAD) continue;
    uintptr_t seg = page_down(p->p_vaddr, pg), end = page_up(p->p_vaddr + p->p_memsz, pg);
    if (p->p_flags & PF_X) __builtin___clear_cache((char *)img->base + seg, (char *)img->base + end);
    if (mprotect(img->base + seg, end - seg, prot_of(p->p_flags))) fn_die("mprotect seg: %s", strerror(errno));
  }
  for (int i = 0; i < eh->e_phnum; i++) {
    const Elf64_Phdr *p = &ph[i];
    if (p->p_type != PT_GNU_RELRO) continue;
    uintptr_t s = page_down(p->p_vaddr, pg), e = page_down(p->p_vaddr + p->p_memsz, pg);
    if (e > s) mprotect(img->base + s, e - s, PROT_READ);
  }
  __builtin___clear_cache((char *)img->tramp, (char *)img->tramp + img->tramp_used);
  if (mprotect(img->tramp, img->tramp_size, PROT_READ | PROT_EXEC)) fn_die("mprotect tramp rx: %s", strerror(errno));
  fn_log("loaded %s at %p (span 0x%zx, %d TLS sites, %zu symbols)", img->path, img->base, img->span,
         img->tls_sites, img->nfuncs);
  return 0;
}

static void call_fini(void) {
  fn_image *img = &fn_img;
  void (**fa)(void) = NULL;
  size_t n = 0;
  for (Elf64_Dyn *d = img->dyn; d->d_tag != DT_NULL; d++) {
    if (d->d_tag == DT_FINI_ARRAY) fa = (void (**)(void))(img->base + d->d_un.d_ptr);
    if (d->d_tag == DT_FINI_ARRAYSZ) n = d->d_un.d_val / sizeof(void *);
  }
  while (n--) if (fa[n] && fa[n] != (void (*)(void))-1) fa[n]();
}

void fn_run_init(fn_image *img, int argc, char **argv, char **envp) {
  typedef void (*initfn)(int, char **, char **);
  initfn init = NULL, *arr = NULL;
  size_t n = 0;
  for (Elf64_Dyn *d = img->dyn; d->d_tag != DT_NULL; d++) {
    if (d->d_tag == DT_INIT) init = (initfn)(img->base + d->d_un.d_ptr);
    if (d->d_tag == DT_INIT_ARRAY) arr = (initfn *)(img->base + d->d_un.d_ptr);
    if (d->d_tag == DT_INIT_ARRAYSZ) n = d->d_un.d_val / sizeof(void *);
  }
  atexit(call_fini);
  if (init) init(argc, argv, envp);
  for (size_t i = 0; i < n; i++)
    if (arr[i] && arr[i] != (initfn)-1) arr[i](argc, argv, envp);
}
