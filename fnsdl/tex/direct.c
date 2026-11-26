// ETC2 inside the game, for GPUs without BC (every Mali).
//
// The game compresses its sprites on the CPU with stb_dxt (BitmapUtil::copyWithDuplicatedBorder ->
// copyAnd4x4BlockCompressWithDuplicatedBorder<block bytes, channels> -> one lambda per format). Its block functions
// are replaced here so that it writes ETC2 / EAC blocks where it wrote BC ones. The block sizes are the same:
//   BC1 8 bytes  -> ETC2 RGB8 8          BC3 16 -> ETC2 RGBA8 EAC 16
//   BC4 8        -> EAC R11 8            BC5 16 -> EAC RG11 16
// The game keeps its BC formats, sizes and offsets; the GPU layer only creates the texture in the ETC2 format of the
// same size and uploads the bytes as they are (fngl_tex.c, nvk/nvk_res.c): no conversion at load, and
// the game's own sprite atlas cache (cache-sprite-atlas) works, it reads back and reloads these ETC2 bytes.
//
// Replaced (found by name and checked; anything unexpected: not enabled, the uploads are converted as before):
//   DXTCompression::STB::compress_dxt_block(dst, rgba[64], alpha, mode)   BC1 (alpha 0) / BC3 -> ETC2 / ETC2+EAC
//   stb__CompressAlphaBlock(dst, src, stride)                             BC4, BC5 channels, alpha planes -> EAC
//   the YCoCg-DXT5 lambda (16 bytes, one stb__CompressAlphaBlock call for its separate alpha plane, its own inlined
//     colour encoder): its BC3 blocks are re-encoded right after it — the only double work left
//   DXTDecompression::decompressBlockBC4 (BC4 source data -> pixels): decodes EAC R11. Its calling convention is the
//     compiler's own (internal function): w0 x, w1 y, w2 row width in pixels, v0.s[0] / v0.s[1] block bytes 0 / 1,
//     x3 bytes 2..7, x4 output (one channel of 4-byte pixels); checked by its first two instructions.
// BC data the game did not compress itself stays BC: the default 4x4 textures of GraphicsInterfaceOpenGL::initialize
// and core/graphics/splash-screen-space-age.dat (loaded by GlobalContext::init), both through createVideoBitmap.
// The GPU layer converts those uploads as before (tex_direct_foreign: the call comes from there).
// The game's GPU compression (TextureProcessor) still writes BC: its self-test fails (the copy of its blocks into a
// texture stored as ETC2 is not done, fngl_tex.c a_glCopyImageSubData; nvk has no such copy) and the game uses
// the CPU, as on Mali before.
#include "direct.h"

#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "etc2.h"

static void (*logf_)(const char *, ...);
static int state = -1;  // -1 not tried, 0 off, 1 on

// ---------------------------------------------------------------- encoders (game threads; no shared state)
// EAC block of 16 values src[i * stride]
static void eac_block(const uint8_t *src, int stride, uint8_t out[8]) {
  uint8_t rgba[64];
  for (int i = 0; i < 16; i++) rgba[i * 4 + 3] = src[i * stride];
  etc2EncodeAlphaBlock(rgba, out);
}

// ETC2 RGB8 block of 16 RGBA pixels. Every pixel counts, as in stb_dxt: the game's alpha is premultiplied, the
// colour of a transparent pixel is light added (or black, which must stay black).
static void color_block(const uint8_t *rgba, uint8_t out[8]) {
  uint8_t px[64];
  memcpy(px, rgba, 64);
  for (int i = 0; i < 16; i++) px[i * 4 + 3] = 255;
  etc2EncodeColorBlock(px, out);
}

// ---------------------------------------------------------------- deferred, parallel encoding
// The game compresses on its main thread, block after block (stb_dxt is fast; ETC2 is several times slower). Inside
// BitmapUtil::copyWithDuplicatedBorder (every compression of the game goes through it) the block functions only queue
// the pixels; the queue is encoded on all cores when it is full and before the call returns: the game finds the
// same bytes when the call ends. Elsewhere (the GPU compression self-test) blocks are encoded at once.
typedef struct {
  uint8_t *dst;
  int kind;        // 0 ETC2 colour, 1 EAC (alpha) + ETC2 colour, 2 EAC of px[0..15]
  uint8_t px[64];  // RGBA pixels (kind 0, 1) or 16 values (kind 2)
} pending;
#define QUEUE_MAX 32768
typedef struct {
  pending *p;
  int n, depth;
} queue;
static __thread queue *tq;

