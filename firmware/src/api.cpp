#include "api.h"
void pet_remote_done(int need);   // pet.cpp
void pet_companion_moved();       // pet.cpp
void backlight(int pct);         // main.cpp
#include "config.h"
#include "settings.h"
#include "care.h"
#include "companion.h"
#include "net.h"
#include "web.h"
#include "ble.h"
#include <ArduinoJson.h>
#include <Preferences.h>
#include <WiFi.h>
#include <ESPmDNS.h>
#include <atomic>
#include <esp_mac.h>

int clockface_style_count();
const char *clockface_style_name(int style);
bool setup_wifi_fail_text(uint32_t el, char *why, size_t n);       // setup_ui.cpp

// ---------- keys ----------
static const int MAX_PHONES = 4;
struct Phone { uint8_t key[16]; char name[24]; };
static Phone phones[MAX_PHONES];
static int phoneCount = 0;
static Preferences phonePrefs;
static char dialName[20];

static void keys_load() {
  phonePrefs.begin("phones", false);
  phoneCount = 0;
  size_t n = phonePrefs.getBytesLength("list");
  if (n && n % sizeof(Phone) == 0 && n <= sizeof(phones)) {
    phonePrefs.getBytes("list", phones, n);
    phoneCount = n / sizeof(Phone);
  }
}
static void keys_save() { phonePrefs.putBytes("list", phones, phoneCount * sizeof(Phone)); }

static void hex(const uint8_t *b, int n, char *out) {
  static const char H[] = "0123456789abcdef";
  for (int i = 0; i < n; i++) { out[i * 2] = H[b[i] >> 4]; out[i * 2 + 1] = H[b[i] & 15]; }
  out[n * 2] = 0;
}

bool api_key_ok(const char *k) {
  if (!k || strlen(k) != 32) return false;
  char h[33];
  for (int i = 0; i < phoneCount; i++) {
    hex(phones[i].key, 16, h);
    uint8_t diff = 0;                                   // compare without an early exit
    for (int j = 0; j < 32; j++) diff |= (uint8_t)(h[j] ^ tolower((unsigned char)k[j]));
    if (!diff) return true;
  }
  return false;
}

int api_paired_count() { return phoneCount; }
void api_forget_all() { phoneCount = 0; keys_save(); }
const char *api_dial_name() { return dialName; }

// ---------- pairing ----------
static bool pairOpen = false;
static uint32_t pairAt = 0;
static char pairCode[7] = "";
static char lastPaired[24] = "";

void api_pair_open(bool on) {
  pairOpen = on;
  lastPaired[0] = 0;
  if (on) {
    snprintf(pairCode, sizeof(pairCode), "%06lu", (unsigned long)(esp_random() % 1000000));
    pairAt = millis();
  }
}
bool api_pair_is_open() { return pairOpen; }
const char *api_pair_code() { return pairCode; }
const char *api_last_paired() { return lastPaired; }

// ---------- requests, carried out on the main loop ----------
struct Req {
  const char *in;
  String out;
  bool authed;
  SemaphoreHandle_t done;
  std::atomic<int> state{0};        // 0 waiting, 1 answered, 2 caller gave up (main loop frees it)
};
static QueueHandle_t q;

bool api_call(const char *json, bool &authed, String &out) {
  Req *r = new Req();
  r->in = strdup(json);
  r->authed = authed;
  r->done = xSemaphoreCreateBinary();
  if (!r->in || !r->done || xQueueSend(q, &r, pdMS_TO_TICKS(1000)) != pdTRUE) {
    free((void *)r->in); if (r->done) vSemaphoreDelete(r->done); delete r;
    out = "{\"ok\":false,\"err\":\"busy\"}";
    return false;
  }
  if (xSemaphoreTake(r->done, pdMS_TO_TICKS(5000)) != pdTRUE) {
    int expect = 0;
    if (r->state.compare_exchange_strong(expect, 2)) { out = "{\"ok\":false,\"err\":\"timeout\"}"; return false; }
    xSemaphoreTake(r->done, portMAX_DELAY);            // it finished just now
  }
  out = r->out;
  authed = r->authed;
  free((void *)r->in);
  vSemaphoreDelete(r->done);
  delete r;
  return true;
}

