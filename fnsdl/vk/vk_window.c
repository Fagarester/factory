// The Vulkan window of the native renderer (fnsdl/nvk): the window framebuffer, the Android swapchain and the
// present, screenshots, and the GL queries the kept game code still makes (strings, limits, timers: values only).
// Until 0.141 this sat in vk_gl.c with the GL-on-Vulkan bridge (every GL call of the game translated); the bridge
// is gone, nvk replaces the game's renderer itself.
#include "fnvk.h"
#include "vk_internal.h"
#include "vk_shader.h"
#include "../fn_upscale.h"
#include "../upscale/sgsr1.h"

#include <GLES3/gl32.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define GL_QUERY_RESULT_AVAILABLE_ 0x8867
#define GL_CONTEXT_PROFILE_MASK_ 0x9126
#define GL_MAX_TEXTURE_BUFFER_SIZE_ 0x8C2B

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


// ---------------------------------------------------------------- default framebuffer (the "window")
static struct {
  vk_image *color, *depth;
  vk_image *depth_turned;  // for the swapchain image turned 90/270 degrees: width and height swapped
  vk_image *fake;          // NVK_FAKE_WINDOW (offscreen tests): stands in for a swapchain image
  int fake_rot;
  uint32_t w, h;           // as the game sees it (render scale applied)
  uint32_t rw, rh;         // the real window
} win = {.fake_rot = -1};
static void (*window_size_fn)(int *, int *);
void nvk_image_gone(vk_image *img);  // nvk_draw.c: its framebuffers with the image go

static void window_ensure(void) {
  int w = 0, h = 0;
  if (window_size_fn) window_size_fn(&w, &h);
  if (w <= 0 || h <= 0) w = 1280, h = 720;
  int rw = w, rh = h;
  fn_upscale_size(&w, &h);  // the game renders smaller; the upscale pass scales at present
  if (win.color && win.w == (uint32_t)w && win.h == (uint32_t)h && win.rw == (uint32_t)rw && win.rh == (uint32_t)rh)
    return;
  win.rw = rw;
  win.rh = rh;
  vk_image *old[4] = {win.color, win.depth, win.depth_turned, win.fake};
  for (int i = 0; i < 4; i++)
    if (old[i]) {
      nvk_image_gone(old[i]);  // nvk's cached framebuffers with it
      vk_image_destroy(old[i]);
    }
  win.depth_turned = win.fake = NULL;
  win.color = vk_image_create(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_VIEW_TYPE_2D, w, h, 1, 1, 1);
  win.depth = vk_image_create(vk.depth_stencil_format, VK_IMAGE_VIEW_TYPE_2D, w, h, 1, 1, 1);
  win.w = w;
  win.h = h;
  fnvk_log("window framebuffer %dx%d (window %dx%d)", w, h, rw, rh);
}

static vk_image *depth_for(int rot) {
  if (!(rot & 1)) return win.depth;
  if (!win.depth_turned)
    win.depth_turned = vk_image_create(vk.depth_stencil_format, VK_IMAGE_VIEW_TYPE_2D, win.h, win.w, 1, 1, 1);
  return win.depth_turned;
}

// NVK_FAKE_WINDOW=<0..3> (offscreen tests in proot, no swapchain): the window is drawn as into a swapchain image
// turned that many quarter turns clockwise; screenshots turn it back. Checks the flip/turn paths without a display.
static vk_image *fake_target(void) {
  if (win.fake_rot == -1) {
    const char *e = getenv("NVK_FAKE_WINDOW");
    win.fake_rot = e && *e ? atoi(e) & 3 : -2;
  }
  if (win.fake_rot < 0) return NULL;
  if (!win.fake) {
    int t = win.fake_rot & 1;
    win.fake = vk_image_create(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_VIEW_TYPE_2D, t ? win.rh : win.rw, t ? win.rw : win.rh,
                               1, 1, 1);
    fnvk_log("fake window image %ux%u, turned %d", win.fake->w, win.fake->h, win.fake_rot);
  }
  return win.fake;
}
// The game draws straight into it when not scaled (else into win.color, then the upscale pass into it)
static vk_image *fake_direct(void) {
  vk_image *f = fake_target();
  return f && win.w == win.rw && win.h == win.rh ? f : NULL;
}
static vk_image *sc_target(void);