static void encode(const pending *j) {
  if (j->kind == 2) {
    eac_block(j->px, 1, j->dst);
    return;
  }
  uint8_t *dst = j->dst;
  if (j->kind == 1) {
    eac_block(j->px + 3, 4, dst);
    dst += 8;
  }
  color_block(j->px, dst);
}

// Batches of queued blocks; any number of game threads may submit at once. Workers and the submitter take chunks.
typedef struct batch {
  const pending *p;
  int n, next, done, users;
  struct batch *link;
} batch;
#define CHUNK 128
static pthread_mutex_t pool_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t work_cv = PTHREAD_COND_INITIALIZER, done_cv = PTHREAD_COND_INITIALIZER;
static batch *batches;
static int pool_started;

static int run_chunk(batch *b) {
  int i = __atomic_fetch_add(&b->next, CHUNK, __ATOMIC_RELAXED);
  if (i >= b->n) return 0;
  int e = i + CHUNK < b->n ? i + CHUNK : b->n;
  for (int k = i; k < e; k++) encode(&b->p[k]);
  if (__atomic_add_fetch(&b->done, e - i, __ATOMIC_ACQ_REL) == b->n) {
    pthread_mutex_lock(&pool_mu);
    pthread_cond_broadcast(&done_cv);
    pthread_mutex_unlock(&pool_mu);
  }
  return 1;
}

static void *worker(void *arg) {
  (void)arg;
  for (;;) {
    pthread_mutex_lock(&pool_mu);
    batch *b;
    for (;;) {
      for (b = batches; b && __atomic_load_n(&b->next, __ATOMIC_RELAXED) >= b->n; b = b->link) {}
      if (b) break;
      pthread_cond_wait(&work_cv, &pool_mu);
    }
    b->users++;
    pthread_mutex_unlock(&pool_mu);
    while (run_chunk(b)) {}
    pthread_mutex_lock(&pool_mu);
    if (--b->users == 0) pthread_cond_broadcast(&done_cv);
    pthread_mutex_unlock(&pool_mu);
  }
  return NULL;
}

static void flush(queue *q) {
  if (!q->n) return;
  pthread_mutex_lock(&pool_mu);
  if (!pool_started) {
    pool_started = 1;
    long cores = sysconf(_SC_NPROCESSORS_ONLN);
    for (long i = 1; i < (cores > 1 ? cores : 1); i++) {  // the submitting thread works too
      pthread_t t;
      if (!pthread_create(&t, NULL, worker, NULL)) pthread_detach(t);
    }
  }
  batch b = {q->p, q->n, 0, 0, 0, batches};
  batches = &b;
  pthread_cond_broadcast(&work_cv);
  pthread_mutex_unlock(&pool_mu);
  while (run_chunk(&b)) {}
  pthread_mutex_lock(&pool_mu);
  while (__atomic_load_n(&b.done, __ATOMIC_ACQUIRE) < b.n || b.users) pthread_cond_wait(&done_cv, &pool_mu);
  batch **l = &batches;
  while (*l != &b) l = &(*l)->link;
  *l = b.link;
  pthread_mutex_unlock(&pool_mu);
  q->n = 0;
}

// the queue of this thread when it is inside copyWithDuplicatedBorder, else NULL
static queue *deferring(void) {
  queue *q = tq;
  return q && q->depth ? q : NULL;
}

static void queued(queue *q) {
  if (++q->n == QUEUE_MAX) flush(q);
}

