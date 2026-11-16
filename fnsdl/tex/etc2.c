// BC (S3TC) decoder and ETC2/EAC encoder, block for block.
// Taken from the Factory project (Gladio server, etc2_transcode.c, same
// author's GPL-3.0 project); thread pool part removed, used by fngl_tex.c.
#include <stdlib.h>
#include <string.h>

#include "etc2.h"

#define GL_COMPRESSED_RGB_S3TC_DXT1 0x83F0
#define GL_COMPRESSED_RGBA_S3TC_DXT1 0x83F1
#define GL_COMPRESSED_RGBA_S3TC_DXT3 0x83F2
#define GL_COMPRESSED_RGBA_S3TC_DXT5 0x83F3

uint32_t etc2FormatFor(uint32_t bcFormat) {
    switch (bcFormat) {
        case GL_COMPRESSED_RGB_S3TC_DXT1: return GL_COMPRESSED_RGB8_ETC2_FORMAT;
        case GL_COMPRESSED_RGBA_S3TC_DXT1:
        case GL_COMPRESSED_RGBA_S3TC_DXT3:
        case GL_COMPRESSED_RGBA_S3TC_DXT5: return GL_COMPRESSED_RGBA8_ETC2_EAC_FORMAT;
        default: return 0;
    }
}

int etc2BlockBytes(uint32_t etc2Format) {
    return etc2Format == GL_COMPRESSED_RGB8_ETC2_FORMAT ? 8 : 16;
}

static inline int clamp255(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

// ---- BC block -> 16 RGBA pixels (row by row) ----

static void decodeColor(const uint8_t* b, uint8_t* rgba, bool threeColorMode, bool alphaForBlack) {
    uint16_t c0 = b[0] | b[1] << 8, c1 = b[2] | b[3] << 8;
    uint8_t c[4][4];
    for (int i = 0; i < 2; i++) {
        uint16_t v = i ? c1 : c0;
        int r = (v >> 11) & 31, g = (v >> 5) & 63, bl = v & 31;
        c[i][0] = r << 3 | r >> 2; c[i][1] = g << 2 | g >> 4; c[i][2] = bl << 3 | bl >> 2; c[i][3] = 255;
    }
    if (!threeColorMode || c0 > c1) {
        for (int k = 0; k < 3; k++) {
            c[2][k] = (2 * c[0][k] + c[1][k]) / 3;
            c[3][k] = (c[0][k] + 2 * c[1][k]) / 3;
        }
        c[2][3] = c[3][3] = 255;
    }
    else {
        for (int k = 0; k < 3; k++) {
            c[2][k] = (c[0][k] + c[1][k]) / 2;
            c[3][k] = 0;
        }
        c[2][3] = 255;
        c[3][3] = alphaForBlack ? 0 : 255;
    }
    uint32_t bits = b[4] | b[5] << 8 | b[6] << 16 | (uint32_t)b[7] << 24;
    for (int i = 0; i < 16; i++) memcpy(rgba + i * 4, c[(bits >> (2 * i)) & 3], 4);
}

static void decodeAlphaBC4(const uint8_t* b, uint8_t* rgba) {
    uint8_t p[8];
    p[0] = b[0]; p[1] = b[1];
    if (p[0] > p[1]) {
        for (int i = 1; i < 7; i++) p[i + 1] = (uint8_t)(((7 - i) * p[0] + i * p[1]) / 7);
    }
    else {
        for (int i = 1; i < 5; i++) p[i + 1] = (uint8_t)(((5 - i) * p[0] + i * p[1]) / 5);
        p[6] = 0; p[7] = 255;
    }
    uint64_t bits = 0;
    for (int i = 0; i < 6; i++) bits |= (uint64_t)b[2 + i] << (8 * i);
    for (int i = 0; i < 16; i++) rgba[i * 4 + 3] = p[(bits >> (3 * i)) & 7];
}

void etc2DecodeBCBlock(uint32_t bcFormat, const uint8_t* block, uint8_t rgba[64]) {
    switch (bcFormat) {
        case GL_COMPRESSED_RGB_S3TC_DXT1:
            decodeColor(block, rgba, true, false);
            break;
        case GL_COMPRESSED_RGBA_S3TC_DXT1:
            decodeColor(block, rgba, true, true);
            break;
        case GL_COMPRESSED_RGBA_S3TC_DXT3:
            decodeColor(block + 8, rgba, false, false);
            for (int i = 0; i < 16; i++) rgba[i * 4 + 3] = ((block[i / 2] >> (4 * (i & 1))) & 15) * 17;
            break;
        case GL_COMPRESSED_RGBA_S3TC_DXT5:
            decodeColor(block + 8, rgba, false, false);
            decodeAlphaBC4(block, rgba);
            break;
    }
}

// ---- ETC1-compatible colour block (individual / differential mode, both flips) ----

static const int etcModifiers[8][2] = {{2, 8}, {5, 17}, {9, 29}, {13, 42}, {18, 60}, {24, 80}, {33, 106}, {47, 183}};

// pixel numbers of the two halves: flip 0 = left / right columns, flip 1 = top / bottom rows (pixel = y * 4 + x)
static void halfPixels(int flip, int half, int out[8]) {
    int n = 0;
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            int inHalf = flip ? (y >> 1) : (x >> 1);
            if (inHalf == half) out[n++] = y * 4 + x;
        }
    }
}

