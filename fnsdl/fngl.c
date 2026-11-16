// GL layer: answers glad's function lookups for GraphicsInterfaceOpenGL.
//
// Rule: a GL name present in GLES 3.2 resolves to the driver function itself
// (no wrapper, no per-call cost). Only functions whose desktop semantics
// differ get an adapter here. Names with no GLES equivalent get a stub that
// logs the first call, so missing pieces show up at run time.
#include "fngl.h"
#include "fngl_tex.h"
#include "fn_upscale.h"

#include <GLES3/gl32.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define GL_NUM_EXTENSIONS_ 0x821D
#define GL_CONTEXT_PROFILE_MASK_ 0x9126
#define GL_CONTEXT_CORE_PROFILE_BIT_ 0x1

static void *gles;
static void *(*egl_proc)(const char *);
static int log_calls;

static void fnlog(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fputs("[fngl] ", stderr);
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
}

static void fnlog_va(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fputs("[fngl] ", stderr);
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
}

static void *driver(const char *name) {
  void *p = gles ? dlsym(gles, name) : NULL;
  if (!p && egl_proc) p = egl_proc(name);
  return p;
}

// ---------------------------------------------------------------- real entry points
static const GLubyte *(*r_glGetString)(GLenum);
static void (*r_glGetIntegerv)(GLenum, GLint *);
static void (*r_glShaderSource)(GLuint, GLsizei, const GLchar *const *, const GLint *);
static void (*r_glClearDepthf)(GLfloat);
static void (*r_glDepthRangef)(GLfloat, GLfloat);
static void (*r_glDrawBuffers)(GLsizei, const GLenum *);
static void (*r_glPolygonModeNV)(GLenum, GLenum);

// ---------------------------------------------------------------- extensions
// Desktop extension names reported to the game. Each one is reported only
// when the GLES driver has the matching feature (NULL = core in GLES 3.2).
static const struct { const char *desktop, *gles; int opt_in; } ext_map[] = {
  {"GL_ARB_texture_storage", NULL},
  {"GL_ARB_copy_image", NULL},
  {"GL_KHR_debug", NULL},
  {"GL_EXT_texture_compression_s3tc", "@s3tc"},  // native or converted (fngl_tex.c)
  {"GL_ARB_texture_compression_bptc", "GL_EXT_texture_compression_bptc"},
  {"GL_ARB_texture_compression_rgtc", "@rgtc"},
  // Off by default: suspected cause of missing geometry under load (2026-11-16).
  {"GL_ARB_buffer_storage", "GL_EXT_buffer_storage", 1},
  {"GL_EXT_texture_filter_anisotropic", "GL_EXT_texture_filter_anisotropic"},
  {"GL_ARB_texture_filter_anisotropic", "GL_EXT_texture_filter_anisotropic"},
};
static const char *extensions[sizeof ext_map / sizeof ext_map[0] + 1];
static int num_extensions;

static int driver_has(const char *name) {
  const GLubyte *(*gsi)(GLenum, GLuint) = (const GLubyte *(*)(GLenum, GLuint))driver("glGetStringi");
  GLint n = 0;
  r_glGetIntegerv(GL_NUM_EXTENSIONS, &n);
  for (GLint i = 0; i < n; i++)
    if (!strcmp((const char *)gsi(GL_EXTENSIONS, (GLuint)i), name)) return 1;
  return 0;
}

static void fnlog_va(const char *fmt, ...);
static void build_extensions(void) {
  fngl_tex_init(driver_has, driver, fnlog_va);
  const char *off = getenv("FNGL_DISABLE_EXTENSIONS");  // comma separated desktop names
  const char *on = getenv("FNGL_ENABLE_EXTENSIONS");    // opt-in ones, comma separated
  for (size_t i = 0; i < sizeof ext_map / sizeof ext_map[0]; i++) {
    if (off && strstr(off, ext_map[i].desktop)) continue;
    if (ext_map[i].opt_in && !(on && strstr(on, ext_map[i].desktop))) continue;
    const char *g = ext_map[i].gles;
    if (g && !strcmp(g, "@s3tc")) { if (!fngl_tex_family(FNGL_TEX_S3TC)) continue; }
    else if (g && !strcmp(g, "@rgtc")) { if (!fngl_tex_family(FNGL_TEX_RGTC)) continue; }
    else if (g && !driver_has(g)) continue;
    extensions[num_extensions++] = ext_map[i].desktop;
  }
  fnlog("reporting %d extensions", num_extensions);
  for (int i = 0; i < num_extensions; i++) fnlog("  %s", extensions[i]);
}

// ---------------------------------------------------------------- adapters
static char version_string[256];

static const GLubyte *a_glGetString(GLenum name) {
  switch (name) {
    case GL_VERSION: return (const GLubyte *)version_string;
    case GL_SHADING_LANGUAGE_VERSION: return (const GLubyte *)"3.30";
    case GL_EXTENSIONS: return (const GLubyte *)"";
  }
  return r_glGetString(name);
}

static const GLubyte *a_glGetStringi(GLenum name, GLuint i) {
  if (name == GL_EXTENSIONS && (int)i < num_extensions) return (const GLubyte *)extensions[i];
  return NULL;
}

