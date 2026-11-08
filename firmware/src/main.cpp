// Deskling for the Elecrow CrowPanel 1.46" rotary display.
// A desk pet that plays its animation loops, plus a dial-driven Home Assistant menu.
//
// Hardware bring-up (power rails, LCD reset, DMA flush) mirrors Elecrow's official Core 3 + LVGL 9 example.
#include <Arduino.h>
#include <Wire.h>
#include <esp_heap_caps.h>
#include <lvgl.h>
#include <WiFi.h>
#include "board.h"
#include "config.h"
#include "app.h"
#include "input.h"
#include "leds.h"
#include "net.h"
#include "settings.h"
#include "crashlog.h"
#include "api.h"
#include "ble.h"
#include "care.h"

LGFX gfx;
static lv_display_t *display;

// ---------- display ----------
static void flush_cb(lv_display_t *disp, const lv_area_t *a, uint8_t *px) {
  if (gfx.getStartCount() > 0) gfx.endWrite();
  gfx.pushImageDMA(a->x1, a->y1, a->x2 - a->x1 + 1, a->y2 - a->y1 + 1, (lgfx::rgb565_t *)px);
  gfx.waitDMA();
  lv_display_flush_ready(disp);
}

// ---------- touch (CST816T at 0x15) ----------
static bool tp_read(uint16_t &x, uint16_t &y) {
  Wire.beginTransmission(0x15);
  Wire.write(0x02);                                   // finger count, then X/Y high/low bytes
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom(0x15, 5) != 5) return false;
  uint8_t n = Wire.read(), xh = Wire.read(), xl = Wire.read(), yh = Wire.read(), yl = Wire.read();
  if ((n & 0x0F) == 0) return false;
  x = ((xh & 0x0F) << 8) | xl;
  y = ((yh & 0x0F) << 8) | yl;
  return !(x == 0 && y == 0);
}

// Touch gestures: tap (released before 0.6 s) and hold (0.6 s, sent while still held). Position doesn't
// matter: the screen is one big button. Two empty reads in a row count as a release, because the
// controller occasionally drops one mid-touch.
//
// The knob is pressed by pushing the display in, so a knob press is also a touch. A touch only counts if the
// button stayed still for the whole of it: a tap waits 150 ms after the finger lifts and is dropped if the
// button moved at any point from 100 ms before the touch began until then. Without this one knob press
// arrived twice (the menu plus its first item; every character typed twice).
static void touch_poll() {
  static uint32_t lastPoll = 0, downAt = 0, tapAt = 0;
  static bool down = false, holdSent = false, tapPending = false, knobSeen = false;
  static uint8_t upReads = 0;
  uint32_t now = millis();
  if (now - lastPoll < 20) return;
  lastPoll = now;

  uint32_t act = input_button_last_activity();
  if ((down || tapPending) && (input_button_down() || (act && (int32_t)(act - (downAt - 100)) >= 0))) knobSeen = true;

  if (tapPending && now - tapAt >= 150) {
    tapPending = false;
    if (!knobSeen) input_inject(IN_TAP);
  }

  uint16_t x, y;
  bool t = tp_read(x, y);
  if (t) {
    upReads = 0;
    if (!down) {
      down = true; downAt = now; holdSent = false; tapPending = false;
      knobSeen = input_button_down() || (act && now - act < 100);
    } else if (!holdSent && !knobSeen && now - downAt >= 600) { input_inject(IN_HOLD); holdSent = true; }
  } else if (down && ++upReads >= 2) {
    down = false;
    if (!holdSent && now - downAt >= 30) { tapPending = true; tapAt = now; }
  }
}

static void touch_begin() {
  pinMode(PIN_TP_INT, INPUT);
  pinMode(PIN_TP_RST, OUTPUT);
  digitalWrite(PIN_TP_RST, HIGH); delay(20);
  digitalWrite(PIN_TP_RST, LOW);  delay(10);
  digitalWrite(PIN_TP_RST, HIGH); delay(100);
  Wire.begin(PIN_TP_SDA, PIN_TP_SCL);
  Wire.beginTransmission(0x15);                       // keep the controller awake so polling always answers
  Wire.write(0xFE); Wire.write(0x01);
  Wire.endTransmission();
}

// ---------- app ----------
void backlight(int pct) { ledcWrite(PIN_BACKLIGHT, constrain(pct, 0, 100) * 255 / 100); }

// The screen input goes to. Set the moment a switch starts: lv_screen_active() can still report the old
// screen while the fade runs, and input landing there was what opened the care ring behind the menu.
static lv_obj_t *target = nullptr;
static uint32_t switchedAt = 0;
static void switching_to(lv_obj_t *s) { target = s; switchedAt = millis(); }

void app_show_pet() {
  switching_to(pet_screen());
  lv_screen_load_anim(pet_screen(), LV_SCR_LOAD_ANIM_FADE_IN, 180, 0, false);
  pet_enter();
}

void app_show_setup() {
  switching_to(setup_screen());
  setup_enter();
  lv_screen_load_anim(setup_screen(), LV_SCR_LOAD_ANIM_FADE_IN, 180, 0, false);
}

void app_show_hub() {
  switching_to(setup_screen());
  setup_enter_hub();
  lv_screen_load_anim(setup_screen(), LV_SCR_LOAD_ANIM_FADE_IN, 180, 0, false);
}

