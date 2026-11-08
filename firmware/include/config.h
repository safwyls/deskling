// Deskling build-time defaults.
//
// Everything below can be changed on the device (hold the knob on the pet screen, or pick Settings at the
// end of the Home Assistant wheel) and is then kept in flash. These values are only used on a freshly
// erased board, or after Settings > Reset settings, so leaving them empty is fine.
#pragma once

#define FW_VERSION "5.21"

// Which pet this firmware is (set per build environment in platformio.ini)
#ifndef PET_ID
#define PET_ID "wisp"
#endif
#ifndef PET_NAME
#define PET_NAME "Wisp"
#endif

// ---- Wi-Fi ----
#define DEFAULT_WIFI_SSID ""
#define DEFAULT_WIFI_PASS ""

// ---- Pet ----
#define DEFAULT_NAP_MIN    10    // minutes without input before the pet naps and the screen dims; 0 = never
#define DEFAULT_BRIGHTNESS 70    // screen, percent
#define BACKLIGHT_ASLEEP   15    // screen while napping, percent
// Battery boards built on the IP5306 switch their 5 V off once the load stays under about 45 mA for about
// 32 s, and a napping dial (screen dimmed, ring off) draws less than that. While the screen is dimmed the
// dial keeps its Wi-Fi radio out of power save (the bulk of the load) and adds a burst of CPU load every few
// seconds, so the board stays on. Napping then draws about what the dial draws awake. 0 turns it off.
// 5.20 tried short bursts alone (0.7 s every 10 s): the board noticed some of them, not enough.
#define KEEPALIVE_EVERY_MS 5000
#define KEEPALIVE_PULSE_MS 1500

// ---- LED ring ----
#define DEFAULT_LED_BRIGHTNESS 60   // 0-255
// Rotation of the ring relative to the screen, in quarter-LED steps (one LED = 45 degrees).
// Negative turns it counter-clockwise. Calibrate in Settings > LED alignment.
#define DEFAULT_LED_OFFSET -2

// ---- Input ----
#define DEFAULT_ENCODER_INVERT 0    // 1 if clockwise moves the wrong way (also in Settings > Knob direction)

// ---- Care / reminders ----
// Minutes between reminders (0 = off). The timers only run during active hours.
#define DEFAULT_REMIND_WATER   45
#define DEFAULT_REMIND_EYES    20     // the 20-20-20 rule: every 20 min, look 20 ft away for 20 s
#define DEFAULT_REMIND_STRETCH 50
#define DEFAULT_REMIND_MOVE    60
// Sit/stand alternation (optional, off until turned on): minutes in each before a nudge to switch
#define DEFAULT_POSTURE_ON     0
#define DEFAULT_SIT_MIN        45
#define DEFAULT_STAND_MIN      15
// Active hours, local time (24 h). Outside them Wisp sleeps and nothing counts down. Same value = always.
#define DEFAULT_HOUR_START 8
#define DEFAULT_HOUR_END   20
// Time zone: index into the list in src/care.cpp (0 = US Pacific).
#define DEFAULT_TZ 0

// ---- Home screen ----
#define DEFAULT_CLOCK_MODE  1    // 0 off, 1 12-hour, 2 24-hour
#define DEFAULT_CLOCK_STYLE 0    // Classic, Big, Split, Analog, Rim, Words (src/clockface.cpp)
#define DEFAULT_SHOW_DATE   0
#define DEFAULT_SHOW_STATUS 1    // Wi-Fi / Home Assistant / paused icons beside the clock
#define DEFAULT_SHOW_NEXT   1    // "Eye break in 12 min" at the bottom
