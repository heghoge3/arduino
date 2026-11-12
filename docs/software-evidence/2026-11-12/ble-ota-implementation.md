# BLE OTA 0.1.30 / Android 0.10.7: программные проверки

12.11.2026. Задача владельца: реализовать первый BLE OTA, ползунок — основной профиль.
Основа `944ad84`, ветка `feat/ble-ota-v2`; точный исходный commit выпуска — в release.json
веток artifacts. [Действующий контракт](../../ble-ota-v2.md). Ни один прибор здесь
не подключался: USB-записи, eFuse, реального отключения и отката **не было**.

## Сборка и бюджет

PlatformIO espressif32 6.9.0; Arduino 2.0.17 (3.20017.241212+sha.dcc1105b),
ESP-IDF 4.4.7, NimBLE 1.4.3, esptool 4.5.1. Успешно:

```sh
pio run -d firmware -e s3 -e s3_ota_bootstrap -e s3_fake -e s3_slider_bench
```

| Файл | Размер | SHA-256 |
|---|---:|---|
| s3 firmware.bin 0.1.30 | 764000 | 952306b003ed4f84eaaec34005e7adc0b9cb73ae31ec20f35ec6bd5eb202c795 |
| s3_ota_bootstrap firmware.bin 0.1.29 | 764176 | fc98692ecda641842fef36246bed1268a16251ea063c34846dafd272323a0fb0 |
| bootloader.bin, оба | 15104 | e01a2300de23c8d601e9f2a37684b5dbc83ae37132d5a8833e23520afd895f92 |
| partitions.bin, оба | 3072 | fc22d7750c62b98ebc4a74afc5ae33c47829e8691ad47b67a3b8705b29f9b941 |

Полный файл 0.1.30 оставляет 808864 байт до конца слота 0x180000, 546720 байта
до лимита 0x140000 (внутри слота резерв 256 КиБ). Подписанный manifest — отдельно,
не добавляется к app.bin в flash. Подпись проверена публичным инструментом без секрета.

esptool image_info v2: ESP32S3/chip9, DIO80MHz, заголовок4MB, checksum/hash valid.
Фактический физический flash_id/PSRAM не измерены. Таблица декодирована
`gen_esp32part.py`: app0 0x10000/0x180000, app1 0x190000/0x180000;
NVS0x9000/0x5000, otadata0xE000/0x2000, SPIFFS0x310000/0xE0000,
coredump0x3F0000/0x10000. Конец0x400000, пересечений нет.

## Реальный загрузчик и hook

Повторный elf2image с dio/80m/4MB из установленного
`tools/sdk/esp32s3/bin/bootloader_qio_80m.elf` дал **побайтно тот же** bootloader.bin.
Конфигурация rollback=1; статическое дизассемблирование NEW→PENDING→ABORTED
сохранено в [первичном аудите](ble-ota-build-audit.md), бинарник загрузчика тот же.
Secure Boot/flash encryption/eFuse anti-rollback не включались.

Итоговый `s3/firmware.elf`: `verifyRollbackLater` — **T (strong)**, 0x42078DC0,
код `entry a1,32; movi.n a2,1; retw.n`. C-hook заменил weak defaultfalse.
`esp_ota_mark_app_valid_cancel_rollback` 0x42031DCC,
`esp_ota_mark_app_invalid_rollback_and_reboot` 0x42031DD8 присутствуют.
Это подтверждает линковку, **не работу отката на ESP32**.
Точная длина ESP image с сегментами/checksum/footer отдельно сверена с обоими .bin;
она не берётся из SDK image_len. Boot_app0 — штатные 8192 байта, старт app0.

## Проверки без устройства

| Набор | Результат / значимое покрытие |
|---|---|
| `pio test -d firmware -e native` | **97/97**; 8 OTA + прежние 89. Отказы до erase, manifest/подпись/модель/профиль/replay, offset/CRC/дубли, разрыв/тайм-аут/OFF, ошибки write/hash/activate, повтор FINISH, размер ESP image. Подставная flash сохраняет активную базу |
| `python3 firmware/tools/check_power_profiles.py` | **11/11** compile-probes: GPIO3, legacy/bench и отказы запрещённых конфигураций |
| host `power_test.cpp` | **4/4**: ручной GPS/привал/wrap и неизвестное/критическое питание |
| Android `testDebugUnitTest` | **163/163**, 0 failures/errors/skipped. Прежние151 + OTA12 |
| Android OTA | Настоящая Java ECDSA на публичном тестовом векторе, повреждение образа/подписи/ключа; CRC/границы; fake-peer полный обмен; потеря DATA ACK/повтор; background/abort; pending не успех; восстановление журнала после нового клиента; ineligible; свежие OTA ACK при устаревшем обычном STATUS; ошибка сохранения журнала до BEGIN; известный откат допускает явную попытку; правильная версия с чужим hash не успех |
| `python3 -m unittest discover -s firmware/tools -p test_ota_tools.py -v` | **4/4**, подпроверки huge_app/layout1 и сохранения адресов; отказ missing/moved/extra/duplicate/неполного второго слота; public bundle и tamper app/signature/model/key |
| `verify_ota_bundle.py` | Реальная публичная подпись 0.1.30/hash/metadata/pinnedkey совпали. Приватный ключ не нужен |
| `package_ota.py` negative | Реальные s3_fake и s3_slider_bench отказались упаковываться; не выданы как OTA firmware |
| Python syntax | py_compile упаковки, публичного verifier и USB-подготовки |

Во время интеграционной проверки исправлены: release minimum 0x11D, точная длина
ESP image (включая footer), сканирование полной metadata вместо строкового префикса,
свежие STATUS/питание при приёме, защита от SESSION quiet, подтверждение не только
по номеру версии/SDK VALID, повтор после известного отката, private journal failure
до BEGIN, отказ подтверждения при невозможности включить watchdog, запрет записи поверх неизвестной дополнительной data partition при USB.

Короткая самопроверка не проверяет все датчики/экран/канал BLE; это отдельная физическая
приёмка. Нативные тесты не эмулируют деградацию SD/flash или электрическую просадку.
Фактические Android BLE/XOS/MTU/USB-переход не проверены.

## Ключи, артефакты и следующие испытания

APK-ключ прежний, сертификат проверяется сборочным инструментом.
Отдельный OTA-private key/ZIP лежат вне git. ZIP CRC, совпадение сохранённого ключа
с рабочим и права 0600 проверены **локально**, не в Library/внешнем хранилище.
Публичные app.bin/manifest/public.der сохранены в исходниках, CI проверяет их без ключа.
[Порядок резервирования](../../ota-signing.md); external backup ещё не подтверждён.

Подписанный APK и ZIP USB-base/update публикуются как комплект проверки, ссылки и
контрольные суммы — в [STATUS](../../STATUS.md) и release.json. Отказных/зависших
образов и приватных flash-backup в комплекте нет.

До признания OTA готовым: измерить EN/GPIO7/3,3 В на ON/reset/bootloader, выполнить
первый USB с копиями, затем нормальное OTA в оба слота и всю
[матрицу отказов](../../ble-ota-v2.md#приёмка-программная-и-на-v2): BLE/питание во всех
окнах, ошибочный boot/watchdog/rollback, OFF при записи на тестовую SD и NVS/bonds/SD
до/после. Автоперезапуск и надёжность носителя не подтверждаются этой сборкой.
