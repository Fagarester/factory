// Factorio native loader: shared declarations.
#pragma once
#include <elf.h>
#include <stddef.h>
#include <stdint.h>

// Bionic TLS slot that holds the per-thread "fake thread pointer" of the
// loaded glibc image. Slot 2 is TLS_SLOT_APP (free for apps on arm64).
#define FN_TLS_SLOT 2

typedef struct fn_image {
  char path[4096];
  uint8_t *file;        // whole file mapped read-only (symbols, sections)
  size_t file_size;
  uint8_t *base;        // load bias
  size_t span;          // reserved bytes for the image
  uint8_t *tramp;       // trampoline area right after the image
  size_t tramp_size, tramp_used;
  const Elf64_Phdr *phdr;  // inside the mapped image
  int phnum;
  Elf64_Dyn *dyn;
  uintptr_t exec_lo, exec_hi;     // executable segment (vaddr)
  uintptr_t eh_frame_hdr;         // vaddr of PT_GNU_EH_FRAME
  uintptr_t tls_vaddr;
  size_t tls_filesz, tls_memsz, tls_align;
  // Sorted function symbols from .symtab (for dladdr and lookups).
  const Elf64_Sym **funcs;
  size_t nfuncs;
  const char *strtab;
  const Elf64_Sym *symtab;
  size_t nsyms;
  int tls_sites;
} fn_image;

extern fn_image fn_img;

// Logging is compiled out: the calls and their arguments are not in the binary. A build with FN_LOG=1
// (tools/build-loader.sh) brings it back (stderr, logcat).
#ifdef FN_LOG
void fn_log(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
#else
static inline __attribute__((format(printf, 1, 2))) void fn_log_off(const char *fmt, ...) { (void)fmt; }
#define fn_log(...) (0 ? fn_log_off(__VA_ARGS__) : (void)0)
#endif
__attribute__((noreturn)) void fn_die(const char *fmt, ...) __attribute__((format(printf, 1, 2)));

// elf.c
int fn_load(fn_image *img, const char *path);
uintptr_t fn_find_symbol(const fn_image *img, const char *name);
const Elf64_Sym *fn_symbol_at(const fn_image *img, uintptr_t addr);
void fn_run_init(fn_image *img, int argc, char **argv, char **envp);

// stats.c
extern volatile uint64_t fn_tick_count;
int fn_hook_counter(fn_image *img, const char *symbol, volatile uint64_t *counter);
// tick.c: a call into C at a game function's start; the tick hook (UPS counter and the UPS limit)
int fn_hook_call(fn_image *img, const char *symbol, void (*cb)(void));
int fn_tick_hook(fn_image *img, const char *symbol);
void fn_set_ups_limit(int n);
// netping.c: the game's sendto, watched for the HUD's multiplayer ping
#include <sys/socket.h>
ssize_t fn_net_sendto(int fd, const void *buf, size_t n, int flags, const struct sockaddr *to, socklen_t len);
// restart.c: the game restarting itself (after its updater, mod changes) through the activity; its update notice
void fn_restart_init(void);
int fn_game_execv(const char *path, char *const argv[]);
void fn_restart_hooks(fn_image *img);
void *fn_game_lookup(const char *name);
int fn_hook_store(fn_image *img, const char *symbol, int reg, volatile uint64_t *dst);
int fn_hook_store2(fn_image *img, const char *symbol, int ra, volatile uint64_t *da, int rb, volatile uint64_t *db);
int fn_hook_store2_count(fn_image *img, const char *symbol, int ra, volatile uint64_t *da, int rb, volatile uint64_t *db,
                         volatile uint64_t *counter);
// quickbar.c: the player's quick bar, read from the game's memory
void fn_quickbar_init(fn_image *img);
void fn_vibrate_init(fn_image *img);
void fn_inputmethod_init(fn_image *img);
void fn_alloc_init(void);
void *fn_alloc_lookup(const char *name);
void fn_stats_read(float *fps, float *ups, float *frames, int n);

// run.c
int fn_run(const char *game, int argc, char **argv);

// tls.c
void fn_tls_setup(const fn_image *img);
void *fn_tls_current(void);
int fn_tls_patch(fn_image *img, uint8_t *text, uintptr_t lo, uintptr_t hi);

// shim.c
void *fn_shim_lookup(const char *name);
void *fn_bionic_lookup(const char *name);
void fn_shim_init(const fn_image *img);

// prof.c: CPU diagnostics (FN_PROF_*), and the game tick for its slow-tick log
void fn_prof_start(void);
void fn_prof_tick(void);
