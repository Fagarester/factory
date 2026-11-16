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
int fngl_tex_level_size(GLenum bc, GLint w, GLint h, GLint d);