// best table and modifier per pixel for one half around `base`; returns the error (alpha 0 pixels do not count).
// Without clamping the error of modifier m is S + 2*m*D + 3*m*m (D = sum of base - pixel over r, g, b; S = its squares):
// exact and cheap while base +- the table's largest modifier stays inside 0..255, the per-channel loop otherwise.
static int fitHalf(const uint8_t* rgba, const int pixels[8], const int base[3], const uint8_t weight[16], int* outTable, uint8_t outIndex[8]) {
    int D[8], S[8];
    int lo = base[0] < base[1] ? (base[0] < base[2] ? base[0] : base[2]) : (base[1] < base[2] ? base[1] : base[2]);
    int hi = base[0] > base[1] ? (base[0] > base[2] ? base[0] : base[2]) : (base[1] > base[2] ? base[1] : base[2]);
    for (int i = 0; i < 8; i++) {
        const uint8_t* p = rgba + pixels[i] * 4;
        int d0 = base[0] - p[0], d1 = base[1] - p[1], d2 = base[2] - p[2];
        D[i] = d0 + d1 + d2;
        S[i] = d0 * d0 + d1 * d1 + d2 * d2;
    }
    // the tables whose middle modifier is nearest the pixels' mean distance from the base (|D| / 3), and their neighbours:
    // the error over the 8 tables has one valley, so 3 of them find it (2.5x faster than all 8)
    int meanOffset = 0;
    for (int i = 0; i < 8; i++) meanOffset += D[i] < 0 ? -D[i] : D[i];
    meanOffset = meanOffset / 24;
    int nearest = 0;
    for (int t = 1; t < 8; t++) {
        int mt = (etcModifiers[t][0] + etcModifiers[t][1]) / 2, mn = (etcModifiers[nearest][0] + etcModifiers[nearest][1]) / 2;
        if (abs(mt - meanOffset) < abs(mn - meanOffset)) nearest = t;
    }
    int firstTable = nearest > 0 ? nearest - 1 : 0, lastTable = nearest < 7 ? nearest + 1 : 7;
    int bestError = 0x7fffffff;
    for (int t = firstTable; t <= lastTable; t++) {
        int mods[4] = {etcModifiers[t][0], etcModifiers[t][1], -etcModifiers[t][0], -etcModifiers[t][1]};
        // per modifier: inside 0..255 for all three channels -> the formula, else per channel with clamping
        bool inRange[4];
        int clamped[4][3];
        for (int m = 0; m < 4; m++) {
            inRange[m] = lo + mods[m] >= 0 && hi + mods[m] <= 255;
            for (int k = 0; k < 3; k++) clamped[m][k] = clamp255(base[k] + mods[m]);
        }
        int error = 0;
        uint8_t index[8];
        for (int i = 0; i < 8 && error < bestError; i++) {
            int best = 0x7fffffff, bestM = 0;
            for (int m = 0; m < 4; m++) {
                int e;
                if (inRange[m]) e = S[i] + 2 * mods[m] * D[i] + 3 * mods[m] * mods[m];
                else {
                    const uint8_t* p = rgba + pixels[i] * 4;
                    int dr = clamped[m][0] - p[0], dg = clamped[m][1] - p[1], db = clamped[m][2] - p[2];
                    e = dr * dr + dg * dg + db * db;
                }
                if (e < best) { best = e; bestM = m; }
            }
            index[i] = bestM;
            error += best * weight[pixels[i]];
        }
        if (error < bestError) {
            bestError = error;
            *outTable = t;
            memcpy(outIndex, index, 8);
        }
    }
    return bestError;
}

static void averageHalf(const uint8_t* rgba, const int pixels[8], const uint8_t weight[16], int avg[3]) {
    int sum[3] = {0, 0, 0}, count = 0;
    for (int i = 0; i < 8; i++) {
        int w = weight[pixels[i]];
        for (int k = 0; k < 3; k++) sum[k] += rgba[pixels[i] * 4 + k] * w;
        count += w;
    }
    for (int k = 0; k < 3; k++) avg[k] = count ? (sum[k] + count / 2) / count : 0;
}

