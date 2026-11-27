// Compressed texture formats the GPU does not have.
//
// The game uploads BC (S3TC / RGTC) textures. For every BC format separately:
//   - the driver has it  -> nothing is done, the game talks to the driver;
//   - the driver lacks it -> the texture is stored in the best target format
//     from `targets` below and every upload is converted on the CPU.
// Targets are tried in order; a new format (ASTC, a vendor format, ...) is one
// more entry in `targets`. FNGL_TEXTURE_TARGET=<name> forces one target,
// FNGL_TEXTURE_TARGET=native disables the conversion.
// Without any of the game's BC formats (Mali) the game itself writes ETC2/EAC (tex/direct.c): the "etc2-direct"
// target then stores the texture in the ETC2 format of the same block size and passes the bytes through.
#include "fngl_tex.h"

#include <GLES3/gl32.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "fnlog.h"
#include "tex/direct.h"
#include "tex/etc2.h"

// ---------------------------------------------------------------- BC formats
enum {
  BC1_RGB = 0x83F0, BC1_RGBA = 0x83F1, BC2 = 0x83F2, BC3 = 0x83F3,
  BC1_SRGB = 0x8C4C, BC1_SRGB_A = 0x8C4D, BC2_SRGB = 0x8C4E, BC3_SRGB = 0x8C4F,
  BC4 = 0x8DBB, BC4_SNORM = 0x8DBC, BC5 = 0x8DBD, BC5_SNORM = 0x8DBE,
};
static const GLenum bc_formats[] = {BC1_RGB, BC1_RGBA, BC2, BC3, BC1_SRGB, BC1_SRGB_A, BC2_SRGB, BC3_SRGB,
                                    BC4, BC4_SNORM, BC5, BC5_SNORM};
#define NBC (sizeof bc_formats / sizeof bc_formats[0])

static int bc_block_bytes(GLenum f) {
  switch (f) {
    case BC1_RGB: case BC1_RGBA: case BC1_SRGB: case BC1_SRGB_A: case BC4: case BC4_SNORM: return 8;
    case BC2: case BC3: case BC2_SRGB: case BC3_SRGB: case BC5: case BC5_SNORM: return 16;
  }
  return 0;
}
static int bc_index(GLenum f) {
  for (size_t i = 0; i < NBC; i++) if (bc_formats[i] == f) return (int)i;
  return -1;
}
// The DXT decoder in tex/etc2.c knows the linear formats; sRGB has the same bits.
static GLenum bc_linear(GLenum f) {
  switch (f) {
    case BC1_SRGB: return BC1_RGB;
    case BC1_SRGB_A: return BC1_RGBA;
    case BC2_SRGB: return BC2;
    case BC3_SRGB: return BC3;
  }
  return f;
}

static void bc4_decode(const uint8_t *b, uint8_t out[16]) {
  uint8_t p[8];
  p[0] = b[0];
  p[1] = b[1];
  if (p[0] > p[1]) {
    for (int i = 1; i < 7; i++) p[i + 1] = (uint8_t)(((7 - i) * p[0] + i * p[1]) / 7);
  } else {
    for (int i = 1; i < 5; i++) p[i + 1] = (uint8_t)(((5 - i) * p[0] + i * p[1]) / 5);
    p[6] = 0;
    p[7] = 255;
  }
  uint64_t bits = 0;
  for (int i = 0; i < 6; i++) bits |= (uint64_t)b[2 + i] << (8 * i);
  for (int i = 0; i < 16; i++) out[i] = p[(bits >> (3 * i)) & 7];
}

// One BC block -> 16 RGBA pixels (row by row). BC4/BC5 go to R / RG.
static void bc_decode(GLenum f, const uint8_t *src, uint8_t rgba[64]) {
  if (f == BC4 || f == BC5) {
    uint8_t r[16], g[16] = {0};
    bc4_decode(src, r);
    if (f == BC5) bc4_decode(src + 8, g);
    for (int i = 0; i < 16; i++) {
      rgba[i * 4] = r[i];
      rgba[i * 4 + 1] = g[i];
      rgba[i * 4 + 2] = 0;
      rgba[i * 4 + 3] = 255;
    }
    return;
  }
  etc2DecodeBCBlock(bc_linear(f), src, rgba);
}

