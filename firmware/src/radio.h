// Радио (docs/gadget-logic.md, раздел 1.3): маячок, окно подключения, сеанс живой/тихий.
// NimBLE запускается только когда нужен и выключается перед сном.
#pragma once
#include <stddef.h>
#include <stdint.h>

#include "proto.h"

namespace radio {

enum class State : uint8_t { OFF, BEACON, WINDOW, CONNECTED };

State state();
bool connected();
bool live();            // живой сеанс: STATUS раз в секунду
bool secured();         // связь зашифрована и телефон сопряжён — можно слать данные
bool windowOpen();

// Короткий выход в эфир со сводкой (~300 мс)
void beacon(const proto::Beacon& b);
// Окно на 2 мин. pairing — разрешить сопряжение нового телефона (только по кнопке B)
void openWindow(const proto::Beacon& b, bool pairing);
// Обновить данные маячка, если сейчас в эфире
void updateBeacon(const proto::Beacon& b);

// Вызывать в каждой итерации цикла: тайм-ауты маячка, окна и сеанса
void poll();

// Команда от телефона (из очереди). false — команд нет
bool popCommand(uint8_t* buf, size_t& len);

void setLive(bool on);
void touch();           // телефон проявился — сбросить тайм-аут сеанса
void disconnect();      // BYE
void shutdown();        // перед глубоким сном

// Отправить пакет на характеристику Status / Event. false — нет связи
bool sendStatus(const uint8_t* data, size_t len);
bool sendEvent(const uint8_t* data, size_t len);

}  // namespace radio
