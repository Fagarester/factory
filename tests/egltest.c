// Offscreen EGL/GLES check from a plain bionic executable.
#include <EGL/egl.h>
#include <GLES3/gl32.h>
#include <stdio.h>
int main(void) {
  EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  EGLint maj, min;
  if (!eglInitialize(d, &maj, &min)) { printf("eglInitialize failed 0x%x\n", eglGetError()); return 1; }
  printf("EGL %d.%d %s\n", maj, min, eglQueryString(d, EGL_VENDOR));
  EGLint ca[] = {EGL_RENDERABLE_TYPE, 0x40 /*ES3*/, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_NONE};
  EGLConfig c; EGLint n;
  eglChooseConfig(d, ca, &c, 1, &n);
  EGLint sa[] = {EGL_WIDTH, 64, EGL_HEIGHT, 64, EGL_NONE};
  EGLSurface s = eglCreatePbufferSurface(d, c, sa);
  EGLint xa[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
  eglBindAPI(EGL_OPENGL_ES_API);
  EGLContext x = eglCreateContext(d, c, EGL_NO_CONTEXT, xa);
  if (!eglMakeCurrent(d, s, s, x)) { printf("makecurrent failed 0x%x\n", eglGetError()); return 1; }
  printf("GL_RENDERER %s\nGL_VERSION %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
  glClearColor(1, 0.5f, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
  unsigned char px[4]; glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
  printf("pixel %d %d %d %d\n", px[0], px[1], px[2], px[3]);
  return 0;
}
