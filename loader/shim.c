// glibc -> bionic compatibility layer for the imports of the Factorio binary.
// Rule: an import goes straight to bionic unless its ABI differs. Only
// differing structures, constants and missing functions get a shim here.
#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <langinfo.h>
#include <libgen.h>
#include <link.h>
#include <locale.h>
#include <netdb.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <sys/auxv.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <wchar.h>
#include <wctype.h>

#include "fn.h"

static const fn_image *img;
static void *h_libc, *h_libm, *h_libdl;

// ---------------------------------------------------------------- logging
void fn_log(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fputs("[fnative] ", stderr);
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
}

void fn_die(const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  fputs("[fnative] FATAL: ", stderr);
  vfprintf(stderr, fmt, ap);
  fputc('\n', stderr);
  va_end(ap);
  _exit(127);
}


// ---------------------------------------------------------------- ctype
// glibc table layout: unsigned short bits, indexable from -128 to 255.
enum {
  G_ISupper = 1 << 8, G_ISlower = 1 << 9, G_ISalpha = 1 << 10, G_ISdigit = 1 << 11,
  G_ISxdigit = 1 << 12, G_ISspace = 1 << 13, G_ISprint = 1 << 14, G_ISgraph = 1 << 15,
  G_ISblank = 1 << 0, G_IScntrl = 1 << 1, G_ISpunct = 1 << 2, G_ISalnum = 1 << 3,
};
static unsigned short ctype_b[384];
static int32_t ctype_lower[384], ctype_upper[384];
static const unsigned short *ctype_b_ptr = ctype_b + 128;
static const int32_t *ctype_lower_ptr = ctype_lower + 128;

static void init_ctype(void) {
  for (int i = -128; i < 256; i++) {
    int c = i & 0xff;  // glibc C locale: bytes >= 0x80 have no class
    unsigned short m = 0;
    int ch = (i >= 0 && i < 128) ? c : -1;
    if (ch >= 0) {
      if (isupper(ch)) m |= G_ISupper;
      if (islower(ch)) m |= G_ISlower;
      if (isalpha(ch)) m |= G_ISalpha;
      if (isdigit(ch)) m |= G_ISdigit;
      if (isxdigit(ch)) m |= G_ISxdigit;
      if (isspace(ch)) m |= G_ISspace;
      if (isprint(ch)) m |= G_ISprint;
      if (isgraph(ch)) m |= G_ISgraph;
      if (ch == ' ' || ch == '\t') m |= G_ISblank;
      if (iscntrl(ch)) m |= G_IScntrl;
      if (ispunct(ch)) m |= G_ISpunct;
      if (isalnum(ch)) m |= G_ISalnum;
    }
    ctype_b[i + 128] = m;
    ctype_lower[i + 128] = (ch >= 'A' && ch <= 'Z') ? ch + 32 : i;
    ctype_upper[i + 128] = (ch >= 'a' && ch <= 'z') ? ch - 32 : i;
  }
}
static const unsigned short **s_ctype_b_loc(void) { return &ctype_b_ptr; }
static const int32_t **s_ctype_tolower_loc(void) { return &ctype_lower_ptr; }

// ---------------------------------------------------------------- locale
// libstdc++ (gnu locale model) reads glibc's struct __locale_struct directly.
typedef struct g_locale {
  void *locales[13];
  const unsigned short *ctype_b;
  const int32_t *ctype_tolower;
  const int32_t *ctype_toupper;
  const char *names[13];
} g_locale;
#define G_LC_GLOBAL_LOCALE ((g_locale *)-1L)

static g_locale c_locale;
static __thread g_locale *cur_locale = G_LC_GLOBAL_LOCALE;

static void init_locale(void) {
  c_locale.ctype_b = ctype_b + 128;
  c_locale.ctype_tolower = ctype_lower + 128;
  c_locale.ctype_toupper = ctype_upper + 128;
  for (int i = 0; i < 13; i++) c_locale.names[i] = "C";
}
static g_locale *s_newlocale(int mask, const char *name, g_locale *base) {
  (void)mask; (void)name;
  g_locale *l = base && base != &c_locale ? base : malloc(sizeof *l);
  if (!l) return NULL;
  *l = c_locale;
  return l;
}
static g_locale *s_duplocale(g_locale *l) {
  g_locale *n = malloc(sizeof *n);
  if (n) *n = (l == G_LC_GLOBAL_LOCALE || !l) ? c_locale : *l;
  return n;
}
static void s_freelocale(g_locale *l) { if (l && l != &c_locale && l != G_LC_GLOBAL_LOCALE) free(l); }
static g_locale *s_uselocale(g_locale *l) {
  g_locale *old = cur_locale;
  if (l) cur_locale = l;
  return old;
}

