// Calls a C function at the start of a game function (fn_hook_call in tick.c). The per-hook
// trampoline loads x16 = its data {callback, continuation} and jumps here. All argument and scratch registers
// (x0-x17, x29, x30, q0-q7) are kept: the game function then runs as if nothing happened.
    .text
    .globl fn_call_stub
    .hidden fn_call_stub
    .type fn_call_stub, %function
    .p2align 2
fn_call_stub:
    sub   sp, sp, #320
    stp   x0, x1, [sp, #0]
    stp   x2, x3, [sp, #16]
    stp   x4, x5, [sp, #32]
    stp   x6, x7, [sp, #48]
    stp   x8, x9, [sp, #64]
    stp   x10, x11, [sp, #80]
    stp   x12, x13, [sp, #96]
    stp   x14, x15, [sp, #112]
    stp   x16, x17, [sp, #128]
    stp   x29, x30, [sp, #144]
    stp   q0, q1, [sp, #160]
    stp   q2, q3, [sp, #192]
    stp   q4, q5, [sp, #224]
    stp   q6, q7, [sp, #256]
    add   x29, sp, #144
    ldr   x17, [x16]           // callback
    blr   x17
    ldr   x16, [sp, #128]      // the hook's data
    ldr   x17, [x16, #8]       // continuation: the moved first instruction, then back into the function
    ldp   q6, q7, [sp, #256]
    ldp   q4, q5, [sp, #224]
    ldp   q2, q3, [sp, #192]
    ldp   q0, q1, [sp, #160]
    ldp   x29, x30, [sp, #144]
    ldp   x14, x15, [sp, #112]
    ldp   x12, x13, [sp, #96]
    ldp   x10, x11, [sp, #80]
    ldp   x8, x9, [sp, #64]
    ldp   x6, x7, [sp, #48]
    ldp   x4, x5, [sp, #32]
    ldp   x2, x3, [sp, #16]
    ldp   x0, x1, [sp, #0]
    add   sp, sp, #320
    br    x17
    .size fn_call_stub, .-fn_call_stub
