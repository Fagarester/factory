// Compressed texture formats the GPU lacks: automatic conversion (fngl_tex.c).
#pragma once
#include <GLES3/gl32.h>

enum { FNGL_TEX_S3TC, FNGL_TEX_RGTC };

void fngl_tex_init(int (*driver_has)(const char *), void *(*driver)(const char *), void (*log)(const char *, ...));
// 1 when every format of the family is native or converted
int fngl_tex_family(int family);
// adapter for an upload/storage function, NULL = use the driver function
void *fngl_tex_adapter(const char *name);
void *fngl_tex_copy_adapter(void *real_copy);
// BC format of the texture bound to target when it is converted, else 0
GLenum fngl_tex_emulated(GLenum target);
// 1 when that texture holds the game's own ETC2/EAC blocks (tex/direct.c): its bytes can be read back as they are
int fngl_tex_direct(GLenum target);
int fngl_tex_level_size(GLenum bc, GLint w, GLint h, GLint d);
// Vulkan renderer on GPUs without BC: ETC2/EAC GL format for a BC one (0: none), and the conversion
// (malloc'd blocks, *n bytes).
void *fngl_tex_to_etc2(unsigned bc, const void *src, int bw, int bh, int d, size_t *n);
