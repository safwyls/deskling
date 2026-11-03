#include "settings.h"
#include "config.h"
#include <Preferences.h>

static Preferences prefs;
static Settings cur;
static SemaphoreHandle_t mtx;
static volatile uint32_t ver = 1;
static volatile int8_t hotOffset;
static volatile bool hotInvert;
static volatile uint8_t hotBright, hotLedBright, hotNap;

static void hot_from(const Settings &s) {
  hotOffset = s.led_offset; hotInvert = s.enc_invert; hotBright = s.bright;
  hotLedBright = s.led_bright; hotNap = s.nap_min;
}

static void load_str(const char *key, char *buf, size_t n, const char *def) {
  if (prefs.isKey(key)) prefs.getString(key, buf, n);
  else strlcpy(buf, def, n);
}

static void load() {
  memset(&cur, 0, sizeof(cur));
  load_str("ssid", cur.ssid, sizeof(cur.ssid), DEFAULT_WIFI_SSID);
  load_str("pass", cur.pass, sizeof(cur.pass), DEFAULT_WIFI_PASS);
  cur.led_offset = prefs.getChar("ledoff", DEFAULT_LED_OFFSET);
  cur.enc_invert = prefs.getBool("inv", DEFAULT_ENCODER_INVERT);
  cur.bright = prefs.getUChar("bright", DEFAULT_BRIGHTNESS);
  cur.led_bright = prefs.getUChar("ledbr", DEFAULT_LED_BRIGHTNESS);
  cur.nap_min = prefs.getUChar("nap", DEFAULT_NAP_MIN);
  static const uint8_t REM_DEF[4] = { DEFAULT_REMIND_WATER, DEFAULT_REMIND_EYES, DEFAULT_REMIND_STRETCH, DEFAULT_REMIND_MOVE };
  static const char *const REM_KEY[4] = { "rem_water", "rem_eyes", "rem_stretch", "rem_move" };
  for (int i = 0; i < 4; i++) cur.remind_min[i] = prefs.getUChar(REM_KEY[i], REM_DEF[i]);
  cur.hour_start = prefs.getUChar("h_start", DEFAULT_HOUR_START);
  cur.hour_end = prefs.getUChar("h_end", DEFAULT_HOUR_END);
  cur.tz = prefs.getUChar("tz", DEFAULT_TZ);
  cur.clock_mode = prefs.getUChar("clock", DEFAULT_CLOCK_MODE);
  cur.clock_style = prefs.getUChar("clock_st", DEFAULT_CLOCK_STYLE);
  cur.show_date = prefs.getBool("s_date", DEFAULT_SHOW_DATE);
  cur.show_status = prefs.getBool("s_status", DEFAULT_SHOW_STATUS);
  cur.show_next = prefs.getBool("s_next", DEFAULT_SHOW_NEXT);
  hot_from(cur);
}

void settings_begin() {
  mtx = xSemaphoreCreateMutex();
  prefs.begin("deskling", false);
  load();
}

void settings_get(Settings &out) {
  xSemaphoreTake(mtx, portMAX_DELAY);
  out = cur;
  xSemaphoreGive(mtx);
}

void settings_put(const Settings &s) {
  xSemaphoreTake(mtx, portMAX_DELAY);
  // only write what changed, to spare the flash
  if (strcmp(s.ssid, cur.ssid)) prefs.putString("ssid", s.ssid);
  if (strcmp(s.pass, cur.pass)) prefs.putString("pass", s.pass);
  if (s.led_offset != cur.led_offset) prefs.putChar("ledoff", s.led_offset);
  if (s.enc_invert != cur.enc_invert) prefs.putBool("inv", s.enc_invert);
  if (s.bright != cur.bright) prefs.putUChar("bright", s.bright);
  if (s.led_bright != cur.led_bright) prefs.putUChar("ledbr", s.led_bright);
  if (s.nap_min != cur.nap_min) prefs.putUChar("nap", s.nap_min);
  static const char *const REM_KEY[4] = { "rem_water", "rem_eyes", "rem_stretch", "rem_move" };
  for (int i = 0; i < 4; i++) if (s.remind_min[i] != cur.remind_min[i]) prefs.putUChar(REM_KEY[i], s.remind_min[i]);
  if (s.hour_start != cur.hour_start) prefs.putUChar("h_start", s.hour_start);
  if (s.hour_end != cur.hour_end) prefs.putUChar("h_end", s.hour_end);
  if (s.tz != cur.tz) prefs.putUChar("tz", s.tz);
  if (s.clock_mode != cur.clock_mode) prefs.putUChar("clock", s.clock_mode);
  if (s.clock_style != cur.clock_style) prefs.putUChar("clock_st", s.clock_style);
  if (s.show_date != cur.show_date) prefs.putBool("s_date", s.show_date);
  if (s.show_status != cur.show_status) prefs.putBool("s_status", s.show_status);
  if (s.show_next != cur.show_next) prefs.putBool("s_next", s.show_next);
  cur = s;
  hot_from(cur);
  ver = ver + 1;
  xSemaphoreGive(mtx);
}

void settings_reset() {
  xSemaphoreTake(mtx, portMAX_DELAY);
  prefs.clear();
  load();
  ver = ver + 1;
  xSemaphoreGive(mtx);
}

uint32_t settings_version() { return ver; }
bool settings_enc_invert() { return hotInvert; }
int settings_led_offset() { return hotOffset; }
int settings_bright() { return hotBright; }
int settings_led_bright() { return hotLedBright; }
int settings_nap_min() { return hotNap; }
void settings_preview_led_offset(int v) { hotOffset = v; }
void settings_preview_led_bright(int v) { hotLedBright = v; }