// ---------- Wi-Fi joins asked for by the app ----------
static bool tryOn = false;
static uint32_t tryAt = 0;
static char trySsid[33], tryPass[65], tryMsg[128] = "";
static int tryResult = 0;           // 0 none, 1 trying, 2 ok, 3 failed

static void try_tick() {
  if (!tryOn) return;
  if (net_try_pending()) { tryAt = millis(); return; }
  uint32_t el = millis() - tryAt;
  if (el > 1500 && WiFi.status() == WL_CONNECTED && WiFi.SSID() == trySsid) {
    static Settings s;
    settings_get(s);
    strlcpy(s.ssid, trySsid, sizeof(s.ssid));
    strlcpy(s.pass, tryPass, sizeof(s.pass));
    settings_put(s);
    tryOn = false; tryResult = 2;
    snprintf(tryMsg, sizeof(tryMsg), "%s", WiFi.localIP().toString().c_str());
  } else if (setup_wifi_fail_text(el, tryMsg, sizeof(tryMsg))) {
    tryOn = false; tryResult = 3;
    net_use_saved();
  }
}

// ---------- the ops ----------
static const char *const NEED_KEY[NEED_COUNT] = { "water", "eyes", "stretch", "move" };

static int need_of(JsonVariantConst v) {
  if (v.is<int>()) { int n = v.as<int>(); return n >= 0 && n < NEED_COUNT ? n : -1; }
  const char *s = v.as<const char *>();
  if (s) for (int i = 0; i < NEED_COUNT; i++) if (!strcmp(s, NEED_KEY[i])) return i;
  return -1;
}

// ---------- the companion, travelling ----------
// The record a move carries: who it is, the move count, and its care state. The app reads and writes the
// same shape (app: data/Companion.kt), and passes it on to other dials and phones unchanged.
static void put_companion(JsonObject o) {
  o["cid"] = care_cid();
  o["pet"] = PET_ID;
  o["epoch"] = care_epoch();
  CareSnapshot c;
  care_snapshot(c);
  JsonObject k = o["care"].to<JsonObject>();
  JsonArray el = k["elapsed"].to<JsonArray>(), td = k["today"].to<JsonArray>();
  for (int i = 0; i < NEED_COUNT; i++) { el.add(c.elapsed[i]); td.add(c.today[i]); }
  k["happy"] = c.happy; k["xp"] = c.xp; k["day"] = c.day; k["streak"] = c.streak; k["bedtime"] = c.bedtime;
  k["standing"] = c.standing; k["postSec"] = c.postSec; k["stands"] = c.standsToday; k["pause"] = c.pauseS; k["woke"] = c.woke;
}

static bool is_cid(const char *s) {
  if (!s || strlen(s) != 16) return false;
  for (const char *p = s; *p; p++) if (!isxdigit((unsigned char)*p)) return false;
  return true;
}

static bool read_companion(JsonObjectConst o, const char *&cid, int &pet, uint32_t &epoch, CareSnapshot &c) {
  cid = o["cid"] | "";
  pet = companion_find(o["pet"] | "");
  epoch = o["epoch"] | 0u;
  JsonObjectConst k = o["care"];
  if (!is_cid(cid) || pet < 0 || !epoch || k.isNull()) return false;
  JsonArrayConst el = k["elapsed"], td = k["today"];
  if (el.size() != NEED_COUNT || td.size() != NEED_COUNT) return false;
  for (int i = 0; i < NEED_COUNT; i++) { c.elapsed[i] = el[i] | 0u; c.today[i] = min(999u, td[i] | 0u); }
  c.happy = k["happy"] | 70.0f; c.xp = k["xp"] | 0u; c.day = k["day"] | 0; c.streak = k["streak"] | 0;
  c.bedtime = k["bedtime"] | false; c.standing = k["standing"] | false; c.postSec = k["postSec"] | 0u;
  c.standsToday = k["stands"] | 0; c.pauseS = min(24u * 3600, k["pause"] | 0u);
  c.woke = k["woke"] | false;
  return true;
}

