#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <stdio.h>
int main(void) {
  EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY); eglInitialize(d, 0, 0);
  EGLint ca[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE}; EGLConfig c; EGLint n;
  eglChooseConfig(d, ca, &c, 1, &n);
  EGLint sa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE}; EGLSurface s = eglCreatePbufferSurface(d, c, sa);
  EGLint xa[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
  EGLContext x = eglCreateContext(d, c, EGL_NO_CONTEXT, xa); eglMakeCurrent(d, s, s, x);
  GLint num; glGetIntegerv(GL_NUM_EXTENSIONS, &num);
  for (int i = 0; i < num; i++) printf("%s\n", glGetStringi(GL_EXTENSIONS, i));
  return 0;
}
