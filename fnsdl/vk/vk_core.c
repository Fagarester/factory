// Vulkan device, memory, frames and transfers for the Vulkan renderer (see vk_internal.h).
#include "vk_internal.h"
#include "../tex/direct.h"

#include <stdarg.h>
#include <stdlib.h>
#include <string.h>

fnvk_device vk;
int vk_core_native;
uint64_t vk_frame_serial = 1, vk_cmd_epoch;
void (*vk_end_pass_hook)(void);

static int has_ext(VkExtensionProperties *e, uint32_t n, const char *name) {
  for (uint32_t i = 0; i < n; i++)
    if (!strcmp(e[i].extensionName, name)) return 1;
  return 0;
}

static uint32_t mem_type(uint32_t bits, VkMemoryPropertyFlags want) {
  for (uint32_t i = 0; i < vk.mem.memoryTypeCount; i++)
    if ((bits & (1u << i)) && (vk.mem.memoryTypes[i].propertyFlags & want) == want) return i;
  return UINT32_MAX;
}

// ---------------------------------------------------------------- frames
typedef struct {
  void (*fn)(void *);
  void *arg;
} deferred;

typedef struct {
  VkCommandPool pool;
  VkCommandBuffer cmd;
  VkFence fence;
  int recording, submitted;
  vk_slice *retired;
  size_t nretired, capretired;
  deferred *defs;
  size_t ndefs, capdefs;
  uint64_t staged;
  VkDescriptorPool *dpools;  // without push descriptors: sets for this frame, reset when it is recorded again
  unsigned ndpools, curdpool;
} frame;

static frame frames[FNVK_FRAMES];
static unsigned cur;

static void slice_free(vk_slice s);

static void frame_release(frame *f) {
  for (size_t i = 0; i < f->nretired; i++) slice_free(f->retired[i]);
  f->nretired = 0;
  for (size_t i = 0; i < f->ndefs; i++) f->defs[i].fn(f->defs[i].arg);
  f->ndefs = 0;
  f->staged = 0;
}

static void frame_wait(frame *f) {
  if (!f->submitted) return;
  VkResult wr = vkWaitForFences(vk.dev, 1, &f->fence, VK_TRUE, UINT64_MAX);
  if (wr) {
    static int told;
    if (told++ < 3) fnvk_log("vkWaitForFences failed: %d (frame %llu)", wr, (unsigned long long)vk_frame_serial);
  }
  VKCHECK(vkResetFences(vk.dev, 1, &f->fence));
  f->submitted = 0;
  frame_release(f);
}

VkCommandBuffer vk_cmd(void) {
  frame *f = &frames[cur];
  if (!f->recording) {
    VKCHECK(vkResetCommandPool(vk.dev, f->pool, 0));
    for (unsigned i = 0; i < f->ndpools; i++) VKCHECK(vkResetDescriptorPool(vk.dev, f->dpools[i], 0));
    f->curdpool = 0;
    VkCommandBufferBeginInfo bi = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, NULL,
                                   VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT, NULL};
    VKCHECK(vkBeginCommandBuffer(f->cmd, &bi));
    f->recording = 1;
    vk_cmd_epoch++;
  }
  return f->cmd;
}

static VkSemaphore sub_wait, sub_signal;
static VkPipelineStageFlags sub_stage;

static void submit_current(void) {
  frame *f = &frames[cur];
  if (vk_end_pass_hook) vk_end_pass_hook();
  vk_cmd();  // an empty frame still gets a submit, so its fence and retirements work the same way
  VKCHECK(vkEndCommandBuffer(f->cmd));
  f->recording = 0;
  VkSubmitInfo si = {VK_STRUCTURE_TYPE_SUBMIT_INFO};
  si.commandBufferCount = 1;
  si.pCommandBuffers = &f->cmd;
  // copies: the globals are cleared before the submit reads the pointers
  VkSemaphore wait = sub_wait, signal = sub_signal;
  VkPipelineStageFlags stage = sub_stage;
  sub_wait = sub_signal = VK_NULL_HANDLE;
  if (wait) {
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &wait;
    si.pWaitDstStageMask = &stage;
  }
  if (signal) {
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &signal;
  }
  VkResult sr = vkQueueSubmit(vk.queue, 1, &si, f->fence);
  if (sr) {
    static int told;
    if (told++ < 3) fnvk_log("vkQueueSubmit failed: %d (frame %llu)", sr, (unsigned long long)vk_frame_serial);
  }
  f->submitted = 1;
}

