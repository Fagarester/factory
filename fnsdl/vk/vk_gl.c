// The GL functions Factorio's GraphicsInterfaceOpenGL calls (92 of them, list in VULKAN.md), implemented on
// Vulkan. GL object names are handles into tables here; nothing reaches a GL driver.
//
// Passes: a framebuffer bind does nothing by itself. The pass starts at the first draw (or a clear that cannot
// be a load op); a full clear before any draw becomes loadOp CLEAR. Images written in a pass (or by a copy)
// get one barrier to SHADER_READ when the next pass starts.
// Pipelines: built from the GL state at draw time, cached by a key of that state.
// Buffers: every glBufferData (and a map with INVALIDATE_BUFFER) takes new memory; the old memory is
// given back when the GPU is done with the frame (orphaning, as the game expects).
#include "fnvk.h"
#include "vk_internal.h"
#include "vk_shader.h"
#include "../fngl_tex.h"

#include <GLES3/gl32.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define GL_BGRA_ 0x80E1
#define GL_TEXTURE_BUFFER_ 0x8C2A
#define GL_COMPRESSED_RGB_S3TC_DXT1_ 0x83F0
#define GL_COMPRESSED_RGBA_S3TC_DXT1_ 0x83F1
#define GL_COMPRESSED_RGBA_S3TC_DXT3_ 0x83F2
#define GL_COMPRESSED_RGBA_S3TC_DXT5_ 0x83F3
#define GL_COMPRESSED_RED_RGTC1_ 0x8DBB
#define GL_COMPRESSED_RG_RGTC2_ 0x8DBD
#define GL_COMPRESSED_RGBA_BPTC_ 0x8E8C
#define GL_TEXTURE_COMPRESSED_IMAGE_SIZE_ 0x86A0
#define GL_CLAMP_TO_BORDER_ 0x812D
#define GL_TEXTURE_LOD_BIAS_ 0x8501
#define GL_TEXTURE_BORDER_COLOR_ 0x1004
#define GL_TIMESTAMP_ 0x8E28
#define GL_QUERY_RESULT_ 0x8866
#define GL_QUERY_RESULT_AVAILABLE_ 0x8867
#define GL_CONTEXT_PROFILE_MASK_ 0x9126
#define GL_MAX_TEXTURE_BUFFER_SIZE_ 0x8C2B
#define GL_TEXTURE_MAX_ANISOTROPY_ 0x84FE

static int log_missing = 1;

// ---------------------------------------------------------------- name tables
typedef struct {
  void **p;
  uint32_t n, cap;
} table;

static uint32_t tnew(table *t, void *o) {
  if (!t->n) t->n = 1;  // name 0 is "none"
  if (t->n >= t->cap) {
    t->cap = t->cap ? t->cap * 2 : 256;
    t->p = realloc(t->p, t->cap * sizeof *t->p);
  }
  t->p[t->n] = o;
  return t->n++;
}
static void *tget(table *t, uint32_t name) { return name && name < t->n ? t->p[name] : NULL; }
static void tdel(table *t, uint32_t name) {
  if (name && name < t->n) t->p[name] = NULL;
}

// ---------------------------------------------------------------- objects
typedef struct {
  vk_slice mem;
  VkDeviceSize size;
  uint64_t serial;      // grows on every new memory (orphan)
  uint64_t used_frame;  // last frame a draw referenced it
  VkBufferView view;
  uint64_t view_serial;
  VkFormat view_format;
} gbuf;

typedef struct {
  GLenum target;
  vk_image *img;
  VkFormat format;
  int compressed;
  uint32_t w, h, d;
  GLint max_level;
  GLint min_filter, mag_filter, wrap_s, wrap_t, wrap_r;
  VkSampler own_sampler;
  int own_dirty;
  GLuint buffer;  // GL_TEXTURE_BUFFER
  VkFormat buffer_format;
  GLenum bc;  // BC format the game uses when the image holds ETC2/EAC instead (no BC in Vulkan)
} gtex;

typedef struct {
  GLint min_filter, mag_filter, wrap_s, wrap_t, wrap_r;
  float min_lod, max_lod, bias, border[4], aniso;
  VkSampler vks;
  int dirty;
} gsampler;

typedef struct {
  GLuint color[2], depth;  // texture names
  int draw[2];             // glDrawBuffers
} gfbo;

typedef struct {
  GLenum type;
  char *src;
} gshader;

typedef struct {
  GLuint shaders[4];
  int nshaders;
  int linked;
  char log[2048];
  fnvk_spirv spv;
  VkShaderModule vs, fs;
  VkDescriptorSetLayout dsl;
  VkPipelineLayout layout;
  int unit[FNVK_MAX_RES], point[FNVK_MAX_RES];
  uint32_t serial;
} gprog;

typedef struct {
  int enabled, integer, normalized;
  GLint size;
  GLenum type;
  GLsizei stride;
  uintptr_t offset;
  GLuint buffer, divisor;
} gattr;

typedef struct {
  gattr a[16];
  GLuint element;
} gvao;

static table buffers, textures, samplers, fbos, shaders, programs, vaos;
static gvao default_vao;

// ---------------------------------------------------------------- state
static struct {
  GLuint array_buffer, generic_uniform, generic_texbuf;
  struct {
    GLuint buf;
    GLintptr off;
    GLsizeiptr size;
  } ubo[64];
  GLuint active;
  struct {
    GLuint t2d, t2da, t3d, tbuf, sampler;
  } unit[32];
  GLuint program, vao, draw_fbo, read_fbo;
  int blend, scissor_test, depth_test, stencil_test;
  GLenum beq_rgb, beq_a, bsrc_rgb, bdst_rgb, bsrc_a, bdst_a;
  uint8_t cmask[2];
  GLenum sfunc, sfail, sdpfail, sdppass, depth_func;
  GLint sref;
  GLuint smask, swmask;
  int depth_mask;
  GLint vp[4], sc[4];
  float clear_color[4];
  GLint unpack_align, unpack_row, pack_align;
} st;

static void state_init(void) {
  st.beq_rgb = st.beq_a = GL_FUNC_ADD;
  st.bsrc_rgb = st.bsrc_a = GL_ONE;
  st.bdst_rgb = st.bdst_a = GL_ZERO;
  st.cmask[0] = st.cmask[1] = 0xf;
  st.sfunc = GL_ALWAYS;
  st.sfail = st.sdpfail = st.sdppass = GL_KEEP;
  st.smask = st.swmask = 0xffffffffu;
  st.depth_func = GL_LESS;
  st.depth_mask = 1;
  st.unpack_align = st.pack_align = 4;
}

static gvao *cur_vao(void) {
  gvao *v = tget(&vaos, st.vao);
  return v ? v : &default_vao;
}

static GLuint *unit_slot(GLenum target, GLuint unit) {
  switch (target) {
    case GL_TEXTURE_2D: return &st.unit[unit].t2d;
    case GL_TEXTURE_2D_ARRAY: return &st.unit[unit].t2da;
    case GL_TEXTURE_3D: return &st.unit[unit].t3d;
    case GL_TEXTURE_BUFFER_: return &st.unit[unit].tbuf;
  }
  fnvk_log("texture target 0x%x not handled", target);
  return &st.unit[unit].t2d;
}

static gtex *bound_tex(GLenum target) { return tget(&textures, *unit_slot(target, st.active)); }

static gbuf *buffer_for(GLenum target) {
  switch (target) {
    case GL_ARRAY_BUFFER: return tget(&buffers, st.array_buffer);
    case GL_ELEMENT_ARRAY_BUFFER: return tget(&buffers, cur_vao()->element);
    case GL_UNIFORM_BUFFER: return tget(&buffers, st.generic_uniform);
    case GL_TEXTURE_BUFFER_: return tget(&buffers, st.generic_texbuf);
  }
  fnvk_log("buffer target 0x%x not handled", target);
  return NULL;
}

// ---------------------------------------------------------------- formats
static VkFormat tex_format(GLenum ifmt, int *compressed) {
  *compressed = 0;
  switch (ifmt) {
    case GL_RGBA: case GL_RGBA8: return VK_FORMAT_R8G8B8A8_UNORM;
    case GL_BGRA_: return VK_FORMAT_B8G8R8A8_UNORM;
    case GL_RGB: case GL_RGB565: return VK_FORMAT_R5G6B5_UNORM_PACK16;
    case GL_R8: return VK_FORMAT_R8_UNORM;
    case GL_RG8: return VK_FORMAT_R8G8_UNORM;
    case GL_RGBA16UI: return VK_FORMAT_R16G16B16A16_UINT;
    case GL_RGBA32UI: return VK_FORMAT_R32G32B32A32_UINT;
    case GL_RG32UI: return VK_FORMAT_R32G32_UINT;
    case GL_R16UI: return VK_FORMAT_R16_UINT;
    case GL_R32UI: return VK_FORMAT_R32_UINT;
    case GL_R32F: return VK_FORMAT_R32_SFLOAT;
    case GL_RGBA32F: return VK_FORMAT_R32G32B32A32_SFLOAT;
    case GL_DEPTH_COMPONENT16: return VK_FORMAT_D16_UNORM;
    case GL_DEPTH24_STENCIL8: return vk.depth_stencil_format;
  }
  *compressed = 1;
  if (!vk.has_bc) switch (fngl_tex_etc2_format(ifmt)) {
      case 0x9274: return VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK;
      case 0x9278: return VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK;
      case 0x9270: return VK_FORMAT_EAC_R11_UNORM_BLOCK;
      case 0x9272: return VK_FORMAT_EAC_R11G11_UNORM_BLOCK;
    }
  switch (ifmt) {
    case GL_COMPRESSED_RGB_S3TC_DXT1_: return VK_FORMAT_BC1_RGB_UNORM_BLOCK;
    case GL_COMPRESSED_RGBA_S3TC_DXT1_: return VK_FORMAT_BC1_RGBA_UNORM_BLOCK;
    case GL_COMPRESSED_RGBA_S3TC_DXT3_: return VK_FORMAT_BC2_UNORM_BLOCK;
    case GL_COMPRESSED_RGBA_S3TC_DXT5_: return VK_FORMAT_BC3_UNORM_BLOCK;
    case GL_COMPRESSED_RED_RGTC1_: return VK_FORMAT_BC4_UNORM_BLOCK;
    case GL_COMPRESSED_RG_RGTC2_: return VK_FORMAT_BC5_UNORM_BLOCK;
    case GL_COMPRESSED_RGBA_BPTC_: return VK_FORMAT_BC7_UNORM_BLOCK;
  }
  *compressed = 0;
  fnvk_log("texture format 0x%x not handled", ifmt);
  return VK_FORMAT_R8G8B8A8_UNORM;
}

static VkFormat texel_buffer_format(GLenum ifmt) {
  switch (ifmt) {
    case GL_R8UI: return VK_FORMAT_R8_UINT;
    case GL_R16UI: return VK_FORMAT_R16_UINT;
    case GL_R32UI: return VK_FORMAT_R32_UINT;
    case GL_RGBA8UI: return VK_FORMAT_R8G8B8A8_UINT;
    case GL_R32F: return VK_FORMAT_R32_SFLOAT;
    case GL_RGBA32F: return VK_FORMAT_R32G32B32A32_SFLOAT;
    case GL_RGBA32UI: return VK_FORMAT_R32G32B32A32_UINT;
  }
  fnvk_log("texel buffer format 0x%x not handled", ifmt);
  return VK_FORMAT_R32_UINT;
}

// ---------------------------------------------------------------- default framebuffer (the "window")
static struct {
  vk_image *color, *depth;
  uint32_t w, h;
} win;
static void (*window_size_fn)(int *, int *);

static void window_ensure(void) {
  int w = 0, h = 0;
  if (window_size_fn) window_size_fn(&w, &h);
  if (w <= 0 || h <= 0) w = 1280, h = 720;
  if (win.color && win.w == (uint32_t)w && win.h == (uint32_t)h) return;
  vk_image_destroy(win.color);
  vk_image_destroy(win.depth);
  win.color = vk_image_create(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_VIEW_TYPE_2D, w, h, 1, 1, 1);
  win.depth = vk_image_create(vk.depth_stencil_format, VK_IMAGE_VIEW_TYPE_2D, w, h, 1, 1, 1);
  win.w = w;
  win.h = h;
  fnvk_log("window framebuffer %dx%d", w, h);
}

// ---------------------------------------------------------------- passes
typedef struct {
  vk_image *color[2], *depth;
  int ncolor;
  uint32_t w, h;
  int flip;  // the window drawn straight into a swapchain image: rows top-down, so y is mirrored
} fb_targets;

static vk_image *sc_target(void);  // the acquired swapchain image of this frame, or NULL

static void fb_resolve(GLuint name, fb_targets *t) {
  memset(t, 0, sizeof *t);
  if (!name) {
    window_ensure();
    vk_image *sci = sc_target();
    t->color[0] = sci ? sci : win.color;
    t->flip = sci != NULL;
    t->depth = win.depth;
    t->ncolor = 1;
    t->w = win.w;
    t->h = win.h;
    return;
  }
  gfbo *f = tget(&fbos, name);
  if (!f) return;
  for (int i = 0; i < 2; i++) {
    gtex *tx = tget(&textures, f->color[i]);
    if (tx && tx->img && f->draw[i]) t->color[t->ncolor++] = tx->img;
  }
  gtex *d = tget(&textures, f->depth);
  t->depth = d ? d->img : NULL;
  vk_image *any = t->ncolor ? t->color[0] : t->depth;
  if (any) t->w = any->w, t->h = any->h;
}

static struct {
  int open;
  GLuint fbo;
  fb_targets t;
} pass;

static uint64_t draws;
static struct {
  uint64_t passes, depth, depth_load, color, color_load, depth_dontcare;
} pstat;
static int depth_dontcare = -1;  // FNVK_DEPTH_DONTCARE=0: always store depth/stencil