static unsigned long n_dxt[2], n_alpha, n_ycocg, n_bc4;
static void h_compress_dxt_block(uint8_t *dst, const uint8_t *src, int alpha, int mode) {
  (void)mode;
  __atomic_add_fetch(&n_dxt[!!alpha], 1, __ATOMIC_RELAXED);
  queue *q = deferring();
  pending one, *j = q ? &q->p[q->n] : &one;
  j->dst = dst;
  j->kind = alpha ? 1 : 0;
  memcpy(j->px, src, 64);
  if (q) queued(q);
  else encode(j);
}

static void h_compress_alpha_block(uint8_t *dst, uint8_t *src, int stride) {
  __atomic_add_fetch(&n_alpha, 1, __ATOMIC_RELAXED);
  queue *q = deferring();
  pending one, *j = q ? &q->p[q->n] : &one;
  j->dst = dst;
  j->kind = 2;
  for (int i = 0; i < 16; i++) j->px[i] = src[i * stride];
  if (q) queued(q);
  else encode(j);
}

// BitmapUtil::copyWithDuplicatedBorder(u32 x4, u8 *dst, int, BitmapFormat, const u8 *src, int, BitmapFormat,
// ConversionOption): 8 register and 3 stack arguments, passed on as they are
typedef void (*copy_fn)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t,
                        uint64_t, uint64_t);
static copy_fn copy_orig;
static void h_copy(uint64_t a0, uint64_t a1, uint64_t a2, uint64_t a3, uint64_t a4, uint64_t a5, uint64_t a6,
                   uint64_t a7, uint64_t s0, uint64_t s1, uint64_t s2) {
  queue *q = tq;
  if (!q) {
    q = calloc(1, sizeof *q);
    q->p = q ? malloc(QUEUE_MAX * sizeof *q->p) : NULL;
    if (!q || !q->p) {  // no memory for the queue: encode at once
      free(q);
      copy_orig(a0, a1, a2, a3, a4, a5, a6, a7, s0, s1, s2);
      return;
    }
    tq = q;
  }
  q->depth++;
  copy_orig(a0, a1, a2, a3, a4, a5, a6, a7, s0, s1, s2);
  if (--q->depth == 0) flush(q);
}

// YCoCg lambda: operator()(dst, row) -> dst after the blocks written (none until it has 4 rows)
typedef uint8_t *(*block_lambda)(void *self, uint8_t *dst, const void *row);
static block_lambda ycocg_orig;
static uint8_t *h_ycocg(void *self, uint8_t *dst, const void *row) {
  uint8_t *end = ycocg_orig(self, dst, row);
  __atomic_add_fetch(&n_ycocg, (unsigned long)(end - dst) / 16, __ATOMIC_RELAXED);
  for (uint8_t *b = dst; b + 16 <= end; b += 16) {
    uint8_t rgba[64];
    etc2DecodeBCBlock(0x83F3 /* DXT5 */, b, rgba);
    eac_block(rgba + 3, 4, b);
    color_block(rgba, b + 8);
  }
  return end;
}

// ---------------------------------------------------------------- EAC R11 decoder (replaces the BC4 one)
static const int eac_mod[16][8] = {
    {-3, -6, -9, -15, 2, 5, 8, 14}, {-3, -7, -10, -13, 2, 6, 9, 12}, {-2, -5, -8, -13, 1, 4, 7, 12},
    {-2, -4, -6, -13, 1, 3, 5, 12}, {-3, -6, -8, -12, 2, 5, 7, 11}, {-3, -7, -9, -11, 2, 6, 8, 10},
    {-4, -7, -8, -11, 3, 6, 7, 10}, {-3, -5, -8, -11, 2, 4, 7, 10}, {-2, -6, -8, -10, 1, 5, 7, 9},
    {-2, -5, -8, -10, 1, 4, 7, 9},  {-2, -4, -8, -10, 1, 3, 7, 9},  {-2, -5, -7, -10, 1, 4, 6, 9},
    {-3, -4, -7, -10, 2, 3, 6, 9},  {-1, -2, -3, -10, 0, 1, 2, 9},  {-4, -6, -8, -9, 3, 5, 7, 8},
    {-3, -5, -7, -9, 2, 4, 6, 8}};