static void a_glGetIntegerv(GLenum pname, GLint *v) {
  switch (pname) {
    case GL_NUM_EXTENSIONS_: *v = num_extensions; return;
    case GL_MAJOR_VERSION: *v = 3; return;
    case GL_MINOR_VERSION: *v = 3; return;
    case GL_CONTEXT_PROFILE_MASK_: *v = GL_CONTEXT_CORE_PROFILE_BIT_; return;
    case 0x821E /* GL_CONTEXT_FLAGS */: *v = 0; return;
  }
  r_glGetIntegerv(pname, v);
  if ((pname == GL_DRAW_FRAMEBUFFER_BINDING || pname == GL_READ_FRAMEBUFFER_BINDING) && fn_upscale_fbo() &&
      (GLuint)*v == fn_upscale_fbo())
    *v = 0;  // the offscreen frame stands in for the window
}

// GLSL 330 (SPIRV-Cross output) -> ESSL 3.20: new #version line and default
// precisions; the rest of the source stays as is.
static const char es_header[] =
    "#version 320 es\n"
    "precision highp float;\n"
    "precision highp int;\n"
    "precision highp sampler2D;\n"
    "precision highp sampler3D;\n"
    "precision highp sampler2DArray;\n"
    "precision highp samplerBuffer;\n"
    "precision highp samplerCube;\n"
    "precision highp sampler2DShadow;\n"
    "precision highp sampler2DArrayShadow;\n"
    "precision highp isampler2D;\n"
    "precision highp usampler2D;\n"
    "precision highp isampler2DArray;\n"
    "precision highp usampler2DArray;\n"
    "precision highp isamplerBuffer;\n"
    "precision highp usamplerBuffer;\n"
    "precision highp sampler2DMS;\n";

static void a_glShaderSource(GLuint sh, GLsizei count, const GLchar *const *str, const GLint *len) {
  size_t total = 0;
  for (GLsizei i = 0; i < count; i++) total += len && len[i] >= 0 ? (size_t)len[i] : strlen(str[i]);
  char *src = malloc(total + 1);
  size_t o = 0;
  for (GLsizei i = 0; i < count; i++) {
    size_t l = len && len[i] >= 0 ? (size_t)len[i] : strlen(str[i]);
    memcpy(src + o, str[i], l);
    o += l;
  }
  src[o] = 0;
  const char *body = src;
  char *v = strstr(src, "#version");
  if (v) {
    char *eol = strchr(v, '\n');
    body = eol ? eol + 1 : v + strlen(v);
    // Anything before #version (comments) is dropped: ESSL needs it first.
  }
  size_t hl = sizeof es_header - 1, bl = strlen(body);
  char *out = malloc(hl + bl + 16);
  memcpy(out, es_header, hl);
  memcpy(out + hl, "#line 2\n", 8);
  memcpy(out + hl + 8, body, bl + 1);
  const GLchar *p = out;
  r_glShaderSource(sh, 1, &p, NULL);
  if (getenv("FNGL_DUMP_SHADERS")) fnlog("shader %u:\n%s", sh, out);
  free(out);
  free(src);
}

static void a_glClearDepth(double d) { r_glClearDepthf((GLfloat)d); }
static void a_glDepthRange(double n, double f) { r_glDepthRangef((GLfloat)n, (GLfloat)f); }
// GL_BACK means the window; with a render scale the window is the offscreen frame.
static GLenum back_to_attachment(GLenum b, GLenum binding) {
  GLint cur = 0;
  if (b != GL_BACK || !fn_upscale_fbo()) return b;
  r_glGetIntegerv(binding, &cur);
  return (GLuint)cur == fn_upscale_fbo() ? GL_COLOR_ATTACHMENT0 : b;
}
static void a_glDrawBuffer(GLenum b) {
  b = back_to_attachment(b, GL_DRAW_FRAMEBUFFER_BINDING);
  r_glDrawBuffers(1, &b);
}
static void a_glDrawBuffers(GLsizei n, const GLenum *bufs) {
  if (n == 1) { a_glDrawBuffer(bufs[0]); return; }
  r_glDrawBuffers(n, bufs);
}
static void (*r_glReadBuffer)(GLenum);
static void a_glReadBuffer(GLenum b) { r_glReadBuffer(back_to_attachment(b, GL_READ_FRAMEBUFFER_BINDING)); }
static void (*r_glInvalidateFramebuffer)(GLenum, GLsizei, const GLenum *);
static void a_glInvalidateFramebuffer(GLenum target, GLsizei n, const GLenum *att) {
  GLint cur = 0;
  r_glGetIntegerv(target == GL_READ_FRAMEBUFFER ? GL_READ_FRAMEBUFFER_BINDING : GL_DRAW_FRAMEBUFFER_BINDING, &cur);
  if (!fn_upscale_fbo() || (GLuint)cur != fn_upscale_fbo() || n > 8) { r_glInvalidateFramebuffer(target, n, att); return; }
  GLenum a[8];
  for (GLsizei i = 0; i < n; i++)
    a[i] = att[i] == GL_COLOR ? GL_COLOR_ATTACHMENT0 : att[i] == GL_DEPTH ? GL_DEPTH_ATTACHMENT
         : att[i] == GL_STENCIL ? GL_STENCIL_ATTACHMENT : att[i];
  r_glInvalidateFramebuffer(target, n, a);
}
static void a_glBindFragDataLocation(GLuint p, GLuint c, const GLchar *n) { (void)p; (void)c; (void)n; }
static void a_glPolygonMode(GLenum face, GLenum mode) {
  if (r_glPolygonModeNV) r_glPolygonModeNV(face, mode);
}