static void begin_pass(GLuint fbo) {
  fb_targets t;
  fb_resolve(fbo, &t);
  vk_image *except[3] = {t.color[0], t.color[1], t.depth};
  vk_flush_dirty(except, 3);
  VkRenderingAttachmentInfoKHR ca[2], da, sa;
  memset(ca, 0, sizeof ca);
  memset(&da, 0, sizeof da);
  memset(&sa, 0, sizeof sa);
  for (int i = 0; i < t.ncolor; i++) {
    vk_image *c = t.color[i];
    vk_image_layout(c, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);  // also orders earlier writes
    ca[i].sType = VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
    ca[i].imageView = c->attach_view;
    ca[i].imageLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    ca[i].loadOp = c->pending ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
    ca[i].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    ca[i].clearValue = c->clear;
    c->pending = 0;
    vk_mark_dirty(c);
  }
  VkRenderingInfoKHR ri = {VK_STRUCTURE_TYPE_RENDERING_INFO_KHR};
  ri.renderArea.extent = (VkExtent2D){t.w, t.h};
  ri.layerCount = 1;
  ri.colorAttachmentCount = t.ncolor;
  ri.pColorAttachments = ca;
  if (t.depth) {
    vk_image *d = t.depth;
    if (depth_dontcare < 0) depth_dontcare = !getenv("FNVK_DEPTH_DONTCARE") || atoi(getenv("FNVK_DEPTH_DONTCARE"));
    int fresh = d->layout == VK_IMAGE_LAYOUT_UNDEFINED;  // nothing written yet: a load reads nothing
    vk_image_layout(d, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    da.sType = VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
    da.imageView = d->attach_view;
    da.imageLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    da.loadOp = d->pending & VK_IMAGE_ASPECT_DEPTH_BIT ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
    da.clearValue = d->clear;
    sa = da;
    sa.loadOp = d->pending & VK_IMAGE_ASPECT_STENCIL_BIT ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
    // Depth/stencil that no pass ever loaded is not written back: the game clears it before each use. The first
    // load marks the image (one frame may see an undefined buffer then, every later one is right).
    if (!d->keep_depth && !fresh && (da.loadOp == VK_ATTACHMENT_LOAD_OP_LOAD ||
                           (sa.loadOp == VK_ATTACHMENT_LOAD_OP_LOAD && (d->aspect & VK_IMAGE_ASPECT_STENCIL_BIT)))) {
      d->keep_depth = 1;
      fnvk_log("depth of framebuffer %u is loaded (frame %llu, depth %s, stencil %s): stored from now on", fbo,
               (unsigned long long)vk_frame_serial, da.loadOp == VK_ATTACHMENT_LOAD_OP_LOAD ? "load" : "clear",
               sa.loadOp == VK_ATTACHMENT_LOAD_OP_LOAD ? "load" : "clear");
    }
    da.storeOp = sa.storeOp =
        d->keep_depth || !depth_dontcare ? VK_ATTACHMENT_STORE_OP_STORE : VK_ATTACHMENT_STORE_OP_DONT_CARE;
    pstat.depth_dontcare += da.storeOp == VK_ATTACHMENT_STORE_OP_DONT_CARE;
    ri.pDepthAttachment = &da;
    if (d->aspect & VK_IMAGE_ASPECT_STENCIL_BIT) ri.pStencilAttachment = &sa;
    d->pending = 0;
    vk_mark_dirty(d);
  }
  static long dump_frame = -2;
  if (dump_frame == -2) dump_frame = getenv("FNVK_PASS_DUMP") ? atol(getenv("FNVK_PASS_DUMP")) : -1;
  if ((long)vk_frame_serial == dump_frame)
    fnvk_log("pass fbo %u %ux%u: color %d [%s %s] fmt %d, depth %s/%s fmt %d, draws before %llu", fbo, t.w, t.h,
             t.ncolor, t.ncolor > 0 ? (ca[0].loadOp == VK_ATTACHMENT_LOAD_OP_LOAD ? "load" : "clear") : "-",
             t.ncolor > 1 ? (ca[1].loadOp == VK_ATTACHMENT_LOAD_OP_LOAD ? "load" : "clear") : "-",
             t.ncolor ? t.color[0]->format : 0,
             t.depth ? (da.loadOp == VK_ATTACHMENT_LOAD_OP_LOAD ? "load" : "clear") : "-",
             t.depth ? (da.storeOp == VK_ATTACHMENT_STORE_OP_STORE ? "store" : "dontcare") : "-",
             t.depth ? t.depth->format : 0, (unsigned long long)draws);
  pstat.passes++;
  if (t.depth) pstat.depth++, pstat.depth_load += da.loadOp == VK_ATTACHMENT_LOAD_OP_LOAD;
  for (int i = 0; i < t.ncolor; i++) pstat.color++, pstat.color_load += ca[i].loadOp == VK_ATTACHMENT_LOAD_OP_LOAD;
  vk.begin_rendering(vk_cmd(), &ri);
  pass.open = 1;
  pass.fbo = fbo;
  pass.t = t;
}

static void end_pass(void) {
  if (pass.open) {
    vk.end_rendering(vk_cmd());
    pass.open = 0;
  }
}

// Clears stay deferred on their images (vk_image.pending); ending a pass is all there is to do.
static void flush_pass(void) { end_pass(); }

static void ensure_pass(void) {
  if (pass.open && pass.fbo == st.draw_fbo) return;
  end_pass();
  begin_pass(st.draw_fbo);
}

static int in_pass(const vk_image *img) {
  return pass.open && img && (pass.t.color[0] == img || pass.t.color[1] == img || pass.t.depth == img);
}

// ---------------------------------------------------------------- shaders and programs
static uint32_t prog_serial;

static GLuint GL_APIENTRY v_glCreateShader(GLenum type) {
  gshader *s = calloc(1, sizeof *s);
  s->type = type;
  return tnew(&shaders, s);
}

static void GL_APIENTRY v_glShaderSource(GLuint sh, GLsizei count, const GLchar *const *str, const GLint *len) {
  gshader *s = tget(&shaders, sh);
  if (!s) return;
  size_t total = 0;
  for (GLsizei i = 0; i < count; i++) total += len && len[i] >= 0 ? (size_t)len[i] : strlen(str[i]);
  free(s->src);
  s->src = malloc(total + 1);
  size_t o = 0;
  for (GLsizei i = 0; i < count; i++) {
    size_t l = len && len[i] >= 0 ? (size_t)len[i] : strlen(str[i]);
    memcpy(s->src + o, str[i], l);
    o += l;
  }
  s->src[o] = 0;
}

static void GL_APIENTRY v_glCompileShader(GLuint sh) { (void)sh; }  // compiled with the program at link

static void GL_APIENTRY v_glGetShaderiv(GLuint sh, GLenum pname, GLint *v) {
  gshader *s = tget(&shaders, sh);
  switch (pname) {
    case GL_COMPILE_STATUS: *v = s != NULL; return;
    case GL_INFO_LOG_LENGTH: *v = 0; return;
    case GL_SHADER_TYPE: *v = s ? (GLint)s->type : 0; return;
  }
  *v = 0;
}

static void GL_APIENTRY v_glGetShaderInfoLog(GLuint sh, GLsizei max, GLsizei *len, GLchar *log) {
  (void)sh;
  if (len) *len = 0;
  if (max > 0) log[0] = 0;
}

static void GL_APIENTRY v_glDeleteShader(GLuint sh) {
  gshader *s = tget(&shaders, sh);
  if (!s) return;
  free(s->src);
  free(s);
  tdel(&shaders, sh);
}

static GLuint GL_APIENTRY v_glCreateProgram(void) { return tnew(&programs, calloc(1, sizeof(gprog))); }

static void GL_APIENTRY v_glAttachShader(GLuint p, GLuint sh) {
  gprog *g = tget(&programs, p);
  if (g && g->nshaders < 4) g->shaders[g->nshaders++] = sh;
}

static void GL_APIENTRY v_glDetachShader(GLuint p, GLuint sh) {
  gprog *g = tget(&programs, p);
  if (!g) return;
  for (int i = 0; i < g->nshaders; i++)
    if (g->shaders[i] == sh) g->shaders[i--] = g->shaders[--g->nshaders];
}

static VkShaderModule module(const uint32_t *code, size_t words) {
  VkShaderModuleCreateInfo ci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, words * 4, code};
  VkShaderModule m = VK_NULL_HANDLE;
  VKCHECK(vkCreateShaderModule(vk.dev, &ci, NULL, &m));
  return m;
}

static void GL_APIENTRY v_glLinkProgram(GLuint p) {
  gprog *g = tget(&programs, p);
  if (!g) return;
  const char *vs = NULL, *fs = NULL;
  for (int i = 0; i < g->nshaders; i++) {
    gshader *s = tget(&shaders, g->shaders[i]);
    if (s && s->type == GL_VERTEX_SHADER) vs = s->src;
    if (s && s->type == GL_FRAGMENT_SHADER) fs = s->src;
  }
  g->linked = vs && fs && fnvk_compile_program(vs, fs, &g->spv);
  if (!g->linked) {
    snprintf(g->log, sizeof g->log, "%s", vs && fs ? g->spv.log : "missing shader stage");
    fnvk_log("program %u does not link:\n%s", p, g->log);
    return;
  }
  g->vs = module(g->spv.code[0], g->spv.words[0]);
  g->fs = module(g->spv.code[1], g->spv.words[1]);
  fnvk_free_spirv(&g->spv);
  VkDescriptorSetLayoutBinding b[FNVK_MAX_RES];
  for (int i = 0; i < g->spv.nres; i++) {
    fnvk_resource *r = &g->spv.res[i];
    b[i] = (VkDescriptorSetLayoutBinding){(uint32_t)i,
                                          r->kind == FNVK_RES_UBO       ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                                          : r->kind == FNVK_RES_SAMPLER ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                                                        : VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER,
                                          1,
                                          (r->stages & 1 ? VK_SHADER_STAGE_VERTEX_BIT : 0) |
                                              (r->stages & 2 ? VK_SHADER_STAGE_FRAGMENT_BIT : 0),
                                          NULL};
    g->unit[i] = 0;
    g->point[i] = 0;
  }
  VkDescriptorSetLayoutCreateInfo dci = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL,
                                         vk.push_descriptors ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0,
                                         (uint32_t)g->spv.nres, b};
  VKCHECK(vkCreateDescriptorSetLayout(vk.dev, &dci, NULL, &g->dsl));
  VkPipelineLayoutCreateInfo pci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 1, &g->dsl};
  VKCHECK(vkCreatePipelineLayout(vk.dev, &pci, NULL, &g->layout));
  g->serial = ++prog_serial;
}

static void GL_APIENTRY v_glGetProgramiv(GLuint p, GLenum pname, GLint *v) {
  gprog *g = tget(&programs, p);
  switch (pname) {
    case GL_LINK_STATUS: *v = g && g->linked; return;
    case GL_INFO_LOG_LENGTH: *v = g ? (GLint)strlen(g->log) + 1 : 0; return;
  }
  *v = 0;
}

static void GL_APIENTRY v_glGetProgramInfoLog(GLuint p, GLsizei max, GLsizei *len, GLchar *log) {
  gprog *g = tget(&programs, p);
  const char *s = g ? g->log : "";
  GLsizei n = (GLsizei)strlen(s);
  if (max <= 0) return;
  if (n >= max) n = max - 1;
  memcpy(log, s, n);
  log[n] = 0;
  if (len) *len = n;
}

static void prog_free(void *p) {
  gprog *g = p;
  if (g->layout) vkDestroyPipelineLayout(vk.dev, g->layout, NULL);
  if (g->dsl) vkDestroyDescriptorSetLayout(vk.dev, g->dsl, NULL);
  if (g->vs) vkDestroyShaderModule(vk.dev, g->vs, NULL);
  if (g->fs) vkDestroyShaderModule(vk.dev, g->fs, NULL);
  free(g);
}

static void pipelines_forget(uint32_t serial);

static void GL_APIENTRY v_glDeleteProgram(GLuint p) {
  gprog *g = tget(&programs, p);
  if (!g) return;
  tdel(&programs, p);
  pipelines_forget(g->serial);
  vk_defer(prog_free, g);
}

static void GL_APIENTRY v_glUseProgram(GLuint p) { st.program = p; }

static int find_res(gprog *g, const char *name, int kind) {
  for (int i = 0; g && i < g->spv.nres; i++)
    if (!strcmp(g->spv.res[i].name, name) && (g->spv.res[i].kind == FNVK_RES_UBO) == (kind == FNVK_RES_UBO))
      return i;
  return -1;
}

static GLint GL_APIENTRY v_glGetUniformLocation(GLuint p, const GLchar *name) {
  return find_res(tget(&programs, p), name, FNVK_RES_SAMPLER);
}

static GLuint GL_APIENTRY v_glGetUniformBlockIndex(GLuint p, const GLchar *name) {
  int i = find_res(tget(&programs, p), name, FNVK_RES_UBO);
  return i < 0 ? GL_INVALID_INDEX : (GLuint)i;
}

static void GL_APIENTRY v_glUniformBlockBinding(GLuint p, GLuint idx, GLuint point) {
  gprog *g = tget(&programs, p);
  if (g && idx < FNVK_MAX_RES) g->point[idx] = point;
}

static void GL_APIENTRY v_glUniform1i(GLint loc, GLint v) {
  gprog *g = tget(&programs, st.program);
  if (g && loc >= 0 && loc < FNVK_MAX_RES) g->unit[loc] = v;
}

// ---------------------------------------------------------------- buffers
static void GL_APIENTRY v_glGenBuffers(GLsizei n, GLuint *out) {
  for (GLsizei i = 0; i < n; i++) {
    gbuf *b = calloc(1, sizeof *b);
    b->mem.cls = 0xffff;
    out[i] = tnew(&buffers, b);
  }
}

static void view_free(void *v) { vkDestroyBufferView(vk.dev, (VkBufferView)(uintptr_t)v, NULL); }

static void GL_APIENTRY v_glDeleteBuffers(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) {
    gbuf *b = tget(&buffers, names[i]);
    if (!b) continue;
    vk_retire(b->mem);
    if (b->view) vk_defer(view_free, (void *)(uintptr_t)b->view);
    free(b);
    tdel(&buffers, names[i]);
  }
}

static void GL_APIENTRY v_glBindBuffer(GLenum target, GLuint name) {
  switch (target) {
    case GL_ARRAY_BUFFER: st.array_buffer = name; return;
    case GL_ELEMENT_ARRAY_BUFFER: cur_vao()->element = name; return;
    case GL_UNIFORM_BUFFER: st.generic_uniform = name; return;
    case GL_TEXTURE_BUFFER_: st.generic_texbuf = name; return;
  }
  fnvk_log("glBindBuffer target 0x%x not handled", target);
}

static void orphan(gbuf *b, VkDeviceSize size) {
  vk_retire(b->mem);
  b->mem = vk_alloc(size ? size : 1);
  b->size = size;
  b->serial++;
  b->used_frame = 0;
}

static void GL_APIENTRY v_glBufferData(GLenum target, GLsizeiptr size, const void *data, GLenum usage) {
  (void)usage;
  gbuf *b = buffer_for(target);
  if (!b) return;
  orphan(b, size);
  if (data && b->mem.ptr) memcpy(b->mem.ptr, data, size);
}

static void *GL_APIENTRY v_glMapBufferRange(GLenum target, GLintptr off, GLsizeiptr len, GLbitfield access) {
  (void)len;
  gbuf *b = buffer_for(target);
  if (!b) return NULL;
  // INVALIDATE_BUFFER, or a write from the start of memory a submitted frame may still read: new memory.
  if (access & GL_MAP_INVALIDATE_BUFFER_BIT) orphan(b, b->size);
  else if (off == 0 && b->used_frame && b->used_frame < vk_frame_serial) {
    static int told;
    if (!told++) fnvk_log("buffer rewritten from 0 while a frame may still read it: new memory");
    orphan(b, b->size);
  }
  return b->mem.ptr ? b->mem.ptr + off : NULL;
}

static void *GL_APIENTRY v_glMapBuffer(GLenum target, GLenum access) {
  (void)access;
  gbuf *b = buffer_for(target);
  if (!b) return NULL;
  if (b->used_frame) vk_finish();  // a whole-buffer map must see finished GPU work
  return b->mem.ptr;
}

static GLboolean GL_APIENTRY v_glUnmapBuffer(GLenum target) {
  (void)target;
  return GL_TRUE;
}

static void GL_APIENTRY v_glBindBufferBase(GLenum target, GLuint index, GLuint name) {
  if (target != GL_UNIFORM_BUFFER || index >= 64) {
    fnvk_log("glBindBufferBase target 0x%x index %u not handled", target, index);
    return;
  }
  st.ubo[index].buf = name;
  st.ubo[index].off = 0;
  st.ubo[index].size = 0;
  st.generic_uniform = name;
}

// ---------------------------------------------------------------- textures
static void GL_APIENTRY v_glGenTextures(GLsizei n, GLuint *out) {
  for (GLsizei i = 0; i < n; i++) {
    gtex *t = calloc(1, sizeof *t);
    t->max_level = 1000;
    t->min_filter = GL_NEAREST_MIPMAP_LINEAR;
    t->mag_filter = GL_LINEAR;
    t->wrap_s = t->wrap_t = t->wrap_r = GL_REPEAT;
    t->own_dirty = 1;
    out[i] = tnew(&textures, t);
  }
}