// 16 8-bit values (row by row) of an EAC R11 block
void tex_direct_decode_r11(const uint8_t b[8], uint8_t out[16]) {
  int base = b[0] * 8 + 4, mult = b[1] >> 4, t = b[1] & 15;
  uint64_t bits = 0;
  for (int i = 2; i < 8; i++) bits = bits << 8 | b[i];
  for (int i = 0; i < 16; i++) {  // index i: column i / 4, row i % 4, first pixel in the highest bits
    int m = eac_mod[t][(bits >> (45 - 3 * i)) & 7];
    int v = base + (mult ? m * mult * 8 : m);
    v = v < 0 ? 0 : v > 2047 ? 2047 : v;
    out[(i % 4) * 4 + i / 4] = (uint8_t)((v * 255 + 1023) / 2047);
  }
}

void tex_direct_backtrace(char *out, size_t n);
__attribute__((used)) static void bc4_decode_c(uint32_t x, uint32_t y, uint32_t w, uint64_t bits, uint8_t *out,
                                                uint32_t b0, uint32_t b1) {
  if (__atomic_add_fetch(&n_bc4, 1, __ATOMIC_RELAXED) == 1 && logf_ && getenv("FNGL_LOG")) {
    char bt[512];
    tex_direct_backtrace(bt, sizeof bt);
    logf_("etc2 direct: first BC4 decode, callers:%s", bt);
  }
  uint8_t blk[8] = {(uint8_t)b0, (uint8_t)b1}, v[16];
  for (int i = 0; i < 6; i++) blk[2 + i] = (uint8_t)(bits >> (8 * i));
  tex_direct_decode_r11(blk, v);
  uint8_t *p = out + ((size_t)y * w + x) * 4;
  for (int r = 0; r < 4; r++, p += (size_t)w * 4)
    for (int c = 0; c < 4; c++) p[c * 4] = v[r * 4 + c];
}
void bc4_decode_stub(void);
__asm__(".text\n.p2align 2\n.type bc4_decode_stub,%function\nbc4_decode_stub:\n"
        "  fmov w5, s0\n  mov w6, v0.s[1]\n  b bc4_decode_c\n.size bc4_decode_stub, .-bc4_decode_stub\n");

// ---------------------------------------------------------------- finding and checking the game's functions
static void *loader_fn(const char *name) {
  void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);  // the APK; in proot the loader is the executable
  void *p = h ? dlsym(h, name) : NULL;
  return p ? p : dlsym(RTLD_DEFAULT, name);
}

static void *(*game_symbol)(const char *);
static int (*game_redirect_at)(void *, void *, void **);
static int (*game_symbols)(const char *, void (*)(const char *, void *, size_t, void *), void *);

#define DXT_BLOCK "_ZN14DXTCompression3STBL18compress_dxt_blockEPhPKhii"
#define ALPHA_BLOCK "_ZL23stb__CompressAlphaBlockPhS_i"
#define BC4_DECODE "_ZN16DXTDecompressionL18decompressBlockBC4EjjjPKhPhi"
#define COPY_BORDER "_ZN10BitmapUtil24copyWithDuplicatedBorderEjjjjPhi12BitmapFormatPKhiS1_NS_16ConversionOptionE"
#define CREATE_BITMAP "_ZN23GraphicsInterfaceOpenGL17createVideoBitmapEjjRK16MemoryBitmapDatabRK13BitmapOptions"
#define GI_INIT "_ZN23GraphicsInterfaceOpenGL10initializeEjj"
#define GC_INIT "_ZN13GlobalContext4initEbbbSt8optionalI18WindowPositionDataE"
#define LAMBDA "_ZZN10BitmapUtil15BitmapConverter43copyAnd4x4BlockCompressWithDuplicatedBorderILm"

typedef struct {
  void *dxt, *alpha;
  int lambdas, bad;
  void *ycocg;
} scan;

