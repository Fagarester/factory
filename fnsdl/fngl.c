// GL layer: answers glad's function lookups for GraphicsInterfaceOpenGL.
//
// Rule: a GL name present in GLES 3.2 resolves to the driver function itself
// (no wrapper, no per-call cost). Only functions whose desktop semantics
// differ get an adapter here. Names with no GLES equivalent get a stub that
// logs the first call, so missing pieces show up at run time.
#include "fngl.h"

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
// Desktop extension names reported to the game. Empty until each one is
// checked against what the GLES driver really offers.
static const char *const extensions[] = {NULL};
static int num_extensions;

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
static void a_glDrawBuffer(GLenum b) { r_glDrawBuffers(1, &b); }
static void a_glBindFragDataLocation(GLuint p, GLuint c, const GLchar *n) { (void)p; (void)c; (void)n; }
static void a_glPolygonMode(GLenum face, GLenum mode) {
  if (r_glPolygonModeNV) r_glPolygonModeNV(face, mode);
}

static const struct { const char *name; void *fn; } adapters[] = {
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
#define MAX_STUBS 2048
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

#define STUB(i) static uint64_t stub_##i(void) { return stub_hit(i); }
#define STUB8(i) STUB(i##0) STUB(i##1) STUB(i##2) STUB(i##3) STUB(i##4) STUB(i##5) STUB(i##6) STUB(i##7)
#define STUB64(i) STUB8(i##0) STUB8(i##1) STUB8(i##2) STUB8(i##3) STUB8(i##4) STUB8(i##5) STUB8(i##6) STUB8(i##7)
#define REF(i) stub_##i,
#define REF8(i) REF(i##0) REF(i##1) REF(i##2) REF(i##3) REF(i##4) REF(i##5) REF(i##6) REF(i##7)
#define REF64(i) REF8(i##0) REF8(i##1) REF8(i##2) REF8(i##3) REF8(i##4) REF8(i##5) REF8(i##6) REF8(i##7)
// Digit expansion: two blocks of 512 stubs (ids 1000..1777, 2000..2777).
STUB64(10) STUB64(11) STUB64(12) STUB64(13) STUB64(14) STUB64(15) STUB64(16) STUB64(17)
STUB64(20) STUB64(21) STUB64(22) STUB64(23) STUB64(24) STUB64(25) STUB64(26) STUB64(27)
static uint64_t (*const stubs[])(void) = {
  REF64(10) REF64(11) REF64(12) REF64(13) REF64(14) REF64(15) REF64(16) REF64(17)
  REF64(20) REF64(21) REF64(22) REF64(23) REF64(24) REF64(25) REF64(26) REF64(27)
};
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
  for (num_extensions = 0; extensions[num_extensions]; num_extensions++) {}
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
  const char *gv = (const char *)r_glGetString(GL_VERSION);
  snprintf(version_string, sizeof version_string, "3.3.0 Core Profile (%s)", gv ? gv : "GLES");
  fnlog("driver: %s / %s", r_glGetString(GL_RENDERER), gv ? gv : "?");
}

void *fngl_get_proc(const char *name) {
  resolve_real();
  for (size_t i = 0; i < sizeof adapters / sizeof adapters[0]; i++)
    if (!strcmp(adapters[i].name, name)) return adapters[i].fn;
  void *p = driver(name);
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