// ---------------------------------------------------------------- targets
typedef struct {
  const char *name;
  // the target can be used on this driver
  int (*usable)(int (*driver_has)(const char *));
  // destination internal format for a BC format, 0 = this target cannot hold it
  GLenum (*format_for)(GLenum bc);
  // destination bytes per 4x4 block; 0 = uncompressed (see upload_* below)
  int (*block_bytes)(GLenum dst);
  // one BC block (format bc) -> one block of the destination format dst (compressed targets only)
  void (*convert_block)(GLenum bc, GLenum dst_format, const uint8_t *src, uint8_t *dst);
} tex_target;

// --- ETC2 / EAC: mandatory in every OpenGL ES 3.x driver
static int etc2_usable(int (*has)(const char *)) { (void)has; return 1; }
static GLenum etc2_format_for(GLenum bc) {
  switch (bc) {
    case BC1_RGB: return 0x9274;                                 // RGB8 ETC2
    case BC1_SRGB: return 0x9275;                                // SRGB8 ETC2
    case BC1_RGBA: case BC2: case BC3: return 0x9278;            // RGBA8 ETC2 EAC
    case BC1_SRGB_A: case BC2_SRGB: case BC3_SRGB: return 0x9279; // SRGB8 ALPHA8 ETC2 EAC
    case BC4: return 0x9270;                                     // R11 EAC
    case BC5: return 0x9272;                                     // RG11 EAC
  }
  return 0;  // signed RGTC: no ETC2 equivalent here
}
static int etc2_block_bytes(GLenum dst) { return dst == 0x9274 || dst == 0x9275 || dst == 0x9270 ? 8 : 16; }
static void etc2_convert_block(GLenum bc, GLenum f, const uint8_t *src, uint8_t *dst) {
  uint8_t rgba[64];
  bc_decode(bc, src, rgba);
  if (f == 0x9270 || f == 0x9272) {  // EAC R11 / RG11: alpha-style EAC block per channel
    for (int c = 0; c < (f == 0x9272 ? 2 : 1); c++) {
      uint8_t ch[64];
      for (int i = 0; i < 16; i++) ch[i * 4 + 3] = rgba[i * 4 + c];
      etc2EncodeAlphaBlock(ch, dst + c * 8);
    }
    return;
  }
  if (f == 0x9278 || f == 0x9279) {
    // Fast paths: sprite atlases are full of blocks with one alpha value.
    uint8_t a0 = rgba[3];
    int same = 1;
    for (int i = 1; i < 16 && same; i++) same = rgba[i * 4 + 3] == a0;
    if (same) {
      // EAC: base = a0, multiplier 1, table 13 (has modifier 0 at index 4),
      // every pixel index 4 -> each pixel decodes exactly to a0
      static const uint8_t idx4[6] = {0x92, 0x49, 0x24, 0x92, 0x49, 0x24};  // "100" x 16, big-endian
      dst[0] = a0;
      dst[1] = (1 << 4) | 13;
      memcpy(dst + 2, idx4, 6);
      // Colour is still encoded when alpha is 0: the game uses premultiplied
      // alpha, where alpha 0 with colour is additive light (logo glow).
    } else {
      etc2EncodeAlphaBlock(rgba, dst);
    }
    dst += 8;
  }
  etc2EncodeColorBlock(rgba, dst);
}

// --- Uncompressed: always works, 4-8x the memory of BC
static int raw_usable(int (*has)(const char *)) { (void)has; return 1; }
static GLenum raw_format_for(GLenum bc) {
  switch (bc) {
    case BC1_RGB: case BC1_RGBA: case BC2: case BC3: return GL_RGBA8;
    case BC1_SRGB: case BC1_SRGB_A: case BC2_SRGB: case BC3_SRGB: return GL_SRGB8_ALPHA8;
    case BC4: return GL_R8;
    case BC5: return GL_RG8;
  }
  return 0;
}
static int raw_block_bytes(GLenum dst) { (void)dst; return 0; }

// --- The game's own ETC2/EAC blocks (tex/direct.c): same block size, no conversion (upload() is not used)
static int direct_usable(int (*has)(const char *)) { (void)has; return 1; }
static GLenum direct_format_for(GLenum bc) { return tex_direct_format(bc); }
static int direct_block_bytes(GLenum dst) { return etc2_block_bytes(dst); }
static const tex_target direct_target = {"etc2-direct", direct_usable, direct_format_for, direct_block_bytes, NULL};

