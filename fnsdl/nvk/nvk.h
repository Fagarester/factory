// Native Vulkan renderer (FN_RENDERER=nvk): the game's GraphicsInterfaceOpenGL methods that render are replaced
// by Vulkan code working on the game's own objects (no GL calls on the drawing path). Design and the list of
// replaced functions: NVK.md. Vulkan 1.1 core only (render passes, no dynamic rendering); push descriptors when
// the driver has them.
//
// Game object layouts below: Factorio 2.1.19 (build 87445, linux-arm64), read from the disassembly of the
// methods named next to each field (analysis/gl-backend.s, not published). nvk_layout_check() compares a few
// instructions at start and refuses to run on a game where they differ.
#pragma once
#include <stddef.h>
#include <stdint.h>

#include "../vk/vk_internal.h"

#define F8(p, off) (*(uint8_t *)((char *)(p) + (off)))
#define F16(p, off) (*(uint16_t *)((char *)(p) + (off)))
#define F32(p, off) (*(uint32_t *)((char *)(p) + (off)))
#define FI32(p, off) (*(int32_t *)((char *)(p) + (off)))
#define FF(p, off) (*(float *)((char *)(p) + (off)))
#define F64(p, off) (*(uint64_t *)((char *)(p) + (off)))
#define FP(p, off) (*(void **)((char *)(p) + (off)))

// ---- GraphicsInterfaceOpenGL (this of every GI method)
#define GI_CUR_BINDING_PSO 0x10  // GraphicsInterface: PSO of the last bindBindings (base class)
#define GI_VIEW_W 0x210          // u32 viewport size set by setViewport / bindFramebuffer
#define GI_VIEW_H 0x214
#define GI_SCISSOR 0x218         // u32 x, y, w, h of the game's scissor rectangle
#define GI_FLIP 0x268            // u8: target rows top-down (setViewport's 3rd argument)
#define GI_SCISSOR_ON 0x26e      // u8: setScissorRectangle was called (reset by resetScissorRectangle)
#define GI_CUR_FB 0x280          // Framebuffer* bound by bindFramebuffer
#define GI_DRAWS 0x308           // u64 draw counter
#define GI_CAPS 0x3e0            // u8 blend on, u8 scissor on, u8 color mask bits (bit0 att 0, bit1 att 1)
#define GI_CUR_PSO 0x498         // PipelineStateObjectOpenGL* bound by bindPipelineState
#define GI_FBO 0x4a0             // u32 bound framebuffer name (ours)

// ---- PipelineStateSpecification (createPipelineStateObject)
#define PSS_BINDINGS 0x0     // std::vector<Binding>, Binding = {u32 type; char *name} (16 bytes)
#define PSS_VS_NAME 0x18     // std::string
#define PSS_FS_NAME 0x38     // std::string
#define PSS_LAYOUT 0x158     // VertexLayoutSpecification*: std::vector<{u32 type; u32 ?}> (8 bytes per element)
#define PSS_BLEND 0x160      // u8 eqRGB, eqA, srcRGB, srcA, dstRGB, dstA (indices into the game's tables)
#define PSS_BLEND_ON 0x166   // u8
#define PSS_COLOR_WRITE 0x167
#define PSS_TOPOLOGY 0x16c   // u8: 0 points, 1 triangles, 2 triangle strip, 3 lines, 4 line strip
#define PSS_FLAGS 0x16d      // u8: bit0 ?, bit1 scissor test, bit2 instanced vertex data
// Binding types (PipelineBindings::Bind::Type): 1 uniform block, 2 texture (kind at DrawBindings), 3 texel buffer