static void read_rgba(vk_image *img, uint32_t level, uint32_t layer, int x, int y, int w, int h, void *out) {
  uint32_t bw, bpp = vk_format_block(img->format, &bw);
  size_t size = (size_t)w * h * bpp;
  uint8_t *tmp = malloc(size);
  vk_download(img, level, x, y, layer, w, h, tmp, size);
  uint8_t *o = out;
  size_t opitch = ((size_t)w * 4 + 4 - 1) / 4 * 4;
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

// ---------------------------------------------------------------- upscale pass
// The game drew into the offscreen window image (render scale < 1, FN_RENDER_SCALE / "Графика"; or no swapchain
// image could be had for the frame): one full-screen triangle draws it into the swapchain image, scaled with
// Snapdragon GSR v1 (as fn_upscale.c for OpenGL ES) or bilinear, flipped to rows top-down and turned with the
// swapchain (pre-rotation). The texture coordinates are GL's as in fn_upscale.c, so GSR samples the same.
static const char up_vs[] =
    "#version 310 es\n"
    "layout(push_constant) uniform PC { highp vec4 vp; highp int rot; } pc;\n"
    "layout(location=0) out highp vec4 in_TEXCOORD0;\n"
    "void main() {\n"
    "  vec2 p = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));\n"
    "  in_TEXCOORD0 = vec4(p, 0.0, 0.0);\n"
    "  vec2 c = vec2(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0);\n"  // GL's bottom row last: rows top-down
    "  if (pc.rot == 1) c = vec2(-c.y, c.x);\n"             // turned clockwise with the swapchain
    "  else if (pc.rot == 2) c = -c;\n"
    "  else if (pc.rot == 3) c = vec2(c.y, -c.x);\n"
    "  gl_Position = vec4(c, 0.0, 1.0);\n"
    "}\n";
static const char up_bilinear_fs[] =
    "#version 310 es\n"
    "precision mediump float;\n"
    "layout(set=0, binding=1) uniform mediump sampler2D ps0;\n"
    "layout(location=0) in highp vec4 in_TEXCOORD0;\n"
    "layout(location=0) out vec4 out_Target0;\n"
    "void main() { out_Target0 = vec4(textureLod(ps0, in_TEXCOORD0.xy, 0.0).rgb, 1.0); }\n";

static struct {
  VkFormat format;  // of the render pass and pipelines
  VkRenderPass rp;
  VkDescriptorSetLayout dsl;
  VkPipelineLayout layout;
  VkPipeline pipe[2];  // bilinear, GSR
  VkSampler sampler;
  VkFramebuffer fb[8];  // per swapchain image
  int failed[2];
  VkRenderPass rp_keep;  // the same, ending in COLOR_ATTACHMENT_OPTIMAL (fake window image of the tests)
  VkFramebuffer fake_fb;
  VkImageView fake_view;
} up;

static void fb_free(void *p) { vkDestroyFramebuffer(vk.dev, (VkFramebuffer)(uintptr_t)p, NULL); }

// GSR source with its uniform as a push constant (the sampler at set 0, binding 1 as written there); glslang does
// not take a precision qualifier on a constructor ("coord + highp vec2(...)"), the GLES drivers did
static char *gsr_source(void) {
  const char *a = "// #define UseUniformBlock", *b = "layout (set=0, binding = 0) uniform UniformBlock",
             *q = "+ highp vec2(";
  size_t n = strlen(sgsr1_frag) + 64;
  char *out = malloc(n), *o = out;
  for (const char *p = sgsr1_frag; *p;) {
    if (!strncmp(p, a, strlen(a))) o += sprintf(o, "#define UseUniformBlock"), p += strlen(a);
    else if (!strncmp(p, b, strlen(b))) o += sprintf(o, "layout(push_constant) uniform UniformBlock"), p += strlen(b);
    else if (!strncmp(p, q, strlen(q))) o += sprintf(o, "+ vec2("), p += strlen(q);
    else *o++ = *p++;
  }
  *o = 0;
  return out;
}

static VkPipeline up_pipeline(int gsr) {
  if (up.pipe[gsr] || up.failed[gsr]) return up.pipe[gsr];
  char *gs = gsr ? gsr_source() : NULL;
  fnvk_spirv sp;
  int ok = fnvk_compile_raw(up_vs, gsr ? gs : up_bilinear_fs, &sp);
  free(gs);
  if (!ok) {
    fnvk_log("upscale: %s shader failed: %s", gsr ? "GSR" : "bilinear", sp.log);
    up.failed[gsr] = 1;
    fnvk_free_spirv(&sp);
    return VK_NULL_HANDLE;
  }
  VkShaderModule mod[2];
  for (int i = 0; i < 2; i++) {
    VkShaderModuleCreateInfo mi = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, sp.words[i] * 4, sp.code[i]};
    VKCHECK(vkCreateShaderModule(vk.dev, &mi, NULL, &mod[i]));
  }
  fnvk_free_spirv(&sp);
  VkPipelineShaderStageCreateInfo st[2] = {
      {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, mod[0], "main", NULL},
      {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, mod[1], "main", NULL}};
  VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
  VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, NULL, 0,
                                               VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, VK_FALSE};
  VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, NULL, 0, 1, NULL, 1,
                                          NULL};
  VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
  rs.polygonMode = VK_POLYGON_MODE_FILL;
  rs.cullMode = VK_CULL_MODE_NONE;
  rs.lineWidth = 1.0f;
  VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
  ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
  VkPipelineColorBlendAttachmentState cba = {0};
  cba.colorWriteMask = 0xf;
  VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
  cb.attachmentCount = 1;
  cb.pAttachments = &cba;
  VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
  VkPipelineDynamicStateCreateInfo dy = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, NULL, 0, 2, dyn};
  VkGraphicsPipelineCreateInfo ci = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
  ci.stageCount = 2;
  ci.pStages = st;
  ci.pVertexInputState = &vi;
  ci.pInputAssemblyState = &ia;
  ci.pViewportState = &vp;
  ci.pRasterizationState = &rs;
  ci.pMultisampleState = &ms;
  ci.pColorBlendState = &cb;
  ci.pDynamicState = &dy;
  ci.layout = up.layout;
  ci.renderPass = up.rp;
  VkResult r = vkCreateGraphicsPipelines(vk.dev, VK_NULL_HANDLE, 1, &ci, NULL, &up.pipe[gsr]);
  vkDestroyShaderModule(vk.dev, mod[0], NULL);
  vkDestroyShaderModule(vk.dev, mod[1], NULL);
  if (r) {
    fnvk_log("upscale: pipeline %d", r);
    up.pipe[gsr] = VK_NULL_HANDLE;
    up.failed[gsr] = 1;
  }
  return up.pipe[gsr];
}