// Order = preference. Future formats (ASTC, vendor ones) go before "rgba8".
static const tex_target targets[] = {
  {"etc2", etc2_usable, etc2_format_for, etc2_block_bytes, etc2_convert_block},
  {"rgba8", raw_usable, raw_format_for, raw_block_bytes, NULL},
};
#define NTARGETS (sizeof targets / sizeof targets[0])

static int raw_channels(GLenum dst) { return dst == GL_R8 ? 1 : dst == GL_RG8 ? 2 : 4; }
static GLenum raw_upload_format(GLenum dst) { return dst == GL_R8 ? GL_RED : dst == GL_RG8 ? GL_RG : GL_RGBA; }

// ---------------------------------------------------------------- state
static int native_has[NBC];           // driver has this BC format
static const tex_target *emu[NBC];    // target used for a missing format
static int any_emulated;
static void *(*drv)(const char *);

// texture name -> emulated BC format (open addressing, 0 = empty)
static struct { GLuint name; GLenum bc; } *tex_map;
static size_t tex_cap;
static pthread_mutex_t tex_lock = PTHREAD_MUTEX_INITIALIZER;

static void map_put(GLuint name, GLenum bc) {
  pthread_mutex_lock(&tex_lock);
  if (!tex_cap) {
    tex_cap = 4096;
    tex_map = calloc(tex_cap, sizeof *tex_map);
  }
  size_t i = name * 2654435761u % tex_cap;
  while (tex_map[i].name && tex_map[i].name != name) i = (i + 1) % tex_cap;
  tex_map[i].name = name;
  tex_map[i].bc = bc;
  pthread_mutex_unlock(&tex_lock);
}
static GLenum map_get(GLuint name) {
  if (!tex_cap || !name) return 0;
  pthread_mutex_lock(&tex_lock);
  size_t i = name * 2654435761u % tex_cap;
  GLenum r = 0;
  while (tex_map[i].name) {
    if (tex_map[i].name == name) { r = tex_map[i].bc; break; }
    i = (i + 1) % tex_cap;
  }
  pthread_mutex_unlock(&tex_lock);
  return r;
}
static void map_del(GLuint name) {
  if (!tex_cap) return;
  pthread_mutex_lock(&tex_lock);
  size_t i = name * 2654435761u % tex_cap;
  while (tex_map[i].name) {
    if (tex_map[i].name == name) { tex_map[i].bc = 0; break; }  // keep slot: probe chains stay intact
    i = (i + 1) % tex_cap;
  }
  pthread_mutex_unlock(&tex_lock);
}

static const tex_target *target_for(GLenum bc) {
  int i = bc_index(bc);
  return i >= 0 && !native_has[i] ? emu[i] : NULL;
}

// ---------------------------------------------------------------- real functions
static void (*r_glTexStorage2D)(GLenum, GLsizei, GLenum, GLsizei, GLsizei);
static void (*r_glTexStorage3D)(GLenum, GLsizei, GLenum, GLsizei, GLsizei, GLsizei);
static void (*r_glCompressedTexImage2D)(GLenum, GLint, GLenum, GLsizei, GLsizei, GLint, GLsizei, const void *);
static void (*r_glCompressedTexImage3D)(GLenum, GLint, GLenum, GLsizei, GLsizei, GLsizei, GLint, GLsizei, const void *);
static void (*r_glCompressedTexSubImage2D)(GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLsizei, const void *);
static void (*r_glCompressedTexSubImage3D)(GLenum, GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei, GLenum, GLsizei, const void *);
static void (*r_glDeleteTextures)(GLsizei, const GLuint *);
static void (*r_glCopyImageSubData)(GLuint, GLenum, GLint, GLint, GLint, GLint, GLuint, GLenum, GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei);
static void (*r_glGetIntegerv)(GLenum, GLint *);

static GLuint bound(GLenum target) {
  GLenum b = target == GL_TEXTURE_2D_ARRAY ? GL_TEXTURE_BINDING_2D_ARRAY
           : target == GL_TEXTURE_3D ? GL_TEXTURE_BINDING_3D
           : target == GL_TEXTURE_CUBE_MAP || (target >= 0x8515 && target <= 0x851A) ? GL_TEXTURE_BINDING_CUBE_MAP
           : GL_TEXTURE_BINDING_2D;
  GLint t = 0;
  r_glGetIntegerv(b, &t);
  return (GLuint)t;
}