// Sampler parameters that GLES does not have.
static void (*r_glSamplerParameteri)(GLuint, GLenum, GLint);
static void (*r_glSamplerParameterf)(GLuint, GLenum, GLfloat);
static int sampler_param_ok(GLenum p) {
  if (p == 0x8501 /* GL_TEXTURE_LOD_BIAS */) return 0;
  return 1;
}
static void a_glSamplerParameteri(GLuint s, GLenum p, GLint v) {
  if (log_calls) fnlog("glSamplerParameteri 0x%x 0x%x", p, v);
  if (sampler_param_ok(p)) r_glSamplerParameteri(s, p, v);
}
static void a_glSamplerParameterf(GLuint s, GLenum p, GLfloat v) {
  if (log_calls) fnlog("glSamplerParameterf 0x%x %f", p, v);
  if (sampler_param_ok(p)) r_glSamplerParameterf(s, p, v);
}

// glGetTexImage: attach the bound texture level to a temporary framebuffer
// and read it with glReadPixels (works into a pixel pack buffer too).
static GLuint readback_fbo;
static int bytes_per_pixel(GLenum format, GLenum type) {
  int c = 4;
  switch (format) {
    case GL_RED: case GL_RED_INTEGER: case GL_DEPTH_COMPONENT: c = 1; break;
    case GL_RG: case GL_RG_INTEGER: c = 2; break;
    case GL_RGB: case GL_RGB_INTEGER: c = 3; break;
  }
  switch (type) {
    case GL_UNSIGNED_BYTE: case GL_BYTE: return c;
    case GL_UNSIGNED_SHORT: case GL_SHORT: case GL_HALF_FLOAT: return c * 2;
    case GL_UNSIGNED_INT: case GL_INT: case GL_FLOAT: return c * 4;
  }
  return 4;  // packed 32-bit types
}
// Readback of textures that cannot be framebuffer attachments (compressed):
// draw the level into an RGBA8 target with a sampling shader.
static GLuint blit_prog, blit_vao, blit_tex, blit_fbo;
static GLint blit_level_loc;
static void (*r_glUseProgram)(GLuint);
static GLuint compile(GLenum type, const char *src) {
  GLuint sh = glCreateShader(type);
  r_glShaderSource(sh, 1, &src, NULL);
  glCompileShader(sh);
  return sh;
}
static int draw_readback(GLuint tex, GLint level, GLint w, GLint h, GLenum format, GLenum type, void *pixels) {
  if (format != GL_RGBA || type != GL_UNSIGNED_BYTE) return 0;
  if (!blit_prog) {
    GLuint vs = compile(GL_VERTEX_SHADER,
        "#version 300 es\nout vec2 uv;\nvoid main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));"
        "uv=p;gl_Position=vec4(p*2.0-1.0,0.0,1.0);}\n");
    GLuint fs = compile(GL_FRAGMENT_SHADER,
        "#version 300 es\nprecision highp float;\nuniform sampler2D t;uniform float lv;in vec2 uv;out vec4 c;"
        "void main(){c=textureLod(t,uv,lv);}\n");
    blit_prog = glCreateProgram();
    glAttachShader(blit_prog, vs);
    glAttachShader(blit_prog, fs);
    glLinkProgram(blit_prog);
    blit_level_loc = glGetUniformLocation(blit_prog, "lv");
    glGenVertexArrays(1, &blit_vao);
    glGenFramebuffers(1, &blit_fbo);
  }
  GLint prog, vao, active, unit_tex, unit_smp, draw_fbo, read_fbo, vp[4], pack_buf, unpack_buf;
  r_glGetIntegerv(GL_CURRENT_PROGRAM, &prog);
  r_glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
  r_glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
  r_glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw_fbo);
  r_glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read_fbo);
  r_glGetIntegerv(GL_VIEWPORT, vp);
  r_glGetIntegerv(GL_PIXEL_UNPACK_BUFFER_BINDING, &unpack_buf);
  r_glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pack_buf);
  GLboolean blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST), scissor = glIsEnabled(GL_SCISSOR_TEST),
            cull = glIsEnabled(GL_CULL_FACE), stencil = glIsEnabled(GL_STENCIL_TEST), mask[4];
  glGetBooleanv(GL_COLOR_WRITEMASK, mask);
  glActiveTexture(GL_TEXTURE0);
  r_glGetIntegerv(GL_TEXTURE_BINDING_2D, &unit_tex);
  r_glGetIntegerv(GL_SAMPLER_BINDING, &unit_smp);

  if (!blit_tex) glGenTextures(1, &blit_tex);
  glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
  glBindTexture(GL_TEXTURE_2D, blit_tex);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
  glBindFramebuffer(GL_FRAMEBUFFER, blit_fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, blit_tex, 0);
  glBindTexture(GL_TEXTURE_2D, tex);
  glBindSampler(0, 0);
  glDisable(GL_BLEND); glDisable(GL_DEPTH_TEST); glDisable(GL_SCISSOR_TEST);
  glDisable(GL_CULL_FACE); glDisable(GL_STENCIL_TEST);
  glColorMask(1, 1, 1, 1);
  glViewport(0, 0, w, h);
  r_glUseProgram(blit_prog);
  glUniform1f(blit_level_loc, (float)level);
  glBindVertexArray(blit_vao);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  glBindBuffer(GL_PIXEL_PACK_BUFFER, (GLuint)pack_buf);
  glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

  glBindBuffer(GL_PIXEL_UNPACK_BUFFER, (GLuint)unpack_buf);
  glBindTexture(GL_TEXTURE_2D, (GLuint)unit_tex);
  glBindSampler(0, (GLuint)unit_smp);
  glActiveTexture((GLenum)active);
  glBindVertexArray((GLuint)vao);
  r_glUseProgram((GLuint)prog);
  glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)draw_fbo);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)read_fbo);
  glViewport(vp[0], vp[1], vp[2], vp[3]);
  glColorMask(mask[0], mask[1], mask[2], mask[3]);
  if (blend) glEnable(GL_BLEND);
  if (depth) glEnable(GL_DEPTH_TEST);
  if (scissor) glEnable(GL_SCISSOR_TEST);
  if (cull) glEnable(GL_CULL_FACE);
  if (stencil) glEnable(GL_STENCIL_TEST);
  return 1;
}

