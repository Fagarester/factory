// The ETC2/EAC blocks the game writes with tex/direct.c, decoded by the GPU: largest error per block kind.
// Build: NDK clang tests/directtest.c fnsdl/tex/etc2.c -lEGL -lGLESv3; run in proot (the phone's GLES driver).
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "../fnsdl/tex/etc2.h"
static GLuint sh(GLenum t, const char *s) { GLuint x = glCreateShader(t); glShaderSource(x, 1, &s, 0); glCompileShader(x); return x; }
enum { W = 256, BW = W / 4, N = BW * BW, KINDS = 6 };
static const char *kind_name[KINDS] = {"noise", "gradient", "saturated", "solid", "edge", "sprite+alpha"};
static void make_block(int k, uint8_t *p) {
  int a = rand() & 255, b = rand() & 255, c = rand() & 255;
  for (int i = 0; i < 16; i++) {
    int x = i % 4, y = i / 4;
    uint8_t *q = p + i * 4;
    switch (k) {
      case 0: q[0] = rand(); q[1] = rand(); q[2] = rand(); q[3] = 255; break;
      case 1: q[0] = a + x * 20; q[1] = b + y * 20; q[2] = c; q[3] = 255; break;
      case 2: q[0] = (i & 1) ? 255 : 0; q[1] = (i & 2) ? 255 : 0; q[2] = (i & 4) ? 255 : 0; q[3] = 255; break;
      case 3: q[0] = a; q[1] = b; q[2] = c; q[3] = 255; break;
      case 4: q[0] = x < 2 ? a : 255 - a; q[1] = x < 2 ? b : 255 - b; q[2] = x < 2 ? c : 255 - c; q[3] = 255; break;
      case 5: { int al = y < 2 ? 0 : (x + 1) * 60; q[0] = a * al / 255; q[1] = b * al / 255; q[2] = c * al / 255; q[3] = al; } break;
    }
  }
}
int main(void) {
  EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(d, 0, 0);
  EGLint ca[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE}; EGLConfig c; EGLint n;
  eglChooseConfig(d, ca, &c, 1, &n);
  EGLint sa[] = {EGL_WIDTH, W, EGL_HEIGHT, W, EGL_NONE}; EGLSurface s = eglCreatePbufferSurface(d, c, sa);
  EGLint xa[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
  eglMakeCurrent(d, s, s, eglCreateContext(d, c, EGL_NO_CONTEXT, xa));
  GLuint p = glCreateProgram();
  glAttachShader(p, sh(GL_VERTEX_SHADER, "#version 300 es\nout vec2 uv;void main(){vec2 q=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));uv=q;gl_Position=vec4(q*2.0-1.0,0,1);}"));
  glAttachShader(p, sh(GL_FRAGMENT_SHADER, "#version 300 es\nprecision highp float;uniform sampler2D t;in vec2 uv;out vec4 o;void main(){o=texture(t,uv);}"));
  glLinkProgram(p); glUseProgram(p);
  GLuint vao; glGenVertexArrays(1, &vao); glBindVertexArray(vao);
  GLuint fb, rt; glGenTextures(1, &rt); glBindTexture(GL_TEXTURE_2D, rt); glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, W, W);
  glGenFramebuffers(1, &fb); glBindFramebuffer(GL_FRAMEBUFFER, fb); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, rt, 0);
  static uint8_t src[N][64], etc[N][16], img[W * W * 4];
  srand(7);
  for (int b = 0; b < N; b++) {
    make_block(b % KINDS, src[b]);
    uint8_t rgba[64]; memcpy(rgba, src[b], 64);
    etc2EncodeAlphaBlock(rgba, etc[b]);               // as h_compress_dxt_block with alpha
    for (int i = 0; i < 16; i++) rgba[i * 4 + 3] = 255;
    etc2EncodeColorBlock(rgba, etc[b] + 8);
  }
  GLuint tex; glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
  glCompressedTexImage2D(GL_TEXTURE_2D, 0, 0x9278, W, W, 0, sizeof etc, etc);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glViewport(0, 0, W, W); glDrawArrays(GL_TRIANGLES, 0, 3);
  glReadPixels(0, 0, W, W, GL_RGBA, GL_UNSIGNED_BYTE, img);
  int worst[KINDS] = {0}, worst_a[KINDS] = {0};
  double sum[KINDS] = {0};
  for (int b = 0; b < N; b++) {
    int bx = b % BW, by = b / BW, m = 0, ma = 0;
    for (int i = 0; i < 16; i++) {
      const uint8_t *g = img + ((by * 4 + i / 4) * W + bx * 4 + i % 4) * 4, *o = src[b] + i * 4;
      for (int ch = 0; ch < 3; ch++) { int e = abs(g[ch] - o[ch]); if (e > m) m = e; sum[b % KINDS] += e; }
      int e = abs(g[3] - o[3]); if (e > ma) ma = e;
    }
    if (m > worst[b % KINDS]) worst[b % KINDS] = m;
    if (ma > worst_a[b % KINDS]) worst_a[b % KINDS] = ma;
  }
  for (int k = 0; k < KINDS; k++)
    printf("%-13s max colour error %3d, mean %.1f, max alpha error %3d\n", kind_name[k], worst[k],
           sum[k] / (N / KINDS) / 48, worst_a[k]);
  // readback as fngl.c does it: blocks copied into an RGBA32UI texture (one texel per block), read as integers
  GLuint tmp; glGenTextures(1, &tmp); glBindTexture(GL_TEXTURE_2D, tmp); glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32UI, BW, BW);
  glCopyImageSubData(tex, GL_TEXTURE_2D, 0, 0, 0, 0, tmp, GL_TEXTURE_2D, 0, 0, 0, 0, BW, BW, 1);
  GLuint rfb; glGenFramebuffers(1, &rfb); glBindFramebuffer(GL_READ_FRAMEBUFFER, rfb);
  glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tmp, 0);
  static uint8_t back[N][16];
  glReadPixels(0, 0, BW, BW, GL_RGBA_INTEGER, GL_UNSIGNED_INT, back);
  int diff = 0;
  for (int b = 0; b < N; b++) diff += memcmp(back[b], etc[b], 16) != 0;
  printf("readback: %d of %d blocks differ\n", diff, N);
  // ETC2 -> ETC2 copy (sprites moved between atlases), drawn and compared with the original drawing
  GLuint t2; glGenTextures(1, &t2); glBindTexture(GL_TEXTURE_2D, t2); glTexStorage2D(GL_TEXTURE_2D, 1, 0x9278, W, W);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glCopyImageSubData(tex, GL_TEXTURE_2D, 0, 0, 0, 0, t2, GL_TEXTURE_2D, 0, 0, 0, 0, W, W, 1);
  glBindFramebuffer(GL_FRAMEBUFFER, fb);
  glDrawArrays(GL_TRIANGLES, 0, 3);
  static uint8_t img2[W * W * 4];
  glReadPixels(0, 0, W, W, GL_RGBA, GL_UNSIGNED_BYTE, img2);
  int px = 0;
  for (int i = 0; i < W * W; i++) px += memcmp(img + i * 4, img2 + i * 4, 4) != 0;
  printf("ETC2 -> ETC2 copy: %d of %d pixels differ\n", px, W * W);
  printf("gl error 0x%x\n", glGetError());
  return 0;
}
