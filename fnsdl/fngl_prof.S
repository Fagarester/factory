// FNGL_PROF: one thunk per GL function the game looks up. A thunk times the
// driver call with the counter register and adds the result to the per-function
// totals (fngl_prof_note). Up to 64 bytes of stack arguments are passed on.
  .text
  .global fngl_prof_thunks
  .hidden fngl_prof_thunks
  .p2align 3
fngl_prof_thunks:
  .set i, 0
  .rept 1024
  mov x16, #i
  b prof_common
  .set i, i + 1
  .endr

prof_common:
  sub sp, sp, #272
  stp x29, x30, [sp, #256]
  add x29, sp, #256
  stp x0, x1, [sp, #64]
  stp x2, x3, [sp, #80]
  stp x4, x5, [sp, #96]
  stp x6, x7, [sp, #112]
  stp d0, d1, [sp, #128]
  stp d2, d3, [sp, #144]
  str x8, [sp, #192]
  str x16, [sp, #200]
  ldp x9, x10, [sp, #272]
  stp x9, x10, [sp, #0]
  ldp x9, x10, [sp, #288]
  stp x9, x10, [sp, #16]
  ldp x9, x10, [sp, #304]
  stp x9, x10, [sp, #32]
  ldp x9, x10, [sp, #320]
  stp x9, x10, [sp, #48]
  mrs x9, cntvct_el0
  str x9, [sp, #208]
  adrp x17, fngl_prof_real
  add x17, x17, :lo12:fngl_prof_real
  ldr x17, [x17, x16, lsl #3]
  ldp d0, d1, [sp, #128]
  blr x17
  mrs x2, cntvct_el0
  str x0, [sp, #216]
  str d0, [sp, #224]
  ldr x0, [sp, #200]
  ldr x1, [sp, #208]
  add x3, sp, #64
  bl fngl_prof_note
  ldr x0, [sp, #216]
  ldr d0, [sp, #224]
  ldp x29, x30, [sp, #256]
  add sp, sp, #272
  ret