// glibc nl_item = (category << 16) | index.
static const char *g_langinfo(int item) {
  switch (item) {
    case 14: return "UTF-8";                    // CODESET
    case 0x10000: return ".";                   // RADIXCHAR / DECIMAL_POINT
    case 0x10001: return "";                    // THOUSEP
    case 0x10002: return "";                    // GROUPING
    case 0x2002C: return "%a %b %e %H:%M:%S %Y";  // D_T_FMT
    case 0x2002D: return "%m/%d/%y";            // D_FMT
    case 0x2002E: return "%H:%M:%S";            // T_FMT
    case 0x2002F: return "%I:%M:%S %p";         // T_FMT_AMPM
    case 0x20026: return "AM";
    case 0x20027: return "PM";
    case 0x50000: return "^[yY]";
    case 0x50001: return "^[nN]";
  }
  if (item >= 0x20000 && item < 0x20007) {  // ABDAY_1..7
    static const char *d[] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    return d[item - 0x20000];
  }
  if (item >= 0x20007 && item < 0x2000E) {  // DAY_1..7
    static const char *d[] = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    return d[item - 0x20007];
  }
  if (item >= 0x2000E && item < 0x2001A) {  // ABMON_1..12
    static const char *m[] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    return m[item - 0x2000E];
  }
  if (item >= 0x2001A && item < 0x20026) {  // MON_1..12
    static const char *m[] = {"January", "February", "March", "April", "May", "June", "July",
                              "August", "September", "October", "November", "December"};
    return m[item - 0x2001A];
  }
  return "";
}
static char *s_nl_langinfo(int item) { return (char *)g_langinfo(item); }
static char *s_nl_langinfo_l(int item, g_locale *l) { (void)l; return (char *)g_langinfo(item); }

static int s_strcoll_l(const char *a, const char *b, g_locale *l) { (void)l; return strcoll(a, b); }
static size_t s_strxfrm_l(char *d, const char *s, size_t n, g_locale *l) { (void)l; return strxfrm(d, s, n); }
static int s_wcscoll_l(const wchar_t *a, const wchar_t *b, g_locale *l) { (void)l; return wcscoll(a, b); }
static size_t s_wcsxfrm_l(wchar_t *d, const wchar_t *s, size_t n, g_locale *l) { (void)l; return wcsxfrm(d, s, n); }
static double s_strtod_l(const char *s, char **e, g_locale *l) { (void)l; return strtod(s, e); }
static float s_strtof_l(const char *s, char **e, g_locale *l) { (void)l; return strtof(s, e); }
static long double s_strtold_l(const char *s, char **e, g_locale *l) { (void)l; return strtold(s, e); }
static size_t s_strftime_l(char *d, size_t n, const char *f, const struct tm *t, g_locale *l) {
  (void)l; return strftime(d, n, f, t);
}
static size_t s_wcsftime_l(wchar_t *d, size_t n, const wchar_t *f, const struct tm *t, g_locale *l) {
  (void)l; return wcsftime(d, n, f, t);
}
static wint_t s_towlower_l(wint_t c, g_locale *l) { (void)l; return towlower(c); }
static wint_t s_towupper_l(wint_t c, g_locale *l) { (void)l; return towupper(c); }
static wctype_t s_wctype_l(const char *n, g_locale *l) { (void)l; return wctype(n); }
static int s_iswctype_l(wint_t c, wctype_t t, g_locale *l) { (void)l; return iswctype(c, t); }

// ---------------------------------------------------------------- gettext
static char *s_gettext(const char *m) { return (char *)m; }
static char *s_dgettext(const char *d, const char *m) { (void)d; return (char *)m; }
static char *s_bindtextdomain(const char *d, const char *dir) { (void)d; return (char *)dir; }
static char *s_bind_textdomain_codeset(const char *d, const char *c) { (void)d; return (char *)c; }

// ---------------------------------------------------------------- scanf / strto (C23 names)
static int s_sscanf(const char *s, const char *f, ...) {
  va_list ap; va_start(ap, f); int r = vsscanf(s, f, ap); va_end(ap); return r;
}
static int s_fscanf(FILE *fp, const char *f, ...) {
  va_list ap; va_start(ap, f); int r = vfscanf(fp, f, ap); va_end(ap); return r;
}

