// CPU diagnostics, switched on by the launcher's "Отладка" checkboxes; files next to fnative.log (FN_PROF_DIR):
//   FN_PROF_THREADS=1  threads.log: every second, the CPU use of each thread (by name) and the cores' frequencies.
//   FN_PROF_SAMPLE=1   prof.log: every 5 s, where the CPU time goes: SIGPROF samples (one per ms of process CPU
//                      time, ITIMER_PROF) of the interrupted pc, as game functions (mangled names: c++filt) or
//                      library!symbol, per thread name; also every gap of more than 100 ms between game ticks.
//   FN_PROF_SYS=1      sys.log: every second, the process's memory (RSS, every 5 s PSS) and page faults, the phone's free
//                      memory and swap, pressure (PSI), the hottest thermal zones, battery, GPU clock and load (if
//                      readable).
//   FN_PROF_AUTO=1     all three unless the variable itself says 0 (the launcher of a "-prof" test build, 0.185).
// The signal handler only stores {pc, tid} in a ring; a background thread does the rest.
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>

#include "fn.h"

static char dir[4096];
static FILE *prof_f, *threads_f, *sys_f;

static double now_s(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec + t.tv_nsec * 1e-9;
}
static double t0;

static FILE *open_log(const char *name) {
  char p[4200];
  snprintf(p, sizeof p, "%s/%s", dir, name);
  FILE *f = fopen(p, "w");
  if (f) setvbuf(f, NULL, _IOLBF, 0);
  else fn_log("prof: cannot write %s", p);
  return f;
}

static void thread_name(pid_t tid, char *out, size_t n) {
  char p[64];
  snprintf(p, sizeof p, "/proc/self/task/%d/comm", (int)tid);
  FILE *f = fopen(p, "r");
  out[0] = 0;
  if (f) {
    if (fgets(out, (int)n, f)) out[strcspn(out, "\n")] = 0;
    fclose(f);
  }
  if (!out[0]) snprintf(out, n, "tid%d", (int)tid);
}

// ---------------------------------------------------------------- threads.log
typedef struct {
  pid_t tid;
  unsigned long long cpu;  // utime + stime, clock ticks
  int core;                // the core it last ran on
} tcpu;
#define MAXT 256
static tcpu prev[MAXT];
static int nprev;

static void threads_line(double dt) {
  long hz = sysconf(_SC_CLK_TCK);
  tcpu cur[MAXT];
  int n = 0;
  DIR *d = opendir("/proc/self/task");
  if (!d) return;
  struct dirent *e;
  while ((e = readdir(d)) && n < MAXT) {
    if (e->d_name[0] == '.') continue;
    char p[96], buf[1024];
    snprintf(p, sizeof p, "/proc/self/task/%s/stat", e->d_name);
    FILE *f = fopen(p, "r");
    if (!f) continue;
    size_t len = fread(buf, 1, sizeof buf - 1, f);
    fclose(f);
    buf[len] = 0;
    char *r = strrchr(buf, ')');  // fields after the name: state is field 3; utime 14, stime 15
    if (!r) continue;
    unsigned long long ut = 0, st = 0;
    if (sscanf(r + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &ut, &st) != 2) continue;
    char *q = r + 2;  // field 3 (state); field 39: the core it last ran on
    for (int fi = 3; fi < 39 && q; fi++) {
      q = strchr(q, ' ');
      if (q) q++;
    }
    int core = q ? atoi(q) : -1;
    cur[n++] = (tcpu){(pid_t)atoi(e->d_name), ut + st, core};
  }
  closedir(d);
  char line[4096];
  int k = snprintf(line, sizeof line, "%7.1f s:", now_s() - t0);
  double total = 0;
  for (int i = 0; i < n; i++) {
    unsigned long long before = 0;
    for (int j = 0; j < nprev; j++)
      if (prev[j].tid == cur[i].tid) before = prev[j].cpu;
    if (!before) continue;
    double pct = (double)(cur[i].cpu - before) / hz / dt * 100;
    total += pct;
    if (pct < 5 || k > (int)sizeof line - 64) continue;
    char name[32];
    thread_name(cur[i].tid, name, sizeof name);
    k += snprintf(line + k, sizeof line - k, " %s %.0f%%@%d", name, pct, cur[i].core);
  }
  memcpy(prev, cur, sizeof cur[0] * n);
  nprev = n;
  k += snprintf(line + k, sizeof line - k, " | all %.0f%% | MHz", total);
  for (int c = 0; c < 16 && k < (int)sizeof line - 16; c++) {
    char p[96];
    snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", c);
    FILE *f = fopen(p, "r");
    if (!f) break;
    long khz = 0;
    if (fscanf(f, "%ld", &khz) == 1) k += snprintf(line + k, sizeof line - k, " %ld", khz / 1000);
    fclose(f);
  }
  fprintf(threads_f, "%s\n", line);
}