static void a_glGetTexImage(GLenum target, GLint level, GLenum format, GLenum type, void *pixels) {
  GLenum binding = target == GL_TEXTURE_2D_ARRAY ? GL_TEXTURE_BINDING_2D_ARRAY
                 : target == GL_TEXTURE_3D ? GL_TEXTURE_BINDING_3D : GL_TEXTURE_BINDING_2D;
  GLint tex = 0, w = 0, h = 0, d = 1, old_fbo = 0, pack_align = 4;
  r_glGetIntegerv(binding, &tex);
  glGetTexLevelParameteriv(target, level, GL_TEXTURE_WIDTH, &w);
  glGetTexLevelParameteriv(target, level, GL_TEXTURE_HEIGHT, &h);
  if (target != GL_TEXTURE_2D) glGetTexLevelParameteriv(target, level, GL_TEXTURE_DEPTH, &d);
  r_glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &old_fbo);
  r_glGetIntegerv(GL_PACK_ALIGNMENT, &pack_align);
  if (!readback_fbo) glGenFramebuffers(1, &readback_fbo);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, readback_fbo);
  size_t row = (size_t)w * bytes_per_pixel(format, type);
  row = (row + pack_align - 1) / pack_align * pack_align;
  for (GLint layer = 0; layer < d; layer++) {
    if (target == GL_TEXTURE_2D)
      glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, level);
    else
      glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, tex, level, layer);
    GLenum st = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
    if (st != GL_FRAMEBUFFER_COMPLETE && target == GL_TEXTURE_2D &&
        draw_readback((GLuint)tex, level, w, h, format, type, pixels)) {
      if (log_calls) fnlog("glGetTexImage via draw (texture not attachable)");
      break;
    }
    if (st != GL_FRAMEBUFFER_COMPLETE) {
      fnlog("glGetTexImage: framebuffer incomplete 0x%x (target 0x%x format 0x%x type 0x%x)", st, target, format, type);
      break;
    }
    glReadPixels(0, 0, w, h, format, type, (char *)pixels + (size_t)layer * row * h);
  }
  glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)old_fbo);
  if (log_calls) fnlog("glGetTexImage target 0x%x level %d %dx%dx%d format 0x%x type 0x%x", target, level, w, h, d, format, type);
}

// ---- compressed textures: size query and readback
static int block_bytes(GLint ifmt) {
  switch (ifmt) {
    case 0x83F0: case 0x83F1: case 0x8C4C: case 0x8C4D:  // BC1
    case 0x8DBB: case 0x8DBC:                            // RGTC1 (BC4)
    case 0x9270: case 0x9271: case 0x9274: case 0x9275: case 0x9276: case 0x9277:  // EAC R11, ETC2 RGB
      return 8;
    case 0x83F2: case 0x83F3: case 0x8C4E: case 0x8C4F:  // BC2, BC3
    case 0x8DBD: case 0x8DBE:                            // RGTC2 (BC5)
    case 0x8E8C: case 0x8E8D: case 0x8E8E: case 0x8E8F:  // BPTC (BC7, BC6H)
    case 0x9272: case 0x9273: case 0x9278: case 0x9279:  // EAC RG11, ETC2 RGBA
      return 16;
  }
  return 0;
}

static void (*r_glGetTexLevelParameteriv)(GLenum, GLint, GLenum, GLint *);
static int compressed_level_size(GLenum target, GLint level) {
  GLint w = 0, h = 0, d = 1, f = 0;
  r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_WIDTH, &w);
  r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_HEIGHT, &h);
  if (target != GL_TEXTURE_2D) r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_DEPTH, &d);
  r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_INTERNAL_FORMAT, &f);
  int bb = block_bytes(f);
  return bb ? ((w + 3) / 4) * ((h + 3) / 4) * d * bb : 0;
}

static void a_glGetTexLevelParameteriv(GLenum target, GLint level, GLenum pname, GLint *v) {
  GLenum bc = (pname == 0x86A0 || pname == GL_TEXTURE_INTERNAL_FORMAT || pname == GL_TEXTURE_COMPRESSED)
                  ? fngl_tex_emulated(target) : 0;
  if (bc) {  // converted texture: answer as the BC texture the game created
    GLint w = 0, h = 0, d = 1;
    r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_WIDTH, &w);
    r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_HEIGHT, &h);
    if (target != GL_TEXTURE_2D) r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_DEPTH, &d);
    *v = pname == 0x86A0 ? fngl_tex_level_size(bc, w, h, d) : pname == GL_TEXTURE_COMPRESSED ? 1 : (GLint)bc;
    return;
  }
  if (pname == 0x86A0 /* GL_TEXTURE_COMPRESSED_IMAGE_SIZE */) { *v = compressed_level_size(target, level); return; }
  r_glGetTexLevelParameteriv(target, level, pname, v);
}