// ---------------------------------------------------------------- misc
static char s_libc_single_threaded = 0;  // threads exist: libstdc++ must use atomics
static int s_malloc_trim(size_t pad) { (void)pad; return 0; }
static int s_pthread_setcanceltype(int t, int *old) { (void)t; if (old) *old = 0; return 0; }
static char *s_secure_getenv(const char *n) { return getenv(n); }
static int s_libc_start_main(void) { fn_die("__libc_start_main called"); }

// bionic aborts when dso_handle is not a known library: use the loader's one.
extern void *__dso_handle;
extern int __cxa_thread_atexit_impl(void (*)(void *), void *, void *);
static int s_cxa_thread_atexit_impl(void (*f)(void *), void *obj, void *dso) {
  (void)dso;
  return __cxa_thread_atexit_impl(f, obj, &__dso_handle);
}

extern char *__gnu_strerror_r(int, char *, size_t);

// sysconf: glibc and bionic number the _SC_* names differently.
static long s_sysconf(int name) {
  static int warned;
  switch (name) {
    case 0: return sysconf(_SC_ARG_MAX);
    case 1: return sysconf(_SC_CHILD_MAX);
    case 2: return sysconf(_SC_CLK_TCK);
    case 3: return sysconf(_SC_NGROUPS_MAX);
    case 4: return sysconf(_SC_OPEN_MAX);
    case 30: return sysconf(_SC_PAGESIZE);
    case 43: return sysconf(_SC_LINE_MAX);
    case 60: return sysconf(_SC_IOV_MAX);
    case 69: return sysconf(_SC_GETGR_R_SIZE_MAX);
    case 70: return sysconf(_SC_GETPW_R_SIZE_MAX);
    case 75: return sysconf(_SC_THREAD_STACK_MIN);
    case 83: return sysconf(_SC_NPROCESSORS_CONF);
    case 84: return sysconf(_SC_NPROCESSORS_ONLN);
    case 85: return sysconf(_SC_PHYS_PAGES);
    case 86: return sysconf(_SC_AVPHYS_PAGES);
    case 180: return sysconf(_SC_HOST_NAME_MAX);
    case 188: return sysconf(_SC_LEVEL1_DCACHE_SIZE);
    case 189: return sysconf(_SC_LEVEL1_DCACHE_ASSOC);
    case 190: return sysconf(_SC_LEVEL1_DCACHE_LINESIZE);
  }
  fn_log("sysconf: unmapped glibc name %d", name);
  (void)warned;
  errno = EINVAL;
  return -1;
}

// ---------------------------------------------------------------- signals
// glibc struct sigaction (aarch64): handler, sigset_t[128 bytes], int flags, restorer.
typedef struct {
  void *handler;
  uint64_t mask[16];
  int flags;
  void (*restorer)(void);
} g_sigaction;

static int s_sigaction(int sig, const g_sigaction *act, g_sigaction *old) {
  struct sigaction a, o;
  if (act) {
    memset(&a, 0, sizeof a);
    a.sa_sigaction = act->handler;
    memcpy(&a.sa_mask, act->mask, sizeof a.sa_mask);
    a.sa_flags = act->flags & ~0x04000000;  // SA_RESTORER is set by libc itself
  }
  int r = sigaction(sig, act ? &a : NULL, old ? &o : NULL);
  if (r == 0 && old) {
    memset(old, 0, sizeof *old);
    old->handler = (void *)o.sa_sigaction;
    memcpy(old->mask, &o.sa_mask, sizeof o.sa_mask);
    old->flags = (int)o.sa_flags;
  }
  return r;
}

// ---------------------------------------------------------------- netdb
// glibc struct addrinfo puts ai_addr before ai_canonname; bionic the reverse.
typedef struct g_addrinfo {
  int ai_flags, ai_family, ai_socktype, ai_protocol;
  socklen_t ai_addrlen;
  struct sockaddr *ai_addr;
  char *ai_canonname;
  struct g_addrinfo *ai_next;
} g_addrinfo;

