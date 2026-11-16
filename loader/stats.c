// Frame and game-tick statistics for the HUD.
//
// Ticks: the entry of a function the game runs once per update tick
// (Scenario::update) is redirected to a trampoline that counts the call,
// runs the moved first instruction and jumps back. Frames: libfnsdl reports
// every SwapWindow through fn_stat_frame().
#include <stdatomic.h>
#include <string.h>
#include <time.h>

#include "fn.h"

volatile uint64_t fn_tick_count;

#define FRAMES 256
static float frame_ms[FRAMES];
static double frame_time[FRAMES];
static _Atomic unsigned frame_pos;

static double now_s(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec + t.tv_nsec * 1e-9;
}

__attribute__((visibility("default"))) void fn_stat_frame(double ms) {
  unsigned i = atomic_fetch_add(&frame_pos, 1) % FRAMES;
  frame_ms[i] = (float)ms;
  frame_time[i] = now_s();
}

// FPS over the last second, UPS over the last call interval (call twice a second).
void fn_stats_read(float *fps, float *ups, float *frames, int n) {
  double t = now_s();
  unsigned pos = atomic_load(&frame_pos);
  int count = 0;
  for (unsigned k = 1; k <= FRAMES && k <= pos; k++) {
    unsigned i = (pos - k) % FRAMES;
    if (t - frame_time[i] > 1.0) break;
    count++;
  }
  *fps = (float)count;
  static double last_t;
  static uint64_t last_ticks;
  uint64_t ticks = fn_tick_count;
  *ups = last_t > 0 && t > last_t ? (float)((ticks - last_ticks) / (t - last_t)) : 0;
  last_t = t;
  last_ticks = ticks;
  for (int k = 0; k < n; k++) frames[n - 1 - k] = k < (int)pos ? frame_ms[(pos - 1 - k) % FRAMES] : 0;
}

static int pc_relative(uint32_t w) {
  return (w & 0x1f000000) == 0x10000000 ||  // adr / adrp
         (w & 0x7c000000) == 0x14000000 ||  // b / bl
         (w & 0x7e000000) == 0x34000000 ||  // cbz / cbnz
         (w & 0x7e000000) == 0x36000000 ||  // tbz / tbnz
         (w & 0xff000010) == 0x54000000 ||  // b.cond
         (w & 0x3b000000) == 0x18000000;    // ldr literal
}

int fn_hook_counter(fn_image *img, const char *symbol, volatile uint64_t *counter) {
  uintptr_t fn = fn_find_symbol(img, symbol);
  if (!fn) return -1;
  uint32_t *site = (uint32_t *)fn;
  if (pc_relative(*site)) { fn_log("hook %s: first instruction is pc-relative, skipped", symbol); return -1; }
  if (img->tramp_used + 48 > img->tramp_size) return -1;
  size_t at = (img->tramp_used + 7) & ~(size_t)7;
  uint32_t *t = (uint32_t *)(img->tramp + at);
  int k = 0;
  t[k++] = 0x58000000u | (7u << 5) | 16;  // ldr x16, lit (t+28)
  t[k++] = 0xf9400211u;                   // ldr x17, [x16]
  t[k++] = 0x91000631u;                   // add x17, x17, #1
  t[k++] = 0xf9000211u;                   // str x17, [x16]
  t[k++] = *site;                         // moved first instruction
  intptr_t d = (intptr_t)((uintptr_t)site + 4 - (uintptr_t)&t[k]);
  t[k++] = 0x14000000u | ((uint32_t)(d >> 2) & 0x03ffffff);  // b fn+4
  t[k++] = 0xd503201fu;                   // nop (literal alignment)
  *(uint64_t *)&t[k] = (uint64_t)(uintptr_t)counter;  // t+28: 7 instructions in
  img->tramp_used = at + 7 * 4 + 8;
  intptr_t e = (intptr_t)((uintptr_t)t - (uintptr_t)site);
  *site = 0x14000000u | ((uint32_t)(e >> 2) & 0x03ffffff);  // b trampoline
  fn_log("hook %s: counting calls", symbol);
  return 0;
}

#ifdef __ANDROID__
#include <jni.h>
// GameActivity.nativeStats(float[] out): out[0] = FPS, out[1] = UPS,
// out[2..] = frame times in ms, oldest first.
__attribute__((visibility("default"))) void Java_com_factorynative_GameActivity_nativeStats(JNIEnv *env, jclass cls,
                                                                                          jfloatArray out) {
  (void)cls;
  jsize n = (*env)->GetArrayLength(env, out);
  if (n < 2) return;
  float buf[2 + FRAMES];
  if (n > 2 + FRAMES) n = 2 + FRAMES;
  fn_stats_read(&buf[0], &buf[1], buf + 2, n - 2);
  (*env)->SetFloatArrayRegion(env, out, 0, n, buf);
}
#endif
