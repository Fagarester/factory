// Render scale + upscaler (fn_upscale.c).
#pragma once
#include <GLES3/gl32.h>

void fn_upscale_init(void);
int fn_upscale_active(void);
float fn_upscale_factor(void);
// real window size -> size the game sees
void fn_upscale_size(int *w, int *h);

// GL side (current context)
void fn_upscale_set_bind(void (*real_bind)(GLenum, GLuint));
void fn_upscale_bind(GLenum target, GLuint name);
GLuint fn_upscale_fbo(void);
void fn_upscale_begin(int real_w, int real_h);
void fn_upscale_save_state(void);
void fn_upscale_present(int real_w, int real_h);
void fn_upscale_restore_state(void);

// Live changes (gear menu "Графика"): request from any thread, applied after a swap on the GL thread.
void fn_upscale_request(float scale, int gsr);
int fn_upscale_apply(int real_w, int real_h);
// Vulkan: the values only (vk_window.c draws the upscale at present)
int fn_upscale_apply_values(void);
int fn_upscale_gsr(void);
