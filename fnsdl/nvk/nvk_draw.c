// Native Vulkan renderer: pipelines, render passes, bindings and draws. Replaces the GraphicsInterfaceOpenGL
// methods of the drawing path (bindPipelineState ... drawInstanced, FramebufferOpenGL clear/copy/blit); the
// game's own bindBindings stays and calls the replaced bind* methods.
//
// Images keep GL's row order (row 0 = GL's bottom row), as in the GL-on-Vulkan renderer: offscreen targets need
// no flip. Only the swapchain image (rows top-down) is drawn with a mirrored viewport and scissor.
#include <stdio.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>

#include "../vk/vk_shader.h"
#include "nvk.h"

#define MAXB 32  // bindings of one pipeline (game slots and descriptor bindings)

static int integer_format(VkFormat f);

// ---------------------------------------------------------------- render passes and framebuffers
typedef struct {
  VkFormat color[2], depth;
  uint8_t ncolor;
  uint8_t load[3];   // VkAttachmentLoadOp per attachment (color0, color1, depth/stencil)
  uint8_t stencil_load;
  uint8_t store_depth;
} rp_key;

typedef struct {
  rp_key k;
  VkRenderPass rp;
} rp_entry;
static rp_entry *rps;
static size_t nrps;

static int has_stencil(VkFormat f) { return f == VK_FORMAT_D24_UNORM_S8_UINT || f == VK_FORMAT_D32_SFLOAT_S8_UINT; }