static uint32_t tick_cb() { return millis(); }

#define BOOT_STEP(msg) Serial.printf("[boot %5lu ms] %s\n", millis(), msg)

// Keep an IP5306 battery board awake while the dial naps (see KEEPALIVE_EVERY_MS in config.h): while the screen
// is dimmed the Wi-Fi radio stays out of power save, and every few seconds this core spins for a moment.
// Nothing shows on the screen or the ring. On USB power it costs nothing that matters.
static void keepalive_task(void *) {
  bool radioAwake = false;
  for (;;) {
    vTaskDelay(pdMS_TO_TICKS(KEEPALIVE_EVERY_MS));
    bool dim = pet_dimmed(), conn = WiFi.status() == WL_CONNECTED;
    if (dim && conn && !radioAwake) { WiFi.setSleep(false); radioAwake = true; }
    else if ((!dim || !conn) && radioAwake) { WiFi.setSleep(true); radioAwake = false; }
    if (!dim) continue;
    uint32_t start = millis();
    volatile uint32_t spin = 0;
    while (millis() - start < KEEPALIVE_PULSE_MS) spin = spin + 1;
  }
}

void setup() {
  Serial.begin(115200);
  crashlog_begin();
  settings_begin();
  care_begin();
  BOOT_STEP("setup start");
  Serial.printf("  psram %u bytes, free heap %u, flash %u\n", ESP.getPsramSize(), ESP.getFreeHeap(), ESP.getFlashChipSize());

  // LCD reset pulse, then both LCD power rails, as in Elecrow's bring-up
  pinMode(PIN_LCD_RST, OUTPUT);
  digitalWrite(PIN_LCD_RST, HIGH); delay(10);
  digitalWrite(PIN_LCD_RST, LOW);  delay(10);
  digitalWrite(PIN_LCD_RST, HIGH);
  pinMode(PIN_POWER_LED, OUTPUT); digitalWrite(PIN_POWER_LED, LOW);
  pinMode(PIN_LCD_PWR_A, OUTPUT); digitalWrite(PIN_LCD_PWR_A, HIGH);
  pinMode(PIN_LCD_PWR_B, OUTPUT); digitalWrite(PIN_LCD_PWR_B, HIGH);

  // Backlight on early so a hang later in setup is visible as a lit (not black) screen
  ledcAttach(PIN_BACKLIGHT, 5000, 8);
  backlight(settings_bright());
  BOOT_STEP("power + backlight on");

  touch_begin();
  BOOT_STEP("touch ok");
  gfx.init();
  gfx.initDMA();
  gfx.startWrite();
  gfx.fillScreen(gfx.color888((PET_BG >> 16) & 0xff, (PET_BG >> 8) & 0xff, PET_BG & 0xff));

  BOOT_STEP("lcd init + first fill");

  lv_init();
  lv_tick_set_cb(tick_cb);

  // One partial-render buffer in internal DMA RAM (fast); PSRAM only if that ever fails. flush_cb waits for
  // each transfer to finish, so a second buffer never overlapped with the first: it only cost 21 KB of the
  // internal RAM the Wi-Fi and Bluetooth drivers need.
  const size_t lines = 30, bytes = SCREEN_W * lines * 2;
  uint8_t *b1 = (uint8_t *)heap_caps_malloc(bytes, MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
  if (!b1) b1 = (uint8_t *)heap_caps_malloc(bytes, MALLOC_CAP_SPIRAM);
  uint8_t *b2 = nullptr;
  display = lv_display_create(SCREEN_W, SCREEN_H);
  lv_display_set_color_format(display, LV_COLOR_FORMAT_RGB565);
  lv_display_set_flush_cb(display, flush_cb);
  lv_display_set_buffers(display, b1, b2, bytes, LV_DISPLAY_RENDER_MODE_PARTIAL);

  // no LVGL pointer device: touch is read by touch_poll() and arrives as the same events as the knob

  BOOT_STEP("lvgl display ready");
  pet_create();
  BOOT_STEP("pet screen built");
  setup_create();
  BOOT_STEP("settings screens built");
  lv_screen_load(pet_screen());

  leds_begin();
  BOOT_STEP("leds task");
  input_begin();
  BOOT_STEP("input task");
  net_begin();
  BOOT_STEP("wifi task");
  pet_enter();
  api_begin();
  ble_begin();
  if (KEEPALIVE_EVERY_MS > 0) xTaskCreatePinnedToCore(keepalive_task, "keepalive", 2048, nullptr, 1, nullptr, 0);
  BOOT_STEP("setup done");
}

void loop() {
  InputEvent e;
  touch_poll();
  while (input_poll(e)) {
    if (millis() - switchedAt < 200) continue;               // mid-switch: nothing should land anywhere
    lv_obj_t *act = target ? target : lv_screen_active();
    if (e == IN_TAP && act != pet_screen()) e = IN_PRESS;     // only the pet screen tells a tap from a press
    if (act == setup_screen()) { pet_activity(); setup_input(e); }
    else pet_input(e);
  }
  pet_tick();
  setup_tick();
  api_poll();
  lv_timer_handler();
  static uint32_t hb = 0;
  if (millis() - hb > 30000) { hb = millis(); Serial.printf("[alive %lus] heap %u psram %u\n", millis() / 1000, ESP.getFreeHeap(), ESP.getFreePsram()); }
  delay(5);
}