// The per-format lambdas (operator() of the inner lambda): which block functions each one calls.
static void scan_lambda(const char *name, void *addr, size_t size, void *ctx) {
  scan *s = ctx;
  if (!strstr(name, "ENKUl")) return;  // the outer template functions call these
  int bytes = atoi(name + strlen(LAMBDA));
  int dxt = 0, alpha = 0;
  const uint32_t *p = addr;
  for (size_t i = 0; i < size / 4; i++)
    if ((p[i] & 0xfc000000u) == 0x94000000u) {  // bl
      int32_t off = (int32_t)(p[i] << 6) >> 4;
      const uint8_t *to = (const uint8_t *)&p[i] + off;
      dxt += to == s->dxt;
      alpha += to == s->alpha;
    }
  s->lambdas++;
  if (dxt) return;                                    // BC1 / BC3 through compress_dxt_block
  if ((bytes == 8 && alpha == 1) || (bytes == 16 && alpha == 2)) return;  // BC4 / BC5
  if (bytes == 16 && alpha == 1 && !s->ycocg) {       // YCoCg-DXT5: its own colour encoder
    s->ycocg = addr;
    return;
  }
  s->bad++;
  if (logf_) logf_("etc2 direct: unknown block lambda %s (%d bytes, %d/%d calls)", name, bytes, dxt, alpha);
}

// game functions as address ranges
typedef struct { uintptr_t a, e; } range;
static range r_create, r_gi_init, r_gc_init;
static size_t (*game_symbol_size)(const char *);
static int find_range(const char *name, range *r) {
  r->a = (uintptr_t)game_symbol(name);
  r->e = r->a + (game_symbol_size ? game_symbol_size(name) : 0);
  return r->a && r->e > r->a;
}
static int in(const range *r, uintptr_t p) { return p > r->a && p <= r->e; }

int tex_direct_foreign(void) {
  if (state <= 0) return 0;
  void **fp = __builtin_frame_address(0);
  for (int i = 0; i < 10 && fp; i++) {
    uintptr_t ra = (uintptr_t)fp[1];
    void **next = fp[0];
    if (in(&r_create, ra)) {  // the frame above createVideoBitmap: who asked for the texture
      uintptr_t caller = next > fp ? (uintptr_t)next[1] : 0;
      return in(&r_gi_init, caller) || in(&r_gc_init, caller);
    }
    if (next <= fp) break;
    fp = next;
  }
  return 0;
}

static int install(void) {
  game_symbol = (void *(*)(const char *))loader_fn("fn_game_symbol");
  game_redirect_at = (int (*)(void *, void *, void **))loader_fn("fn_game_redirect_at");
  game_symbols = (int (*)(const char *, void (*)(const char *, void *, size_t, void *), void *))loader_fn("fn_game_symbols");
  game_symbol_size = (size_t (*)(const char *))loader_fn("fn_game_symbol_size");
  if (!game_symbol || !game_redirect_at || !game_symbols) {
    logf_("etc2 direct: the loader does not export the game access");
    return 0;
  }
  scan s = {game_symbol(DXT_BLOCK), game_symbol(ALPHA_BLOCK), 0, 0, NULL};
  uint32_t *bc4 = game_symbol(BC4_DECODE);
  if (!s.dxt || !s.alpha || !bc4) {
    logf_("etc2 direct: the game has no stb_dxt block functions (%p %p %p)", s.dxt, s.alpha, (void *)bc4);
    return 0;
  }
  if (bc4[0] != 0x0e0c3c0au || bc4[1] != 0x1e260009u) {  // mov w10, v0.s[1]; fmov w9, s0
    logf_("etc2 direct: decompressBlockBC4 has another calling convention (%08x %08x)", bc4[0], bc4[1]);
    return 0;
  }
  if (!find_range(CREATE_BITMAP, &r_create) || !find_range(GI_INIT, &r_gi_init) || !find_range(GC_INIT, &r_gc_init)) {
    logf_("etc2 direct: createVideoBitmap / initialize / GlobalContext::init not found");
    return 0;
  }
  game_symbols(LAMBDA, scan_lambda, &s);
  if (s.bad || s.lambdas < 5 || !s.ycocg) {
    logf_("etc2 direct: block lambdas not as expected (%d found, %d unknown, YCoCg %s)", s.lambdas, s.bad,
          s.ycocg ? "yes" : "no");
    return 0;
  }
  void *copy = game_symbol(COPY_BORDER);
  if (!copy) {
    logf_("etc2 direct: no BitmapUtil::copyWithDuplicatedBorder");
    return 0;
  }
  // all found: now replace (the ones that need their old code first)
  void *orig = NULL;
  if (game_redirect_at(copy, (void *)h_copy, &orig) || !orig) {
    logf_("etc2 direct: cannot replace copyWithDuplicatedBorder");
    return 0;
  }
  copy_orig = (copy_fn)orig;
  orig = NULL;
  if (game_redirect_at(s.ycocg, (void *)h_ycocg, &orig) || !orig) {  // copyWithDuplicatedBorder alone: harmless
    logf_("etc2 direct: cannot replace the YCoCg lambda");
    return 0;
  }
  ycocg_orig = (block_lambda)orig;
  int r = game_redirect_at(s.dxt, (void *)h_compress_dxt_block, NULL);
  r |= game_redirect_at(s.alpha, (void *)h_compress_alpha_block, NULL);
  r |= game_redirect_at(bc4, (void *)bc4_decode_stub, NULL);
  if (r) {  // not seen; the game's blocks are mixed now
    logf_("etc2 direct: replacing failed half way (%d)", r);
    abort();
  }
  logf_("etc2 direct: the game writes ETC2/EAC instead of BC (%d block lambdas, YCoCg re-encoded, %ld cores)", s.lambdas,
        sysconf(_SC_NPROCESSORS_ONLN));
  return 1;
}

