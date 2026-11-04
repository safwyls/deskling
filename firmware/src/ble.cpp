#include "ble.h"
#include "api.h"
#include "crashlog.h"
#include "care.h"
#include "settings.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>

#define SVC_UUID "6d7a0001-5f2b-4b6c-9e2d-3a1f0c0d5e11"
#define RX_UUID  "6d7a0002-5f2b-4b6c-9e2d-3a1f0c0d5e11"
#define TX_UUID  "6d7a0003-5f2b-4b6c-9e2d-3a1f0c0d5e11"

static BLEServer *server = nullptr;
static BLECharacteristic *tx = nullptr;
static volatile bool started = false, connected = false, fresh = false;
static volatile uint16_t peerMtu = 23;
static QueueHandle_t lines;               // char* messages from the phone, one per request
static String inbuf;                      // only touched on the Bluetooth host task
static char why[48] = "off";

class ServerCB : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { connected = true; fresh = true; peerMtu = 23; }
  void onDisconnect(BLEServer *) override {
    connected = false; fresh = true;
    BLEDevice::startAdvertising();        // let the phone (or another) find us again
  }
  void onMtuChanged(BLEServer *, ble_gap_conn_desc *, uint16_t mtu) override { peerMtu = mtu; }
};

class RxCB : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    for (size_t i = 0; i < v.length(); i++) {
      char ch = v[i];
      if (ch == '\n') {
        if (inbuf.length()) {
          char *m = strdup(inbuf.c_str());
          if (m && xQueueSend(lines, &m, 0) != pdTRUE) free(m);   // full: drop it; the app retries on a timeout
        }
        inbuf = "";
      } else if (inbuf.length() < 4096) inbuf += ch;
    }
  }
};

static void send_line(String out) {
  out += '\n';
  size_t chunk = peerMtu > 23 ? min<size_t>(peerMtu - 3, 240) : 20;
  for (size_t p = 0; p < out.length() && connected && started && tx; p += chunk) {
    size_t n = min(chunk, out.length() - p);
    tx->setValue((uint8_t *)out.c_str() + p, n);
    tx->notify();
    vTaskDelay(pdMS_TO_TICKS(6));      // keep the stack's buffers from running dry on long replies
  }
}

// Answers requests one at a time: waits for the main loop, then sends the reply in MTU-sized notifications.
// Between requests it watches for changes made on the dial (a break, a pat, a setting) and tells a paired,
// connected phone with {"event":"changed"}, so the phone fetches the new state straight away instead of on
// its next scheduled sync. Replies always carry "ok"; events never do, which is how the phone tells them apart.
static void worker(void *) {
  bool authed = false;
  uint32_t seenChange = 0, seenSettings = 0;
  for (;;) {
    char *m;
    bool got = xQueueReceive(lines, &m, pdMS_TO_TICKS(250)) == pdTRUE;
    if (fresh) { authed = false; fresh = false; }      // a new connection proves its key again
    if (!got) {
      if (authed && connected && started && tx && (care_change() != seenChange || settings_version() != seenSettings)) {
        seenChange = care_change(); seenSettings = settings_version();
        send_line("{\"event\":\"changed\"}");
      }
      continue;
    }
    // a state request brings the phone up to date: nothing before it needs announcing
    bool state = strstr(m, "\"op\":\"state\"") != nullptr;
    uint32_t c = care_change(), sv = settings_version();
    String out;
    api_call(m, authed, out);
    free(m);
    if (state) { seenChange = c; seenSettings = sv; }
    if (!connected || !started || !tx) continue;
    send_line(out);
  }
}

// Bring the Bluetooth stack up (again). The request queue and worker task are made once and outlive pauses.
static bool stack_up() {
  uint32_t fr, mn, lg;
  crashlog_heap(fr, mn, lg);
  uint32_t before = fr;
  if (fr < 90 * 1024) {                  // Bluetooth takes ~75 KB of internal RAM; leave room for Wi-Fi to work
    Serial.printf("[ble] skipped: only %lu bytes of internal RAM free\n", (unsigned long)fr);
    snprintf(why, sizeof(why), "off (%lu KB RAM free, needs 90)", (unsigned long)(fr / 1024));
    return false;
  }
  BLEDevice::init(api_dial_name());
  BLEDevice::setMTU(247);
  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCB());
  BLEService *svc = server->createService(SVC_UUID);
  BLECharacteristic *rx = svc->createCharacteristic(RX_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new RxCB());
  tx = svc->createCharacteristic(TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  tx->addDescriptor(new BLE2902());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SVC_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  started = true;
  crashlog_heap(fr, mn, lg);
  snprintf(why, sizeof(why), "on (used %lu KB RAM)", (unsigned long)((before - fr) / 1024));
  Serial.printf("[ble] advertising as %s, internal RAM now %lu free\n", api_dial_name(), (unsigned long)fr);
  return true;
}

void ble_begin() {
  lines = xQueueCreate(4, sizeof(char *));
  xTaskCreatePinnedToCore(worker, "ble_api", 4096, nullptr, 1, nullptr, 0);
  stack_up();
}

static bool paused = false;

void ble_pause() {
  if (!started) return;
  started = false;                       // the worker stops sending before the stack goes away
  connected = false;
  vTaskDelay(pdMS_TO_TICKS(60));
  tx = nullptr;
  server = nullptr;                      // deleted by deinit
  BLEDevice::deinit(false);              // stops the stack and frees its working memory; it can start again
  paused = true;
  snprintf(why, sizeof(why), "paused for the setup network");
  uint32_t fr, mn, lg;
  crashlog_heap(fr, mn, lg);
  Serial.printf("[ble] paused, internal RAM now %lu free\n", (unsigned long)fr);
}

void ble_resume() {
  if (!paused) return;
  if (stack_up()) paused = false;        // short of RAM: stays paused, and the next call tries again
}

bool ble_paused() { return paused; }

const char *ble_status() { return !started ? "off" : connected ? "connected" : "on"; }
const char *ble_detail() { return connected ? "connected" : why; }