// ---------------------------------------------------------------- conversion
typedef struct {
  const tex_target *t;
  GLenum bc, dst;
  const uint8_t *src;
  uint8_t *out;
  int bw, bh, layers;
  int row0, row1;  // block rows (over all layers)
} job;

static void *run_job(void *p) {
  job *j = p;
  int sbb = bc_block_bytes(j->bc);
  if (j->t->block_bytes(j->dst)) {
    int dbb = j->t->block_bytes(j->dst);
    for (int r = j->row0; r < j->row1; r++)
      for (int x = 0; x < j->bw; x++) {
        size_t k = (size_t)r * j->bw + x;
        j->t->convert_block(j->bc, j->dst, j->src + k * sbb, j->out + k * dbb);
      }
  } else {  // uncompressed: pixel rows of bw*4 pixels, layer after layer
    int ch = raw_channels(j->dst);
    size_t pitch = (size_t)j->bw * 4 * ch;
    uint8_t rgba[64];
    for (int r = j->row0; r < j->row1; r++)
      for (int x = 0; x < j->bw; x++) {
        bc_decode(j->bc, j->src + ((size_t)r * j->bw + x) * sbb, rgba);
        for (int py = 0; py < 4; py++)
          for (int px = 0; px < 4; px++) {
            uint8_t *o = j->out + ((size_t)r * 4 + py) * pitch + ((size_t)x * 4 + px) * ch;
            memcpy(o, rgba + (py * 4 + px) * 4, ch);
          }
      }
  }
  return NULL;
}

// Persistent worker pool: uploads arrive one after another on the GL thread,
// most of them small (one sprite), so threads are started once.
#define MAX_WORKERS 8
static pthread_mutex_t pool_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t pool_go = PTHREAD_COND_INITIALIZER, pool_done = PTHREAD_COND_INITIALIZER;
static job pool_jobs[MAX_WORKERS];
static int pool_n, pool_gen, pool_pending, pool_started;

static void *worker(void *arg) {
  int id = (int)(intptr_t)arg, seen = 0;
  for (;;) {
    pthread_mutex_lock(&pool_lock);
    while (pool_gen == seen) pthread_cond_wait(&pool_go, &pool_lock);
    seen = pool_gen;
    int mine = id < pool_n;
    pthread_mutex_unlock(&pool_lock);
    if (!mine) continue;
    run_job(&pool_jobs[id]);
    pthread_mutex_lock(&pool_lock);
    if (--pool_pending == 0) pthread_cond_signal(&pool_done);
    pthread_mutex_unlock(&pool_lock);
  }
  return NULL;
}

static int pool_size(void) {
  if (!pool_started) {
    pool_started = (int)sysconf(_SC_NPROCESSORS_ONLN);
    if (pool_started > MAX_WORKERS) pool_started = MAX_WORKERS;
    if (pool_started < 1) pool_started = 1;
    for (int i = 1; i < pool_started; i++) {  // worker 0 is the calling thread
      pthread_t t;
      pthread_create(&t, NULL, worker, (void *)(intptr_t)i);
      pthread_detach(t);
    }
  }
  return pool_started;
}

// Converts bw x bh x layers BC blocks. Returns malloc'ed data and its size.
static uint8_t *convert(const tex_target *t, GLenum bc, GLenum dst, const void *src, int bw, int bh, int layers,
                        size_t *size) {
  int dbb = t->block_bytes(dst);
  size_t rows = (size_t)bh * layers;
  *size = dbb ? rows * bw * dbb : rows * 16 * bw * raw_channels(dst);
  uint8_t *out = malloc(*size ? *size : 1);
  int n = rows * bw >= 256 ? pool_size() : 1;
  if ((size_t)n > rows) n = (int)rows;
  if (n < 1) n = 1;
  size_t per = (rows + n - 1) / n;
  job jobs[MAX_WORKERS];
  for (int i = 0; i < n; i++) {
    size_t a = i * per, b = a + per > rows ? rows : a + per;
    jobs[i] = (job){t, bc, dst, src, out, bw, bh, layers, (int)(a < rows ? a : rows), (int)(a < rows ? b : rows)};
  }
  if (n == 1) {
    run_job(&jobs[0]);
    return out;
  }
  pthread_mutex_lock(&pool_lock);
  memcpy(pool_jobs, jobs, sizeof jobs);
  pool_n = n;
  pool_pending = n - 1;
  pool_gen++;
  pthread_cond_broadcast(&pool_go);
  pthread_mutex_unlock(&pool_lock);
  run_job(&jobs[0]);
  pthread_mutex_lock(&pool_lock);
  while (pool_pending) pthread_cond_wait(&pool_done, &pool_lock);
  pthread_mutex_unlock(&pool_lock);
  return out;
}

