# BLE OTA: свидетельства анализа сборки

12.11.2026. Только анализ, без изменений поведения/выбранной разметки и без записи
на устройство. Источник: основная ветка `claude/happy-wozniak-l7x710`, commit
`964584248dd237c10fcbf1d3f05f1d09166e0223`; firmware 0.1.25. Физического V2 нет.
Сводка/предложения: [ble-ota-v2.md](../../ble-ota-v2.md).

## Свежая сборка

```sh
pio run -d firmware -e s3 -t clean
pio run -d firmware -e s3
```

Успешно, 40,014 с. RAM 35 520 / 327 680 байт; отчёт flash 726 509 / 3 145 728.
Для OTA применяется полный размер `.bin`, не отчёт flash. Новый артефакт не выпускался.

| Файл | Размер, байт | SHA-256 |
|---|---:|---|
| firmware.bin | 728784 | fd86dd3fb57abc821a722c4381eccbc567c3f2ed3dce93e8e51eaa9421959525 |
| bootloader.bin | 15104 | e01a2300de23c8d601e9f2a37684b5dbc83ae37132d5a8833e23520afd895f92 |
| partitions.bin | 3072 | aaae2888c5a6a348004b5b436f47abb25ae32e72d9003902955a998eda723edd |

Версии: platform espressif32 6.9.0; framework-arduinoespressif32
3.20017.241212+sha.dcc1105b = Arduino 2.0.17; ESP-IDF 4.4.7; NimBLE 1.4.3;
esptool 4.5.1. Пути SDK ниже относительно установленного framework-пакета.

`esptool.py --chip esp32s3 image_info --version 2 firmware.bin`:
ESP32S3, chip ID 9, Flash size 4MB, DIO, 80m, checksum/hash valid, secure version 0.
В app descriptor: project `arduino-lib-builder`, app version `esp-idf: v4.4.7 38eeba213a`,
compile time Mar 5 2024. Это предсобранный descriptor SDK, не дата/версия нашего продукта.
По нему нельзя устанавливать факт версии 0.1.25. Отдельная продуктовая metadata — будущая задача.

Профиль `esp32-s3-devkitc-1` печатает 8MB, но фактический заголовок образа 4MB:
проект переопределяет upload.flash_size. Это доказательство конфигурации сборки,
не физического размера flash. Обычные аппаратные `flash_id`/eFuse не выполнялись.

`gen_esp32part.py partitions.bin` подтвердил:

```csv
nvs,data,nvs,0x9000,0x5000,
otadata,data,ota,0xe000,0x2000,
app0,app,ota_0,0x10000,0x300000,
spiffs,data,spiffs,0x310000,0xE0000,
coredump,data,coredump,0x3F0000,0x10000,
```

Временный расчётный CSV из анализа с app0/app1 по 0x180000 проверен
`gen_esp32part.py --flash-size 4MB`, затем декодирован обратно: пересечений нет,
конец 0x400000. Не подключался в PlatformIO и не прошивался. Запас слота относительно
текущего файла 844080 байт; до предложенного CI-предела 0x140000 — 581936 байт.

## Проверка фактического bootloader и Arduino

В `tools/platformio-build.py` get_bootloader_image выбирает
`tools/sdk/esp32s3/bin/bootloader_qio_80m.elf`; builder конвертирует QIO flash mode в DIO.
Повторная конверсия ниже дала файл **побайтно одинаковый** с bootloader.bin сборки:

```sh
esptool.py --chip esp32s3 elf2image --flash_mode dio --flash_freq 80m \
  --flash_size 4MB -o /tmp/bootloader-check.bin \
  tools/sdk/esp32s3/bin/bootloader_qio_80m.elf
cmp /tmp/bootloader-check.bin firmware/.pio/build/s3/bootloader.bin
```

В sdkconfig.h выбранного `qio_qspi`: CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=1,
CONFIG_APP_ROLLBACK_ENABLE alias; в sdkconfig подписанные приложения без Secure Boot,
Secure Boot и flash encryption выключены. Поддерживаются ECDSA и secp256r1.
Из одного sdkconfig работоспособность bootloader не выводилась:

- nm фактического bootloader ELF: `bootloader_utility_get_selected_boot_partition`
  0x403cd9c8, `set_actual_ota_seq` 0x403cd8b8, `bootloader_common_ota_select_invalid`
  0x403cd2f8, `bootloader_common_ota_select_valid` 0x403cd1e0.
- objdump выбранного bootloader: если `ota_state == 1`, записывает 4 (ABORTED) и
  вызывает `write_otadata` — адреса 0x403cda1b–0x403cda29; аналогично для второй копии.
  Для выбранной NEW (0) записывает 1 (PENDING_VERIFY) — 0x403cda6e–0x403cda82.
  Значения enum сверены с `include/bootloader_support/include/esp_flash_partitions.h`.
- В свежем firmware.elf weak `verifyOta` 0x42073414 действительно возвращает 1;
  `verifyRollbackLater` 0x4207341c возвращает 0 (проверено objdump).
  `cores/esp32/esp32-hal-misc.c:223` initArduino вызывает mark-valid при pending.
  `cores/esp32/main.cpp` вызывает initArduino до setup/loopTask.
- Поэтому default Arduino подтверждает приложение до наших тестов. Наличие
  `esp_ota_mark_app_invalid_rollback_and_reboot` в ELF не исправляет это само по себе.

Это статическая проверка выбранных двоичных файлов и исходников integration.
Реальные отключения питания, первый запуск, неудачный boot и возврат не испытывались.

## Прочие подтверждённые места интеграции

- firmware/src/power.cpp begin: холодный/software-reset путь выставляет HOLD LOW,
  далее ожидает A+B; retainForSleep — отдельный путь. setup вызывает begin первым.
- firmware/src/radio.cpp: NimBLE secured/bonded, Command WRITE_ENC, MTU247,
  очередь команд 8, xQueueSend без проверки; старый Event ACK не подтверждает flash.
- firmware/src/main.cpp sendInfo: версия/тип чипа, без аппаратного ID/layout/hash OTA.
- Android LinkManager.setForeground: тихий режим через 30 с, BYE через 2 минуты.
  BleTransport requestMtu без сохранения результата; send использует WRITE_TYPE_DEFAULT.
  AppViewModel владеет репозиторием через viewModelScope; WatchService сканирует маячки.
- firmware/src/sensors.cpp: при неизвестной INA219 pct=100 с ok=false;
  percentage только по напряжению. Для OTA нужен отдельный строгий допуск питания.

Сборка не проверяет электрическое удержание GPIO7/EN на reset, исправность V2,
загрузку с обоих OTA-слотов, подлинность будущего образа или сохранность реальной NVS/SD.
