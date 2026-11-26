// Native Vulkan renderer: the game's GPU resources (buffers, uniform buffers, textures, texture arrays, 3D
// textures, samplers, framebuffer objects). Objects keep the game's layout (nvk.h); the "GL name" fields hold
// our names (nvk_main.c tables). Destructors stay the game's: their glDelete* calls free ours (nvk_gl.c).
#include <stdlib.h>
#include <string.h>

#include "../fngl_tex.h"
#include "../tex/direct.h"
#include "nvk.h"

// ---------------------------------------------------------------- formats
// BitmapFormat: 0 RGBA8, 1 BGRA8, 2 RGB565, 3 R8, 4 RG8, 5 RGBA16UI, 6 RGBA32UI, 7 RG32UI, 8 BC1, 9 BC3, 10 BC4,
// 11 BC5, 12 D16, 13 D24S8, 14 R16UI (tables at 0xfe0238/0xfe0278 in 2.1.19)
static const unsigned bc_gl[4] = {0x83f0, 0x83f3, 0x8dbb, 0x8dbd};

VkFormat nvk_bitmap_format(int fmt, unsigned *bc) {
  *bc = 0;
  switch (fmt) {
    case 0: return VK_FORMAT_R8G8B8A8_UNORM;
    case 1: return VK_FORMAT_B8G8R8A8_UNORM;
    case 2: return VK_FORMAT_R5G6B5_UNORM_PACK16;
    case 3: return VK_FORMAT_R8_UNORM;
    case 4: return VK_FORMAT_R8G8_UNORM;
    case 5: return VK_FORMAT_R16G16B16A16_UINT;
    case 6: return VK_FORMAT_R32G32B32A32_UINT;
    case 7: return VK_FORMAT_R32G32_UINT;
    case 8: case 9: case 10: case 11:
      if (!vk.has_bc) {  // Mali: the game's own ETC2/EAC blocks (tex/direct.c), else converted (fngl_tex.c)
        *bc = bc_gl[fmt - 8];
        static const VkFormat etc[4] = {VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK, VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK,
                                        VK_FORMAT_EAC_R11_UNORM_BLOCK, VK_FORMAT_EAC_R11G11_UNORM_BLOCK};
        return etc[fmt - 8];
      }
      static const VkFormat bcf[4] = {VK_FORMAT_BC1_RGB_UNORM_BLOCK, VK_FORMAT_BC3_UNORM_BLOCK,
                                      VK_FORMAT_BC4_UNORM_BLOCK, VK_FORMAT_BC5_UNORM_BLOCK};
      return bcf[fmt - 8];
    case 12: return VK_FORMAT_D16_UNORM;
    case 13: return vk.depth_stencil_format;
    case 14: return VK_FORMAT_R16_UINT;
    default: return VK_FORMAT_R8G8B8A8_UNORM;
  }
}

static int compressed(int fmt) { return fmt >= 8 && fmt <= 11; }

// bytes per texel, or per 4x4 block for compressed formats (the game's data, before any conversion)
static uint32_t fmt_bytes(int fmt) {
  static const uint8_t b[15] = {4, 4, 2, 1, 2, 8, 16, 8, 8, 16, 8, 16, 2, 4, 2};
  return fmt >= 0 && fmt < 15 ? b[fmt] : 4;
}

// bytes of one row (of blocks) and number of rows of a w x h region
static void region_rows(int fmt, uint32_t w, uint32_t h, size_t *row, uint32_t *rows) {
  if (compressed(fmt)) {
    *row = (size_t)((w + 3) / 4) * fmt_bytes(fmt);
    *rows = (h + 3) / 4;
  } else {
    *row = (size_t)w * fmt_bytes(fmt);
    *rows = h;
  }
}

// ---------------------------------------------------------------- textures
nvk_tex *nvk_tex_get(uint32_t name) { return nvk_name_get(NVK_T_TEX, name); }

nvk_tex *nvk_bitmap_tex(const void *bm) {
  if (!bm) return NULL;
  uint32_t kind = F32(bm, BM_KIND);
  if (kind == 2) bm = FP(bm, BM_NAME);  // another bitmap stands in (bindTexture does the same)
  return bm ? nvk_tex_get(F32(bm, BM_NAME)) : NULL;
}

static uint32_t full_levels(uint32_t w, uint32_t h, uint32_t d) {
  uint32_t n = 1, m = w > h ? w : h;
  if (d > m) m = d;
  while (m > 1) m >>= 1, n++;
  return n;
}