static VkRenderPass render_pass(const rp_key *k) {
  for (size_t i = 0; i < nrps; i++)
    if (!memcmp(&rps[i].k, k, sizeof *k)) return rps[i].rp;
  VkAttachmentDescription a[3];
  VkAttachmentReference cref[2], dref;
  memset(a, 0, sizeof a);
  uint32_t n = 0;
  for (int i = 0; i < k->ncolor; i++, n++) {
    a[n].format = k->color[i];
    a[n].samples = VK_SAMPLE_COUNT_1_BIT;
    a[n].loadOp = (VkAttachmentLoadOp)k->load[i];
    a[n].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    a[n].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    a[n].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a[n].initialLayout = a[n].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    cref[i] = (VkAttachmentReference){n, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
  }
  if (k->depth) {
    a[n].format = k->depth;
    a[n].samples = VK_SAMPLE_COUNT_1_BIT;
    a[n].loadOp = (VkAttachmentLoadOp)k->load[2];
    a[n].storeOp = k->store_depth ? VK_ATTACHMENT_STORE_OP_STORE : VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a[n].stencilLoadOp = has_stencil(k->depth) ? (VkAttachmentLoadOp)k->stencil_load : VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    a[n].stencilStoreOp = has_stencil(k->depth) ? a[n].storeOp : VK_ATTACHMENT_STORE_OP_DONT_CARE;
    a[n].initialLayout = a[n].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    dref = (VkAttachmentReference){n, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    n++;
  }
  VkSubpassDescription sp = {0};
  sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
  sp.colorAttachmentCount = k->ncolor;
  sp.pColorAttachments = cref;
  sp.pDepthStencilAttachment = k->depth ? &dref : NULL;
  // ordering with the barriers recorded outside the passes (vk_image_layout) is done by those barriers
  VkSubpassDependency dep[2] = {
      {VK_SUBPASS_EXTERNAL, 0,
       VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
           VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
       VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT |
           VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
       VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
       VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
           VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
       0},
      {0, VK_SUBPASS_EXTERNAL,
       VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
       VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
       VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
       VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT, 0}};
  VkRenderPassCreateInfo ci = {VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
  ci.attachmentCount = n;
  ci.pAttachments = a;
  ci.subpassCount = 1;
  ci.pSubpasses = &sp;
  ci.dependencyCount = 2;
  ci.pDependencies = dep;
  VkRenderPass rp = VK_NULL_HANDLE;
  VKCHECK(vkCreateRenderPass(vk.dev, &ci, NULL, &rp));
  rps = realloc(rps, (nrps + 1) * sizeof *rps);
  rps[nrps++] = (rp_entry){*k, rp};
  return rp;
}

// the render pass pipelines are made with (compatibility depends on formats only)
static VkRenderPass compat_pass(VkFormat c0, VkFormat c1, int ncolor, VkFormat depth) {
  rp_key k;
  memset(&k, 0, sizeof k);
  k.color[0] = c0;
  k.color[1] = c1;
  k.ncolor = (uint8_t)ncolor;
  k.depth = depth;
  k.load[0] = k.load[1] = k.load[2] = k.stencil_load = VK_ATTACHMENT_LOAD_OP_LOAD;
  k.store_depth = 1;
  return render_pass(&k);
}

typedef struct {
  VkImageView v[3];
  uint32_t w, h;
  VkRenderPass rp;
  VkFramebuffer fb;
} fb_entry;
static fb_entry *fbs;
static size_t nfbs;

static void fb_destroy(void *p) { vkDestroyFramebuffer(vk.dev, (VkFramebuffer)(uintptr_t)p, NULL); }

static VkFramebuffer framebuffer(VkRenderPass rp, VkImageView *views, uint32_t n, uint32_t w, uint32_t h,
                                 int transient) {
  VkImageView v[3] = {0};
  memcpy(v, views, n * sizeof *views);
  if (!transient)
    for (size_t i = 0; i < nfbs; i++)
      if (fbs[i].rp == rp && fbs[i].w == w && fbs[i].h == h && !memcmp(fbs[i].v, v, sizeof v)) return fbs[i].fb;
  VkFramebufferCreateInfo ci = {VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
  ci.renderPass = rp;
  ci.attachmentCount = n;
  ci.pAttachments = views;
  ci.width = w;
  ci.height = h;
  ci.layers = 1;
  VkFramebuffer fb = VK_NULL_HANDLE;
  VKCHECK(vkCreateFramebuffer(vk.dev, &ci, NULL, &fb));
  if (transient) vk_defer(fb_destroy, (void *)(uintptr_t)fb);  // swapchain images come and go
  else {
    fbs = realloc(fbs, (nfbs + 1) * sizeof *fbs);
    fbs[nfbs++] = (fb_entry){{v[0], v[1], v[2]}, w, h, rp, fb};
  }
  return fb;
}

void nvk_image_gone(vk_image *img) {
  if (!img->attach_view) return;
  for (size_t i = 0; i < nfbs; i++)
    if (fbs[i].v[0] == img->attach_view || fbs[i].v[1] == img->attach_view || fbs[i].v[2] == img->attach_view) {
      vk_defer(fb_destroy, (void *)(uintptr_t)fbs[i].fb);
      fbs[i--] = fbs[--nfbs];
    }
}

// ---------------------------------------------------------------- targets and passes
void nvk_targets_of(uint32_t fbo, nvk_targets *t) {
  memset(t, 0, sizeof *t);
  if (!fbo) {
    t->color[0] = fnvk_window_target(&t->flip, &t->rot, &t->depth, &t->w, &t->h);
    t->ncolor = 1;
    return;
  }
  void *fb = nvk_fb_get(fbo);
  if (!fb) return;
  nvk_tex *c0 = nvk_bitmap_tex(FP(fb, FB_COLOR0)), *c1 = nvk_bitmap_tex(FP(fb, FB_COLOR1)),
          *d = nvk_bitmap_tex(FP(fb, FB_DEPTH));
  if (c0 && c0->img) t->color[t->ncolor++] = c0->img;
  if (c1 && c1->img) t->color[t->ncolor++] = c1->img;
  t->depth = d ? d->img : NULL;
  vk_image *any = t->ncolor ? t->color[0] : t->depth;
  if (any) t->w = any->w, t->h = any->h;
}

static struct {
  uint32_t fbo;          // bound target (glBindFramebuffer)
  int open;              // a render pass is recording
  uint32_t pass_fbo;
  nvk_targets t;         // targets of the open pass
  VkRenderPass compat;   // its compatible render pass (pipelines)
  int vx, vy, vw, vh;    // GL viewport
  int sx, sy, sw, sh;    // GL scissor
  int scissor_on;
  int mask[2];           // color write of attachments 0 and 1 (glColorMaski)
  float clear[4];        // glClearColor
  uint64_t epoch;        // vk_cmd_epoch the bound state belongs to
  VkPipeline pipe;
  VkPipelineLayout layout;
  int dyn_dirty;         // viewport/scissor/stencil reference to set again
} S = {.mask = {1, 1}};

static int depth_dontcare = -1;

// pass statistics (the 600-frame report): passes begun, colour attachments loaded / cleared, and why passes ended
enum { END_TARGET, END_IMAGES, END_COPY, END_OTHER, END_N };
static const char *const end_names[END_N] = {"target switch", "texture prep", "copy", "upload/submit/other"};
static uint64_t st_passes, st_color_load, st_color_clear, st_end[END_N];
static int tracing;  // the frame being traced (NVK_TRACE_AT): draws, and passes with their load ops
static uint64_t pass_draws;  // draws in the open pass (the trace)
static int end_reason = END_OTHER;

// The image size of a target: the window turned 90/270 degrees has width and height swapped.
static VkExtent2D target_extent(const nvk_targets *t) {
  return t->flip && (t->rot & 1) ? (VkExtent2D){t->h, t->w} : (VkExtent2D){t->w, t->h};
}

// A GL rectangle (x, y from the bottom left) of the target -> the image's rectangle (rows top-down when flipped,
// turned with the window). See window_prelude in vk_shader.cpp for the matching shader side.
static void target_rect(const nvk_targets *t, int *x, int *y, int *w, int *h) {
  int W = (int)t->w, H = (int)t->h, gx = *x, gy = *y, gw = *w, gh = *h;
  if (!t->flip) return;
  switch (t->rot) {
    case 0: *y = H - (gy + gh); break;
    case 1: *x = gy, *y = gx, *w = gh, *h = gw; break;
    case 2: *x = W - (gx + gw); break;
    case 3: *x = H - (gy + gh), *y = W - (gx + gw), *w = gh, *h = gw; break;
  }
}

static void begin_pass(void) {
  nvk_targets t;
  nvk_targets_of(S.fbo, &t);
  if (!t.ncolor && !t.depth) return;
  vk_image *except[3] = {t.color[0], t.color[1], t.depth};
  vk_flush_dirty(except, 3);
  rp_key k;
  memset(&k, 0, sizeof k);
  VkClearValue cv[3];
  VkImageView views[3];
  uint32_t n = 0;
  for (int i = 0; i < t.ncolor; i++, n++) {
    vk_image *c = t.color[i];
    vk_image_layout(c, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
    k.color[i] = c->format;
    k.load[i] = c->pending ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
    if (c->pending) st_color_clear++;
    else st_color_load++;
    cv[n] = c->clear;
    c->pending = 0;
    views[n] = c->attach_view;
    vk_mark_dirty(c);
  }
  k.ncolor = (uint8_t)t.ncolor;
  if (t.depth) {
    vk_image *d = t.depth;
    if (depth_dontcare < 0) depth_dontcare = !getenv("NVK_DEPTH_STORE");
    int fresh = d->layout == VK_IMAGE_LAYOUT_UNDEFINED;
    vk_image_layout(d, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    k.depth = d->format;
    k.load[2] = d->pending & VK_IMAGE_ASPECT_DEPTH_BIT ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
    k.stencil_load = d->pending & VK_IMAGE_ASPECT_STENCIL_BIT ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD;
    // depth/stencil that no pass ever loaded is not written back (the game clears it before use; as fnvk)
    if (!d->keep_depth && !fresh &&
        (k.load[2] == VK_ATTACHMENT_LOAD_OP_LOAD || (has_stencil(d->format) && k.stencil_load == VK_ATTACHMENT_LOAD_OP_LOAD)))
      d->keep_depth = 1;
    k.store_depth = d->keep_depth || !depth_dontcare;
    cv[n] = d->clear;
    views[n++] = d->attach_view;
    d->pending = 0;
    vk_mark_dirty(d);
  }
  VkRenderPass rp = render_pass(&k);
  S.compat = compat_pass(k.color[0], k.color[1], k.ncolor, k.depth);
  VkRenderPassBeginInfo bi = {VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
  bi.renderPass = rp;
  VkExtent2D ext = target_extent(&t);
  bi.framebuffer = framebuffer(rp, views, n, ext.width, ext.height, t.flip || (t.color[0] && t.color[0]->swapchain));
  bi.renderArea.extent = ext;
  bi.clearValueCount = n;
  bi.pClearValues = cv;
  vkCmdBeginRenderPass(vk_cmd(), &bi, VK_SUBPASS_CONTENTS_INLINE);
  st_passes++;
  pass_draws = 0;
  if (tracing) {
    static const char *const op[3] = {"load", "clear", "dontcare"};
    nvk_log("pass fbo %u %ux%u: c0 %s fmt %d%s%s, c1 %s, depth %s stencil %s store %d", S.fbo, ext.width,
            ext.height, t.ncolor ? op[k.load[0]] : "-", t.ncolor ? (int)t.color[0]->format : 0,
            t.color[0] && t.color[0]->swapchain ? " swapchain" : "", t.flip ? " window" : "",
            t.ncolor > 1 ? op[k.load[1]] : "-", t.depth ? op[k.load[2]] : "-", t.depth ? op[k.stencil_load] : "-",
            k.store_depth);
  }
  S.open = 1;
  S.pass_fbo = S.fbo;
  S.t = t;
  S.pipe = VK_NULL_HANDLE;
  S.dyn_dirty = 1;
}

void nvk_end_pass(void) {
  int why = end_reason;
  end_reason = END_OTHER;
  if (!S.open) return;
  st_end[why]++;
  if (tracing) nvk_log("  end: %llu draws, %s", (unsigned long long)pass_draws, end_names[why]);
  vkCmdEndRenderPass(vk_cmd());
  S.open = 0;
  S.pipe = VK_NULL_HANDLE;
}

static void ensure_pass(void) {
  if (S.open && S.pass_fbo == S.fbo) return;
  end_reason = END_TARGET;
  nvk_end_pass();
  begin_pass();
}

void nvk_target_bind(uint32_t fbo) {
  if (S.fbo == fbo) return;
  S.fbo = fbo;
  S.dyn_dirty = 1;
}

void nvk_viewport(int x, int y, int w, int h) {
  S.vx = x, S.vy = y, S.vw = w, S.vh = h;
  S.dyn_dirty = 1;
}

void nvk_scissor(int x, int y, int w, int h) {
  S.sx = x, S.sy = y, S.sw = w, S.sh = h;
  S.dyn_dirty = 1;
}

void nvk_scissor_enable(int on) {
  if (S.scissor_on != on) S.scissor_on = on, S.dyn_dirty = 1;
}

void nvk_color_mask(int att, int on) { S.mask[att & 1] = on; }

void nvk_clear_color(float r, float g, float b, float a) {
  S.clear[0] = r, S.clear[1] = g, S.clear[2] = b, S.clear[3] = a;
}

// Clear of the target `fbo` (the bound one for glClear). An open pass on it clears in place; else the images
// keep the clear and the next pass loads it as CLEAR (no extra pass for clear-then-draw).
static void clear_target(uint32_t fbo, const float rgba[4], int color0, int color1, int depth, int stencil, float dv,
                         uint32_t sv) {
  nvk_targets t;
  nvk_targets_of(fbo, &t);
  VkClearValue c;
  memcpy(c.color.float32, rgba, 16);
  if (S.open && S.pass_fbo == fbo) {
    VkClearAttachment a[3];
    uint32_t n = 0;
    for (int i = 0; i < S.t.ncolor && i < 2; i++)
      if (i == 0 ? color0 : color1) a[n++] = (VkClearAttachment){VK_IMAGE_ASPECT_COLOR_BIT, (uint32_t)i, c};
    if (S.t.depth && (depth || stencil)) {
      VkImageAspectFlags as = (depth ? VK_IMAGE_ASPECT_DEPTH_BIT : 0) |
                              (stencil && has_stencil(S.t.depth->format) ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
      if (as) {
        VkClearValue d;
        d.depthStencil = (VkClearDepthStencilValue){dv, sv};
        a[n++] = (VkClearAttachment){as, 0, d};
      }
    }
    if (!n) return;
    VkClearRect r = {{{0, 0}, target_extent(&S.t)}, 0, 1};
    if (tracing)
      nvk_log("  clear in pass after %llu draws: c0 %d c1 %d depth %d stencil %d", (unsigned long long)pass_draws, color0,
              color1, depth, stencil);
    vkCmdClearAttachments(vk_cmd(), n, a, 1, &r);
    return;
  }
  for (int i = 0; i < t.ncolor; i++)
    if (i == 0 ? color0 : color1) {
      t.color[i]->pending = VK_IMAGE_ASPECT_COLOR_BIT;
      t.color[i]->clear = c;
    }
  if (t.depth && (depth || stencil)) {
    VkImageAspectFlags as = (depth ? VK_IMAGE_ASPECT_DEPTH_BIT : 0) |
                            (stencil && has_stencil(t.depth->format) ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
    // a partial depth/stencil clear keeps the other aspect's pending clear (or loads it)
    t.depth->pending |= as;
    if (as & VK_IMAGE_ASPECT_DEPTH_BIT) t.depth->clear.depthStencil.depth = dv;
    if (as & VK_IMAGE_ASPECT_STENCIL_BIT) t.depth->clear.depthStencil.stencil = sv;
  }
}

// glClear on the bound target (kept game code: swapBuffers)
void nvk_clear(const float rgba[4], int color0, int color1, int depth, int stencil, float d, int s) {
  clear_target(S.fbo, rgba ? rgba : S.clear, color0 && S.mask[0], color1 && S.mask[1], depth, stencil, d,
               (uint32_t)s);
}

// ---------------------------------------------------------------- programs and pipeline state objects
typedef struct {
  uint32_t vs, fs;  // shader store names
  fnvk_spirv sp;
  VkShaderModule mod[2];
  VkDescriptorSetLayout dsl;
  VkPipelineLayout layout;
  int ok;
} program;
static program **progs;
static size_t nprogs;
// build cost (the 600-frame report): programs compiled (GLSL -> SPIR-V -> modules) and Vulkan pipelines made
static uint64_t n_prog_built, n_prog_cached, n_pipe_built;
static double prog_ms, pipe_ms;
static double ms_now(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e3 + t.tv_nsec * 1e-6;
}

static VkShaderModule shader_module(const uint32_t *code, size_t words) {
  VkShaderModuleCreateInfo ci = {VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO, NULL, 0, words * 4, code};
  VkShaderModule m = VK_NULL_HANDLE;
  VKCHECK(vkCreateShaderModule(vk.dev, &ci, NULL, &m));
  return m;
}

static VkDescriptorType res_type(int kind) {
  return kind == FNVK_RES_UBO       ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
         : kind == FNVK_RES_SAMPLER ? VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                                    : VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
}

// ---------------------------------------------------------------- SPIR-V cache on disk
// <FN_PROF_DIR>/nvk-spirv.bin: the compiled programs (GLSL rewrite + glslang, ~3 ms each on Adreno 830: ~0.2 s at
// start for ~70 programs, and a stall for each new one in play). Key: the two sources and the rewrite's build.
// The file starts with "NVKH" and the rewrite's build (64 bytes); another build's file is written anew. Records are
// appended: "NVKS", key, resource table, the two SPIR-V blobs.
typedef struct {
  uint64_t key;
  fnvk_spirv sp;
} spv_entry;
static spv_entry *spv;
static size_t nspv;
static char spv_path[4096];
static int spv_loaded, spv_fresh;  // fresh: the file is not this build's (the first store writes it anew)

static uint64_t fnv(uint64_t h, const char *s) {
  for (; *s; s++) h = (h ^ (uint8_t)*s) * 0x100000001b3ull;
  return (h ^ 0xff) * 0x100000001b3ull;  // the end of a string (keeps "ab"+"c" apart from "a"+"bc")
}

static void spv_load(void) {
  spv_loaded = 1;
  const char *dir = getenv("FN_PROF_DIR");
  if (!dir || !*dir) return;
  snprintf(spv_path, sizeof spv_path, "%s/nvk-spirv.bin", dir);
  spv_fresh = 1;
  FILE *f = fopen(spv_path, "rb");
  if (!f) return;
  char head[68] = {0}, id[64] = {0};
  snprintf(id, sizeof id, "%s", fnvk_rewrite_id());
  if (fread(head, 1, 68, f) != 68 || memcmp(head, "NVKH", 4) || memcmp(head + 4, id, 64)) {
    fclose(f);
    nvk_log("SPIR-V cache %s: another build, made anew", spv_path);
    return;
  }
  spv_fresh = 0;
  for (;;) {
    char magic[4];
    spv_entry e;
    memset(&e, 0, sizeof e);
    uint32_t nres, words[2];
    if (fread(magic, 1, 4, f) != 4 || memcmp(magic, "NVKS", 4) || fread(&e.key, 8, 1, f) != 1 ||
        fread(&nres, 4, 1, f) != 1 || nres > FNVK_MAX_RES ||
        fread(e.sp.res, sizeof *e.sp.res, nres, f) != nres || fread(words, 4, 2, f) != 2 ||
        words[0] > (1u << 22) || words[1] > (1u << 22))
      break;
    e.sp.nres = (int)nres;
    int ok = 1;
    for (int i = 0; i < 2; i++) {
      e.sp.words[i] = words[i];
      e.sp.code[i] = malloc((size_t)words[i] * 4);
      ok &= e.sp.code[i] && fread(e.sp.code[i], 4, words[i], f) == words[i];
    }
    if (!ok) {
      fnvk_free_spirv(&e.sp);
      break;  // a cut record (the app was closed while writing): the next compile appends a whole one
    }
    spv = realloc(spv, (nspv + 1) * sizeof *spv);
    spv[nspv++] = e;
  }
  fclose(f);
  nvk_log("SPIR-V cache %s: %zu programs", spv_path, nspv);
}

static int spv_find(uint64_t key, fnvk_spirv *out) {
  for (size_t i = 0; i < nspv; i++)
    if (spv[i].key == key) {
      memset(out, 0, sizeof *out);
      out->nres = spv[i].sp.nres;
      memcpy(out->res, spv[i].sp.res, sizeof out->res);
      for (int k = 0; k < 2; k++) {
        out->words[k] = spv[i].sp.words[k];
        out->code[k] = malloc(out->words[k] * 4);
        memcpy(out->code[k], spv[i].sp.code[k], out->words[k] * 4);
      }
      return 1;
    }
  return 0;
}

static void spv_store(uint64_t key, const fnvk_spirv *sp) {
  if (!spv_path[0]) return;
  FILE *f = fopen(spv_path, spv_fresh ? "wb" : "ab");
  if (!f) return;
  if (spv_fresh) {
    char id[64] = {0};
    snprintf(id, sizeof id, "%s", fnvk_rewrite_id());
    fwrite("NVKH", 1, 4, f);
    fwrite(id, 1, 64, f);
    spv_fresh = 0;
  }
  uint32_t nres = (uint32_t)sp->nres, words[2] = {(uint32_t)sp->words[0], (uint32_t)sp->words[1]};
  fwrite("NVKS", 1, 4, f);
  fwrite(&key, 8, 1, f);
  fwrite(&nres, 4, 1, f);
  fwrite(sp->res, sizeof *sp->res, nres, f);
  fwrite(words, 4, 2, f);
  fwrite(sp->code[0], 4, words[0], f);
  fwrite(sp->code[1], 4, words[1], f);
  fclose(f);
}

static program *program_get(uint32_t vs, uint32_t fs) {
  for (size_t i = 0; i < nprogs; i++)
    if (progs[i]->vs == vs && progs[i]->fs == fs) return progs[i];
  program *p = calloc(1, sizeof *p);
  p->vs = vs;
  p->fs = fs;
  progs = realloc(progs, (nprogs + 1) * sizeof *progs);
  progs[nprogs++] = p;
  const char *vsrc = nvk_shader_source(vs), *fsrc = nvk_shader_source(fs);
  // NVK_SHADER_DIR: <vs>.vert / <fs>.frag there replace the game's text (experiments)
  static char *over[2];
  const char *sdir = getenv("NVK_SHADER_DIR");
  for (int k = 0; sdir && k < 2; k++) {
    char path[512];
    snprintf(path, sizeof path, "%s/%u.%s", sdir, k ? fs : vs, k ? "frag" : "vert");
    FILE *f = fopen(path, "r");
    if (!f) continue;
    free(over[k]);
    over[k] = calloc(1, 1 << 20);
    size_t n = fread(over[k], 1, (1 << 20) - 1, f);
    over[k][n] = 0;
    fclose(f);
    if (k) fsrc = over[k];
    else vsrc = over[k];
    nvk_log("shader %s from %s", k ? "fragment" : "vertex", path);
  }
  const char *dump = getenv("NVK_DUMP_SHADERS");  // a folder: <vs>.vert / <fs>.frag as the game gave them
  if (dump && vsrc && fsrc) {
    char path[512];
    FILE *f;
    snprintf(path, sizeof path, "%s/%u.vert", dump, vs);
    if ((f = fopen(path, "w"))) fputs(vsrc, f), fclose(f);
    snprintf(path, sizeof path, "%s/%u.frag", dump, fs);
    if ((f = fopen(path, "w"))) fputs(fsrc, f), fclose(f);
  }
  if (!vsrc || !fsrc) {
    nvk_log("program %u/%u: no shader source", vs, fs);
    return p;
  }
  double t0 = ms_now();
  if (!spv_loaded) spv_load();
  uint64_t key = fnv(fnv(fnv(0xcbf29ce484222325ull, fnvk_rewrite_id()), vsrc), fsrc);
  int cached = !sdir && spv_find(key, &p->sp);
  int compiled = cached || fnvk_compile_program(vsrc, fsrc, &p->sp);
  if (compiled && !cached && !sdir) spv_store(key, &p->sp);
  prog_ms += ms_now() - t0;
  n_prog_built++;
  n_prog_cached += cached;
  if (!compiled) {
    nvk_log("program %u/%u does not compile:\n%s", vs, fs, p->sp.log);
    return p;
  }
  p->mod[0] = shader_module(p->sp.code[0], p->sp.words[0]);
  p->mod[1] = shader_module(p->sp.code[1], p->sp.words[1]);
  VkDescriptorSetLayoutBinding b[FNVK_MAX_RES];
  for (int i = 0; i < p->sp.nres; i++) {
    b[i] = (VkDescriptorSetLayoutBinding){(uint32_t)i, res_type(p->sp.res[i].kind), 1, 0, NULL};
    b[i].stageFlags = (p->sp.res[i].stages & 1 ? VK_SHADER_STAGE_VERTEX_BIT : 0) |
                      (p->sp.res[i].stages & 2 ? VK_SHADER_STAGE_FRAGMENT_BIT : 0);
  }
  VkDescriptorSetLayoutCreateInfo dci = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO, NULL,
                                         vk.push_descriptors ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0,
                                         (uint32_t)p->sp.nres, b};
  VKCHECK(vkCreateDescriptorSetLayout(vk.dev, &dci, NULL, &p->dsl));
  VkPipelineLayoutCreateInfo lci = {VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO, NULL, 0, 1, &p->dsl, 0, NULL};
  VKCHECK(vkCreatePipelineLayout(vk.dev, &lci, NULL, &p->layout));
  p->ok = 1;
  return p;
}

typedef struct {
  uint64_t key[3];
  VkPipeline pipe;
} pipe_entry;

typedef struct nvk_pso {
  program *prog;
  int nbind;
  int slot[MAXB];   // game binding -> descriptor binding, -1: the shader does not use it
  uint32_t nattr, stride;
  VkVertexInputAttributeDescription attr[16];
  int instanced;
  VkPrimitiveTopology topo;
  VkPipelineColorBlendAttachmentState blend;
  int color1_write;
  int scissor;
  pipe_entry *pipes;
  size_t npipes;
} nvk_pso;

static const VkBlendOp blend_ops[5] = {VK_BLEND_OP_ADD, VK_BLEND_OP_SUBTRACT, VK_BLEND_OP_REVERSE_SUBTRACT,
                                       VK_BLEND_OP_MIN, VK_BLEND_OP_MAX};
static const VkBlendFactor blend_args[11] = {
    VK_BLEND_FACTOR_ZERO, VK_BLEND_FACTOR_ONE, VK_BLEND_FACTOR_SRC_COLOR, VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR,
    VK_BLEND_FACTOR_SRC_ALPHA, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, VK_BLEND_FACTOR_DST_ALPHA,
    VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA, VK_BLEND_FACTOR_DST_COLOR, VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR,
    VK_BLEND_FACTOR_CONSTANT_COLOR};
// VertexLayoutSpecification::ElementType: float, vec2, vec3, vec4, ushort2 (int), uint, uvec2, uvec3, uvec4,
// ubyte4 normalized
static const VkFormat attr_formats[10] = {
    VK_FORMAT_R32_SFLOAT,      VK_FORMAT_R32G32_SFLOAT,    VK_FORMAT_R32G32B32_SFLOAT, VK_FORMAT_R32G32B32A32_SFLOAT,
    VK_FORMAT_R16G16_UINT,     VK_FORMAT_R32_UINT,         VK_FORMAT_R32G32_UINT,      VK_FORMAT_R32G32B32_UINT,
    VK_FORMAT_R32G32B32A32_UINT, VK_FORMAT_R8G8B8A8_UNORM};
static const uint8_t attr_bytes[10] = {4, 8, 12, 16, 4, 4, 8, 12, 16, 4};
static const VkPrimitiveTopology topologies[5] = {VK_PRIMITIVE_TOPOLOGY_POINT_LIST, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,
                                                  VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, VK_PRIMITIVE_TOPOLOGY_LINE_LIST,
                                                  VK_PRIMITIVE_TOPOLOGY_LINE_STRIP};
static const uint32_t gl_topologies[5] = {0, 4, 5, 1, 3};
static const uint32_t gl_blend_ops[5] = {0x8006, 0x800a, 0x800b, 0x8007, 0x8008};
static const uint32_t gl_blend_args[11] = {0, 1, 0x300, 0x301, 0x302, 0x303, 0x304, 0x305, 0x306, 0x307, 0x8001};

typedef struct {
  void *b, *e, *c;
} vec;

static void *(*fetch_shader)(void *gi, const void *name, uint32_t type, int flag);

// createPipelineStateObject(spec) -> unique_ptr<PipelineStateObject>: the game's object (bindBindings reads its
// binding list) with our pipeline data behind PSO_NVK
void nvk_t_createPipelineStateObject_c(void *gi, const uint8_t *spec, void **ret) {
  static void *vt;
  if (!vt) vt = nvk_vtable("_ZTV25PipelineStateObjectOpenGL");
  uint8_t flags = spec[PSS_FLAGS];
  const uint8_t *vsh = fetch_shader(gi, spec + PSS_VS_NAME, 0x8b31, !(flags & 1));
  const uint8_t *fsh = fetch_shader(gi, spec + PSS_FS_NAME, 0x8b30, !(flags & 1));
  nvk_pso *p = calloc(1, sizeof *p);
  p->prog = program_get(vsh ? F32(vsh, SHADER_ID) : 0, fsh ? F32(fsh, SHADER_ID) : 0);
  const vec *bind = (const vec *)(spec + PSS_BINDINGS);
  size_t nb = ((char *)bind->e - (char *)bind->b) / 16;
  p->nbind = nb > MAXB ? MAXB : (int)nb;
  for (int i = 0; i < p->nbind; i++) {
    const char *name = FP(bind->b, i * 16 + 8);
    p->slot[i] = -1;
    if (p->prog->ok && name)
      for (int r = 0; r < p->prog->sp.nres; r++)
        if (!strcmp(p->prog->sp.res[r].name, name)) p->slot[i] = r;
  }
  if (nvk_trace_on()) {
    char line[1024];
    int k = snprintf(line, sizeof line, "pso prog %u/%u bindings:", p->prog->vs, p->prog->fs);
    for (int i = 0; i < p->nbind && k < 960; i++) {
      const char *name = FP(bind->b, i * 16 + 8);
      k += snprintf(line + k, sizeof line - k, " %d:%s->%d", i, name ? name : "?", p->slot[i]);
    }
    nvk_log("%s", line);
  }
  const vec *layout = FP(spec, PSS_LAYOUT);
  uint32_t off = 0;
  if (layout) {
    size_t ne = ((char *)layout->e - (char *)layout->b) / 8;
    for (size_t i = 0; i < ne && i < 16; i++) {
      uint32_t ty = F32(layout->b, i * 8);
      if (ty >= 10) ty = 0;
      p->attr[p->nattr++] = (VkVertexInputAttributeDescription){(uint32_t)i, 0, attr_formats[ty], off};
      off += attr_bytes[ty];
    }
  }
  p->stride = off;
  p->instanced = (flags >> 2) & 1;
  uint8_t topo = spec[PSS_TOPOLOGY] < 5 ? spec[PSS_TOPOLOGY] : 1;
  p->topo = topologies[topo];
  const uint8_t *bl = spec + PSS_BLEND;
  p->blend.blendEnable = spec[PSS_BLEND_ON] != 0;
  p->blend.colorBlendOp = blend_ops[bl[0] % 5];
  p->blend.alphaBlendOp = blend_ops[bl[1] % 5];
  p->blend.srcColorBlendFactor = blend_args[bl[2] % 11];
  p->blend.srcAlphaBlendFactor = blend_args[bl[3] % 11];
  p->blend.dstColorBlendFactor = blend_args[bl[4] % 11];
  p->blend.dstAlphaBlendFactor = blend_args[bl[5] % 11];
  p->blend.colorWriteMask = 0xf;
  p->color1_write = spec[PSS_COLOR_WRITE] == 1;
  p->scissor = (flags >> 1) & 1;

  // the game's object, filled as the original fills it (fields other code might read)
  uint8_t *o = nvk_game_new(PSO_SIZE);
  memset(o, 0, PSO_SIZE);
  FP(o, 0) = vt;
  vec *ob = (vec *)(o + PSO_BINDINGS);
  if (nb) {
    ob->b = nvk_game_new(nb * 16);
    memcpy(ob->b, bind->b, nb * 16);
    ob->e = ob->c = (char *)ob->b + nb * 16;
  }
  F32(o, PSO_PROGRAM) = p->prog->ok ? 1 : 0;
  F8(o, PSO_PROGRAM + 4) = (uint8_t)p->prog->ok;
  FP(o, PSO_NVK) = p;
  FP(o, PSO_LAYOUT) = (void *)layout;
  F8(o, PSO_INSTANCED) = (uint8_t)p->instanced;
  for (int i = 0; i < 6; i++) F32(o, PSO_BLEND + 4 * i) = i < 2 ? gl_blend_ops[bl[i] % 5] : gl_blend_args[bl[i] % 11];
  F8(o, PSO_BLEND_ON) = spec[PSS_BLEND_ON];
  F8(o, PSO_COLOR_WRITE) = spec[PSS_COLOR_WRITE];
  F32(o, PSO_TOPOLOGY_GL) = gl_topologies[topo];
  F8(o, PSO_TOPOLOGY) = topo;
  F8(o, PSO_SCISSOR) = (uint8_t)p->scissor;
  *ret = o;
}

// ---------------------------------------------------------------- bound state
static struct {
  void *pso;             // PipelineStateObjectOpenGL*
  nvk_pso *p;
  const uint8_t *ds;     // DepthStencilState*
  uint32_t ref;
  // per game slot
  uint32_t ubo[MAXB];    // UniformBuffer name
  uint32_t tex[MAXB];    // nvk_tex name
  uint32_t samp[MAXB];   // sampler name
  uint32_t texel[MAXB];  // VideoBuffer name (texel buffer)
} B;

static void v_bindPipelineState(void *gi, void *pso) {
  if (FP(gi, GI_CUR_PSO) == pso) return;
  FP(gi, GI_CUR_PSO) = pso;
  B.pso = pso;
  B.p = pso ? FP(pso, PSO_NVK) : NULL;
  if (B.p) nvk_scissor_enable(B.p->scissor);
}

static void v_bindDepthStencilState(void *gi, const uint8_t *ds, uint8_t ref) {
  (void)gi;
  B.ds = ds;
  if (B.ref != ref) S.dyn_dirty = 1;
  B.ref = ref;
}

static void v_bindUniformBuffer(void *gi, uint32_t slot, const void *pso, const void *ubo) {
  (void)gi, (void)pso;
  if (slot < MAXB) B.ubo[slot] = ubo ? F32(ubo, UB_NAME) : 0;
}

static void v_bindTexture(void *gi, uint32_t slot, const void *pso, const void *bm, const void *sampler) {
  (void)gi, (void)pso;
  if (slot >= MAXB) return;
  uint32_t name = 0;
  if (bm) {
    if (F32(bm, BM_KIND) == 2) bm = FP(bm, BM_NAME);
    name = bm ? F32(bm, BM_NAME) : 0;
  }
  B.tex[slot] = name;
  B.samp[slot] = sampler ? F32(sampler, SS_NAME) : 0;
}

static void v_bindTextureArray(void *gi, uint32_t slot, const void *pso, const void *a, const void *sampler) {
  (void)gi, (void)pso;
  if (slot >= MAXB) return;
  B.tex[slot] = a ? F32(a, BA_NAME) : 0;
  B.samp[slot] = sampler ? F32(sampler, SS_NAME) : 0;
}

static void v_bindTexture3D(void *gi, uint32_t slot, const void *pso, const void *b, const void *sampler) {
  (void)gi, (void)pso;
  if (slot >= MAXB) return;
  B.tex[slot] = b ? F32(b, B3_NAME) : 0;
  B.samp[slot] = sampler ? F32(sampler, SS_NAME) : 0;
}

static void v_bindBuffer(void *gi, uint32_t slot, const void *pso, const void *vb) {
  (void)gi, (void)pso;
  if (slot < MAXB) B.texel[slot] = vb ? F32(vb, VB_NAME) : 0;
}

// GraphicsInterfaceOpenGL::resetPipelineState(): nothing bound (the game's bindBindings state is reset by the
// original, which we keep for that part)
static void (*orig_resetPipelineState)(void *);
static void v_resetPipelineState(void *gi) {
  if (orig_resetPipelineState) orig_resetPipelineState(gi);  // glBindVertexArray/glColorMaski: ours (nvk_gl.c)
  FP(gi, GI_CUR_PSO) = NULL;
  B.pso = NULL;
  B.p = NULL;
}

// ---------------------------------------------------------------- pipelines
static VkPipelineCache pcache;
static int independent_blend;
static const VkCompareOp cmp_ops[8] = {VK_COMPARE_OP_NEVER,   VK_COMPARE_OP_LESS,      VK_COMPARE_OP_LESS_OR_EQUAL,
                                       VK_COMPARE_OP_GREATER, VK_COMPARE_OP_GREATER_OR_EQUAL, VK_COMPARE_OP_EQUAL,
                                       VK_COMPARE_OP_NOT_EQUAL, VK_COMPARE_OP_ALWAYS};
static const VkStencilOp st_ops[8] = {VK_STENCIL_OP_KEEP, VK_STENCIL_OP_ZERO, VK_STENCIL_OP_REPLACE,
                                      VK_STENCIL_OP_INCREMENT_AND_CLAMP, VK_STENCIL_OP_DECREMENT_AND_CLAMP,
                                      VK_STENCIL_OP_INCREMENT_AND_WRAP, VK_STENCIL_OP_DECREMENT_AND_WRAP,
                                      VK_STENCIL_OP_INVERT};

static VkPipeline pipeline(nvk_pso *p) {
  const uint8_t *ds = S.t.depth ? B.ds : NULL;
  int depth_on = ds && F8(ds, DS_DEPTH), stencil_on = ds && F8(ds, DS_STENCIL) && has_stencil(S.t.depth->format);
  uint64_t key[3];
  key[0] = (uint64_t)(uintptr_t)S.compat;
  key[1] = (uint64_t)depth_on | (uint64_t)stencil_on << 1 | (uint64_t)S.mask[0] << 2 | (uint64_t)S.mask[1] << 3;
  if (stencil_on)
    key[1] |= (uint64_t)F8(ds, DS_READ_MASK) << 8 | (uint64_t)F8(ds, DS_WRITE_MASK) << 16 |
              (uint64_t)(F32(ds, DS_FUNC) & 7) << 24 | (uint64_t)(F32(ds, DS_SFAIL) & 7) << 28 |
              (uint64_t)(F32(ds, DS_DPFAIL) & 7) << 32 | (uint64_t)(F32(ds, DS_DPPASS) & 7) << 36;
  // window drawing (vk_shader.cpp window_prelude): mode and the window size as specialization constants
  struct {
    int32_t mode;
    float w, h;
  } spec = {S.t.flip ? 1 + S.t.rot : 0, S.t.flip ? (float)S.t.w : 0.0f, S.t.flip ? (float)S.t.h : 0.0f};
  key[2] = (uint64_t)spec.mode | (uint64_t)(uint32_t)spec.w << 8 | (uint64_t)(uint32_t)spec.h << 36;
  for (size_t i = 0; i < p->npipes; i++)
    if (!memcmp(p->pipes[i].key, key, sizeof key)) return p->pipes[i].pipe;
  static const VkSpecializationMapEntry spec_map[3] = {{100, 0, 4}, {101, 4, 4}, {102, 8, 4}};
  VkSpecializationInfo si = {3, spec_map, sizeof spec, &spec};
  VkPipelineShaderStageCreateInfo st[2] = {
      {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_VERTEX_BIT, p->prog->mod[0],
       "main", spec.mode ? &si : NULL},
      {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, NULL, 0, VK_SHADER_STAGE_FRAGMENT_BIT, p->prog->mod[1],
       "main", spec.mode ? &si : NULL}};
  VkVertexInputBindingDescription vb = {0, p->stride,
                                        p->instanced ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX};
  VkPipelineVertexInputStateCreateInfo vi = {VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
  if (p->nattr) {
    vi.vertexBindingDescriptionCount = 1;
    vi.pVertexBindingDescriptions = &vb;
    vi.vertexAttributeDescriptionCount = p->nattr;
    vi.pVertexAttributeDescriptions = p->attr;
  }
  VkPipelineInputAssemblyStateCreateInfo ia = {VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO, NULL, 0,
                                               p->topo, VK_FALSE};
  VkPipelineViewportStateCreateInfo vp = {VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO, NULL, 0, 1, NULL, 1,
                                          NULL};
  VkPipelineRasterizationStateCreateInfo rs = {VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
  rs.polygonMode = VK_POLYGON_MODE_FILL;
  rs.cullMode = VK_CULL_MODE_NONE;
  rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
  rs.lineWidth = 1.0f;
  VkPipelineMultisampleStateCreateInfo ms = {VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
  ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
  VkPipelineDepthStencilStateCreateInfo dss = {VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
  dss.depthTestEnable = depth_on;
  dss.depthWriteEnable = depth_on;  // GL: depth mask on (the game never changes it), writes only with the test
  dss.depthCompareOp = VK_COMPARE_OP_LESS;
  dss.stencilTestEnable = stencil_on;
  if (stencil_on) {
    VkStencilOpState s = {st_ops[F32(ds, DS_SFAIL) & 7], st_ops[F32(ds, DS_DPPASS) & 7], st_ops[F32(ds, DS_DPFAIL) & 7],
                          cmp_ops[F32(ds, DS_FUNC) & 7], F8(ds, DS_READ_MASK), F8(ds, DS_WRITE_MASK), 0};
    dss.front = dss.back = s;
  }
  VkPipelineColorBlendAttachmentState cba[2] = {p->blend, p->blend};
  cba[0].colorWriteMask = S.mask[0] ? 0xf : 0;
  cba[1].colorWriteMask = S.mask[1] && p->color1_write ? 0xf : 0;
  if (!independent_blend) cba[1] = cba[0];  // the same state for both attachments then (rarely differs)
  // integer targets (the game's GPU block compression draws into RGBA32UI with blending on): GL ignores blending
  // there, Vulkan forbids it (FNative Lab, 2026-11-20)
  for (int i = 0; i < S.t.ncolor && i < 2; i++)
    if (integer_format(S.t.color[i]->format)) cba[i].blendEnable = VK_FALSE;
  if (!independent_blend && S.t.ncolor == 2 && cba[0].blendEnable != cba[1].blendEnable)
    cba[0].blendEnable = cba[1].blendEnable = VK_FALSE;
  VkPipelineColorBlendStateCreateInfo cb = {VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
  cb.attachmentCount = S.t.ncolor;
  cb.pAttachments = cba;
  VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_STENCIL_REFERENCE};
  VkPipelineDynamicStateCreateInfo dy = {VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO, NULL, 0, 3, dyn};
  VkGraphicsPipelineCreateInfo ci = {VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
  ci.stageCount = 2;
  ci.pStages = st;
  ci.pVertexInputState = &vi;
  ci.pInputAssemblyState = &ia;
  ci.pViewportState = &vp;
  ci.pRasterizationState = &rs;
  ci.pMultisampleState = &ms;
  ci.pDepthStencilState = &dss;
  ci.pColorBlendState = &cb;
  ci.pDynamicState = &dy;
  ci.layout = p->prog->layout;
  ci.renderPass = S.compat;
  VkPipeline pipe = VK_NULL_HANDLE;
  double t0 = ms_now();
  VkResult r = vkCreateGraphicsPipelines(vk.dev, pcache, 1, &ci, NULL, &pipe);
  pipe_ms += ms_now() - t0;
  n_pipe_built++;
  if (r) nvk_log("vkCreateGraphicsPipelines: %d", r);
  p->pipes = realloc(p->pipes, (p->npipes + 1) * sizeof *p->pipes);
  p->pipes[p->npipes++] = (pipe_entry){{key[0], key[1], key[2]}, pipe};
  return pipe;
}

// ---------------------------------------------------------------- descriptors
static vk_image *dummy[3];  // 2D, 2D array, 3D: unbound textures read zeros (as GL's incomplete textures)
static VkSampler dummy_sampler;
static vk_slice dummy_ubo;

static void dummies_make(void) {  // outside any pass: at install
  static const VkImageViewType vt[3] = {VK_IMAGE_VIEW_TYPE_2D, VK_IMAGE_VIEW_TYPE_2D_ARRAY, VK_IMAGE_VIEW_TYPE_3D};
  static const uint8_t zero[4];
  for (int v = 0; v < 3; v++) {
    dummy[v] = vk_image_create(VK_FORMAT_R8G8B8A8_UNORM, vt[v], 1, 1, 1, 1, 1);
    vk_upload(dummy[v], 0, 0, 0, 0, 1, 1, 1, zero, 4, 1, 1);
    vk_image_layout(dummy[v], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    dummy[v]->dirty = 0;
  }
  vk_flush_dirty(NULL, 0);
}

static int integer_format(VkFormat f) {
  return f == VK_FORMAT_R16G16B16A16_UINT || f == VK_FORMAT_R32G32B32A32_UINT || f == VK_FORMAT_R32G32_UINT ||
         f == VK_FORMAT_R16_UINT || f == VK_FORMAT_R8_UINT || f == VK_FORMAT_R32_UINT;
}

static VkSampler nearest_sampler(void) {  // integer images: GL ignores linear filtering there, Vulkan forbids it
  static VkSampler s;
  if (!s) {
    VkSamplerCreateInfo ci = {VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    ci.addressModeU = ci.addressModeV = ci.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    ci.maxLod = 1000;
    VKCHECK(vkCreateSampler(vk.dev, &ci, NULL, &s));
  }
  return s;
}

// The images the pipeline samples must be in SHADER_READ_ONLY before the pass; returns 1 when one was not (the
// pass is then ended, the image moved, and the draw starts a new pass).
static int prepare_images(nvk_pso *p) {
  int moved = 0;
  for (int i = 0; i < p->nbind; i++) {
    int r = p->slot[i];
    if (r < 0 || p->prog->sp.res[r].kind != FNVK_RES_SAMPLER) continue;
    nvk_tex *t = nvk_tex_get(B.tex[i]);
    vk_image *img = t ? t->img : NULL;
    if (!img) continue;
    if (img == S.t.color[0] || img == S.t.color[1] || img == S.t.depth) continue;  // feedback: as GL, undefined
    if (img->layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL && !img->pending && !img->dirty) continue;
    if (S.open) {
      end_reason = END_IMAGES;
      nvk_end_pass();
      moved = 1;
    }
    vk_materialize(img);
    if (img->layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
      vk_image_layout(img, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    img->dirty = 0;
  }
  return moved;
}

static struct {
  VkDescriptorBufferInfo buf[MAXB];
  VkDescriptorImageInfo img[MAXB];
  VkBufferView view[MAXB];
} D, Dlast;
static int dlast_valid;
static VkPipelineLayout dlast_layout;

static void write_descriptors(nvk_pso *p) {
  program *g = p->prog;
  int nres = g->sp.nres;
  memset(&D, 0, sizeof D);
  // defaults for resources the game did not bind
  for (int r = 0; r < nres; r++) {
    switch (g->sp.res[r].kind) {
      case FNVK_RES_UBO:
        if (!dummy_ubo.ptr) {
          dummy_ubo = vk_alloc(4096);
          memset(dummy_ubo.ptr, 0, 4096);
        }
        D.buf[r] = (VkDescriptorBufferInfo){dummy_ubo.buf, dummy_ubo.off, 4096};
        break;
      case FNVK_RES_SAMPLER: {
        vk_image *d = dummy[g->sp.res[r].view];  // made at install, already SHADER_READ_ONLY
        D.img[r] = (VkDescriptorImageInfo){dummy_sampler, d->view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        break;
      }
      default: break;
    }
  }
  for (int i = 0; i < p->nbind; i++) {
    int r = p->slot[i];
    if (r < 0) continue;
    switch (g->sp.res[r].kind) {
      case FNVK_RES_UBO: {
        nvk_buf *b = nvk_buf_get(B.ubo[i]);
        if (b && b->cur.buf) D.buf[r] = (VkDescriptorBufferInfo){b->cur.buf, b->cur.off, b->size ? b->size : 256};
        break;
      }
      case FNVK_RES_SAMPLER: {
        nvk_tex *t = nvk_tex_get(B.tex[i]);
        if (t && t->img) {
          VkSampler s = integer_format(t->img->format) ? nearest_sampler() : nvk_sampler_get(B.samp[i]);
          if (!s) s = nearest_sampler();
          D.img[r] = (VkDescriptorImageInfo){s, t->img->view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        }
        break;
      }
      case FNVK_RES_TEXEL_BUFFER: {
        nvk_buf *b = nvk_buf_get(B.texel[i]);
        D.view[r] = nvk_buf_view(b);
        break;
      }
    }
  }
  for (int r = 0; r < nres; r++)
    if (g->sp.res[r].kind == FNVK_RES_TEXEL_BUFFER && !D.view[r]) {
      static nvk_buf zero;
      if (!zero.cur.buf) {
        zero.cur = vk_alloc(256);
        memset(zero.cur.ptr, 0, 256);
        zero.size = 256;
        zero.texel = VK_FORMAT_R32_UINT;
      }
      D.view[r] = nvk_buf_view(&zero);
    }
  if (dlast_valid && dlast_layout == g->layout && !memcmp(&D, &Dlast, sizeof D)) return;
  // the same layout as the last write: only the changed bindings (push descriptors keep the others; mostly one
  // texture changes between draws: 144 of 222 writes per frame, _autosave11 in proot, 2026-11-25)
  int partial = vk.push_descriptors && dlast_valid && dlast_layout == g->layout;
  VkWriteDescriptorSet w[FNVK_MAX_RES];
  uint32_t n = 0;
  for (int r = 0; r < nres; r++) {
    if (partial && !memcmp(&D.buf[r], &Dlast.buf[r], sizeof D.buf[r]) &&
        !memcmp(&D.img[r], &Dlast.img[r], sizeof D.img[r]) && D.view[r] == Dlast.view[r])
      continue;
    w[n] = (VkWriteDescriptorSet){VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w[n].dstBinding = (uint32_t)r;
    w[n].descriptorCount = 1;
    w[n].descriptorType = res_type(g->sp.res[r].kind);
    w[n].pBufferInfo = &D.buf[r];
    w[n].pImageInfo = &D.img[r];
    w[n].pTexelBufferView = &D.view[r];
    n++;
  }
  VkCommandBuffer c = vk_cmd();
  if (vk.push_descriptors) {
    vk.push_descriptors(c, VK_PIPELINE_BIND_POINT_GRAPHICS, g->layout, 0, n, w);
  } else {
    VkDescriptorSet set = vk_alloc_set(g->dsl);
    for (uint32_t r = 0; r < n; r++) w[r].dstSet = set;
    vkUpdateDescriptorSets(vk.dev, n, w, 0, NULL);
    vkCmdBindDescriptorSets(c, VK_PIPELINE_BIND_POINT_GRAPHICS, g->layout, 0, 1, &set, 0, NULL);
  }
  Dlast = D;
  dlast_valid = 1;
  dlast_layout = g->layout;
}

// ---------------------------------------------------------------- one-frame trace (NVK_TRACE_AT=<seconds>)
static void trace_draw(const char *kind, uint32_t count) {
  if (!tracing) return;
  nvk_pso *p = B.p;
  char line[1024];
  int k = snprintf(line, sizeof line, "%s n%u prog %u/%u fbo %u blend %d(%d,%d,%d,%d) mask %d%d scissor %d [%d,%d %dx%d]",
                   kind, count, p ? p->prog->vs : 0, p ? p->prog->fs : 0, S.fbo, p ? p->blend.blendEnable : -1,
                   p ? p->blend.srcColorBlendFactor : 0, p ? p->blend.dstColorBlendFactor : 0,
                   p ? p->blend.srcAlphaBlendFactor : 0, p ? p->blend.dstAlphaBlendFactor : 0, S.mask[0], S.mask[1],
                   S.scissor_on, S.sx, S.sy, S.sw, S.sh);
  for (int i = 0; i < MAXB && k < (int)sizeof line - 80; i++) {
    if (!B.tex[i] || !p || i >= p->nbind || p->slot[i] < 0) continue;  // only what the shader reads
    nvk_tex *t = nvk_tex_get(B.tex[i]);
    if (t && t->img)
      k += snprintf(line + k, sizeof line - k, " t%d=%u:f%d %ux%u L%u", i, B.tex[i], t->fmt, t->img->w, t->img->h,
                    t->img->levels);
    else
      k += snprintf(line + k, sizeof line - k, " t%d=%u:none", i, B.tex[i]);
  }
  nvk_log("draw %s", line);
}

// ---------------------------------------------------------------- draws
static void (*bind_bindings)(void *gi, const void *bindings);
static uint64_t draws, pipelines_made_log;

static void set_dynamic(void) {
  VkCommandBuffer c = vk_cmd();
  int vx = S.vx, vy = S.vy, vw = S.vw, vh = S.vh;
  if (!vw || !vh) vx = vy = 0, vw = (int)S.t.w, vh = (int)S.t.h;
  VkViewport v = {(float)vx, (float)vy, (float)vw, (float)vh, 0.0f, 1.0f};
  if (S.t.flip && !S.t.rot) {  // swapchain image: rows top-down, the viewport mirrors y
    v.y = (float)S.t.h - (float)vy;
    v.height = -(float)vh;
  } else if (S.t.flip) {  // turned too: the turned rectangle, the vertex shader turns the position
    target_rect(&S.t, &vx, &vy, &vw, &vh);
    v = (VkViewport){(float)vx, (float)vy, (float)vw, (float)vh, 0.0f, 1.0f};
  }
  vkCmdSetViewport(c, 0, 1, &v);
  VkRect2D sc = {{0, 0}, target_extent(&S.t)};
  if (S.scissor_on) {
    int x = S.sx, y = S.sy, w = S.sw, h = S.sh;
    target_rect(&S.t, &x, &y, &w, &h);
    if (x < 0) w += x, x = 0;
    if (y < 0) h += y, y = 0;
    if (w < 0) w = 0;
    if (h < 0) h = 0;
    sc = (VkRect2D){{x, y}, {(uint32_t)w, (uint32_t)h}};
  }
  vkCmdSetScissor(c, 0, 1, &sc);
  vkCmdSetStencilReference(c, VK_STENCIL_FACE_FRONT_AND_BACK, B.ref);
  S.dyn_dirty = 0;
}

// Skipped draws by reason (FN_LOG build: logged every 600 frames): 0 no pipeline state, 1 its program failed,
// 2 no pass, 3 no Vulkan pipeline, 4 no vertex buffer, 5 no index buffer
static uint64_t skipped[6];
#ifdef FN_LOG
static const char *const skip_names[6] = {"no PSO", "program failed", "no pass", "no pipeline", "no vertex buffer",
                                          "no index buffer"};
#endif

// Everything a draw needs; 0 = skip it.
static int draw_setup(void) {
  nvk_pso *p = B.p;
  if (!p) return skipped[0]++, 0;
  if (!p->prog->ok) return skipped[1]++, 0;
  if (S.epoch != vk_cmd_epoch) {  // a new command buffer: nothing is bound in it
    S.epoch = vk_cmd_epoch;
    S.pipe = VK_NULL_HANDLE;
    dlast_valid = 0;
    S.dyn_dirty = 1;
  }
  prepare_images(p);
  ensure_pass();
  if (!S.open) return skipped[2]++, 0;
  if (S.epoch != vk_cmd_epoch) {  // ending/starting passes can start a new command buffer
    S.epoch = vk_cmd_epoch;
    dlast_valid = 0;
  }
  VkPipeline pipe = pipeline(p);
  if (!pipe) return skipped[3]++, 0;
  VkCommandBuffer c = vk_cmd();
  if (pipe != S.pipe) {
    vkCmdBindPipeline(c, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
    S.pipe = pipe;
  }
  if (S.dyn_dirty) set_dynamic();
  write_descriptors(p);
  return 1;
}

static int bind_vertex_buffer(nvk_pso *p, void *vbo) {
  if (!p->nattr) return 1;
  nvk_buf *b = vbo ? nvk_buf_get(F32(vbo, VB_NAME)) : NULL;
  if (!b || !b->cur.buf) return skipped[4]++, 0;
  VkDeviceSize off = b->cur.off;
  vkCmdBindVertexBuffers(vk_cmd(), 0, 1, &b->cur.buf, &off);
  return 1;
}

static void count_draw(void *gi) {
  F64(gi, GI_DRAWS)++;
  draws++;
  pass_draws++;
}

static void v_draw(void *gi, const void *bindings, void *vbo, uint32_t count, uint32_t first) {
  bind_bindings(gi, bindings);
  trace_draw("draw", count);
  if (!draw_setup() || !bind_vertex_buffer(B.p, vbo)) return;
  vkCmdDraw(vk_cmd(), count, 1, first, 0);
  count_draw(gi);
}

static void v_drawIndexed(void *gi, const void *bindings, void *vbo, void *ibo, uint32_t count, uint32_t first) {
  bind_bindings(gi, bindings);
  trace_draw("indexed", count);
  if (!draw_setup() || !bind_vertex_buffer(B.p, vbo)) return;
  nvk_buf *ib = ibo ? nvk_buf_get(F32(ibo, VB_NAME)) : NULL;
  if (!ib || !ib->cur.buf) return (void)skipped[5]++;
  vkCmdBindIndexBuffer(vk_cmd(), ib->cur.buf, ib->cur.off, VK_INDEX_TYPE_UINT32);
  vkCmdDrawIndexed(vk_cmd(), count, 1, first, 0, 0);
  count_draw(gi);
}

static void v_drawInstanced(void *gi, const void *bindings, void *ibo, void *vbo, uint32_t count, uint32_t first,
                            uint32_t instances, uint32_t base) {
  bind_bindings(gi, bindings);
  trace_draw("instanced", count * 1000 + instances);
  if (ibo) {
    static int told;
    if (!told++) nvk_log("indexed instanced draw: not supported by the game's GL path either");
    return;
  }
  if (!draw_setup() || !bind_vertex_buffer(B.p, vbo)) return;
  vkCmdDraw(vk_cmd(), count, instances, first, base);
  count_draw(gi);
}

// ---------------------------------------------------------------- framebuffer operations
// FramebufferOpenGL::clear(Color, caps): bit0 color 0, bit1 color 1, bit2 depth, bit3 stencil; the scissor does
// not apply (the original turns it off)
static void v_fbClear(void *fb, float r, float g, float b, float a, uint32_t caps) {
  if (!(caps & 0xff)) return;
  float c[4] = {r, g, b, a};
  clear_target(F32(fb, FB_NAME), c, caps & 1, (caps >> 1) & 1, (caps >> 2) & 1, (caps >> 3) & 1, 1.0f, 0);
}

static void copy_image(vk_image *src, int sx, int sy, uint32_t sz, vk_image *dst, int dx, int dy, int dz, uint32_t w,
                       uint32_t h, int blit_filter) {
  if (!src || !dst || !w || !h) return;
  end_reason = END_COPY;
  nvk_end_pass();
  vk_materialize(src);
  vk_materialize(dst);
  vk_image_layout(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
  vk_image_layout(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  uint32_t bw;
  if (src->format == dst->format && !blit_filter) {
    VkImageCopy r = {{src->aspect, 0, 0, 1}, {sx, sy, (int32_t)sz}, {dst->aspect, 0, 0, 1}, {dx, dy, dz}, {w, h, 1}};
    if (dst->view_type == VK_IMAGE_VIEW_TYPE_2D_ARRAY) r.dstSubresource.baseArrayLayer = (uint32_t)dz, r.dstOffset.z = 0;
    vkCmdCopyImage(vk_cmd(), src->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst->image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
  } else {
    (void)bw;
    VkImageBlit r = {{src->aspect, 0, 0, 1}, {{sx, sy, (int32_t)sz}, {sx + (int)w, sy + (int)h, (int32_t)sz + 1}},
                     {dst->aspect, 0, 0, 1}, {{dx, dy, dz}, {dx + (int)w, dy + (int)h, dz + 1}}};
    vkCmdBlitImage(vk_cmd(), src->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst->image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r, VK_FILTER_NEAREST);
  }
  vk_mark_dirty(src);
  vk_mark_dirty(dst);
}

// glCopyImageSubData (kept game code: the game's GPU mipmaps and block compression render into a temporary texture
// and copy it into a level of the atlas). Names are nvk textures. Uncompressed into compressed (size-compatible: an
// RGBA32UI texel is one BC3 block): extent in source texels, as GL. Not done without BC in Vulkan (Mali): the GPU's
// BC blocks cannot go into a texture held as ETC2 (the GLES path skips the same copy, fngl_tex.c).
static int block_compressed(VkFormat f) { uint32_t bw; vk_format_block(f, &bw); return bw > 1; }

void nvk_copy_image_sub(uint32_t sname, int slevel, int sx, int sy, int sz, uint32_t dname, int dlevel, int dx, int dy,
                        int dz, int w, int h, int d) {
  nvk_tex *st = nvk_tex_get(sname), *dt = nvk_tex_get(dname);
  if (!st || !dt || !st->img || !dt->img || w <= 0 || h <= 0 || d <= 0) return;
  vk_image *src = st->img, *dst = dt->img;
  if ((uint32_t)slevel >= src->levels || (uint32_t)dlevel >= dst->levels) return;
  int sc = block_compressed(src->format), dc = block_compressed(dst->format);
  if (!sc && dc && !vk.has_bc) {
    static int told;
    if (!told++) nvk_log("copy of GPU-made BC blocks into an ETC2 texture skipped (no BC in Vulkan)");
    return;
  }
  uint32_t sbw, dbw, sbytes = vk_format_block(src->format, &sbw), dbytes = vk_format_block(dst->format, &dbw);
  if (sbytes != dbytes) {
    static int told;
    if (!told++) nvk_log("glCopyImageSubData between formats %d and %d (%u/%u bytes): skipped", src->format,
                         dst->format, sbytes, dbytes);
    return;
  }
  end_reason = END_COPY;
  nvk_end_pass();
  vk_materialize(src);
  vk_materialize(dst);
  vk_image_layout(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
  vk_image_layout(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkImageCopy r = {{src->aspect, (uint32_t)slevel, 0, 1}, {sx, sy, 0}, {dst->aspect, (uint32_t)dlevel, 0, 1},
                   {dx, dy, 0}, {(uint32_t)w, (uint32_t)h, (uint32_t)d}};
  // texel extent of the source; a compressed source counts in blocks of texels already (GL: texels)
  if (src->view_type == VK_IMAGE_VIEW_TYPE_2D_ARRAY) r.srcSubresource.baseArrayLayer = (uint32_t)sz, r.srcSubresource.layerCount = (uint32_t)d;
  else r.srcOffset.z = sz;
  if (dst->view_type == VK_IMAGE_VIEW_TYPE_2D_ARRAY) r.dstSubresource.baseArrayLayer = (uint32_t)dz, r.dstSubresource.layerCount = (uint32_t)d;
  else r.dstOffset.z = dz;
  if (src->view_type == VK_IMAGE_VIEW_TYPE_2D_ARRAY || dst->view_type == VK_IMAGE_VIEW_TYPE_2D_ARRAY) {
    r.extent.depth = 1;
    if (src->view_type != dst->view_type) {
      static int told;
      if (!told++) nvk_log("glCopyImageSubData between an array and a 2D/3D texture: skipped");
      return;
    }
  }
  vkCmdCopyImage(vk_cmd(), src->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst->image,
                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
  vk_mark_dirty(src);
  vk_mark_dirty(dst);
}

// glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE) of the bound target, GL row order (bottom row first); the kept
// readFramebufferToMemory (game screenshots) reads the window with it. Returns 0 when not possible.
int fnvk_read_window(int w, int h, void *rgba);
int nvk_read_pixels(int x, int y, int w, int h, uint8_t *out) {
  if (w <= 0 || h <= 0) return 0;
  if (!S.fbo) {
    if (x || y) return 0;
    return fnvk_read_window(w, h, out);
  }
  nvk_targets t;
  nvk_targets_of(S.fbo, &t);
  if (!t.ncolor || x < 0 || y < 0 || (uint32_t)(x + w) > t.w || (uint32_t)(y + h) > t.h) return 0;
  vk_image *img = t.color[0];
  uint32_t bw, bpp = vk_format_block(img->format, &bw);
  if (bpp != 4) return 0;
  end_reason = END_COPY;
  nvk_end_pass();
  vk_download(img, 0, x, y, 0, (uint32_t)w, (uint32_t)h, out, (size_t)w * h * 4);
  if (img->format == VK_FORMAT_B8G8R8A8_UNORM)
    for (size_t i = 0; i < (size_t)w * h * 4; i += 4) {
      uint8_t c = out[i];
      out[i] = out[i + 2];
      out[i + 2] = c;
    }
  return 1;
}

// FramebufferOpenGL::copyTo(other): color 0, the whole size of this framebuffer's color 0
static void v_fbCopyTo(void *fb, void *other) {
  nvk_targets a, b;
  nvk_targets_of(F32(fb, FB_NAME), &a);
  nvk_targets_of(other ? F32(other, FB_NAME) : 0, &b);
  void *c0 = FP(fb, FB_COLOR0);
  uint32_t w = c0 ? F32(c0, BM_W) : a.w, h = c0 ? F32(c0, BM_H) : a.h;
  if (!a.ncolor || !b.ncolor) return;
  if (a.flip) {  // the window as the source: copied as stored (rows top-down); not seen from the game so far
    static int told;
    if (!told++) nvk_log("copyTo from the window (turned %d)%s", a.rot, a.rot ? ": not done" : "");
    if (a.rot) return;
  }
  if (b.rot) {  // a blit cannot turn; not seen from the game (2.1.19) so far
    static int told;
    if (!told++) nvk_log("copyTo into the turned window: not done");
    return;
  }
  if (b.flip) {  // into the swapchain image: rows top-down
    nvk_end_pass();
    vk_image *s = a.color[0], *d = b.color[0];
    vk_materialize(s);
    vk_image_layout(s, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    vk_image_layout(d, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
    VkImageBlit r = {{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, 0, 0}, {(int32_t)w, (int32_t)h, 1}},
                     {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}, {{0, (int32_t)h, 0}, {(int32_t)w, 0, 1}}};
    vkCmdBlitImage(vk_cmd(), s->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, d->image,
                   VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r, VK_FILTER_NEAREST);
    vk_mark_dirty(s);
    return;
  }
  copy_image(a.color[0], 0, 0, 0, b.color[0], 0, 0, 0, w, h, a.color[0]->format != b.color[0]->format);
}

// FramebufferOpenGL::blitTo(bitmap, dst x, dst y, src x, src y, w, h): glCopyTexSubImage2D from color 0
static void v_fbBlitTo(void *fb, void *bm, int dx, int dy, int sx, int sy, int w, int h) {
  nvk_targets a;
  nvk_targets_of(F32(fb, FB_NAME), &a);
  nvk_tex *t = nvk_bitmap_tex(bm);
  if (!a.ncolor || !t || w <= 0 || h <= 0) return;
  if (a.flip) {  // the window as the source: rows top-down (maybe turned); not seen from the game (2.1.19) so far
    static int told;
    if (!told++) nvk_log("blitTo from the window (turned %d)%s", a.rot, a.rot ? ": not done" : "");
    if (a.rot) return;
  }
  copy_image(a.color[0], sx, sy, 0, t->img, dx, dy, 0, (uint32_t)w, (uint32_t)h, a.color[0]->format != t->img->format);
}

// FramebufferOpenGL::blitTo3DTexture(bitmap3D, slices, n): {x off, y off, z off, x, y, w, h} each
static void v_fbBlitTo3D(void *fb, void *b3, const int32_t *s, size_t n) {
  nvk_targets a;
  nvk_targets_of(F32(fb, FB_NAME), &a);
  nvk_tex *t = b3 ? nvk_tex_get(F32(b3, B3_NAME)) : NULL;
  if (nvk_trace_on()) {
    static int nlog;
    if (nlog++ < 20)
      nvk_log("blitTo3D fb %u -> 3D tex %u, %zu slices, first {%d,%d,%d,%d,%d,%d,%d}, src %s fmt %d -> fmt %d",
              F32(fb, FB_NAME), b3 ? F32(b3, B3_NAME) : 0, n, s[0], s[1], s[2], s[3], s[4], s[5], s[6],
              a.ncolor ? "ok" : "none", a.ncolor ? (int)a.color[0]->format : -1, t ? (int)t->img->format : -1);
  }
  if (!a.ncolor || !t) return;
  for (size_t i = 0; i < n; i++, s += 7)
    copy_image(a.color[0], s[3], s[4], 0, t->img, s[0], s[1], s[2], (uint32_t)s[5], (uint32_t)s[6],
               a.color[0]->format != t->img->format);
}

// ---------------------------------------------------------------- pipeline cache on disk
// <FN_PROF_DIR>/nvk-pipelines.bin (the launcher's Download/FactorioNative): the driver's compiled pipelines, so a new
// pipeline in play does not cost a stall again on the next start (Adreno: ~70 ms for each of the first ones). The
// driver checks the header (vendor, device, driver UUID) and ignores a foreign cache. Saved after new pipelines,
// at most once every 600 frames.
static char pcache_path[4096];
static void *pcache_load(size_t *n) {
  *n = 0;
  const char *dir = getenv("FN_PROF_DIR");
  if (!dir || !*dir) return NULL;
  snprintf(pcache_path, sizeof pcache_path, "%s/nvk-pipelines.bin", dir);
  FILE *f = fopen(pcache_path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  long len = ftell(f);
  fseek(f, 0, SEEK_SET);
  void *d = len > 0 && len < (256L << 20) ? malloc((size_t)len) : NULL;
  if (d && fread(d, 1, (size_t)len, f) == (size_t)len) *n = (size_t)len;
  else free(d), d = NULL;
  fclose(f);
  nvk_log("pipeline cache %s: %zu bytes", pcache_path, *n);
  return d;
}

static void pcache_save(void) {
  if (!pcache_path[0] || !pcache) return;
  size_t n = 0;
  if (vkGetPipelineCacheData(vk.dev, pcache, &n, NULL) != VK_SUCCESS || !n) return;
  void *d = malloc(n);
  if (d && vkGetPipelineCacheData(vk.dev, pcache, &n, d) == VK_SUCCESS) {
    char tmp[4200];
    snprintf(tmp, sizeof tmp, "%s.tmp", pcache_path);
    FILE *f = fopen(tmp, "wb");
    if (f) {
      int ok = fwrite(d, 1, n, f) == n;
      ok &= fclose(f) == 0;
      if (ok) rename(tmp, pcache_path);
      else remove(tmp);
    }
  }
  free(d);
}

// ---------------------------------------------------------------- install
void nvk_draw_install(void) {
  // TextureCopyHelperOpenGL::copyRegionsFromFramebuffer stays the game's: with ARB_copy_image (reported) it copies
  // regions {dstX, dstY, srcX, srcY, w, h} into level <arg> with glCopyImageSubData (nvk_copy_image_sub) — the
  // game's GPU mipmaps (TextureProcessor::generateMipmapsAccelerated).
  // StagingBitmapOpenGL: NVK_TRACE logs whether it is created at all (not replaced).
  if (nvk_trace_on()) {
    extern void nvk_trace_staging(void);
    nvk_trace_staging();
  }
  extern void nvk_t_createPipelineStateObject(void);
  fetch_shader = nvk_sym("_ZN23GraphicsInterfaceOpenGL11fetchShaderERKNSt7__cxx1112basic_stringIcSt11char_traitsIcESaIcEEEjb");
  bind_bindings = nvk_sym("_ZN17GraphicsInterface12bindBindingsERK12DrawBindings");
  VkPipelineCacheCreateInfo pc = {VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
  void *saved = pcache_load(&pc.initialDataSize);
  pc.pInitialData = saved;
  if (vkCreatePipelineCache(vk.dev, &pc, NULL, &pcache) != VK_SUCCESS) {  // a cache of another driver: empty
    pc.initialDataSize = 0;
    pc.pInitialData = NULL;
    VKCHECK(vkCreatePipelineCache(vk.dev, &pc, NULL, &pcache));
  }
  free(saved);
  VkPhysicalDeviceFeatures f;
  vkGetPhysicalDeviceFeatures(vk.pd, &f);
  independent_blend = f.independentBlend;
  dummies_make();
  dummy_sampler = nearest_sampler();
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL25createPipelineStateObjectERK26PipelineStateSpecification",
               (void *)nvk_t_createPipelineStateObject, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL17bindPipelineStateEP19PipelineStateObject", (void *)v_bindPipelineState,
               NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL21bindDepthStencilStateEPK17DepthStencilStateh",
               (void *)v_bindDepthStencilState, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL17bindUniformBufferEjRK19PipelineStateObjectPK13UniformBuffer",
               (void *)v_bindUniformBuffer, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL11bindTextureEjRK19PipelineStateObjectPK11VideoBitmapPK12SamplerState",
               (void *)v_bindTexture, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL16bindTextureArrayEjRK19PipelineStateObjectPK16VideoBitmapArrayPK12SamplerState",
               (void *)v_bindTextureArray, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL13bindTexture3DEjRK19PipelineStateObjectPK13VideoBitmap3DPK12SamplerState",
               (void *)v_bindTexture3D, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL10bindBufferEjRK19PipelineStateObjectPK11VideoBuffer",
               (void *)v_bindBuffer, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL18resetPipelineStateEv", (void *)v_resetPipelineState,
               (void **)&orig_resetPipelineState);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL4drawERK12DrawBindingsP11VideoBufferjj", (void *)v_draw, NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL11drawIndexedERK12DrawBindingsP11VideoBufferS4_jj", (void *)v_drawIndexed,
               NULL);
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL13drawInstancedERK12DrawBindingsP11VideoBufferS4_jjjj",
               (void *)v_drawInstanced, NULL);
  nvk_redirect("_ZN17FramebufferOpenGL5clearE5ColorN11Framebuffer12CapabilitiesE", (void *)v_fbClear, NULL);
  nvk_redirect("_ZN17FramebufferOpenGL6copyToEP11Framebuffer", (void *)v_fbCopyTo, NULL);
  nvk_redirect("_ZN17FramebufferOpenGL6blitToER11VideoBitmapiiiiii", (void *)v_fbBlitTo, NULL);
  nvk_redirect("_ZN17FramebufferOpenGL15blitTo3DTextureER13VideoBitmap3DPKN11Framebuffer24BlitTo3DTextureSliceDescEm",
               (void *)v_fbBlitTo3D, NULL);
  (void)pipelines_made_log;
}

// once every 600 frames, when draws were skipped since the last report (nvk_main.c, nvk_swap)
void nvk_draw_frame_end(void) {
  static uint64_t frames;
  static double start, at = -1;
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  double now = ts.tv_sec + ts.tv_nsec * 1e-9;
  if (at < 0) {
    start = now;
    at = getenv("NVK_TRACE_AT") ? atof(getenv("NVK_TRACE_AT")) : 0;
  }
  if (tracing) {
    tracing = 0;
    nvk_log("trace: frame end");
    for (uint32_t name = 1; name < 400; name++) {  // 3D textures (colour LUTs): slice 0, a few texels
      nvk_tex *t = nvk_tex_get(name);
      if (!t || !t->img || t->type != VK_IMAGE_VIEW_TYPE_3D) continue;
      uint32_t w = t->img->w, h = t->img->h;
      uint8_t *px = malloc((size_t)w * h * 4);
      vk_download(t->img, 0, 0, 0, 0, w, h, px, (size_t)w * h * 4);
      const uint8_t *a0 = px, *a1 = px + ((size_t)(h - 1) * w + (w - 1)) * 4, *a2 = px + ((size_t)(h / 2) * w + w / 2) * 4;
      nvk_log("3D tex %u %ux%ux%u slice 0: (0,0)=%u,%u,%u,%u (mid)=%u,%u,%u,%u (end)=%u,%u,%u,%u", name, w, h,
              t->img->d, a0[0], a0[1], a0[2], a0[3], a2[0], a2[1], a2[2], a2[3], a1[0], a1[1], a1[2], a1[3]);
      free(px);
    }
    // the textures of the window's draws: per mip level, the mean alpha and how many texels are not zero
    for (uint32_t name = 1; name < 200; name++) {
      nvk_tex *t = nvk_tex_get(name);
      if (!t || !t->img || t->fmt != 0 || t->img->levels < 2) continue;
      for (uint32_t l = 0; l < t->img->levels; l++) {
        uint32_t w = t->img->w >> l, h = t->img->h >> l;
        size_t n = (size_t)w * h * 4;
        uint8_t *px = malloc(n);
        vk_download(t->img, l, 0, 0, 0, w, h, px, n);
        double a = 0;
        size_t nz = 0;
        for (size_t i = 0; i < n; i += 4) a += px[i + 3], nz += px[i] | px[i + 1] | px[i + 2] | px[i + 3] ? 1 : 0;
        nvk_log("tex %u level %u %ux%u: mean alpha %.1f, %.1f%% texels not zero", name, l, w, h, a / (n / 4),
                100.0 * nz / (n / 4));
        free(px);
      }
    }
  } else if (at > 0 && now - start >= at) {
    at = 0;
    tracing = 1;
    nvk_log("trace: frame %llu", (unsigned long long)frames);
  }
  if (++frames % 600) return;
#ifdef FN_LOG
  static uint64_t last[6];
  char line[256];
  int k = 0, any = 0;
  for (int i = 0; i < 6; i++)
    if (skipped[i] != last[i]) {
      k += snprintf(line + k, sizeof line - k, " %s %llu", skip_names[i], (unsigned long long)(skipped[i] - last[i]));
      last[i] = skipped[i];
      any = 1;
    }
  if (any) nvk_log("frames %llu: skipped draws:%s", (unsigned long long)frames, line);
  {
    static uint64_t lp, ll, lc, le[END_N];
    char e[256];
    int k = 0;
    for (int i = 0; i < END_N; i++) k += snprintf(e + k, sizeof e - k, " %s %.1f", end_names[i], (st_end[i] - le[i]) / 600.0), le[i] = st_end[i];
    nvk_log("frames %llu, per frame: %.1f passes, colour %.1f loaded %.1f cleared; ends:%s", (unsigned long long)frames,
            (st_passes - lp) / 600.0, (st_color_load - ll) / 600.0, (st_color_clear - lc) / 600.0, e);
    lp = st_passes, ll = st_color_load, lc = st_color_clear;
    uint64_t host, host_free, images;
    vk_mem_stats(&host, &host_free, &images);
    nvk_log("frames %llu: memory: buffer pool %llu MB (%llu MB free), images %llu MB", (unsigned long long)frames,
            (unsigned long long)(host >> 20), (unsigned long long)(host_free >> 20), (unsigned long long)(images >> 20));
  }
#endif
  static uint64_t last_prog, last_pipe;
  if (n_prog_built != last_prog || n_pipe_built != last_pipe) {
    nvk_log("frames %llu: %llu programs built (%llu from the SPIR-V cache, %.0f ms in all), %llu pipelines (%.0f ms in all)",
            (unsigned long long)frames, (unsigned long long)n_prog_built, (unsigned long long)n_prog_cached, prog_ms,
            (unsigned long long)n_pipe_built,
            pipe_ms);
    if (n_pipe_built != last_pipe) pcache_save();
    last_prog = n_prog_built;
    last_pipe = n_pipe_built;
  }
}

void tex_direct_backtrace(char *out, size_t n);
// NVK_TRACE: is GraphicsInterfaceOpenGL::createStagingBitmap used? (the original runs; logged with callers)
static void (*staging_orig)(void);
__attribute__((naked)) static void v_createStagingBitmap(void) {
  __asm__ volatile(
      "stp x29, x30, [sp, #-96]!\n mov x29, sp\n stp x0, x1, [sp, #16]\n stp x2, x3, [sp, #32]\n"
      "stp x4, x5, [sp, #48]\n stp x6, x7, [sp, #64]\n str x8, [sp, #80]\n bl staging_note\n"
      "ldp x0, x1, [sp, #16]\n ldp x2, x3, [sp, #32]\n ldp x4, x5, [sp, #48]\n ldp x6, x7, [sp, #64]\n"
      "ldr x8, [sp, #80]\n adrp x16, staging_orig\n ldr x16, [x16, :lo12:staging_orig]\n ldp x29, x30, [sp], #96\n"
      "br x16\n");
}
__attribute__((used)) static void staging_note(void) {
  static int n;
  if (n++ < 5) {
    char bt[512];
    tex_direct_backtrace(bt, sizeof bt);
    nvk_log("createStagingBitmap called; callers:%s", bt);
  }
}
void nvk_trace_staging(void) {
  nvk_redirect("_ZN23GraphicsInterfaceOpenGL19createStagingBitmapEjj9CpuAccess12BitmapFormat",
               (void *)v_createStagingBitmap, (void **)&staging_orig);
}