static int ai_flags_to_bionic(int f) {
  int r = 0;
  if (f & 0x1) r |= AI_PASSIVE;
  if (f & 0x2) r |= AI_CANONNAME;
  if (f & 0x4) r |= AI_NUMERICHOST;
  if (f & 0x8) r |= AI_V4MAPPED;
  if (f & 0x10) r |= AI_ALL;
  if (f & 0x20) r |= AI_ADDRCONFIG;
  if (f & 0x400) r |= AI_NUMERICSERV;
  return r;
}
static int eai_to_glibc(int e) {
  switch (e) {
    case 0: return 0;
    case EAI_BADFLAGS: return -1;
    case EAI_NONAME: return -2;
    case EAI_AGAIN: return -3;
    case EAI_FAIL: return -4;
    case EAI_NODATA: return -5;
    case EAI_FAMILY: return -6;
    case EAI_SOCKTYPE: return -7;
    case EAI_SERVICE: return -8;
    case EAI_ADDRFAMILY: return -9;
    case EAI_MEMORY: return -10;
    case EAI_SYSTEM: return -11;
    case EAI_OVERFLOW: return -12;
  }
  return -4;
}
static int eai_to_bionic(int e) {
  static const int t[] = {0, EAI_BADFLAGS, EAI_NONAME, EAI_AGAIN, EAI_FAIL, EAI_NODATA, EAI_FAMILY,
                          EAI_SOCKTYPE, EAI_SERVICE, EAI_ADDRFAMILY, EAI_MEMORY, EAI_SYSTEM, EAI_OVERFLOW};
  return (e <= 0 && e >= -12) ? t[-e] : EAI_FAIL;
}

static int s_getaddrinfo(const char *node, const char *service, const g_addrinfo *hints, g_addrinfo **res) {
  struct addrinfo h, *out = NULL;
  if (hints) {
    memset(&h, 0, sizeof h);
    h.ai_flags = ai_flags_to_bionic(hints->ai_flags);
    h.ai_family = hints->ai_family;
    h.ai_socktype = hints->ai_socktype;
    h.ai_protocol = hints->ai_protocol;
  }
  int r = getaddrinfo(node, service, hints ? &h : NULL, &out);
  if (r) return eai_to_glibc(r);
  g_addrinfo *head = NULL, **tail = &head;
  for (struct addrinfo *a = out; a; a = a->ai_next) {
    size_t cn = a->ai_canonname ? strlen(a->ai_canonname) + 1 : 0;
    g_addrinfo *g = calloc(1, sizeof *g + a->ai_addrlen + cn);
    if (!g) break;
    g->ai_flags = a->ai_flags;  // values only informative on output
    g->ai_family = a->ai_family;
    g->ai_socktype = a->ai_socktype;
    g->ai_protocol = a->ai_protocol;
    g->ai_addrlen = a->ai_addrlen;
    g->ai_addr = (struct sockaddr *)(g + 1);
    memcpy(g->ai_addr, a->ai_addr, a->ai_addrlen);
    if (cn) {
      g->ai_canonname = (char *)g->ai_addr + a->ai_addrlen;
      memcpy(g->ai_canonname, a->ai_canonname, cn);
    }
    *tail = g;
    tail = &g->ai_next;
  }
  freeaddrinfo(out);
  *res = head;
  return head ? 0 : -10;
}
static void s_freeaddrinfo(g_addrinfo *a) {
  while (a) { g_addrinfo *n = a->ai_next; free(a); a = n; }
}
static const char *s_gai_strerror(int e) { return gai_strerror(eai_to_bionic(e)); }
static int s_getnameinfo(const struct sockaddr *sa, socklen_t sl, char *host, socklen_t hl, char *serv,
                         socklen_t svl, int flags) {
  int f = 0;
  if (flags & 1) f |= NI_NUMERICHOST;
  if (flags & 2) f |= NI_NUMERICSERV;
  if (flags & 4) f |= NI_NOFQDN;
  if (flags & 8) f |= NI_NAMEREQD;
  if (flags & 16) f |= NI_DGRAM;
  return eai_to_glibc(getnameinfo(sa, sl, host, hl, serv, svl, f));
}
// glibc resolver (SRV lookups): not available, callers get a failure.
static int s_res_fail(void) { errno = ENOSYS; return -1; }
static void s_res_nclose(void *st) { (void)st; }

// ---------------------------------------------------------------- dl*
// glibc struct dl_find_object (2.35).
typedef struct {
  unsigned long long flags;
  void *map_start, *map_end;
  void *link_map;
  void *eh_frame;
  unsigned long long reserved[7];
} g_dl_find_object;

