# vabuGEOde

Походный трекер: ESP32-S3 + GPS + датчики → SD-карта и приложение на телефоне по Bluetooth.
Полное ТЗ — в [CLAUDE.md](CLAUDE.md).

## Структура

- `docs/protocol.md` — протокол BLE между устройством и телефоном; `docs/protocol_vectors.json` — эталонные пакеты.
- `android/` — приложение (Kotlin, Jetpack Compose). Пока платы нет, данные шлёт **виртуальное устройство**
  байтами по тому же протоколу, что и прошивка: устройство → байты → транспорт → разбор → экраны.
  Журнал обмена — на вкладке «Трекер».
- `firmware/` — прошивка ESP32-S3 (PlatformIO), см. [firmware/README.md](firmware/README.md).
- `docs/gadget-logic.md` — как устройство живёт: питание, GPS, радио, тревоги, связь с телефоном.

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
