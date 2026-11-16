#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <stdio.h>
int main(void) {
  EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(d, 0, 0);
  EGLint ca[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE}; EGLConfig c; EGLint n;
  eglChooseConfig(d, ca, &c, 1, &n);
  EGLint sa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE}; EGLSurface s = eglCreatePbufferSurface(d, c, sa);
  EGLint xa[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
  eglMakeCurrent(d, s, s, eglCreateContext(d, c, EGL_NO_CONTEXT, xa));
  struct { const char *n; GLenum e; } q[] = {
    {"MAX_TEXTURE_BUFFER_SIZE", GL_MAX_TEXTURE_BUFFER_SIZE}, {"TEXTURE_BUFFER_OFFSET_ALIGNMENT", GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT},
    {"MAX_UNIFORM_BLOCK_SIZE", GL_MAX_UNIFORM_BLOCK_SIZE}, {"UNIFORM_BUFFER_OFFSET_ALIGNMENT", GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT},
    {"MAX_TEXTURE_IMAGE_UNITS", GL_MAX_TEXTURE_IMAGE_UNITS}, {"MAX_COMBINED_TEXTURE_IMAGE_UNITS", GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS},
    {"MAX_ELEMENTS_INDICES", GL_MAX_ELEMENTS_INDICES}, {"MAX_ELEMENTS_VERTICES", GL_MAX_ELEMENTS_VERTICES},
    {"MAX_VERTEX_ATTRIBS", GL_MAX_VERTEX_ATTRIBS}, {"MAX_TEXTURE_SIZE", GL_MAX_TEXTURE_SIZE}, {"MAX_ARRAY_TEXTURE_LAYERS", GL_MAX_ARRAY_TEXTURE_LAYERS},
  };
  for (unsigned i = 0; i < sizeof q / sizeof q[0]; i++) { GLint v = -1; glGetIntegerv(q[i].e, &v); printf("%s %d\n", q[i].n, v); }
  return 0;
}