static void a_glGetCompressedTexImage(GLenum target, GLint level, void *img) {
  GLenum bc = fngl_tex_emulated(target);
  if (bc) {  // the BC bytes no longer exist: zeros (the GPU compression self-test then fails)
    GLint w = 0, h = 0, d = 1, pack = 0;
    r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_WIDTH, &w);
    r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_HEIGHT, &h);
    if (target != GL_TEXTURE_2D) r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_DEPTH, &d);
    size_t n = (size_t)fngl_tex_level_size(bc, w, h, d);
    r_glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pack);
    void *z = calloc(1, n ? n : 1);
    if (pack) glBufferSubData(GL_PIXEL_PACK_BUFFER, (GLintptr)img, (GLsizeiptr)n, z);
    else memcpy(img, z, n);
    free(z);
    return;
  }
  if (target != GL_TEXTURE_2D) { fnlog("glGetCompressedTexImage: target 0x%x not supported", target); return; }
  GLint tex = 0, w = 0, h = 0, f = 0, old_fbo = 0, pack_buf = 0;
  r_glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex);
  r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_WIDTH, &w);
  r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_HEIGHT, &h);
  r_glGetTexLevelParameteriv(target, level, GL_TEXTURE_INTERNAL_FORMAT, &f);
  int bb = block_bytes(f);
  if (!bb) { fnlog("glGetCompressedTexImage: unknown format 0x%x", f); return; }
  GLint bw = (w + 3) / 4, bh = (h + 3) / 4;
  // Same block size uncompressed format: one texel = one block.
  GLuint tmp;
  glGenTextures(1, &tmp);
  glBindTexture(GL_TEXTURE_2D, tmp);
  glTexStorage2D(GL_TEXTURE_2D, 1, bb == 8 ? GL_RG32UI : GL_RGBA32UI, bw, bh);
  glBindTexture(GL_TEXTURE_2D, (GLuint)tex);
  glCopyImageSubData((GLuint)tex, GL_TEXTURE_2D, level, 0, 0, 0, tmp, GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
  r_glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &old_fbo);
  r_glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pack_buf);
  if (!readback_fbo) glGenFramebuffers(1, &readback_fbo);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, readback_fbo);
  glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tmp, 0);
  uint32_t *px = malloc((size_t)bw * bh * 16);
  glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
  GLint align;
  r_glGetIntegerv(GL_PACK_ALIGNMENT, &align);
  glPixelStorei(GL_PACK_ALIGNMENT, 4);
  glReadPixels(0, 0, bw, bh, GL_RGBA_INTEGER, GL_UNSIGNED_INT, px);
  glPixelStorei(GL_PACK_ALIGNMENT, align);
  size_t n = (size_t)bw * bh, size = n * bb;
  if (bb == 8)
    for (size_t i = 0; i < n; i++) { px[i * 2] = px[i * 4]; px[i * 2 + 1] = px[i * 4 + 1]; }
  if (pack_buf) {
    glBindBuffer(GL_PIXEL_PACK_BUFFER, (GLuint)pack_buf);
    glBufferSubData(GL_PIXEL_PACK_BUFFER, (GLintptr)img, (GLsizeiptr)size, px);
  } else {
    memcpy(img, px, size);
  }
  free(px);
  glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)old_fbo);
  glDeleteTextures(1, &tmp);
  if (log_calls) fnlog("glGetCompressedTexImage %dx%d format 0x%x -> %zu bytes", w, h, f, size);
}

// ---- tracing of texture writes (FNGL_TRACE=1)
static void (*r_glCopyImageSubData)(GLuint, GLenum, GLint, GLint, GLint, GLint, GLuint, GLenum, GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei);
static void (*r_glCompressedTexSubImage2D)(GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLsizei, const void *);
static void (*r_glCompressedTexSubImage3D)(GLenum, GLint, GLint, GLint, GLint, GLsizei, GLsizei, GLsizei, GLenum, GLsizei, const void *);
static int trace;
static int trace_budget = 100000;
#define TRACE(...) do { if (trace && trace_budget > 0) { trace_budget--; fnlog(__VA_ARGS__); } } while (0)
static void t_glCopyImageSubData(GLuint s, GLenum st, GLint sl, GLint sx, GLint sy, GLint sz, GLuint d, GLenum dt, GLint dl,
                                 GLint dx, GLint dy, GLint dz, GLsizei w, GLsizei h, GLsizei dd) {
  TRACE("glCopyImageSubData src %u t0x%x l%d (%d,%d,%d) -> dst %u t0x%x l%d (%d,%d,%d) %dx%dx%d", s, st, sl, sx, sy, sz,
        d, dt, dl, dx, dy, dz, w, h, dd);
  r_glCopyImageSubData(s, st, sl, sx, sy, sz, d, dt, dl, dx, dy, dz, w, h, dd);
}
static void t_glCompressedTexSubImage2D(GLenum t, GLint l, GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLsizei n, const void *p) {
  TRACE("glCompressedTexSubImage2D t0x%x l%d (%d,%d) %dx%d f0x%x size %d", t, l, x, y, w, h, f, n);
  r_glCompressedTexSubImage2D(t, l, x, y, w, h, f, n, p);
}
static void t_glCompressedTexSubImage3D(GLenum t, GLint l, GLint x, GLint y, GLint z, GLsizei w, GLsizei h, GLsizei d, GLenum f,
                                        GLsizei n, const void *p) {
  TRACE("glCompressedTexSubImage3D t0x%x l%d (%d,%d,%d) %dx%dx%d f0x%x size %d", t, l, x, y, z, w, h, d, f, n);
  r_glCompressedTexSubImage3D(t, l, x, y, z, w, h, d, f, n, p);
}

