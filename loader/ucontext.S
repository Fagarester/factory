// getcontext/setcontext/makecontext with the glibc aarch64 ucontext_t layout:
//   uc_link 8, uc_stack {ss_sp 16, ss_flags 24, ss_size 32}, uc_sigmask 40,
//   uc_mcontext 176: fault_address 176, regs[31] 184, sp 432, pc 440,
//   pstate 448, __reserved 464 (fpsimd_context: magic, size, fpsr, fpcr, vregs).
#define oX0 184
#define oSP 432
#define oPC 440
#define oEXT 464
#define oFPSR (oEXT + 8)
#define oFPCR (oEXT + 12)
#define oV0 (oEXT + 16)
#define FPSIMD_MAGIC 0x46508001
#define FPSIMD_SIZE 528

  .text
  .globl fn_getcontext
  .type fn_getcontext, %function
  .p2align 4
fn_getcontext:
  str xzr, [x0, #oX0 + 0 * 8]
  stp x18, x19, [x0, #oX0 + 18 * 8]
  stp x20, x21, [x0, #oX0 + 20 * 8]
  stp x22, x23, [x0, #oX0 + 22 * 8]
  stp x24, x25, [x0, #oX0 + 24 * 8]
  stp x26, x27, [x0, #oX0 + 26 * 8]
  stp x28, x29, [x0, #oX0 + 28 * 8]
  str x30, [x0, #oX0 + 30 * 8]
  str x30, [x0, #oPC]
  mov x2, sp
  str x2, [x0, #oSP]
  str xzr, [x0, #oPC + 8]
  // fpsimd_context
  add x2, x0, #oEXT
  mov w3, #(FPSIMD_MAGIC & 0xffff)
  movk w3, #(FPSIMD_MAGIC >> 16), lsl #16
  mov w4, #FPSIMD_SIZE
  stp w3, w4, [x2]
  mrs x3, fpsr
  mrs x4, fpcr
  stp w3, w4, [x2, #8]
  add x2, x0, #oV0
  stp q8, q9, [x2, #8 * 16]
  stp q10, q11, [x2, #10 * 16]
  stp q12, q13, [x2, #12 * 16]
  stp q14, q15, [x2, #14 * 16]
  add x2, x0, #(oEXT + FPSIMD_SIZE)
  stp xzr, xzr, [x2]
  // current signal mask -> uc_sigmask
  mov x9, x0
  mov x0, #0          // SIG_BLOCK
  mov x1, #0
  add x2, x9, #40
  mov x3, #8
  mov x8, #135        // rt_sigprocmask
  svc #0
  mov x0, #0
  ret
  .size fn_getcontext, .-fn_getcontext

  .globl fn_setcontext
  .type fn_setcontext, %function
  .p2align 4
fn_setcontext:
  mov x9, x0
  mov x0, #2          // SIG_SETMASK
  add x1, x9, #40
  mov x2, #0
  mov x3, #8
  mov x8, #135
  svc #0
  cmn x0, #4095
  b.cs 1f
  mov x0, x9
  add x2, x0, #oEXT
  ldp w3, w4, [x2, #8]
  msr fpsr, x3
  msr fpcr, x4
  add x2, x0, #oV0
  ldp q8, q9, [x2, #8 * 16]
  ldp q10, q11, [x2, #10 * 16]
  ldp q12, q13, [x2, #12 * 16]
  ldp q14, q15, [x2, #14 * 16]
  ldp x18, x19, [x0, #oX0 + 18 * 8]
  ldp x20, x21, [x0, #oX0 + 20 * 8]
  ldp x22, x23, [x0, #oX0 + 22 * 8]
  ldp x24, x25, [x0, #oX0 + 24 * 8]
  ldp x26, x27, [x0, #oX0 + 26 * 8]
  ldp x28, x29, [x0, #oX0 + 28 * 8]
  ldr x30, [x0, #oX0 + 30 * 8]
  ldr x2, [x0, #oSP]
  mov sp, x2
  ldr x16, [x0, #oPC]
  ldp x2, x3, [x0, #oX0 + 2 * 8]
  ldp x4, x5, [x0, #oX0 + 4 * 8]
  ldp x6, x7, [x0, #oX0 + 6 * 8]
  ldr x1, [x0, #oX0 + 1 * 8]
  ldr x0, [x0, #oX0 + 0 * 8]
  br x16
1:
  neg w0, w0
  mov x9, x0
  bl __errno
  str w9, [x0]
  mov x0, #-1
  ret
  .size fn_setcontext, .-fn_setcontext

// Entry of a makecontext() function returns here; x19 = uc_link.
  .globl fn_startcontext
  .type fn_startcontext, %function
  .p2align 4
fn_startcontext:
  mov x0, x19
  cbnz x0, fn_setcontext
  mov x0, #0
  bl exit
  brk #1
  .size fn_startcontext, .-fn_startcontext