static nvk_tex *tex_new(int fmt, VkImageViewType type, uint32_t w, uint32_t h, uint32_t d, uint32_t layers,
                        uint32_t levels, uint32_t *name) {
  nvk_tex *t = calloc(1, sizeof *t);
  t->fmt = fmt;
  t->type = type;
  VkFormat vf = nvk_bitmap_format(fmt, &t->bc);
  if (!levels) levels = 1;
  uint32_t full = full_levels(w, h, type == VK_IMAGE_VIEW_TYPE_3D ? d : 1);
  if (levels > full) levels = full;
  t->img = vk_image_create(vf, type, w ? w : 1, h ? h : 1, d, layers, levels);
  if (getenv("NVK_TRACE") && w * h >= 256 * 256)
    nvk_log("texture fmt %d %ux%ux%u layers %u levels %u (asked %u) type %d", fmt, w, h, d, layers, levels,
            levels, (int)type);
  *name = nvk_name_new(NVK_T_TEX, t);
  return t;
}

void nvk_tex_free(uint32_t name) {
  nvk_tex *t = nvk_tex_get(name);
  if (!t) return;
  if (t->img) {
    nvk_image_gone(t->img);
    vk_image_destroy(t->img);
  }
  free(t);
  nvk_name_del(NVK_T_TEX, name);
}

// Uploads a w x h region of `level` (layer or z = `z`) with rows `stride` bytes apart (negative: bottom-up in
// memory; 0: tightly packed). Compressed formats: block rows; converted to ETC2/EAC on GPUs without BC.
static void tex_upload(nvk_tex *t, uint32_t level, int x, int y, int z, uint32_t w, uint32_t h, uint32_t d,
                       const uint8_t *data, long stride) {
  if (!t || !t->img || !data || !w || !h || level >= t->img->levels) return;
  size_t row;
  uint32_t rows;
  region_rows(t->fmt, w, h, &row, &rows);
  uint8_t *packed = NULL;
  if (stride && stride != (long)row) {
    packed = malloc(row * rows * d);
    size_t n = (size_t)(stride < 0 ? -stride : stride) < row ? (size_t)(stride < 0 ? -stride : stride) : row;
    for (uint32_t i = 0; i < rows * d; i++) memcpy(packed + i * row, data + (long)i * stride, n);
    data = packed;
  }
  size_t size = row * rows * d;
  uint32_t row_texels = compressed(t->fmt) ? (w + 3) & ~3u : w, img_rows = compressed(t->fmt) ? (h + 3) & ~3u : h;
  if (t->bc && (!tex_direct_active() || tex_direct_foreign())) {  // foreign BC: the splash screen, defaults
    size_t n = 0;
    void *etc = fngl_tex_to_etc2(t->bc, data, (int)(row_texels / 4), (int)(img_rows / 4), (int)d, &n);
    if (etc) vk_upload(t->img, level, x, y, z, w, h, d, etc, n, row_texels, img_rows);
    free(etc);
  } else {
    vk_upload(t->img, level, x, y, z, w, h, d, data, size, row_texels, img_rows);
  }
  free(packed);
}

static void gen_mips(vk_image *img) {
  if (!img || img->levels < 2) return;
  if (vk_end_pass_hook) vk_end_pass_hook();
  vk_materialize(img);
  VkCommandBuffer c = vk_cmd();
  // level 0 -> source, the rest -> destination
  VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  b.image = img->image;
  b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
  b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
  b.oldLayout = img->layout;
  b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  b.subresourceRange = (VkImageSubresourceRange){img->aspect, 0, img->levels, 0, img->layers};
  vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1,
                       &b);
  for (uint32_t l = 1; l < img->levels; l++) {
    b.subresourceRange = (VkImageSubresourceRange){img->aspect, l - 1, 1, 0, img->layers};
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &b);
    int32_t sw = img->w >> (l - 1), sh = img->h >> (l - 1), dw = img->w >> l, dh = img->h >> l;
    VkImageBlit r = {{img->aspect, l - 1, 0, img->layers}, {{0, 0, 0}, {sw ? sw : 1, sh ? sh : 1, 1}},
                     {img->aspect, l, 0, img->layers}, {{0, 0, 0}, {dw ? dw : 1, dh ? dh : 1, 1}}};
    vkCmdBlitImage(c, img->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, img->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                   1, &r, VK_FILTER_LINEAR);
  }
  // all levels -> one layout again: the last is still TRANSFER_DST, the others TRANSFER_SRC
  b.subresourceRange = (VkImageSubresourceRange){img->aspect, 0, img->levels - 1, 0, img->layers};
  b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  b.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
  b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &b);
  img->layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  vk_mark_dirty(img);
}

