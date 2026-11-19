# FNative — Factorio on Android, natively

FNative runs **your own copy of the official Factorio for Linux ARM64** on an Android phone — no emulator, no
Linux environment, no X server. The game's own program runs on the phone's CPU inside the app's process; a small
loader connects it to Android's C library, and a thin layer passes its OpenGL calls to the phone's GLES driver.

**Unofficial, not affiliated with or endorsed by Wube Software.** Free and non-commercial. The app contains no
game files: you need to own Factorio and provide the Linux ARM64 build (a folder or the `.tar.xz` archive from
factorio.com; the free demo can be downloaded from the app).

Written by Claude (Anthropic's AI) under Fagarester's direction.

## How it works

- `loader/` — ELF loader for the game binary (glibc → bionic shim, TLS trampolines), small hooks for the app
  (UPS counter, the quick bar, in-game / menu state).
- `fnsdl/` — SDL3 dynamic-API layer and the GL layer: desktop GL calls → GLES 3.2, texture formats (BC native,
  ETC2 transcoding and cache on GPUs without BC), upscaling (Snapdragon GSR), Adreno workarounds.
- `fnxz/` — native `.tar.xz` decoding (liblzma) for unpacking the game archive.
- `android/` — the app: first start (find the game, choose folder / archive / demo), settings (hold the app icon →
  "Настройки"), on-screen controls (stick, multi-function keys, themes, tutorial), quick bar panel, mouse support.

## Installing

Download the APK from [Releases](../../releases), install, start it and follow the first-start screen.
The app asks for file access (the game runs from its folder on the phone's storage).

## Building

Built on the phone itself (Ubuntu in proot on Android, arm64) or any Linux with the Android SDK and NDK 29:

1. `git clone --depth 1 -b release-3.4.20 https://github.com/libsdl-org/SDL.git third_party/SDL3`
   and `git -C third_party/SDL3 apply ../../patches/sdl3-offscreen-default-display.patch`
2. SDL3 for Android: `cmake -S third_party/SDL3 -B work/sdl3-build -G Ninja
   -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a
   -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release`
3. `tools/build-apk.sh` — builds the loader, the GL layer, liblzma (downloaded and checked by SHA-256) and the APK
   into `out/`. Signing uses your own keystore (`FNATIVE_SIGNING` → a `keystore.properties` file), else debug.

Notes in Russian: `CONTINUE.md` (state of the work), `LEARNING.md` (lessons, causes of fixed problems).

## License

MIT (see `LICENSE`); third-party parts keep their own licenses.