static void writeColorBlock(uint8_t out[8], bool diff, int flip, const int q0[3], const int q1[3], int t0, int t1,
                            const int pixels0[8], const uint8_t index0[8], const int pixels1[8], const uint8_t index1[8]) {
    for (int k = 0; k < 3; k++) out[k] = diff ? (q0[k] << 3 | ((q1[k] - q0[k]) & 7)) : (q0[k] << 4 | q1[k]);
    out[3] = t0 << 5 | t1 << 2 | (diff ? 2 : 0) | flip;
    uint32_t msb = 0, lsb = 0;
    for (int h = 0; h < 2; h++) {
        const int* pixels = h ? pixels1 : pixels0;
        const uint8_t* index = h ? index1 : index0;
        for (int i = 0; i < 8; i++) {
            int y = pixels[i] >> 2, x = pixels[i] & 3;
            int bit = x * 4 + y; // ETC numbers pixels column by column
            // modifier number: 0 = +small, 1 = +large, 2 = -small, 3 = -large -> (msb, lsb)
            static const uint8_t code[4] = {0, 1, 2, 3};
            int c = code[index[i]];
            msb |= (uint32_t)(c >> 1) << bit;
            lsb |= (uint32_t)(c & 1) << bit;
        }
    }
    uint32_t bits = msb << 16 | lsb;
    out[4] = bits >> 24; out[5] = bits >> 16; out[6] = bits >> 8; out[7] = bits;
}

void etc2EncodeColorBlock(const uint8_t rgba[64], uint8_t out[8]) {
    // colours of fully transparent pixels do not matter (unless the whole block is transparent)
    uint8_t weight[16];
    bool anyOpaque = false;
    for (int i = 0; i < 16; i++) anyOpaque |= rgba[i * 4 + 3] != 0;
    for (int i = 0; i < 16; i++) weight[i] = !anyOpaque || rgba[i * 4 + 3] != 0;

    // the flip whose halves are the most uniform (spread around the half averages), checked cheaply; trying both
    // flips and both modes in full cost 4x the time for ~0.3 dB (Factorio's sprites took 440 s instead of 40 on the stand)
    int bestError = 0x7fffffff;
    int flipPixels[2][2][8], flipAvg[2][2][3], spread[2] = {0, 0};
    for (int flip = 0; flip < 2; flip++) {
        for (int h = 0; h < 2; h++) {
            halfPixels(flip, h, flipPixels[flip][h]);
            averageHalf(rgba, flipPixels[flip][h], weight, flipAvg[flip][h]);
            for (int i = 0; i < 8; i++) {
                const uint8_t* p = rgba + flipPixels[flip][h][i] * 4;
                for (int k = 0; k < 3; k++) {
                    int d = p[k] - flipAvg[flip][h][k];
                    spread[flip] += d * d * weight[flipPixels[flip][h][i]];
                }
            }
        }
    }
    int flip = spread[1] < spread[0];
    {
        int (*pixels)[8] = flipPixels[flip];
        int (*avg)[3] = flipAvg[flip];
        bool diffDone = false;
        for (int diff = 1; diff >= 0 && !diffDone; diff--) {
            // pass 0: the halves' average colours; pass 1: refined, base = mean of (pixel - its chosen modifier)
            int target[2][3];
            memcpy(target, avg, sizeof(target));
            for (int pass = 0; pass < 2; pass++) {
                int q[2][3], base[2][3];
                bool ok = true;
                for (int h = 0; h < 2; h++) {
                    for (int k = 0; k < 3; k++) {
                        int v = clamp255(target[h][k]);
                        if (diff) {
                            q[h][k] = (v * 31 + 127) / 255;
                            base[h][k] = q[h][k] << 3 | q[h][k] >> 2;
                        }
                        else {
                            q[h][k] = (v * 15 + 127) / 255;
                            base[h][k] = q[h][k] << 4 | q[h][k];
                        }
                    }
                }
                if (diff) {
                    // the second colour as an offset of -4..3 from the first; else ETC2 reads another mode
                    for (int k = 0; k < 3; k++) ok &= q[1][k] - q[0][k] >= -4 && q[1][k] - q[0][k] <= 3;
                    if (!ok) break;
                }
                int table[2];
                uint8_t index[2][8];
                int error = fitHalf(rgba, pixels[0], base[0], weight, &table[0], index[0]);
                error += fitHalf(rgba, pixels[1], base[1], weight, &table[1], index[1]);
                if (error < bestError) {
                    bestError = error;
                    writeColorBlock(out, diff, flip, q[0], q[1], table[0], table[1], pixels[0], index[0], pixels[1], index[1]);
                }
                if (bestError == 0) return;
                if (diff) diffDone = true; // individual mode only where the colours are too far apart for diff
                if (pass == 0) {
                    for (int h = 0; h < 2; h++) {
                        int mods[4] = {etcModifiers[table[h]][0], etcModifiers[table[h]][1], -etcModifiers[table[h]][0], -etcModifiers[table[h]][1]};
                        int sum[3] = {0, 0, 0}, count = 0;
                        for (int i = 0; i < 8; i++) {
                            int w = weight[pixels[h][i]];
                            for (int k = 0; k < 3; k++) sum[k] += (rgba[pixels[h][i] * 4 + k] - mods[index[h][i]]) * w;
                            count += w;
                        }
                        for (int k = 0; k < 3; k++) target[h][k] = count ? (sum[k] + (sum[k] >= 0 ? count / 2 : -count / 2)) / count : avg[h][k];
                    }
                }
            }
        }
    }
}