// Render pass, layouts and sampler for swapchain images of `format` (made again when it changes).
static int up_ensure(VkFormat format) {
  if (up.rp && up.format == format) return 1;
  if (up.rp) {
    vk_finish();
    for (int i = 0; i < 2; i++) {
      if (up.pipe[i]) vkDestroyPipeline(vk.dev, up.pipe[i], NULL);
      up.pipe[i] = VK_NULL_HANDLE;
      up.failed[i] = 0;
    }
    vkDestroyRenderPass(vk.dev, up.rp, NULL);
    vkDestroyRenderPass(vk.dev, up.rp_keep, NULL);
  }
  up.format = format;
  VkAttachmentDescription a = {0, format, VK_SAMPLE_COUNT_1_BIT, VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                               VK_ATTACHMENT_STORE_OP_STORE, VK_ATTACHMENT_LOAD_OP_DONT_CARE,
                               VK_ATTACHMENT_STORE_OP_DONT_CARE, VK_IMAGE_LAYOUT_UNDEFINED,
                               VK_IMAGE_LAYOUT_PRESENT_SRC_KHR};
  VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
  VkSubpassDescription sub = {0, VK_PIPELINE_BIND_POINT_GRAPHICS, 0, NULL, 1, &ref, NULL, NULL, 0, NULL};
  // the acquire semaphore is waited at COLOR_ATTACHMENT_OUTPUT: the layout change waits there too
  VkSubpassDependency dep = {VK_SUBPASS_EXTERNAL, 0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                             VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0};
  VkRenderPassCreateInfo rpi = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO, NULL, 0, 1, &a, 1, &sub, 1, &dep};
  VKCHECK(vkCreateRenderPass(vk.dev, &rpi, NULL, &up.rp));
  a.initialLayout = a.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
  VKCHECK(vkCreateRenderPass(vk.dev, &rpi, NULL, &up.rp_keep));
  if (!up.layout) {
    VkDescriptorSetLayoutBinding b = {1, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_FRAGMENT_BIT, NULL};
    VkDescriptorSetLayoutCreateInfo di = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL,
                                          vk.push_descriptors ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0,
                                          1, &b};
    VKCHECK(vkCreateDescriptorSetLayout(vk.dev, &di, NULL, &up.dsl));
    VkPushConstantRange pr = {VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 20};
    VkPipelineLayoutCreateInfo li = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 1, &up.dsl, 1, &pr};
    VKCHECK(vkCreatePipelineLayout(vk.dev, &li, NULL, &up.layout));
    VkSamplerCreateInfo si = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = si.minFilter = VK_FILTER_LINEAR;
    si.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VKCHECK(vkCreateSampler(vk.dev, &si, NULL, &up.sampler));
  }
  return up.rp != VK_NULL_HANDLE;
}