static uint32_t restartAt = 0;     // a new companion moved in: restart once the reply is out, to show it

static void put_travel(JsonObject o) {
  o["pet"] = PET_ID;
  o["cid"] = care_cid();
  o["epoch"] = care_epoch();
  o["here"] = care_here();
  o["where"] = care_here() ? "" : care_where();
}

static void put_state(JsonObject o) {
  o["time"] = (uint32_t)(care_clock_valid() ? time(nullptr) : 0);
  put_travel(o);
  o["mood"] = care_mood_name(care_mood());
  o["happy"] = care_happiness();
  o["xp"] = care_xp();
  o["level"] = care_level();
  o["levelProgress"] = care_level_progress();
  o["streak"] = care_streak();
  o["inHours"] = care_in_hours();
  o["active"] = care_active();
  o["paused"] = care_paused() ? care_pause_left_s() : 0;
  o["bedtime"] = care_bedtime();
  o["woke"] = care_woke_early();
  o["urgent"] = care_most_urgent() >= 0 ? NEED_KEY[care_most_urgent()] : nullptr;
  JsonArray needs = o["needs"].to<JsonArray>();
  for (int i = 0; i < NEED_COUNT; i++) {
    Need n = (Need)i;
    JsonObject x = needs.add<JsonObject>();
    x["id"] = NEED_KEY[i];
    x["name"] = care_need_name(n);
    x["elapsed"] = care_need_elapsed_s(n);
    x["interval"] = care_need_interval_s(n);
    x["due"] = care_need_due(n);
    x["today"] = care_today(n);
  }
  o["day"] = care_today_date();
  o["steps"] = care_steps_today();
  JsonObject ps = o["posture"].to<JsonObject>();
  ps["on"] = care_posture_on();
  ps["standing"] = care_standing();
  ps["elapsed"] = care_posture_elapsed_s();
  ps["interval"] = care_posture_interval_s();
  ps["due"] = care_posture_due();
  ps["stands"] = care_stands_today();
  JsonObject w = o["wifi"].to<JsonObject>();
  bool up = WiFi.status() == WL_CONNECTED;
  w["up"] = up;
  w["ssid"] = up ? WiFi.SSID() : String("");
  w["ip"] = up ? WiFi.localIP().toString() : String("");
  static const char *const TRY[] = { "", "trying", "ok", "fail" };
  w["try"] = TRY[tryResult];
  w["tryMsg"] = tryMsg;
}

static void put_settings(JsonObject o) {
  static Settings s;
  settings_get(s);
  JsonArray r = o["remind"].to<JsonArray>();
  for (int i = 0; i < 4; i++) r.add(s.remind_min[i]);
  o["hourStart"] = s.hour_start; o["hourEnd"] = s.hour_end; o["tz"] = s.tz;
  o["clockMode"] = s.clock_mode; o["clockStyle"] = s.clock_style;
  o["showDate"] = s.show_date; o["showStatus"] = s.show_status; o["showNext"] = s.show_next;
  o["bright"] = s.bright; o["ledBright"] = s.led_bright; o["napMin"] = s.nap_min;
  o["encInvert"] = s.enc_invert; o["ledOffset"] = s.led_offset;
  o["postureOn"] = s.posture_on; o["sitMin"] = s.sit_min; o["standMin"] = s.stand_min;
  o["ssid"] = s.ssid;
  JsonArray tz = o["tzNames"].to<JsonArray>();
  for (int i = 0; i < care_tz_count(); i++) tz.add(care_tz_name(i));
  JsonArray cs = o["clockStyles"].to<JsonArray>();
  for (int i = 0; i < clockface_style_count(); i++) cs.add(clockface_style_name(i));
  o["pet"] = PET_ID;
  JsonArray pets = o["pets"].to<JsonArray>();
  for (int i = 0; i < companion_count(); i++) pets.add(COMPANIONS[i]->id);
}

