> 2026-11-24, 0.142: the bridge described here (fnsdl/vk/vk_gl.c, FN_RENDERER=vulkan) was removed; the only Vulkan
> renderer is nvk (NVK.md), FN_RENDERER=vulkan now starts it too. vk_core.c and the window/present part (now
> vk_window.c) are still used. Kept as the record of the bridge and its measurements.

# Vulkan renderer (FN_RENDERER=vulkan; from FNative Lab, in FNative since 0.136 — launcher engineering settings)

The game keeps its own OpenGL backend (GraphicsInterfaceOpenGL). The 92 GL functions it calls are implemented on
Vulkan in fnsdl/vk (no GLES driver, no ANGLE):
- vk_shader.cpp: the game's GLSL 330 -> Vulkan GLSL 450 (bindings, varying locations by name, depth -w..w -> 0..w)
  -> SPIR-V with glslang from the NDK (tools/build-glslang.sh). All 81 game shaders compile (out/vk-shadertest).
- vk_core.c: device (Vulkan 1.1 + dynamic_rendering; push_descriptor if present, else per-frame descriptor pools
  (Mali; FNVK_NO_PUSH=1 tests that path); BC or ETC2 textures), host memory
  slices, 3 frames in flight, uploads/readbacks.
- vk_gl.c: GL objects and state; passes start at the first draw; a full clear is deferred on the image and becomes
  the next pass's loadOp CLEAR (or a transfer clear when the image is read first); depth/stencil that no pass ever
  loaded is not stored; pipelines cached by state; Android: own swapchain, the window is made without OpenGL
  (fnsdl.c, vk_android), the game draws straight into the swapchain image (y mirrored by viewport/scissor).
- No BC in Vulkan (Mali): BC uploads converted to ETC2/EAC (fngl_tex.c, same disk cache as GLES).
- Render scale: the game sees the smaller window, the present blit scales (bilinear).
Images keep GL row order; only the present blit flips.

Switch: launcher settings "Рендер Vulkan"; the lab run (launcher, measurement section) compares OpenGL ES and Vulkan.
Analysis (not in git): analysis/gl-backend.s (the game's GL backend, GL calls annotated).
Proot tests: see LEARNING.md (program location), FNGL_PROF=1 (per-frame GL time), FNGL_PROF_DUMP=N|all.

Open (lab10, 2026-11-21): lab9 energy per frame = GLES on Adreno 830 (before deferred clears); GPU compression path loses the device (FNVK_BC_READBACK=1, LEARNING); no GSR in Vulkan (bilinear); no pre-rotation (system rotates); timestamps are CPU time.
Passes per frame (nauvis_lab): 11 -> 6, color loads 7 -> 3, depth loads 1 -> 0, depth stores 1 -> 0.

lab11 (2026-11-23): lab10 on Mali (MT6886) never ran Vulkan (no push_descriptor) - fixed; awaiting the Mali run.
Mali (2026-11-23, Mali-G610 MC4): works since lab11 (no push descriptors -> per-frame pools); energy per frame = GLES.

