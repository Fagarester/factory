// makecontext() for the glibc ucontext_t layout (see ucontext.S).
#include <stdarg.h>
#include <stdint.h>
#include <string.h>

extern void fn_startcontext(void);

#define U64(p, off) (*(uint64_t *)((char *)(p) + (off)))

void fn_makecontext(void *uc, void (*func)(void), int argc, ...) {
  uintptr_t sp = (uintptr_t)U64(uc, 16) + (uintptr_t)U64(uc, 32);
  sp &= ~(uintptr_t)15;
  U64(uc, 184 + 19 * 8) = U64(uc, 8);  // x19 = uc_link
  U64(uc, 432) = sp;
  U64(uc, 440) = (uintptr_t)func;
  U64(uc, 184 + 29 * 8) = 0;
  U64(uc, 184 + 30 * 8) = (uintptr_t)fn_startcontext;
  va_list ap;
  va_start(ap, argc);
  for (int i = 0; i < argc && i < 8; i++) U64(uc, 184 + i * 8) = va_arg(ap, uint64_t);
  va_end(ap);
}