// Records the pass: win.color scaled (and turned `rot` quarter turns clockwise) into the framebuffer.
static void up_draw(VkRenderPass rp, VkFramebuffer fb, uint32_t w, uint32_t h, int rot) {
  VkPipeline pipe = up_pipeline(fn_upscale_gsr() && (win.w != win.rw || win.h != win.rh));
  if (!pipe) pipe = up_pipeline(0);
  vk_materialize(win.color);
  vk_image_layout(win.color, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
  VkCommandBuffer c = vk_cmd();
  VkRenderPassBeginInfo bi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO, NULL, rp, fb, {{0, 0}, {w, h}}, 0, NULL};
  vkCmdBeginRenderPass(c, &bi, VK_SUBPASS_CONTENTS_INLINE);
  if (pipe) {
    vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
    VkViewport v = {0, 0, (float)w, (float)h, 0, 1};
    VkRect2D sr = {{0, 0}, {w, h}};
    vkCmdSetViewport(c, 0, 1, &v);
    vkCmdSetScissor(c, 0, 1, &sr);
    VkDescriptorImageInfo ii = {up.sampler, win.color->view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet wr = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    wr.dstBinding = 1;
    wr.descriptorCount = 1;
    wr.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wr.pImageInfo = &ii;
    if (vk.push_descriptors) {
      vk.push_descriptors(c, VK_PIPELINE_BIND_POINT_GRAPHICS, up.layout, 0, 1, &wr);
    } else {
      wr.dstSet = vk_alloc_set(up.dsl);
      vkUpdateDescriptorSets(vk.dev, 1, &wr, 0, NULL);
      vkCmdBindDescriptorSets(c, VK_PIPELINE_BIND_POINT_GRAPHICS, up.layout, 0, 1, &wr.dstSet, 0, NULL);
    }
    struct {
      float vp[4];
      int32_t rot;
    } pc = {{1.0f / win.w, 1.0f / win.h, (float)win.w, (float)win.h}, rot};
    vkCmdPushConstants(c, up.layout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, 20, &pc);
    vkCmdDraw(c, 3, 1, 0, 0);
  }
  vkCmdEndRenderPass(c);
}

// NVK_FAKE_WINDOW with render scale (offscreen tests): the upscale pass into the fake window image.
static void up_fake(void) {
  vk_image *f = fake_target();
  if (!f || fake_direct() || !win.color || !up_ensure(f->format)) return;
  if (up.fake_view != f->attach_view) {
    if (up.fake_fb) vk_defer(fb_free, (void *)(uintptr_t)up.fake_fb);
    VkFramebufferCreateInfo fi = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, NULL, 0, up.rp_keep, 1, &f->attach_view,
                                  f->w, f->h, 1};
    VKCHECK(vkCreateFramebuffer(vk.dev, &fi, NULL, &up.fake_fb));
    up.fake_view = f->attach_view;
  }
  vk_image_layout(f, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
  up_draw(up.rp_keep, up.fake_fb, f->w, f->h, win.fake_rot);
  f->layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
  vk_mark_dirty(f);
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
  int rot;           // images pre-rotated rot * 90 degrees clockwise (prerotate_on); 0 = the system turns them
  int transform_ok;  // SUBOPTIMAL checked: the surface transform is the one used (no new swapchain)
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
    if (up.fb[i]) vkDestroyFramebuffer(vk.dev, up.fb[i], NULL);
    up.fb[i] = VK_NULL_HANDLE;
  }
  vkDestroySwapchainKHR(vk.dev, sc.chain, NULL);
  sc.chain = VK_NULL_HANDLE;
  sc.count = 0;
  sc.frame_idx = -1;
}