static void sampler_free(void *s) { vkDestroySampler(vk.dev, (VkSampler)(uintptr_t)s, NULL); }

static void GL_APIENTRY v_glDeleteTextures(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) {
    gtex *t = tget(&textures, names[i]);
    if (!t) continue;
    if (in_pass(t->img)) end_pass();
    vk_image_destroy(t->img);
    if (t->own_sampler) vk_defer(sampler_free, (void *)(uintptr_t)t->own_sampler);
    free(t);
    tdel(&textures, names[i]);
    for (int u = 0; u < 32; u++) {
      if (st.unit[u].t2d == names[i]) st.unit[u].t2d = 0;
      if (st.unit[u].t2da == names[i]) st.unit[u].t2da = 0;
      if (st.unit[u].t3d == names[i]) st.unit[u].t3d = 0;
      if (st.unit[u].tbuf == names[i]) st.unit[u].tbuf = 0;
    }
  }
}

static void GL_APIENTRY v_glActiveTexture(GLenum unit) { st.active = (unit - GL_TEXTURE0) & 31; }

static void GL_APIENTRY v_glBindTexture(GLenum target, GLuint name) {
  *unit_slot(target, st.active) = name;
  gtex *t = tget(&textures, name);
  if (t && !t->target) t->target = target;
}

static void GL_APIENTRY v_glTexParameteri(GLenum target, GLenum pname, GLint v) {
  gtex *t = bound_tex(target);
  if (!t) return;
  switch (pname) {
    case GL_TEXTURE_MAX_LEVEL: t->max_level = v; return;
    case GL_TEXTURE_BASE_LEVEL: return;
    case GL_TEXTURE_MIN_FILTER: t->min_filter = v; break;
    case GL_TEXTURE_MAG_FILTER: t->mag_filter = v; break;
    case GL_TEXTURE_WRAP_S: t->wrap_s = v; break;
    case GL_TEXTURE_WRAP_T: t->wrap_t = v; break;
    case GL_TEXTURE_WRAP_R: t->wrap_r = v; break;
    default: fnvk_log("glTexParameteri 0x%x not handled", pname); return;
  }
  t->own_dirty = 1;
}

static uint32_t mip_count(gtex *t, uint32_t w, uint32_t h, uint32_t d) {
  uint32_t full = 1, m = w > h ? w : h;
  if (t->target == GL_TEXTURE_3D && d > m) m = d;
  while (m > 1) m >>= 1, full++;
  if (t->max_level >= 1000) return 1;  // never set: the game uploads one level
  uint32_t want = (uint32_t)t->max_level + 1;
  return want < full ? want : full;
}

// (Re)creates the image when level 0 is defined with a new size or format.
static void tex_define(gtex *t, GLenum target, GLint level, GLenum ifmt, uint32_t w, uint32_t h, uint32_t d) {
  if (!t->target) t->target = target;
  if (level != 0) return;
  int compressed;
  VkFormat f = tex_format(ifmt, &compressed);
  if (t->img && t->format == f && t->w == w && t->h == h && t->d == d) return;
  if (pass.open && (pass.t.color[0] == t->img || pass.t.color[1] == t->img || pass.t.depth == t->img)) end_pass();
  vk_image_destroy(t->img);
  VkImageViewType vt = target == GL_TEXTURE_3D        ? VK_IMAGE_VIEW_TYPE_3D
                       : target == GL_TEXTURE_2D_ARRAY ? VK_IMAGE_VIEW_TYPE_2D_ARRAY
                                                       : VK_IMAGE_VIEW_TYPE_2D;
  t->img = vk_image_create(f, vt, w, h, target == GL_TEXTURE_3D ? d : 1, target == GL_TEXTURE_2D_ARRAY ? d : 1,
                           mip_count(t, w, h, d));
  t->format = f;
  t->compressed = compressed;
  t->bc = compressed && !vk.has_bc && fngl_tex_etc2_format(ifmt) ? ifmt : 0;
  t->w = w;
  t->h = h;
  t->d = d;
}

static size_t texel_bytes(GLenum format, GLenum type) {
  size_t c = format == GL_RED || format == GL_RED_INTEGER || format == GL_DEPTH_COMPONENT ? 1
             : format == GL_RG || format == GL_RG_INTEGER                                 ? 2
             : format == GL_RGB || format == GL_RGB_INTEGER                               ? 3
                                                                                           : 4;
  switch (type) {
    case GL_UNSIGNED_BYTE: case GL_BYTE: return c;
    case GL_UNSIGNED_SHORT: case GL_SHORT: case GL_HALF_FLOAT: return 2 * c;
    case GL_UNSIGNED_SHORT_5_6_5: case GL_UNSIGNED_SHORT_4_4_4_4: case GL_UNSIGNED_SHORT_5_5_5_1: return 2;
    case GL_UNSIGNED_INT_24_8: return 4;
    default: return 4 * c;
  }
}

static void upload_pixels(gtex *t, GLint level, GLint x, GLint y, GLint z, GLsizei w, GLsizei h, GLsizei d,
                          GLenum format, GLenum type, const void *data) {
  if (!data || !t->img || w <= 0 || h <= 0 || d <= 0) return;
  size_t bpp = texel_bytes(format, type);
  size_t row = st.unpack_row > 0 ? (size_t)st.unpack_row : (size_t)w;
  size_t pitch = (row * bpp + st.unpack_align - 1) / st.unpack_align * st.unpack_align;
  if (pitch % bpp) {
    fnvk_log("row pitch %zu not a whole number of %zu-byte texels", pitch, bpp);
    return;
  }
  size_t size = pitch * ((size_t)h * d - 1) + (size_t)w * bpp;
  vk_upload(t->img, level, x, y, z, w, h, d, data, size, (uint32_t)(pitch / bpp), (uint32_t)h);
}

static void GL_APIENTRY v_glTexImage2D(GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLint border,
                                       GLenum format, GLenum type, const void *data) {
  (void)border;
  gtex *t = bound_tex(target);
  if (!t) return;
  tex_define(t, target, level, (GLenum)ifmt, w, h, 1);
  if (t->img && (uint32_t)level < t->img->levels) upload_pixels(t, level, 0, 0, 0, w, h, 1, format, type, data);
}

static void GL_APIENTRY v_glTexImage3D(GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLsizei d,
                                       GLint border, GLenum format, GLenum type, const void *data) {
  (void)border;
  gtex *t = bound_tex(target);
  if (!t) return;
  tex_define(t, target, level, (GLenum)ifmt, w, h, d);
  if (t->img && (uint32_t)level < t->img->levels) upload_pixels(t, level, 0, 0, 0, w, h, d, format, type, data);
}

static void GL_APIENTRY v_glTexSubImage2D(GLenum target, GLint level, GLint x, GLint y, GLsizei w, GLsizei h,
                                          GLenum format, GLenum type, const void *data) {
  gtex *t = bound_tex(target);
  if (t) upload_pixels(t, level, x, y, 0, w, h, 1, format, type, data);
}

static void GL_APIENTRY v_glTexSubImage3D(GLenum target, GLint level, GLint x, GLint y, GLint z, GLsizei w,
                                          GLsizei h, GLsizei d, GLenum format, GLenum type, const void *data) {
  gtex *t = bound_tex(target);
  if (t) upload_pixels(t, level, x, y, z, w, h, d, format, type, data);
}

static void upload_compressed(gtex *t, GLint level, GLint x, GLint y, GLint z, GLsizei w, GLsizei h, GLsizei d,
                              GLsizei size, const void *data) {
  if (!data || !t->img || (uint32_t)level >= t->img->levels) return;
  uint32_t row = ((uint32_t)w + 3) & ~3u, rows = ((uint32_t)h + 3) & ~3u;
  if (t->bc) {  // BC blocks -> ETC2/EAC (the texture's BC format decides, as the GLES path does)
    size_t n = 0;
    void *etc = fngl_tex_to_etc2(t->bc, data, (int)(row / 4), (int)(rows / 4), d, &n);
    if (etc) vk_upload(t->img, level, x, y, z, w, h, d, etc, n, row, rows);
    free(etc);
    return;
  }
  vk_upload(t->img, level, x, y, z, w, h, d, data, size, row, rows);
}

static void GL_APIENTRY v_glCompressedTexImage2D(GLenum target, GLint level, GLenum ifmt, GLsizei w, GLsizei h,
                                                 GLint border, GLsizei size, const void *data) {
  (void)border;
  gtex *t = bound_tex(target);
  if (!t) return;
  tex_define(t, target, level, ifmt, w, h, 1);
  upload_compressed(t, level, 0, 0, 0, w, h, 1, size, data);
}

static void GL_APIENTRY v_glCompressedTexSubImage2D(GLenum target, GLint level, GLint x, GLint y, GLsizei w,
                                                    GLsizei h, GLenum format, GLsizei size, const void *data) {
  (void)format;
  gtex *t = bound_tex(target);
  if (t) upload_compressed(t, level, x, y, 0, w, h, 1, size, data);
}

static void GL_APIENTRY v_glCompressedTexSubImage3D(GLenum target, GLint level, GLint x, GLint y, GLint z,
                                                    GLsizei w, GLsizei h, GLsizei d, GLenum format, GLsizei size,
                                                    const void *data) {
  (void)format;
  gtex *t = bound_tex(target);
  if (t) upload_compressed(t, level, x, y, z, w, h, d, size, data);
}

static void GL_APIENTRY v_glTexBuffer(GLenum target, GLenum ifmt, GLuint buffer) {
  gtex *t = bound_tex(target);
  if (!t) return;
  t->target = GL_TEXTURE_BUFFER_;
  t->buffer = buffer;
  t->buffer_format = texel_buffer_format(ifmt);
}

static void GL_APIENTRY v_glPixelStorei(GLenum pname, GLint v) {
  switch (pname) {
    case GL_UNPACK_ALIGNMENT: st.unpack_align = v > 0 ? v : 1; return;
    case GL_UNPACK_ROW_LENGTH: st.unpack_row = v; return;
    case GL_PACK_ALIGNMENT: st.pack_align = v > 0 ? v : 1; return;
  }
  fnvk_log("glPixelStorei 0x%x not handled", pname);
}

static void blit_levels(vk_image *img) {
  if (img->levels < 2) return;
  vk_materialize(img);
  VkCommandBuffer c = vk_cmd();
  vk_image_layout(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  for (uint32_t l = 1; l < img->levels; l++) {
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img->image;
    b.subresourceRange = (VkImageSubresourceRange){img->aspect, l - 1, 1, 0, img->layers};
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &b);
    VkImageBlit r = {0};
    r.srcSubresource = (VkImageSubresourceLayers){img->aspect, l - 1, 0, img->layers};
    r.srcOffsets[1] = (VkOffset3D){(int32_t)(img->w >> (l - 1)) ?: 1, (int32_t)(img->h >> (l - 1)) ?: 1, 1};
    r.dstSubresource = (VkImageSubresourceLayers){img->aspect, l, 0, img->layers};
    r.dstOffsets[1] = (VkOffset3D){(int32_t)(img->w >> l) ?: 1, (int32_t)(img->h >> l) ?: 1, 1};
    vkCmdBlitImage(c, img->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, img->image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r, VK_FILTER_LINEAR);
  }
  // levels 0..n-2 are TRANSFER_SRC, the last TRANSFER_DST: one barrier to SHADER_READ for all
  VkImageMemoryBarrier b[2] = {{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}, {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}};
  for (int i = 0; i < 2; i++) {
    b[i].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    b[i].srcQueueFamilyIndex = b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b[i].image = img->image;
    b[i].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
  }
  b[0].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
  b[0].subresourceRange = (VkImageSubresourceRange){img->aspect, 0, img->levels - 1, 0, img->layers};
  b[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  b[1].subresourceRange = (VkImageSubresourceRange){img->aspect, img->levels - 1, 1, 0, img->layers};
  vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT,
                       VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, NULL, 0,
                       NULL, 2, b);
  img->layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
}

static void GL_APIENTRY v_glGenerateMipmap(GLenum target) {
  gtex *t = bound_tex(target);
  if (!t || !t->img) return;
  flush_pass();
  blit_levels(t->img);
}

// ---------------------------------------------------------------- samplers
static void GL_APIENTRY v_glGenSamplers(GLsizei n, GLuint *out) {
  for (GLsizei i = 0; i < n; i++) {
    gsampler *s = calloc(1, sizeof *s);
    s->min_filter = GL_NEAREST_MIPMAP_LINEAR;
    s->mag_filter = GL_LINEAR;
    s->wrap_s = s->wrap_t = s->wrap_r = GL_REPEAT;
    s->min_lod = -1000;
    s->max_lod = 1000;
    s->dirty = 1;
    out[i] = tnew(&samplers, s);
  }
}

static void GL_APIENTRY v_glDeleteSamplers(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) {
    gsampler *s = tget(&samplers, names[i]);
    if (!s) continue;
    if (s->vks) vk_defer(sampler_free, (void *)(uintptr_t)s->vks);
    free(s);
    tdel(&samplers, names[i]);
    for (int u = 0; u < 32; u++)
      if (st.unit[u].sampler == names[i]) st.unit[u].sampler = 0;
  }
}

static void GL_APIENTRY v_glBindSampler(GLuint unit, GLuint s) { st.unit[unit & 31].sampler = s; }

static void GL_APIENTRY v_glSamplerParameteri(GLuint name, GLenum pname, GLint v) {
  gsampler *s = tget(&samplers, name);
  if (!s) return;
  switch (pname) {
    case GL_TEXTURE_MIN_FILTER: s->min_filter = v; break;
    case GL_TEXTURE_MAG_FILTER: s->mag_filter = v; break;
    case GL_TEXTURE_WRAP_S: s->wrap_s = v; break;
    case GL_TEXTURE_WRAP_T: s->wrap_t = v; break;
    case GL_TEXTURE_WRAP_R: s->wrap_r = v; break;
    default: fnvk_log("glSamplerParameteri 0x%x not handled", pname); return;
  }
  s->dirty = 1;
}

static void GL_APIENTRY v_glSamplerParameterf(GLuint name, GLenum pname, GLfloat v) {
  gsampler *s = tget(&samplers, name);
  if (!s) return;
  switch (pname) {
    case GL_TEXTURE_MIN_LOD: s->min_lod = v; break;
    case GL_TEXTURE_MAX_LOD: s->max_lod = v; break;
    case GL_TEXTURE_LOD_BIAS_: s->bias = v; break;
    case GL_TEXTURE_MAX_ANISOTROPY_: s->aniso = v; break;
    default: fnvk_log("glSamplerParameterf 0x%x not handled", pname); return;
  }
  s->dirty = 1;
}

static void GL_APIENTRY v_glSamplerParameterfv(GLuint name, GLenum pname, const GLfloat *v) {
  gsampler *s = tget(&samplers, name);
  if (!s) return;
  if (pname == GL_TEXTURE_BORDER_COLOR_) {
    memcpy(s->border, v, sizeof s->border);
    s->dirty = 1;
  } else
    v_glSamplerParameterf(name, pname, v[0]);
}

static VkSamplerAddressMode wrap(GLint w) {
  switch (w) {
    case GL_MIRRORED_REPEAT: return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
    case GL_CLAMP_TO_EDGE: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    case GL_CLAMP_TO_BORDER_: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
  }
  return VK_SAMPLER_ADDRESS_MODE_REPEAT;
}

