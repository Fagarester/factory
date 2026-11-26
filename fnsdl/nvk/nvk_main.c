// Native Vulkan renderer: start, game symbols, the layout check, name tables, end of frame. See nvk.h, NVK.md.
#include <dlfcn.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>

#include "nvk.h"

void nvk_log(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fputs("[nvk] ", stderr);
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
}

// ---------------------------------------------------------------- the loader's game access (loader/redirect.c)
static void *(*game_symbol)(const char *);
static int (*game_redirect)(const char *, void *, void **);
static void *(*game_new)(size_t);
static uint32_t *next_id;

static void *loader_fn(const char *name) {
  void *h = dlopen("libfnative.so", RTLD_NOW | RTLD_NOLOAD);  // the APK; in proot the loader is the executable
  void *p = h ? dlsym(h, name) : NULL;
  return p ? p : dlsym(RTLD_DEFAULT, name);
}

void *nvk_sym(const char *mangled) { return game_symbol ? game_symbol(mangled) : NULL; }

int nvk_redirect(const char *mangled, void *to, void **orig) {
  int r = game_redirect ? game_redirect(mangled, to, orig) : -1;
  if (r) nvk_log("cannot replace %s (%d)", mangled, r);
  return r;
}

void *nvk_game_new(size_t n) { return game_new(n); }

uint32_t nvk_next_resource_id(void) { return (*next_id)++; }

void *nvk_vtable(const char *mangled) {
  char *v = nvk_sym(mangled);
  return v ? v + 16 : NULL;
}

// ---------------------------------------------------------------- layout check
// Each replaced function's layout assumptions show in the game's own code: an instruction (opcode and immediate,
// registers masked) that must be in the named function. A game build where one is missing keeps OpenGL ES.
typedef struct {
  const char *fn;
  uint32_t word;  // compared with mask 0xfffffc00 (Rn, Rt free)
  const char *what;
} check;

static const check checks[] = {
    {"_ZN23GraphicsInterfaceOpenGL17bindPipelineStateEP19PipelineStateObject", 0xf9424c08, "ldr x, [gi, #0x498]"},
    {"_ZN23GraphicsInterfaceOpenGL11bindTextureEjRK19PipelineStateObjectPK11VideoBitmapPK12SamplerState", 0xb9403468,
     "ldr w, [bitmap, #0x34] (kind)"},
    {"_ZN23GraphicsInterfaceOpenGL11bindTextureEjRK19PipelineStateObjectPK11VideoBitmapPK12SamplerState", 0xb9403874,
     "ldr w, [bitmap, #0x38] (texture)"},
    {"_ZN23GraphicsInterfaceOpenGL11bindTextureEjRK19PipelineStateObjectPK11VideoBitmapPK12SamplerState", 0xb9400c95,
     "ldr w, [sampler, #0xc]"},
    {"_ZN23GraphicsInterfaceOpenGL11drawIndexedERK12DrawBindingsP11VideoBufferS4_jj", 0xb9400ee0,
     "ldr w, [buffer, #0xc] (type)"},
    {"_ZN23GraphicsInterfaceOpenGL11drawIndexedERK12DrawBindingsP11VideoBufferS4_jj", 0xb9403ae1,
     "ldr w, [buffer, #0x38] (name)"},
    {"_ZN23GraphicsInterfaceOpenGL25createPipelineStateObjectERK26PipelineStateSpecification", 0xf940aec8,
     "ldr x, [spec, #0x158] (layout)"},
    {"_ZN23GraphicsInterfaceOpenGL25createPipelineStateObjectERK26PipelineStateSpecification", 0x394582c0,
     "ldrb w, [spec, #0x160] (blend)"},
    {"_ZN23GraphicsInterfaceOpenGL25createPipelineStateObjectERK26PipelineStateSpecification", 0x3945b6c8,
     "ldrb w, [spec, #0x16d] (flags)"},
    {"_ZN23GraphicsInterfaceOpenGL25createPipelineStateObjectERK26PipelineStateSpecification", 0x3945b2c2,
     "ldrb w, [spec, #0x16c] (topology)"},
    {"_ZN23GraphicsInterfaceOpenGL17bindUniformBufferEjRK19PipelineStateObjectPK13UniformBuffer", 0xb9401073,
     "ldr w, [ubo, #0x10]"},
    {"_ZN23GraphicsInterfaceOpenGL19setScissorRectangleEjjjj", 0x3909b909, "strb w, [gi, #0x26e]"},
    {"_ZN23GraphicsInterfaceOpenGL15bindFramebufferEPK11Framebuffer", 0xb9403681, "ldr w, [fb, #0x34]"},
    {"_ZN23GraphicsInterfaceOpenGL15bindFramebufferEPK11Framebuffer", 0xf9014274, "str x, [gi, #0x280]"},
    {"_ZN17VideoBitmapOpenGL21generateTextureObjectEPKhb", 0x3940a668, "ldrb w, [bitmap, #0x29] (levels)"},
    {"_ZN17VideoBitmapOpenGL21generateTextureObjectEPKhb", 0xb9402e62, "ldr w, [bitmap, #0x2c] (format)"},
    {"_ZN17VideoBitmapOpenGL21generateTextureObjectEPKhb", 0x9100e001, "add x, bitmap, #0x38"},
    {"_ZN23GraphicsInterfaceOpenGL22createVideoBitmapArrayEjjjRK13BitmapOptions", 0x91009001, "add x, array, #0x24"},
    {"_ZN23GraphicsInterfaceOpenGL22createVideoBitmapArrayEjjjRK13BitmapOptions", 0xb9401e62,
     "ldr w, [array, #0x1c] (format)"},
    {"_ZN17VideoBufferOpenGL9mapBufferEmmb", 0xb9403a61, "ldr w, [buffer, #0x38]"},
    {"_ZN17VideoBufferOpenGL9mapBufferEmmb", 0x39405269, "ldrb w, [buffer, #0x14] (static)"},
    {"_ZN17FramebufferOpenGL25generateFramebufferObjectEv", 0x9100d001, "add x, fb, #0x34"},
    {"_ZN17FramebufferOpenGL25generateFramebufferObjectEv", 0xf9401268, "ldr x, [fb, #0x20] (depth)"},
    {"_ZN23GraphicsInterfaceOpenGL21bindDepthStencilStateEPK17DepthStencilStateh", 0x39403028,
     "ldrb w, [state, #0xc] (depth)"},
    {"_ZN23GraphicsInterfaceOpenGL21bindDepthStencilStateEPK17DepthStencilStateh", 0xb9401660,
     "ldr w, [state, #0x14] (stencil func)"},
    {"_ZN23GraphicsInterfaceOpenGL18createSamplerStateERK16SamplerStateSpec", 0xb9400ec0, "ldr w, [spec, #0xc]"},
};

