#include "net.h"
#include "crashlog.h"
#include "config.h"
#include "settings.h"
#include <WiFi.h>

static char activeSsid[33], activePass[65];
static uint32_t cfgVersion = 0;

// requests from other tasks
static SemaphoreHandle_t reqMtx;
static char trySsid[33], tryPass[65];
static volatile bool tryReq = false, savedReq = false, scanReq = false;

static const int MAX_NETS = 24;
static WifiNet nets[MAX_NETS];
static volatile int netCount = 0;
static volatile ScanState scanState = SCAN_IDLE;

// ---------- Wi-Fi diagnostics ----------
static volatile uint8_t dReason = 0, dAuthFails = 0, dExpired = 0;
static volatile bool dAssoc = false, dIp = false;

static bool is_auth_reason(uint8_t r) {
  // what a refused passphrase looks like: depending on the router it ends the handshake, times it out or rejects
  // (AUTH_EXPIRE is different: the router didn't answer the join request at all, before any password)
  return r == WIFI_REASON_4WAY_HANDSHAKE_TIMEOUT || r == WIFI_REASON_HANDSHAKE_TIMEOUT || r == WIFI_REASON_AUTH_FAIL ||
         r == WIFI_REASON_MIC_FAILURE || r == 210 /* no AP with compatible security */;
}

static void on_wifi_event(arduino_event_id_t ev, arduino_event_info_t info) {
  if (ev == ARDUINO_EVENT_WIFI_STA_CONNECTED) dAssoc = true;
  else if (ev == ARDUINO_EVENT_WIFI_STA_GOT_IP) dIp = true;
  else if (ev == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
    uint8_t r = info.wifi_sta_disconnected.reason;
    if (r == WIFI_REASON_ASSOC_LEAVE) return;          // ours (switching networks), not a failure
    dReason = r;
    if (is_auth_reason(r) && dAuthFails < 255) dAuthFails = dAuthFails + 1;
    if (r == WIFI_REASON_AUTH_EXPIRE && dExpired < 255) dExpired = dExpired + 1;
    Serial.printf("[wifi] disconnected: %u %s\n", r, net_reason_name(r));
  }
}

const char *net_reason_name(uint8_t r) { return WiFi.STA.disconnectReasonName((wifi_err_reason_t)r); }

WifiDiag net_diag() { return WifiDiag{ dReason, dAuthFails, dExpired, dAssoc, dIp }; }

// Reconnecting is done here rather than by the driver, which retries a refused login every few seconds
// for ever. Some routers lock a device out after that (which then fails with AUTH_EXPIRE).
static bool tryMode = false;               // testing new credentials from Settings: one attempt, no retries
static volatile bool hold = false;         // the setup network is up: no background reconnects
static volatile bool apReq = false, apWant = false;
static volatile ApState apState = AP_OFF;
static char apSsid[33], apPass[65];
static char apErr[48] = "";
static const uint32_t AP_MIN_FREE = 40 * 1024, AP_MIN_BLOCK = 16 * 1024;   // margins over what the AP takes
static uint8_t apCh = 1;

static void ap_apply() {
  apReq = false;
  if (apWant) {
    if (apState == AP_UP) return;
    // Starting an access point allocates the driver's AP buffers from internal RAM, and the driver
    // crashes rather than failing when that runs out. Check first, and record the figures in case.
    uint32_t fr, mn, lg;
    crashlog_heap(fr, mn, lg);
    Serial.printf("[wifi] setup network %s on channel %u, internal RAM %lu free, largest %lu\n", apSsid, apCh,
                  (unsigned long)fr, (unsigned long)lg);
    if (fr < AP_MIN_FREE || lg < AP_MIN_BLOCK) {
      snprintf(apErr, sizeof(apErr), "Not enough memory (%lu KB free)", (unsigned long)(fr / 1024));
      apState = AP_FAILED;
      return;
    }
    crashlog_note("softAP");
    bool ok = WiFi.softAP(apSsid, apPass, apCh, 0, 2);
    crashlog_note_done();
    Serial.printf("[wifi] setup network %s\n", ok ? "up" : "failed");
    if (!ok) strlcpy(apErr, "The Wi-Fi driver refused", sizeof(apErr));
    apState = ok ? AP_UP : AP_FAILED;
  } else if (apState != AP_OFF) {
    WiFi.softAPdisconnect(true);
    apState = AP_OFF;
    Serial.println("[wifi] setup network off");
  }
}
static uint32_t nextAttempt = 0, backoff = 5000;

static void wifi_connect(const char *ssid, const char *pass, bool isTry = false) {
  strlcpy(activeSsid, ssid, sizeof(activeSsid));
  strlcpy(activePass, pass, sizeof(activePass));
  tryMode = isTry;
  backoff = 5000;
  nextAttempt = millis() + 20000;          // let this attempt play out before any retry
  WiFi.disconnect();
  dReason = 0; dAuthFails = 0; dExpired = 0; dAssoc = false; dIp = false;     // a fresh attempt
  vTaskDelay(pdMS_TO_TICKS(100));
  if (activeSsid[0]) WiFi.begin(activeSsid, activePass[0] ? activePass : nullptr);
}