// Source data may live in a pixel unpack buffer: copy it out.
static const void *client_data(const void *data, GLsizei size, void **tmp) {
  GLint pbo = 0;
  r_glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &pbo);
  *tmp = NULL;
  if (!pbo) return data;
  void *p = glMapBufferRange(GL_PIXEL_UNPACK_BUFFER, (GLintptr)data, size, GL_MAP_READ_BIT);
  if (!p) return NULL;
  *tmp = malloc(size);
  memcpy(*tmp, p, size);
  glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
  return *tmp;
}

// Uploads converted data with no unpack buffer and neutral unpack state.
typedef struct { GLint pbo, row, height, skip_px, skip_rows, skip_img, align; } unpack_state;
static void unpack_push(unpack_state *s, int row_length, int image_height) {
  r_glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &s->pbo);
  r_glGetIntegerv(GL_UNPACK_ROW_LENGTH, &s->row);
  r_glGetIntegerv(GL_UNPACK_IMAGE_HEIGHT, &s->height);
  r_glGetIntegerv(GL_UNPACK_SKIP_PIXELS, &s->skip_px);
  r_glGetIntegerv(GL_UNPACK_SKIP_ROWS, &s->skip_rows);
  r_glGetIntegerv(GL_UNPACK_SKIP_IMAGES, &s->skip_img);
  r_glGetIntegerv(GL_UNPACK_ALIGNMENT, &s->align);
  glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, row_length);
  glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, image_height);
  glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
  glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
  glPixelStorei(GL_UNPACK_SKIP_IMAGES, 0);
  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
}
static void unpack_pop(const unpack_state *s) {
  glBindBuffer(GL_PIXEL_UNPACK_BUFFER, (GLuint)s->pbo);
  glPixelStorei(GL_UNPACK_ROW_LENGTH, s->row);
  glPixelStorei(GL_UNPACK_IMAGE_HEIGHT, s->height);
  glPixelStorei(GL_UNPACK_SKIP_PIXELS, s->skip_px);
  glPixelStorei(GL_UNPACK_SKIP_ROWS, s->skip_rows);
  glPixelStorei(GL_UNPACK_SKIP_IMAGES, s->skip_img);
  glPixelStorei(GL_UNPACK_ALIGNMENT, s->align);
}

// Uploads a converted region. dims = 2 or 3; full = allocate (TexImage) or update (SubImage).
static void upload(int dims, int full, GLenum target, GLint level, GLint x, GLint y, GLint z, GLsizei w, GLsizei h,
                   GLsizei d, GLenum bc, GLsizei size, const void *data) {
  // The destination format comes from the texture: the game may upload BC1
  // blocks without alpha into a BC1-with-alpha texture (legal on desktop).
  GLenum tex_bc = map_get(bound(target));
  if (!tex_bc) tex_bc = bc;
  const tex_target *t = target_for(tex_bc);
  if (!t) t = target_for(bc);
  GLenum dst = t->format_for(tex_bc);
  // BC the game did not compress (its default textures, the splash screen): converted into the same ETC2 format
  if (t == &direct_target && tex_direct_foreign() && etc2_format_for(tex_bc) == dst) t = &targets[0];
  if (t == &direct_target) {  // the game's bytes are ETC2/EAC already; data may be in an unpack buffer: as it is
    if (dims == 2 && full) r_glCompressedTexImage2D(target, level, dst, w, h, 0, size, data);
    else if (dims == 2) r_glCompressedTexSubImage2D(target, level, x, y, w, h, dst, size, data);
    else if (full) r_glCompressedTexImage3D(target, level, dst, w, h, d, 0, size, data);
    else r_glCompressedTexSubImage3D(target, level, x, y, z, w, h, d, dst, size, data);
    return;
  }
  int bw = (w + 3) / 4, bh = (h + 3) / 4;
  void *tmp = NULL;
  const void *src = data ? client_data(data, size, &tmp) : NULL;
  if (!src && data) { FNSDL_LOG("fngl", "texture: cannot read unpack buffer"); return; }
  if (src && size < bw * bh * d * bc_block_bytes(bc)) { FNSDL_LOG("fngl", "texture: short data %d", size); free(tmp); return; }
  size_t n = 0;
  uint8_t *out = src ? convert(t, bc, dst, src, bw, bh, d, &n) : NULL;
  unpack_state st;
  unpack_push(&st, bw * 4, bh * 4);
  if (t->block_bytes(dst)) {
    if (dims == 2 && full) r_glCompressedTexImage2D(target, level, dst, w, h, 0, (GLsizei)n, out);
    else if (dims == 2) r_glCompressedTexSubImage2D(target, level, x, y, w, h, dst, (GLsizei)n, out);
    else if (full) r_glCompressedTexImage3D(target, level, dst, w, h, d, 0, (GLsizei)n, out);
    else r_glCompressedTexSubImage3D(target, level, x, y, z, w, h, d, dst, (GLsizei)n, out);
  } else {
    GLenum f = raw_upload_format(dst);
    if (dims == 2 && full) glTexImage2D(target, level, dst, w, h, 0, f, GL_UNSIGNED_BYTE, out);
    else if (dims == 2) glTexSubImage2D(target, level, x, y, w, h, f, GL_UNSIGNED_BYTE, out);
    else if (full) glTexImage3D(target, level, dst, w, h, d, 0, f, GL_UNSIGNED_BYTE, out);
    else glTexSubImage3D(target, level, x, y, z, w, h, d, f, GL_UNSIGNED_BYTE, out);
  }
  unpack_pop(&st);
  free(out);
  free(tmp);
}

