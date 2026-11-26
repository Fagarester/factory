// Native Vulkan renderer: the GL entry points the game code we keep still calls (nvk_get_proc). They are not a
// GL implementation: state setters of the kept GraphicsInterfaceOpenGL methods (setViewport, bindFramebuffer,
// setScissorRectangle, swapBuffers...), object deletion from the game's destructors, and the shader store
// fetchShader fills (the program is built in createPipelineStateObject, nvk_draw.c). Queries of strings, limits
// and timers are answered by vk_window.c, which only reports values.
// Any other GL call is a stub that logs its name once (fngl.c): a sign of a game path not replaced yet.
#include <GLES3/gl32.h>
#include <stdlib.h>
#include <string.h>

#include "nvk.h"

void nvk_tex_free(uint32_t name);
void nvk_buf_free(uint32_t name);
void nvk_sampler_free(uint32_t name);

// ---------------------------------------------------------------- state of the kept game code
static void GL_APIENTRY n_glViewport(GLint x, GLint y, GLsizei w, GLsizei h) { nvk_viewport(x, y, w, h); }
static void GL_APIENTRY n_glScissor(GLint x, GLint y, GLsizei w, GLsizei h) { nvk_scissor(x, y, w, h); }
static void GL_APIENTRY n_glEnable(GLenum cap) {
  if (cap == GL_SCISSOR_TEST) nvk_scissor_enable(1);
}
static void GL_APIENTRY n_glDisable(GLenum cap) {
  if (cap == GL_SCISSOR_TEST) nvk_scissor_enable(0);
}
static void GL_APIENTRY n_glBindFramebuffer(GLenum target, GLuint name) {
  if (target != GL_READ_FRAMEBUFFER) nvk_target_bind(name);
}
static void GL_APIENTRY n_glClearColor(GLfloat r, GLfloat g, GLfloat b, GLfloat a) { nvk_clear_color(r, g, b, a); }
static void GL_APIENTRY n_glClear(GLbitfield mask) {
  nvk_clear(NULL, (mask & GL_COLOR_BUFFER_BIT) != 0, 0, (mask & GL_DEPTH_BUFFER_BIT) != 0,
            (mask & GL_STENCIL_BUFFER_BIT) != 0, 1.0f, 0);
}
static void GL_APIENTRY n_glColorMaski(GLuint i, GLboolean r, GLboolean g, GLboolean b, GLboolean a) {
  nvk_color_mask((int)i, r || g || b || a);
}
static void GL_APIENTRY n_glNoop1(GLuint x) { (void)x; }  // glBindVertexArray, glUseProgram, glDeleteProgram
static GLenum GL_APIENTRY n_glGetError(void) { return GL_NO_ERROR; }
static void GL_APIENTRY n_glFlush(void) {}
static void GL_APIENTRY n_glFinish(void) {
  nvk_end_pass();
  vk_finish();
}

static void GL_APIENTRY n_glCopyImageSubData(GLuint s, GLenum st, GLint sl, GLint sx, GLint sy, GLint sz, GLuint d,
                                             GLenum dt, GLint dl, GLint dx, GLint dy, GLint dz, GLsizei w, GLsizei h,
                                             GLsizei dd) {
  (void)st, (void)dt;
  nvk_copy_image_sub(s, sl, sx, sy, sz, d, dl, dx, dy, dz, w, h, dd);
}

static void GL_APIENTRY n_glReadPixels(GLint x, GLint y, GLsizei w, GLsizei h, GLenum format, GLenum type, void *out) {
  static int told;
  if (format != GL_RGBA || type != GL_UNSIGNED_BYTE || !nvk_read_pixels(x, y, w, h, out)) {
    if (!told++) nvk_log("glReadPixels 0x%x/0x%x %dx%d not handled: zeros", format, type, w, h);
    if (format == GL_RGBA && type == GL_UNSIGNED_BYTE && out) memset(out, 0, (size_t)w * h * 4);
  }
}

// ---------------------------------------------------------------- deletion (the game's destructors)
static void GL_APIENTRY n_glDeleteTextures(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) nvk_tex_free(names[i]);
}
static void GL_APIENTRY n_glDeleteBuffers(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) nvk_buf_free(names[i]);
}
static void GL_APIENTRY n_glDeleteSamplers(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++) nvk_sampler_free(names[i]);
}
static void GL_APIENTRY n_glDeleteFramebuffers(GLsizei n, const GLuint *names) {
  for (GLsizei i = 0; i < n; i++)
    if (names[i]) {
      nvk_end_pass();  // it may be the open pass's target
      nvk_name_del(NVK_T_FB, names[i]);
    }
}
static void GL_APIENTRY n_glDeleteVertexArrays(GLsizei n, const GLuint *names) { (void)n, (void)names; }

