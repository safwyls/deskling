#include "input.h"
#include "board.h"
#include "settings.h"

static QueueHandle_t q;
static volatile bool btnDown = false;
static volatile uint32_t btnActivity = 0;
bool input_button_down() { return btnDown; }
uint32_t input_button_last_activity() { return btnActivity; }

static volatile uint32_t downCount = 0, downAt = 0;
void input_mark_down(uint32_t at) {
  if (downAt && at - downAt < 250) return;
  downAt = at ? at : 1;
  downCount = downCount + 1;
}
uint32_t input_down_count() { return downCount; }
uint32_t input_down_at() { return downAt; }

static void send(InputEvent e) { xQueueSend(q, &e, 0); }
void input_inject(InputEvent e) { send(e); }

// Polls every 2 ms. Rotation uses the same edge rule as Elecrow's example for this encoder
// (one event per rising edge of phase A, direction from phase B). The button is debounced; a release
// before 0.6 s is a press, holding to 0.6 s is a hold (and the release after a hold sends nothing).
static void input_task(void *) {
  int lastA = digitalRead(PIN_ENC_A);
  bool rawDown = false, down = false, holdSent = false;
  uint32_t rawSince = 0, pressAt = 0, quietUntil = 0;
  const uint32_t DEBOUNCE = 25, HOLD_MS = 600;

  for (;;) {
    uint32_t now = millis();

    // Pushing the knob in can nudge the encoder: ignore rotation while the button is down (raw, before
    // debounce) and for a moment after it's released, so a press never also counts as a turn.
    int a = digitalRead(PIN_ENC_A);
    bool btn = digitalRead(PIN_ENC_SW) == LOW;
    if (btn) quietUntil = now + 150;
    if (btn != btnDown) { btnDown = btn; btnActivity = now ? now : 1; }
    if (a != lastA && a == HIGH && (int32_t)(now - quietUntil) >= 0) {
      bool up = digitalRead(PIN_ENC_B) != a;
      if (settings_enc_invert()) up = !up;
      send(up ? IN_CW : IN_CCW);
    }
    lastA = a;

    bool r = btn;
    if (r != rawDown) { rawDown = r; rawSince = now; }
    if (rawDown != down && now - rawSince >= DEBOUNCE) {
      down = rawDown;
      if (down) { pressAt = now; holdSent = false; input_mark_down(rawSince); }   // when it first closed, not after debouncing
      else if (!holdSent) send(IN_PRESS);
    }
    if (down && !holdSent && now - pressAt >= HOLD_MS) { send(IN_HOLD); holdSent = true; }

    vTaskDelay(pdMS_TO_TICKS(2));
  }
}

void input_begin() {
  pinMode(PIN_ENC_A, INPUT);
  pinMode(PIN_ENC_B, INPUT);
  pinMode(PIN_ENC_SW, INPUT_PULLUP);
  q = xQueueCreate(32, sizeof(InputEvent));
  xTaskCreatePinnedToCore(input_task, "input", 3072, nullptr, 3, nullptr, 0);
}

bool input_poll(InputEvent &ev) { return xQueueReceive(q, &ev, 0) == pdTRUE; }
