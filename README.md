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

## License

MIT (see `LICENSE`); third-party parts keep their own licenses.
