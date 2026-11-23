// Vulkan renderer (FN_RENDERER=vulkan): the GL functions the game uses, implemented on Vulkan (vk_gl.c).
#pragma once

int fnvk_active(void);                                // FN_RENDERER=vulkan and a usable device
void *fnvk_get_proc(const char *name);                // NULL: not implemented
void fnvk_set_window_size_fn(void (*fn)(int *w, int *h));
void fnvk_swap(void);                                 // end of frame (SDL_GL_SwapWindow)
int fnvk_read_window(int w, int h, void *rgba);       // last frame, GL row order (screenshots)
void fnvk_set_native_window(void *anw);                // Android: present to this ANativeWindow (NULL: paused)