static size_t (*game_symbol_size)(const char *);

static int layout_ok(void) {
  int bad = 0;
  for (size_t i = 0; i < sizeof checks / sizeof *checks; i++) {
    const uint32_t *f = nvk_sym(checks[i].fn);
    size_t n = game_symbol_size ? game_symbol_size(checks[i].fn) : 0;
    if (!f || !n) {
      nvk_log("layout check: the game has no %s", checks[i].fn);
      bad++;
      continue;
    }
    int found = 0;
    for (size_t k = 0; k < n / 4 && !found; k++) found = (f[k] & 0xfffffc00u) == (checks[i].word & 0xfffffc00u);
    if (!found) {
      nvk_log("layout check: %s lacks %s", checks[i].fn, checks[i].what);
      bad++;
    }
  }
  return !bad;
}

// ---------------------------------------------------------------- name tables (the "GL names" in game objects)
typedef struct {
  void **p;
  uint32_t n, cap;
  uint32_t *free;
  uint32_t nfree, capfree;
} table;
static table tables[NVK_T_COUNT];

uint32_t nvk_name_new(int t, void *obj) {
  table *tb = &tables[t];
  uint32_t name;
  if (tb->nfree) name = tb->free[--tb->nfree];
  else {
    if (!tb->n) tb->n = 1;  // 0 = none
    if (tb->n >= tb->cap) {
      tb->cap = tb->cap ? tb->cap * 2 : 256;
      tb->p = realloc(tb->p, tb->cap * sizeof *tb->p);
    }
    name = tb->n++;
  }
  tb->p[name] = obj;
  return name;
}

void *nvk_name_get(int t, uint32_t name) {
  table *tb = &tables[t];
  return name && name < tb->n ? tb->p[name] : NULL;
}

void nvk_name_del(int t, uint32_t name) {
  table *tb = &tables[t];
  if (!name || name >= tb->n || !tb->p[name]) return;
  tb->p[name] = NULL;
  if (tb->nfree == tb->capfree) {
    tb->capfree = tb->capfree ? tb->capfree * 2 : 256;
    tb->free = realloc(tb->free, tb->capfree * sizeof *tb->free);
  }
  tb->free[tb->nfree++] = name;
}

// ---------------------------------------------------------------- start
int nvk_activate(void) {
  game_symbol = (void *(*)(const char *))loader_fn("fn_game_symbol");
  game_redirect = (int (*)(const char *, void *, void **))loader_fn("fn_game_redirect");
  game_symbol_size = (size_t (*)(const char *))loader_fn("fn_game_symbol_size");
  if (!game_symbol || !game_redirect) {
    nvk_log("the loader does not export fn_game_symbol/fn_game_redirect");
    return 0;
  }
  game_new = (void *(*)(size_t))nvk_sym("_Znwm");
  next_id = nvk_sym("_ZN13VideoResource16nextSequentialIdE");
  if (!game_new || !next_id) {
    nvk_log("the game has no operator new or VideoResource::nextSequentialId");
    return 0;
  }
  if (!layout_ok()) return 0;
  nvk_res_install();
  nvk_draw_install();
  vk_end_pass_hook = nvk_end_pass;
  nvk_log("native Vulkan renderer: device %s, Vulkan %u.%u, push descriptors %s", vk.props.deviceName,
          VK_VERSION_MAJOR(vk.props.apiVersion), VK_VERSION_MINOR(vk.props.apiVersion),
          vk.push_descriptors ? "yes" : "no");
  return 1;
}

void nvk_draw_frame_end(void);
void nvk_swap(void) {
  nvk_draw_frame_end();
  fnvk_present_frame();
}

size_t nvk_sym_size(const char *mangled) { return game_symbol_size ? game_symbol_size(mangled) : 0; }
