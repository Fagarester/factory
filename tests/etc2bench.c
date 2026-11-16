// Single-thread speed of the BC3 -> ETC2 block conversion.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "../fnsdl/tex/etc2.h"
static double now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return t.tv_sec + t.tv_nsec * 1e-9; }
int main(void) {
  enum { N = 200000 };
  uint8_t *src = malloc(N * 16), out[16], rgba[64];
  srand(1);
  for (int i = 0; i < N * 16; i++) src[i] = (uint8_t)(rand() >> 7);
  double t0 = now();
  for (int i = 0; i < N; i++) etc2DecodeBCBlock(0x83F3, src + i * 16, rgba);
  double t1 = now();
  for (int i = 0; i < N; i++) { etc2DecodeBCBlock(0x83F3, src + i * 16, rgba); etc2EncodeColorBlock(rgba, out + 8); }
  double t2 = now();
  for (int i = 0; i < N; i++) { etc2DecodeBCBlock(0x83F3, src + i * 16, rgba); etc2EncodeAlphaBlock(rgba, out); }
  double t3 = now();
  printf("decode %.0f ns/block, decode+color %.0f ns, decode+alpha %.0f ns\n", (t1 - t0) / N * 1e9, (t2 - t1) / N * 1e9, (t3 - t2) / N * 1e9);
  return 0;
}