// VideoBitmapOpenGL::generateTextureObject(data, flip): the texture of a new bitmap, level 0 from data
// (flip: rows reversed, 4 bytes per texel as the game assumes), other levels empty.
static void v_generateTextureObject(void *bm, const uint8_t *data, int flip) {
  uint32_t w = F32(bm, BM_W), h = F32(bm, BM_H), name;
  int fmt = (int)F32(bm, BM_FORMAT);
  nvk_tex *t = tex_new(fmt, VK_IMAGE_VIEW_TYPE_2D, w, h, 1, 1, F8(bm, BM_LEVELS), &name);
  F32(bm, BM_NAME) = name;
  if (!data) return;
  if (flip & 1) {  // last row first
    long row = (long)w * 4;
    tex_upload(t, 0, 0, 0, 0, w, h, 1, data + row * (h ? h - 1 : 0), -row);
  } else {
    tex_upload(t, 0, 0, 0, 0, w, h, 1, data, 0);
  }
}

// VideoBitmapOpenGL::setSize(w, h): new size, contents undefined (the game draws it again)
static void v_bitmapSetSize(void *bm, uint32_t w, uint32_t h) {
  nvk_log("bitmap %p resized to %ux%u", bm, w, h);
  F32(bm, BM_W) = w;
  F32(bm, BM_H) = h;
  FF(bm, BM_INV) = 1.0f / (float)w;
  FF(bm, BM_INV + 4) = 1.0f / (float)h;
  uint32_t old = F32(bm, BM_NAME), name;
  nvk_tex *o = nvk_tex_get(old);
  tex_new((int)F32(bm, BM_FORMAT), VK_IMAGE_VIEW_TYPE_2D, w, h, 1, 1, o && o->img ? o->img->levels : 1, &name);
  F32(bm, BM_NAME) = name;
  nvk_tex_free(old);
}

// VideoBitmapOpenGL::updateMipmapPixels(x, y, w, h, level, data, stride)
static void v_bitmapUpdate(void *bm, uint32_t x, uint32_t y, uint32_t w, uint32_t h, uint32_t level,
                           const uint8_t *data, int stride) {
  if (!w || !h) return;
  if (getenv("NVK_TRACE")) {  // uploads per texture and level, every 500th logged
    static unsigned long n;
    nvk_tex *t = nvk_tex_get(F32(bm, BM_NAME));
    if (t && t->img && t->img->levels > 1 && (level > 0 || n % 500 == 0))
    if (t && t->fmt == 0 && stride >= (int)(w * 4)) {  // RGBA only: the mean alpha of the data
      unsigned long as = 0;
      for (uint32_t r = 0; r < h; r++)
        for (uint32_t c = 0; c < w; c++) as += data[(long)r * stride + c * 4 + 3];
      nvk_log("upload tex %u level %u (%u,%u) %ux%u stride %d mean alpha %.1f", F32(bm, BM_NAME), level, x, y, w, h,
              stride, (double)as / (w * h));
    }
    n++;
  }
  tex_upload(nvk_tex_get(F32(bm, BM_NAME)), level, (int)x, (int)y, 0, w, h, 1, data, stride);
}

static void v_bitmapGenerateMipmaps(void *bm) {
  if (getenv("NVK_TRACE")) nvk_log("generateMipmaps tex %u levels %u", F32(bm, BM_NAME), F8(bm, BM_LEVELS));
  if (F8(bm, BM_LEVELS) < 2) return;
  nvk_tex *t = nvk_tex_get(F32(bm, BM_NAME));
  if (t && !compressed(t->fmt)) gen_mips(t->img);
}