// ---------------------------------------------------------------- shader store (fetchShader)
typedef struct {
  GLenum type;
  char *src;
} shader;

static GLuint GL_APIENTRY n_glCreateShader(GLenum type) {
  shader *s = calloc(1, sizeof *s);
  s->type = type;
  return nvk_name_new(NVK_T_SHADER, s);
}
static void GL_APIENTRY n_glShaderSource(GLuint name, GLsizei count, const GLchar *const *str, const GLint *len) {
  shader *s = nvk_name_get(NVK_T_SHADER, name);
  if (!s) return;
  size_t total = 0;
  for (GLsizei i = 0; i < count; i++) total += len && len[i] >= 0 ? (size_t)len[i] : strlen(str[i]);
  free(s->src);
  s->src = malloc(total + 1);
  size_t at = 0;
  for (GLsizei i = 0; i < count; i++) {
    size_t n = len && len[i] >= 0 ? (size_t)len[i] : strlen(str[i]);
    memcpy(s->src + at, str[i], n);
    at += n;
  }
  s->src[at] = 0;
}
static void GL_APIENTRY n_glCompileShader(GLuint name) { (void)name; }  // compiled with its program
static void GL_APIENTRY n_glGetShaderiv(GLuint name, GLenum pname, GLint *v) {
  shader *s = nvk_name_get(NVK_T_SHADER, name);
  if (pname == GL_COMPILE_STATUS) *v = s && s->src ? GL_TRUE : GL_FALSE;
  else if (pname == GL_SHADER_TYPE) *v = s ? (GLint)s->type : 0;
  else *v = 0;
}
static void GL_APIENTRY n_glGetShaderInfoLog(GLuint name, GLsizei max, GLsizei *len, GLchar *log) {
  (void)name;
  if (len) *len = 0;
  if (max > 0 && log) log[0] = 0;
}
static void GL_APIENTRY n_glDeleteShader(GLuint name) {
  shader *s = nvk_name_get(NVK_T_SHADER, name);
  if (!s) return;
  free(s->src);
  free(s);
  nvk_name_del(NVK_T_SHADER, name);
}

const char *nvk_shader_source(uint32_t name) {
  shader *s = nvk_name_get(NVK_T_SHADER, name);
  return s ? s->src : NULL;
}

// ---------------------------------------------------------------- table
#define E(n, f) {#n, (void *)f}
static const struct {
  const char *name;
  void *fn;
} own[] = {
    E(glViewport, n_glViewport), E(glScissor, n_glScissor), E(glEnable, n_glEnable), E(glDisable, n_glDisable),
    E(glBindFramebuffer, n_glBindFramebuffer), E(glClearColor, n_glClearColor), E(glClear, n_glClear),
    E(glColorMaski, n_glColorMaski), E(glBindVertexArray, n_glNoop1), E(glUseProgram, n_glNoop1),
    E(glDeleteProgram, n_glNoop1), E(glGetError, n_glGetError), E(glFlush, n_glFlush), E(glFinish, n_glFinish),
    E(glDeleteTextures, n_glDeleteTextures), E(glDeleteBuffers, n_glDeleteBuffers),
    E(glDeleteSamplers, n_glDeleteSamplers), E(glDeleteFramebuffers, n_glDeleteFramebuffers),
    E(glDeleteVertexArrays, n_glDeleteVertexArrays), E(glCreateShader, n_glCreateShader), E(glCopyImageSubData, n_glCopyImageSubData), E(glReadPixels, n_glReadPixels),
    E(glShaderSource, n_glShaderSource), E(glCompileShader, n_glCompileShader), E(glGetShaderiv, n_glGetShaderiv),
    E(glGetShaderInfoLog, n_glGetShaderInfoLog), E(glDeleteShader, n_glDeleteShader),
};
// answered by vk_window.c: values only, no drawing
static const char *const reported[] = {
    "glGetString", "glGetStringi", "glGetIntegerv", "glGetFloatv", "glDebugMessageCallback",
    "glDebugMessageControl", "glGenQueries", "glDeleteQueries", "glQueryCounter", "glBeginQuery", "glEndQuery",
    "glGetQueryObjecti64v",
};

void *nvk_get_proc(const char *name) {
  for (size_t i = 0; i < sizeof own / sizeof *own; i++)
    if (!strcmp(own[i].name, name)) return own[i].fn;
  for (size_t i = 0; i < sizeof reported / sizeof *reported; i++)
    if (!strcmp(reported[i], name)) return fnvk_get_proc(name);
  return NULL;  // fngl.c gives a stub that logs the first call
}