// ---------------------------------------------------------------- prof.log
typedef struct {
  uintptr_t pc;
  pid_t tid;
} sample;
#define RING 65536
static sample ring[RING];
static atomic_uint ring_w;
static unsigned ring_r;

static void on_prof(int sig, siginfo_t *si, void *ctx) {
  (void)sig;
  (void)si;
  int saved = errno;
  ucontext_t *u = ctx;
  unsigned i = atomic_fetch_add_explicit(&ring_w, 1, memory_order_relaxed);
  ring[i % RING] = (sample){(uintptr_t)u->uc_mcontext.pc, gettid()};
  errno = saved;
}

typedef struct {
  pid_t tid;
  const char *where;  // stable string (symbol name or interned library name)
  unsigned n;
} bucket;
#define MAXB 8192

// "symbol" of the game, "lib!symbol" elsewhere; the returned string stays valid (interned)
static const char *where_of(uintptr_t pc) {
  static char *names[4096];
  static int nnames;
  char buf[512];
  if (pc >= (uintptr_t)fn_img.base && pc < (uintptr_t)fn_img.base + fn_img.span) {
    const Elf64_Sym *s = fn_symbol_at(&fn_img, pc);
    if (s) return fn_img.strtab + s->st_name;
    return "game?";
  }
  Dl_info d;
  if (dladdr((void *)pc, &d) && d.dli_fname) {
    const char *lib = strrchr(d.dli_fname, '/');
    snprintf(buf, sizeof buf, "%s!%s", lib ? lib + 1 : d.dli_fname, d.dli_sname ? d.dli_sname : "?");
  } else {
    snprintf(buf, sizeof buf, "?");
  }
  for (int i = 0; i < nnames; i++)
    if (!strcmp(names[i], buf)) return names[i];
  if (nnames == 4096) return "other";
  return names[nnames++] = strdup(buf);
}

static int by_count(const void *a, const void *b) {
  unsigned x = ((const bucket *)a)->n, y = ((const bucket *)b)->n;
  return x < y ? 1 : x > y ? -1 : 0;
}

static void prof_window(void) {
  static bucket b[MAXB];
  int nb = 0;
  unsigned w = atomic_load(&ring_w), total = 0;
  if (w - ring_r > RING) ring_r = w - RING;  // overrun: the oldest are gone
  for (; ring_r != w; ring_r++) {
    sample s = ring[ring_r % RING];
    const char *at = where_of(s.pc);
    int i = 0;
    while (i < nb && (b[i].tid != s.tid || b[i].where != at)) i++;
    if (i == nb) {
      if (nb == MAXB) continue;
      b[nb++] = (bucket){s.tid, at, 0};
    }
    b[i].n++;
    total++;
  }
  if (!total) return;
  qsort(b, nb, sizeof b[0], by_count);
  fprintf(prof_f, "== %.1f s: %u samples (1 = 1 ms of CPU)\n", now_s() - t0, total);
  for (int i = 0; i < nb && i < 60; i++) {
    char name[32];
    thread_name(b[i].tid, name, sizeof name);
    fprintf(prof_f, "%5.1f%% %-16s %s\n", 100.0 * b[i].n / total, name, b[i].where);
  }
}

// game ticks (tick.c): gaps of more than 100 ms
static volatile int ticks_on;
static atomic_uint tick_count;  // all game ticks, for ticks per second (sys.log)
void fn_prof_tick(void) {
  atomic_fetch_add_explicit(&tick_count, 1, memory_order_relaxed);
  if (!ticks_on) return;
  static double last;
  double t = now_s();
  if (last && t - last > 0.1) fprintf(prof_f, "slow tick: %.0f ms at %.1f s\n", (t - last) * 1e3, t - t0);
  last = t;
}

