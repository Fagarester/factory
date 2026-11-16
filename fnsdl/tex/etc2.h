// BC -> RGBA decode and ETC2 / EAC block encoders (see etc2.c).
#pragma once
#include <stdbool.h>
#include <stdint.h>

#define GL_COMPRESSED_RGB8_ETC2_FORMAT 0x9274
#define GL_COMPRESSED_RGBA8_ETC2_EAC_FORMAT 0x9278

uint32_t etc2FormatFor(uint32_t bcFormat);
int etc2BlockBytes(uint32_t etc2Format);
// 16 RGBA pixels, row by row
void etc2DecodeBCBlock(uint32_t bcFormat, const uint8_t *block, uint8_t rgba[64]);
void etc2EncodeColorBlock(const uint8_t rgba[64], uint8_t out[8]);
// EAC block from the alpha channel (also usable as EAC R11 for one channel)
void etc2EncodeAlphaBlock(const uint8_t rgba[64], uint8_t out[8]);
