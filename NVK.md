# Native Vulkan renderer (FN_RENDERER=nvk or vulkan; launcher: one checkbox "Рендер Vulkan"; since 0.137, the only
# Vulkan renderer since 0.142: the GL-on-Vulkan bridge vk_gl.c was removed)

The game's GraphicsInterfaceOpenGL methods that render are replaced at run time (loader/redirect.c: branch at the
function start, libfnsdl fills it) by Vulkan 1.1 code working on the game's own objects (fnsdl/nvk). No GL call on
the drawing path; Vulkan 1.1 core (render passes), push descriptors when present.

- nvk_main.c: start, layout check (instructions with the expected field offsets must be in the game's functions,
  else OpenGL ES), name tables. nvk.h: object layouts of 2.1.19 (from analysis/gl-backend.s, not published).
- nvk_draw.c: createPipelineStateObject (shader text from the game's fetchShader -> vk_shader.cpp -> SPIR-V),
  bind*/draw*, render passes, clears, Framebuffer clear/copyTo/blitTo/blitTo3DTexture.
- nvk_res.c: buffers, uniform buffers, textures (BC -> ETC2 on Mali), arrays, 3D, samplers, framebuffer objects.
- ../vk/vk_window.c: window framebuffer, Android swapchain and present, screenshots, GL queries (strings, limits,
  timers). ../vk/vk_core.c: device, memory, frames, transfers.
- nvk_gl.c: the few GL entry points of game code kept as is (setViewport, bindFramebuffer, setScissorRectangle,
  swapBuffers, destructors' glDelete*, shader store). Any other GL call logs "MISSING GL function called".
- nvk_thunk.S: create* return unique_ptr at x8.
- Rule: replace only functions that call GL (list: analysis/gl-callers-2.1.19.txt, tools/gl-callers.py).
- Tools: tools/vtable-dump.py, tools/game-read.py, tools/gl-callers.py. Proot run: /tmp/fn-nvk/run.sh (copies to
  /data/data/com.termux/cache/fn-nvk; Vulkan is not seen from /root).

glCopyImageSubData (0.143, nvk_copy_image_sub): the game builds atlas mipmaps on the GPU and copies them into the
levels with it; without it every level but the game's custom icon mipmaps stayed empty (GUI buttons without
background, faint inventory icons, noisy sprites at distance). Not done (Mali): GPU BC blocks into ETC2 textures.
Debug: NVK_TRACE=1 (textures, samplers, uploads, 3D LUTs, PSO bindings), NVK_TRACE_AT=<s> (one frame of draws),
NVK_DUMP_SHADERS=<dir>, NVK_SHADER_DIR=<dir> (replace a shader), skipped draws in the log every 600 frames.

Pipeline cache on disk (0.144): <FN_PROF_DIR>/nvk-pipelines.bin (Download/FactorioNative); proot: 22 pipelines
720 ms -> 7 ms on the second start. The log reports programs/pipelines built and their time every 600 frames.

Pre-rotation (0.146; on by default since 0.154, NVK_PREROTATE=0 turns it off): swapchain in the panel orientation, turned by the vertex shader and the
turned viewport/scissor; fragment shaders get GL window coordinates back (vk_shader.cpp window_prelude).
Offscreen check: NVK_FAKE_WINDOW=0..3.

Render scale (0.147): the game draws into the smaller offscreen window image; at present vk_window.c up_draw
draws it into the swapchain image with Snapdragon GSR v1 or bilinear (flipped, turned). Live changes: fn_upscale_apply_values.

SPIR-V cache (0.151): <FN_PROF_DIR>/nvk-spirv.bin, header with the rewrite build (another build: made anew).
Descriptors (0.151): same layout -> only changed bindings pushed. NVK_TRACE_AT also logs passes (load ops, draws, end).

Plan and what is left (with reasons and expected gains, Russian): CONTINUE.md, "План Vulkan (nvk)".

Not done yet (0.137): StagingBitmap, TextureCopyHelper, readFramebufferToMemory, map loading, Mali, phone test, game versions other than 2.1.19 (layout check decides).