// ---- EAC alpha (the first half of an ETC2 RGBA8 block) ----

static const int eacModifiers[16][8] = {
    {-3, -6, -9, -15, 2, 5, 8, 14}, {-3, -7, -10, -13, 2, 6, 9, 12}, {-2, -5, -8, -13, 1, 4, 7, 12}, {-2, -4, -6, -13, 1, 3, 5, 12},
    {-3, -6, -8, -12, 2, 5, 7, 11}, {-3, -7, -9, -11, 2, 6, 8, 10}, {-4, -7, -8, -11, 3, 6, 7, 10}, {-3, -5, -8, -11, 2, 4, 7, 10},
    {-2, -6, -8, -10, 1, 5, 7, 9}, {-2, -5, -8, -10, 1, 4, 7, 9}, {-2, -4, -8, -10, 1, 3, 7, 9}, {-2, -5, -7, -10, 1, 4, 6, 9},
    {-3, -4, -7, -10, 2, 3, 6, 9}, {-1, -2, -3, -10, 0, 1, 2, 9}, {-4, -6, -8, -9, 3, 5, 7, 8}, {-3, -5, -7, -9, 2, 4, 6, 8}};

static int fitAlpha(const uint8_t a[16], int base, int mult, int t, uint8_t index[16]) {
    int values[8];
    for (int m = 0; m < 8; m++) values[m] = clamp255(base + eacModifiers[t][m] * mult);
    int error = 0;
    for (int i = 0; i < 16; i++) {
        int best = 0x7fffffff, bestM = 0;
        for (int m = 0; m < 8; m++) {
            int d = values[m] - a[i];
            if (d * d < best) { best = d * d; bestM = m; }
        }
        index[i] = bestM;
        error += best;
    }
    return error;
}

void etc2EncodeAlphaBlock(const uint8_t rgba[64], uint8_t out[8]) {
    uint8_t a[16];
    int lo = 255, hi = 0;
    for (int i = 0; i < 16; i++) {
        a[i] = rgba[i * 4 + 3];
        if (a[i] < lo) lo = a[i];
        if (a[i] > hi) hi = a[i];
    }
    int bestBase = lo, bestMult = 1, bestTable = 13, bestError = 0x7fffffff;
    uint8_t bestIndex[16];
    if (lo == hi) {
        // table 13 has a 0 modifier: exact (most blocks of a sprite are fully opaque or fully transparent)
        memset(bestIndex, 4, sizeof(bestIndex));
    }
    else {
        for (int t = 0; t < 16 && bestError > 0; t++) {
            int span = eacModifiers[t][7] - eacModifiers[t][3];
            int mid = (eacModifiers[t][7] + eacModifiers[t][3]);
            int idealMult = (hi - lo + span / 2) / span;
            for (int mult = idealMult; mult <= idealMult + 1; mult++) {
                if (mult < 1 || mult > 15) continue;
                // base so that the table's range is centred on [lo, hi]
                int base = clamp255(((lo + hi) - mid * mult) / 2);
                uint8_t index[16];
                int error = fitAlpha(a, base, mult, t, index);
                if (error < bestError) {
                    bestError = error; bestBase = base; bestMult = mult; bestTable = t;
                    memcpy(bestIndex, index, 16);
                }
            }
        }
        // the base refined for the chosen table and multiplier: the mean of (alpha - its modifier)
        int sum = 0;
        for (int i = 0; i < 16; i++) sum += a[i] - eacModifiers[bestTable][bestIndex[i]] * bestMult;
        int base = clamp255((sum + (sum >= 0 ? 8 : -8)) / 16);
        if (base != bestBase) {
            uint8_t index[16];
            int error = fitAlpha(a, base, bestMult, bestTable, index);
            if (error < bestError) {
                bestError = error; bestBase = base;
                memcpy(bestIndex, index, 16);
            }
        }
    }
    out[0] = bestBase;
    out[1] = bestMult << 4 | bestTable;
    uint64_t bits = 0;
    for (int x = 0; x < 4; x++) {
        for (int y = 0; y < 4; y++) bits = bits << 3 | bestIndex[y * 4 + x]; // column by column, first pixel highest
    }
    for (int i = 0; i < 6; i++) out[2 + i] = bits >> (40 - 8 * i);
}

