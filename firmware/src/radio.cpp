#include "radio.h"

#include "dlog.h"

#include <Arduino.h>
#include <NimBLEDevice.h>
#include <freertos/queue.h>

#include "config.h"

namespace radio {

namespace {

struct CmdItem {
  uint8_t len;
  uint8_t data[247];
};

bool g_init = false;
State g_state = State::OFF;
bool g_live = false;
bool g_secured = false;
bool g_pairing = false;
uint16_t g_conn = 0xFFFF;
int g_bondsAtConnect = 0;
uint32_t g_advUntilMs = 0;
uint32_t g_lastRxMs = 0;
uint32_t g_windowUntilMs = 0;
volatile bool g_disconnected = false;
volatile bool g_dropSecured = false;  // оборвалась связь с сопряжённым телефоном
bool g_selfDisconnect = false;        // рвём сами (BYE, тайм-аут) — окно потом не нужно

NimBLEServer* g_server = nullptr;
NimBLECharacteristic* g_status = nullptr;
NimBLECharacteristic* g_event = nullptr;
QueueHandle_t g_queue = nullptr;
proto::Beacon g_lastBeacon{};

// Интервалы в единицах BLE
uint16_t advUnits(uint16_t ms) { return ms * 1000 / 625; }

class ServerCB : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, ble_gap_conn_desc* desc) override {
    g_conn = desc->conn_handle;
    g_state = State::CONNECTED;
    g_live = false;
    g_secured = desc->sec_state.encrypted && desc->sec_state.bonded;
    g_bondsAtConnect = NimBLEDevice::getNumBonds();
    g_lastRxMs = millis();
    dlog::counters().bleConnects++;
    LOGI(M_BLE, "телефон подключился, шифрование %d, сопряжён %d", desc->sec_state.encrypted, desc->sec_state.bonded);
    // Сразу просим шифрование: сопряжённый телефон восстановит его без вопросов
    NimBLEDevice::startSecurity(desc->conn_handle);
  }

  void onDisconnect(NimBLEServer*, ble_gap_conn_desc*) override {
    LOGI(M_BLE, "телефон отключился");
    g_conn = 0xFFFF;
    g_live = false;
    g_dropSecured = g_secured;
    g_secured = false;
    g_disconnected = true;
  }

  void onAuthenticationComplete(ble_gap_conn_desc* desc) override {
    bool newBond = NimBLEDevice::getNumBonds() > g_bondsAtConnect;
    if (!desc->sec_state.encrypted || (newBond && !g_pairing)) {
      // Чужой телефон: сопряжение только в окне после долгого нажатия B (ТЗ, раздел 11)
      dlog::counters().bleRejects++;
      LOGW(M_BLE, "отказ: %s", !desc->sec_state.encrypted ? "шифрование не удалось" : "чужой телефон вне окна сопряжения");
      if (newBond) NimBLEDevice::deleteBond(NimBLEAddress(desc->peer_id_addr));
      g_server->disconnect(desc->conn_handle);
      return;
    }
    g_secured = desc->sec_state.bonded;
    if (newBond) LOGI(M_BLE, "новый телефон сопряжён");
  }
};

class CommandCB : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c) override {
    NimBLEAttValue v = c->getValue();
    CmdItem item;
    item.len = v.length() > sizeof(item.data) ? sizeof(item.data) : v.length();
    memcpy(item.data, v.data(), item.len);
    g_lastRxMs = millis();
    // Разбор и выполнение — в основном цикле, не в потоке BLE
    xQueueSend(g_queue, &item, 0);
  }
};

void buildAdv(const proto::Beacon& b) {
  uint8_t payload[2 + proto::BEACON_SIZE];
  payload[0] = proto::COMPANY_ID & 0xFF;
  payload[1] = proto::COMPANY_ID >> 8;
  proto::encodeBeacon(b, payload + 2, proto::BEACON_SIZE);

  NimBLEAdvertisementData ad;
  ad.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  ad.setManufacturerData(std::string(reinterpret_cast<char*>(payload), sizeof(payload)));
  NimBLEAdvertisementData sr;
  sr.setCompleteServices(NimBLEUUID(proto::SERVICE_UUID));
  sr.setName("vabuGEOde");
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->setAdvertisementData(ad);
  adv->setScanResponseData(sr);
}

void ensureInit() {
  if (g_init) return;
  if (!g_queue) g_queue = xQueueCreate(8, sizeof(CmdItem));
  NimBLEDevice::init("vabuGEOde");
  NimBLEDevice::setPower(ESP_PWR_LVL_P3);
  NimBLEDevice::setMTU(247);
  // Сопряжение «без ввода кода», с запоминанием (bonding) и Secure Connections
  NimBLEDevice::setSecurityAuth(true, false, true);
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);

  g_server = NimBLEDevice::createServer();
  g_server->setCallbacks(new ServerCB());
  g_server->advertiseOnDisconnect(false);
  NimBLEService* svc = g_server->createService(proto::SERVICE_UUID);
  g_status = svc->createCharacteristic(proto::CHAR_STATUS_UUID,
                                       NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::NOTIFY);
  // Запись только по зашифрованной связи: телефон без сопряжения командовать не сможет
  NimBLECharacteristic* cmd = svc->createCharacteristic(proto::CHAR_COMMAND_UUID,
                                                        NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC);
  cmd->setCallbacks(new CommandCB());
  g_event = svc->createCharacteristic(proto::CHAR_EVENT_UUID, NIMBLE_PROPERTY::NOTIFY);
  svc->start();
  g_init = true;
}