// ---------------------------------------------------------------- adapters
static void a_glTexStorage2D(GLenum target, GLsizei levels, GLenum fmt, GLsizei w, GLsizei h) {
  const tex_target *t = target_for(fmt);
  if (!t) { r_glTexStorage2D(target, levels, fmt, w, h); return; }
  map_put(bound(target), fmt);
  r_glTexStorage2D(target, levels, t->format_for(fmt), w, h);
}
static void a_glTexStorage3D(GLenum target, GLsizei levels, GLenum fmt, GLsizei w, GLsizei h, GLsizei d) {
  const tex_target *t = target_for(fmt);
  if (!t) { r_glTexStorage3D(target, levels, fmt, w, h, d); return; }
  map_put(bound(target), fmt);
  r_glTexStorage3D(target, levels, t->format_for(fmt), w, h, d);
}
static void a_glCompressedTexImage2D(GLenum target, GLint level, GLenum fmt, GLsizei w, GLsizei h, GLint border,
                                     GLsizei size, const void *data) {
  if (!target_for(fmt)) { r_glCompressedTexImage2D(target, level, fmt, w, h, border, size, data); return; }
  if (!map_get(bound(target))) map_put(bound(target), fmt);  // later levels keep the level-0 format
  upload(2, 1, target, level, 0, 0, 0, w, h, 1, fmt, size, data);
}
static void a_glCompressedTexImage3D(GLenum target, GLint level, GLenum fmt, GLsizei w, GLsizei h, GLsizei d,
                                     GLint border, GLsizei size, const void *data) {
  if (!target_for(fmt)) { r_glCompressedTexImage3D(target, level, fmt, w, h, d, border, size, data); return; }
  if (!map_get(bound(target))) map_put(bound(target), fmt);
  upload(3, 1, target, level, 0, 0, 0, w, h, d, fmt, size, data);
}
static void a_glCompressedTexSubImage2D(GLenum target, GLint level, GLint x, GLint y, GLsizei w, GLsizei h,
                                        GLenum fmt, GLsizei size, const void *data) {
  if (!target_for(fmt)) { r_glCompressedTexSubImage2D(target, level, x, y, w, h, fmt, size, data); return; }
  upload(2, 0, target, level, x, y, 0, w, h, 1, fmt, size, data);
}
static void a_glCompressedTexSubImage3D(GLenum target, GLint level, GLint x, GLint y, GLint z, GLsizei w,
                                        GLsizei h, GLsizei d, GLenum fmt, GLsizei size, const void *data) {
  if (!target_for(fmt)) { r_glCompressedTexSubImage3D(target, level, x, y, z, w, h, d, fmt, size, data); return; }
  upload(3, 0, target, level, x, y, z, w, h, d, fmt, size, data);
}
// Desktop GL allows glTexImage* with a compressed internal format and no data
// (storage only). GLES does not: allocate the converted format instead.
static void (*r_glTexImage2D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *);
static void (*r_glTexImage3D)(GLenum, GLint, GLint, GLsizei, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *);
static void alloc_level(int dims, GLenum target, GLint level, GLenum bc, GLsizei w, GLsizei h, GLsizei d) {
  if (!map_get(bound(target))) map_put(bound(target), bc);
  GLenum tex_bc = map_get(bound(target));
  const tex_target *t = target_for(tex_bc);
  GLenum dst = t->format_for(tex_bc);
  unpack_state st;
  unpack_push(&st, 0, 0);
  if (t->block_bytes(dst)) {
    size_t n = (size_t)((w + 3) / 4) * ((h + 3) / 4) * d * t->block_bytes(dst);
    void *z = calloc(1, n ? n : 1);
    if (dims == 2) r_glCompressedTexImage2D(target, level, dst, w, h, 0, (GLsizei)n, z);
    else r_glCompressedTexImage3D(target, level, dst, w, h, d, 0, (GLsizei)n, z);
    free(z);
  } else {
    GLenum f = raw_upload_format(dst);
    if (dims == 2) r_glTexImage2D(target, level, dst, w, h, 0, f, GL_UNSIGNED_BYTE, NULL);
    else r_glTexImage3D(target, level, dst, w, h, d, 0, f, GL_UNSIGNED_BYTE, NULL);
  }
  unpack_pop(&st);
}
static void a_glTexImage2D(GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLint border, GLenum f,
                           GLenum type, const void *data) {
  if (!target_for((GLenum)ifmt)) { r_glTexImage2D(target, level, ifmt, w, h, border, f, type, data); return; }
  if (data) FNSDL_LOG("fngl", "texture: glTexImage2D with BC format 0x%x and data (desktop driver compression) not supported", ifmt);
  alloc_level(2, target, level, (GLenum)ifmt, w, h, 1);
}
static void a_glTexImage3D(GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLsizei d, GLint border,
                           GLenum f, GLenum type, const void *data) {
  if (!target_for((GLenum)ifmt)) { r_glTexImage3D(target, level, ifmt, w, h, d, border, f, type, data); return; }
  if (data) FNSDL_LOG("fngl", "texture: glTexImage3D with BC format 0x%x and data (desktop driver compression) not supported", ifmt);
  alloc_level(3, target, level, (GLenum)ifmt, w, h, d);
}

