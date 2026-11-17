// Load system ANGLE explicitly, create a GLES 3 context, print the renderer.
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <stdio.h>
int main(void) {
  void *egl = dlopen("/system/lib64/libEGL_angle.so", RTLD_NOW | RTLD_LOCAL);
  void *gles = dlopen("/system/lib64/libGLESv2_angle.so", RTLD_NOW | RTLD_LOCAL);
  printf("egl %p gles %p %s\n", egl, gles, (egl && gles) ? "" : dlerror());
  if (!egl || !gles) return 1;
  EGLDisplay (*getDisplay)(EGLNativeDisplayType) = dlsym(egl, "eglGetDisplay");
  EGLBoolean (*init)(EGLDisplay, EGLint *, EGLint *) = dlsym(egl, "eglInitialize");
  EGLBoolean (*choose)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *) = dlsym(egl, "eglChooseConfig");
  EGLSurface (*pbuf)(EGLDisplay, EGLConfig, const EGLint *) = dlsym(egl, "eglCreatePbufferSurface");
  EGLContext (*ctx)(EGLDisplay, EGLConfig, EGLContext, const EGLint *) = dlsym(egl, "eglCreateContext");
  EGLBoolean (*cur)(EGLDisplay, EGLSurface, EGLSurface, EGLContext) = dlsym(egl, "eglMakeCurrent");
  const GLubyte *(*str)(GLenum) = dlsym(gles, "glGetString");
  EGLDisplay d = getDisplay(EGL_DEFAULT_DISPLAY);
  EGLint ma, mi;
  if (!init(d, &ma, &mi)) { printf("eglInitialize failed\n"); return 1; }
  EGLint ca[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE}; EGLConfig c; EGLint n;
  choose(d, ca, &c, 1, &n);
  EGLint sa[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
  EGLint xa[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
  EGLSurface s = pbuf(d, c, sa);
  if (!cur(d, s, s, ctx(d, c, EGL_NO_CONTEXT, xa))) { printf("makecurrent failed\n"); return 1; }
  printf("EGL %d.%d\nGL_RENDERER %s\nGL_VERSION %s\n", ma, mi, str(GL_RENDERER), str(GL_VERSION));
  return 0;
}
