# Factorio native (bionic) — состояние на 2026-11-16 (пауза)

Цель: официальный Linux ARM64 Factorio в процессе bionic, рендер напрямую на GLES, без мостов.

## Готово и проверено (proot на телефоне, Adreno 830)
- loader/ (out/fnative): загрузчик ELF, релокации, 294 `mrs tpidr_el0` -> трамплины
  (фиктивный tp из bionic-слота 2), shim glibc->bionic (shim.c), ucontext.
- `--version`, `--create` и `--benchmark` работают. Сумма симуляции на 2m_final.zip
  совпала с glibc (716673159). Тик 27,1 мс против 26,2 мс у glibc (один прогон).
- DWARF только line-tables: раскладок классов нет. Путь графики: родной
  GraphicsInterfaceOpenGL + функции GLES драйвера напрямую через glad.
- fnsdl/ (out/libfnsdl.so): SDL_DYNAPI_entry, подмена SDL_GL_Set/GetAttribute
  и SDL_GL_GetProcAddress; fngl.c — GL-слой (адаптеры + заглушки с логом).
- Контекст GLES 3.2 создаётся внутри игры (offscreen SDL, Adreno).

## Где остановились
`Failed to load glad`: glad падает при 0 расширений (malloc(0)).
Следующий шаг: заполнить список расширений в fnsdl/fngl.c
(desktop-имена по GLES-расширениям драйвера: analysis/adreno830_extensions.txt),
затем смотреть логи `MISSING GL function` и ошибки шейдеров.

## Запуск теста в proot
tools/build-loader.sh; tools/build-fnsdl.sh
Тестовый SDL: work/sdl3-host (Linux-вариант на bionic, -U__ANDROID__,
патч patches/sdl3-offscreen-default-display.patch). Android SDL: work/sdl3-build.
cd /tmp/fn-test; SDL3_DYNAMIC_API=/root/factorio-native/out/libfnsdl.so \
 FN_SDL_LIBRARY=/root/factorio-native/work/sdl3-host/libSDL3.so.0 SDL_VIDEO_DRIVER=offscreen \
 SDL_EGL_LIBRARY=libEGL.so SDL_OPENGL_LIBRARY=libGLESv2.so \
 /root/factorio-native/out/fnative /root/factorio-test/game/bin/arm64/factorio -c /tmp/fn-test/config.ini --disable-audio

## Дальше
1. Расширения + адаптеры GL до главного меню (offscreen, скриншот через glReadPixels).
2. APK: SDLActivity + libSDL3 (Android) + libfnative.so (загрузчик как .so) + libfnsdl.
3. Проверить гипотезу: медленнее из-за malloc bionic (scudo).