static void a_glDeleteTextures(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) map_del(names[i]);
  r_glDeleteTextures(n, names);
}
// Copies between two converted textures of the same format work as they are.
// A copy of raw BC blocks into a converted texture (GPU compression) cannot:
// it is skipped; the game's GPU compression self-test then fails and the game
// compresses on the CPU (uploads, which are converted here).
static void a_glCopyImageSubData(GLuint s, GLenum st, GLint sl, GLint sx, GLint sy, GLint sz, GLuint d, GLenum dt,
                                 GLint dl, GLint dx, GLint dy, GLint dz, GLsizei w, GLsizei h, GLsizei dd) {
  GLenum sb = st == GL_RENDERBUFFER ? 0 : map_get(s), db = dt == GL_RENDERBUFFER ? 0 : map_get(d);
  if (db && sb != db) {
    static int once;
    if (!once++) FNSDL_LOG("fngl", "texture: copy of BC blocks into converted texture skipped (GPU compression off)");
    return;
  }
  r_glCopyImageSubData(s, st, sl, sx, sy, sz, d, dt, dl, dx, dy, dz, w, h, dd);
}

// ---------------------------------------------------------------- public
void fngl_tex_init(int (*driver_has)(const char *), void *(*driver)(const char *)) {
  drv = driver;
  r_glGetIntegerv = driver("glGetIntegerv");
  int s3tc = driver_has("GL_EXT_texture_compression_s3tc");
  int s3tc_srgb = driver_has("GL_EXT_texture_compression_s3tc_srgb") || driver_has("GL_EXT_texture_sRGB");
  int rgtc = driver_has("GL_EXT_texture_compression_rgtc");
  const char *force = getenv("FNGL_TEXTURE_TARGET");
  int disabled = force && !strcmp(force, "native");
  for (size_t i = 0; i < NBC; i++) {
    GLenum f = bc_formats[i];
    native_has[i] = f == BC4 || f == BC4_SNORM || f == BC5 || f == BC5_SNORM ? rgtc
                  : f == BC1_SRGB || f == BC1_SRGB_A || f == BC2_SRGB || f == BC3_SRGB ? s3tc && s3tc_srgb
                  : s3tc;
    if (force && !disabled) native_has[i] = 0;  // forced target: convert everything (testing)
    emu[i] = NULL;
    if (native_has[i] || disabled) continue;
    for (size_t k = 0; k < NTARGETS; k++) {
      if (force && strcmp(force, targets[k].name)) continue;
      if (targets[k].usable(driver_has) && targets[k].format_for(f)) { emu[i] = &targets[k]; break; }
    }
    if (emu[i]) any_emulated = 1;
  }
  // none of the game's formats (BC1, BC3, BC4, BC5) native and ETC2 the target: the game writes ETC2 itself
  static const GLenum game_formats[] = {BC1_RGB, BC3, BC4, BC5};
  int none_native = !disabled;
  for (size_t i = 0; i < 4; i++) {
    int k = bc_index(game_formats[i]);
    none_native &= !native_has[k] && emu[k] == &targets[0];
  }
  if (none_native && tex_direct_enable())
    for (size_t i = 0; i < NBC; i++)
      if (emu[i] && tex_direct_format(bc_formats[i])) emu[i] = &direct_target;
  for (size_t i = 0; i < NBC; i++)
    FNSDL_LOG("fngl", "texture format 0x%x: %s", bc_formats[i], native_has[i] ? "native" : emu[i] ? emu[i]->name : "unavailable");
}

