// The game tick (Scenario::update or its successor): counted for the HUD's UPS, and the UPS limit (gear menu,
// "Производительность"; FN_UPS_LIMIT at start): the update thread sleeps so the game runs N/60 as fast — the
// simulation slows down (fewer watts on weak phones), unlike the FPS limit. Measured in FNative Lab (2026-11-20).
#include <errno.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

#include "fn.h"

extern void fn_call_stub(void);

// The game function [symbol] calls cb() first, then runs on (callhook.S keeps every register). 0 when hooked.
int fn_hook_call(fn_image *img, const char *symbol, void (*cb)(void)) {
  uintptr_t fn = fn_find_symbol(img, symbol);
  if (!fn) return -1;
  uint32_t *site = (uint32_t *)fn, w = *site;
  int pc_rel = (w & 0x1f000000) == 0x10000000 || (w & 0x7c000000) == 0x14000000 || (w & 0x7e000000) == 0x34000000 ||
               (w & 0x7e000000) == 0x36000000 || (w & 0xff000010) == 0x54000000 || (w & 0x3b000000) == 0x18000000;
  if (pc_rel) {
    fn_log("hook %s: first instruction is pc-relative, skipped", symbol);
    return -1;
  }
  if (img->tramp_used + 64 > img->tramp_size) return -1;
  size_t at = (img->tramp_used + 7) & ~(size_t)7;
  uint32_t *t = (uint32_t *)(img->tramp + at);
  // t[0..3]: x16 = data, x17 = stub, br x17; t[4..5]: the continuation; t[6..11]: data {cb, cont}, stub address
  t[0] = 0x10000000u | (6u << 5) | 16;  // adr x16, t+24: the data's ADDRESS (an ldr literal here gave SIGBUS)
  t[1] = 0x58000000u | (9u << 5) | 17;  // ldr x17, t+40 (stub)
  t[2] = 0xd61f0220u;                   // br x17
  t[3] = 0xd503201fu;                   // nop
  t[4] = w;                             // continuation: the moved first instruction
  intptr_t d = (intptr_t)((uintptr_t)site + 4 - (uintptr_t)&t[5]);
  t[5] = 0x14000000u | ((uint32_t)(d >> 2) & 0x03ffffff);  // b fn+4
  uint64_t *lit = (uint64_t *)&t[6];
  lit[0] = (uint64_t)(uintptr_t)cb;
  lit[1] = (uint64_t)(uintptr_t)&t[4];
  lit[2] = (uint64_t)(uintptr_t)fn_call_stub;
  img->tramp_used = at + 48;
  intptr_t e = (intptr_t)((uintptr_t)t - (uintptr_t)site);
  *site = 0x14000000u | ((uint32_t)(e >> 2) & 0x03ffffff);  // b trampoline
  fn_log("hook %s: calls into the loader", symbol);
  return 0;
}

static atomic_int ups_limit;  // 0 = none

void fn_set_ups_limit(int n) {
  if (n < 0 || n >= 60) n = 0;
  if (atomic_exchange(&ups_limit, n) != n) fn_log("UPS limit: %d", n);
}

static long long now_ns(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1000000000LL + t.tv_nsec;
}

// CPU time per tick for the measurement and the lab run (launcher's engineering settings): the update thread's and
// the whole game's, summed over ticks (GameActivity.nativeTickCpu reads them at the measurement's start and end).
static volatile uint64_t acc_ticks, acc_thread_ns, acc_proc_ns;

static long long cpu_ns(clockid_t c) {
  struct timespec t;
  clock_gettime(c, &t);
  return t.tv_sec * 1000000000LL + t.tv_nsec;
}

static void tick(void) {
  static long long next, last_cpu, last_proc;
  static pid_t tick_tid;
  fn_tick_count++;  // the HUD's UPS
  long long cpu = cpu_ns(CLOCK_THREAD_CPUTIME_ID), proc = cpu_ns(CLOCK_PROCESS_CPUTIME_ID);
  pid_t tid = gettid();
  if (tid == tick_tid && cpu >= last_cpu && proc >= last_proc && last_proc) {
    acc_thread_ns += (uint64_t)(cpu - last_cpu);
    acc_proc_ns += (uint64_t)(proc - last_proc);
    acc_ticks++;
  }
  tick_tid = tid;  // another thread's clock: no difference to take this time
  last_cpu = cpu;
  last_proc = proc;
  int limit = atomic_load(&ups_limit);
  if (!limit) {
    next = 0;
    return;
  }
  long long period = 1000000000LL / limit, now = now_ns();
  if (!next || now - next > period) next = now;  // a new limit, a slow tick or a pause: no burst to catch up
  next += period;
  if (next > now) {
    struct timespec ts = {next / 1000000000LL, next % 1000000000LL};
    while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL) == EINTR) {}
  }
}

// Hooks the tick; 0 when hooked. Without a call hook (pc-relative start) the plain counter is used (no UPS limit).
int fn_tick_hook(fn_image *img, const char *symbol) {
  const char *env = getenv("FN_UPS_LIMIT");
  if (env) fn_set_ups_limit(atoi(env));
  if (fn_hook_call(img, symbol, tick) == 0) return 0;
  return fn_hook_counter(img, symbol, &fn_tick_count);
}

#ifdef __ANDROID__
#include <jni.h>
__attribute__((visibility("default"))) void Java_com_factorynative_GameActivity_nativeSetUpsLimit(JNIEnv *env, jclass cls, jint n) {
  fn_set_ups_limit(n);
}

// {ticks, update thread CPU ns, game CPU ns} so far
__attribute__((visibility("default"))) void Java_com_factorynative_GameActivity_nativeTickCpu(JNIEnv *env, jclass cls, jlongArray out) {
  jlong v[3] = {(jlong)acc_ticks, (jlong)acc_thread_ns, (jlong)acc_proc_ns};
  if ((*env)->GetArrayLength(env, out) >= 3) (*env)->SetLongArrayRegion(env, out, 0, 3, v);
}
#endif