// FN_ETC2_DIRECT_STATUS=<file>: "<FN_ETC2_DIRECT_KEY> on|off" for the launcher (the game's atlas cache is only
// allowed on a GPU without BC when the game writes ETC2).
static void write_status(void) {
  const char *path = getenv("FN_ETC2_DIRECT_STATUS"), *key = getenv("FN_ETC2_DIRECT_KEY");
  if (!path || !*path) return;
  FILE *f = fopen(path, "w");
  if (!f) return;
  fprintf(f, "%s %s\n", key ? key : "", state ? "on" : "off");
  fclose(f);
}

int tex_direct_enable(void (*log)(const char *, ...)) {
  if (state >= 0) return state;
  logf_ = log;
  const char *e = getenv("FN_ETC2_DIRECT");  // 0: convert at upload as before (comparison, other game versions)
  if (e && !strcmp(e, "0")) {
    state = 0;
    log("etc2 direct: off (FN_ETC2_DIRECT=0)");
  } else {
    state = install();
  }
  write_status();
  return state;
}

int tex_direct_active(void) { return state > 0; }

// game offsets of the callers (frame pointer chain) as text, for diagnostics
void tex_direct_backtrace(char *out, size_t n) {
  uintptr_t base = game_symbol ? (uintptr_t)game_symbol(DXT_BLOCK) - 0x3345b60 : 0;
  void **fp = __builtin_frame_address(0);
  size_t k = 0;
  out[0] = 0;
  for (int i = 0; i < 12 && fp && k + 20 < n; i++) {
    uintptr_t ra = (uintptr_t)fp[1];
    k += snprintf(out + k, n - k, " %lx", (unsigned long)(ra - base));
    void **next = fp[0];
    if (next <= fp) break;
    fp = next;
  }
}

void tex_direct_stats(unsigned long out[5]) {
  out[0] = n_dxt[0], out[1] = n_dxt[1], out[2] = n_alpha, out[3] = n_ycocg, out[4] = n_bc4;
}

unsigned tex_direct_format(unsigned bc) {
  switch (bc) {
    case 0x83F0: case 0x83F1: return 0x9274;  // BC1 (the game's blocks are opaque) -> RGB8 ETC2
    case 0x8C4C: case 0x8C4D: return 0x9275;  // sRGB BC1 -> SRGB8 ETC2
    case 0x83F3: return 0x9278;               // BC3 -> RGBA8 ETC2 EAC
    case 0x8C4F: return 0x9279;               // sRGB BC3 -> SRGB8 ALPHA8 ETC2 EAC
    case 0x8DBB: return 0x9270;               // BC4 -> R11 EAC
    case 0x8DBD: return 0x9272;               // BC5 -> RG11 EAC
  }
  return 0;  // BC2, signed RGTC: the game's stb_dxt does not write them
}