static void *surface_window;

// On by default since 0.154 (user's Adreno 830, 0.151: no SUBOPTIMAL present, watts not above OpenGL ES);
// NVK_PREROTATE=0 leaves the turn to the system.
static int prerotate_on(void) {
  static int on = -1;
  if (on < 0) on = !getenv("NVK_PREROTATE") || atoi(getenv("NVK_PREROTATE"));
  return on;
}

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
  if (ext.width == 0xffffffffu) ext = (VkExtent2D){win.rw, win.rh};
  if (!ext.width || !ext.height) return 0;
  // pre-rotation: the images in the panel's own orientation, turned by us (Android reports currentExtent in the
  // turned orientation: width and height swapped for 90/270)
  int rot = 0;
  VkSurfaceTransformFlagBitsKHR tr = caps.currentTransform;
  if (prerotate_on() && (caps.supportedTransforms & tr))
    rot = tr == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR    ? 1
          : tr == VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR ? 2
          : tr == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR ? 3
                                                          : 0;
  if (rot & 1) ext = (VkExtent2D){ext.height, ext.width};
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
  ci.preTransform = rot                                                                    ? tr
                    : (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
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
  sc.rot = rot;
  sc.transform_ok = 0;
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
  uint32_t lw = sc.rot & 1 ? sc.h : sc.w, lh = sc.rot & 1 ? sc.w : sc.h;  // as the game sees it
  if (!sc.chain || sc.stale || lw != win.w || lh != win.h || sc.format != VK_FORMAT_R8G8B8A8_UNORM) return NULL;
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
  // rotates. Recreating on it cost a full GPU stall every frame (lab7: about 29 FPS). With pre-rotation a new
  // swapchain is made once when the transform changed (the phone turned the other way round).
  if (r == VK_SUBOPTIMAL_KHR && !sc.transform_ok && prerotate_on()) {
    VkSurfaceCapabilitiesKHR caps;
    static const int rots[] = {VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR, VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR,
                               VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR, VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR};
    if (!vkGetPhysicalDeviceSurfaceCapabilitiesKHR(vk.pd, sc.surface, &caps)) {
      if ((int)caps.currentTransform != rots[sc.rot]) {
        fnvk_log("present: SUBOPTIMAL, transform now 0x%x (images turned %d): new swapchain", caps.currentTransform,
                 sc.rot);
        sc.stale = 1;
      } else {
        fnvk_log("present: SUBOPTIMAL with the current transform 0x%x: kept", caps.currentTransform);
        sc.transform_ok = 1;
      }
    }
  }
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
  // the offscreen window image (render scale, or no swapchain image this frame): the upscale pass draws it
  if (!present_on || !sc.chain || sc.stale || !win.color || !up_ensure(sc.format)) return 0;
  VkSemaphore acq = sc.acquired[vk_frame_slot()];
  uint32_t idx = 0;
  VkResult r = vkAcquireNextImageKHR(vk.dev, sc.chain, 1000000000ull, acq, VK_NULL_HANDLE, &idx);
  PTRACE("acquire for the upscale pass (slot %u): %d, image %u; window image %ux%u -> %ux%u turned %d",
         vk_frame_slot(), r, idx, win.w, win.h, sc.w, sc.h, sc.rot);
  if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) sc.stale = 1;
  if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return 0;
  if (!up.fb[idx]) {
    VkFramebufferCreateInfo fi = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO, NULL, 0, up.rp, 1,
                                  &sc.wrap[idx]->attach_view, sc.w, sc.h, 1};
    VKCHECK(vkCreateFramebuffer(vk.dev, &fi, NULL, &up.fb[idx]));
  }
  // nothing drawn (no pipeline): the image is still presented, the acquire must be waited and released
  up_draw(up.rp, up.fb[idx], sc.w, sc.h, sc.rot);
  sc.wrap[idx]->layout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
  vk_submit_wait(acq, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
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
    F(glGenQueries), F(glDeleteQueries), F(glQueryCounter), F(glBeginQuery), F(glEndQuery),
    F(glGetQueryObjecti64v), F(glGetError), F(glFlush), F(glFinish), F(glDebugMessageCallback),
    F(glDebugMessageControl), F(glGetString), F(glGetStringi), F(glGetIntegerv), F(glGetFloatv),
};

