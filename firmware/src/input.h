// Input: the knob and the touch screen, reduced to four events every screen understands.
//
//   rotate                      IN_CW / IN_CCW   move the highlight, change a value
//   press the knob              IN_PRESS         do the highlighted thing (sent on release)
//   tap the screen              IN_TAP           same as a press everywhere, except Wisp's bare home screen,
//                                                where it's a pat (so only a full knob press opens the menu)
//   hold the knob, hold screen  IN_HOLD          back one level (sent at 0.6 s, while still held)
//
// The knob is read on its own task; touch gestures are detected in the main loop and injected here.
#pragma once
#include <Arduino.h>

enum InputEvent : uint8_t {
  IN_CW,
  IN_CCW,
  IN_PRESS,
  IN_TAP,
  IN_HOLD,
};

void input_begin();
bool input_poll(InputEvent &ev);   // non-blocking; call from the LVGL loop
void input_inject(InputEvent ev);  // from the touch detector
// The knob is pressed by pushing the whole display in, so every knob press also touches the screen.
// The touch detector uses these to drop touches that were really a knob press.
bool input_button_down();                // raw, right now
uint32_t input_button_last_activity();   // millis() of the last time the button went down or came up
// The moment a finger or the knob went down, for things that need timing (the catch game). Presses and taps
// are only sent on release (to tell them from a hold, and a touch from a knob push), a few hundred ms later.
// One knob push also touches the screen: downs within 250 ms of each other count once.
void input_mark_down(uint32_t atMs);
uint32_t input_down_count();             // goes up by one per down
uint32_t input_down_at();                // millis() of the latest
