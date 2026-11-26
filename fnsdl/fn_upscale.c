// Render scale + upscaler.
//
// FN_RENDER_SCALE=0.5..0.99 makes the game render at a lower resolution:
// fnsdl.c reports a smaller window to the game, and here the default
// framebuffer (0) is replaced by an offscreen one of that size. At SwapWindow
// one pass scales it to the real window: FN_UPSCALER=gsr (default, Snapdragon
// Game Super Resolution v1: upscale + sharpen) or bilinear (cheapest).
// Changed while the game runs (gear menu "Графика"): fn_upscale_request, applied by fn_upscale_apply after a swap;
// the game then gets a window size event and makes its buffers again, as when a desktop window is resized.
#include "fn_upscale.h"

#include <GLES3/gl32.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "upscale/sgsr1.h"

static float scale = 1.0f;
static int use_gsr = 1;
static GLuint fbo, color_tex, depth_rb, prog, vao;
static GLint loc_viewport;
static int fb_w, fb_h;  // offscreen size (what the game renders)
static int ready;

static void (*r_glBindFramebuffer)(GLenum, GLuint);

static const char vs_src[] =
    "#version 310 es\n"
    "layout(location=0) out highp vec4 in_TEXCOORD0;\n"
    "void main() {\n"
    "  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));\n"
    "  in_TEXCOORD0 = vec4(p, 0.0, 0.0);\n"
    "  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);\n"
    "}\n";

static const char bilinear_fs[] =
    "#version 310 es\n"
    "precision mediump float;\n"
    "uniform highp vec4 ViewportInfo[1];\n"
    "uniform mediump sampler2D ps0;\n"
    "layout(location=0) in highp vec4 in_TEXCOORD0;\n"
    "layout(location=0) out vec4 out_Target0;\n"
    "void main() { out_Target0 = vec4(textureLod(ps0, in_TEXCOORD0.xy, 0.0).rgb, 1.0); }\n";

void fn_upscale_init(void) {
  const char *s = getenv("FN_RENDER_SCALE");
  if (s) scale = (float)atof(s);
  if (!(scale >= 0.25f && scale < 0.999f)) scale = 1.0f;
  const char *u = getenv("FN_UPSCALER");
  use_gsr = !(u && !strcmp(u, "bilinear"));
  if (scale < 1.0f) fprintf(stderr, "[upscale] render scale %.2f, upscaler %s\n", scale, use_gsr ? "gsr" : "bilinear");
}

int fn_upscale_active(void) { return scale < 1.0f; }

void fn_upscale_size(int *w, int *h) {
  if (scale >= 1.0f) return;
  if (w) *w = (int)(*w * scale + 0.5f);
  if (h) *h = (int)(*h * scale + 0.5f);
}

float fn_upscale_factor(void) { return scale; }

static GLuint compile(GLenum type, const char *src) {
  GLuint sh = glCreateShader(type);
  glShaderSource(sh, 1, &src, NULL);
  glCompileShader(sh);
  GLint ok = 0;
  glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
  if (!ok) {
    char log[2048];
    glGetShaderInfoLog(sh, sizeof log, NULL, log);
    fprintf(stderr, "[upscale] shader error: %s\n", log);
  }
  return sh;
}

static void ensure_prog(void);

static void create(int real_w, int real_h) {
  fb_w = real_w;
  fb_h = real_h;
  fn_upscale_size(&fb_w, &fb_h);
  if (!fbo) {
    glGenFramebuffers(1, &fbo);
    glGenTextures(1, &color_tex);
    glGenRenderbuffers(1, &depth_rb);
  }
  GLint old_tex, old_rb;
  glGetIntegerv(GL_TEXTURE_BINDING_2D, &old_tex);
  glGetIntegerv(GL_RENDERBUFFER_BINDING, &old_rb);
  // Mutable storage: the size changes with the window.
  glBindTexture(GL_TEXTURE_2D, color_tex);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, fb_w, fb_h, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
  glBindRenderbuffer(GL_RENDERBUFFER, depth_rb);
  glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, fb_w, fb_h);
  r_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color_tex, 0);
  glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depth_rb);
  GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
  glBindTexture(GL_TEXTURE_2D, (GLuint)old_tex);
  glBindRenderbuffer(GL_RENDERBUFFER, (GLuint)old_rb);
  ensure_prog();
  fprintf(stderr, "[upscale] offscreen %dx%d for window %dx%d (status 0x%x)\n", fb_w, fb_h, real_w, real_h, st);
  ready = 1;
}