// Level 0 of a texture into host memory, the game's format (RGBA8 view for copyToMemoryBitmap). 0 = not possible.
// raw: the game's sprite atlas cache (copyDataRaw outside the GPU compression self-test) needs the blocks as stored
static int tex_read_raw(nvk_tex *t, uint8_t *out, size_t size, int raw) {
  if (!t || !t->img) return 0;
  if (raw && compressed(t->fmt) && (!t->bc || tex_direct_active())) {
    vk_download(t->img, 0, 0, 0, 0, t->img->w, t->img->h, out, size);
    return 1;
  }
  // BC readback lets the game's self-test pass and switches on its GPU block compression, which lost the device in
  // the GL-on-Vulkan renderer (FNative Lab, 2026-11-20): zeros, the game compresses on the CPU. NVK_BC_READBACK=1 tries it.
  static int bc_readback = -1;
  if (bc_readback < 0) bc_readback = getenv("NVK_BC_READBACK") && atoi(getenv("NVK_BC_READBACK"));
  // the game's own ETC2 blocks (tex/direct.c) are read as they are: its sprite atlas cache. Its GPU compression
  // still fails its self-test: nvk does not copy rendered blocks into a compressed texture (must stay so with it).
  if (t->bc && tex_direct_active()) {
    vk_download(t->img, 0, 0, 0, 0, t->img->w, t->img->h, out, size);
    return 1;
  }
  if (compressed(t->fmt) && !bc_readback) {
    memset(out, 0, size);
    return 1;
  }
  if (t->bc) {
    static int told;
    if (!told++) nvk_log("readback of a BC texture stored as ETC2: zeros (as the GLES path on Mali)");
    memset(out, 0, size);
    return 1;
  }
  vk_download(t->img, 0, 0, 0, 0, t->img->w, t->img->h, out, size);
  return 1;
}
static int tex_read(nvk_tex *t, uint8_t *out, size_t size) { return tex_read_raw(t, out, size, 0); }

// VideoBitmapOpenGL::copyToMemoryBitmap() -> unique_ptr<MemoryBitmap> {u32 w, h; u8 *data; size, capacity; 0}
void nvk_t_copyToMemoryBitmap_c(const void *bm, void **ret) {
  uint32_t w = F32(bm, BM_W), h = F32(bm, BM_H);
  size_t n = (size_t)w * h * 4;
  static void *(*new_arr)(size_t);
  if (!new_arr) new_arr = (void *(*)(size_t))nvk_sym("_Znam");
  uint8_t *px = n ? new_arr(n) : NULL;
  nvk_tex *t = nvk_tex_get(F32(bm, BM_NAME));
  if (px) {
    if (t && (t->fmt == 0 || t->fmt == 1)) {
      tex_read(t, px, n);
      if (t->fmt == 1)
        for (size_t i = 0; i < n; i += 4) {
          uint8_t s = px[i];
          px[i] = px[i + 2];
          px[i + 2] = s;
        }
    } else if (t && compressed(t->fmt)) {
      // the game's GPU compression self-test reads its BC result decoded: zeros, so the test fails and the game
      // compresses on the CPU (intended: GPU compression lost the device in FNative Lab, see tex_read_raw)
      static int told;
      if (!told++) nvk_log("copyToMemoryBitmap of compressed format %d %ux%u: zeros (GPU compression stays off)", t->fmt, w, h);
      memset(px, 0, n);
    } else {
      char bt[512];
      tex_direct_backtrace(bt, sizeof bt);
      nvk_log("copyToMemoryBitmap of format %d %ux%u: zeros; callers:%s", t ? t->fmt : -1, w, h, bt);
      memset(px, 0, n);
    }
  }
  void *mb = nvk_game_new(0x28);
  F32(mb, 0) = w;
  F32(mb, 4) = h;
  FP(mb, 8) = px;
  F64(mb, 0x10) = n;
  F64(mb, 0x18) = n;
  F64(mb, 0x20) = 0;
  *ret = mb;
}

// VideoBitmapOpenGL::copyDataRaw(std::vector<u8>&, offset): level 0 as stored, appended at offset
static void v_copyDataRaw(const void *bm, void *vec, size_t offset) {
  static void (*resize)(void *, size_t);
  if (!resize) resize = (void (*)(void *, size_t))nvk_sym("_ZNSt6vectorIhSaIhEE6resizeEm");
  nvk_tex *t = nvk_tex_get(F32(bm, BM_NAME));
  int fmt = (int)F32(bm, BM_FORMAT);
  size_t row;
  uint32_t rows;
  region_rows(fmt, F32(bm, BM_W), F32(bm, BM_H), &row, &rows);
  size_t n = row * rows;
  resize(vec, offset + n);
  uint8_t *dst = (uint8_t *)FP(vec, 0) + offset;
  // the GPU compression self-test reads its BC result through CustomVideoBitmap::copyToMemoryBitmap -> here: zeros
  // for it keep the game on CPU compression (as the GLES path on GPUs without BC); every other reader (the sprite
  // atlas cache) gets the real blocks — zeros there wrote a cache of black sprites (0.141 made the cache default)
  static uintptr_t test_lo, test_hi;
  if (!test_lo) {
    test_lo = (uintptr_t)nvk_sym("_ZNK17CustomVideoBitmap18copyToMemoryBitmapEv");
    test_hi = test_lo + nvk_sym_size("_ZNK17CustomVideoBitmap18copyToMemoryBitmapEv");
    if (!test_lo) test_lo = 1;
  }
  uintptr_t ra = (uintptr_t)__builtin_extract_return_addr(__builtin_return_address(0));
  int self_test = ra > test_lo && ra <= test_hi;
  if (!tex_read_raw(t, dst, n, !self_test)) memset(dst, 0, n);
}

