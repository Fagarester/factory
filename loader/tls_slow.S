// Slow path of the TLS trampolines: allocates the TLS block of the current
// thread. Preserves every register except x0 (result) and x30 (link).
  .text
  .globl fn_tls_slow
  .type fn_tls_slow, %function
  .p2align 4
fn_tls_slow:
  sub sp, sp, #704
  stp x1, x2, [sp, #0]
  stp x3, x4, [sp, #16]
  stp x5, x6, [sp, #32]
  stp x7, x8, [sp, #48]
  stp x9, x10, [sp, #64]
  stp x11, x12, [sp, #80]
  stp x13, x14, [sp, #96]
  stp x15, x16, [sp, #112]
  stp x17, x18, [sp, #128]
  stp x29, x30, [sp, #144]
  mrs x1, nzcv
  mrs x2, fpsr
  stp x1, x2, [sp, #160]
  stp q0, q1, [sp, #176]
  stp q2, q3, [sp, #208]
  stp q4, q5, [sp, #240]
  stp q6, q7, [sp, #272]
  stp q8, q9, [sp, #304]
  stp q10, q11, [sp, #336]
  stp q12, q13, [sp, #368]
  stp q14, q15, [sp, #400]
  stp q16, q17, [sp, #432]
  stp q18, q19, [sp, #464]
  stp q20, q21, [sp, #496]
  stp q22, q23, [sp, #528]
  stp q24, q25, [sp, #560]
  stp q26, q27, [sp, #592]
  stp q28, q29, [sp, #624]
  stp q30, q31, [sp, #656]
  add x29, sp, #144
  bl fn_tls_alloc
  ldp q0, q1, [sp, #176]
  ldp q2, q3, [sp, #208]
  ldp q4, q5, [sp, #240]
  ldp q6, q7, [sp, #272]
  ldp q8, q9, [sp, #304]
  ldp q10, q11, [sp, #336]
  ldp q12, q13, [sp, #368]
  ldp q14, q15, [sp, #400]
  ldp q16, q17, [sp, #432]
  ldp q18, q19, [sp, #464]
  ldp q20, q21, [sp, #496]
  ldp q22, q23, [sp, #528]
  ldp q24, q25, [sp, #560]
  ldp q26, q27, [sp, #592]
  ldp q28, q29, [sp, #624]
  ldp q30, q31, [sp, #656]
  ldp x1, x2, [sp, #160]
  msr nzcv, x1
  msr fpsr, x2
  ldp x1, x2, [sp, #0]
  ldp x3, x4, [sp, #16]
  ldp x5, x6, [sp, #32]
  ldp x7, x8, [sp, #48]
  ldp x9, x10, [sp, #64]
  ldp x11, x12, [sp, #80]
  ldp x13, x14, [sp, #96]
  ldp x15, x16, [sp, #112]
  ldp x17, x18, [sp, #128]
  ldp x29, x30, [sp, #144]
  add sp, sp, #704
  ret
  .size fn_tls_slow, .-fn_tls_slow
