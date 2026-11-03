// The 8-LED ring around the knob. Runs on its own task; the UI just sets a mode.
#pragma once
#include <Arduino.h>

enum RingMode : uint8_t {
  RING_OFF,
  RING_BREATHE,   // slow glow in ring_color, speed set by ring_period_ms
  RING_POSITION,  // one lit LED marking ring_value (0-1000) around the ring, e.g. menu position
  RING_LEVEL,     // ring filled clockwise to ring_value (0-1000), e.g. brightness
};

void leds_begin();
void leds_set(RingMode mode, uint32_t rgb, uint16_t value = 0, uint16_t period_ms = 4000);
void leds_flash(uint32_t rgb);   // two quick pulses over whatever is showing (action ok / failed)