// ---------------------------------------------------------------- texture arrays and 3D textures
// createVideoBitmapArray(w, h, layers, options): 40-byte VideoBitmapArrayOpenGL
void nvk_t_createVideoBitmapArray_c(void *gi, uint32_t w, uint32_t h, uint32_t layers, const uint8_t *opts,
                                    void **ret) {
  (void)gi;
  static void *vt;
  if (!vt) vt = nvk_vtable("_ZTV22VideoBitmapArrayOpenGL");
  void *a = nvk_game_new(0x28);
  memset(a, 0, 0x28);
  FP(a, 0) = vt;
  F32(a, 0x8) = nvk_next_resource_id();
  F32(a, BA_W) = w;
  F32(a, BA_H) = h;
  F32(a, BA_LAYERS) = layers;
  memcpy((char *)a + 0x18, opts, 8);
  F32(a, 0x20) = *(const uint32_t *)(opts + 8);
  uint32_t name;
  tex_new((int)F32(a, BA_FORMAT), VK_IMAGE_VIEW_TYPE_2D_ARRAY, w, h, 1, layers, F8(a, BA_LEVELS), &name);
  F32(a, BA_NAME) = name;
  *ret = a;
}

// VideoBitmapArrayOpenGL::updateMipmapPixels(layer, level, data): a whole level of one layer
static void v_arrayUpdate(void *a, uint32_t layer, uint32_t level, const uint8_t *data) {
  nvk_tex *t = nvk_tex_get(F32(a, BA_NAME));
  uint32_t w = F32(a, BA_W) >> level, h = F32(a, BA_H) >> level;
  tex_upload(t, level, 0, 0, (int)layer, w ? w : 1, h ? h : 1, 1, data, 0);
}

// createVideoBitmap3D(data, w, h, slices, stride, orientation, options): the data is one 2D image with the
// slices side by side (orientation 0) or stacked (1)
void nvk_t_createVideoBitmap3D_c(void *gi, const uint8_t *data, uint32_t w, uint32_t h, uint32_t d, uint32_t stride,
                                 uint32_t orientation, const uint8_t *opts, void **ret) {
  (void)gi;
  static void *vt;
  if (!vt) vt = nvk_vtable("_ZTV19VideoBitmap3DOpenGL");
  if (orientation) h /= d;
  else w /= d;
  void *b = nvk_game_new(0x28);
  memset(b, 0, 0x28);
  FP(b, 0) = vt;
  F32(b, 0x8) = nvk_next_resource_id();
  F32(b, 0xc) = w;
  F32(b, 0x10) = h;
  F32(b, 0x14) = d;
  memcpy((char *)b + 0x18, opts, 8);
  F32(b, 0x20) = *(const uint32_t *)(opts + 8);
  int fmt = (int)F32(b, 0x1c);
  uint32_t name;
  nvk_tex *t = tex_new(fmt, VK_IMAGE_VIEW_TYPE_3D, w, h, d, 1, 1, &name);
  F32(b, B3_NAME) = name;
  if (data && t) {
    size_t row = (size_t)w * fmt_bytes(fmt);
    uint8_t *vol = malloc(row * h * d);
    for (uint32_t z = 0; z < d; z++)
      for (uint32_t y = 0; y < h; y++) {
        const uint8_t *src = orientation ? data + ((size_t)z * h + y) * row : data + z * row + (size_t)y * stride;
        memcpy(vol + ((size_t)z * h + y) * row, src, row);
      }
    if (getenv("NVK_TRACE")) {
      char line[512];
      int k = snprintf(line, sizeof line, "3D texture %ux%ux%u fmt %d stride %u orientation %u:", w, h, d, fmt, stride,
                       orientation);
      for (int i = 0; i < 6 && k < 480; i++) {  // texels (x, y, z) = (i, i, i)*3
        uint32_t x = i * 3 % w, y = i * 3 % h, z = i * 3 % d;
        const uint8_t *px = vol + (((size_t)z * h + y) * w + x) * fmt_bytes(fmt);
        k += snprintf(line + k, sizeof line - k, " (%u,%u,%u)=%u,%u,%u,%u", x, y, z, px[0], px[1], px[2], px[3]);
      }
      nvk_log("%s", line);
    }
    tex_upload(t, 0, 0, 0, 0, w, h, d, vol, 0);
    free(vol);
  }
  *ret = b;
}

