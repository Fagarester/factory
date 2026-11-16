// Which constant-alpha EAC block decodes exactly: multiplier 0 vs table 13 index 4.
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <stdio.h>
#include <string.h>
static GLuint sh(GLenum t, const char *s) { GLuint x = glCreateShader(t); glShaderSource(x, 1, &s, 0); glCompileShader(x); return x; }
int main(void) {
  EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(d, 0, 0);
  EGLint ca[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE}; EGLConfig c; EGLint n;
  eglChooseConfig(d, ca, &c, 1, &n);
  EGLint sa[] = {EGL_WIDTH, 4, EGL_HEIGHT, 4, EGL_NONE}; EGLSurface s = eglCreatePbufferSurface(d, c, sa);
  EGLint xa[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_NONE};
  eglMakeCurrent(d, s, s, eglCreateContext(d, c, EGL_NO_CONTEXT, xa));
  GLuint p = glCreateProgram();
  glAttachShader(p, sh(GL_VERTEX_SHADER, "#version 300 es\nout vec2 uv;void main(){vec2 q=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));uv=q;gl_Position=vec4(q*2.0-1.0,0,1);}"));
  glAttachShader(p, sh(GL_FRAGMENT_SHADER, "#version 300 es\nprecision highp float;uniform sampler2D t;in vec2 uv;out vec4 o;void main(){o=texture(t,uv);}"));
  glLinkProgram(p); glUseProgram(p);
  GLuint vao; glGenVertexArrays(1, &vao); glBindVertexArray(vao);
  GLuint fb, rt; glGenTextures(1, &rt); glBindTexture(GL_TEXTURE_2D, rt); glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, 4, 4);
  glGenFramebuffers(1, &fb); glBindFramebuffer(GL_FRAMEBUFFER, fb); glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, rt, 0);
  uint8_t mult0[16] = {200, 0, 0, 0, 0, 0, 0, 0};
  uint8_t t13[16] = {200, 0x1d, 0x92, 0x49, 0x24, 0x92, 0x49, 0x24};
  uint8_t *blocks[2] = {mult0, t13}; const char *names[2] = {"multiplier 0", "table 13 idx 4"};
  for (int k = 0; k < 2; k++) {
    GLuint tex; glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
    glCompressedTexImage2D(GL_TEXTURE_2D, 0, 0x9278, 4, 4, 0, 16, blocks[k]);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST); glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glViewport(0, 0, 4, 4); glDrawArrays(GL_TRIANGLES, 0, 3);
    uint8_t px[64]; glReadPixels(0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, px);
    printf("%s: alpha", names[k]); for (int i = 0; i < 16; i++) printf(" %d", px[i * 4 + 3]); printf("  (gl err 0x%x)\n", glGetError());
  }
  return 0;
}