void vk_submit_wait(VkSemaphore wait, VkPipelineStageFlags stage) {
  sub_wait = wait;
  sub_stage = stage;
}

void vk_submit_signal(VkSemaphore signal) { sub_signal = signal; }

unsigned vk_frame_slot(void) { return cur; }

void vk_end_frame(void) {
  submit_current();
  vk_frame_serial++;
  cur = (cur + 1) % FNVK_FRAMES;
  frame_wait(&frames[cur]);
}

void vk_finish(void) {
  submit_current();
  vk_frame_serial++;
  for (unsigned i = 0; i < FNVK_FRAMES; i++) frame_wait(&frames[(cur + 1 + i) % FNVK_FRAMES]);
}

uint64_t vk_staged_bytes(void) { return frames[cur].staged; }

VkDescriptorSet vk_alloc_set(VkDescriptorSetLayout dsl) {
  vk_cmd();  // the frame is recording: its pools were reset
  frame *f = &frames[cur];
  for (;;) {
    if (f->curdpool == f->ndpools) {
      VkDescriptorPoolSize sizes[] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 4096},
                                      {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8192},
                                      {VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER, 1024}};
      VkDescriptorPoolCreateInfo pci = {VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO, NULL, 0, 2048,
                                        sizeof sizes / sizeof *sizes, sizes};
      VkDescriptorPool pool;
      if (vkCreateDescriptorPool(vk.dev, &pci, NULL, &pool) != VK_SUCCESS) {
        fnvk_log("vkCreateDescriptorPool failed");
        return VK_NULL_HANDLE;
      }
      f->dpools = realloc(f->dpools, (f->ndpools + 1) * sizeof *f->dpools);
      f->dpools[f->ndpools++] = pool;
      if (f->ndpools > 1) fnvk_log("frame slot %u: %u descriptor pools", cur, f->ndpools);
    }
    VkDescriptorSetAllocateInfo ai = {VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO, NULL, f->dpools[f->curdpool],
                                      1, &dsl};
    VkDescriptorSet set;
    VkResult r = vkAllocateDescriptorSets(vk.dev, &ai, &set);
    if (r == VK_SUCCESS) return set;
    if (r != VK_ERROR_OUT_OF_POOL_MEMORY && r != VK_ERROR_FRAGMENTED_POOL) {
      fnvk_log("vkAllocateDescriptorSets failed: %d", r);
      return VK_NULL_HANDLE;
    }
    f->curdpool++;  // full: the next pool
  }
}

void vk_defer(void (*fn)(void *), void *arg) {
  frame *f = &frames[cur];
  if (f->ndefs == f->capdefs) {
    f->capdefs = f->capdefs ? f->capdefs * 2 : 64;
    f->defs = realloc(f->defs, f->capdefs * sizeof *f->defs);
  }
  f->defs[f->ndefs++] = (deferred){fn, arg};
}

// ---------------------------------------------------------------- host-visible slices
// Size classes 256 B .. 256 MB (powers of two). A chunk is one VkBuffer of max(32 MB, class) bytes on
// persistently mapped coherent memory, cut into equal slots with a free list.
#define NCLASS 21
#define CHUNK_MIN (32u << 20)

typedef struct {
  VkBuffer buf;
  VkDeviceMemory mem;
  uint8_t *ptr;
} chunk;

typedef struct {
  chunk *chunks;
  uint32_t nchunks;
  uint64_t *free;  // (chunk << 32) | slot
  size_t nfree, capfree;
} sclass;