// ---------------------------------------------------------------- samplers
VkSampler nvk_sampler_get(uint32_t name) {
  nvk_sampler *s = nvk_name_get(NVK_T_SAMPLER, name);
  return s ? s->s : VK_NULL_HANDLE;
}

static void sampler_destroy(void *p) {
  nvk_sampler *s = p;
  vkDestroySampler(vk.dev, s->s, NULL);
  free(s);
}

void nvk_sampler_free(uint32_t name) {
  nvk_sampler *s = nvk_name_get(NVK_T_SAMPLER, name);
  if (!s) return;
  nvk_name_del(NVK_T_SAMPLER, name);
  vk_defer(sampler_destroy, s);  // the GPU may still use it in this frame
}

// createSamplerState(spec): 16-byte SamplerStateOpenGL
void nvk_t_createSamplerState_c(void *gi, const uint8_t *spec, void **ret) {
  (void)gi;
  static void *vt;
  if (!vt) vt = nvk_vtable("_ZTV18SamplerStateOpenGL");
  static const VkSamplerAddressMode wrap[4] = {VK_SAMPLER_ADDRESS_MODE_REPEAT, VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT,
                                               VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                                               VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER};
  const uint32_t *u = (const uint32_t *)spec;
  const float *f = (const float *)spec;
  VkSamplerCreateInfo ci = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
  ci.minFilter = u[0] == 1 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
  ci.magFilter = u[1] == 1 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
  ci.mipmapMode = u[2] == 1 ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
  ci.addressModeU = wrap[u[3] & 3];
  ci.addressModeV = wrap[u[4] & 3];
  ci.addressModeW = wrap[u[5] & 3];
  ci.mipLodBias = f[6];
  ci.minLod = f[7];
  ci.maxLod = f[8];
  // border colours of Vulkan 1.1: the nearest of transparent black, opaque black, opaque white
  const float *bc = f + 9;
  ci.borderColor = bc[3] < 0.5f                          ? VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK
                   : bc[0] + bc[1] + bc[2] < 1.5f         ? VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK
                                                          : VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE;
  if (getenv("NVK_TRACE"))
    nvk_log("sampler min %u mag %u mip %u wrap %u/%u/%u bias %.2f lod %.2f..%.2f", u[0], u[1], u[2], u[3], u[4], u[5],
            f[6], f[7], f[8]);
  if (ci.mipLodBias > vk.props.limits.maxSamplerLodBias) ci.mipLodBias = vk.props.limits.maxSamplerLodBias;
  if (ci.mipLodBias < -vk.props.limits.maxSamplerLodBias) ci.mipLodBias = -vk.props.limits.maxSamplerLodBias;
  nvk_sampler *s = calloc(1, sizeof *s);
  VKCHECK(vkCreateSampler(vk.dev, &ci, NULL, &s->s));
  void *o = nvk_game_new(0x10);
  FP(o, 0) = vt;
  F32(o, 0x8) = nvk_next_resource_id();
  F32(o, SS_NAME) = nvk_name_new(NVK_T_SAMPLER, s);
  *ret = o;
}

// ---------------------------------------------------------------- buffers
nvk_buf *nvk_buf_get(uint32_t name) { return nvk_name_get(NVK_T_BUF, name); }

static void view_destroy(void *p) {
  vkDestroyBufferView(vk.dev, (VkBufferView)(uintptr_t)p, NULL);
}

void nvk_buf_free(uint32_t name) {
  nvk_buf *b = nvk_buf_get(name);
  if (!b) return;
  vk_retire(b->cur);
  if (b->view) vk_defer(view_destroy, (void *)(uintptr_t)b->view);
  free(b);
  nvk_name_del(NVK_T_BUF, name);
}

// New memory for the buffer (GL orphaning): the old memory is given back when the GPU is done with this frame.
static void buf_orphan(nvk_buf *b, VkDeviceSize size) {
  vk_retire(b->cur);
  b->cur = vk_alloc(size ? size : 256);
  b->size = size;
}

