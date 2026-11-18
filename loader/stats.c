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
static _Atomic unsigned frame_pos;  // also the total number of frames

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

// Keeps argument register x<reg> of every call to [symbol] in *dst (the last call wins).
int fn_hook_store(fn_image *img, const char *symbol, int reg, volatile uint64_t *dst) {
  uintptr_t fn = fn_find_symbol(img, symbol);
  if (!fn) return -1;
  uint32_t *site = (uint32_t *)fn;
  if (pc_relative(*site)) { fn_log("hook %s: first instruction is pc-relative, skipped", symbol); return -1; }
  if (img->tramp_used + 40 > img->tramp_size) return -1;
  size_t at = (img->tramp_used + 7) & ~(size_t)7;
  uint32_t *t = (uint32_t *)(img->tramp + at);
  t[0] = 0x58000000u | (6u << 5) | 16;           // ldr x16, lit (t+24)
  t[1] = 0xf9000200u | (uint32_t)(reg & 31);     // str x<reg>, [x16]
  t[2] = *site;                                  // moved first instruction
  intptr_t d = (intptr_t)((uintptr_t)site + 4 - (uintptr_t)&t[3]);
  t[3] = 0x14000000u | ((uint32_t)(d >> 2) & 0x03ffffff);  // b fn+4
  t[4] = 0xd503201fu;                            // nop
  t[5] = 0xd503201fu;                            // nop (literal alignment)
  *(uint64_t *)&t[6] = (uint64_t)(uintptr_t)dst;
  img->tramp_used = at + 32;
  intptr_t e = (intptr_t)((uintptr_t)t - (uintptr_t)site);
  *site = 0x14000000u | ((uint32_t)(e >> 2) & 0x03ffffff);  // b trampoline
  fn_log("hook %s: keeping x%d", symbol, reg);
  return 0;
}

// As fn_hook_store, for two argument registers.
int fn_hook_store2(fn_image *img, const char *symbol, int ra, volatile uint64_t *da, int rb, volatile uint64_t *db) {
  uintptr_t fn = fn_find_symbol(img, symbol);
  if (!fn) return -1;
  uint32_t *site = (uint32_t *)fn;
  if (pc_relative(*site)) { fn_log("hook %s: first instruction is pc-relative, skipped", symbol); return -1; }
  if (img->tramp_used + 48 > img->tramp_size) return -1;
  size_t at = (img->tramp_used + 7) & ~(size_t)7;
  uint32_t *t = (uint32_t *)(img->tramp + at);
  t[0] = 0x58000000u | (6u << 5) | 16;           // ldr x16, lit a (t+24)
  t[1] = 0xf9000200u | (uint32_t)(ra & 31);      // str x<ra>, [x16]
  t[2] = 0x58000000u | (6u << 5) | 16;           // ldr x16, lit b (t+32)
  t[3] = 0xf9000200u | (uint32_t)(rb & 31);      // str x<rb>, [x16]
  t[4] = *site;                                  // moved first instruction
  intptr_t d = (intptr_t)((uintptr_t)site + 4 - (uintptr_t)&t[5]);
  t[5] = 0x14000000u | ((uint32_t)(d >> 2) & 0x03ffffff);  // b fn+4
  *(uint64_t *)&t[6] = (uint64_t)(uintptr_t)da;
  *(uint64_t *)&t[8] = (uint64_t)(uintptr_t)db;
  img->tramp_used = at + 40;
  intptr_t e = (intptr_t)((uintptr_t)t - (uintptr_t)site);
  *site = 0x14000000u | ((uint32_t)(e >> 2) & 0x03ffffff);  // b trampoline
  fn_log("hook %s: keeping x%d, x%d", symbol, ra, rb);
  return 0;
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

#ifdef __ANDROID__
// GameActivity.nativeFrames(float[] out): frame times only (for the graph,
// called often; does not touch the UPS window of nativeStats).
__attribute__((visibility("default"))) void Java_com_factorynative_GameActivity_nativeFrames(JNIEnv *env, jclass cls,
                                                                                           jfloatArray out) {
  (void)cls;
  jsize n = (*env)->GetArrayLength(env, out);
  if (n > FRAMES) n = FRAMES;
  float buf[FRAMES];
  unsigned pos = atomic_load(&frame_pos);
  for (int k = 0; k < n; k++) buf[n - 1 - k] = k < (int)pos ? frame_ms[(pos - 1 - k) % FRAMES] : 0;
  (*env)->SetFloatArrayRegion(env, out, 0, n, buf);
}
#endif

#ifdef __ANDROID__
// GameActivity.nativeCounters(long[] out): out[0] = game ticks, out[1] = frames
// (totals since start; the benchmark takes its own differences).
__attribute__((visibility("default"))) void Java_com_factorynative_GameActivity_nativeCounters(JNIEnv *env, jclass cls,
                                                                                             jlongArray out) {
  (void)cls;
  jlong v[2] = {(jlong)fn_tick_count, (jlong)atomic_load(&frame_pos)};
  (*env)->SetLongArrayRegion(env, out, 0, 2, v);
}
#endif
