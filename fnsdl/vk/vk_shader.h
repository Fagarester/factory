// GLSL 330 -> SPIR-V for the Vulkan renderer (vk_shader.cpp).
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { FNVK_RES_UBO, FNVK_RES_SAMPLER, FNVK_RES_TEXEL_BUFFER };
enum { FNVK_VIEW_2D, FNVK_VIEW_2D_ARRAY, FNVK_VIEW_3D };
#define FNVK_MAX_RES 32

typedef struct {
  char name[64];
  int kind, view, stages;  // stages: 1 vertex, 2 fragment
} fnvk_resource;

typedef struct {
  uint32_t *code[2];  // vertex, fragment
  size_t words[2];
  int nres;
  fnvk_resource res[FNVK_MAX_RES];  // binding = index
  char log[2048];
} fnvk_spirv;

int fnvk_compile_program(const char *vs, const char *fs, fnvk_spirv *out);
int fnvk_compile_raw(const char *vs, const char *fs, fnvk_spirv *out);  // Vulkan GLSL as it is
void fnvk_free_spirv(fnvk_spirv *s);
const char *fnvk_rewrite_id(void);  // the SPIR-V cache key part of this build

#ifdef __cplusplus
}
#endif
