// FNGL_PROF=1: calls and driver time of every GL function per frame (thunks in
// fngl_prof.S). Every FNGL_PROF_FRAMES frames (default 300) the log gets the
// totals per frame and the top functions by time. Measures how much CPU the GL
// driver costs, i.e. what a Vulkan backend could save at most.
#include "fngl_prof.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>
#include <unistd.h>

#define NPROF 1024
extern const char fngl_prof_thunks[];
void *fngl_prof_real[NPROF] __attribute__((visibility("hidden")));
static const char *names[NPROF];
static uint64_t calls[NPROF], ticks[NPROF];
static int n;
static int enabled = -1;

// FNGL_PROF_DUMP=N: every call of frame N with its raw arguments (x0..x7, d0..d3; return value).
static long dump_frame = -1, cur_frame;
static FILE *dump;
static pthread_mutex_t dump_lock = PTHREAD_MUTEX_INITIALIZER;

static void dump_init(void) {
  const char *e = getenv("FNGL_PROF_DUMP");
  dump_frame = !e ? 0 : !strcmp(e, "all") ? -2 : atol(e);
  if (e) dump = fopen(getenv("FNGL_PROF_DUMP_FILE") ? getenv("FNGL_PROF_DUMP_FILE") : "gl-frame.txt", "w");
}

int fngl_prof_enabled(void) {
  if (enabled < 0) enabled = getenv("FNGL_PROF") != NULL;
  return enabled;
}

void *fngl_prof_wrap(const char *name, void *fn) {
  if (dump_frame == -1) dump_init();
  if (!fn || n >= NPROF) return fn;
  for (int i = 0; i < n; i++)
    if (fngl_prof_real[i] == fn && !strcmp(names[i], name)) return (void *)(fngl_prof_thunks + 8 * i);
  names[n] = strdup(name);
  fngl_prof_real[n] = fn;
  return (void *)(fngl_prof_thunks + 8 * n++);
}


__attribute__((visibility("hidden"))) void fngl_prof_note(uint64_t i, uint64_t t0, uint64_t t1, const uint64_t *a) {
  if (dump && (cur_frame == dump_frame || dump_frame == -2)) {
    const double *d = (const double *)(a + 8);
    pthread_mutex_lock(&dump_lock);
    fprintf(dump, "%ld %d ", cur_frame, gettid());
    fprintf(dump, "%s(%llx, %llx, %llx, %llx, %llx, %llx, %llx, %llx | %g %g %g %g) = %llx\n", names[i],
            (unsigned long long)a[0], (unsigned long long)a[1], (unsigned long long)a[2], (unsigned long long)a[3],
            (unsigned long long)a[4], (unsigned long long)a[5], (unsigned long long)a[6], (unsigned long long)a[7],
            *(const float *)&d[0], *(const float *)&d[1], *(const float *)&d[2], *(const float *)&d[3],
            (unsigned long long)a[19]);
    if (dump_frame == -2) fflush(dump);
    pthread_mutex_unlock(&dump_lock);
  }
  __atomic_add_fetch(&calls[i], 1, __ATOMIC_RELAXED);
  __atomic_add_fetch(&ticks[i], t1 - t0, __ATOMIC_RELAXED);
}

static uint64_t cntfrq(void) {
  uint64_t f;
  __asm__ volatile("mrs %0, cntfrq_el0" : "=r"(f));
  return f;
}
static uint64_t cntvct(void) {
  uint64_t v;
  __asm__ volatile("mrs %0, cntvct_el0" : "=r"(v));
  return v;
}
static double thread_cpu_s(void) {
  struct timespec ts;
  clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
  return ts.tv_sec + ts.tv_nsec * 1e-9;
}

static uint64_t swap_ticks, swap_t0;
void fngl_prof_swap_begin(void) { swap_t0 = cntvct(); }
void fngl_prof_swap_end(void) { swap_ticks += cntvct() - swap_t0; }

void fngl_prof_frame(void) {
  static long frames, every;
  if (dump && cur_frame == dump_frame) fflush(dump);
  cur_frame++;
  static uint64_t base_calls[NPROF], base_ticks[NPROF], wall0, base_swap;
  static double cpu0;
  if (!every) {
    const char *e = getenv("FNGL_PROF_FRAMES");
    every = e ? atol(e) : 300;
    wall0 = cntvct();
    cpu0 = thread_cpu_s();
    memcpy(base_calls, calls, sizeof calls);
    memcpy(base_ticks, ticks, sizeof ticks);
    return;
  }
  if (++frames < every) return;
  double f = (double)cntfrq(), nf = (double)frames;
  uint64_t wall = cntvct();
  double cpu = thread_cpu_s();
  uint64_t dc[NPROF], dt[NPROF], tc = 0, tt = 0;
  for (int i = 0; i < n; i++) {
    dc[i] = calls[i] - base_calls[i];
    dt[i] = ticks[i] - base_ticks[i];
    tc += dc[i];
    tt += dt[i];
  }
  fprintf(stderr,
          "[fngl prof] %ld frames: %.2f ms/frame wall, render thread CPU %.2f ms/frame, GL calls %.0f/frame, "
          "time in GL %.2f ms/frame, swap %.2f ms/frame\n",
          frames, (wall - wall0) / f * 1e3 / nf, (cpu - cpu0) * 1e3 / nf, tc / nf, tt / f * 1e3 / nf,
          (swap_ticks - base_swap) / f * 1e3 / nf);
  for (int k = 0; k < 25; k++) {  // top functions by time
    int best = -1;
    for (int i = 0; i < n; i++)
      if (dt[i] && (best < 0 || dt[i] > dt[best])) best = i;
    if (best < 0) break;
    fprintf(stderr, "[fngl prof]   %-34s %9.1f calls/frame %8.1f us/frame\n", names[best], dc[best] / nf,
            dt[best] / f * 1e6 / nf);
    dt[best] = 0;
  }
  frames = 0;
  wall0 = wall;
  cpu0 = cpu;
  base_swap = swap_ticks;
  memcpy(base_calls, calls, sizeof calls);
  memcpy(base_ticks, ticks, sizeof ticks);
}
