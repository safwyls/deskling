// Settings kept in flash (NVS). Defaults come from include/config.h.
// settings_get()/settings_put() are thread safe; the small hot values that tasks read constantly
// (knob direction, LED offset, brightness, nap timer) have their own lock-free getters.
#pragma once
#include <Arduino.h>


struct Settings {
  char ssid[33];
  char pass[65];
  int8_t led_offset;                  // quarter-LED steps
  bool enc_invert;
  uint8_t bright;                     // screen brightness, percent
  uint8_t led_bright;                 // ring brightness, 0-255
  uint8_t nap_min;                    // 0 = never
  uint8_t remind_min[4];              // water, eyes, stretch, move: minutes between reminders, 0 = off
  uint8_t hour_start, hour_end;       // active hours
  uint8_t tz;                         // index into care.cpp's time zone list
  uint8_t clock_mode;                 // home screen: 0 off, 1 12-hour, 2 24-hour
  uint8_t clock_style;                // ClockStyle
  bool show_date, show_status, show_next;
};

void settings_begin();
void settings_get(Settings &out);
void settings_put(const Settings &s);   // saves to flash and tells everyone (settings_version bumps)
uint32_t settings_version();
void settings_reset();                  // erase everything back to config.h defaults

// Lock-free reads of the hot values (and previews that change them live without saving).
bool settings_enc_invert();
int settings_led_offset();
int settings_bright();
int settings_led_bright();
int settings_nap_min();
void settings_preview_led_offset(int v);
void settings_preview_led_bright(int v);
