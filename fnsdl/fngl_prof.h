// FNGL_PROF=1: per-frame GL call counts and driver time (fngl_prof.c).
#pragma once

int fngl_prof_enabled(void);
void *fngl_prof_wrap(const char *name, void *fn);
void fngl_prof_frame(void);
void fngl_prof_swap_begin(void);
void fngl_prof_swap_end(void);