void startAdv(uint16_t intervalMs) {
  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->stop();
  adv->setMinInterval(advUnits(intervalMs));
  adv->setMaxInterval(advUnits(intervalMs + 10));
  adv->start(0);
}

void stopAdv() {
  if (g_init) NimBLEDevice::getAdvertising()->stop();
}

}  // namespace

State state() { return g_state; }
bool connected() { return g_state == State::CONNECTED; }
bool live() { return connected() && g_live; }
bool secured() { return connected() && g_secured; }
bool windowOpen() { return g_state == State::WINDOW; }

void beacon(const proto::Beacon& b) {
  if (g_state == State::CONNECTED || g_state == State::WINDOW) return;
  ensureInit();
  g_lastBeacon = b;
  buildAdv(b);
  startAdv(BEACON_ADV_INTERVAL_MS);
  g_state = State::BEACON;
  g_advUntilMs = millis() + BEACON_BURST_MS;
}

void openWindow(const proto::Beacon& b, bool pairing) {
  if (g_state == State::CONNECTED) return;
  ensureInit();
  g_lastBeacon = b;
  g_lastBeacon.flags |= proto::B_WINDOW;
  buildAdv(g_lastBeacon);
  startAdv(WINDOW_ADV_INTERVAL_MS);
  g_state = State::WINDOW;
  g_pairing = pairing;
  g_windowUntilMs = millis() + WINDOW_MS;
}

void updateBeacon(const proto::Beacon& b) {
  if (g_state != State::BEACON && g_state != State::WINDOW) return;
  g_lastBeacon = b;
  if (g_state == State::WINDOW) g_lastBeacon.flags |= proto::B_WINDOW;
  buildAdv(g_lastBeacon);
}

void poll() {
  uint32_t now = millis();
  if (g_disconnected) {
    g_disconnected = false;
    bool reopen = g_dropSecured && !g_selfDisconnect;
    g_dropSecured = false;
    g_selfDisconnect = false;
    g_state = State::OFF;
    g_pairing = false;
    if (reopen) {
      // Телефон ушёл из зоны: 2 мин окно, чтобы он переподключился сам, как только вернётся.
      // Короткого маячка недостаточно для быстрого восстановления уже оборванного сеанса
      LOGI(M_BLE, "связь оборвалась — окно переподключения");
      openWindow(g_lastBeacon, false);
    }
  }
  switch (g_state) {
    case State::BEACON:
      if ((int32_t)(now - g_advUntilMs) >= 0) {
        stopAdv();
        g_state = State::OFF;
      }
      break;
    case State::WINDOW:
      if ((int32_t)(now - g_windowUntilMs) >= 0) {
        stopAdv();
        g_state = State::OFF;
        g_pairing = false;
      }
      break;
    case State::CONNECTED:
      // Телефон молчит (приложение шлёт SESSION каждые 20 с) — рвём, чтобы не жечь батарею
      if (now - g_lastRxMs > SESSION_TIMEOUT_MS) {
        LOGW(M_BLE, "телефон молчит %lu с — рву связь", (unsigned long)((now - g_lastRxMs) / 1000));
        g_lastRxMs = now;  // не повторять строку, пока стек рвёт связь
        disconnect();
      }
      break;
    default:
      break;
  }
}

bool popCommand(uint8_t* buf, size_t& len) {
  if (!g_queue) return false;
  CmdItem item;
  if (xQueueReceive(g_queue, &item, 0) != pdTRUE) return false;
  memcpy(buf, item.data, item.len);
  len = item.len;
  return true;
}

void setLive(bool on) {
  g_live = on;
  if (!connected() || g_conn == 0xFFFF) return;
  // Живой: интервал 15–30 мс; тихий: ~0,5 с с пропуском событий — радио почти спит
  if (on) g_server->updateConnParams(g_conn, 12, 24, 0, 400);
  else g_server->updateConnParams(g_conn, 320, 400, 4, 600);
}

void touch() { g_lastRxMs = millis(); }

void disconnect() {
  if (!connected() || g_conn == 0xFFFF) return;
  g_selfDisconnect = true;
  g_server->disconnect(g_conn);
}

void shutdown() {
  if (!g_init) return;
  stopAdv();
  // Вызывается только перед deep sleep. Не удаляем стек и его объекты, пока
  // FreeRTOS может выполнять отложенные BLE-таймеры после отключения телефона.
  // Сам deep sleep выключает радио; после пробуждения setup запускается заново.
  g_state = State::OFF;
  g_live = g_secured = g_pairing = false;
}

bool sendStatus(const uint8_t* data, size_t len) {
  if (!secured() || !g_status) return false;
  g_status->setValue(data, len);
  g_status->notify();
  return true;
}

bool sendEvent(const uint8_t* data, size_t len) {
  if (!secured() || !g_event || g_conn == 0xFFFF) return false;
  // Уведомление напрямую через стек, чтобы знать, ушло ли оно: при выгрузке пачкой буферы NimBLE
  // кончаются, и notify() молча теряет пакеты. Несколько коротких попыток, потом — false
  for (int attempt = 0; attempt < 5; attempt++) {
    os_mbuf* om = ble_hs_mbuf_from_flat(data, len);
    if (om && ble_gatts_notify_custom(g_conn, g_event->getHandle(), om) == 0) return true;
    // om при ошибке стек освобождает сам
    delay(10);
  }
  return false;
}

}  // namespace radio