static VkSampler make_sampler(GLint minf, GLint magf, GLint ws, GLint wt, GLint wr, float min_lod, float max_lod,
                              float bias, const float *border, float aniso) {
  VkSamplerCreateInfo ci = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
  ci.magFilter = magf == GL_LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
  ci.minFilter = minf == GL_LINEAR || minf == GL_LINEAR_MIPMAP_NEAREST || minf == GL_LINEAR_MIPMAP_LINEAR
                     ? VK_FILTER_LINEAR
                     : VK_FILTER_NEAREST;
  ci.mipmapMode = minf == GL_NEAREST_MIPMAP_LINEAR || minf == GL_LINEAR_MIPMAP_LINEAR ? VK_SAMPLER_MIPMAP_MODE_LINEAR
                                                                                     : VK_SAMPLER_MIPMAP_MODE_NEAREST;
  ci.addressModeU = wrap(ws);
  ci.addressModeV = wrap(wt);
  ci.addressModeW = wrap(wr);
  ci.mipLodBias = bias;
  ci.minLod = minf == GL_NEAREST || minf == GL_LINEAR ? 0 : (min_lod < 0 ? 0 : min_lod);
  ci.maxLod = minf == GL_NEAREST || minf == GL_LINEAR ? 0.25f : max_lod;
  if (aniso > 1 && vk.props.limits.maxSamplerAnisotropy > 1) {
    ci.anisotropyEnable = VK_TRUE;
    ci.maxAnisotropy = aniso < vk.props.limits.maxSamplerAnisotropy ? aniso : vk.props.limits.maxSamplerAnisotropy;
  }
  float a = border ? border[3] : 0, c = border ? border[0] + border[1] + border[2] : 0;
  ci.borderColor = a < 0.5f ? VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK
                   : c < 1.5f ? VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK
                              : VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE;
  VkSampler s = VK_NULL_HANDLE;
  VKCHECK(vkCreateSampler(vk.dev, &ci, NULL, &s));
  return s;
}

static int integer_format(VkFormat f) {
  return f == VK_FORMAT_R32G32B32A32_UINT || f == VK_FORMAT_R32G32_UINT || f == VK_FORMAT_R16_UINT ||
         f == VK_FORMAT_R16G16B16A16_UINT || f == VK_FORMAT_R32_UINT || f == VK_FORMAT_R8G8B8A8_UINT;
}

static VkSampler nearest_sampler(void) {  // integer images: GL ignores linear filtering there, Vulkan forbids it
  static VkSampler s;
  if (!s) s = make_sampler(GL_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, 0, 1000, 0, NULL, 0);
  return s;
}

static VkSampler sampler_for(GLuint unit, gtex *t) {
  if (t->img && integer_format(t->img->format)) return nearest_sampler();
  gsampler *s = tget(&samplers, st.unit[unit].sampler);
  if (s) {
    if (s->dirty || !s->vks) {
      if (s->vks) vk_defer(sampler_free, (void *)(uintptr_t)s->vks);
      s->vks = make_sampler(s->min_filter, s->mag_filter, s->wrap_s, s->wrap_t, s->wrap_r, s->min_lod, s->max_lod,
                            s->bias, s->border, s->aniso);
      s->dirty = 0;
    }
    return s->vks;
  }
  if (t->own_dirty || !t->own_sampler) {
    if (t->own_sampler) vk_defer(sampler_free, (void *)(uintptr_t)t->own_sampler);
    t->own_sampler = make_sampler(t->min_filter, t->mag_filter, t->wrap_s, t->wrap_t, t->wrap_r, -1000, 1000, 0,
                                  NULL, 0);
    t->own_dirty = 0;
  }
  return t->own_sampler;
}

// ---------------------------------------------------------------- framebuffers
static void GL_APIENTRY v_glGenFramebuffers(GLsizei n, GLuint *out) {
  for (GLsizei i = 0; i < n; i++) {
    gfbo *f = calloc(1, sizeof *f);
    f->draw[0] = 1;
    out[i] = tnew(&fbos, f);
  }
}

static void GL_APIENTRY v_glDeleteFramebuffers(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) {
    if (!tget(&fbos, names[i])) continue;
    if (pass.fbo == names[i]) flush_pass(), pass.fbo = 0;
    free(tget(&fbos, names[i]));
    tdel(&fbos, names[i]);
    if (st.draw_fbo == names[i]) st.draw_fbo = 0;
    if (st.read_fbo == names[i]) st.read_fbo = 0;
  }
}

static void GL_APIENTRY v_glBindFramebuffer(GLenum target, GLuint name) {
  if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) st.draw_fbo = name;
  if (target == GL_FRAMEBUFFER || target == GL_READ_FRAMEBUFFER) st.read_fbo = name;
}

static void GL_APIENTRY v_glFramebufferTexture2D(GLenum target, GLenum att, GLenum textarget, GLuint tex,
                                                 GLint level) {
  (void)textarget;
  if (level) fnvk_log("framebuffer attachment level %d not handled", level);
  gfbo *f = tget(&fbos, target == GL_READ_FRAMEBUFFER ? st.read_fbo : st.draw_fbo);
  if (!f) return;
  GLuint fname = target == GL_READ_FRAMEBUFFER ? st.read_fbo : st.draw_fbo;
  if (pass.fbo == fname) flush_pass();
  if (att == GL_COLOR_ATTACHMENT0) f->color[0] = tex;
  else if (att == GL_COLOR_ATTACHMENT1) f->color[1] = tex;
  else if (att == GL_DEPTH_STENCIL_ATTACHMENT || att == GL_DEPTH_ATTACHMENT) f->depth = tex;
  else fnvk_log("framebuffer attachment 0x%x not handled", att);
}

static void GL_APIENTRY v_glDrawBuffers(GLsizei n, const GLenum *bufs) {
  gfbo *f = tget(&fbos, st.draw_fbo);
  if (!f) return;
  if (pass.fbo == st.draw_fbo) flush_pass();
  f->draw[0] = f->draw[1] = 0;
  for (GLsizei i = 0; i < n; i++) {
    if (bufs[i] == GL_COLOR_ATTACHMENT0) f->draw[0] = 1;
    if (bufs[i] == GL_COLOR_ATTACHMENT1) f->draw[1] = 1;
  }
}

static void GL_APIENTRY v_glClearColor(GLfloat r, GLfloat g, GLfloat b, GLfloat a) {
  st.clear_color[0] = r;
  st.clear_color[1] = g;
  st.clear_color[2] = b;
  st.clear_color[3] = a;
}

static void GL_APIENTRY v_glClear(GLbitfield mask) {
  fb_targets t;
  fb_resolve(st.draw_fbo, &t);
  if (!t.depth) mask &= ~(GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT);
  if (!t.ncolor) mask &= ~GL_COLOR_BUFFER_BIT;
  if (!mask) return;
  int full = !st.scissor_test ||
             (st.sc[0] <= 0 && st.sc[1] <= 0 && st.sc[0] + st.sc[2] >= (GLint)t.w && st.sc[1] + st.sc[3] >= (GLint)t.h);
  int masks_full = (!(mask & GL_COLOR_BUFFER_BIT) || (st.cmask[0] == 0xf && (t.ncolor < 2 || st.cmask[1] == 0xf))) &&
                   (!(mask & GL_STENCIL_BUFFER_BIT) || (st.swmask & 0xff) == 0xff) &&
                   (!(mask & GL_DEPTH_BUFFER_BIT) || st.depth_mask);
  VkClearValue cc;
  memcpy(cc.color.float32, st.clear_color, sizeof cc.color.float32);
  // A full clear of attachments not in the open pass: deferred on the images (no pass of its own).
  if (full && masks_full && !in_pass(t.color[0]) && !in_pass(t.color[1]) && !in_pass(t.depth)) {
    if (mask & GL_COLOR_BUFFER_BIT)
      for (int i = 0; i < t.ncolor; i++) {
        t.color[i]->pending = VK_IMAGE_ASPECT_COLOR_BIT;
        t.color[i]->clear = cc;
      }
    if (t.depth && (mask & (GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT))) {
      VkImageAspectFlags a = (mask & GL_DEPTH_BUFFER_BIT ? VK_IMAGE_ASPECT_DEPTH_BIT : 0) |
                             (mask & GL_STENCIL_BUFFER_BIT ? t.depth->aspect & VK_IMAGE_ASPECT_STENCIL_BIT : 0);
      t.depth->pending |= a;
      t.depth->clear.depthStencil = (VkClearDepthStencilValue){1, 0};
    }
    return;
  }
  ensure_pass();
  VkClearAttachment ca[3];
  int n = 0;
  if (mask & GL_COLOR_BUFFER_BIT)
    for (int i = 0; i < t.ncolor; i++)
      if (st.cmask[i]) ca[n++] = (VkClearAttachment){VK_IMAGE_ASPECT_COLOR_BIT, (uint32_t)i, cc};
  VkImageAspectFlags ds = (mask & GL_DEPTH_BUFFER_BIT && st.depth_mask ? VK_IMAGE_ASPECT_DEPTH_BIT : 0) |
                          (mask & GL_STENCIL_BUFFER_BIT && (t.depth->aspect & VK_IMAGE_ASPECT_STENCIL_BIT)
                               ? VK_IMAGE_ASPECT_STENCIL_BIT
                               : 0);
  if (ds) ca[n++] = (VkClearAttachment){ds, 0, {.depthStencil = {1, 0}}};
  VkClearRect r = {{{0, 0}, {t.w, t.h}}, 0, 1};
  if (st.scissor_test) {
    int x0 = st.sc[0] < 0 ? 0 : st.sc[0], y0 = st.sc[1] < 0 ? 0 : st.sc[1];
    int x1 = st.sc[0] + st.sc[2], y1 = st.sc[1] + st.sc[3];
    if (x1 > (int)t.w) x1 = t.w;
    if (y1 > (int)t.h) y1 = t.h;
    if (x1 <= x0 || y1 <= y0) return;
    r.rect = (VkRect2D){{x0, t.flip ? (int32_t)t.h - y1 : y0}, {(uint32_t)(x1 - x0), (uint32_t)(y1 - y0)}};
  }
  if (n) vkCmdClearAttachments(vk_cmd(), n, ca, 1, &r);
}

