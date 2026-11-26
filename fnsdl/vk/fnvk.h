// Vulkan renderer (FN_RENDERER=vulkan or nvk): the native renderer fnsdl/nvk; window, present and GL queries in
// vk_window.c.
#pragma once

int fnvk_active(void);                                // FN_RENDERER=vulkan or nvk, a usable device and game
void *nvk_get_proc(const char *name);                 // nvk: the few GL entry points the kept game code calls
void *fnvk_get_proc(const char *name);                // the GL queries (strings, limits, timers); NULL: none
void fnvk_set_window_size_fn(void (*fn)(int *w, int *h));
void fnvk_swap(void);                                 // end of frame (SDL_GL_SwapWindow)
int fnvk_read_window(int w, int h, void *rgba);       // last frame, GL row order (screenshots)
void fnvk_set_native_window(void *anw);                // Android: present to this ANativeWindow (NULL: paused)