static void apply_set(JsonObjectConst in) {
  static Settings s;
  settings_get(s);
  if (in["remind"].is<JsonArrayConst>()) {
    JsonArrayConst r = in["remind"];
    for (int i = 0; i < 4 && i < (int)r.size(); i++) s.remind_min[i] = constrain(r[i].as<int>(), 0, 120);
  }
  if (in["hourStart"].is<int>()) s.hour_start = constrain(in["hourStart"].as<int>(), 0, 23);
  if (in["hourEnd"].is<int>()) s.hour_end = constrain(in["hourEnd"].as<int>(), 1, 24);
  if (in["tz"].is<int>()) s.tz = constrain(in["tz"].as<int>(), 0, care_tz_count() - 1);
  if (in["clockMode"].is<int>()) s.clock_mode = constrain(in["clockMode"].as<int>(), 0, 2);
  if (in["clockStyle"].is<int>()) s.clock_style = constrain(in["clockStyle"].as<int>(), 0, clockface_style_count() - 1);
  if (in["showDate"].is<bool>()) s.show_date = in["showDate"];
  if (in["showStatus"].is<bool>()) s.show_status = in["showStatus"];
  if (in["showNext"].is<bool>()) s.show_next = in["showNext"];
  if (in["bright"].is<int>()) s.bright = constrain(in["bright"].as<int>(), 10, 100);
  if (in["ledBright"].is<int>()) s.led_bright = constrain(in["ledBright"].as<int>(), 5, 255);
  if (in["napMin"].is<int>()) s.nap_min = constrain(in["napMin"].as<int>(), 0, 60);
  if (in["encInvert"].is<bool>()) s.enc_invert = in["encInvert"];
  if (in["ledOffset"].is<int>()) s.led_offset = constrain(in["ledOffset"].as<int>(), -16, 16);
  if (in["postureOn"].is<bool>()) s.posture_on = in["postureOn"];
  if (in["sitMin"].is<int>()) s.sit_min = constrain(in["sitMin"].as<int>(), 15, 120);
  if (in["standMin"].is<int>()) s.stand_min = constrain(in["standMin"].as<int>(), 5, 90);
  settings_put(s);
  backlight(s.bright);
  // which companion: only where it is (elsewhere it's the phone's or another dial's to change)
  int pet = companion_find(in["pet"] | "");
  if (pet >= 0 && pet != companion_index() && care_here()) { companion_set(pet); restartAt = millis() + 1500; }
}