// ---------------------------------------------------------------- sys.log

// first number after [key] in the file (e.g. "MemAvailable:" in /proc/meminfo, "avg10=" in /proc/pressure/cpu)
static double file_num(const char *path, const char *key) {
  char buf[4096];
  FILE *f = fopen(path, "r");
  if (!f) return -1;
  size_t n = fread(buf, 1, sizeof buf - 1, f);
  fclose(f);
  buf[n] = 0;
  char *at = key ? strstr(buf, key) : buf;
  if (!at) return -1;
  at += key ? strlen(key) : 0;
  while (*at == ' ' || *at == '\t') at++;
  char *end;
  double v = strtod(at, &end);
  return end == at ? -1 : v;
}

#define MAXZ 96
static char zone_type[MAXZ][32];
static int zone_id[MAXZ], nzones;
static const char *gpu_freq_path, *gpu_load_path;
static double gpu_freq_div = 1;

static void sys_init(void) {
  for (int i = 0; i < 200 && nzones < MAXZ; i++) {
    char p[96];
    snprintf(p, sizeof p, "/sys/class/thermal/thermal_zone%d/type", i);
    FILE *f = fopen(p, "r");
    if (!f) continue;
    char t[32] = "";
    if (fgets(t, sizeof t, f)) t[strcspn(t, "\n")] = 0;
    fclose(f);
    snprintf(p, sizeof p, "/sys/class/thermal/thermal_zone%d/temp", i);
    if (file_num(p, NULL) < 0) continue;  // not readable
    snprintf(zone_type[nzones], sizeof zone_type[0], "%s", t);
    zone_id[nzones++] = i;
  }
  // GPU clock and load: MediaTek (GED), Qualcomm (kgsl), generic devfreq; the first one readable
  static const char *freq[][2] = {{"/sys/kernel/ged/hal/current_freqency", "1"},
                                  {"/sys/class/kgsl/kgsl-3d0/gpuclk", "1000000"},
                                  {"/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "1000000"},
                                  {"/sys/class/devfreq/13000000.mali/cur_freq", "1000000"},
                                  {"/sys/class/misc/mali0/device/devfreq/13000000.mali/cur_freq", "1000000"}};
  for (unsigned i = 0; i < sizeof freq / sizeof freq[0]; i++)
    if (file_num(freq[i][0], NULL) >= 0) {
      gpu_freq_path = freq[i][0];
      gpu_freq_div = atof(freq[i][1]);
      break;
    }
  static const char *load[] = {"/sys/kernel/ged/hal/gpu_utilization", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                               "/sys/class/misc/mali0/device/utilization"};
  for (unsigned i = 0; i < sizeof load / sizeof load[0]; i++)
    if (file_num(load[i], NULL) >= 0) {
      gpu_load_path = load[i];
      break;
    }
  fprintf(sys_f, "# thermal zones readable: %d; GPU clock: %s; GPU load: %s\n", nzones,
          gpu_freq_path ? gpu_freq_path : "not readable", gpu_load_path ? gpu_load_path : "not readable");
  fprintf(sys_f, "# rss/pss/swap: this process; avail, swap: the phone; flt: page faults per second (major = read from "
                 "storage or swap); psi: share of time something waited for CPU / memory / io (avg10, %%)\n");
}

static void sys_line(double dt, int with_pss) {
  static unsigned long long last_min, last_maj;
  static unsigned last_ticks;
  char line[2048];
  int k = snprintf(line, sizeof line, "%7.1f s:", now_s() - t0);
  unsigned tc = atomic_load(&tick_count);
  k += snprintf(line + k, sizeof line - k, " ticks %.0f/s |", (tc - last_ticks) / dt);
  last_ticks = tc;
  k += snprintf(line + k, sizeof line - k, " rss %.0f", file_num("/proc/self/status", "VmRSS:") / 1024);
  if (with_pss) k += snprintf(line + k, sizeof line - k, " pss %.0f", file_num("/proc/self/smaps_rollup", "\nPss:") / 1024);
  k += snprintf(line + k, sizeof line - k, " swap %.0f MB", file_num("/proc/self/status", "VmSwap:") / 1024);
  {
    char buf[1024];
    FILE *f = fopen("/proc/self/stat", "r");
    if (f) {
      size_t n = fread(buf, 1, sizeof buf - 1, f);
      fclose(f);
      buf[n] = 0;
      char *r = strrchr(buf, ')');
      unsigned long long mn = 0, mj = 0;
      if (r && sscanf(r + 2, "%*c %*d %*d %*d %*d %*d %*u %llu %*u %llu", &mn, &mj) == 2) {
        if (last_min) k += snprintf(line + k, sizeof line - k, " flt %.0f/s major %.0f/s", (mn - last_min) / dt, (mj - last_maj) / dt);
        last_min = mn;
        last_maj = mj;
      }
    }
  }
  double avail = file_num("/proc/meminfo", "MemAvailable:"), st = file_num("/proc/meminfo", "SwapTotal:"),
         sf = file_num("/proc/meminfo", "SwapFree:");
  k += snprintf(line + k, sizeof line - k, " | phone avail %.0f MB swap used %.0f/%.0f MB", avail / 1024,
                st >= 0 && sf >= 0 ? (st - sf) / 1024 : -1, st / 1024);
  k += snprintf(line + k, sizeof line - k, " | psi cpu %.1f mem %.1f io %.1f", file_num("/proc/pressure/cpu", "avg10="),
                file_num("/proc/pressure/memory", "avg10="), file_num("/proc/pressure/io", "avg10="));
  // the hottest three zones
  double best[3] = {-1e9, -1e9, -1e9};
  int bi[3] = {-1, -1, -1};
  for (int i = 0; i < nzones; i++) {
    char p[96];
    snprintf(p, sizeof p, "/sys/class/thermal/thermal_zone%d/temp", zone_id[i]);
    double t = file_num(p, NULL);
    if (t > 1000) t /= 1000;  // millidegrees
    for (int j = 0; j < 3; j++)
      if (t > best[j]) {
        for (int m = 2; m > j; m--) best[m] = best[m - 1], bi[m] = bi[m - 1];
        best[j] = t;
        bi[j] = i;
        break;
      }
  }
  k += snprintf(line + k, sizeof line - k, " | temp");
  for (int j = 0; j < 3; j++)
    if (bi[j] >= 0) k += snprintf(line + k, sizeof line - k, " %s %.1f", zone_type[bi[j]], best[j]);
  double bt = file_num("/sys/class/power_supply/battery/temp", NULL);
  if (bt >= 0) k += snprintf(line + k, sizeof line - k, " battery %.1f", bt / 10);
  if (gpu_freq_path) k += snprintf(line + k, sizeof line - k, " | gpu %.0f MHz", file_num(gpu_freq_path, NULL) / gpu_freq_div);
  if (gpu_load_path) k += snprintf(line + k, sizeof line - k, " load %.0f%%", file_num(gpu_load_path, NULL));
  fprintf(sys_f, "%s\n", line);
}

// Every 30 s: the process's memory by owner, from /proc/self/smaps (resident, swapped, proportional set size).
typedef struct {
  const char *name;
  double rss, swap, pss;
} mgroup;

static const char *map_owner(const char *path) {
  if (!*path) return "anon (game heap: mimalloc, mmap)";
  if (strstr(path, "mali") || strstr(path, "kgsl") || strstr(path, "dmabuf") || strstr(path, "dma_buf") ||
      strstr(path, "/dev/dri") || strstr(path, "gpu"))
    return "GPU driver mappings";
  if (strstr(path, "/factorio")) return "game binary and data files";
  if (strstr(path, "dalvik") || strstr(path, "art") || strstr(path, ".art") || strstr(path, ".oat") ||
      strstr(path, ".vdex") || strstr(path, ".jar") || strstr(path, ".apk"))
    return "Java / ART";
  if (strstr(path, "scudo") || strstr(path, "malloc") || strstr(path, "jemalloc")) return "Android malloc";
  if (strstr(path, ".so")) return "libraries";
  if (strstr(path, "stack")) return "thread stacks";
  if (path[0] == '[') return "other [anon:...]";
  return "other files";
}

static void smaps_line(void) {
  FILE *f = fopen("/proc/self/smaps", "r");
  if (!f) return;
  mgroup g[16];
  int ng = 0, cur = -1;
  char line[1024];
  while (fgets(line, sizeof line, f)) {
    unsigned long a, b;
    if (sscanf(line, "%lx-%lx ", &a, &b) == 2 && strchr(line, ' ') && line[strlen(line) - 1] == '\n' &&
        strchr(line, '-') < strchr(line, ' ')) {
      // header: start-end perms offset dev inode [path]
      char *p = line;
      for (int i = 0; i < 5 && p; i++) {
        p = strchr(p, ' ');
        if (p) while (*p == ' ') p++;
      }
      char path[512] = "";
      if (p) {
        snprintf(path, sizeof path, "%s", p);
        path[strcspn(path, "\n")] = 0;
      }
      const char *o = map_owner(path);
      cur = -1;
      for (int i = 0; i < ng; i++)
        if (g[i].name == o) cur = i;
      if (cur < 0 && ng < 16) g[cur = ng++] = (mgroup){o, 0, 0, 0};
      continue;
    }
    double kb;
    if (cur < 0) continue;
    if (sscanf(line, "Rss: %lf", &kb) == 1) g[cur].rss += kb;
    else if (sscanf(line, "Swap: %lf", &kb) == 1) g[cur].swap += kb;
    else if (sscanf(line, "Pss: %lf", &kb) == 1) g[cur].pss += kb;
  }
  fclose(f);
  fprintf(sys_f, "%7.1f s: memory by owner (MB: in RAM / swapped / pss):", now_s() - t0);
  for (int i = 0; i < ng; i++)
    if (g[i].rss + g[i].swap >= 1024)
      fprintf(sys_f, " | %s %.0f/%.0f/%.0f", g[i].name, g[i].rss / 1024, g[i].swap / 1024, g[i].pss / 1024);
  fprintf(sys_f, "\n");
}

// ---------------------------------------------------------------- start
static void *run(void *arg) {
  (void)arg;
  pthread_setname_np(pthread_self(), "fn-prof");
  double last = now_s(), last_prof = last;
  for (;;) {
    sleep(1);
    double t = now_s();
    if (threads_f) threads_line(t - last);
    if (sys_f) {
      static int n;
      sys_line(t - last, n % 5 == 0);
      if (n % 30 == 0) smaps_line();
      n++;
    }
    if (prof_f && t - last_prof >= 5) {
      prof_window();
      last_prof = t;
    }
    last = t;
  }
  return NULL;
}

// "1" on, "0" off; unset: on with FN_PROF_AUTO=1
static int env_on(const char *name) {
  const char *v = getenv(name);
  if (v && *v) return atoi(v) != 0;
  v = getenv("FN_PROF_AUTO");
  return v && atoi(v) != 0;
}

void fn_prof_start(void) {
  const char *d = getenv("FN_PROF_DIR");
  int threads = env_on("FN_PROF_THREADS"), sample_on = env_on("FN_PROF_SAMPLE"), sys_on = env_on("FN_PROF_SYS");
  if (!d || !*d || (!threads && !sample_on && !sys_on)) return;
  snprintf(dir, sizeof dir, "%s", d);
  t0 = now_s();
  if (threads) threads_f = open_log("threads.log");
  if (sys_on && (sys_f = open_log("sys.log"))) sys_init();
  if (sample_on && (prof_f = open_log("prof.log"))) {
    struct sigaction sa = {0};
    sa.sa_sigaction = on_prof;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGPROF, &sa, NULL);
    struct itimerval it = {{0, 1000}, {0, 1000}};
    setitimer(ITIMER_PROF, &it, NULL);
    ticks_on = 1;
  }
  pthread_t t;
  if (!pthread_create(&t, NULL, run, NULL)) pthread_detach(t);
  fn_log("prof: threads %s, samples %s, system %s -> %s", threads_f ? "on" : "off", prof_f ? "on" : "off",
         sys_f ? "on" : "off", dir);
}