struct find_ctx { uintptr_t pc; g_dl_find_object *out; int found; };
static int find_cb(struct dl_phdr_info *info, size_t size, void *data) {
  (void)size;
  struct find_ctx *c = data;
  uintptr_t lo = UINTPTR_MAX, hi = 0, eh = 0;
  int hit = 0;
  for (int i = 0; i < info->dlpi_phnum; i++) {
    const ElfW(Phdr) *p = &info->dlpi_phdr[i];
    uintptr_t s = info->dlpi_addr + p->p_vaddr;
    if (p->p_type == PT_LOAD) {
      if (s < lo) lo = s;
      if (s + p->p_memsz > hi) hi = s + p->p_memsz;
      if (c->pc >= s && c->pc < s + p->p_memsz) hit = 1;
    } else if (p->p_type == PT_GNU_EH_FRAME) {
      eh = s;
    }
  }
  if (!hit) return 0;
  c->out->map_start = (void *)lo;
  c->out->map_end = (void *)hi;
  c->out->eh_frame = (void *)eh;
  c->found = 1;
  return 1;
}
static int s_dl_find_object(void *pc, g_dl_find_object *out) {
  uintptr_t a = (uintptr_t)pc;
  memset(out, 0, sizeof *out);
  if (a >= (uintptr_t)img->base && a < (uintptr_t)img->base + img->span) {
    out->map_start = img->base;
    out->map_end = img->base + img->span;
    out->link_map = (void *)img;
    out->eh_frame = img->base + img->eh_frame_hdr;
    return 0;
  }
  struct find_ctx c = {a, out, 0};
  dl_iterate_phdr(find_cb, &c);
  return c.found ? 0 : -1;
}

static int s_dl_iterate_phdr(int (*cb)(struct dl_phdr_info *, size_t, void *), void *data) {
  struct dl_phdr_info info;
  memset(&info, 0, sizeof info);
  info.dlpi_addr = (ElfW(Addr))img->base;
  info.dlpi_name = img->path;
  info.dlpi_phdr = img->phdr;
  info.dlpi_phnum = (ElfW(Half))img->phnum;
  int r = cb(&info, sizeof info, data);
  if (r) return r;
  return dl_iterate_phdr(cb, data);
}

static int s_dladdr(const void *addr, Dl_info *info) {
  uintptr_t a = (uintptr_t)addr;
  if (a >= (uintptr_t)img->base && a < (uintptr_t)img->base + img->span) {
    const Elf64_Sym *s = fn_symbol_at(img, a);
    info->dli_fname = img->path;
    info->dli_fbase = img->base;
    info->dli_sname = s ? img->strtab + s->st_name : NULL;
    info->dli_saddr = s ? img->base + s->st_value : NULL;
    return 1;
  }
  return dladdr(addr, info);
}

static void *s_dlopen(const char *name, int flags) {
  void *h = dlopen(name, flags & ~0x8 /* RTLD_DEEPBIND */);
  if (!h && name) fn_log("dlopen %s failed: %s", name, dlerror());
  return h;
}

// ---------------------------------------------------------------- /proc/self/exe
static int is_self_exe(const char *p) { return p && !strcmp(p, "/proc/self/exe"); }
static ssize_t s_readlink(const char *p, char *buf, size_t n) {
  if (is_self_exe(p)) {
    size_t l = strlen(img->path);
    if (l > n) l = n;
    memcpy(buf, img->path, l);
    return (ssize_t)l;
  }
  return readlink(p, buf, n);
}
static int s_open(const char *p, int flags, ...) {
  va_list ap;
  va_start(ap, flags);
  int mode = va_arg(ap, int);
  va_end(ap);
  return open(is_self_exe(p) ? img->path : p, flags, mode);
}
static FILE *s_fopen(const char *p, const char *m) { return fopen(is_self_exe(p) ? img->path : p, m); }
static char *s_realpath(const char *p, char *out) { return realpath(is_self_exe(p) ? img->path : p, out); }

// ---------------------------------------------------------------- table
extern int fn_getcontext(void *);
extern int fn_setcontext(const void *);
extern void fn_makecontext(void *, void (*)(void), int, ...);

