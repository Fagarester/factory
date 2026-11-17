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
  v0.6 (fence, CPU не дальше 1 кадра) на телефоне НЕ помог — опровергнуто.
- 2026-11-16 ПРИЧИНА «чужих спрайтов»/чёрных блоков найдена (воспроизведено в proot при 1216x2688,
  FN_OFFSCREEN_SIZE): потоковые буферы 1 МБ, игра делает glBufferData(NULL) и затем
  glMapBufferRange(off 0, WRITE|UNSYNCHRONIZED). Adreno при такой записи не даёт новое хранилище.
  Без UNSYNCHRONIZED — чисто, но 21 fps. Исправление (FNGL_SYNC_MAPS=2): карта с offset 0 →
  INVALIDATE_BUFFER без UNSYNCHRONIZED; 12 сцен меню чистые, 55–59 fps. Артефакт зависит от размера кадра.
- 2026-11-16: proot-воспроизведение графики телефона: FN_OFFSCREEN_SIZE=WxH (патч host SDL,
  patches/sdl3-offscreen-host-test.patch) + настройки video-memory-usage=all, texture-streaming=false.
  --window-size не меняет pbuffer. Сводка снимков: tools/contact_sheet.py.
- 2026-11-16 (v0.7–v0.10): трассирующие обёртки (draw/copy/readpixels) включались вместе с FNGL_SYNC_MAPS —
  лишняя функция на каждом draw. Разделено в v0.11: без FNGL_TRACE обёрнут только glMapBufferRange.
- 2026-11-16 текстуры без BC (fngl_tex.c, проверено в proot с FNGL_TEXTURE_TARGET=etc2 на Adreno):
  игра создаёт массивы атласов через glTexImage3D(BC-формат, NULL) — его тоже надо перехватывать;
  догрузки идут в формат текстуры (BC1 без альфы в BC1 с альфой) — целевой формат брать из текстуры;
  premultiplied alpha: у блоков с alpha=0 цвет нужен (свечение логотипа). EAC «множитель 0» и
  «таблица 13, индекс 4» на Adreno дают одинаковый результат; используется таблица 13.
  Кодировщик ETC2 ~0,5 мкс/блок на поток; полная загрузка атласов: ~122 с перекодирования (proot).
  GPU-сжатие игры с перекодированием не работает: самопроверка падает → сжатие на CPU.
- 2026-11-16 апскейл (fn_upscale.c, v0.12): игре сообщается меньшее окно (SDL GetWindowSize*/события/мышь),
  framebuffer 0 подменён внешним FBO (glBindFramebuffer, GL_*FRAMEBUFFER_BINDING, GL_BACK→ATTACHMENT0,
  InvalidateFramebuffer), при SwapWindow — проход Snapdragon GSR v1 (BSD-3, ESSL 3.10: textureGather
  с константным компонентом). Проверено в proot (0.6 и 0.75, без ошибок GL). Интерфейс игры при этом
  крупнее — UI раскладывается под уменьшенное окно.
- 2026-11-16 (v0.15): выходное разрешение = SurfaceHolder.setFixedSize (масштабирует композитор).
  SurfaceView после setFixedSize меряет себя по буферу (картинка в углу, сжатие при поворотах) — onMeasure в SDLSurface.
  SDLSurface нормировал касания по размеру буфера (mWidth) — в нашей копии SDL Java нормировка по
  размеру view и toBufferX/Y для мыши/пера/ПКМ. При обновлении SDL Java правку перенести.
- 2026-11-16 (v0.18): UPS = счётчик вызовов Scenario::update() (переход в начале функции на трамплин; 300 тиков бенчмарка = 300 вызовов). При новой версии игры проверить символ и пролог.
- 2026-11-17: ряд (горизонтальный LinearLayout) внутри вертикальной колонки без LayoutParams получает MATCH_PARENT и обрезается по ширине колонки (пропали F4 и нижний ряд в v0.26). Задавать WRAP_CONTENT.
- 2026-11-17: интернет и LAN в нативной сборке работают (проверил пользователь на двух телефонах).