static sclass classes[NCLASS];
static uint32_t host_type;
static uint64_t host_bytes;

static VkDeviceSize class_size(uint32_t c) { return (VkDeviceSize)256 << c; }
static uint64_t image_bytes;

void vk_mem_stats(uint64_t *host, uint64_t *host_free, uint64_t *images) {
  uint64_t fr = 0;
  for (uint32_t c = 0; c < NCLASS; c++) fr += (uint64_t)classes[c].nfree * class_size(c);
  *host = host_bytes;
  *host_free = fr;
  *images = image_bytes;
}

static void push_free(sclass *sc, uint64_t v) {
  if (sc->nfree == sc->capfree) {
    sc->capfree = sc->capfree ? sc->capfree * 2 : 256;
    sc->free = realloc(sc->free, sc->capfree * sizeof *sc->free);
  }
  sc->free[sc->nfree++] = v;
}

static int add_chunk(uint32_t c) {
  sclass *sc = &classes[c];
  VkDeviceSize cs = class_size(c), size = cs > CHUNK_MIN ? cs : CHUNK_MIN;
  chunk ch = {0};
  VkBufferCreateInfo bi = {VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
  bi.size = size;
  bi.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT |
             VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
             VK_BUFFER_USAGE_TRANSFER_DST_BIT;
  if (vkCreateBuffer(vk.dev, &bi, NULL, &ch.buf) != VK_SUCCESS) return 0;
  VkMemoryRequirements mr;
  vkGetBufferMemoryRequirements(vk.dev, ch.buf, &mr);
  VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, mr.size, host_type};
  if (vkAllocateMemory(vk.dev, &ai, NULL, &ch.mem) != VK_SUCCESS) {
    vkDestroyBuffer(vk.dev, ch.buf, NULL);
    fnvk_log("out of host-visible memory (%llu MB in use)", (unsigned long long)(host_bytes >> 20));
    return 0;
  }
  VKCHECK(vkBindBufferMemory(vk.dev, ch.buf, ch.mem, 0));
  VKCHECK(vkMapMemory(vk.dev, ch.mem, 0, VK_WHOLE_SIZE, 0, (void **)&ch.ptr));
  host_bytes += size;
  sc->chunks = realloc(sc->chunks, (sc->nchunks + 1) * sizeof *sc->chunks);
  sc->chunks[sc->nchunks] = ch;
  uint32_t slots = (uint32_t)(size / cs);
  for (uint32_t s = slots; s-- > 0;) push_free(sc, ((uint64_t)sc->nchunks << 32) | s);
  sc->nchunks++;
  return 1;
}

vk_slice vk_alloc(VkDeviceSize size) {
  vk_slice s = {0};
  s.cls = 0xffff;
  uint32_t c = 0;
  while (c < NCLASS && class_size(c) < size) c++;
  if (c == NCLASS) {
    fnvk_log("allocation of %llu bytes is too big", (unsigned long long)size);
    return s;
  }
  sclass *sc = &classes[c];
  if (!sc->nfree && !add_chunk(c)) return s;
  uint64_t v = sc->free[--sc->nfree];
  chunk *ch = &sc->chunks[v >> 32];
  s.buf = ch->buf;
  s.off = (v & 0xffffffffu) * class_size(c);
  s.size = size;
  s.ptr = ch->ptr + s.off;
  s.cls = c;
  s.chunk = (uint32_t)(v >> 32);
  return s;
}

static void slice_free(vk_slice s) {
  if (s.cls == 0xffff) return;
  push_free(&classes[s.cls], ((uint64_t)s.chunk << 32) | (s.off / class_size(s.cls)));
}

void vk_retire(vk_slice s) {
  if (s.cls == 0xffff) return;
  frame *f = &frames[cur];
  if (f->nretired == f->capretired) {
    f->capretired = f->capretired ? f->capretired * 2 : 256;
    f->retired = realloc(f->retired, f->capretired * sizeof *f->retired);
  }
  f->retired[f->nretired++] = s;
}