static void handle(Req *r) {
  JsonDocument in, outDoc;
  JsonObject out = outDoc.to<JsonObject>();
  DeserializationError err = deserializeJson(in, r->in);
  if (err) { out["ok"] = false; out["err"] = "bad json"; serializeJson(outDoc, r->out); return; }
  const char *op = in["op"] | "";
  if (in["id"].is<int>()) out["id"] = in["id"];
  out["ok"] = true;

  if (!strcmp(op, "pair")) {
    const char *code = in["code"] | "";
    if (!pairOpen || strcmp(code, pairCode)) { out["ok"] = false; out["err"] = pairOpen ? "wrong code" : "open Settings > Phone app on the dial"; }
    else {
      Phone p;
      esp_fill_random(p.key, sizeof(p.key));
      strlcpy(p.name, in["phone"] | "phone", sizeof(p.name));
      if (phoneCount == MAX_PHONES) { memmove(phones, phones + 1, sizeof(Phone) * (MAX_PHONES - 1)); phoneCount--; }   // drop the oldest
      phones[phoneCount++] = p;
      keys_save();
      char h[33]; hex(p.key, 16, h);
      out["key"] = h;
      out["name"] = dialName;
      put_travel(out);
      strlcpy(lastPaired, p.name, sizeof(lastPaired));
      pairOpen = false;
      r->authed = true;
    }
  } else if (!strcmp(op, "hello")) {
    if (api_key_ok(in["key"] | "")) {
      r->authed = true;
      out["name"] = dialName;
      out["fw"] = FW_VERSION;
      put_travel(out);
      if (WiFi.status() == WL_CONNECTED) out["ip"] = WiFi.localIP().toString();
    } else { out["ok"] = false; out["err"] = "unknown key: pair again"; }
  } else if (!r->authed) {
    out["ok"] = false; out["err"] = "not paired";
  } else {
    // the phone's clock is a good time source when the dial has none yet
    if (in["now"].is<uint32_t>() && in["now"].as<uint32_t>() > 1700000000u) care_clock_hint((time_t)in["now"].as<uint32_t>());
    static const char *const CARE_OPS[] = { "done", "pet", "played", "pause", "bedtime", "wake", "posture" };
    bool careOp = false;
    for (const char *c : CARE_OPS) careOp |= !strcmp(op, c);
    if (careOp && !care_here()) { out["ok"] = false; out["err"] = "away"; put_state(out); }
    else if (!strcmp(op, "state")) put_state(out);
    else if (!strcmp(op, "settings")) put_settings(out);
    else if (!strcmp(op, "set")) { apply_set(in.as<JsonObjectConst>()); put_settings(out); }
    else if (!strcmp(op, "done")) {
      int n = need_of(in["need"]);
      if (n < 0) { out["ok"] = false; out["err"] = "unknown need"; }
      else {
        uint32_t at = in["at"] | 0u, now = care_clock_valid() ? (uint32_t)time(nullptr) : 0;
        uint32_t age = at && now && now > at ? now - at : 0;
        if (age > 24 * 3600) age = 24 * 3600;
        care_done_at((Need)n, age);
        pet_remote_done(n);
        put_state(out);
      }
    }
    else if (!strcmp(op, "pet")) { out["counted"] = care_pet(); put_state(out); }
    else if (!strcmp(op, "played")) { care_played(in["hits"] | 0, in["rounds"] | 5); put_state(out); }
    else if (!strcmp(op, "pause")) { care_pause(in["minutes"] | 0); put_state(out); }
    else if (!strcmp(op, "bedtime")) { care_set_bedtime(in["on"] | false); put_state(out); }
    else if (!strcmp(op, "wake")) { care_wake(); put_state(out); }
    else if (!strcmp(op, "posture")) { care_posture_set(in["standing"] | false); put_state(out); }
    else if (!strcmp(op, "steps")) { care_set_steps(in["day"] | 0, in["count"] | 0u); out["steps"] = care_steps_today(); }
    else if (!strcmp(op, "leave")) {
      // {"op":"leave","to":"Pixel 8"}: the companion goes with the phone asking. Asking again (the reply got
      // lost) hands over the same record; a different phone hears where it went.
      char to[33];
      strlcpy(to, in["to"] | "", sizeof(to));
      if (!*to) strlcpy(to, "your phone", sizeof(to));
      bool left = care_here();
      if (left) care_leave(to);
      if (strcmp(care_where(), to)) { out["ok"] = false; out["err"] = "away"; put_travel(out); }
      else put_companion(out["companion"].to<JsonObject>());
      if (left) pet_companion_moved();
    }
    else if (!strcmp(op, "arrive")) {
      // {"op":"arrive","companion":{...},"replace":false}: it moves in here. The epoch it arrives with is the
      // one it left with plus one.
      const char *cid; int pet; uint32_t epoch; CareSnapshot c;
      if (!read_companion(in["companion"], cid, pet, epoch, c)) { out["ok"] = false; out["err"] = "bad companion"; }
      else if (!strcmp(cid, care_cid()) && care_epoch() > epoch) {
        out["already"] = true;                     // it already moved in (a retry), or this copy is out of date
      }
      else if (care_here() && strcmp(cid, care_cid()) && !(in["replace"] | false)) {
        out["ok"] = false; out["err"] = "occupied";
        out["level"] = care_level();
      }
      else {
        care_arrive(cid, epoch + 1, c);
        if (pet != companion_index()) { companion_set(pet); restartAt = millis() + 1500; out["restart"] = true; }
        pet_companion_moved();
      }
      put_travel(out);
    }
    else if (!strcmp(op, "where")) {
      // {"op":"where","cid":"...","epoch":9,"at":"Deskling-77B2"}: news of where it is now, from a phone
      if (!strcmp(in["cid"] | "", care_cid())) care_set_where(in["epoch"] | 0u, in["at"] | "");
      put_travel(out);
    }
    else if (!strcmp(op, "scan")) { net_scan(); }
    else if (!strcmp(op, "nets")) {
      ScanState st = net_scan_state();
      out["state"] = st == SCAN_BUSY ? "busy" : st == SCAN_DONE ? "done" : st == SCAN_FAILED ? "failed" : "idle";
      JsonArray a = out["nets"].to<JsonArray>();
      if (st == SCAN_DONE)
        for (int i = 0; i < net_scan_count(); i++) {
          const WifiNet &w = net_scan_net(i);
          JsonObject x = a.add<JsonObject>();
          x["ssid"] = w.ssid; x["rssi"] = w.rssi; x["open"] = w.open;
        }
    }
    else if (!strcmp(op, "wifi")) {
      const char *ss = in["ssid"] | "", *pp = in["pass"] | "";
      static Settings s;
      settings_get(s);
      if (!*ss || strlen(ss) > 32 || strlen(pp) > 64) { out["ok"] = false; out["err"] = "bad network name or password"; }
      else {
        strlcpy(trySsid, ss, sizeof(trySsid));
        strlcpy(tryPass, *pp || strcmp(ss, s.ssid) ? pp : s.pass, sizeof(tryPass));   // empty: keep the saved one
        net_try(trySsid, tryPass);
        tryOn = true; tryAt = millis(); tryResult = 1; tryMsg[0] = 0;
      }
    }
    else { out["ok"] = false; out["err"] = "unknown op"; }
  }
  serializeJson(outDoc, r->out);
}