int fngl_tex_family(int family) {
  // the game may use the extension when every format of it can be stored
  static const GLenum s3tc[] = {BC1_RGB, BC1_RGBA, BC2, BC3}, rgtc[] = {BC4, BC5};
  const GLenum *l = family == FNGL_TEX_S3TC ? s3tc : rgtc;
  size_t n = family == FNGL_TEX_S3TC ? 4 : 2;
  for (size_t i = 0; i < n; i++) {
    int k = bc_index(l[i]);
    if (!native_has[k] && !emu[k]) return 0;
  }
  return 1;
}

void *fngl_tex_adapter(const char *name) {
  if (!any_emulated) return NULL;  // every format native: the game calls the driver directly
  if (!r_glCompressedTexImage2D) {
    r_glCompressedTexImage2D = drv("glCompressedTexImage2D");
    r_glCompressedTexImage3D = drv("glCompressedTexImage3D");
    r_glCompressedTexSubImage2D = drv("glCompressedTexSubImage2D");
    r_glCompressedTexSubImage3D = drv("glCompressedTexSubImage3D");
    r_glTexImage2D = drv("glTexImage2D");
    r_glTexImage3D = drv("glTexImage3D");
  }
#define A(n) if (!strcmp(name, #n)) { r_##n = drv(name); return (void *)a_##n; }
  A(glTexStorage2D) A(glTexStorage3D) A(glCompressedTexImage2D) A(glCompressedTexImage3D)
  A(glCompressedTexSubImage2D) A(glCompressedTexSubImage3D) A(glDeleteTextures) A(glTexImage2D) A(glTexImage3D)
#undef A
  return NULL;
}

void *fngl_tex_copy_adapter(void *real_copy) {
  if (!any_emulated) return NULL;
  r_glCopyImageSubData = real_copy;
  return (void *)a_glCopyImageSubData;
}

int fngl_tex_direct(GLenum target) {
  GLenum bc = fngl_tex_emulated(target);
  return bc && target_for(bc) == &direct_target;
}

GLenum fngl_tex_emulated(GLenum target) { return any_emulated ? map_get(bound(target)) : 0; }

int fngl_tex_level_size(GLenum bc, GLint w, GLint h, GLint d) {
  return ((w + 3) / 4) * ((h + 3) / 4) * d * bc_block_bytes(bc);
}

// ---------------------------------------------------------------- Vulkan renderer without BC (nvk_res.c)
// The same BC -> ETC2/EAC conversion as the GLES path (BC the game did not compress itself, see tex/direct.c).

void *fngl_tex_to_etc2(unsigned bc, const void *src, int bw, int bh, int d, size_t *n) {
  GLenum dst = etc2_format_for(bc);
  if (!dst) return NULL;
  return convert(&targets[0], bc, dst, src, bw, bh, d, n);
}