VkBufferView nvk_buf_view(nvk_buf *b) {
  if (!b || !b->cur.buf) return VK_NULL_HANDLE;
  if (b->view && b->view_buf == b->cur.buf && b->view_off == b->cur.off) return b->view;
  if (b->view) vk_defer(view_destroy, (void *)(uintptr_t)b->view);
  VkBufferViewCreateInfo ci = {VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO};
  ci.buffer = b->cur.buf;
  ci.format = b->texel;
  ci.offset = b->cur.off;
  ci.range = b->size ? b->size : VK_WHOLE_SIZE;
  uint32_t bpp, bw;
  bpp = vk_format_block(b->texel, &bw);
  ci.range -= ci.range % bpp;
  if (ci.range / bpp > vk.props.limits.maxTexelBufferElements) ci.range = (VkDeviceSize)vk.props.limits.maxTexelBufferElements * bpp;
  b->view = VK_NULL_HANDLE;
  VKCHECK(vkCreateBufferView(vk.dev, &ci, NULL, &b->view));
  b->view_buf = b->cur.buf;
  b->view_off = b->cur.off;
  return b->view;
}

static VkFormat texel_format(uint32_t f) {
  switch (f) {
    case 1: return VK_FORMAT_R8_UINT;
    case 2: return VK_FORMAT_R16_UINT;
    case 3: return VK_FORMAT_R32_UINT;
    case 4: return VK_FORMAT_R8G8B8A8_UINT;
    case 5: return VK_FORMAT_R32_SFLOAT;
    case 6: return VK_FORMAT_R32G32B32A32_SFLOAT;
    default:
      nvk_log("texel buffer format %u", f);
      return VK_FORMAT_R32_UINT;
  }
}

// createVideoBuffer(type, options): 64-byte VideoBufferOpenGL; memory comes with the first data
void nvk_t_createVideoBuffer_c(void *gi, uint32_t type, uint64_t o0, uint64_t o1, void **ret) {
  static void *vt;
  static uint64_t *serial;
  if (!vt) {
    vt = nvk_vtable("_ZTV17VideoBufferOpenGL");
    serial = nvk_sym("_ZN17VideoBufferOpenGL12nextUniqueIdE");
  }
  void *o = nvk_game_new(0x40);
  memset(o, 0, 0x40);
  FP(o, 0) = vt;
  F32(o, 0x8) = nvk_next_resource_id();
  F32(o, VB_TYPE) = type;
  F64(o, 0x10) = o0;
  F64(o, 0x18) = o1;
  FP(o, VB_GI) = gi;
  F64(o, VB_SERIAL) = (*serial)++;
  nvk_buf *b = calloc(1, sizeof *b);
  b->kind = type == 0 ? NVK_BUF_VERTEX : type == 1 ? NVK_BUF_INDEX : NVK_BUF_TEXEL;
  if (type == 2) b->texel = texel_format(F32(o, VB_FORMAT));
  b->cur.cls = 0xffff;
  F32(o, VB_NAME) = nvk_name_new(NVK_T_BUF, b);
  *ret = o;
}

// VideoBufferOpenGL::mapBuffer(offset, length, discard): writes without synchronisation, as the game asks
static void *v_mapBuffer(void *o, size_t off, size_t len, int discard) {
  nvk_buf *b = nvk_buf_get(F32(o, VB_NAME));
  if (!b) return NULL;
  if ((discard & 1) || !b->cur.ptr) buf_orphan(b, F64(o, VB_SIZE) > off + len ? F64(o, VB_SIZE) : off + len);
  if (off + len > b->cur.size) {
    nvk_log("map of %zu+%zu bytes beyond the buffer (%llu)", off, len, (unsigned long long)b->cur.size);
    return NULL;
  }
  return b->cur.ptr + off;
}

static void v_unmapBuffer(void *o) { (void)o; }

// VideoBufferOpenGL::updateBuffer(size, data): smaller than the buffer -> map with discard and copy; larger -> new
// size (the game's VB_SIZE grows)
static void v_updateBuffer(void *o, size_t size, const void *data) {
  if (!size) return;
  nvk_buf *b = nvk_buf_get(F32(o, VB_NAME));
  if (!b) return;
  if (size > F64(o, VB_SIZE)) F64(o, VB_SIZE) = size;
  buf_orphan(b, F64(o, VB_SIZE));
  if (data && b->cur.ptr) memcpy(b->cur.ptr, data, size);
}

