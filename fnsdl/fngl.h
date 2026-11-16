// GL function table for Factorio's glad loader: GLES 3.2 driver functions
// directly, plus small adapters where desktop GL 3.3 core differs.
#pragma once

void fngl_init(void *(*egl_get_proc)(const char *));
void fngl_note_requested_profile(int profile);
void *fngl_get_proc(const char *name);
