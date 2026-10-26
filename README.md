# vabuGEOde

Походный трекер: ESP32-S3 + GPS + датчики → SD-карта и приложение на телефоне по Bluetooth.
Полное ТЗ — в [CLAUDE.md](CLAUDE.md).

## Структура

- `android/` — приложение (Kotlin, Jetpack Compose). Сейчас работает в **демо-режиме**: данные даёт симулятор похода.
- `firmware/` — прошивка (появится следующим этапом).

## Как поставить приложение на телефон

1. Открыть вкладку **Actions** в репозитории → последний успешный запуск «Android APK».
2. Внизу в **Artifacts** скачать `vabugeode-apk` (zip), распаковать, открыть `app-debug.apk`.
3. Разрешить установку из этого источника, если телефон спросит.

## Сборка локально

```
cd android
./gradlew assembleDebug testDebugUnitTest
```

Тест `ScreenshotTest` рисует экраны с демо-данными в `android/app/build/screenshots/`.
