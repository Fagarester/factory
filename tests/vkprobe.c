// Vulkan in proot: system loader first, then the vendor ICD directly (HAL module "HMI").
// Build: $NDK/.../aarch64-linux-android29-clang -o vkprobe tests/vkprobe.c -ldl
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

static int probe(PFN_vkGetInstanceProcAddr gipa, const char *via) {
  PFN_vkCreateInstance ci = (PFN_vkCreateInstance)gipa(NULL, "vkCreateInstance");
  PFN_vkEnumerateInstanceVersion ev = (PFN_vkEnumerateInstanceVersion)gipa(NULL, "vkEnumerateInstanceVersion");
  uint32_t ver = 0;
  if (ev) ev(&ver);
  printf("[%s] instance version %u.%u.%u\n", via, VK_VERSION_MAJOR(ver), VK_VERSION_MINOR(ver), VK_VERSION_PATCH(ver));
  VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO, 0, "vkprobe", 1, "fn", 1, VK_API_VERSION_1_1};
  VkInstanceCreateInfo ici = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, 0, 0, &app};
  VkInstance inst;
  VkResult r = ci(&ici, NULL, &inst);
  printf("[%s] vkCreateInstance = %d\n", via, r);
  if (r) return 1;
  PFN_vkEnumeratePhysicalDevices epd = (PFN_vkEnumeratePhysicalDevices)gipa(inst, "vkEnumeratePhysicalDevices");
  PFN_vkGetPhysicalDeviceProperties gp = (PFN_vkGetPhysicalDeviceProperties)gipa(inst, "vkGetPhysicalDeviceProperties");
  uint32_t n = 4; VkPhysicalDevice pd[4];
  r = epd(inst, &n, pd);
  printf("[%s] devices %d (r=%d)\n", via, n, r);
  for (uint32_t i = 0; i < n; i++) {
    VkPhysicalDeviceProperties p; gp(pd[i], &p);
    printf("  %s api %u.%u.%u driver 0x%x\n", p.deviceName, VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion),
           VK_VERSION_PATCH(p.apiVersion), p.driverVersion);
  }
  return n == 0;
}

int main(void) {
  void *l = dlopen("libvulkan.so", RTLD_NOW);
  printf("libvulkan %p %s\n", l, l ? "" : dlerror());
  if (l && !probe((PFN_vkGetInstanceProcAddr)dlsym(l, "vkGetInstanceProcAddr"), "loader")) return 0;
  void *icd = dlopen("/vendor/lib64/hw/vulkan.adreno.so", RTLD_NOW);
  printf("icd %p %s\n", icd, icd ? "" : dlerror());
  if (!icd) return 1;
  void *gipa = dlsym(icd, "vk_icdGetInstanceProcAddr");
  printf("vk_icdGetInstanceProcAddr %p, HMI %p\n", gipa, dlsym(icd, "HMI"));
  if (gipa) return probe((PFN_vkGetInstanceProcAddr)gipa, "icd");
  return 1;
}