#define S(n, f) {n, (void *)(f)}
static const struct { const char *name; void *fn; } shims[] = {
  // ctype / locale
  S("__ctype_b_loc", s_ctype_b_loc),
  S("__ctype_tolower_loc", s_ctype_tolower_loc),
  S("__newlocale", s_newlocale),
  S("__duplocale", s_duplocale),
  S("__freelocale", s_freelocale),
  S("__uselocale", s_uselocale),
  S("nl_langinfo", s_nl_langinfo),
  S("__nl_langinfo_l", s_nl_langinfo_l),
  S("__strcoll_l", s_strcoll_l),
  S("__strxfrm_l", s_strxfrm_l),
  S("__wcscoll_l", s_wcscoll_l),
  S("__wcsxfrm_l", s_wcsxfrm_l),
  S("__strtod_l", s_strtod_l),
  S("__strtof_l", s_strtof_l),
  S("strtold_l", s_strtold_l),
  S("__strftime_l", s_strftime_l),
  S("__wcsftime_l", s_wcsftime_l),
  S("__towlower_l", s_towlower_l),
  S("__towupper_l", s_towupper_l),
  S("__wctype_l", s_wctype_l),
  S("__iswctype_l", s_iswctype_l),
  // gettext
  S("gettext", s_gettext),
  S("dgettext", s_dgettext),
  S("bindtextdomain", s_bindtextdomain),
  S("bind_textdomain_codeset", s_bind_textdomain_codeset),
  // C23 / C99 names
  S("__isoc23_sscanf", s_sscanf),
  S("__isoc99_sscanf", s_sscanf),
  S("__isoc23_fscanf", s_fscanf),
  S("__isoc23_vsscanf", vsscanf),
  S("__isoc23_strtol", strtol),
  S("__isoc23_strtoll", strtoll),
  S("__isoc23_strtoul", strtoul),
  S("__isoc23_strtoull", strtoull),
  S("__isoc23_wcstol", wcstol),
  // glibc aliases
  S("__errno_location", __errno),
  S("__h_errno_location", __get_h_errno),
  S("__getauxval", getauxval),
  S("__xpg_basename", basename),
  S("__xpg_strerror_r", strerror_r),
  S("strerror_r", __gnu_strerror_r),
  S("bcmp", memcmp),
  S("__libc_single_threaded", &s_libc_single_threaded),
  S("__libc_start_main", s_libc_start_main),
  S("malloc_trim", s_malloc_trim),
  S("pthread_setcanceltype", s_pthread_setcanceltype),
  S("secure_getenv", s_secure_getenv),
  S("sysconf", s_sysconf),
  S("__cxa_thread_atexit_impl", s_cxa_thread_atexit_impl),
  S("__sigsetjmp", sigsetjmp),
  // ucontext (own implementation, glibc layout)
  S("getcontext", fn_getcontext),
  S("setcontext", fn_setcontext),
  S("makecontext", fn_makecontext),
  // signals
  S("sigaction", s_sigaction),
  // netdb / resolver
  S("getaddrinfo", s_getaddrinfo),
  S("freeaddrinfo", s_freeaddrinfo),
  S("gai_strerror", s_gai_strerror),
  S("getnameinfo", s_getnameinfo),
  S("__res_ninit", s_res_fail),
  S("__res_nclose", s_res_nclose),
  S("res_nquery", s_res_fail),
  S("dn_expand", s_res_fail),
  S("ns_initparse", s_res_fail),
  S("ns_parserr", s_res_fail),
  // dynamic linking
  S("_dl_find_object", s_dl_find_object),
  S("dl_iterate_phdr", s_dl_iterate_phdr),
  S("dladdr", s_dladdr),
  S("dlopen", s_dlopen),
  // own executable path
  S("readlink", s_readlink),
  S("open", s_open),
  S("fopen", s_fopen),
  S("fopen64", s_fopen),
  S("realpath", s_realpath),
};
#undef S

void *fn_shim_lookup(const char *name) {
  for (size_t i = 0; i < sizeof shims / sizeof shims[0]; i++)
    if (!strcmp(shims[i].name, name)) return shims[i].fn;
  return NULL;
}

void *fn_bionic_lookup(const char *name) {
  void *p = NULL;
  if (h_libc) p = dlsym(h_libc, name);
  if (!p && h_libm) p = dlsym(h_libm, name);
  if (!p && h_libdl) p = dlsym(h_libdl, name);
  return p;
}

void fn_shim_init(const fn_image *image) {
  img = image;
  h_libc = dlopen("libc.so", RTLD_NOW | RTLD_NOLOAD);
  h_libm = dlopen("libm.so", RTLD_NOW);
  h_libdl = dlopen("libdl.so", RTLD_NOW);
  if (!h_libc) fn_die("dlopen libc.so: %s", dlerror());
  init_ctype();
  init_locale();
}
