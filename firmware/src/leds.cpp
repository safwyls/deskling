#include "leds.h"
#include "board.h"
#include "settings.h"
#include <Adafruit_NeoPixel.h>

static Adafruit_NeoPixel ring(RING_COUNT, PIN_RING, NEO_GRB + NEO_KHZ800);
static volatile RingMode mode = RING_OFF;
static volatile uint32_t color = 0x4cc8ff;
static volatile uint16_t value = 0, period = 4000;
static volatile uint32_t flashColor = 0, flashUntil = 0;

static uint32_t scale(uint32_t rgb, float k) {
  uint8_t r = ((rgb >> 16) & 0xff) * k, g = ((rgb >> 8) & 0xff) * k, b = (rgb & 0xff) * k;
  return ring.Color(r, g, b);
}

static void led_task(void *) {
  int bright = -1;
  for (;;) {
    uint32_t now = millis();
    if (settings_led_bright() != bright) { bright = settings_led_bright(); ring.setBrightness(bright); }
    // Positions are logical (0 = top of the screen, clockwise); the offset maps them onto the physical ring.
    float off = settings_led_offset() / 4.0f;
    float lvl[RING_COUNT] = {0};
    switch (mode) {
      case RING_OFF: break;
      case RING_BREATHE: {
        float t = (now % period) / (float)period;
        float k = 0.15f + 0.85f * (0.5f - 0.5f * cosf(t * 2 * PI));
        for (int i = 0; i < RING_COUNT; i++) lvl[i] = k;
        break;
      }
      case RING_POSITION: {
        float p = fmodf(value / 1000.0f * RING_COUNT + off + RING_COUNT * 4, RING_COUNT);
        for (int i = 0; i < RING_COUNT; i++) {
          float d = fabsf(i - p); d = fminf(d, RING_COUNT - d);
          lvl[i] = fmaxf(0.0f, 1.0f - d) * 0.9f + 0.05f;
        }
        break;
      }
      case RING_LEVEL: {
        float p = value / 1000.0f * RING_COUNT;
        for (int i = 0; i < RING_COUNT; i++) {
          float logical = fmodf(i - off + RING_COUNT * 4, RING_COUNT);
          lvl[i] = fminf(1.0f, fmaxf(0.04f, p - logical));
        }
        break;
      }
    }
    bool flashing = now < flashUntil;
    bool on = flashing && ((flashUntil - now) / 120) % 2 == 0;
    for (int i = 0; i < RING_COUNT; i++)
      ring.setPixelColor(i, on ? scale(flashColor, 1.0f) : scale(color, lvl[i]));
    ring.show();
    vTaskDelay(pdMS_TO_TICKS(30));
  }
}

void leds_begin() {
  pinMode(PIN_RING_PWR, OUTPUT);
  digitalWrite(PIN_RING_PWR, HIGH);
  ring.begin();
  ring.setBrightness(settings_led_bright());
  ring.clear();
  ring.show();
  xTaskCreatePinnedToCore(led_task, "leds", 3072, nullptr, 1, nullptr, 0);
}

void leds_set(RingMode m, uint32_t rgb, uint16_t v, uint16_t p) {
  color = rgb; value = v; period = p ? p : 4000; mode = m;
}

void leds_flash(uint32_t rgb) {
  flashColor = rgb;
  flashUntil = millis() + 480;
}