static void (*r_glReadPixels)(GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *);
static void t_glReadPixels(GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum t, void *p) {
  GLint fbo = 0, pbo = 0, rb = 0;
  if (trace) {
    r_glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &fbo);
    r_glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &pbo);
    r_glGetIntegerv(GL_READ_BUFFER, &rb);
  }
  r_glReadPixels(x, y, w, h, f, t, p);
  if (trace) {
    GLenum e = glGetError();
    TRACE("glReadPixels (%d,%d) %dx%d f0x%x t0x%x fbo %d readbuf 0x%x pbo %d -> err 0x%x", x, y, w, h, f, t, fbo, rb, pbo, e);
  }
}

// ---- buffer usage statistics (FNGL_TRACE): printed every 600 calls of glMapBufferRange
static void *(*r_glMapBufferRange)(GLenum, GLintptr, GLsizeiptr, GLbitfield);
static GLsync (*r_glFenceSync)(GLenum, GLbitfield);
static GLenum (*r_glClientWaitSync)(GLsync, GLbitfield, GLuint64);
static void (*r_glWaitSync)(GLsync, GLbitfield, GLuint64);
static int sync_maps;
static long st_map, st_fence, st_cwait, st_wait, st_access[64];
static void (*r_glBufferData)(GLenum, GLsizeiptr, const void *, GLenum);
static void (*r_glBindBuffer)(GLenum, GLuint);
static void (*r_glBindBufferRange)(GLenum, GLuint, GLuint, GLintptr, GLsizeiptr);
static int trace_buf_lines = 400;
static GLuint bound_buf[16];
static int tidx(GLenum t) {
  switch (t) { case GL_ARRAY_BUFFER: return 0; case GL_ELEMENT_ARRAY_BUFFER: return 1; case GL_UNIFORM_BUFFER: return 2;
    case GL_TEXTURE_BUFFER: return 3; case GL_PIXEL_UNPACK_BUFFER: return 4; case GL_PIXEL_PACK_BUFFER: return 5; case GL_COPY_WRITE_BUFFER: return 6; }
  return 7;
}
static void t_glBindBuffer(GLenum t, GLuint b) { bound_buf[tidx(t)] = b; r_glBindBuffer(t, b); }
static void t_glBufferData(GLenum t, GLsizeiptr sz, const void *d, GLenum u) {
  if (trace_buf_lines > 0 && sz >= 65536) { trace_buf_lines--; fnlog("glBufferData t0x%x buf %u size %ld data %p usage 0x%x", t, bound_buf[tidx(t)], (long)sz, d, u); }
  r_glBufferData(t, sz, d, u);
}
static void t_glBindBufferRange(GLenum t, GLuint i, GLuint b, GLintptr o, GLsizeiptr sz) {
  if (trace_buf_lines > 0 && (st_map % 5000) < 3) { trace_buf_lines--; fnlog("glBindBufferRange t0x%x idx %u buf %u off %ld size %ld", t, i, b, (long)o, (long)sz); }
  r_glBindBufferRange(t, i, b, o, sz);
}
static void *t_glMapBufferRange(GLenum t, GLintptr o, GLsizeiptr l, GLbitfield a) {
  if (!trace) goto map;
  st_map++;
  if (trace_buf_lines > 0 && (st_map % 5000) < 6) { trace_buf_lines--; fnlog("map t0x%x buf %u off %ld len %ld acc 0x%x", t, bound_buf[tidx(t)], (long)o, (long)l, a); }
  st_access[a & 63]++;
  if (st_map % 2000 == 0) {
    fnlog("buffers: map %ld fence %ld clientwait %ld wait %ld", st_map, st_fence, st_cwait, st_wait);
    for (int i = 0; i < 64; i++) if (st_access[i]) fnlog("  access 0x%x: %ld", i, st_access[i]);
  }
map:
  if (sync_maps == 1) a &= ~GL_MAP_UNSYNCHRONIZED_BIT;
  // Mode 2 (default): a write map at offset 0 starts a new pass over a stream
  // buffer the game has just orphaned with glBufferData(NULL). Adreno does not
  // give new storage there for unsynchronized maps, so ask for it explicitly.
  if (sync_maps == 2 && o == 0 && (a & GL_MAP_UNSYNCHRONIZED_BIT) && (a & GL_MAP_WRITE_BIT))
    a = (a & ~GL_MAP_UNSYNCHRONIZED_BIT) | GL_MAP_INVALIDATE_BUFFER_BIT;
  return r_glMapBufferRange(t, o, l, a);
}
static GLsync t_glFenceSync(GLenum c, GLbitfield f) { st_fence++; return r_glFenceSync(c, f); }
static GLenum t_glClientWaitSync(GLsync s, GLbitfield f, GLuint64 t) { st_cwait++; return r_glClientWaitSync(s, f, t); }
static void t_glWaitSync(GLsync s, GLbitfield f, GLuint64 t) { st_wait++; r_glWaitSync(s, f, t); }