static void ensure_prog(void) {
  if (!prog) {
    prog = glCreateProgram();
    glAttachShader(prog, compile(GL_VERTEX_SHADER, vs_src));
    glAttachShader(prog, compile(GL_FRAGMENT_SHADER, use_gsr ? sgsr1_frag : bilinear_fs));
    glLinkProgram(prog);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok && use_gsr) {  // fall back instead of a black screen
      fprintf(stderr, "[upscale] GSR shader failed, using bilinear\n");
      glDeleteProgram(prog);
      prog = glCreateProgram();
      glAttachShader(prog, compile(GL_VERTEX_SHADER, vs_src));
      glAttachShader(prog, compile(GL_FRAGMENT_SHADER, bilinear_fs));
      glLinkProgram(prog);
      use_gsr = 0;
    }
    loc_viewport = glGetUniformLocation(prog, "ViewportInfo");
    if (!vao) glGenVertexArrays(1, &vao);
  }
}

// ---- default framebuffer redirection (installed only when active)

GLuint fn_upscale_fbo(void) { return ready ? fbo : 0; }

void fn_upscale_set_bind(void (*real_bind)(GLenum, GLuint)) { r_glBindFramebuffer = real_bind; }

void fn_upscale_bind(GLenum target, GLuint name) {
  r_glBindFramebuffer(target, name == 0 && ready ? fbo : name);
}

// First call with a current context, and on every window size change.
void fn_upscale_begin(int real_w, int real_h) {
  if (scale >= 1.0f) return;  // no offscreen frame at 100 % (it would never be shown)
  int w = real_w, h = real_h;
  fn_upscale_size(&w, &h);
  if (!ready || w != fb_w || h != fb_h) {
    GLint draw = 0, read = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &read);
    int was_default = !ready || (GLuint)draw == fbo;
    create(real_w, real_h);
    if (!was_default) {
      r_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)draw);
      r_glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)read);
    }
  }
}

// Scales the offscreen frame into the real window. Call order at SwapWindow:
// save_state, present, real swap, restore_state.
void fn_upscale_present(int real_w, int real_h) {
  if (!ready) return;
  glActiveTexture(GL_TEXTURE0);
  // The game's frame is done: depth/stencil of the offscreen buffer is not needed.
  r_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  static const GLenum ds[] = {GL_DEPTH_STENCIL_ATTACHMENT};
  glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, ds);

  r_glBindFramebuffer(GL_FRAMEBUFFER, 0);
  static const GLenum back[] = {GL_COLOR};
  glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, back);  // whole window is overwritten
  glViewport(0, 0, real_w, real_h);
  glDisable(GL_BLEND);
  glDisable(GL_DEPTH_TEST);
  glDisable(GL_SCISSOR_TEST);
  glDisable(GL_CULL_FACE);
  glDisable(GL_STENCIL_TEST);
  glColorMask(1, 1, 1, 1);
  glUseProgram(prog);
  glUniform4f(loc_viewport, 1.0f / fb_w, 1.0f / fb_h, (float)fb_w, (float)fb_h);
  glBindTexture(GL_TEXTURE_2D, color_tex);
  glBindSampler(0, 0);
  glBindVertexArray(vao);
  glDrawArrays(GL_TRIANGLES, 0, 3);
}

// After the real swap: the game's "default framebuffer" is the offscreen one
// again; restore the state saved in present.
static struct {
  GLint prog, vao, active, tex0, smp0, draw, read, vp[4];
  GLboolean blend, depth, scissor, cull, stencil, mask[4];
} saved;