static int active = -1;

// FN_RENDERER=vulkan or nvk: the native renderer (before 0.141 "vulkan" was the GL-on-Vulkan bridge)
int fnvk_active(void) {
  if (active < 0) {
    const char *r = getenv("FN_RENDERER");
    active = r && (!strcmp(r, "vulkan") || !strcmp(r, "nvk"));
    vk_core_native = active;  // Vulkan 1.1 core, render passes
    if (active && !vk_core_init()) {
      fnvk_log("Vulkan is not usable: OpenGL ES instead");
      active = 0;
    }
    if (active && !nvk_activate()) {
      fnvk_log("native renderer not usable on this game: OpenGL ES instead");
      active = 0;  // nothing of the game was changed
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

// The native renderer draws the window into the acquired swapchain image (or the offscreen window image).
vk_image *fnvk_window_target(int *flip, int *rot, vk_image **depth, uint32_t *w, uint32_t *h) {
  window_ensure();
  vk_image *sci = sc_target();
  *rot = sci ? sc.rot : 0;
  if (!sci && (sci = fake_direct())) *rot = win.fake_rot;
  *flip = sci != NULL;
  *depth = depth_for(*rot);
  *w = win.w;
  *h = win.h;
  return sci ? sci : win.color;
}

void fnvk_present_frame(void) {
  if (vk_end_pass_hook) vk_end_pass_hook();
  if (!sc.chain) up_fake();
  if (!present()) vk_end_frame();
  chain_maintain();
}

void fnvk_swap(void) { nvk_swap(); }

int fnvk_read_window(int w, int h, void *rgba) {
  if (!win.color) return 0;
  if (vk_end_pass_hook) vk_end_pass_hook();
  // the fake window image (tests) has the real window size: scaled by the upscale pass (the last frame's)
  vk_image *src = sc.frame_idx >= 0 ? sc.wrap[sc.frame_idx] : win.fake ? win.fake : win.color;
  int rot = sc.frame_idx >= 0 ? sc.rot : win.fake ? win.fake_rot : 0;
  uint32_t W = src == win.fake ? win.rw : win.w, H = src == win.fake ? win.rh : win.h;
  if ((uint32_t)w > W || (uint32_t)h > H) return 0;
  if (rot) {  // the turned image: whole, then each GL pixel (x, y from the bottom) from where the turn put it
    uint32_t pw = src->w, ph = src->h;
    uint32_t *all = malloc((size_t)pw * ph * 4), *o = rgba;
    read_rgba(src, 0, 0, 0, 0, (int)pw, (int)ph, all);
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        uint32_t px = rot == 1 ? (uint32_t)y : rot == 2 ? W - 1 - x : H - 1 - y;
        uint32_t py = rot == 1 ? (uint32_t)x : rot == 2 ? (uint32_t)y : W - 1 - x;
        o[(size_t)y * w + x] = all[(size_t)py * pw + px];
      }
    free(all);
    return 1;
  }
  read_rgba(src, 0, 0, 0, 0, w, h, rgba);
  if (src->swapchain || src == win.fake) {  // rows top-down: to GL order
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
