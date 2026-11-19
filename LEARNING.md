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
- 2026-11-16 (v0.18): UPS = счётчик вызовов функции тика (переход в начале функции на трамплин).
  2026-11-17: в 2.1.25 Scenario::update() встроена — UPS=0. Список кандидатов в elf.c: Scenario::update (2.1.19),
  Scenario::updateStep (2.1.25), Map::updateEntities(bool); проверка: --benchmark N тиков = N вызовов.
  Загрузчик 2.1.25 запустил без правок (299 мест TLS).
- 2026-11-17: ряд (горизонтальный LinearLayout) внутри вертикальной колонки без LayoutParams получает MATCH_PARENT и обрезается по ширине колонки (пропали F4 и нижний ряд в v0.26). Задавать WRAP_CONTENT.
- 2026-11-17: интернет и LAN в нативной сборке работают (проверил пользователь на двух телефонах).
- 2026-11-17, пользователь: MediaTek Dimensity 9300+ (Mali-G720) — работает, артефактов нет, первая загрузка
  долгая (BC→ETC2 на CPU), мегабаза ~15–20 FPS (без HUD, не замерено). Кириллица работает.
- 2026-11-17: системный ANGLE: /system/lib64/lib{EGL,GLESv2}_angle.so (зависимости только публичные NDK). В proot Vulkan не инициализируется (не показатель). FN_ANGLE обрабатывать ПОСЛЕ разбора --fn-env.
- 2026-11-17 кэши (v0.36): Adreno/есть BC — собственный кэш игры cache-sprite-atlas (proot: из кэша 3–6 с,
  init ~20 с вместо 70–120 с, atlas-cache.dat ~0,7 ГБ). На Mali его НЕЛЬЗЯ: запись кэша читает BC обратно
  (у нас нули) — лаунчер ставит cache-sprite-atlas=false. Mali — свой кэш ETC2 (fnsdl/tex/cache.c, ключ = хэш
  BC-данных загрузки): 2-й запуск 0 промахов, ~4 с вместо ~118 с, ~2,5 ГБ.
- 2026-11-17 подпись: свой ключ /root/factorio-native-keys (не в git), SHA-256 09dd73cc…; смена с debug-ключа
  требует удалить старое приложение. Имя приложения FNative, package com.factorynative прежний.
- 2026-11-17 Mali 9300+ замер (v0.35, 2m_final): FPS=UPS ≈29.5, макс. частота ядра ~1,7 ГГц, 4,8 Вт → упор в
  ограничение частот прошивкой. ANGLE на Xiaomi Android 14 нет в /system/lib64 (фолбэк сработал).
  Варианты по частотам: /sdcard/Download/FNative-CPU-frequency-options-2026-11-17.md.
- 2026-11-17: выбор ядер (v0.37) пользователь проверил — «вроде лучше когда все» (без замеров); функция убрана в v0.38 (git revert).
- 2026-11-17 (v0.44): горизонтальный LinearLayout по умолчанию выравнивает детей по базовой линии текста. Клавиши с иконкой
  (compound drawable сверху) и без неё в одном ряду → текстовые (Ctrl/Alt) сдвигаются вниз (скриншот пользователя, v0.43).
  В рядах клавиш setBaselineAligned(false).
- 2026-11-17 (v0.47): офлайн-сборка не разрешает commons-compress 1.21 (нет родительских POM в кэше Gradle); 1.28 есть,
  но тянет commons-io/codec/lang3. Распаковка tar — свой короткий читатель в FirstRun (ustar, GNU 'L', pax path),
  xz — org.tukaani:xz:1.7 (в кэше). Проверено на factorio-demo 2.1.23: 8450 файлов, имена и размеры = `tar -tvJf`,
  содержимое выборки = `tar -xJf`.
- 2026-11-17: Java-xz (tukaani) в 4 раза медленнее системного xz: демо 1,68 ГиБ — 118 с против 28,5 с (proot, HotSpot).
  Полная распаковка демо в proot 8,3 мин, из них большая часть — запись 8450 файлов через proot. На телефоне не замерено.
  Быстрее — нативный xz (как xz_jni в /root/factory).
- 2026-11-17 (v0.49 падал при запуске, журнал пользователя): Window.getInsetsController() до setContentView
  возвращает NPE внутри PhoneWindow (нет DecorView). Скрывать системные панели после setContentView
  (или сначала getDecorView()). Исправлено в v0.50.
- 2026-11-17 (v0.54, жалоба пользователя): ViewGroup отдаёт каждый следующий палец над View, уже держащим палец,
  этому View без вопроса (onTouchEvent=false не помогает). Полноэкранный стик поверх тачпада забирал палец курсора.
  Сенсорные View делать размером своей зоны (стик — левые 40 % × нижние 75 %).
- 2026-11-18 панель быстрого доступа из памяти игры (loader/quickbar.c; проверено в proot на 2.1.24 по сейву пользователя,
  имена совпали: burner-mining-drill, stone-furnace, wood, iron-plate / iron-ore, coal, copper-ore…): QuickBar* = x1
  конструктора `_ZN11QuickBarGuiC2ER8QuickBar10GuiContext` (перехват fn_hook_store); страницы vector<vector<QuickBarSlot>>
  в QuickBar+0x10 (10×10), слот 0x90 байт, ID предмета u16 в +0x08 (не +0x50!), тип u8 в +0x88 (1 = предмет);
  ID → `PrototypeList<ItemPrototype>::indexToPrototype` (vector), имя std::string в прототипе +0x08.
  Указатели кучи Android с тегом 0xb4 в старшем байте — проверки адресов без тега. Второй ряд панели в игре без клавиш
  по умолчанию (quick-bar-button-N-secondary пусто), Shift+цифра занята action-bar-select-page; свободно CONTROL + цифра.
  QuickBar+0x28 (vector<u8>, 10 шт.) — предположительно страница каждого ряда, не подтверждено.