void fn_upscale_save_state(void) {
  glGetIntegerv(GL_CURRENT_PROGRAM, &saved.prog);
  glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &saved.vao);
  glGetIntegerv(GL_ACTIVE_TEXTURE, &saved.active);
  glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &saved.draw);
  glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &saved.read);
  glGetIntegerv(GL_VIEWPORT, saved.vp);
  glGetBooleanv(GL_COLOR_WRITEMASK, saved.mask);
  saved.blend = glIsEnabled(GL_BLEND);
  saved.depth = glIsEnabled(GL_DEPTH_TEST);
  saved.scissor = glIsEnabled(GL_SCISSOR_TEST);
  saved.cull = glIsEnabled(GL_CULL_FACE);
  saved.stencil = glIsEnabled(GL_STENCIL_TEST);
  glActiveTexture(GL_TEXTURE0);
  glGetIntegerv(GL_TEXTURE_BINDING_2D, &saved.tex0);
  glGetIntegerv(GL_SAMPLER_BINDING, &saved.smp0);
  glActiveTexture((GLenum)saved.active);
}

void fn_upscale_restore_state(void) {
  glActiveTexture(GL_TEXTURE0);
  glBindTexture(GL_TEXTURE_2D, (GLuint)saved.tex0);
  glBindSampler(0, (GLuint)saved.smp0);
  glActiveTexture((GLenum)saved.active);
  glUseProgram((GLuint)saved.prog);
  glBindVertexArray((GLuint)saved.vao);
  r_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)saved.draw);
  r_glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)saved.read);
  glViewport(saved.vp[0], saved.vp[1], saved.vp[2], saved.vp[3]);
  glColorMask(saved.mask[0], saved.mask[1], saved.mask[2], saved.mask[3]);
  if (saved.blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
  if (saved.depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
  if (saved.scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
  if (saved.cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
  if (saved.stencil) glEnable(GL_STENCIL_TEST); else glDisable(GL_STENCIL_TEST);
}

// ---- live changes (gear menu "Графика")

static volatile float want_scale = -1.0f;
static volatile int want_gsr = -1;

void fn_upscale_request(float s, int gsr) {
  if (!(s >= 0.25f && s < 0.999f)) s = 1.0f;
  want_gsr = gsr ? 1 : 0;
  want_scale = s;
}

int fn_upscale_gsr(void) { return use_gsr; }

// Vulkan (no GL context; vk_window.c scales at present): only the values. 1 when the size the game sees changed.
int fn_upscale_apply_values(void) {
  float s = want_scale;
  if (s < 0) return 0;
  want_scale = -1.0f;
  if (want_gsr >= 0) use_gsr = want_gsr;
  if (s == scale) return 0;
  scale = s;
  fprintf(stderr, "[upscale] live (Vulkan): render scale %.2f, upscaler %s\n", scale, use_gsr ? "gsr" : "bilinear");
  return 1;
}

// After a swap, with the game's context current: 1 when the size the game sees changed.
int fn_upscale_apply(int real_w, int real_h) {
  float s = want_scale;
  if (s < 0) return 0;
  want_scale = -1.0f;
  int gsr = want_gsr;
  if (gsr >= 0 && gsr != use_gsr) {  // another shader: made again on next use
    use_gsr = gsr;
    if (prog) glDeleteProgram(prog);
    prog = 0;
    if (ready) ensure_prog();
  }
  if (s == scale) return 0;
  GLint draw = 0;
  glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &draw);
  scale = s;
  if (scale >= 1.0f) {
    if (ready) {  // back to the real window: the offscreen frame goes
      ready = 0;
      if ((GLuint)draw == fbo) r_glBindFramebuffer(GL_FRAMEBUFFER, 0);
      glDeleteFramebuffers(1, &fbo);
      glDeleteTextures(1, &color_tex);
      glDeleteRenderbuffers(1, &depth_rb);
      fbo = color_tex = depth_rb = 0;
    }
  } else {
    fn_upscale_begin(real_w, real_h);  // made or resized; the game's "default framebuffer" is it from now on
    if (draw == 0) r_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    ensure_prog();
  }
  fprintf(stderr, "[upscale] live: render scale %.2f, upscaler %s\n", scale, use_gsr ? "gsr" : "bilinear");
  return 1;
}
