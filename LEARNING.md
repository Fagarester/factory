# LEARNING — Factorio native (bionic)

Подтверждённые уроки проекта. Состояние работы — в CONTINUE.md.

- 2026-11-16, бинарник 2.1.19: DWARF собран как line-tables-only (в .debug_abbrev нет типов).
  Раскладки классов рендера из DWARF не достать. Подтверждение: `llvm-dwarfdump --debug-abbrev`.
- 2026-11-16: bionic `__cxa_thread_atexit_impl` падает с «Couldn't find soinfo by dso_handle»,
  если dso_handle не из известной библиотеки. Передавать `__dso_handle` загрузчика (shim.c).
- 2026-11-16: glad (v1) в игре возвращает ошибку при 0 расширений GL (malloc(0) == NULL).
  Список расширений не может быть пустым (fngl.c, build_extensions).
- 2026-11-16: GLES не знает `GL_TEXTURE_LOD_BIAS` у сэмплеров: INVALID_ENUM в createSamplerState.
  Параметр отбрасывается; смещение −0,48 одного сэмплера теряется.
- 2026-11-16: самопроверка GPU-сжатия (`testGpuAcceleratedCompression`) требует
  `glGetTexImage` для несжатых и `GL_TEXTURE_COMPRESSED_IMAGE_SIZE` + `glGetCompressedTexImage`
  для BC-текстур. В GLES: FBO+glReadPixels, отрисовка в RGBA8 для неподключаемых текстур,
  копирование BC в RG32UI/RGBA32UI через glCopyImageSubData. После этого тест проходит.
- 2026-11-16, proot: SDL3 Android-сборка не грузится из proot (libandroid → libicu/libvendorutils
  вне namespace). Для тестов в proot — work/sdl3-host (Linux-вариант на bionic, -U__ANDROID__,
  offscreen + patches/sdl3-offscreen-default-display.patch).
- 2026-11-16: снимки по номеру кадра ненадёжны (загрузка 40–150 с). Использовать FN_SHOT_EVERY.
  Одиночный снимок с «мозаикой» в первые секунды меню не воспроизвёлся (не подтверждено).
- 2026-11-16: Termux/proot не читает Android/data (Permission denied), adb не подключён. Журналы и write-data приложения — в Download/FactorioNative.
- 2026-11-16: в Android-сборке SDL3 dynapi выключен в SDL_dynapi.h (`SDL_DYNAMIC_API 0`),
  `SDL_DYNAPI_entry` нет. libfnsdl заполняет таблицу через dlsym по SDL_dynapi_procs.h
  (все 1270 функций экспортированы). С dynapi-сборкой SDL (proot-тест) dlsym даёт переходники
  → бесконечный цикл; в proot нужен путь через SDL_DYNAPI_entry.
- 2026-11-16, пользователь: APK проверяет он сам на телефоне; не воспроизводить Android-условия в proot.
- 2026-11-16: отключение ARB_buffer_storage (v0.5) НЕ убрало «чужие спрайты» на телефоне — гипотеза опровергнута.
  Игра пишет вершины через glMapBufferRange(WRITE|UNSYNCHRONIZED=0x22) без fence (трассировка в proot).
  Версия v0.6: fence после SwapWindow и ожидание кадра N-1 (FN_FRAMES_IN_FLIGHT). На телефоне не подтверждено.
- 2026-11-16: в proot артефакт не воспроизводится (offscreen pbuffer, синхронный показ). Окно offscreen
  не меняет размер pbuffer: снимки при --window-size неверны.
