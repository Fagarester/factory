// BC readback check (cache-sprite-atlas defect, 2026-11-19): random BC1/BC3 blocks uploaded at the game's atlas
// sizes and mip levels, read back the way fngl.c a_glGetCompressedTexImage does (glCopyImageSubData into
// RG32UI/RGBA32UI, glReadPixels as RGBA_INTEGER), compared byte by byte.
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static GLuint fbo;

static int readback(GLuint tex, GLint level, int w, int h, int bb, uint8_t *out) {
  int bw = (w + 3) / 4, bh = (h + 3) / 4;
  GLuint tmp;
  glGenTextures(1, &tmp);
  glBindTexture(GL_TEXTURE_2D, tmp);
  glTexStorage2D(GL_TEXTURE_2D, 1, bb == 8 ? GL_RG32UI : GL_RGBA32UI, bw, bh);
  int tile = getenv("TILE") ? atoi(getenv("TILE")) : 0;  // copy in tiles of tile x tile blocks (0: whole)
  if (!tile) glCopyImageSubData(tex, GL_TEXTURE_2D, level, 0, 0, 0, tmp, GL_TEXTURE_2D, 0, 0, 0, 0, w, h, 1);
  else {
    // whole blocks in tiles; the level's partial edge blocks (w or h not a multiple of 4) in calls of their own
    int fw = w / 4, fh = h / 4;  // whole blocks
    for (int by = 0; by < bh; by += tile)
      for (int bx = 0; bx < bw; bx += tile) {
        int cw = bx + tile > fw ? fw - bx : tile, ch = by + tile > fh ? fh - by : tile;
        if (cw > 0 && ch > 0)
          glCopyImageSubData(tex, GL_TEXTURE_2D, level, bx * 4, by * 4, 0, tmp, GL_TEXTURE_2D, 0, bx, by, 0, cw * 4, ch * 4, 1);
      }
    if (w % 4)  // right edge column of partial blocks
      for (int by = 0; by < fh; by += tile) {
        int ch = by + tile > fh ? fh - by : tile;
        glCopyImageSubData(tex, GL_TEXTURE_2D, level, fw * 4, by * 4, 0, tmp, GL_TEXTURE_2D, 0, fw, by, 0, w % 4, ch * 4, 1);
      }
    if (h % 4)  // bottom edge row
      for (int bx = 0; bx < fw; bx += tile) {
        int cw = bx + tile > fw ? fw - bx : tile;
        glCopyImageSubData(tex, GL_TEXTURE_2D, level, bx * 4, fh * 4, 0, tmp, GL_TEXTURE_2D, 0, bx, fh, 0, cw * 4, h % 4, 1);
      }
    if (w % 4 && h % 4)  // the corner block
      glCopyImageSubData(tex, GL_TEXTURE_2D, level, fw * 4, fh * 4, 0, tmp, GL_TEXTURE_2D, 0, fw, fh, 0, w % 4, h % 4, 1);
  }
  if (!fbo) glGenFramebuffers(1, &fbo);
  glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
  glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tmp, 0);
  uint32_t *px = malloc((size_t)bw * bh * 16);
  glPixelStorei(GL_PACK_ALIGNMENT, 4);
  glReadPixels(0, 0, bw, bh, GL_RGBA_INTEGER, GL_UNSIGNED_INT, px);
  size_t n = (size_t)bw * bh;
  if (bb == 8)
    for (size_t i = 0; i < n; i++) { px[i * 2] = px[i * 4]; px[i * 2 + 1] = px[i * 4 + 1]; }
  memcpy(out, px, n * bb);
  free(px);
  glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, 0, 0);
  glDeleteTextures(1, &tmp);
  return glGetError();
}

int main(void) {
  EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  eglInitialize(d, NULL, NULL);
  EGLint ca[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
  EGLConfig c; EGLint nc;
  eglChooseConfig(d, ca, &c, 1, &nc);
  EGLint sa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
  EGLSurface s = eglCreatePbufferSurface(d, c, sa);
  EGLint xa[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
  eglBindAPI(EGL_OPENGL_ES_API);
  EGLContext x = eglCreateContext(d, c, EGL_NO_CONTEXT, xa);
  if (!eglMakeCurrent(d, s, s, x)) { printf("no context\n"); return 1; }
  printf("%s\n", glGetString(GL_RENDERER));
  struct { int w, h; GLenum f; int bb, levels; } cases[] = {
    {8192, 8184, 0x83F3, 16, 1}, {8192, 8180, 0x83F1, 8, 1}, {8192, 5616, 0x83F3, 16, 4}, {4096, 1284, 0x83F1, 8, 1},
    {8192, 8096, 0x83F1, 8, 4}, {4096, 1632, 0x83F3, 16, 4}, {1000, 600, 0x83F3, 16, 3},
  };
  if (getenv("READTEST")) {  // glReadPixels alone: RGBA32UI 2048 x 2046 uploaded, read back
    int bw = 2048, bh = 2046;
    size_t n = (size_t)bw * bh * 4;
    uint32_t *in = malloc(n * 4), *out = calloc(n, 4);
    for (size_t i = 0; i < n; i++) in[i] = (uint32_t)rand() * 2654435761u;
    GLuint t; glGenTextures(1, &t); glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32UI, bw, bh);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bw, bh, GL_RGBA_INTEGER, GL_UNSIGNED_INT, in);
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, t, 0);
    glReadPixels(0, 0, bw, bh, GL_RGBA_INTEGER, GL_UNSIGNED_INT, out);
    size_t diff = 0; for (size_t i = 0; i < n; i++) diff += in[i] != out[i];
    printf("readPixels RGBA32UI %dx%d: %zu words differ\n", bw, bh, diff);
    return 0;
  }
  int bad = 0;
  srand(1);
  for (unsigned k = 0; k < sizeof cases / sizeof cases[0]; k++) {
    GLuint tex;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, cases[k].levels, cases[k].f, cases[k].w, cases[k].h);
    for (int l = 0; l < cases[k].levels; l++) {
      int w = cases[k].w >> l, h = cases[k].h >> l;
      if (w < 1) w = 1;
      if (h < 1) h = 1;
      size_t size = (size_t)((w + 3) / 4) * ((h + 3) / 4) * cases[k].bb;
      uint8_t *in = malloc(size), *out = calloc(1, size);
      for (size_t i = 0; i < size; i++) in[i] = (uint8_t)rand();
      glBindTexture(GL_TEXTURE_2D, tex);
      glCompressedTexSubImage2D(GL_TEXTURE_2D, l, 0, 0, w, h, cases[k].f, (GLsizei)size, in);
      int e = readback(tex, l, w, h, cases[k].bb, out);
      size_t diff = 0, first = (size_t)-1;
      for (size_t i = 0; i < size; i++) if (in[i] != out[i]) { if (first == (size_t)-1) first = i; diff++; }
      printf("%dx%d fmt 0x%x level %d (%dx%d): %s, %zu bytes differ%s (gl error 0x%x)\n", cases[k].w, cases[k].h,
             cases[k].f, l, w, h, diff ? "BAD" : "ok", diff, diff ? "" : "", e);
      if (diff) {
        bad++;
        size_t blk = first / cases[k].bb, bw = (w + 3) / 4;
        printf("   first difference at block %zu (x %zu, y %zu)\n", blk, blk % bw, blk / bw);
      }
      free(in);
      free(out);
    }
    glDeleteTextures(1, &tex);
  }
  printf(bad ? "READBACK BROKEN in %d cases\n" : "all readbacks exact\n", bad);
  return bad != 0;
}