// ---- PipelineStateObjectOpenGL (168 bytes, vtable _ZTV25PipelineStateObjectOpenGL)
#define PSO_BINDINGS 0x8     // std::vector<Binding> (base class PipelineStateObject)
#define PSO_PROGRAM 0x20     // u32 GL program (ours: unused), u8 linked at 0x24
#define PSO_VAO 0x28         // u32 GL VAO name
#define PSO_NVK 0x30         // ours: nvk_pso* (the original keeps a VAO buffer cache here)
#define PSO_LAYOUT 0x40      // VertexLayoutSpecification*
#define PSO_INSTANCED 0x48   // u8
#define PSO_BLEND 0x50       // u32 GL eqRGB, eqA, srcRGB, srcA, dstRGB, dstA
#define PSO_BLEND_ON 0x68    // u8
#define PSO_COLOR_WRITE 0x69 // u8
#define PSO_TOPOLOGY_GL 0x6c // u32 GL mode
#define PSO_TOPOLOGY 0x70    // u8
#define PSO_SCISSOR 0x71     // u8
#define PSO_LOCATIONS 0x78   // std::vector<int>
#define PSO_UNITS 0x90       // std::vector<int8_t>
#define PSO_SIZE 0xa8

// ---- ShaderOpenGL (fetchShader's result)
#define SHADER_ID 0x0        // u32 GL shader name (ours: shader store)
#define SHADER_OK 0x4        // u8 compiled

// ---- VideoBuffer / VideoBufferOpenGL (64 bytes)
#define VB_TYPE 0xc          // u32 0 vertex, 1 index, 2 texel buffer
#define VB_FORMAT 0x10       // u32 BufferFormat of a texel buffer: 1 R8UI, 2 R16UI, 3 R32UI, 4 RGBA8UI, 5 R32F, 6 RGBA32F
#define VB_STATIC 0x14       // u8
#define VB_SIZE 0x20         // u64 allocated bytes
#define VB_GI 0x28
#define VB_SERIAL 0x30       // u64
#define VB_NAME 0x38         // u32 GL buffer (ours: nvk_buf)
#define VB_TEX 0x3c          // u32 GL buffer texture (texel buffers)

// ---- UniformBufferOpenGL (24 bytes)
#define UB_SIZE 0xc
#define UB_NAME 0x10

// ---- VideoBitmap / VideoBitmapOpenGL (64 bytes)
#define BM_W 0x18
#define BM_H 0x1c
#define BM_INV 0x20          // float 1/w, 1/h
#define BM_OPTIONS 0x28      // BitmapOptions: u8 ?, u8 mip levels (0x29), u32 format (0x2c), u8 flag (0x30)
#define BM_LEVELS 0x29
#define BM_FORMAT 0x2c
#define BM_KIND 0x34         // u32: 0 own texture, 2 another bitmap at BM_NAME (pointer), 1 invalid
#define BM_NAME 0x38         // u32 GL texture (ours: nvk_tex)

// ---- VideoBitmapArrayOpenGL (40 bytes)
#define BA_W 0xc
#define BA_H 0x10
#define BA_LAYERS 0x14
#define BA_LEVELS 0x19
#define BA_FORMAT 0x1c
#define BA_NAME 0x24

// ---- VideoBitmap3DOpenGL
#define B3_NAME 0x24

// ---- SamplerStateOpenGL (16 bytes) and SamplerStateSpec
#define SS_NAME 0xc
// spec: u32 min (1 linear), mag, mip, wrap s, t, r (0 repeat, 1 mirrored, 2 clamp, 3 border), float lod bias,
// min lod, max lod, border color[4]

// ---- DepthStencilStateOpenGL (40 bytes): spec copied to +0xc
#define DS_DEPTH 0xc         // u8 depth test
#define DS_STENCIL 0x10      // u8 stencil test
#define DS_READ_MASK 0x11
#define DS_WRITE_MASK 0x12
#define DS_FUNC 0x14         // u32 CompareFunc index
#define DS_SFAIL 0x18        // u32 StencilOp index
#define DS_DPFAIL 0x1c
#define DS_DPPASS 0x20

// ---- Framebuffer / FramebufferOpenGL (56 bytes)
#define FB_GI 0x8
#define FB_COLOR0 0x10       // VideoBitmap*
#define FB_COLOR1 0x18
#define FB_DEPTH 0x20
#define FB_W 0x2c
#define FB_H 0x30
#define FB_NAME 0x34         // u32 GL framebuffer (ours: nvk_fb)

// ---------------------------------------------------------------- internal API
#include "../fnlog.h"
#define nvk_log(...) FNSDL_LOG("nvk", __VA_ARGS__)
// NVK_TRACE (diagnostics, FN_LOG builds only): read once; 0 at compile time otherwise, so the trace code goes
#ifdef FN_LOG
int nvk_trace_on(void);
#else
#define nvk_trace_on() 0
#endif