// ---------------------------------------------------------------- init
static int core_init(void);
int vk_core_init(void) {
  if (vk.dev) return 1;
  return core_init();
}

static int core_init(void) {
  VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, NULL, "FNative", 1, "fnvk", 1, VK_API_VERSION_1_1};
  VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  ici.pApplicationInfo = &app;
  uint32_t nie = 0;
  vkEnumerateInstanceExtensionProperties(NULL, &nie, NULL);
  VkExtensionProperties *ie = calloc(nie ? nie : 1, sizeof *ie);
  vkEnumerateInstanceExtensionProperties(NULL, &nie, ie);
  const char *iext[2];
  if (has_ext(ie, nie, VK_KHR_SURFACE_EXTENSION_NAME) && has_ext(ie, nie, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
    iext[0] = VK_KHR_SURFACE_EXTENSION_NAME;
    iext[1] = VK_KHR_ANDROID_SURFACE_EXTENSION_NAME;
    ici.enabledExtensionCount = 2;
    ici.ppEnabledExtensionNames = iext;
    vk.has_surface = 1;
  }
  free(ie);
  if (vkCreateInstance(&ici, NULL, &vk.inst) != VK_SUCCESS) {
    fnvk_log("vkCreateInstance failed");
    return 0;
  }
  uint32_t n = 1;
  VkResult er = vkEnumeratePhysicalDevices(vk.inst, &n, &vk.pd);
  if (er < 0 || !n) {
    fnvk_log("no Vulkan device (vkEnumeratePhysicalDevices %d, %u devices)", er, n);
    // proot on the phone: a program under /root sees no device, the same program under /tmp does (2026-11-20)
    vkDestroyInstance(vk.inst, NULL);
    vk.inst = VK_NULL_HANDLE;
    return 0;
  }
  vkGetPhysicalDeviceProperties(vk.pd, &vk.props);
  vkGetPhysicalDeviceMemoryProperties(vk.pd, &vk.mem);
  uint32_t nq = 0;
  vkGetPhysicalDeviceQueueFamilyProperties(vk.pd, &nq, NULL);
  VkQueueFamilyProperties *qf = calloc(nq, sizeof *qf);
  vkGetPhysicalDeviceQueueFamilyProperties(vk.pd, &nq, qf);
  vk.qfam = UINT32_MAX;
  for (uint32_t i = 0; i < nq; i++)
    if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { vk.qfam = i; break; }
  free(qf);
  uint32_t ne = 0;
  vkEnumerateDeviceExtensionProperties(vk.pd, NULL, &ne, NULL);
  VkExtensionProperties *ext = calloc(ne, sizeof *ext);
  vkEnumerateDeviceExtensionProperties(vk.pd, NULL, &ne, ext);
  // push descriptors are optional (Mali drivers lack them: lab10, MT6886): sets from per-frame pools instead
  const char *need[] = {VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME, VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME,
                        VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME};
  unsigned nneed = vk_core_native ? 0 : sizeof need / sizeof *need;  // nvk: render passes of Vulkan 1.1
  int missing = 0;
  for (unsigned i = 0; i < nneed; i++)
    if (!has_ext(ext, ne, need[i])) {
      fnvk_log("device lacks %s", need[i]);
      missing++;
    }
  if (missing) {
    free(ext);
    return 0;
  }
  const char *dext[5];
  unsigned ndext = nneed;
  memcpy(dext, need, nneed * sizeof *need);
  int has_push = has_ext(ext, ne, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME) && !getenv("FNVK_NO_PUSH");
  if (has_push) dext[ndext++] = VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME;  // FNVK_NO_PUSH: test the Mali path
  else fnvk_log("no push descriptors: descriptor sets from per-frame pools");
  if (vk.has_surface && has_ext(ext, ne, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
    dext[ndext++] = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
    vk.has_swapchain = 1;
  }
  free(ext);
  VkPhysicalDeviceFeatures have;
  vkGetPhysicalDeviceFeatures(vk.pd, &have);
  vk.has_bc = have.textureCompressionBC && !getenv("FNVK_FORCE_ETC2");  // FNVK_FORCE_ETC2: test the Mali path
  if (!vk.has_bc && !have.textureCompressionETC2) {
    fnvk_log("neither BC nor ETC2 textures in Vulkan");
    return 0;
  }
  if (!vk.has_bc && !tex_direct_enable()) fnvk_log("no BC textures in Vulkan: converted to ETC2/EAC");
  VkPhysicalDeviceFeatures feat = {0};
  feat.textureCompressionETC2 = have.textureCompressionETC2;
  feat.textureCompressionBC = have.textureCompressionBC;
  feat.samplerAnisotropy = have.samplerAnisotropy;
  feat.independentBlend = have.independentBlend;
  VkPhysicalDeviceDynamicRenderingFeaturesKHR dr = {VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR};
  dr.dynamicRendering = VK_TRUE;
  float prio = 1;
  VkDeviceQueueCreateInfo qci = {VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, NULL, 0, vk.qfam, 1, &prio};
  VkDeviceCreateInfo dci = {VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, vk_core_native ? NULL : &dr};
  dci.queueCreateInfoCount = 1;
  dci.pQueueCreateInfos = &qci;
  dci.enabledExtensionCount = ndext;
  dci.ppEnabledExtensionNames = dext;
  dci.pEnabledFeatures = &feat;
  if (vkCreateDevice(vk.pd, &dci, NULL, &vk.dev) != VK_SUCCESS) {
    fnvk_log("vkCreateDevice failed");
    return 0;
  }
  vkGetDeviceQueue(vk.dev, vk.qfam, 0, &vk.queue);
  vk.push_descriptors =
      has_push ? (PFN_vkCmdPushDescriptorSetKHR)vkGetDeviceProcAddr(vk.dev, "vkCmdPushDescriptorSetKHR") : NULL;
  if (!vk_core_native)
    vk.begin_rendering = (PFN_vkCmdBeginRenderingKHR)vkGetDeviceProcAddr(vk.dev, "vkCmdBeginRenderingKHR");
  if (!vk_core_native)
    vk.end_rendering = (PFN_vkCmdEndRenderingKHR)vkGetDeviceProcAddr(vk.dev, "vkCmdEndRenderingKHR");
  VkFormatProperties fp;
  vkGetPhysicalDeviceFormatProperties(vk.pd, VK_FORMAT_D24_UNORM_S8_UINT, &fp);
  vk.depth_stencil_format = (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)
                                ? VK_FORMAT_D24_UNORM_S8_UINT
                                : VK_FORMAT_D32_SFLOAT_S8_UINT;
  host_type = mem_type(UINT32_MAX, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                       VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
  if (host_type == UINT32_MAX)
    host_type = mem_type(UINT32_MAX, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
  for (unsigned i = 0; i < FNVK_FRAMES; i++) {
    VkCommandPoolCreateInfo pci = {VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, NULL,
                                   VK_COMMAND_POOL_CREATE_TRANSIENT_BIT, vk.qfam};
    VKCHECK(vkCreateCommandPool(vk.dev, &pci, NULL, &frames[i].pool));
    VkCommandBufferAllocateInfo cai = {VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, NULL, frames[i].pool,
                                       VK_COMMAND_BUFFER_LEVEL_PRIMARY, 1};
    VKCHECK(vkAllocateCommandBuffers(vk.dev, &cai, &frames[i].cmd));
    VkFenceCreateInfo fci = {VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VKCHECK(vkCreateFence(vk.dev, &fci, NULL, &frames[i].fence));
  }
  fnvk_log("device: %s, Vulkan %u.%u.%u, driver 0x%x, host memory type %u, depth/stencil format %d",
           vk.props.deviceName, VK_VERSION_MAJOR(vk.props.apiVersion), VK_VERSION_MINOR(vk.props.apiVersion),
           VK_VERSION_PATCH(vk.props.apiVersion), vk.props.driverVersion, host_type, vk.depth_stencil_format);
  return 1;
}

// ---------------------------------------------------------------- images
static vk_image **dirty;
static size_t ndirty, capdirty;

uint32_t vk_format_block(VkFormat f, uint32_t *bw) {
  *bw = 1;
  switch (f) {
    case VK_FORMAT_R8_UNORM: case VK_FORMAT_R8_UINT: return 1;
    case VK_FORMAT_R8G8_UNORM: case VK_FORMAT_R16_UINT: case VK_FORMAT_R5G6B5_UNORM_PACK16: case VK_FORMAT_D16_UNORM: return 2;
    case VK_FORMAT_R8G8B8A8_UNORM: case VK_FORMAT_B8G8R8A8_UNORM: case VK_FORMAT_R32_UINT: case VK_FORMAT_R32_SFLOAT:
    case VK_FORMAT_R8G8B8A8_UINT: case VK_FORMAT_D24_UNORM_S8_UINT: case VK_FORMAT_R16G16_UINT: return 4;
    case VK_FORMAT_R16G16B16A16_UINT: case VK_FORMAT_R32G32_UINT: case VK_FORMAT_D32_SFLOAT_S8_UINT: return 8;
    case VK_FORMAT_R32G32B32A32_UINT: case VK_FORMAT_R32G32B32A32_SFLOAT: return 16;
    case VK_FORMAT_BC1_RGB_UNORM_BLOCK: case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: case VK_FORMAT_BC4_UNORM_BLOCK: *bw = 4; return 8;
    case VK_FORMAT_BC2_UNORM_BLOCK: case VK_FORMAT_BC3_UNORM_BLOCK: case VK_FORMAT_BC5_UNORM_BLOCK:
    case VK_FORMAT_BC7_UNORM_BLOCK: *bw = 4; return 16;
    case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK: case VK_FORMAT_EAC_R11_UNORM_BLOCK: *bw = 4; return 8;
    case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK: case VK_FORMAT_EAC_R11G11_UNORM_BLOCK: *bw = 4; return 16;
    default: return 4;
  }
}

static int is_depth(VkFormat f) {
  return f == VK_FORMAT_D16_UNORM || f == VK_FORMAT_D24_UNORM_S8_UINT || f == VK_FORMAT_D32_SFLOAT_S8_UINT;
}

vk_image *vk_image_create(VkFormat fmt, VkImageViewType type, uint32_t w, uint32_t h, uint32_t d, uint32_t layers,
                          uint32_t levels) {
  vk_image *img = calloc(1, sizeof *img);
  img->format = fmt;
  img->w = w;
  img->h = h;
  img->d = d ? d : 1;
  img->layers = layers ? layers : 1;
  img->levels = levels ? levels : 1;
  img->view_type = type;
  img->layout = VK_IMAGE_LAYOUT_UNDEFINED;
  img->aspect = !is_depth(fmt) ? VK_IMAGE_ASPECT_COLOR_BIT
                : fmt == VK_FORMAT_D16_UNORM ? VK_IMAGE_ASPECT_DEPTH_BIT
                                             : VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
  VkFormatProperties fp;
  vkGetPhysicalDeviceFormatProperties(vk.pd, fmt, &fp);
  VkImageCreateInfo ci = {VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  ci.imageType = type == VK_IMAGE_VIEW_TYPE_3D ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D;
  ci.format = fmt;
  ci.extent = (VkExtent3D){w, h, img->d};
  ci.mipLevels = img->levels;
  ci.arrayLayers = img->layers;
  ci.samples = VK_SAMPLE_COUNT_1_BIT;
  ci.tiling = VK_IMAGE_TILING_OPTIMAL;
  ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
  if (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) ci.usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
  if (fp.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)
    ci.usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
  ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (vkCreateImage(vk.dev, &ci, NULL, &img->image) != VK_SUCCESS) {
    fnvk_log("vkCreateImage %ux%ux%u fmt %d levels %u layers %u failed", w, h, img->d, fmt, img->levels, img->layers);
    free(img);
    return NULL;
  }
  VkMemoryRequirements mr;
  vkGetImageMemoryRequirements(vk.dev, img->image, &mr);
  VkMemoryAllocateInfo ai = {VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, NULL, mr.size,
                             mem_type(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)};
  if (vkAllocateMemory(vk.dev, &ai, NULL, &img->memory) != VK_SUCCESS) {
    fnvk_log("out of device memory for a %ux%u image", w, h);
    vkDestroyImage(vk.dev, img->image, NULL);
    free(img);
    return NULL;
  }
  img->bytes = mr.size;
  image_bytes += mr.size;
  VKCHECK(vkBindImageMemory(vk.dev, img->image, img->memory, 0));
  VkImageViewCreateInfo vi = {VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  vi.image = img->image;
  vi.viewType = type;
  vi.format = fmt;
  vi.subresourceRange = (VkImageSubresourceRange){img->aspect & ~VK_IMAGE_ASPECT_STENCIL_BIT, 0, img->levels, 0,
                                                  img->layers};
  VkImageViewUsageCreateInfo usage = {VK_STRUCTURE_TYPE_IMAGE_VIEW_USAGE_CREATE_INFO, NULL, VK_IMAGE_USAGE_SAMPLED_BIT};
  vi.pNext = &usage;
  VKCHECK(vkCreateImageView(vk.dev, &vi, NULL, &img->view));
  if (ci.usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)) {
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.subresourceRange = (VkImageSubresourceRange){img->aspect, 0, 1, 0, 1};
    usage.usage = ci.usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
    VKCHECK(vkCreateImageView(vk.dev, &vi, NULL, &img->attach_view));
  }
  return img;
}

static void image_free(void *p) {
  vk_image *img = p;
  vkDestroyImageView(vk.dev, img->view, NULL);
  if (img->attach_view) vkDestroyImageView(vk.dev, img->attach_view, NULL);
  vkDestroyImage(vk.dev, img->image, NULL);
  vkFreeMemory(vk.dev, img->memory, NULL);
  image_bytes -= img->bytes;
  free(img);
}

void vk_image_destroy(vk_image *img) {
  if (!img) return;
  for (size_t i = 0; i < ndirty; i++)
    if (dirty[i] == img) dirty[i--] = dirty[--ndirty];
  vk_defer(image_free, img);
}

static void layout_masks(VkImageLayout l, int src, VkPipelineStageFlags *stage, VkAccessFlags *access) {
  switch (l) {
    case VK_IMAGE_LAYOUT_UNDEFINED:
      *stage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
      *access = 0;
      break;
    case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL:
      *stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
      *access = VK_ACCESS_TRANSFER_WRITE_BIT;
      break;
    case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL:
      *stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
      *access = src ? 0 : VK_ACCESS_TRANSFER_READ_BIT;
      break;
    case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
      *stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
      *access = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | (src ? 0 : VK_ACCESS_COLOR_ATTACHMENT_READ_BIT);
      break;
    case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
      *stage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
      *access = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | (src ? 0 : VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT);
      break;
    default:  // SHADER_READ_ONLY_OPTIMAL
      *stage = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
      *access = src ? 0 : VK_ACCESS_SHADER_READ_BIT;
      break;
  }
}

void vk_image_layout(vk_image *img, VkImageLayout layout) {
  VkImageMemoryBarrier b = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  VkPipelineStageFlags ss, ds;
  layout_masks(img->layout, 1, &ss, &b.srcAccessMask);
  layout_masks(layout, 0, &ds, &b.dstAccessMask);
  b.oldLayout = img->layout;
  b.newLayout = layout;
  b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  b.image = img->image;
  b.subresourceRange = (VkImageSubresourceRange){img->aspect, 0, img->levels, 0, img->layers};
  vkCmdPipelineBarrier(vk_cmd(), ss, ds, 0, 0, NULL, 0, NULL, 1, &b);
  img->layout = layout;
}

void vk_materialize(vk_image *img) {
  if (!img || !img->pending) return;
  if (vk_end_pass_hook) vk_end_pass_hook();
  if (img->layout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) vk_image_layout(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkImageSubresourceRange r = {img->pending, 0, img->levels, 0, img->layers};
  if (img->pending & VK_IMAGE_ASPECT_COLOR_BIT)
    vkCmdClearColorImage(vk_cmd(), img->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &img->clear.color, 1, &r);
  else
    vkCmdClearDepthStencilImage(vk_cmd(), img->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &img->clear.depthStencil,
                                1, &r);
  img->pending = 0;
  vk_mark_dirty(img);
}

void vk_mark_dirty(vk_image *img) {
  if (img->dirty || img->swapchain) return;
  img->dirty = 1;
  if (ndirty == capdirty) {
    capdirty = capdirty ? capdirty * 2 : 64;
    dirty = realloc(dirty, capdirty * sizeof *dirty);
  }
  dirty[ndirty++] = img;
}

void vk_flush_dirty(vk_image *except[], int n) {
  for (size_t i = 0; i < ndirty; i++) {
    vk_image *img = dirty[i];
    int skip = 0;
    for (int k = 0; k < n; k++) skip |= except[k] == img;
    if (skip) continue;
    if (img->layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
      vk_image_layout(img, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    img->dirty = 0;
    dirty[i--] = dirty[--ndirty];
  }
}

// ---------------------------------------------------------------- transfers
void vk_upload(vk_image *img, uint32_t level, int32_t x, int32_t y, int32_t z, uint32_t w, uint32_t h, uint32_t d,
               const void *data, size_t size, uint32_t row_len, uint32_t image_height) {
  if (vk_end_pass_hook) vk_end_pass_hook();
  if (vk_staged_bytes() > (256u << 20)) vk_finish();  // big loads: do not pile up staging memory
  vk_materialize(img);
  vk_slice s = vk_alloc(size);
  if (!s.ptr) return;
  memcpy(s.ptr, data, size);
  frames[cur].staged += size;
  if (img->layout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) vk_image_layout(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
  VkBufferImageCopy r = {0};
  r.bufferOffset = s.off;
  r.bufferRowLength = row_len;
  r.bufferImageHeight = image_height;
  int array = img->view_type == VK_IMAGE_VIEW_TYPE_2D_ARRAY;
  r.imageSubresource = (VkImageSubresourceLayers){img->aspect & ~VK_IMAGE_ASPECT_STENCIL_BIT, level,
                                                  array ? (uint32_t)z : 0, array ? d : 1};
  r.imageOffset = (VkOffset3D){x, y, array ? 0 : z};
  r.imageExtent = (VkExtent3D){w, h, array ? 1 : d};
  vkCmdCopyBufferToImage(vk_cmd(), s.buf, img->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
  vk_retire(s);
  vk_mark_dirty(img);
}

void vk_download(vk_image *img, uint32_t level, int32_t x, int32_t y, uint32_t layer, uint32_t w, uint32_t h,
                 void *out, size_t size) {
  if (vk_end_pass_hook) vk_end_pass_hook();
  vk_materialize(img);
  img->keep_depth = 1;  // read back: depth/stencil content matters
  vk_slice s = vk_alloc(size);
  if (!s.ptr) return;
  if (img->layout != VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) vk_image_layout(img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
  VkBufferImageCopy r = {0};
  r.bufferOffset = s.off;
  r.imageSubresource = (VkImageSubresourceLayers){img->aspect & ~VK_IMAGE_ASPECT_STENCIL_BIT, level, layer, 1};
  r.imageOffset = (VkOffset3D){x, y, 0};
  r.imageExtent = (VkExtent3D){w, h, 1};
  vkCmdCopyImageToBuffer(vk_cmd(), img->image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, s.buf, 1, &r);
  VkMemoryBarrier mb = {VK_STRUCTURE_TYPE_MEMORY_BARRIER, NULL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT};
  vkCmdPipelineBarrier(vk_cmd(), VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, NULL, 0,
                       NULL);
  vk_mark_dirty(img);
  vk_finish();
  memcpy(out, s.ptr, size);
  slice_free(s);
}