// ---------- main loop ----------
static bool mdnsUp = false;

void api_begin() {
  keys_load();
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_STA);          // works before Wi-Fi has started
  snprintf(dialName, sizeof(dialName), "Deskling-%02X%02X", mac[4], mac[5]);
  q = xQueueCreate(4, sizeof(Req *));
}

void api_poll() {
  Req *r;
  while (xQueueReceive(q, &r, 0) == pdTRUE) {
    if (r->state.load() == 2) { free((void *)r->in); vSemaphoreDelete(r->done); delete r; continue; }
    handle(r);
    int expect = 0;
    if (r->state.compare_exchange_strong(expect, 1)) xSemaphoreGive(r->done);
    else { free((void *)r->in); vSemaphoreDelete(r->done); delete r; }   // the caller gave up meanwhile
  }
  try_tick();
  if (restartAt && (int32_t)(millis() - restartAt) > 0) ESP.restart();
  if (pairOpen && millis() - pairAt > 3 * 60000UL) pairOpen = false;
  // Bluetooth back once the setup network is gone and its memory returned
  static uint32_t lastResume = 0;
  if (ble_paused() && !web_ap_active() && net_ap_state() == AP_OFF && millis() - lastResume > 3000) {
    lastResume = millis();
    ble_resume();
  }
  // the API answers on your network whenever the dial is on it
  bool up = WiFi.status() == WL_CONNECTED;
  if (up) {
    web_begin();
    if (!mdnsUp && MDNS.begin(dialName)) {
      MDNS.addService("deskling", "tcp", 80);
      mdnsUp = true;
    }
  }
}