// createUniformBuffer(size): 24-byte UniformBufferOpenGL
void nvk_t_createUniformBuffer_c(void *gi, uint32_t size, void **ret) {
  (void)gi;
  static void *vt;
  if (!vt) vt = nvk_vtable("_ZTV19UniformBufferOpenGL");
  void *o = nvk_game_new(0x18);
  FP(o, 0) = vt;
  F32(o, 0x8) = nvk_next_resource_id();
  F32(o, UB_SIZE) = size;
  nvk_buf *b = calloc(1, sizeof *b);
  b->kind = NVK_BUF_UNIFORM;
  b->cur.cls = 0xffff;
  buf_orphan(b, size);
  memset(b->cur.ptr, 0, size);
  F32(o, UB_NAME) = nvk_name_new(NVK_T_BUF, b);
  *ret = o;
}

// UniformBufferOpenGL::updateBuffer(data, size): new memory each time (the GPU may still read the old values)
static void v_uniformUpdate(void *o, const void *data, uint32_t size) {
  nvk_buf *b = nvk_buf_get(F32(o, UB_NAME));
  if (!b) return;
  buf_orphan(b, size);
  if (data && b->cur.ptr) memcpy(b->cur.ptr, data, size);
}

// ---------------------------------------------------------------- framebuffer objects
void *nvk_fb_get(uint32_t name) { return nvk_name_get(NVK_T_FB, name); }

// FramebufferOpenGL::generateFramebufferObject(): the attachments are the game's bitmaps, read at bind time
static void v_generateFramebufferObject(void *fb) { F32(fb, FB_NAME) = nvk_name_new(NVK_T_FB, fb); }

// ---------------------------------------------------------------- install
void nvk_res_install(void) {
  extern void nvk_t_createUniformBuffer(void), nvk_t_createVideoBuffer(void), nvk_t_createVideoBitmapArray(void),
      nvk_t_createSamplerState(void), nvk_t_copyToMemoryBitmap(void), nvk_t_createVideoBitmap3D(void);
  nvk_redirect("_ZN17VideoBitmapOpenGL21generateTextureObjectEPKhb", (void *)v_generateTextureObject, NULL);
  nvk_redirect("_ZN17VideoBitmapOpenGL7setSizeEjj", (void *)v_bitmapSetSize, NULL);
  nvk_redirect("_ZN17VideoBitmapOpenGL18updateMipmapPixelsEjjjjjPKhi", (void *)v_bitmapUpdate, NULL);
  nvk_redirect("_ZN17VideoBitmapOpenGL15generateMipmapsEv", (void *)v_bitmapGenerateMipmaps, NULL);
  nvk_redirect("_ZNK17VideoBitmapOpenGL18copyToMemoryBitmapEv", (void *)nvk_t_copyToMemoryBitmap, NULL);
  nvk_redirect("_ZNK17VideoBitmapOpenGL11copyDataRawERSt6vectorIhSaIhEEm", (void *)v_copyDataRaw, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL22createVideoBitmapArrayEjjjRK13BitmapOptions",
               (void *)nvk_t_createVideoBitmapArray, NULL);
  nvk_redirect("_ZN22VideoBitmapArrayOpenGL18updateMipmapPixelsEjjPKh", (void *)v_arrayUpdate, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL19createVideoBitmap3DEPKhjjjjN13VideoBitmap3D15DataOrientationERK13BitmapOptions",
               (void *)nvk_t_createVideoBitmap3D, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL18createSamplerStateERK16SamplerStateSpec",
               (void *)nvk_t_createSamplerState, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL17createVideoBufferEN11VideoBuffer4TypeE13BufferOptions",
               (void *)nvk_t_createVideoBuffer, NULL);
  nvk_redirect("_ZN17VideoBufferOpenGL9mapBufferEmmb", (void *)v_mapBuffer, NULL);
  nvk_redirect("_ZN17VideoBufferOpenGL11unmapBufferEv", (void *)v_unmapBuffer, NULL);
  nvk_redirect("_ZN17VideoBufferOpenGL12updateBufferEmPKv", (void *)v_updateBuffer, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL19createUniformBufferEj", (void *)nvk_t_createUniformBuffer, NULL);
  nvk_redirect("_ZN19UniformBufferOpenGL12updateBufferEPKvj", (void *)v_uniformUpdate, NULL);
  nvk_redirect("_ZN17FramebufferOpenGL25generateFramebufferObjectEv", (void *)v_generateFramebufferObject, NULL);
}