// Copies between images: whole-image layout changes, outside passes.
static void copy_region(vk_image *src, uint32_t sl, uint32_t slayer, int32_t sx, int32_t sy, int32_t sz,
                        vk_image *dst, uint32_t dl, uint32_t dlayer, int32_t dx, int32_t dy, int32_t dz, uint32_t w,
                        uint32_t h, uint32_t d) {
  flush_pass();
  if (src == dst) {
    fnvk_log("copy within one image not handled");
    return;
  }
  // bounds: extent is in source texels; a compressed side scales by its block size
  uint32_t sbw, dbw;
  vk_format_block(src->format, &sbw);
  vk_format_block(dst->format, &dbw);
  uint32_t dw = w * dbw / sbw, dh = h * dbw / sbw;
  uint32_t slw = src->w >> sl ?: 1, slh = src->h >> sl ?: 1, dlw = dst->w >> dl ?: 1, dlh = dst->h >> dl ?: 1;
  // whole blocks inside the level only: a block past the edge of a level that is not a multiple of the block is
  // not a valid copy (the GLES driver on Adreno skipped those too, see LEARNING 2026-11-19)
  uint32_t dlw4 = dbw > 1 ? dlw / dbw * dbw : dlw, dlh4 = dbw > 1 ? dlh / dbw * dbw : dlh;
  if (sx + w * sbw / sbw > slw || sy + h > slh || (uint32_t)dx + dw > dlw4 || (uint32_t)dy + dh > dlh4) {
    static int told;
    if (told++ < 20)
      fnvk_log("copy out of bounds: src fmt %d level %u %ux%u at %d,%d size %ux%u -> dst fmt %d level %u %ux%u at %d,%d "
               "(%ux%u)", src->format, sl, slw, slh, sx, sy, w, h, dst->format, dl, dlw, dlh, dx, dy, dw, dh);
    if ((uint32_t)dx + dw > dlw4) dw = dlw4 - dx;
    if ((uint32_t)dy + dh > dlh4) dh = dlh4 - dy;
    w = dw * sbw / dbw;
    h = dh * sbw / dbw;
    if (sx + w > slw) w = slw - sx;
    if (sy + h > slh) h = slh - sy;
    if (!w || !h) return;
  }
  vk_materialize(src);
  vk_materialize(dst);
  src->keep_depth = 1;  // a copy source keeps its contents
  vk_image_layout(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
  vk_image_layout(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  int sa = src->view_type == VK_IMAGE_VIEW_TYPE_2D_ARRAY, da = dst->view_type == VK_IMAGE_VIEW_TYPE_2D_ARRAY;
  VkImageCopy r = {{src->aspect, sl, sa ? (uint32_t)sz : slayer, sa ? d : 1},
                   {sx, sy, sa ? 0 : sz},
                   {dst->aspect, dl, da ? (uint32_t)dz : dlayer, da ? d : 1},
                   {dx, dy, da ? 0 : dz},
                   {w, h, (sa || da) ? 1 : d}};
  vkCmdCopyImage(vk_cmd(), src->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst->image,
                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
  vk_mark_dirty(src);
  vk_mark_dirty(dst);
}

static void GL_APIENTRY v_glCopyImageSubData(GLuint src, GLenum st_, GLint sl, GLint sx, GLint sy, GLint sz,
                                             GLuint dst, GLenum dt, GLint dl, GLint dx, GLint dy, GLint dz,
                                             GLsizei w, GLsizei h, GLsizei d) {
  (void)st_;
  (void)dt;
  gtex *s = tget(&textures, src), *t = tget(&textures, dst);
  if (!s || !t || !s->img || !t->img) return;
  copy_region(s->img, sl, 0, sx, sy, sz, t->img, dl, 0, dx, dy, dz, w, h, d);
}

static vk_image *read_image(void) {
  fb_targets t;
  fb_resolve(st.read_fbo, &t);
  if (st.read_fbo) {
    gfbo *f = tget(&fbos, st.read_fbo);
    gtex *c = f ? tget(&textures, f->color[0]) : NULL;
    return c ? c->img : NULL;
  }
  return t.color[0];
}

static void GL_APIENTRY v_glCopyTexSubImage2D(GLenum target, GLint level, GLint xo, GLint yo, GLint x, GLint y,
                                              GLsizei w, GLsizei h) {
  gtex *t = bound_tex(target);
  vk_image *src = read_image();
  if (t && t->img && src) copy_region(src, 0, 0, x, y, 0, t->img, level, 0, xo, yo, 0, w, h, 1);
}

static void GL_APIENTRY v_glCopyTexSubImage3D(GLenum target, GLint level, GLint xo, GLint yo, GLint zo, GLint x,
                                              GLint y, GLsizei w, GLsizei h) {
  gtex *t = bound_tex(target);
  vk_image *src = read_image();
  if (t && t->img && src) copy_region(src, 0, 0, x, y, 0, t->img, level, 0, xo, yo, zo, w, h, 1);
}

static void GL_APIENTRY v_glBlitFramebuffer(GLint sx0, GLint sy0, GLint sx1, GLint sy1, GLint dx0, GLint dy0,
                                            GLint dx1, GLint dy1, GLbitfield mask, GLenum filter) {
  if (!(mask & GL_COLOR_BUFFER_BIT)) {
    fnvk_log("glBlitFramebuffer mask 0x%x not handled", mask);
    return;
  }
  vk_image *src = read_image();
  fb_targets t;
  fb_resolve(st.draw_fbo, &t);
  if (!src || !t.ncolor || src == t.color[0]) return;
  flush_pass();
  vk_materialize(src);
  vk_materialize(t.color[0]);
  vk_image_layout(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
  vk_image_layout(t.color[0], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkImageBlit r = {{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{sx0, sy0, 0}, {sx1, sy1, 1}},
                   {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{dx0, dy0, 0}, {dx1, dy1, 1}}};
  vkCmdBlitImage(vk_cmd(), src->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, t.color[0]->image,
                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r, filter == GL_LINEAR ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
  vk_mark_dirty(src);
  vk_mark_dirty(t.color[0]);
}

// RGBA8 / BGRA8 images to the GL_RGBA + GL_UNSIGNED_BYTE the game asks for.
static void read_rgba(vk_image *img, uint32_t level, uint32_t layer, int x, int y, int w, int h, void *out) {
  uint32_t bw, bpp = vk_format_block(img->format, &bw);
  size_t size = (size_t)w * h * bpp;
  uint8_t *tmp = malloc(size);
  vk_download(img, level, x, y, layer, w, h, tmp, size);
  uint8_t *o = out;
  size_t opitch = ((size_t)w * 4 + st.pack_align - 1) / st.pack_align * st.pack_align;
  for (int r = 0; r < h; r++)
    for (int c = 0; c < w; c++) {
      const uint8_t *p = tmp + ((size_t)r * w + c) * bpp;
      uint8_t *q = o + r * opitch + (size_t)c * 4;
      if (img->format == VK_FORMAT_B8G8R8A8_UNORM) q[0] = p[2], q[1] = p[1], q[2] = p[0], q[3] = p[3];
      else if (bpp == 4) memcpy(q, p, 4);
      else if (bpp == 1) q[0] = p[0], q[1] = q[2] = 0, q[3] = 255;
      else if (img->format == VK_FORMAT_R8G8_UNORM) q[0] = p[0], q[1] = p[1], q[2] = 0, q[3] = 255;
      else memcpy(q, p, bpp < 4 ? bpp : 4);
    }
  free(tmp);
}

static void GL_APIENTRY v_glReadPixels(GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLenum type, void *out) {
  vk_image *src = read_image();
  if (!src || format != GL_RGBA || type != GL_UNSIGNED_BYTE) {
    fnvk_log("glReadPixels 0x%x/0x%x not handled", format, type);
    return;
  }
  flush_pass();
  read_rgba(src, 0, 0, x, y, w, h, out);
}

static void GL_APIENTRY v_glGetTexImage(GLenum target, GLint level, GLenum format, GLenum type, void *out) {
  gtex *t = bound_tex(target);
  if (!t || !t->img) return;
  flush_pass();
  uint32_t w = t->w >> level ?: 1, h = t->h >> level ?: 1;
  if (format == GL_RGBA && type == GL_UNSIGNED_BYTE && !t->compressed) {
    read_rgba(t->img, level, 0, 0, 0, w, h, out);
    return;
  }
  // Compressed: the GPU decodes it into an RGBA8 image. Off by default: with it the game's GPU compression self-test
  // passes, and its GPU mipmap generation then loses the device on Adreno 830 (2026-11-21: only when the BC blocks
  // rendered into RGBA32UI are really copied into the atlas, directly or through a buffer; see LEARNING). Without,
  // the game compresses on the CPU.
  if (format == GL_RGBA && type == GL_UNSIGNED_BYTE && getenv("FNVK_BC_READBACK")) {
    vk_image *tmp = vk_image_create(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_VIEW_TYPE_2D, w, h, 1, 1, 1);
    if (!tmp) return;
    vk_materialize(t->img);
    vk_image_layout(t->img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    vk_image_layout(tmp, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkImageBlit r = {{VK_IMAGE_ASPECT_COLOR_BIT, (uint32_t)level, 0, 1}, {{0, 0, 0}, {(int32_t)w, (int32_t)h, 1}},
                     {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, 0, 0}, {(int32_t)w, (int32_t)h, 1}}};
    vkCmdBlitImage(vk_cmd(), t->img->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, tmp->image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r, VK_FILTER_NEAREST);
    vk_mark_dirty(t->img);
    read_rgba(tmp, 0, 0, 0, 0, w, h, out);
    vk_image_destroy(tmp);
    return;
  }
  uint32_t bw, bpp = vk_format_block(t->format, &bw);
  if (texel_bytes(format, type) == bpp && !t->compressed) {
    vk_download(t->img, level, 0, 0, 0, w, h, out, (size_t)w * h * bpp);
    return;
  }
  fnvk_log("glGetTexImage 0x%x/0x%x of format %d not handled", format, type, t->format);
}

static void GL_APIENTRY v_glGetCompressedTexImage(GLenum target, GLint level, void *out) {
  gtex *t = bound_tex(target);
  if (!t || !t->img || !t->compressed) return;
  if (t->bc) {
    static int told;
    if (!told++) fnvk_log("glGetCompressedTexImage of a converted texture: not available (no BC)");
    return;
  }
  flush_pass();
  uint32_t w = t->w >> level ?: 1, h = t->h >> level ?: 1, bw, bpp = vk_format_block(t->format, &bw);
  size_t size = (size_t)((w + 3) / 4) * ((h + 3) / 4) * bpp;
  uint32_t layers = t->target == GL_TEXTURE_2D_ARRAY ? t->d : 1;
  for (uint32_t l = 0; l < layers; l++) vk_download(t->img, level, 0, 0, l, w, h, (uint8_t *)out + l * size, size);
}

static void GL_APIENTRY v_glGetTexLevelParameteriv(GLenum target, GLint level, GLenum pname, GLint *v) {
  gtex *t = bound_tex(target);
  *v = 0;
  if (!t || !t->img) return;
  uint32_t w = t->w >> level ?: 1, h = t->h >> level ?: 1, bw, bpp = vk_format_block(t->format, &bw);
  switch (pname) {
    case GL_TEXTURE_WIDTH: *v = w; return;
    case GL_TEXTURE_HEIGHT: *v = h; return;
    case GL_TEXTURE_DEPTH: *v = t->d; return;
    case GL_TEXTURE_COMPRESSED: *v = t->compressed; return;
    case GL_TEXTURE_COMPRESSED_IMAGE_SIZE_:
      if (t->bc) bpp = t->bc == GL_COMPRESSED_RGB_S3TC_DXT1_ || t->bc == GL_COMPRESSED_RGBA_S3TC_DXT1_ ||
                       t->bc == GL_COMPRESSED_RED_RGTC1_ ? 8 : 16;  // the game asks in BC terms
      *v = t->compressed ? (GLint)(((w + 3) / 4) * ((h + 3) / 4) * bpp * (t->target == GL_TEXTURE_2D_ARRAY ? t->d : 1))
                         : 0;
      return;
  }
  fnvk_log("glGetTexLevelParameteriv 0x%x not handled", pname);
}

// ---------------------------------------------------------------- vertex arrays
static void GL_APIENTRY v_glGenVertexArrays(GLsizei n, GLuint *out) {
  for (GLsizei i = 0; i < n; i++) out[i] = tnew(&vaos, calloc(1, sizeof(gvao)));
}

static void GL_APIENTRY v_glDeleteVertexArrays(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) {
    free(tget(&vaos, names[i]));
    tdel(&vaos, names[i]);
    if (st.vao == names[i]) st.vao = 0;
  }
}

static void GL_APIENTRY v_glBindVertexArray(GLuint name) { st.vao = name; }
static void GL_APIENTRY v_glEnableVertexAttribArray(GLuint i) { cur_vao()->a[i & 15].enabled = 1; }
static void GL_APIENTRY v_glDisableVertexAttribArray(GLuint i) { cur_vao()->a[i & 15].enabled = 0; }

static void GL_APIENTRY v_glVertexAttribPointer(GLuint i, GLint size, GLenum type, GLboolean norm, GLsizei stride,
                                                const void *ptr) {
  gattr *a = &cur_vao()->a[i & 15];
  a->size = size;
  a->type = type;
  a->normalized = norm;
  a->integer = 0;
  a->stride = stride;
  a->offset = (uintptr_t)ptr;
  a->buffer = st.array_buffer;
}

static void GL_APIENTRY v_glVertexAttribIPointer(GLuint i, GLint size, GLenum type, GLsizei stride, const void *ptr) {
  v_glVertexAttribPointer(i, size, type, 0, stride, ptr);
  cur_vao()->a[i & 15].integer = 1;
}

static void GL_APIENTRY v_glVertexAttribDivisor(GLuint i, GLuint d) { cur_vao()->a[i & 15].divisor = d; }

static VkFormat attr_format(const gattr *a, uint32_t *bytes) {
  static const VkFormat f32[] = {VK_FORMAT_R32_SFLOAT, VK_FORMAT_R32G32_SFLOAT, VK_FORMAT_R32G32B32_SFLOAT,
                                 VK_FORMAT_R32G32B32A32_SFLOAT};
  static const VkFormat u32[] = {VK_FORMAT_R32_UINT, VK_FORMAT_R32G32_UINT, VK_FORMAT_R32G32B32_UINT,
                                 VK_FORMAT_R32G32B32A32_UINT};
  static const VkFormat u16[] = {VK_FORMAT_R16_UINT, VK_FORMAT_R16G16_UINT, VK_FORMAT_R16G16B16_UINT,
                                 VK_FORMAT_R16G16B16A16_UINT};
  static const VkFormat u8n[] = {VK_FORMAT_R8_UNORM, VK_FORMAT_R8G8_UNORM, VK_FORMAT_R8G8B8_UNORM,
                                 VK_FORMAT_R8G8B8A8_UNORM};
  static const VkFormat u8i[] = {VK_FORMAT_R8_UINT, VK_FORMAT_R8G8_UINT, VK_FORMAT_R8G8B8_UINT,
                                 VK_FORMAT_R8G8B8A8_UINT};
  int n = (a->size >= 1 && a->size <= 4 ? a->size : 4) - 1;
  switch (a->type) {
    case GL_FLOAT: *bytes = 4 * (n + 1); return f32[n];
    case GL_UNSIGNED_INT: *bytes = 4 * (n + 1); return a->integer ? u32[n] : f32[n];
    case GL_UNSIGNED_SHORT: *bytes = 2 * (n + 1); return u16[n];
    case GL_UNSIGNED_BYTE: *bytes = n + 1; return a->integer ? u8i[n] : u8n[n];
  }
  fnvk_log("vertex attribute type 0x%x not handled", a->type);
  *bytes = 4;
  return VK_FORMAT_R32_SFLOAT;
}

// ---------------------------------------------------------------- caps, blend, stencil, viewport
static void set_cap(GLenum cap, int on) {
  switch (cap) {
    case GL_BLEND: st.blend = on; return;
    case GL_SCISSOR_TEST: st.scissor_test = on; return;
    case GL_DEPTH_TEST: st.depth_test = on; return;
    case GL_STENCIL_TEST: st.stencil_test = on; return;
    case GL_CULL_FACE: case GL_DITHER: case 0x8DB9 /* FRAMEBUFFER_SRGB */: case 0x809D /* MULTISAMPLE */:
    case GL_DEBUG_OUTPUT: case GL_DEBUG_OUTPUT_SYNCHRONOUS: case 0x884F /* TEXTURE_CUBE_MAP_SEAMLESS */:
      return;
  }
  fnvk_log("gl%sable 0x%x not handled", on ? "En" : "Dis", cap);
}
static void GL_APIENTRY v_glEnable(GLenum cap) { set_cap(cap, 1); }
static void GL_APIENTRY v_glDisable(GLenum cap) { set_cap(cap, 0); }

static void GL_APIENTRY v_glBlendFuncSeparate(GLenum sr, GLenum dr, GLenum sa, GLenum da) {
  st.bsrc_rgb = sr;
  st.bdst_rgb = dr;
  st.bsrc_a = sa;
  st.bdst_a = da;
}
static void GL_APIENTRY v_glBlendEquationSeparate(GLenum rgb, GLenum a) {
  st.beq_rgb = rgb;
  st.beq_a = a;
}
static void GL_APIENTRY v_glColorMaski(GLuint i, GLboolean r, GLboolean g, GLboolean b, GLboolean a) {
  if (i < 2) st.cmask[i] = (r ? 1 : 0) | (g ? 2 : 0) | (b ? 4 : 0) | (a ? 8 : 0);
}
static void GL_APIENTRY v_glStencilFunc(GLenum f, GLint ref, GLuint mask) {
  st.sfunc = f;
  st.sref = ref;
  st.smask = mask;
}
static void GL_APIENTRY v_glStencilOp(GLenum fail, GLenum zfail, GLenum zpass) {
  st.sfail = fail;
  st.sdpfail = zfail;
  st.sdppass = zpass;
}
static void GL_APIENTRY v_glStencilMask(GLuint m) { st.swmask = m; }
static void GL_APIENTRY v_glViewport(GLint x, GLint y, GLsizei w, GLsizei h) {
  st.vp[0] = x, st.vp[1] = y, st.vp[2] = w, st.vp[3] = h;
}
static void GL_APIENTRY v_glScissor(GLint x, GLint y, GLsizei w, GLsizei h) {
  st.sc[0] = x, st.sc[1] = y, st.sc[2] = w, st.sc[3] = h;
}

static VkBlendFactor blend_factor(GLenum f) {
  switch (f) {
    case GL_ZERO: return VK_BLEND_FACTOR_ZERO;
    case GL_ONE: return VK_BLEND_FACTOR_ONE;
    case GL_SRC_COLOR: return VK_BLEND_FACTOR_SRC_COLOR;
    case GL_ONE_MINUS_SRC_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
    case GL_SRC_ALPHA: return VK_BLEND_FACTOR_SRC_ALPHA;
    case GL_ONE_MINUS_SRC_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    case GL_DST_ALPHA: return VK_BLEND_FACTOR_DST_ALPHA;
    case GL_ONE_MINUS_DST_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
    case GL_DST_COLOR: return VK_BLEND_FACTOR_DST_COLOR;
    case GL_ONE_MINUS_DST_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
    case GL_CONSTANT_COLOR: return VK_BLEND_FACTOR_CONSTANT_COLOR;
    case GL_ONE_MINUS_CONSTANT_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
  }
  return VK_BLEND_FACTOR_ONE;
}

static VkBlendOp blend_op(GLenum e) {
  switch (e) {
    case GL_FUNC_SUBTRACT: return VK_BLEND_OP_SUBTRACT;
    case GL_FUNC_REVERSE_SUBTRACT: return VK_BLEND_OP_REVERSE_SUBTRACT;
    case GL_MIN: return VK_BLEND_OP_MIN;
    case GL_MAX: return VK_BLEND_OP_MAX;
  }
  return VK_BLEND_OP_ADD;
}

static VkCompareOp compare_op(GLenum f) { return (VkCompareOp)((f - GL_NEVER) & 7); }  // same order as GL

static VkStencilOp stencil_op(GLenum o) {
  switch (o) {
    case GL_ZERO: return VK_STENCIL_OP_ZERO;
    case GL_REPLACE: return VK_STENCIL_OP_REPLACE;
    case GL_INCR: return VK_STENCIL_OP_INCREMENT_AND_CLAMP;
    case GL_DECR: return VK_STENCIL_OP_DECREMENT_AND_CLAMP;
    case GL_INVERT: return VK_STENCIL_OP_INVERT;
    case GL_INCR_WRAP: return VK_STENCIL_OP_INCREMENT_AND_WRAP;
    case GL_DECR_WRAP: return VK_STENCIL_OP_DECREMENT_AND_WRAP;
  }
  return VK_STENCIL_OP_KEEP;
}

// ---------------------------------------------------------------- pipelines
typedef struct {
  uint32_t prog;
  uint8_t topology, blend, ncolor, depth_test, depth_write, stencil, nattr, nbind;
  uint8_t cmask[2];
  uint16_t eq[2], bf[4];
  uint16_t depth_func, sfunc, sfail, sdpfail, sdppass;
  uint32_t color_fmt[2], ds_fmt;
  struct {
    uint8_t loc, binding;
    uint16_t offset;
    uint32_t format;
  } attr[16];
  struct {
    uint16_t stride;
    uint8_t rate, pad;
  } bind[8];
} pipe_key;

typedef struct {
  pipe_key key;
  uint64_t hash;
  VkPipeline pipe;
} pipe_entry;

static pipe_entry *pipes;
static size_t npipes, cappipes;  // open addressing, capacity power of two
static VkPipelineCache pipeline_cache;

static uint64_t key_hash(const pipe_key *k) {
  const uint8_t *p = (const uint8_t *)k;
  uint64_t h = 1469598103934665603ull;
  for (size_t i = 0; i < sizeof *k; i++) h = (h ^ p[i]) * 1099511628211ull;
  return h | 1;
}

static void pipe_insert(pipe_entry e) {
  if ((npipes + 1) * 2 > cappipes) {
    pipe_entry *old = pipes;
    size_t oldcap = cappipes;
    cappipes = cappipes ? cappipes * 2 : 1024;
    pipes = calloc(cappipes, sizeof *pipes);
    npipes = 0;
    for (size_t i = 0; i < oldcap; i++)
      if (old[i].hash) pipe_insert(old[i]);
    free(old);
  }
  size_t i = e.hash & (cappipes - 1);
  while (pipes[i].hash) i = (i + 1) & (cappipes - 1);
  pipes[i] = e;
  npipes++;
}

static void pipe_free(void *p) { vkDestroyPipeline(vk.dev, (VkPipeline)(uintptr_t)p, NULL); }

static void pipelines_forget(uint32_t serial) {
  if (!cappipes) return;
  pipe_entry *old = pipes;
  size_t oldcap = cappipes;
  pipes = calloc(cappipes, sizeof *pipes);
  npipes = 0;
  for (size_t i = 0; i < oldcap; i++) {
    if (!old[i].hash) continue;
    if (old[i].key.prog == serial) vk_defer(pipe_free, (void *)(uintptr_t)old[i].pipe);
    else pipe_insert(old[i]);
  }
  free(old);
}

static VkPipeline pipeline_create(const pipe_key *k, gprog *g) {
  VkPipelineShaderStageCreateInfo stages[2] = {
      {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, g->vs, "main", NULL},
      {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, g->fs, "main", NULL}};
  VkVertexInputBindingDescription vb[8];
  VkVertexInputAttributeDescription va[16];
  for (int i = 0; i < k->nbind; i++)
    vb[i] = (VkVertexInputBindingDescription){(uint32_t)i, k->bind[i].stride,
                                              k->bind[i].rate ? VK_VERTEX_INPUT_RATE_INSTANCE
                                                              : VK_VERTEX_INPUT_RATE_VERTEX};
  for (int i = 0; i < k->nattr; i++)
    va[i] = (VkVertexInputAttributeDescription){k->attr[i].loc, k->attr[i].binding, (VkFormat)k->attr[i].format,
                                                k->attr[i].offset};
  VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO, NULL, 0,
                                             k->nbind, vb, k->nattr, va};
  VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, NULL, 0,
                                               (VkPrimitiveTopology)k->topology, VK_FALSE};
  VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, NULL, 0, 1, NULL, 1,
                                          NULL};
  VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
  rs.polygonMode = VK_POLYGON_MODE_FILL;
  rs.cullMode = VK_CULL_MODE_NONE;
  rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
  rs.lineWidth = 1;
  VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
  ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
  VkPipelineDepthStencilStateCreateInfo ds = {VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
  ds.depthTestEnable = k->depth_test;
  ds.depthWriteEnable = k->depth_write;
  ds.depthCompareOp = compare_op(k->depth_func);
  ds.stencilTestEnable = k->stencil;
  ds.front.failOp = stencil_op(k->sfail);
  ds.front.passOp = stencil_op(k->sdppass);
  ds.front.depthFailOp = stencil_op(k->sdpfail);
  ds.front.compareOp = compare_op(k->sfunc);
  ds.back = ds.front;
  VkPipelineColorBlendAttachmentState cb[2];
  memset(cb, 0, sizeof cb);
  for (int i = 0; i < k->ncolor; i++) {
    VkFormat cfmt = (VkFormat)k->color_fmt[i];
    int integer = cfmt == VK_FORMAT_R32G32B32A32_UINT || cfmt == VK_FORMAT_R32G32_UINT || cfmt == VK_FORMAT_R16_UINT ||
                  cfmt == VK_FORMAT_R16G16B16A16_UINT || cfmt == VK_FORMAT_R32_UINT || cfmt == VK_FORMAT_R8G8B8A8_UINT;
    cb[i].blendEnable = k->blend && !integer;  // GL ignores blending on integer targets
    cb[i].srcColorBlendFactor = blend_factor(k->bf[0]);
    cb[i].dstColorBlendFactor = blend_factor(k->bf[1]);
    cb[i].colorBlendOp = blend_op(k->eq[0]);
    cb[i].srcAlphaBlendFactor = blend_factor(k->bf[2]);
    cb[i].dstAlphaBlendFactor = blend_factor(k->bf[3]);
    cb[i].alphaBlendOp = blend_op(k->eq[1]);
    cb[i].colorWriteMask = k->cmask[i];
  }
  VkPipelineColorBlendStateCreateInfo bs = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
  bs.attachmentCount = k->ncolor;
  bs.pAttachments = cb;
  VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK,
                          VK_DYNAMIC_STATE_STENCIL_WRITE_MASK, VK_DYNAMIC_STATE_STENCIL_REFERENCE,
                          VK_DYNAMIC_STATE_BLEND_CONSTANTS};
  VkPipelineDynamicStateCreateInfo dy = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, NULL, 0,
                                         sizeof dyn / sizeof *dyn, dyn};
  VkFormat cf[2] = {(VkFormat)k->color_fmt[0], (VkFormat)k->color_fmt[1]};
  VkPipelineRenderingCreateInfoKHR ri = {VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO_KHR};
  ri.colorAttachmentCount = k->ncolor;
  ri.pColorAttachmentFormats = cf;
  ri.depthAttachmentFormat = (VkFormat)k->ds_fmt;
  ri.stencilAttachmentFormat =
      k->ds_fmt == VK_FORMAT_D24_UNORM_S8_UINT || k->ds_fmt == VK_FORMAT_D32_SFLOAT_S8_UINT ? (VkFormat)k->ds_fmt : 0;
  VkGraphicsPipelineCreateInfo ci = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO, &ri};
  ci.stageCount = 2;
  ci.pStages = stages;
  ci.pVertexInputState = &vi;
  ci.pInputAssemblyState = &ia;
  ci.pViewportState = &vp;
  ci.pRasterizationState = &rs;
  ci.pMultisampleState = &ms;
  ci.pDepthStencilState = &ds;
  ci.pColorBlendState = &bs;
  ci.pDynamicState = &dy;
  ci.layout = g->layout;
  VkPipeline p = VK_NULL_HANDLE;
  VKCHECK(vkCreateGraphicsPipelines(vk.dev, pipeline_cache, 1, &ci, NULL, &p));
  return p;
}

static uint64_t pipelines_made;

// ---------------------------------------------------------------- draw
static struct {
  uint64_t epoch;
  VkPipeline pipe;
  pipe_key key;
  int has_key;
  VkViewport vp;
  VkRect2D sc;
  int stencil_set;
  GLuint smask, swmask;
  GLint sref;
  VkPipelineLayout dlayout;
  int nw;
  VkWriteDescriptorSet w[FNVK_MAX_RES];
  union {
    VkDescriptorImageInfo i;
    VkDescriptorBufferInfo b;
    VkBufferView v;
  } info[FNVK_MAX_RES];
  VkBuffer vb[8];
  VkDeviceSize vboff[8];
  VkBuffer ib;
  VkDeviceSize iboff;
  VkIndexType itype;
} bound;

static vk_image *dummy[3];
static vk_slice dummy_ubo;

static vk_image *dummy_image(int view) {
  if (!dummy[view]) {
    VkImageViewType t = view == FNVK_VIEW_3D ? VK_IMAGE_VIEW_TYPE_3D
                        : view == FNVK_VIEW_2D_ARRAY ? VK_IMAGE_VIEW_TYPE_2D_ARRAY
                                                     : VK_IMAGE_VIEW_TYPE_2D;
    dummy[view] = vk_image_create(VK_FORMAT_R8G8B8A8_UNORM, t, 1, 1, 1, 1, 1);
    static const uint8_t px[4] = {0, 0, 0, 0};
    vk_upload(dummy[view], 0, 0, 0, 0, 1, 1, 1, px, 4, 1, 1);
  }
  return dummy[view];
}

static VkBufferView texel_view(gbuf *b, VkFormat f) {
  if (b->view && b->view_serial == b->serial && b->view_format == f) return b->view;
  if (b->view) vk_defer(view_free, (void *)(uintptr_t)b->view);
  uint32_t bw, bpp = vk_format_block(f, &bw);
  VkDeviceSize range = b->size / bpp * bpp;
  VkDeviceSize maxr = (VkDeviceSize)vk.props.limits.maxTexelBufferElements * bpp;
  if (range > maxr) range = maxr;
  if (!range) range = bpp;
  VkBufferViewCreateInfo ci = {VK_STRUCTURE_TYPE_BUFFER_VIEW_CREATE_INFO, NULL, 0, b->mem.buf, f, b->mem.off, range};
  b->view = VK_NULL_HANDLE;
  VKCHECK(vkCreateBufferView(vk.dev, &ci, NULL, &b->view));
  b->view_serial = b->serial;
  b->view_format = f;
  return b->view;
}

// Images a draw samples must be readable; a pass in the way is ended (rare: only never-written textures).
static void prepare_images(gprog *g) {
  vk_image *need[FNVK_MAX_RES];
  int n = 0;
  for (int i = 0; i < g->spv.nres; i++) {
    fnvk_resource *r = &g->spv.res[i];
    if (r->kind != FNVK_RES_SAMPLER) continue;
    GLuint u = (GLuint)g->unit[i] & 31;
    GLuint name = r->view == FNVK_VIEW_3D ? st.unit[u].t3d : r->view == FNVK_VIEW_2D_ARRAY ? st.unit[u].t2da
                                                                                          : st.unit[u].t2d;
    gtex *t = tget(&textures, name);
    vk_image *img = t && t->img ? t->img : dummy[r->view];
    if (img->layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL || img->dirty || img->pending) need[n++] = img;
  }
  if (!n) return;
  int reopen = pass.open;
  GLuint fbo = pass.fbo;
  for (int i = 0; i < n; i++) {
    if (pass.open && (need[i] == pass.t.color[0] || need[i] == pass.t.color[1] || need[i] == pass.t.depth)) {
      static int warned;
      if (!warned++) fnvk_log("a draw samples its own framebuffer attachment");
      continue;
    }
    if (pass.open) end_pass();
    vk_materialize(need[i]);  // a deferred clear the draw is about to read
    need[i]->keep_depth = 1;
    if (need[i]->layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
      vk_image_layout(need[i], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  }
  if (reopen && !pass.open) begin_pass(fbo);
}

static int draw_setup(GLenum mode) {
  gprog *g = tget(&programs, st.program);
  if (!g || !g->linked) return 0;
  // images before the pass (barriers), then the pass
  if (!pass.open || pass.fbo != st.draw_fbo) {
    end_pass();
    // make images this draw samples readable before begin_pass flushes the rest
    prepare_images(g);
    begin_pass(st.draw_fbo);
  } else
    prepare_images(g);
  VkCommandBuffer c = vk_cmd();
  if (bound.epoch != vk_cmd_epoch) memset(&bound, 0, sizeof bound), bound.epoch = vk_cmd_epoch;
  fb_targets *t = &pass.t;

  pipe_key k;
  memset(&k, 0, sizeof k);
  k.prog = g->serial;
  switch (mode) {
    case GL_POINTS: k.topology = VK_PRIMITIVE_TOPOLOGY_POINT_LIST; break;
    case GL_LINES: k.topology = VK_PRIMITIVE_TOPOLOGY_LINE_LIST; break;
    case GL_LINE_STRIP: k.topology = VK_PRIMITIVE_TOPOLOGY_LINE_STRIP; break;
    case GL_TRIANGLE_STRIP: k.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP; break;
    case GL_TRIANGLE_FAN: k.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN; break;
    default: k.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST; break;
  }
  k.blend = st.blend;
  if (st.blend) {
    k.eq[0] = st.beq_rgb, k.eq[1] = st.beq_a;
    k.bf[0] = st.bsrc_rgb, k.bf[1] = st.bdst_rgb, k.bf[2] = st.bsrc_a, k.bf[3] = st.bdst_a;
  }
  k.ncolor = t->ncolor;
  for (int i = 0; i < t->ncolor; i++) {
    k.color_fmt[i] = t->color[i]->format;
    k.cmask[i] = st.cmask[i];
  }
  if (t->depth) {
    k.ds_fmt = t->depth->format;
    k.depth_test = st.depth_test;
    k.depth_write = st.depth_test && st.depth_mask;
    k.depth_func = st.depth_test ? st.depth_func : 0;
    k.stencil = st.stencil_test;
    if (st.stencil_test) k.sfunc = st.sfunc, k.sfail = st.sfail, k.sdpfail = st.sdpfail, k.sdppass = st.sdppass;
  }
  // vertex input: one binding per (buffer, stride, divisor)
  gvao *v = cur_vao();
  GLuint bbuf[8];
  for (int i = 0; i < 16; i++) {
    gattr *a = &v->a[i];
    if (!a->enabled) continue;
    uint32_t bytes;
    VkFormat f = attr_format(a, &bytes);
    uint16_t stride = (uint16_t)(a->stride ? a->stride : bytes);
    int b;
    for (b = 0; b < k.nbind; b++)
      if (bbuf[b] == a->buffer && k.bind[b].stride == stride && k.bind[b].rate == (a->divisor != 0)) break;
    if (b == k.nbind) {
      if (k.nbind == 8) continue;
      bbuf[b] = a->buffer;
      k.bind[b].stride = stride;
      k.bind[b].rate = a->divisor != 0;
      k.nbind++;
    }
    k.attr[k.nattr].loc = (uint8_t)i;
    k.attr[k.nattr].binding = (uint8_t)b;
    k.attr[k.nattr].offset = (uint16_t)a->offset;
    k.attr[k.nattr].format = f;
    k.nattr++;
  }
  VkPipeline pipe = VK_NULL_HANDLE;
  if (bound.has_key && !memcmp(&bound.key, &k, sizeof k)) pipe = bound.pipe;
  uint64_t h = pipe ? 0 : key_hash(&k);
  if (!pipe && cappipes)
    for (size_t i = h & (cappipes - 1); pipes[i].hash; i = (i + 1) & (cappipes - 1))
      if (pipes[i].hash == h && !memcmp(&pipes[i].key, &k, sizeof k)) {
        pipe = pipes[i].pipe;
        break;
      }
  if (!pipe) {
    pipe = pipeline_create(&k, g);
    if (!pipe) return 0;
    pipe_insert((pipe_entry){k, h, pipe});
    pipelines_made++;
  }
  if (pipe != bound.pipe) {
    vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
    bound.pipe = pipe;
  }
  bound.key = k;
  bound.has_key = 1;
  // vertex buffers
  for (int b = 0; b < k.nbind; b++) {
    gbuf *gb = tget(&buffers, bbuf[b]);
    if (!gb || !gb->mem.ptr) {
      vkCmdBindVertexBuffers(c, b, 1, &dummy_ubo.buf, &dummy_ubo.off);
      bound.vb[b] = VK_NULL_HANDLE;
      continue;
    }
    gb->used_frame = vk_frame_serial;
    if (bound.vb[b] != gb->mem.buf || bound.vboff[b] != gb->mem.off) {
      vkCmdBindVertexBuffers(c, b, 1, &gb->mem.buf, &gb->mem.off);
      bound.vb[b] = gb->mem.buf;
      bound.vboff[b] = gb->mem.off;
    }
  }
  // dynamic state
  VkViewport vpt = {(float)st.vp[0], (float)st.vp[1], (float)st.vp[2], (float)st.vp[3], 0, 1};
  if (t->flip) vpt.y = (float)t->h - st.vp[1], vpt.height = -(float)st.vp[3];  // GL rows bottom-up
  if (memcmp(&vpt, &bound.vp, sizeof vpt)) {
    vkCmdSetViewport(c, 0, 1, &vpt);
    bound.vp = vpt;
  }
  VkRect2D sc = {{0, 0}, {t->w, t->h}};
  if (st.scissor_test) {
    int x0 = st.sc[0] < 0 ? 0 : st.sc[0], y0 = st.sc[1] < 0 ? 0 : st.sc[1];
    int x1 = st.sc[0] + st.sc[2], y1 = st.sc[1] + st.sc[3];
    if (x1 > (int)t->w) x1 = t->w;
    if (y1 > (int)t->h) y1 = t->h;
    if (x1 < x0) x1 = x0;
    if (y1 < y0) y1 = y0;
    sc = (VkRect2D){{x0, t->flip ? (int32_t)t->h - y1 : y0}, {(uint32_t)(x1 - x0), (uint32_t)(y1 - y0)}};
  }
  if (memcmp(&sc, &bound.sc, sizeof sc)) {
    vkCmdSetScissor(c, 0, 1, &sc);
    bound.sc = sc;
  }
  if (k.stencil && (!bound.stencil_set || bound.smask != st.smask || bound.swmask != st.swmask || bound.sref != st.sref)) {
    vkCmdSetStencilCompareMask(c, VK_STENCIL_FACE_FRONT_AND_BACK, st.smask);
    vkCmdSetStencilWriteMask(c, VK_STENCIL_FACE_FRONT_AND_BACK, st.swmask);
    vkCmdSetStencilReference(c, VK_STENCIL_FACE_FRONT_AND_BACK, (uint32_t)st.sref);
    bound.stencil_set = 1, bound.smask = st.smask, bound.swmask = st.swmask, bound.sref = st.sref;
  }
  // descriptors
  VkWriteDescriptorSet w[FNVK_MAX_RES];
  VkDescriptorImageInfo ii[FNVK_MAX_RES];
  VkDescriptorBufferInfo bi[FNVK_MAX_RES];
  VkBufferView tv[FNVK_MAX_RES];
  int nw = 0;
  for (int i = 0; i < g->spv.nres; i++) {
    fnvk_resource *r = &g->spv.res[i];
    VkWriteDescriptorSet *d = &w[nw++];
    memset(d, 0, sizeof *d);
    d->sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    d->dstBinding = i;
    d->descriptorCount = 1;
    if (r->kind == FNVK_RES_UBO) {
      int point = g->point[i] & 63;
      gbuf *gb = tget(&buffers, st.ubo[point].buf);
      if (gb && gb->mem.ptr) {
        gb->used_frame = vk_frame_serial;
        VkDeviceSize range = st.ubo[point].size ? (VkDeviceSize)st.ubo[point].size : gb->size;
        if (range > vk.props.limits.maxUniformBufferRange) range = vk.props.limits.maxUniformBufferRange;
        bi[i] = (VkDescriptorBufferInfo){gb->mem.buf, gb->mem.off + st.ubo[point].off, range};
      } else
        bi[i] = (VkDescriptorBufferInfo){dummy_ubo.buf, dummy_ubo.off, 256};
      d->descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
      d->pBufferInfo = &bi[i];
    } else if (r->kind == FNVK_RES_SAMPLER) {
      GLuint u = (GLuint)g->unit[i] & 31;
      GLuint name = r->view == FNVK_VIEW_3D ? st.unit[u].t3d : r->view == FNVK_VIEW_2D_ARRAY ? st.unit[u].t2da
                                                                                            : st.unit[u].t2d;
      gtex *tx = tget(&textures, name);
      vk_image *img = tx && tx->img ? tx->img : dummy[r->view];
      static gtex dummy_tex = {.min_filter = GL_NEAREST, .mag_filter = GL_NEAREST, .wrap_s = GL_REPEAT,
                               .wrap_t = GL_REPEAT, .wrap_r = GL_REPEAT, .own_dirty = 1};
      ii[i] = (VkDescriptorImageInfo){sampler_for(u, tx && tx->img ? tx : &dummy_tex), img->view,
                                      VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
      d->descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
      d->pImageInfo = &ii[i];
    } else {
      GLuint u = (GLuint)g->unit[i] & 31;
      gtex *tx = tget(&textures, st.unit[u].tbuf);
      gbuf *gb = tx ? tget(&buffers, tx->buffer) : NULL;
      if (!gb || !gb->mem.ptr) {
        nw--;
        static int warned;
        if (!warned++) fnvk_log("texel buffer %s has no buffer", r->name);
        continue;
      }
      gb->used_frame = vk_frame_serial;
      tv[i] = texel_view(gb, tx->buffer_format);
      d->descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
      d->pTexelBufferView = &tv[i];
    }
  }
  // skip the push when the same layout gets the same resources again
  int same = nw && bound.dlayout == g->layout && bound.nw == nw;
  for (int i = 0; same && i < nw; i++) {
    const VkWriteDescriptorSet *a = &w[i];
    same = a->dstBinding == bound.w[i].dstBinding && a->descriptorType == bound.w[i].descriptorType &&
           !memcmp(a->pImageInfo ? (const void *)a->pImageInfo : a->pBufferInfo ? (const void *)a->pBufferInfo
                                                                                 : (const void *)a->pTexelBufferView,
                   &bound.info[i], a->pImageInfo ? sizeof(VkDescriptorImageInfo)
                                   : a->pBufferInfo ? sizeof(VkDescriptorBufferInfo) : sizeof(VkBufferView));
  }
  if (nw && !same) {
    if (vk.push_descriptors)
      vk.push_descriptors(c, VK_PIPELINE_BIND_POINT_GRAPHICS, g->layout, 0, nw, w);
    else {
      VkDescriptorSet set = vk_alloc_set(g->dsl);
      if (!set) return 0;
      for (int i = 0; i < nw; i++) w[i].dstSet = set;
      vkUpdateDescriptorSets(vk.dev, (uint32_t)nw, w, 0, NULL);
      vkCmdBindDescriptorSets(c, VK_PIPELINE_BIND_POINT_GRAPHICS, g->layout, 0, 1, &set, 0, NULL);
    }
    bound.dlayout = g->layout;
    bound.nw = nw;
    for (int i = 0; i < nw; i++) {
      bound.w[i] = w[i];
      if (w[i].pImageInfo) bound.info[i].i = *w[i].pImageInfo;
      else if (w[i].pBufferInfo) bound.info[i].b = *w[i].pBufferInfo;
      else bound.info[i].v = *w[i].pTexelBufferView;
    }
  }
  return 1;
}


static void GL_APIENTRY v_glDrawElements(GLenum mode, GLsizei count, GLenum type, const void *indices) {
  if (count <= 0 || !draw_setup(mode)) return;
  gbuf *ib = tget(&buffers, cur_vao()->element);
  if (!ib || !ib->mem.ptr) return;
  ib->used_frame = vk_frame_serial;
  VkIndexType it = type == GL_UNSIGNED_SHORT ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
  VkCommandBuffer c = vk_cmd();
  if (bound.ib != ib->mem.buf || bound.iboff != ib->mem.off || bound.itype != it) {
    vkCmdBindIndexBuffer(c, ib->mem.buf, ib->mem.off, it);
    bound.ib = ib->mem.buf;
    bound.iboff = ib->mem.off;
    bound.itype = it;
  }
  uint32_t first = (uint32_t)((uintptr_t)indices / (it == VK_INDEX_TYPE_UINT16 ? 2 : 4));
  vkCmdDrawIndexed(c, count, 1, first, 0, 0);
  draws++;
}

static void GL_APIENTRY v_glDrawArrays(GLenum mode, GLint first, GLsizei count) {
  if (count <= 0 || !draw_setup(mode)) return;
  vkCmdDraw(vk_cmd(), count, 1, first, 0);
  draws++;
}

static void GL_APIENTRY v_glDrawArraysInstancedBaseInstance(GLenum mode, GLint first, GLsizei count, GLsizei inst,
                                                            GLuint base) {
  if (count <= 0 || inst <= 0 || !draw_setup(mode)) return;
  vkCmdDraw(vk_cmd(), count, inst, first, base);
  draws++;
}

// ---------------------------------------------------------------- queries (CPU time stands in for GPU time)
static table queries;

static uint64_t now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000000ull + ts.tv_nsec;
}

static void GL_APIENTRY v_glGenQueries(GLsizei n, GLuint *out) {
  for (GLsizei i = 0; i < n; i++) out[i] = tnew(&queries, calloc(1, sizeof(uint64_t)));
}
static void GL_APIENTRY v_glDeleteQueries(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) {
    free(tget(&queries, names[i]));
    tdel(&queries, names[i]);
  }
}
static void GL_APIENTRY v_glQueryCounter(GLuint id, GLenum target) {
  (void)target;
  uint64_t *q = tget(&queries, id);
  if (q) *q = now_ns();
}
static void GL_APIENTRY v_glBeginQuery(GLenum target, GLuint id) { v_glQueryCounter(id, target); }
static void GL_APIENTRY v_glEndQuery(GLenum target) { (void)target; }
static void GL_APIENTRY v_glGetQueryObjecti64v(GLuint id, GLenum pname, GLint64 *v) {
  uint64_t *q = tget(&queries, id);
  *v = pname == GL_QUERY_RESULT_AVAILABLE_ ? 1 : q ? (GLint64)*q : 0;
}

// ---------------------------------------------------------------- misc
static GLenum GL_APIENTRY v_glGetError(void) { return GL_NO_ERROR; }
static void GL_APIENTRY v_glFlush(void) {}
static void GL_APIENTRY v_glFinish(void) { vk_finish(); }
static void GL_APIENTRY v_glDebugMessageCallback(GLDEBUGPROC cb, const void *user) {
  (void)cb;
  (void)user;
}
static void GL_APIENTRY v_glDebugMessageControl(GLenum s, GLenum t, GLenum sev, GLsizei n, const GLuint *ids,
                                                GLboolean on) {
  (void)s, (void)t, (void)sev, (void)n, (void)ids, (void)on;
}

static const char *const extensions[] = {
    "GL_ARB_texture_storage", "GL_ARB_copy_image", "GL_KHR_debug", "GL_EXT_texture_compression_s3tc",
    "GL_ARB_texture_compression_rgtc", "GL_EXT_texture_filter_anisotropic", "GL_ARB_texture_filter_anisotropic"};
#define NEXT ((int)(sizeof extensions / sizeof *extensions))
static char renderer[300];

static const GLubyte *GL_APIENTRY v_glGetString(GLenum name) {
  switch (name) {
    case GL_VENDOR: return (const GLubyte *)"FNative";
    case GL_RENDERER:
      if (!renderer[0]) snprintf(renderer, sizeof renderer, "%s (Vulkan, fnvk)", vk.props.deviceName);
      return (const GLubyte *)renderer;
    case GL_VERSION: return (const GLubyte *)"4.1.0 Core Profile (FNative Vulkan)";
    case GL_SHADING_LANGUAGE_VERSION: return (const GLubyte *)"4.10";
    case GL_EXTENSIONS: return (const GLubyte *)"";
  }
  return (const GLubyte *)"";
}

static const GLubyte *GL_APIENTRY v_glGetStringi(GLenum name, GLuint i) {
  return name == GL_EXTENSIONS && (int)i < NEXT ? (const GLubyte *)extensions[i] : NULL;
}

static void GL_APIENTRY v_glGetIntegerv(GLenum pname, GLint *v) {
  const VkPhysicalDeviceLimits *l = &vk.props.limits;
  switch (pname) {
    case GL_NUM_EXTENSIONS: *v = NEXT; return;
    case GL_MAJOR_VERSION: *v = 4; return;
    case GL_MINOR_VERSION: *v = 1; return;
    case GL_CONTEXT_PROFILE_MASK_: *v = 1; return;
    case 0x821E /* CONTEXT_FLAGS */: *v = 0; return;
    case GL_MAX_TEXTURE_SIZE: *v = (GLint)l->maxImageDimension2D; return;
    case GL_MAX_ARRAY_TEXTURE_LAYERS: *v = (GLint)l->maxImageArrayLayers; return;
    case GL_MAX_3D_TEXTURE_SIZE: *v = (GLint)l->maxImageDimension3D; return;
    case GL_MAX_TEXTURE_BUFFER_SIZE_: *v = (GLint)l->maxTexelBufferElements; return;
    case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS: case GL_MAX_TEXTURE_IMAGE_UNITS: *v = 32; return;
    case GL_MAX_UNIFORM_BLOCK_SIZE: *v = (GLint)l->maxUniformBufferRange; return;
    case GL_MAX_UNIFORM_BUFFER_BINDINGS: *v = 64; return;
    case GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT: *v = (GLint)l->minUniformBufferOffsetAlignment; return;
    case GL_MAX_VERTEX_ATTRIBS: *v = 16; return;
    case GL_MAX_DRAW_BUFFERS: case GL_MAX_COLOR_ATTACHMENTS: *v = 2; return;
    case GL_MAX_SAMPLES: *v = 1; return;
    case GL_DRAW_FRAMEBUFFER_BINDING: *v = (GLint)st.draw_fbo; return;
    case GL_READ_FRAMEBUFFER_BINDING: *v = (GLint)st.read_fbo; return;
    case GL_VIEWPORT: memcpy(v, st.vp, sizeof st.vp); return;
    case 0x84FF: *v = (GLint)l->maxSamplerAnisotropy; return;
  }
  fnvk_log("glGetIntegerv 0x%x not handled", pname);
  *v = 0;
}

static void GL_APIENTRY v_glGetFloatv(GLenum pname, GLfloat *v) {
  if (pname == 0x84FF) {
    *v = vk.props.limits.maxSamplerAnisotropy;
    return;
  }
  GLint i = 0;
  v_glGetIntegerv(pname, &i);
  *v = (GLfloat)i;
}


// ---------------------------------------------------------------- present (Android window)
// The window (GL framebuffer 0) is drawn straight into the acquired swapchain image: viewport, scissor and clear
// rects mirror y (fb_targets.flip), so no copy. Fallback when no image could be had this frame (window just made
// or lost): the offscreen window image, blitted flipped. The native window comes from SDL every frame; a new one
// (the app came back) means a new surface and swapchain, none means background: no present.
static struct {
  void *window;
  VkSurfaceKHR surface;
  VkSwapchainKHR chain;
  VkImage images[8];
  vk_image *wrap[8];
  VkSemaphore done[8];
  VkSemaphore acquired[FNVK_FRAMES];
  uint32_t count, w, h;
  VkFormat format;
  int stale, failed;
  int frame_idx;  // image acquired for the frame being recorded, -1 none
} sc = {.frame_idx = -1};
static int present_on, present_traced;
#define PTRACE(...) \
  do { \
    if (present_traced < 3) { fnvk_log(__VA_ARGS__); fflush(stderr); } \
  } while (0)

void fnvk_set_native_window(void *w) {
  present_on = 1;
  sc.window = w;
}

static void wrap_free(void *p) {
  vk_image *img = p;
  vkDestroyImageView(vk.dev, img->attach_view, NULL);
  free(img);
}

static void chain_destroy(void) {
  if (!sc.chain) return;
  vk_finish();
  for (uint32_t i = 0; i < sc.count; i++) {
    vkDestroySemaphore(vk.dev, sc.done[i], NULL);
    wrap_free(sc.wrap[i]);
  }
  vkDestroySwapchainKHR(vk.dev, sc.chain, NULL);
  sc.chain = VK_NULL_HANDLE;
  sc.count = 0;
  sc.frame_idx = -1;
}

static void *surface_window;

static void surface_update(void) {
  if (sc.window == surface_window && (sc.surface || !sc.window)) return;
  chain_destroy();
  if (sc.surface) vkDestroySurfaceKHR(vk.inst, sc.surface, NULL);
  sc.surface = VK_NULL_HANDLE;
  surface_window = sc.window;
  sc.failed = 0;
  if (!sc.window) return;
  VkAndroidSurfaceCreateInfoKHR ci = {VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR, NULL, 0, sc.window};
  VkResult r = vkCreateAndroidSurfaceKHR(vk.inst, &ci, NULL, &sc.surface);
  if (r) fnvk_log("vkCreateAndroidSurfaceKHR: %d", r);
}

static int chain_create(void) {
  VkSurfaceCapabilitiesKHR caps;
  if (vkGetPhysicalDeviceSurfaceCapabilitiesKHR(vk.pd, sc.surface, &caps)) return 0;
  VkBool32 ok = 0;
  vkGetPhysicalDeviceSurfaceSupportKHR(vk.pd, vk.qfam, sc.surface, &ok);
  if (!ok) {
    fnvk_log("the queue cannot present to this window");
    return 0;
  }
  uint32_t nf = 0;
  vkGetPhysicalDeviceSurfaceFormatsKHR(vk.pd, sc.surface, &nf, NULL);
  VkSurfaceFormatKHR f[32];
  if (nf > 32) nf = 32;
  vkGetPhysicalDeviceSurfaceFormatsKHR(vk.pd, sc.surface, &nf, f);
  if (!nf) return 0;
  VkSurfaceFormatKHR use = f[0];
  for (uint32_t i = 0; i < nf; i++)
    if (f[i].format == VK_FORMAT_R8G8B8A8_UNORM) use = f[i];
  VkExtent2D ext = caps.currentExtent;
  if (ext.width == 0xffffffffu) ext = (VkExtent2D){win.w, win.h};
  if (!ext.width || !ext.height) return 0;
  uint32_t n = caps.minImageCount + 1;
  if (caps.maxImageCount && n > caps.maxImageCount) n = caps.maxImageCount;
  if (n > 8) n = 8;
  VkSwapchainCreateInfoKHR ci = {VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
  ci.surface = sc.surface;
  ci.minImageCount = n;
  ci.imageFormat = use.format;
  ci.imageColorSpace = use.colorSpace;
  ci.imageExtent = ext;
  ci.imageArrayLayers = 1;
  ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                  (caps.supportedUsageFlags & VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
  ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
  ci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
                        : caps.currentTransform;
  ci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                          ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                          : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
  ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
  ci.clipped = VK_TRUE;
  VkResult r = vkCreateSwapchainKHR(vk.dev, &ci, NULL, &sc.chain);
  if (r) {
    fnvk_log("vkCreateSwapchainKHR: %d", r);
    sc.chain = VK_NULL_HANDLE;
    return 0;
  }
  sc.count = 8;
  vkGetSwapchainImagesKHR(vk.dev, sc.chain, &sc.count, sc.images);
  VkSemaphoreCreateInfo si = {VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
  for (uint32_t i = 0; i < sc.count; i++) {
    VKCHECK(vkCreateSemaphore(vk.dev, &si, NULL, &sc.done[i]));
    vk_image *w = calloc(1, sizeof *w);
    w->image = sc.images[i];
    w->format = use.format;
    w->aspect = VK_IMAGE_ASPECT_COLOR_BIT;
    w->w = ext.width, w->h = ext.height, w->d = w->layers = w->levels = 1;
    w->view_type = VK_IMAGE_VIEW_TYPE_2D;
    w->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    w->swapchain = 1;
    VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = w->image;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = use.format;
    vi.subresourceRange = (VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VKCHECK(vkCreateImageView(vk.dev, &vi, NULL, &w->attach_view));
    sc.wrap[i] = w;
  }
  if (!sc.acquired[0])
    for (int i = 0; i < FNVK_FRAMES; i++) VKCHECK(vkCreateSemaphore(vk.dev, &si, NULL, &sc.acquired[i]));
  sc.w = ext.width;
  sc.h = ext.height;
  sc.format = use.format;
  sc.stale = 0;
  fnvk_log("swapchain %ux%u, format %d, %u images, transform current 0x%x used 0x%x", sc.w, sc.h, sc.format,
           sc.count, caps.currentTransform, ci.preTransform);
  return 1;
}

// Between frames (no pass, nothing recorded for the window yet): window, surface and swapchain up to date.
static void chain_maintain(void) {
  if (!present_on || !vk.has_swapchain) return;
  surface_update();
  if (!sc.surface || sc.failed) return;
  if (sc.stale) chain_destroy();
  if (!sc.chain && !chain_create()) sc.failed = 1;  // tried with this window; a new window tries again
}

static vk_image *sc_target(void) {
  if (sc.frame_idx >= 0) return sc.wrap[sc.frame_idx];
  if (!sc.chain || sc.stale || sc.w != win.w || sc.h != win.h || sc.format != VK_FORMAT_R8G8B8A8_UNORM) return NULL;
  VkSemaphore acq = sc.acquired[vk_frame_slot()];
  uint32_t idx = 0;
  VkResult r = vkAcquireNextImageKHR(vk.dev, sc.chain, 1000000000ull, acq, VK_NULL_HANDLE, &idx);
  PTRACE("acquire (slot %u): %d, image %u", vk_frame_slot(), r, idx);
  if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) sc.stale = 1;
  if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return NULL;
  // the next submit (end of frame, or an earlier finish) waits for the image before writing it
  vk_submit_wait(acq, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT);
  sc.frame_idx = (int)idx;
  sc.wrap[idx]->layout = VK_IMAGE_LAYOUT_UNDEFINED;  // the previous contents are not needed
  return sc.wrap[idx];
}

static void queue_present(uint32_t idx) {
  VkPresentInfoKHR pi = {VK_STRUCTURE_TYPE_PRESENT_INFO_KHR, NULL, 1, &sc.done[idx], 1, &sc.chain, &idx, NULL};
  VkResult r = vkQueuePresentKHR(vk.queue, &pi);
  PTRACE("present: %d", r);
  present_traced++;
  // SUBOPTIMAL (the screen is rotated against the panel, images are not pre-rotated) is kept: the system
  // rotates. Recreating on it cost a full GPU stall every frame (lab7: about 29 FPS).
  if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) sc.stale = 1;
}

// End of frame. Returns 1 when the frame was submitted (with a present).
static int present(void) {
  if (sc.frame_idx >= 0) {  // drawn straight into the swapchain image
    uint32_t idx = (uint32_t)sc.frame_idx;
    vk_image *img = sc.wrap[idx];
    vk_materialize(img);  // cleared and never drawn
    VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    VkPipelineStageFlags ss = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
    b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
    b.oldLayout = img->layout;
    b.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = img->image;
    b.subresourceRange = (VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(vk_cmd(), ss, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL, 1, &b);
    img->layout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    vk_submit_signal(sc.done[idx]);
    vk_end_frame();
    sc.frame_idx = -1;
    queue_present(idx);
    return 1;
  }
  // fallback: the offscreen window image, blitted flipped
  if (!present_on || !sc.chain || sc.stale || !win.color) return 0;
  VkSemaphore acq = sc.acquired[vk_frame_slot()];
  uint32_t idx = 0;
  VkResult r = vkAcquireNextImageKHR(vk.dev, sc.chain, 1000000000ull, acq, VK_NULL_HANDLE, &idx);
  PTRACE("acquire for a copy (slot %u): %d, image %u; window image %ux%u -> %ux%u", vk_frame_slot(), r, idx, win.w,
         win.h, sc.w, sc.h);
  if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) sc.stale = 1;
  if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return 0;
  vk_materialize(win.color);
  VkCommandBuffer c = vk_cmd();
  vk_image_layout(win.color, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
  vk_mark_dirty(win.color);
  VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  b.image = sc.images[idx];
  b.subresourceRange = (VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
  vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, NULL, 0, NULL, 1, &b);
  VkImageBlit blit = {{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, (int32_t)win.h, 0}, {(int32_t)win.w, 0, 1}},
                      {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, 0, 0}, {(int32_t)sc.w, (int32_t)sc.h, 1}}};
  vkCmdBlitImage(c, win.color->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, sc.images[idx],
                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit,
                 sc.w == win.w && sc.h == win.h ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
  b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
  b.dstAccessMask = 0;
  b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
  b.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, NULL, 0, NULL,
                       1, &b);
  sc.wrap[idx]->layout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  vk_submit_wait(acq, VK_PIPELINE_STAGE_TRANSFER_BIT);
  vk_submit_signal(sc.done[idx]);
  vk_end_frame();
  queue_present(idx);
  return 1;
}

// ---------------------------------------------------------------- public
#define F(n) {#n, (void *)v_##n}
static const struct {
  const char *name;
  void *fn;
} funcs[] = {
    F(glCreateShader), F(glShaderSource), F(glCompileShader), F(glGetShaderiv), F(glGetShaderInfoLog),
    F(glDeleteShader), F(glCreateProgram), F(glAttachShader), F(glDetachShader), F(glLinkProgram),
    F(glGetProgramiv), F(glGetProgramInfoLog), F(glDeleteProgram), F(glUseProgram), F(glGetUniformLocation),
    F(glGetUniformBlockIndex), F(glUniformBlockBinding), F(glUniform1i), F(glGenBuffers), F(glDeleteBuffers),
    F(glBindBuffer), F(glBufferData), F(glMapBufferRange), F(glMapBuffer), F(glUnmapBuffer), F(glBindBufferBase),
    F(glGenTextures), F(glDeleteTextures), F(glActiveTexture), F(glBindTexture), F(glTexParameteri),
    F(glTexImage2D), F(glTexImage3D), F(glTexSubImage2D), F(glTexSubImage3D), F(glCompressedTexImage2D),
    F(glCompressedTexSubImage2D), F(glCompressedTexSubImage3D), F(glTexBuffer), F(glPixelStorei),
    F(glGenerateMipmap), F(glGenSamplers), F(glDeleteSamplers), F(glBindSampler), F(glSamplerParameteri),
    F(glSamplerParameterf), F(glSamplerParameterfv), F(glGenFramebuffers), F(glDeleteFramebuffers),
    F(glBindFramebuffer), F(glFramebufferTexture2D), F(glDrawBuffers), F(glClearColor), F(glClear),
    F(glCopyImageSubData), F(glCopyTexSubImage2D), F(glCopyTexSubImage3D), F(glBlitFramebuffer), F(glReadPixels),
    F(glGetTexImage), F(glGetCompressedTexImage), F(glGetTexLevelParameteriv), F(glGenVertexArrays),
    F(glDeleteVertexArrays), F(glBindVertexArray), F(glEnableVertexAttribArray), F(glDisableVertexAttribArray),
    F(glVertexAttribPointer), F(glVertexAttribIPointer), F(glVertexAttribDivisor), F(glEnable), F(glDisable),
    F(glBlendFuncSeparate), F(glBlendEquationSeparate), F(glColorMaski), F(glStencilFunc), F(glStencilOp),
    F(glStencilMask), F(glViewport), F(glScissor), F(glDrawElements), F(glDrawArrays),
    F(glDrawArraysInstancedBaseInstance), F(glGenQueries), F(glDeleteQueries), F(glQueryCounter), F(glBeginQuery),
    F(glEndQuery), F(glGetQueryObjecti64v), F(glGetError), F(glFlush), F(glFinish), F(glDebugMessageCallback),
    F(glDebugMessageControl), F(glGetString), F(glGetStringi), F(glGetIntegerv), F(glGetFloatv),
};

static int active = -1;

int fnvk_active(void) {
  if (active < 0) {
    const char *r = getenv("FN_RENDERER");
    active = r && !strcmp(r, "vulkan");
    if (active && !vk_core_init()) {
      fnvk_log("Vulkan is not usable: OpenGL ES instead");
      active = 0;
    }
    if (active) {
      state_init();
      vk_end_pass_hook = flush_pass;
      VkPipelineCacheCreateInfo pc = {VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
      VKCHECK(vkCreatePipelineCache(vk.dev, &pc, NULL, &pipeline_cache));
      log_missing = getenv("FNVK_QUIET") == NULL;
      for (int v = 0; v < 3; v++) dummy_image(v);
      dummy_ubo = vk_alloc(256);
      memset(dummy_ubo.ptr, 0, 256);
    }
  }
  return active;
}

void fnvk_set_window_size_fn(void (*fn)(int *, int *)) { window_size_fn = fn; }

void *fnvk_get_proc(const char *name) {
  for (size_t i = 0; i < sizeof funcs / sizeof *funcs; i++)
    if (!strcmp(funcs[i].name, name)) return funcs[i].fn;
  return NULL;
}

void fnvk_swap(void) {
  flush_pass();
  if (!present()) vk_end_frame();
  chain_maintain();
  static uint64_t frames, last_draws, last_pipes;
  if (++frames % 600 == 0) {
    fnvk_log("frame %llu: %.0f draws/frame, %llu pipelines (+%llu)", (unsigned long long)frames,
             (draws - last_draws) / 600.0, (unsigned long long)pipelines_made,
             (unsigned long long)(pipelines_made - last_pipes));
    last_draws = draws;
    last_pipes = pipelines_made;
    fnvk_log("  per frame: %.1f passes, color %.1f (%.1f load), depth %.1f (%.1f load, %.1f not stored)",
             pstat.passes / 600.0, pstat.color / 600.0, pstat.color_load / 600.0, pstat.depth / 600.0,
             pstat.depth_load / 600.0, pstat.depth_dontcare / 600.0);
    memset(&pstat, 0, sizeof pstat);
  }
}

int fnvk_read_window(int w, int h, void *rgba) {
  if (!win.color || (uint32_t)w > win.w || (uint32_t)h > win.h) return 0;
  flush_pass();
  vk_image *src = sc.frame_idx >= 0 ? sc.wrap[sc.frame_idx] : win.color;
  GLint pa = st.pack_align;
  st.pack_align = 4;
  read_rgba(src, 0, 0, 0, 0, w, h, rgba);
  st.pack_align = pa;
  if (src->swapchain) {  // rows top-down: to GL order
    uint8_t *p = rgba, *tmp = malloc((size_t)w * 4);
    for (int y = 0; y < h / 2; y++) {
      memcpy(tmp, p + (size_t)y * w * 4, (size_t)w * 4);
      memcpy(p + (size_t)y * w * 4, p + (size_t)(h - 1 - y) * w * 4, (size_t)w * 4);
      memcpy(p + (size_t)(h - 1 - y) * w * 4, tmp, (size_t)w * 4);
    }
    free(tmp);
  }
  return 1;
}

