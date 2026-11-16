# Factorio native (bionic) — состояние на 2026-11-16

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
  в процессе :game. Логи, config и write-data: /sdcard/Download/FactorioNative/ (Android/data из Termux не читается).
- Сборка: tools/build-apk.sh.

## Тест в proot
tools/build-loader.sh; tools/build-fnsdl.sh; cd /tmp/fn-test;
FN_SHOT=/tmp/fn-test/shots/x FN_SHOT_EVERY=15 tools/run-offscreen.sh [--load-game map.zip]
Переменные: FNGL_LOG, FNGL_DEBUG (KHR_debug), FNGL_TRACE, FNGL_DISABLE_EXTENSIONS, FNGL_DUMP_SHADERS.

## Дальше
1. Результат пользователя с телефона: запуск, ввод касанием, звук, сворачивание.
2. Mali: проверить без BC (нет s3tc/bptc/rgtc) — путь несжатых/ETC2.
3. Скорость: аллокатор (гипотеза), профиль кадра на мегабазе.