- 2026-11-18 экранная клавиатура (окно логина при первом запуске): игра вызывает SDL_StartTextInputWithProperties
  один раз (трассировка FNSDL_TEXT_TRACE в proot), т.е. клавиатуру повторно не просит. Пользователь: «Назад» не всегда
  закрывает клавиатуру (Android 16 жесты, 9300+), было и до Back→Esc (v0.32). v0.86: при видимой клавиатуре
  (WindowInsets ime) «Назад» сам прячет её (как SDL COMMAND_TEXTEDIT_HIDE + onNativeKeyboardFocusLost), Esc не шлёт.
  На телефоне не проверено.
- 2026-11-18 ПРИЧИНА «дёргается клавиатура» (keyboard.log v0.87 с телефона): на «Назад» SDL получал «focus lost» и
  выключал ввод; поле игры оставалось в фокусе, и игра через ~33 мс снова вызывала SDL_StartTextInputWithProperties
  (active 0) — клавиатура возвращалась. v0.88: только прятать клавиатуру (imm + onNativeScreenKeyboardHidden), ввод не трогать.
- 2026-11-18 «в игре или в меню» (loader/quickbar.c fn_in_game, проверено в proot на 2.1.24: меню 0, загрузка сейва
  0 → 1, выход → 0): конструктор `GameView(Game&, Player*, NamedBool<IsSImulationTag> (w3), …)` — флаг у фоновой
  симуляции меню; `~GameView` D2 (D0 его вызывает). Перехват двух регистров — fn_hook_store2. Счётчик тиков для этого не
  годится: в меню тоже идёт симуляция.
- 2026-11-19 мышь (видео тестера): в нашей копии SDL Java клики мыши шли через SDLSurface.onTouch с toBufferX/Y, а
  движение (ACTION_HOVER_MOVE в SDLGenericMotionListener) — без пересчёта; при выходном разрешении < 100 % и при подъёме
  картинки курсор игры и клики расходились. Большая стрелка — курсор Factorio, SDL ставит его как PointerIcon. v0.96:
  мышь целиком в GameActivity.dispatchGenericMotionEvent/dispatchTouchEvent → onNativeMouse с пересчётом; PointerIcon
  TYPE_NULL на наших View и в SDLActivity.setCustomCursor/setSystemCursor (fnHidePointer); свой курсор поверх всего.
- 2026-11-19 клики курсора при открытой экранной клавиатуре (input.log + FNSDL_EVENT_TRACE с телефона): клики доходят
  до игры (SDL_EVENT_MOUSE_BUTTON_DOWN/UP, окно в фокусе, keyboard 1), но игра реагирует только без клавиатуры. v0.100:
  клик курсора при видимой клавиатуре — сначала спрятать её (ввод не выключать), через 150 мс клик в ту же точку игры.
  ПРИЧИНА (v0.103, подтверждено пользователем): игра не принимает клики, пока SDL считает экранную клавиатуру
  показанной. Достаточно на время клика onNativeScreenKeyboardHidden(), затем onNativeScreenKeyboardShown(), если ввод
  ещё идёт (nativeTextWanted из libfnsdl), — клавиатура не мигает. Фокус SDLDummyEdit как признак ввода ненадёжен.
- 2026-11-19 ПРИЧИНА «прыгающих» спрайтов с кэшем атласов (Adreno 830, тест tests/bcreadback.c на телефоне):
  один glCopyImageSubData всего BC-уровня 8192×8184 в RG32UI/RGBA32UI теряет блоки после нескольких десятков строк
  блоков (glReadPixels RGBA32UI сам по себе точен). Игра пишет cache-sprite-atlas через наш glGetCompressedTexImage →
  кэш с испорченными участками. Копирование кусками 64×64 блока — точно; неполные краевые блоки (уровень не кратен 4)
  драйвер не копирует ни так, ни отдельно (1–3 краевых текселя мелких mip-уровней). Кусок 256 на таких уровнях тоже
  ломается. v0.113: куски 64 + удаление старого atlas-cache.dat один раз (prefs atlasCacheFix113).
- 2026-11-19 локализация (v0.114): новый текст интерфейса пишется в коде по-русски в Tr.t("…") и добавляется строкой
  «исходник<TAB>перевод» во ВСЕ assets/i18n/*.tsv (ключ — как в исходнике Java, \n буквально). Строку, у которой перевод
  совпадает с исходником, тоже писать: без неё Tr.t берёт английский (так чуть не потерялись украинские названия тем).
  Названия тем, вкладок и 'ПКМ', 'Пробел', 'Колесо' — внутренние ключи (prefs, значки): их переводить только при показе
  (Tr.t(label)), не в объявлении. Tr.t нельзя звать в static/полях класса — до Tr.init вернётся русский.
- 2026-11-19 экранный геймпад (v0.117, по коду SDL3 third_party/SDL3/src/joystick): Android-джойстик без устройства
  добавляется nativeAddJoystick (маска кнопок → GUID → SDL_CreateMappingForAndroidGamepad). SDL берёт ПЕРВОЕ значение
  каждой оси за её ноль (SDL_SendJoystickAxis, has_initial_value) — сначала слать покой (0 стику, -1 триггеру).
  nativeInGame = 0 приходит до инициализации джойстиков SDL; готовность — вызов Java pollInputDevices() из
  ANDROID_JoystickInit (флаг fnJoysticksReady).