static void apply_settings() {
  static Settings s;            // big: keep it off the task stack
  settings_get(s);
  cfgVersion = settings_version();
  // (not during a test of other credentials, or while the setup network is up: those end with an explicit use_saved)
  if (!tryMode && !hold && (strcmp(activeSsid, s.ssid) || strcmp(activePass, s.pass))) wifi_connect(s.ssid, s.pass);
}

// ---------- scan ----------
static void do_scan() {
  bool connected = WiFi.status() == WL_CONNECTED;
  if (!connected) WiFi.disconnect();          // a scan can't run while the driver keeps retrying a connection
  int n = WiFi.scanNetworks(false, false);
  if (n < 0) { vTaskDelay(pdMS_TO_TICKS(500)); n = WiFi.scanNetworks(false, false); }
  int k = 0;
  for (int i = 0; i < n; i++) {
    String s = WiFi.SSID(i);
    if (!s.length()) continue;                // hidden networks
    int8_t rssi = WiFi.RSSI(i);
    bool open = WiFi.encryptionType(i) == WIFI_AUTH_OPEN;
    int j = 0;
    for (; j < k; j++) if (s == nets[j].ssid) break;
    if (j < k) { if (rssi > nets[j].rssi) nets[j].rssi = rssi; continue; }
    if (k >= MAX_NETS) continue;
    strlcpy(nets[k].ssid, s.c_str(), sizeof(nets[k].ssid));
    nets[k].rssi = rssi; nets[k].open = open; nets[k].ch = (uint8_t)WiFi.channel(i);
    k++;
  }
  WiFi.scanDelete();
  for (int a = 1; a < k; a++)                 // strongest first
    for (int b = a; b > 0 && nets[b].rssi > nets[b - 1].rssi; b--) { WifiNet t = nets[b]; nets[b] = nets[b - 1]; nets[b - 1] = t; }
  netCount = k;
  scanState = n < 0 ? SCAN_FAILED : SCAN_DONE;
  if (!connected && activeSsid[0] && !tryMode && !hold) { WiFi.begin(activeSsid, activePass[0] ? activePass : nullptr); nextAttempt = millis() + 20000; }
}

// ---------- task ----------
static void net_task(void *) {
  WiFi.onEvent(on_wifi_event);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(false);            // see wifi_connect: retries are ours, with a backoff

  for (;;) {
    if (settings_version() != cfgVersion) apply_settings();
    if (savedReq) {
      savedReq = false;
      static Settings s; settings_get(s);
      wifi_connect(s.ssid, s.pass);
    }
    if (tryReq) {
      char ss[33], pp[65];
      xSemaphoreTake(reqMtx, portMAX_DELAY);
      strlcpy(ss, trySsid, sizeof(ss)); strlcpy(pp, tryPass, sizeof(pp));
      xSemaphoreGive(reqMtx);
      wifi_connect(ss, pp, true);
      tryReq = false;          // only now: until the old connection is dropped, WiFi.status() still describes it
    }
    if (scanReq) { scanReq = false; do_scan(); }
    if (apReq) ap_apply();

    if (activeSsid[0] && WiFi.status() != WL_CONNECTED) {
      if (!tryMode && !hold && (int32_t)(millis() - nextAttempt) >= 0) {        // the saved network: retry, backing off
        Serial.printf("[wifi] retrying %s (next in %lus)\n", activeSsid, (unsigned long)(backoff / 1000));
        WiFi.disconnect();
        vTaskDelay(pdMS_TO_TICKS(100));
        WiFi.begin(activeSsid, activePass[0] ? activePass : nullptr);
        nextAttempt = millis() + backoff;
        backoff = min<uint32_t>(backoff * 2, 60000);
      }
    } else if (WiFi.status() == WL_CONNECTED) {
      backoff = 5000;
      tryMode = false;                     // a tried network that joined is now the saved one; retry it if it drops
    }
    vTaskDelay(pdMS_TO_TICKS(200));
  }
}

void net_begin() {
  reqMtx = xSemaphoreCreateMutex();
  xTaskCreatePinnedToCore(net_task, "net", 6144, nullptr, 1, nullptr, 0);   // no HTTP or TLS here any more: a small stack
}

void net_scan() { scanState = SCAN_BUSY; scanReq = true; }
ScanState net_scan_state() { return scanState; }
int net_scan_count() { return netCount; }
const WifiNet &net_scan_net(int i) { return nets[constrain(i, 0, MAX_NETS - 1)]; }

void net_try(const char *ssid, const char *pass) {
  xSemaphoreTake(reqMtx, portMAX_DELAY);
  strlcpy(trySsid, ssid, sizeof(trySsid));
  strlcpy(tryPass, pass, sizeof(tryPass));
  tryReq = true;
  xSemaphoreGive(reqMtx);
}
bool net_try_pending() { return tryReq; }
void net_use_saved() { savedReq = true; }
void net_hold(bool on) { hold = on; }
void net_ap_start(const char *ssid, const char *pass, uint8_t channel) {
  strlcpy(apSsid, ssid, sizeof(apSsid)); strlcpy(apPass, pass, sizeof(apPass));
  apCh = channel;
  apErr[0] = 0;
  apState = AP_STARTING; apWant = true; apReq = true;
}
void net_ap_stop() { apWant = false; apReq = true; }
ApState net_ap_state() { return apState; }
const char *net_ap_error() { return apErr; }