// game symbols (loader)
void *nvk_sym(const char *mangled);
size_t nvk_sym_size(const char *mangled);
int nvk_redirect(const char *mangled, void *to, void **orig);
void *nvk_game_new(size_t n);  // the game's operator new
uint32_t nvk_next_resource_id(void);  // VideoResource::nextSequentialId++
void *nvk_vtable(const char *mangled_vtable);  // address the objects point to (vtable + 16)

// resources (nvk_res.c)
typedef struct nvk_buf {
  vk_slice cur;
  VkDeviceSize size;
  int kind;         // NVK_BUF_*
  VkFormat texel;   // texel buffers
  VkBufferView view;
  VkBuffer view_buf;
  VkDeviceSize view_off;
  void *mapped;     // StagingBitmap map
} nvk_buf;
enum { NVK_BUF_VERTEX, NVK_BUF_INDEX, NVK_BUF_TEXEL, NVK_BUF_UNIFORM, NVK_BUF_STAGING };

typedef struct nvk_tex {
  vk_image *img;
  int fmt;          // BitmapFormat
  unsigned bc;      // GL BC format converted to ETC2/EAC (no BC in Vulkan), else 0
  VkImageViewType type;
} nvk_tex;

typedef struct nvk_sampler {
  VkSampler s;
} nvk_sampler;

nvk_buf *nvk_buf_get(uint32_t name);
nvk_tex *nvk_tex_get(uint32_t name);
VkSampler nvk_sampler_get(uint32_t name);
void *nvk_fb_get(uint32_t name);  // the game's Framebuffer*
nvk_tex *nvk_bitmap_tex(const void *bitmap);  // VideoBitmap* -> its texture (follows BM_KIND 2)
VkBufferView nvk_buf_view(nvk_buf *b);
uint32_t nvk_name_new(int table, void *obj);
void *nvk_name_get(int table, uint32_t name);
void nvk_name_del(int table, uint32_t name);
enum { NVK_T_BUF, NVK_T_TEX, NVK_T_FB, NVK_T_SAMPLER, NVK_T_SHADER, NVK_T_COUNT };
VkFormat nvk_bitmap_format(int fmt, unsigned *bc);
void nvk_res_install(void);

// drawing (nvk_draw.c)
void nvk_draw_install(void);
void nvk_end_pass(void);
int nvk_read_pixels(int x, int y, int w, int h, uint8_t *out);
void nvk_copy_image_sub(uint32_t sname, int slevel, int sx, int sy, int sz, uint32_t dname, int dlevel, int dx, int dy,
                        int dz, int w, int h, int d);
void nvk_target_bind(uint32_t fbo);  // glBindFramebuffer
void nvk_viewport(int x, int y, int w, int h);
void nvk_scissor(int x, int y, int w, int h);
void nvk_scissor_enable(int on);
void nvk_clear(const float rgba[4], int color0, int color1, int depth, int stencil, float d, int s);
void nvk_clear_color(float r, float g, float b, float a);
void nvk_color_mask(int att, int on);
void nvk_image_gone(vk_image *img);  // framebuffers made with it are destroyed
// the target being drawn: images and size (window: the swapchain image or the offscreen window image)
typedef struct {
  vk_image *color[2], *depth;
  int ncolor;
  uint32_t w, h;  // as the game sees it (window: before the turn)
  int flip;       // the swapchain image: rows top-down
  int rot;        // with flip: the image is turned rot * 90 degrees clockwise for the display (pre-rotation)
} nvk_targets;
void nvk_targets_of(uint32_t fbo, nvk_targets *t);

// GL entry points the kept game functions still call (nvk_gl.c)
void *nvk_get_proc(const char *name);
const char *nvk_shader_source(uint32_t name);

// vk_window.c (window and swapchain)
vk_image *fnvk_window_target(int *flip, int *rot, vk_image **depth, uint32_t *w, uint32_t *h);
void fnvk_present_frame(void);
void *fnvk_get_proc(const char *name);