// ---- draw call statistics (FNGL_TRACE)
static void (*r_glDrawElementsBaseVertex)(GLenum, GLsizei, GLenum, const void *, GLint);
static void (*r_glDrawElements)(GLenum, GLsizei, GLenum, const void *);
static void (*r_glDrawElementsInstancedBaseVertex)(GLenum, GLsizei, GLenum, const void *, GLsizei, GLint);
static void (*r_glDrawArrays)(GLenum, GLint, GLsizei);
static void (*r_glDrawArraysInstanced)(GLenum, GLint, GLsizei, GLsizei);
static long dr_n[5], dr_maxcount[5], dr_maxbase[5], dr_type[5], dr_maxoff[5];
static void dr_note(int k, GLsizei count, GLenum type, const void *off, GLint base) {
  dr_n[k]++;
  if (count > dr_maxcount[k]) dr_maxcount[k] = count;
  if (base > dr_maxbase[k]) dr_maxbase[k] = base;
  if ((long)(intptr_t)off > dr_maxoff[k]) dr_maxoff[k] = (long)(intptr_t)off;
  dr_type[k] |= type == GL_UNSIGNED_SHORT ? 1 : type == GL_UNSIGNED_INT ? 2 : type == GL_UNSIGNED_BYTE ? 4 : 0;
  long total = dr_n[0] + dr_n[1] + dr_n[2] + dr_n[3] + dr_n[4];
  if (total % 50000 == 0) {
    static const char *nm[] = {"DrawElementsBaseVertex", "DrawElements", "DrawElementsInstancedBaseVertex", "DrawArrays", "DrawArraysInstanced"};
    for (int i = 0; i < 5; i++)
      if (dr_n[i]) fnlog("draw %s: n %ld maxcount %ld maxbase %ld maxoffset %ld types %ld", nm[i], dr_n[i], dr_maxcount[i], dr_maxbase[i], dr_maxoff[i], dr_type[i]);
  }
}
static void t_glDrawElementsBaseVertex(GLenum m, GLsizei c, GLenum t, const void *o, GLint b) { dr_note(0, c, t, o, b); r_glDrawElementsBaseVertex(m, c, t, o, b); }
static void t_glDrawElements(GLenum m, GLsizei c, GLenum t, const void *o) { dr_note(1, c, t, o, 0); r_glDrawElements(m, c, t, o); }
static void t_glDrawElementsInstancedBaseVertex(GLenum m, GLsizei c, GLenum t, const void *o, GLsizei i, GLint b) { dr_note(2, c, t, o, b); r_glDrawElementsInstancedBaseVertex(m, c, t, o, i, b); }
static void t_glDrawArrays(GLenum m, GLint f, GLsizei c) { dr_note(3, c, 0, (void *)(intptr_t)f, 0); r_glDrawArrays(m, f, c); }
static void t_glDrawArraysInstanced(GLenum m, GLint f, GLsizei c, GLsizei i) { dr_note(4, c, 0, (void *)(intptr_t)f, 0); r_glDrawArraysInstanced(m, f, c, i); }

// Adapters only installed with a render scale (FN_RENDER_SCALE < 1).
static const struct { const char *name; void *fn; } scale_adapters[] = {
  {"glBindFramebuffer", (void *)fn_upscale_bind},
  {"glDrawBuffers", (void *)a_glDrawBuffers},
  {"glReadBuffer", (void *)a_glReadBuffer},
  {"glInvalidateFramebuffer", (void *)a_glInvalidateFramebuffer},
};

static const struct { const char *name; void *fn; } adapters[] = {
  {"glGetTexLevelParameteriv", (void *)a_glGetTexLevelParameteriv},
  {"glGetCompressedTexImage", (void *)a_glGetCompressedTexImage},
  {"glGetTexImage", (void *)a_glGetTexImage},
  {"glSamplerParameteri", (void *)a_glSamplerParameteri},
  {"glSamplerParameterf", (void *)a_glSamplerParameterf},
  {"glGetString", (void *)a_glGetString},
  {"glGetStringi", (void *)a_glGetStringi},
  {"glGetIntegerv", (void *)a_glGetIntegerv},
  {"glShaderSource", (void *)a_glShaderSource},
  {"glClearDepth", (void *)a_glClearDepth},
  {"glDepthRange", (void *)a_glDepthRange},
  {"glDrawBuffer", (void *)a_glDrawBuffer},
  {"glBindFragDataLocation", (void *)a_glBindFragDataLocation},
  {"glPolygonMode", (void *)a_glPolygonMode},
};

// ---------------------------------------------------------------- missing-function stubs
#define MAX_STUBS 1024
static const char *stub_names[MAX_STUBS];
static char stub_called[MAX_STUBS];
static int nstubs;

static uint64_t stub_hit(int i) {
  if (!stub_called[i]) {
    stub_called[i] = 1;
    fnlog("MISSING GL function called: %s", stub_names[i]);
  }
  return 0;
}

#include "fngl_stubs.h"
#define NSTUB_FUNCS ((int)(sizeof stubs / sizeof stubs[0]))

static void *make_stub(const char *name) {
  for (int i = 0; i < nstubs; i++)
    if (!strcmp(stub_names[i], name)) return (void *)stubs[i];
  if (nstubs >= NSTUB_FUNCS) return NULL;
  stub_names[nstubs] = strdup(name);
  return (void *)stubs[nstubs++];
}

// ---------------------------------------------------------------- public
void fngl_note_requested_profile(int profile) { (void)profile; }

