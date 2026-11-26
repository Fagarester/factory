// ETC2 inside the game for GPUs without BC (direct.c).
#pragma once
#include <stddef.h>
#include <stdint.h>

// Once (later calls return the first answer): replaces the game's BC block compressor by ETC2/EAC. 1 = on: every
// BC upload of the game then holds ETC2/EAC blocks of the same size (tex_direct_format). FN_ETC2_DIRECT=0: off.
int tex_direct_enable(void (*log)(const char *, ...));
int tex_direct_active(void);
// 1 when the texture upload under way holds BC data the game did not compress itself (its default textures, the
// splash screen file): it must be converted as without tex/direct.c
int tex_direct_foreign(void);
// the ETC2/EAC GL format with the block size of a BC format, 0 = none (formats the game's compressor does not write)
unsigned tex_direct_format(unsigned bc);
// EAC R11 block -> 16 8-bit values, row by row (tests)
void tex_direct_decode_r11(const uint8_t b[8], uint8_t out[16]);
// blocks written so far: BC1->ETC2, BC3->ETC2+EAC, EAC channels, YCoCg re-encoded; BC4 decodes (diagnostics)
void tex_direct_stats(unsigned long out[5]);
void tex_direct_backtrace(char *out, size_t n);
