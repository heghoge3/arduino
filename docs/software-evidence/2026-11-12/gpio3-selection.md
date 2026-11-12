# GPIO3: проверка назначения, 12.11.2026

Исходники 0.1.28, база 8779000. Фактического экземпляра в среде нет.
[Решение и электрический интерфейс](../../slider-power-v2.md#gpio-и-пробуждение).

- GPIO3 не использовался в pins.h и не входит в запрет владельца на нижние площадки.
- В ESP32-S3 это RTC_GPIO3. По первичным источникам его strap выбирает JTAG,
  а не boot mode; внешняя 10 кОм подтяжка задаёт ON/HIGH ещё до setup.
- eFuse не читались и не прожигались; реальная плата/контакты/EN не измерялись.
- GPIO0/19/20/45 и занятые линии не стали допустимыми вслед за исключением GPIO3.

Выполнены `pio test -d firmware -e native` (89/89),
`python3 firmware/tools/check_power_profiles.py` (11/11),
`pio run -d firmware -e s3 -e s3_fake -e s3_slider_bench` (все успешны).
Compile-probes сверяют точное значение default GPIO3 и -1 для явного bench/legacy,
разрешают явный GPIO3 и запрещают неназначенный override/занятые/запрещённые входы.

| Сборка | Размер bin, байт | SHA-256 |
|---|---:|---|
| s3 | 730928 | 45b929b2204efaeccaaf4d0bfc8dcee94505dd7484632098770f64a72aab6652 |
| s3_fake | 729984 | 3672b193674fe599329e09db316784446e8682e9677f0c9ede9275d21c29e0c5 |
| s3_slider_bench | 731136 | 3517af0eaa05ab636037f23d931203443ecdd4a811a6db415dbe589795789610 |

Эти размеры не содержат OTA-приёмник/новую разметку и не доказывают его готовность.
Это build-проверки, не прошивочный выпуск/установка. Не выполнены: GPIO3 уровни,
OFF из глубокого сна, reset/USB в обоих положениях, независимость контактов,
EN/HOLD при reset, сохранность SD после реального снятия питания.

Источники: [RTC GPIO, IDF 4.4.7](https://docs.espressif.com/projects/esp-idf/en/v4.4.7/esp32s3/api-reference/peripherals/gpio.html),
[JTAG/USB CDC, IDF 4.4.7](https://docs.espressif.com/projects/esp-idf/en/v4.4.7/esp32s3/api-guides/jtag-debugging/configure-other-jtag.html),
[даташит ESP32-S3, глава 3](https://documentation.espressif.com/esp32-s3_datasheet_en.pdf).
