// Shared parts of the Vulkan renderer (FN_RENDERER=vulkan): device and memory (vk_core.c), the GL API the
// game's GraphicsInterfaceOpenGL calls, implemented on Vulkan (vk_gl.c).
//
// Images keep GL's row order (row 0 = GL's bottom row): GL and Vulkan both put framebuffer y = 0 and
// texture t = 0 at the first row in memory, so nothing is flipped except the final present.
#pragma once
#include <stdint.h>
#include <stdio.h>
#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <vulkan/vulkan.h>

#define FNVK_FRAMES 3  // command buffers in flight

void fnvk_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
#define VKCHECK(x)                                                                \
  do {                                                                            \
    VkResult r_ = (x);                                                            \
    if (r_ != VK_SUCCESS) fnvk_log("%s failed: %d (%s:%d)", #x, r_, __FILE__, __LINE__); \
  } while (0)

typedef struct {
  VkInstance inst;
  VkPhysicalDevice pd;
  VkDevice dev;
  VkQueue queue;
  uint32_t qfam;
  VkPhysicalDeviceProperties props;
  VkPhysicalDeviceMemoryProperties mem;
  PFN_vkCmdPushDescriptorSetKHR push_descriptors;  // NULL: no VK_KHR_push_descriptor (vk_alloc_set)
  PFN_vkCmdBeginRenderingKHR begin_rendering;
  PFN_vkCmdEndRenderingKHR end_rendering;
  VkFormat depth_stencil_format;  // for GL_DEPTH24_STENCIL8
  int has_surface, has_swapchain;
  int has_bc;  // BC textures in Vulkan; else BC uploads are converted to ETC2/EAC
} fnvk_device;
extern fnvk_device vk;

int vk_core_init(void);

// ---- host-visible memory slices (vertex/index/uniform/texel buffers, staging). 256-byte aligned.
typedef struct {
  VkBuffer buf;
  VkDeviceSize off, size;
  uint8_t *ptr;
  uint32_t cls, chunk;  // cls 0xffff = empty slice
} vk_slice;
vk_slice vk_alloc(VkDeviceSize size);
void vk_retire(vk_slice s);  // given back when the GPU is done with the current frame

// Callbacks run when the current frame's GPU work has finished (destroying views, images...).
void vk_defer(void (*fn)(void *), void *arg);

// ---- device-local images
typedef struct vk_image {
  VkImage image;
  VkDeviceMemory memory;
  VkImageView view;        // all levels/layers, sampled
  VkImageView attach_view; // level 0, layer 0 (attachments)
  VkFormat format;
  VkImageAspectFlags aspect;
  uint32_t w, h, d, layers, levels;
  VkImageViewType view_type;
  VkImageLayout layout;
  int dirty;  // last written (attachment or transfer): needs a barrier before sampling
  int swapchain;  // a swapchain image: never sampled, not owned
  // Deferred clear: a GL clear before any draw waits here and becomes the next pass's load op CLEAR; any other
  // use first does it as a transfer clear (vk_materialize).
  VkImageAspectFlags pending;
  VkClearValue clear;
  int keep_depth;  // depth/stencil content was loaded by a pass once: always stored
} vk_image;
vk_image *vk_image_create(VkFormat fmt, VkImageViewType type, uint32_t w, uint32_t h, uint32_t d, uint32_t layers,
                          uint32_t levels);
void vk_image_destroy(vk_image *img);  // deferred to the end of the frame
// Layout change with a full barrier (outside rendering).
void vk_image_layout(vk_image *img, VkImageLayout layout);
void vk_mark_dirty(vk_image *img);
void vk_materialize(vk_image *img);  // does a deferred clear now (outside passes)
void vk_flush_dirty(vk_image *except[], int n);  // all written images -> SHADER_READ_ONLY

// ---- commands
// vk_frame_serial: number of the frame being recorded (grows at every submit). vk_cmd_epoch: grows with every
// new command buffer (bound state must be set again).
extern uint64_t vk_frame_serial, vk_cmd_epoch;
VkCommandBuffer vk_cmd(void);   // current frame's command buffer (recording)
void vk_end_frame(void);        // submit; waits for the frame FNVK_FRAMES back
// Semaphores for the next submit only (presenting): wait before `stage`, signal when done.
void vk_submit_wait(VkSemaphore wait, VkPipelineStageFlags stage);
void vk_submit_signal(VkSemaphore signal);
unsigned vk_frame_slot(void);   // 0..FNVK_FRAMES-1, the frame being recorded
void vk_finish(void);           // submit and wait for the GPU (readbacks, glFinish)
uint64_t vk_staged_bytes(void); // staging used in this frame
// A descriptor set valid for the frame being recorded (devices without push descriptors).
VkDescriptorSet vk_alloc_set(VkDescriptorSetLayout dsl);

// The renderer ends a pass before any transfer command (vk_gl.c registers it).
extern void (*vk_end_pass_hook)(void);

// Buffer -> image upload of one region (rows of row_len texels / block rows for compressed formats).
void vk_upload(vk_image *img, uint32_t level, int32_t x, int32_t y, int32_t z, uint32_t w, uint32_t h, uint32_t d,
               const void *data, size_t size, uint32_t row_len, uint32_t image_height);
// Image -> host copy of one level region; waits for the GPU.
void vk_download(vk_image *img, uint32_t level, int32_t x, int32_t y, uint32_t layer, uint32_t w, uint32_t h,
                 void *out, size_t size);

// Format helpers
uint32_t vk_format_block(VkFormat f, uint32_t *block_w);  // bytes per texel or per 4x4 block