void fngl_init(void *(*egl_get_proc)(const char *)) {
  egl_proc = egl_get_proc;
  gles = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
  if (!gles) gles = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
  log_calls = getenv("FNGL_LOG") != NULL;
  trace = getenv("FNGL_TRACE") != NULL;
  fn_upscale_init();
  fn_upscale_set_bind((void (*)(GLenum, GLuint))driver("glBindFramebuffer"));
  // FNGL_SYNC_MAPS: 0 = as the game asks, 1 = all maps synchronized, 2 = invalidate on restart (default)
  sync_maps = getenv("FNGL_SYNC_MAPS") ? atoi(getenv("FNGL_SYNC_MAPS")) : 2;
}

// Driver error reports (FNGL_DEBUG=1): first 200 messages of medium/high severity.
static void GL_APIENTRY debug_cb(GLenum src, GLenum type, GLuint id, GLenum sev, GLsizei len, const GLchar *msg,
                                 const void *user) {
  static int count;
  (void)src; (void)id; (void)len; (void)user;
  if (sev == GL_DEBUG_SEVERITY_NOTIFICATION || sev == GL_DEBUG_SEVERITY_LOW) return;
  if (count++ < 200) fnlog("driver debug type 0x%x sev 0x%x: %s", type, sev, msg);
}

static int resolved_real;
static void resolve_real(void) {
  if (resolved_real) return;
  resolved_real = 1;
  r_glGetString = driver("glGetString");
  r_glGetIntegerv = driver("glGetIntegerv");
  r_glShaderSource = driver("glShaderSource");
  r_glClearDepthf = driver("glClearDepthf");
  r_glDepthRangef = driver("glDepthRangef");
  r_glDrawBuffers = driver("glDrawBuffers");
  r_glPolygonModeNV = driver("glPolygonModeNV");
  r_glReadBuffer = driver("glReadBuffer");
  r_glInvalidateFramebuffer = driver("glInvalidateFramebuffer");
  r_glSamplerParameteri = driver("glSamplerParameteri");
  r_glUseProgram = driver("glUseProgram");
  r_glGetTexLevelParameteriv = driver("glGetTexLevelParameteriv");
  r_glSamplerParameterf = driver("glSamplerParameterf");
  const char *gv = (const char *)r_glGetString(GL_VERSION);
  snprintf(version_string, sizeof version_string, "3.3.0 Core Profile (%s)", gv ? gv : "GLES");
  fnlog("driver: %s / %s", r_glGetString(GL_RENDERER), gv ? gv : "?");
  build_extensions();
  if (getenv("FNGL_DEBUG")) {
    glEnable(GL_DEBUG_OUTPUT);
    glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
    glDebugMessageCallback(debug_cb, NULL);
  }
}

void *fngl_get_proc(const char *name) {
  resolve_real();
  if ((trace || sync_maps) && !strcmp(name, "glMapBufferRange")) {
    r_glMapBufferRange = driver(name);
    return (void *)t_glMapBufferRange;
  }
  if (trace) {
#define TR(n) if (!strcmp(name, #n)) { r_##n = driver(name); return (void *)t_##n; }
    TR(glDrawElementsBaseVertex) TR(glDrawElements) TR(glDrawElementsInstancedBaseVertex) TR(glDrawArrays) TR(glDrawArraysInstanced)
#undef TR
    if (!strcmp(name, "glBufferData")) { r_glBufferData = driver(name); return (void *)t_glBufferData; }
    if (!strcmp(name, "glBindBuffer")) { r_glBindBuffer = driver(name); return (void *)t_glBindBuffer; }
    if (!strcmp(name, "glBindBufferRange")) { r_glBindBufferRange = driver(name); return (void *)t_glBindBufferRange; }
    if (!strcmp(name, "glFenceSync")) { r_glFenceSync = driver(name); return (void *)t_glFenceSync; }
    if (!strcmp(name, "glClientWaitSync")) { r_glClientWaitSync = driver(name); return (void *)t_glClientWaitSync; }
    if (!strcmp(name, "glWaitSync")) { r_glWaitSync = driver(name); return (void *)t_glWaitSync; }
    if (!strcmp(name, "glReadPixels")) { r_glReadPixels = driver(name); return (void *)t_glReadPixels; }
    if (!strcmp(name, "glCopyImageSubData")) { r_glCopyImageSubData = driver(name); return (void *)t_glCopyImageSubData; }
    if (!strcmp(name, "glCompressedTexSubImage2D")) { r_glCompressedTexSubImage2D = driver(name); return (void *)t_glCompressedTexSubImage2D; }
    if (!strcmp(name, "glCompressedTexSubImage3D")) { r_glCompressedTexSubImage3D = driver(name); return (void *)t_glCompressedTexSubImage3D; }
  }
  for (size_t i = 0; i < sizeof adapters / sizeof adapters[0]; i++)
    if (!strcmp(adapters[i].name, name)) return adapters[i].fn;
  if (fn_upscale_active())
    for (size_t i = 0; i < sizeof scale_adapters / sizeof scale_adapters[0]; i++)
      if (!strcmp(scale_adapters[i].name, name)) return scale_adapters[i].fn;
  void *p = fngl_tex_adapter(name);
  if (p) return p;
  if (!strcmp(name, "glCopyImageSubData") && (p = fngl_tex_copy_adapter(driver(name)))) return p;
  p = driver(name);
  if (p) return p;
  static const char *const suffixes[] = {"EXT", "OES", "KHR"};
  for (size_t i = 0; i < 3; i++) {
    char alt[128];
    snprintf(alt, sizeof alt, "%s%s", name, suffixes[i]);
    if ((p = driver(alt))) return p;
  }
  if (log_calls) fnlog("no GLES function for %s", name);
  return make_stub(name);
}
