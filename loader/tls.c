// Thread-local storage of the glibc image.
//
// The image uses local-exec TLS: "mrs Xn, tpidr_el0" and then fixed offsets
// (tp + 16 + off). On bionic tp+16.. are bionic/ART/GL slots, so every
// "mrs Xn, tpidr_el0" is redirected to a trampoline that loads a per-thread
// fake thread pointer from bionic slot FN_TLS_SLOT instead. The offsets in the
// game code stay unchanged. A thread gets its block on first TLS access.
#include <pthread.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "fn.h"

extern void fn_tls_slow(void);  // tls_slow.S

static pthread_key_t key;
static size_t block_size, tcb_off;
static const uint8_t *tdata;
static size_t tdata_size;

static void **tp(void) { return (void **)__builtin_thread_pointer(); }

static void free_block(void *b) {
  if (tp()[FN_TLS_SLOT] == b) tp()[FN_TLS_SLOT] = NULL;
  munmap(b, block_size);
}

// Called from fn_tls_slow with all registers saved.
void *fn_tls_alloc(void) {
  void **t = tp();
  if (t[FN_TLS_SLOT]) return t[FN_TLS_SLOT];
  uint8_t *b = mmap(NULL, block_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
  if (b == MAP_FAILED) fn_die("TLS block mmap failed");
  if (tdata_size) memcpy(b + tcb_off, tdata, tdata_size);
  t[FN_TLS_SLOT] = b;
  pthread_setspecific(key, b);
  return b;
}

void *fn_tls_current(void) { return fn_tls_alloc(); }

void fn_tls_setup(const fn_image *img) {
  size_t pg = (size_t)getpagesize();
  size_t align = img->tls_align < 16 ? 16 : img->tls_align;
  tcb_off = (16 + align - 1) & ~(align - 1);  // glibc aarch64: TCB is 16 bytes
  block_size = (tcb_off + img->tls_memsz + pg - 1) & ~(pg - 1);
  tdata = img->base + img->tls_vaddr;
  tdata_size = img->tls_filesz;
  if (tp()[FN_TLS_SLOT]) fn_die("bionic TLS slot %d is already used (%p)", FN_TLS_SLOT, tp()[FN_TLS_SLOT]);
  pthread_key_create(&key, free_block);
}

static uint32_t b_to(uintptr_t from, uintptr_t to, uint32_t op) {
  intptr_t d = (intptr_t)(to - from);
  if (d >= (1 << 27) || d < -(1 << 27)) fn_die("branch out of range %lx -> %lx", (unsigned long)from, (unsigned long)to);
  return op | (((uint32_t)(d >> 2)) & 0x03ffffff);
}

int fn_tls_patch(fn_image *img, uint8_t *base, uintptr_t lo, uintptr_t hi) {
  // Shared literal with the address of fn_tls_slow at the start of the area.
  size_t lit_off = (img->tramp_used + 7) & ~(size_t)7;
  *(uint64_t *)(img->tramp + lit_off) = (uint64_t)(uintptr_t)fn_tls_slow;
  img->tramp_used = lit_off + 8;
  uintptr_t lit = (uintptr_t)img->tramp + lit_off;

  int n = 0;
  for (uintptr_t v = lo & ~(uintptr_t)3; v + 4 <= hi; v += 4) {
    uint32_t *site = (uint32_t *)(base + v);
    uint32_t w = *site;
    if ((w & ~31u) != 0xd53bd040u) continue;
    uint32_t r = w & 31;
    if (img->tramp_used + 64 > img->tramp_size) fn_die("trampoline area full");
    uint32_t *t = (uint32_t *)(img->tramp + img->tramp_used);
    uintptr_t back = (uintptr_t)site + 4;
    int k = 0;
    t[k++] = 0xd53bd040u | r;                              // mrs  xR, tpidr_el0
    t[k++] = 0xf9400000u | ((FN_TLS_SLOT) << 10) | (r << 5) | r;  // ldr xR, [xR, #8*slot]
    t[k++] = 0xb4000000u | (2u << 5) | r;                  // cbz  xR, slow
    t[k] = b_to((uintptr_t)&t[k], back, 0x14000000u); k++; // b    back
    // slow: keep every register except xR
    if (r == 0) {
      t[k++] = 0xf81f0ffeu;                                // str x30, [sp, #-16]!
    } else if (r == 30) {
      t[k++] = 0xf81f0fe0u;                                // str x0, [sp, #-16]!
    } else {
      t[k++] = 0xa9800000u | (0x7eu << 15) | (31u << 5) | 30;  // stp x30, x0, [sp, #-16]!
    }
    int32_t d = (int32_t)(((intptr_t)lit - (intptr_t)&t[k]) >> 2);
    t[k++] = 0x58000000u | (((uint32_t)d & 0x7ffff) << 5) | 30;  // ldr x30, =fn_tls_slow
    t[k++] = 0xd63f03c0u;                                  // blr x30
    if (r == 0) {
      t[k++] = 0xf84107feu;                                // ldr x30, [sp], #16
    } else if (r == 30) {
      t[k++] = 0xaa0003feu;                                // mov x30, x0
      t[k++] = 0xf84107e0u;                                // ldr x0, [sp], #16
    } else {
      t[k++] = 0xaa0003e0u | r;                            // mov xR, x0
      t[k++] = 0xa8c00000u | (2u << 15) | (31u << 5) | 30; // ldp x30, x0, [sp], #16
    }
    t[k] = b_to((uintptr_t)&t[k], back, 0x14000000u); k++;
    *site = b_to((uintptr_t)site, (uintptr_t)t, 0x14000000u);
    img->tramp_used += (size_t)k * 4;
    img->tramp_used = (img->tramp_used + 7) & ~(size_t)7;
    n++;
  }
  return n;
}
