# Factorio native (bionic) — состояние на 2026-11-17

Цель: официальный Linux ARM64 Factorio в процессе bionic, рендер напрямую на GLES, без мостов.
Уроки — LEARNING.md.

## Проверено в proot на телефоне (Adreno 830, offscreen)
- loader/: загрузчик (pread в анонимную память, релокации, 294 TLS-трамплина, shim glibc->bionic).
  --version, --create, --benchmark; сумма симуляции 2m_final.zip = glibc (716673159).
- fnsdl/: SDL_DYNAPI_entry (GL-атрибуты -> ES 3.2, GetProcAddress, SwapWindow: FPS+снимки),
  fngl.c: функции драйвера GLES напрямую + адаптеры (GetString/GetIntegerv/расширения,
  ShaderSource 330->320 es, ClearDepth/DepthRange/DrawBuffer, LOD_BIAS, GetTexImage,
  compressed size/readback), заглушки с логом для отсутствующих функций.
- Главное меню и загруженная карта рисуются правильно, 60 FPS; GPU-сжатие проходит самопроверку.

## APK (собран, на телефоне НЕ проверен)
- out/FactorioNative-0.4-native-test.apk (com.factorynative, versionCode 4, arm64-v8a,
  debug-подпись, sha256 see out/). Копия: /sdcard/Download/.
- android/: LauncherActivity (путь к игре, доступ к файлам), GameActivity = SDLActivity
  в процессе :game. Логи и config: /sdcard/Download/FactorioNative/; write-data = папка игры (saves, mods), с v0.9.
- Сборка: tools/build-apk.sh.

## Тест в proot
tools/build-loader.sh; tools/build-fnsdl.sh; cd /tmp/fn-test;
FN_SHOT=/tmp/fn-test/shots/x FN_SHOT_EVERY=15 tools/run-offscreen.sh [--load-game map.zip]
Переменные: FNGL_LOG, FNGL_DEBUG (KHR_debug), FNGL_TRACE, FNGL_DISABLE_EXTENSIONS, FNGL_DUMP_SHADERS.

- v0.11: авто-форматы текстур (BC → ETC2 → RGBA8, fngl_tex.c), редактор env.txt в лаунчере. На Mali не проверено.

- v0.29: замер производительности из лаунчера (bench-*.txt). Mali 9300+ работает (со слов пользователя).

- v0.36: кэши (атласы игры для BC / ETC2 для Mali), новая подпись, имя FNative, иконка.

- v0.37: выбор ядер; пользователь: «лучше когда все» → убран в v0.38 (git revert). Последний APK: out/FNative-0.38-native-test.apk (копия в Download).

- v0.42: 44 стиля кнопок. С 25-го — 4 группы по 5 (материалы, наука, здания, настроение), у каждой свой набор значков KeyIcon (iconSet 1–4: толстые с тенью, в круге, сигнал, тонкие со свечением; 2 и 4 — другие глифы). Выбор — прокручиваемый список справа с образцом. Ждём выбор пользователя.

## Дальше
1. Результат пользователя с телефона: запуск, ввод касанием, звук, сворачивание.
2. Mali: проверить без BC (нет s3tc/bptc/rgtc) — путь несжатых/ETC2.
3. Скорость: аллокатор (гипотеза), профиль кадра на мегабазе.
