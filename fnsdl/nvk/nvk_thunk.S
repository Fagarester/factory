// The game's create* methods return std::unique_ptr in memory at x8 (C++ indirect result). These entry points
// pass x8 as the next free argument register (or on the stack) to the C implementations in nvk_res.c/nvk_draw.c.
	.text

.macro X8_IN reg, name
	.globl \name
	.hidden \name
	.type \name, %function
\name:
	mov \reg, x8
	b \name\()_c
.endm

	X8_IN x2, nvk_t_createUniformBuffer           // (gi, size)
	X8_IN x4, nvk_t_createVideoBuffer             // (gi, type, options lo, options hi)
	X8_IN x5, nvk_t_createVideoBitmapArray        // (gi, w, h, layers, options*)
	X8_IN x2, nvk_t_createSamplerState            // (gi, spec*)
	X8_IN x2, nvk_t_createPipelineStateObject     // (gi, spec*)
	X8_IN x1, nvk_t_copyToMemoryBitmap            // (bitmap)

// createVideoBitmap3D(gi, data, w, h, d, stride, orientation, options*): x0..x7 taken, x8 goes on the stack.
	.globl nvk_t_createVideoBitmap3D
	.hidden nvk_t_createVideoBitmap3D
	.type nvk_t_createVideoBitmap3D, %function
nvk_t_createVideoBitmap3D:
	sub sp, sp, #32
	stp x29, x30, [sp, #16]
	add x29, sp, #16
	str x8, [sp]
	bl nvk_t_createVideoBitmap3D_c
	ldp x29, x30, [sp, #16]
	add sp, sp, #32
	ret
