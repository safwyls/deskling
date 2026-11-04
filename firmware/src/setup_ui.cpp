// Settings, all on the dial. Open it from the menu a press on the pet screen brings up.
//   turn    move            press   choose / type the highlighted character
//   hold    back (in lists) or done (when typing)
//
// Four views are reused by every page: a list, a character wheel for typing, a value dial and an
// info card (which can also show a QR code). Pages are just functions that open a view with callbacks.
#include "app.h"
#include "config.h"
#include "settings.h"
#include "net.h"
#include "leds.h"
#include "web.h"
#include "care.h"
#include "clockface.h"
#include "crashlog.h"
#include "api.h"
#include "ble.h"
#include <WiFi.h>

static const uint32_t C_TEXT = 0xe8eef8, C_ACCENT = 0x9fd8f7, C_SUB = 0x8a94a8, C_DIM = 0x4a5468,
                      C_OK = 0x5cf08a, C_BAD = 0xff6b6b, C_PILL = 0x1e2a42;
static const uint32_t IDLE_BACK_MS = 180000;
#define SEP "  " LV_SYMBOL_BULLET "  "

typedef void (*Fn)();
static lv_obj_t *scr;
static Settings S;                 // working copy; refreshed from flash on entry and after every save
static uint32_t lastInput = 0;
static Fn tickFn = nullptr;        // polled while a page is open (scan results, connection, loading...)
static bool noIdle = false;        // pages that wait on the user elsewhere (phone, connecting) don't time out

static void save() { settings_put(S); settings_get(S); }

// pages
static void main_open();
static void wifi_open();
static void wifi_list_open();
static void phone_open(Fn back);
static void restart_open();
static void app_open();
static void about_open();
static void remind_open();
static void poses_open();
static void hub_open();
static void home_open();
static void clockpick_open();
static void setup_exit();

// ============================================================ widgets
enum ViewKind { V_LIST, V_TEXT, V_VALUE, V_INFO, V_PREVIEW, V_CLOCK, V_COUNT };
static lv_obj_t *views[V_COUNT];
static ViewKind view = V_LIST;

static lv_timer_t *pTimer, *cTimer;
static void show(ViewKind v) {
  view = v;
  if (pTimer && v != V_PREVIEW) lv_timer_pause(pTimer);
  if (cTimer && v != V_CLOCK) lv_timer_pause(cTimer);
  for (int i = 0; i < V_COUNT; i++)
    i == v ? lv_obj_remove_flag(views[i], LV_OBJ_FLAG_HIDDEN) : lv_obj_add_flag(views[i], LV_OBJ_FLAG_HIDDEN);
  noIdle = false;
  tickFn = nullptr;
}

static lv_obj_t *mk_view() {
  lv_obj_t *v = lv_obj_create(scr);
  lv_obj_remove_style_all(v);
  lv_obj_set_size(v, 360, 360);
  lv_obj_remove_flag(v, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_remove_flag(v, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_add_flag(v, LV_OBJ_FLAG_HIDDEN);
  return v;
}

static lv_obj_t *mk_label(lv_obj_t *p, const lv_font_t *f, int y, int w, uint32_t col, bool wrap = false) {
  lv_obj_t *l = lv_label_create(p);
  lv_obj_set_style_text_font(l, f, 0);
  lv_obj_set_style_text_color(l, lv_color_hex(col), 0);
  lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_set_width(l, w);
  lv_label_set_long_mode(l, wrap ? LV_LABEL_LONG_WRAP : LV_LABEL_LONG_DOT);
  if (!wrap) lv_obj_set_height(l, lv_font_get_line_height(f));   // one line, "..." when too long
  lv_label_set_text(l, "");
  lv_obj_align(l, LV_ALIGN_CENTER, 0, y);
  return l;
}

static lv_obj_t *mk_arc(lv_obj_t *p, int size, int width) {
  lv_obj_t *a = lv_arc_create(p);
  lv_obj_set_size(a, size, size);
  lv_obj_center(a);
  lv_obj_remove_style(a, nullptr, LV_PART_KNOB);
  lv_obj_remove_flag(a, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_arc_width(a, width, LV_PART_MAIN);
  lv_obj_set_style_arc_width(a, width, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(a, lv_color_hex(C_PILL), LV_PART_MAIN);
  lv_obj_set_style_arc_color(a, lv_color_hex(C_ACCENT), LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(a, true, LV_PART_INDICATOR);
  return a;
}

static lv_obj_t *mk_pill(lv_obj_t *p, int w, int h, int y) {
  lv_obj_t *o = lv_obj_create(p);
  lv_obj_remove_style_all(o);
  lv_obj_set_size(o, w, h);
  lv_obj_set_style_radius(o, h / 2 > 20 ? 20 : h / 2, 0);
  lv_obj_set_style_bg_color(o, lv_color_hex(C_PILL), 0);
  lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
  lv_obj_align(o, LV_ALIGN_CENTER, 0, y);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_CLICKABLE);        // touches go to the view behind
  return o;
}

// ------------------------------------------------------------ list
struct Row { char text[80]; char detail[96]; uint32_t color; };
typedef int (*CountFn)();
typedef void (*RowFn)(int, Row &);
typedef void (*TapFn)(int);
static struct { char title[48]; CountFn count; RowFn row; TapFn tap; Fn back; } L;
static int lsel = 0;
static lv_obj_t *lTitle, *lRows[5], *lDetail, *lArc;
static uint32_t lMovedAt = 0;          // last change of selection: names start scrolling after a pause
static bool lMarquee = false;
static const int ROW_Y[5] = { -100, -52, 0, 52, 100 };
static const int ROW_W[5] = { 190, 250, 290, 250, 190 };

static void list_marquee(bool on) {
  lMarquee = on;
  lv_label_long_mode_t m = on ? LV_LABEL_LONG_SCROLL_CIRCULAR : LV_LABEL_LONG_DOT;
  lv_label_set_long_mode(lRows[2], m);
  lv_label_set_long_mode(lDetail, m);
}

static void list_render() {
  int n = L.count();
  static int lastSel = -1;
  if (lsel != lastSel) { lastSel = lsel; lMovedAt = millis(); if (lMarquee) list_marquee(false); }
  if (lsel >= n) lsel = n - 1;
  if (lsel < 0) lsel = 0;
  lv_label_set_text(lTitle, L.title);
  lv_label_set_text(lDetail, "");
  static Row r;
  for (int k = 0; k < 5; k++) {
    int i = lsel + k - 2;
    if (i < 0 || i >= n) { lv_label_set_text(lRows[k], ""); continue; }
    L.row(i, r);
    lv_label_set_text(lRows[k], r.text);
    uint32_t col = k == 2 ? r.color : (k == 1 || k == 3) ? C_SUB : C_DIM;
    lv_obj_set_style_text_color(lRows[k], lv_color_hex(col), 0);
    if (k == 2) lv_label_set_text(lDetail, r.detail);
  }
  if (n > 0) {
    int seg = n >= 60 ? 6 : 360 / n, start = 270 + lsel * 360 / n - seg / 2;
    lv_arc_set_angles(lArc, start % 360, (start + (seg > 8 ? seg - 4 : seg)) % 360);
  }
  leds_set(RING_POSITION, C_ACCENT, n > 1 ? (uint16_t)(lsel * 1000 / n) : 0);
}

static void list_tick() {
  if (!lMarquee && millis() - lMovedAt > 900) list_marquee(true);   // long names scroll after a pause
}

static void list_open(const char *title, CountFn count, RowFn row, TapFn tap, Fn back, int sel) {
  strlcpy(L.title, title, sizeof(L.title));
  L.count = count; L.row = row; L.tap = tap; L.back = back;
  lsel = sel;
  show(V_LIST);
  list_render();
}

// Fast turns through a long list take bigger steps.
static int knob_step(int n) {
  static uint32_t last = 0;
  static int streak = 0;
  uint32_t now = millis();
  streak = now - last < 80 ? streak + 1 : 0;
  last = now;
  if (n < 40) return 1;
  return streak > 8 ? 8 : streak > 4 ? 3 : 1;
}

static void list_move(int d) {
  int n = L.count();
  if (!n) return;
  if (n < 40) lsel = ((lsel + d) % n + n) % n;               // short lists wrap around
  else lsel = constrain(lsel + d, 0, n - 1);                  // long ones stop at the ends
  list_render();
}

static void list_input(InputEvent e) {
  int n = L.count();
  if (e == IN_CW && n) list_move(knob_step(n));
  else if (e == IN_CCW && n) list_move(-knob_step(n));
  else if (e == IN_PRESS && n && L.tap) L.tap(lsel);
  else if (e == IN_HOLD && L.back) L.back();
}

// ------------------------------------------------------------ character wheel
// The wheel holds a few actions followed by the characters of the current set. It wraps, so the actions
// sit just counter-clockwise of 'a': one click back is delete, two is done, three switches the set.
static const char *const CHARSET[4] = { "abcdefghijklmnopqrstuvwxyz ", "ABCDEFGHIJKLMNOPQRSTUVWXYZ ",
                                        "0123456789.:/-_@", "!?#$%&*+=,;'\"()[]{}<>^~|\\`" };
static const char *const CHARSET_NAME[4] = { "abc", "ABC", "123", "#+=" };
enum { T_CANCEL, T_PAGE, T_DONE, T_DEL, T_ACTIONS };
static struct { char title[64]; char buf[300]; char initial[300]; size_t max; Fn done; Fn cancel; } T;
static int tpage = 0, tidx = T_ACTIONS;
static lv_obj_t *tTitle, *tValue, *tItems[7], *tHint, *tPage;
static const int TXPOS[7] = { -148, -106, -60, 0, 60, 106, 148 };
static const lv_font_t *TF[7];

static int t_total() { return T_ACTIONS + (int)strlen(CHARSET[tpage]); }

static uint32_t t_item(int i, char *out, size_t n, bool &word) {
  int tot = t_total();
  i = ((i % tot) + tot) % tot;
  word = false;
  switch (i) {
    case T_CANCEL: snprintf(out, n, "%s", LV_SYMBOL_CLOSE); return C_BAD;
    case T_PAGE:   snprintf(out, n, "%s", CHARSET_NAME[(tpage + 1) % 4]); word = true; return C_ACCENT;
    case T_DONE:   snprintf(out, n, "%s", LV_SYMBOL_OK); return C_OK;
    case T_DEL:    snprintf(out, n, "%s", LV_SYMBOL_BACKSPACE); return C_ACCENT;
  }
  char c = CHARSET[tpage][i - T_ACTIONS];
  if (c == ' ') { snprintf(out, n, "space"); word = true; return C_TEXT; }
  out[0] = c; out[1] = 0;
  return C_TEXT;
}

static void text_render() {
  char buf[16];
  bool word;
  for (int k = 0; k < 7; k++) {
    uint32_t col = t_item(tidx + k - 3, buf, sizeof(buf), word);
    lv_label_set_text(tItems[k], buf);
    lv_obj_set_style_text_font(tItems[k], word ? (k == 3 ? &lv_font_montserrat_28 : &lv_font_montserrat_14) : TF[k], 0);
    lv_obj_set_style_text_color(tItems[k], lv_color_hex(k == 3 ? col : (k == 2 || k == 4) ? C_SUB : C_DIM), 0);
  }
  // show the end of long text, with a cursor
  size_t len = strlen(T.buf);
  char shown[24];
  if (len > 16) snprintf(shown, sizeof(shown), "..%s_", T.buf + len - 14);
  else snprintf(shown, sizeof(shown), "%s_", T.buf);
  lv_label_set_text(tValue, shown);
  char pg[24];
  snprintf(pg, sizeof(pg), len > 16 ? "%s  (%u)" : "%s", CHARSET_NAME[tpage], (unsigned)len);
  lv_label_set_text(tPage, pg);
  leds_set(RING_POSITION, C_ACCENT, (uint16_t)(tidx * 1000 / t_total()));
}

static void text_open(const char *title, const char *initial, size_t max, Fn done, Fn cancel, int page = 0) {
  strlcpy(T.title, title, sizeof(T.title));
  T.max = max < sizeof(T.buf) ? max : sizeof(T.buf);
  strlcpy(T.buf, initial ? initial : "", T.max);
  strlcpy(T.initial, T.buf, sizeof(T.initial));
  T.done = done; T.cancel = cancel;
  tpage = page; tidx = T_ACTIONS;
  lv_label_set_text(tTitle, T.title);
  show(V_TEXT);
  text_render();
}

static void choice_open(const char *title, const char *detail, const char *l1, Fn f1, const char *l2, Fn f2, Fn back = nullptr);
static void text_resume() { show(V_TEXT); text_render(); }
static void text_discard() { if (T.cancel) T.cancel(); }

static void text_input(InputEvent e) {
  int tot = t_total();
  if (e == IN_CW) { tidx = (tidx + 1) % tot; text_render(); return; }
  if (e == IN_CCW) { tidx = (tidx - 1 + tot) % tot; text_render(); return; }
  if (e == IN_HOLD) {                               // back: leave, but don't lose typing by accident
    // a second hold confirms: one accidental hold can't lose what was typed, and holds alone still get out
    if (strcmp(T.buf, T.initial)) choice_open("Hold again to discard", "the " LV_SYMBOL_OK " on the wheel saves instead",
                                              LV_SYMBOL_EDIT "  Keep typing", text_resume, LV_SYMBOL_TRASH "  Discard", text_discard,
                                              text_discard);
    else text_discard();
    return;
  }
  if (e != IN_PRESS) return;
  size_t len = strlen(T.buf);
  switch (tidx) {
    case T_CANCEL: if (T.cancel) T.cancel(); return;
    case T_PAGE: tpage = (tpage + 1) % 4; break;           // stays on this item, so pressing again cycles sets
    case T_DONE: if (T.done) T.done(); return;
    case T_DEL: if (len) T.buf[len - 1] = 0; break;
    default:
      if (len + 1 < T.max) { T.buf[len] = CHARSET[tpage][tidx - T_ACTIONS]; T.buf[len + 1] = 0; }
      else leds_flash(C_BAD);
  }
  text_render();
}

// ------------------------------------------------------------ value dial
typedef void (*FmtFn)(int v, char *main, size_t mn, char *sub, size_t sn);
typedef void (*IntFn)(int);
static struct { int min, max, step, v, orig; FmtFn fmt; IntFn live; IntFn save; Fn back; } V;
static lv_obj_t *vTitle, *vVal, *vSub, *vArc, *vHint, *vMarker;

static void value_render() {
  char m[32], sub[96];
  V.fmt(V.v, m, sizeof(m), sub, sizeof(sub));
  lv_label_set_text(vVal, m);
  lv_label_set_text(vSub, sub);
  lv_arc_set_value(vArc, V.v);
  int span = V.max - V.min;
  leds_set(RING_LEVEL, C_ACCENT, span ? (uint16_t)((V.v - V.min) * 1000 / span) : 0);
  if (V.live) V.live(V.v);       // may override the ring (LED pages preview on it)
}

static void value_open(const char *title, int min, int max, int step, int v, FmtFn fmt, IntFn live, IntFn save, bool centered, Fn back) {
  V.min = min; V.max = max; V.step = step; V.v = constrain(v, min, max); V.orig = V.v;
  V.fmt = fmt; V.live = live; V.save = save; V.back = back;
  lv_label_set_text(vTitle, title);
  lv_arc_set_mode(vArc, centered ? LV_ARC_MODE_SYMMETRICAL : LV_ARC_MODE_NORMAL);
  lv_arc_set_range(vArc, min, max);
  centered ? lv_obj_remove_flag(vMarker, LV_OBJ_FLAG_HIDDEN) : lv_obj_add_flag(vMarker, LV_OBJ_FLAG_HIDDEN);
  show(V_VALUE);
  value_render();
}

static void value_input(InputEvent e) {
  if (e == IN_CW) { V.v = min(V.max, V.v + V.step); value_render(); }
  else if (e == IN_CCW) { V.v = max(V.min, V.v - V.step); value_render(); }
  else if (e == IN_PRESS && V.save) V.save(V.v);
  else if (e == IN_HOLD) { if (V.live) V.live(V.orig); if (V.back) V.back(); }   // cancel: put it back as it was
}

// ------------------------------------------------------------ info card
static lv_obj_t *iTitle, *iIcon, *iMain, *iSub, *iHint, *iSpin, *iQrBox, *iQr;
static Fn iTap, iBack;

static void info_open(const char *title, const char *icon, uint32_t iconCol, const char *main, const char *sub,
                      const char *hint, bool spin, Fn tap, Fn back) {
  show(V_INFO);
  iTap = tap; iBack = back;
  lv_label_set_text(iTitle, title);
  lv_label_set_text(iIcon, icon ? icon : "");
  lv_obj_set_style_text_color(iIcon, lv_color_hex(iconCol), 0);
  lv_label_set_text(iMain, main ? main : "");
  lv_label_set_text(iSub, sub ? sub : "");
  lv_label_set_text(iHint, hint ? hint : "");
  bool multi = sub && strchr(sub, '\n');
  lv_obj_set_style_text_font(iMain, &lv_font_montserrat_28, 0);
  lv_obj_set_style_text_font(iSub, multi ? &lv_font_montserrat_14 : &lv_font_montserrat_20, 0);
  lv_obj_align(iTitle, LV_ALIGN_CENTER, 0, -128);
  lv_obj_align(iMain, LV_ALIGN_CENTER, 0, 6);
  lv_obj_align(iSub, LV_ALIGN_CENTER, 0, multi ? 64 : 50);
  spin ? lv_obj_remove_flag(iSpin, LV_OBJ_FLAG_HIDDEN) : lv_obj_add_flag(iSpin, LV_OBJ_FLAG_HIDDEN);
  lv_obj_remove_flag(iIcon, LV_OBJ_FLAG_HIDDEN);
  lv_obj_add_flag(iQrBox, LV_OBJ_FLAG_HIDDEN);
  leds_set(spin ? RING_BREATHE : RING_OFF, C_ACCENT, 0, 1400);
}

static void info_qr(const char *data, int size = 140) {
  if (lv_obj_get_width(iQrBox) != size + 24) {
    lv_obj_set_size(iQrBox, size + 24, size + 24);
    lv_qrcode_set_size(iQr, size);
    lv_obj_center(iQr);
  }
  lv_obj_align(iQrBox, LV_ALIGN_CENTER, 0, size > 140 ? -14 : -32);
  lv_qrcode_update(iQr, data, strlen(data));
  lv_obj_remove_flag(iQrBox, LV_OBJ_FLAG_HIDDEN);
  lv_obj_add_flag(iIcon, LV_OBJ_FLAG_HIDDEN);
  lv_obj_add_flag(iSpin, LV_OBJ_FLAG_HIDDEN);
  lv_obj_set_style_text_font(iMain, &lv_font_montserrat_20, 0);
  lv_obj_set_style_text_font(iSub, &lv_font_montserrat_14, 0);
  lv_obj_align(iTitle, LV_ALIGN_CENTER, 0, -142);
  lv_obj_align(iMain, LV_ALIGN_CENTER, 0, 74);
  lv_obj_align(iSub, LV_ALIGN_CENTER, 0, 101);
}

static void info_input(InputEvent e) {
  if (e == IN_PRESS && iTap) iTap();
  else if (e == IN_HOLD && iBack) iBack();
}

// ------------------------------------------------------------ choice: a short list of options
// Used instead of cards that needed a hold for their second action.
struct Choice { const char *label; Fn fn; };
static Choice C[3];
static int cN = 0;
static Fn cBack = nullptr;
static char cDetail[96];
static int choice_count() { return cN; }
static void choice_row(int i, Row &r) {
  snprintf(r.text, sizeof(r.text), "%s", C[i].label);
  snprintf(r.detail, sizeof(r.detail), "%s", cDetail);
  r.color = C_TEXT;
}
static void choice_tap(int i) { if (C[i].fn) C[i].fn(); }
static void choice_back() { Fn f = cBack ? cBack : C[cN - 1].fn; if (f) f(); }   // hold: the given way out, else the last option
static void choice_open(const char *title, const char *detail, const char *l1, Fn f1, const char *l2, Fn f2, Fn back) {
  C[0] = { l1, f1 }; C[1] = { l2, f2 }; cN = 2; cBack = back;
  snprintf(cDetail, sizeof(cDetail), "%s", detail);
  list_open(title, choice_count, choice_row, choice_tap, choice_back, 0);
  if (!back) leds_flash(C_BAD);       // failures flash red; a question doesn't
}

// ============================================================ helpers
static bool wifi_up() { return WiFi.status() == WL_CONNECTED; }

// ============================================================ main
enum { M_PHONE, M_APP, M_WIFI, M_REMIND, M_POSES, M_HOME, M_LED, M_KNOB, M_BRIGHT, M_LEDBR, M_NAP, M_ABOUT, M_RESTART, M_RESET, M_BACK, M_COUNT };
static int mainSel = 0;
static bool resetArmed = false;

static int main_count() { return M_COUNT; }

static void main_row(int i, Row &r) {
  r.color = C_TEXT; r.detail[0] = 0;
  switch (i) {
    case M_PHONE:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_UPLOAD "  Set up from phone");
      snprintf(r.detail, sizeof(r.detail), "%s", wifi_up() ? "everything, on a web page" : "Wi-Fi and everything else");
      break;
    case M_APP:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_CALL "  Phone app");
      if (api_paired_count()) snprintf(r.detail, sizeof(r.detail), "%d paired" SEP "%s", api_paired_count(), ble_status());
      else snprintf(r.detail, sizeof(r.detail), "pair the Deskling app");
      break;
    case M_WIFI:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_WIFI "  Wi-Fi");
      if (!S.ssid[0]) snprintf(r.detail, sizeof(r.detail), "not set up");
      else snprintf(r.detail, sizeof(r.detail), "%s" SEP "%s", S.ssid, wifi_up() ? "connected" : "not connected");
      break;
    case M_REMIND: {
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_BELL "  Reminders");
      int on = 0; for (int k = 0; k < 4; k++) on += S.remind_min[k] > 0;
      snprintf(r.detail, sizeof(r.detail), "%d on" SEP "%d:00 to %d:00", on, S.hour_start, S.hour_end);
      break;
    }
    case M_POSES:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_IMAGE "  Poses");
      snprintf(r.detail, sizeof(r.detail), "see every mood and animation");
      break;
    case M_HOME:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_LIST "  Home screen");
      snprintf(r.detail, sizeof(r.detail), "clock, date, status icons");
      break;
    case M_LED:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_LOOP "  LED alignment");
      snprintf(r.detail, sizeof(r.detail), "%+.0f\xC2\xB0", S.led_offset * 11.25f);
      break;
    case M_KNOB:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_SHUFFLE "  Knob direction");
      snprintf(r.detail, sizeof(r.detail), "%s" SEP "press to flip", S.enc_invert ? "reversed" : "normal");
      break;
    case M_BRIGHT:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_EYE_OPEN "  Screen brightness");
      snprintf(r.detail, sizeof(r.detail), "%d%%", S.bright);
      break;
    case M_LEDBR:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_CHARGE "  LED brightness");
      snprintf(r.detail, sizeof(r.detail), "%d%%", (S.led_bright * 100 + 127) / 255);
      break;
    case M_NAP:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_EYE_CLOSE "  Nap timer");
      if (S.nap_min) snprintf(r.detail, sizeof(r.detail), "after %d min", S.nap_min);
      else snprintf(r.detail, sizeof(r.detail), "never");
      break;
    case M_ABOUT:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_SETTINGS "  About");
      snprintf(r.detail, sizeof(r.detail), "%s", wifi_up() ? WiFi.localIP().toString().c_str() : "offline");
      break;
    case M_RESTART:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_REFRESH "  Last restart");
      snprintf(r.detail, sizeof(r.detail), "%s%s", crashlog_reason(), crashlog_has_dump() ? SEP "crash report saved" : "");
      if (crashlog_bad() || crashlog_has_dump()) r.color = C_BAD;
      break;
    case M_RESET:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_TRASH "  Reset settings");
      if (resetArmed) { snprintf(r.detail, sizeof(r.detail), "press again to erase"); r.color = C_BAD; }
      else snprintf(r.detail, sizeof(r.detail), "back to defaults");
      break;
    case M_BACK:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_LEFT "  Back");
      snprintf(r.detail, sizeof(r.detail), "to the menu");
      break;
  }
}

// value pages
static void fmt_led(int v, char *m, size_t mn, char *s, size_t sn) {
  snprintf(m, mn, "%+.0f\xC2\xB0", v * 11.25f);
  snprintf(s, sn, "Turn until the lit LED sits by the arrow");
}
static void live_led(int v) { settings_preview_led_offset(v); leds_set(RING_POSITION, 0xffffff, 0); }
static void save_led(int v) { S.led_offset = v; save(); main_open(); }

static void fmt_pct(int v, char *m, size_t mn, char *s, size_t sn) { snprintf(m, mn, "%d%%", v); s[0] = 0; }
static void live_bright(int v) { backlight(v); }
static void save_bright(int v) { S.bright = v; save(); main_open(); }

static void fmt_ledbr(int v, char *m, size_t mn, char *s, size_t sn) { snprintf(m, mn, "%d%%", (v * 100 + 127) / 255); s[0] = 0; }
static void live_ledbr(int v) { settings_preview_led_bright(v); }
static void save_ledbr(int v) { S.led_bright = v; save(); main_open(); }

static void fmt_nap(int v, char *m, size_t mn, char *s, size_t sn) {
  if (v) snprintf(m, mn, "%d min", v); else snprintf(m, mn, "Never");
  snprintf(s, sn, PET_NAME " naps and the screen dims after this long without input");
}
static void save_nap(int v) { S.nap_min = v; save(); main_open(); }

static void main_tap(int i) {
  mainSel = i;
  if (i != M_RESET) resetArmed = false;
  switch (i) {
    case M_PHONE: phone_open(main_open); break;
    case M_APP: app_open(); break;
    case M_WIFI: wifi_open(); break;
    case M_REMIND: remind_open(); break;
    case M_POSES: poses_open(); break;
    case M_HOME: home_open(); break;
    case M_LED: value_open("LED alignment", -16, 16, 1, S.led_offset, fmt_led, live_led, save_led, true, main_open); break;
    case M_KNOB: S.enc_invert = !S.enc_invert; save(); leds_flash(C_OK); list_render(); break;
    case M_BRIGHT: value_open("Screen brightness", 10, 100, 5, S.bright, fmt_pct, live_bright, save_bright, false, main_open); break;
    case M_LEDBR: value_open("LED brightness", 5, 255, 10, S.led_bright, fmt_ledbr, live_ledbr, save_ledbr, false, main_open); break;
    case M_NAP: value_open("Nap timer", 0, 60, 1, S.nap_min, fmt_nap, nullptr, save_nap, false, main_open); break;
    case M_ABOUT: about_open(); break;
    case M_RESTART: restart_open(); break;
    case M_RESET:
      if (!resetArmed) { resetArmed = true; list_render(); break; }
      resetArmed = false;
      settings_reset();
      settings_get(S);
      backlight(S.bright);
      leds_flash(C_OK);
      info_open("Reset settings", LV_SYMBOL_OK, C_OK, "Erased", "Everything is back to defaults", "press to continue",
                false, main_open, main_open);
      break;
    case M_BACK: hub_open(); break;
  }
}

static void main_tick() {
  static uint32_t last = 0;
  if (millis() - last > 1000) { last = millis(); list_render(); }   // keep the status details current
}

static void main_open() {
  list_open("Settings", main_count, main_row, main_tap, hub_open, mainSel);
  tickFn = main_tick;
}

// ============================================================ phone app
// Pairing: a 6-digit code the app asks for. The app finds the dial over Bluetooth, or on your network.
enum { AP_PAIR, AP_FORGET, AP_BACK, AP_COUNT };
static int appSel = 0;
static bool forgetArmed = false;

static void pair_close() { api_pair_open(false); app_open(); }
static void pair_tick() {
  if (api_last_paired()[0]) {
    char sub[48];
    snprintf(sub, sizeof(sub), "with %s", api_last_paired());
    leds_flash(C_OK);
    info_open("Phone app", LV_SYMBOL_OK, C_OK, "Paired", sub, "press to continue", false, app_open, app_open);
    api_pair_open(false);
    return;
  }
  if (!api_pair_is_open()) { app_open(); return; }        // timed out
}
static void pair_open() {
  api_pair_open(true);
  char code[12];
  snprintf(code, sizeof(code), "%.3s %.3s", api_pair_code(), api_pair_code() + 3);
  char sub[48];
  snprintf(sub, sizeof(sub), "in the app, pick %s", api_dial_name());
  info_open("Pair a phone", LV_SYMBOL_CALL, C_ACCENT, code, sub, "press to cancel", false, pair_close, pair_close);
  lv_obj_set_style_text_font(iMain, &lv_font_montserrat_28, 0);
  tickFn = pair_tick;
  noIdle = true;
}

static int app_count() { return AP_COUNT; }
static void app_row(int i, Row &r) {
  r.color = C_TEXT; r.detail[0] = 0;
  switch (i) {
    case AP_PAIR:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_PLUS "  Pair a phone");
      snprintf(r.detail, sizeof(r.detail), "Bluetooth %s" SEP "%s", ble_status(), api_dial_name());
      break;
    case AP_FORGET:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_TRASH "  Forget phones");
      if (forgetArmed) { snprintf(r.detail, sizeof(r.detail), "press again to unpair all"); r.color = C_BAD; }
      else snprintf(r.detail, sizeof(r.detail), "%d paired", api_paired_count());
      break;
    case AP_BACK:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_LEFT "  Back");
      break;
  }
}
static void app_tap(int i) {
  appSel = i;
  if (i != AP_FORGET) forgetArmed = false;
  switch (i) {
    case AP_PAIR: pair_open(); break;
    case AP_FORGET:
      if (!forgetArmed) { forgetArmed = true; list_render(); break; }
      forgetArmed = false; api_forget_all(); leds_flash(C_OK); list_render(); break;
    case AP_BACK: main_open(); break;
  }
}
static void app_open() {
  list_open("Phone app", app_count, app_row, app_tap, main_open, appSel);
  tickFn = main_tick;
}

// ============================================================ last restart
static void restart_open() {
  if (crashlog_has_dump()) {
    char main[48];
    snprintf(main, sizeof(main), "Last reset: %s", crashlog_reason());
    info_open("Crash report", nullptr, 0, main, "", "", false, main_open, main_open);
    info_qr(crashlog_qr(), 196);            // big: the report is a dense code
    lv_obj_align(iMain, LV_ALIGN_CENTER, 0, 112);
  } else {
    info_open("Last restart", crashlog_bad() ? LV_SYMBOL_WARNING : LV_SYMBOL_OK, crashlog_bad() ? C_BAD : C_OK,
              crashlog_reason(), crashlog_summary()[0] ? crashlog_summary() : "nothing went wrong", "press to go back", false, main_open, main_open);
  }
}

// ============================================================ about
static void about_fill() {
  char main[24], sub[280], wifi[64];
  snprintf(main, sizeof(main), "%s", wifi_up() ? WiFi.localIP().toString().c_str() : "offline");
  if (!S.ssid[0]) snprintf(wifi, sizeof(wifi), "Wi-Fi not set up");
  else if (wifi_up()) snprintf(wifi, sizeof(wifi), "%s" SEP "%d dBm", S.ssid, WiFi.RSSI());
  else snprintf(wifi, sizeof(wifi), "%s" SEP "not connected", S.ssid);
  uint32_t fr, mn, lg;
  crashlog_heap(fr, mn, lg);
  snprintf(sub, sizeof(sub), "%s\nBluetooth %s\nMAC %s\nRAM %lu KB free (low %lu, block %lu)\nPSRAM %.1f MB free",
           wifi, ble_detail(), WiFi.macAddress().c_str(), (unsigned long)(fr / 1024), (unsigned long)(mn / 1024),
           (unsigned long)(lg / 1024), ESP.getFreePsram() / 1048576.0f);
  lv_label_set_text(iMain, main);
  lv_label_set_text(iSub, sub);
}
static void about_tick() {
  static uint32_t last = 0;
  if (millis() - last > 2000) { last = millis(); about_fill(); }
}
static void about_open() {
  info_open("About", nullptr, 0, "", "x\n", "press to go back", false, main_open, main_open);
  lv_obj_align(iMain, LV_ALIGN_CENTER, 0, -52);
  lv_obj_align(iSub, LV_ALIGN_CENTER, 0, 22);
  about_fill();
  tickFn = about_tick;
}

// ============================================================ Wi-Fi
static char pickSsid[33];
static bool pickOpen = false, tried = false;
static char connPass[65];
static uint32_t connAt = 0;

static void wifi_restore() {             // leaving the Wi-Fi pages after trying a network: back to the saved one
  if (tried) { tried = false; net_use_saved(); }
}
static void wifi_leave() { wifi_restore(); main_open(); }
static void wifi_leave_to_list() { wifi_restore(); wifi_list_open(); }

static void connect_start(const char *pass);
static void pass_done() { connect_start(T.buf); }
static void ask_password() {
  char title[64];
  snprintf(title, sizeof(title), "Password for %s", pickSsid);
  const char *initial = tried ? connPass : !strcmp(pickSsid, S.ssid) ? S.pass : "";
  text_open(title, initial, sizeof(S.pass), pass_done, wifi_leave_to_list);
}

static void connect_cancel() { wifi_leave(); }
static void connect_retry() { if (pickOpen) connect_start(""); else ask_password(); }

// What the Wi-Fi driver reported about an attempt that has run for el ms, or false while it still might work.
bool setup_wifi_fail_text(uint32_t el, char *why, size_t n) {
  wl_status_t wl = WiFi.status();
  WifiDiag d = net_diag();
  why[0] = 0;
  if (d.authFails >= 2 || (el > 4000 && wl == WL_CONNECT_FAILED))
    snprintf(why, n, "The router refused the password (%s)", net_reason_name(d.lastReason));
  else if (d.expired >= 2)
    snprintf(why, n, "Router isn't answering (AUTH_EXPIRE). Restart it, or check its blocked devices");
  else if (el > 9000 && (wl == WL_NO_SSID_AVAIL || d.lastReason == WIFI_REASON_NO_AP_FOUND))
    snprintf(why, n, "Can't see that network from here");
  else if (el > 6000 && (d.lastReason == 211 || d.lastReason == 212))
    snprintf(why, n, "Network found but not usable (%s)", net_reason_name(d.lastReason));
  else if (el > 15000 && d.associated && !d.gotIp)
    snprintf(why, n, "Joined, but the router gave no IP address (DHCP)");
  else if (el > 20000)
    snprintf(why, n, d.lastReason ? "No connection (%s)" : "No answer from the network",
             d.lastReason ? net_reason_name(d.lastReason) : "");
  return why[0] != 0;
}

static void connect_tick() {
  if (net_try_pending()) { connAt = millis(); return; }   // count from when the attempt really starts
  uint32_t el = millis() - connAt;
  wl_status_t wl = WiFi.status();
  if (el > 1500 && wl == WL_CONNECTED && WiFi.SSID() == pickSsid) {
    strlcpy(S.ssid, pickSsid, sizeof(S.ssid));
    strlcpy(S.pass, connPass, sizeof(S.pass));
    save();
    tried = false;
    leds_flash(C_OK);
    String ip = WiFi.localIP().toString();
    info_open("Wi-Fi", LV_SYMBOL_OK, C_OK, "Connected", ip.c_str(), "press to continue", false, main_open, main_open);
    return;
  }
  static char why[128];
  if (!setup_wifi_fail_text(el, why, sizeof(why))) return;
  const char *whyp = why;
  choice_open("Couldn't connect", whyp, LV_SYMBOL_REFRESH "  Try again", connect_retry, LV_SYMBOL_LEFT "  Back", connect_cancel);
}

static void connect_start(const char *pass) {
  strlcpy(connPass, pass, sizeof(connPass));
  tried = true;
  net_try(pickSsid, connPass);
  connAt = millis();
  info_open("Wi-Fi", nullptr, 0, "Connecting", pickSsid, "press to cancel", true, connect_cancel, connect_cancel);
  tickFn = connect_tick;
  noIdle = true;
}

static void hidden_done() {
  if (!T.buf[0]) { wifi_list_open(); return; }
  strlcpy(pickSsid, T.buf, sizeof(pickSsid));
  pickOpen = false;
  ask_password();         // leave it empty for an open network
}

static int wifi_count() { return net_scan_count() + 3; }

static const char *strength(int rssi) {
  return rssi > -55 ? "strong" : rssi > -67 ? "good" : rssi > -78 ? "weak" : "very weak";
}

static void wifi_row(int i, Row &r) {
  int n = net_scan_count();
  r.color = C_TEXT;
  if (i == n) {
    snprintf(r.text, sizeof(r.text), LV_SYMBOL_EDIT "  Hidden network");
    snprintf(r.detail, sizeof(r.detail), "type its name");
  } else if (i == n + 1) {
    snprintf(r.text, sizeof(r.text), LV_SYMBOL_REFRESH "  Scan again");
    snprintf(r.detail, sizeof(r.detail), "%s", net_scan_state() == SCAN_FAILED ? "the last scan failed" : "");
  } else if (i == n + 2) {
    snprintf(r.text, sizeof(r.text), LV_SYMBOL_LEFT "  Back");
    snprintf(r.detail, sizeof(r.detail), "to Settings");
  } else {
    const WifiNet &w = net_scan_net(i);
    bool current = wifi_up() && WiFi.SSID() == w.ssid;
    snprintf(r.text, sizeof(r.text), "%s", w.ssid);
    if (current) r.color = C_OK;
    snprintf(r.detail, sizeof(r.detail), "%s" SEP "%s%s", strength(w.rssi), w.open ? "open" : "secured",
             current ? SEP "connected" : !strcmp(w.ssid, S.ssid) ? SEP "saved" : "");
  }
}

static void wifi_tap(int i) {
  int n = net_scan_count();
  if (i == n) { text_open("Network name", "", sizeof(pickSsid), hidden_done, wifi_list_open); return; }
  if (i == n + 1) { wifi_open(); return; }
  if (i == n + 2) { wifi_leave(); return; }
  const WifiNet &w = net_scan_net(i);
  strlcpy(pickSsid, w.ssid, sizeof(pickSsid));
  pickOpen = w.open;
  tried = false;
  if (pickOpen) connect_start("");
  else ask_password();
}

static void wifi_list_open() {
  list_open(net_scan_state() == SCAN_FAILED && !net_scan_count() ? "Wi-Fi  (scan failed)" : "Wi-Fi",
            wifi_count, wifi_row, wifi_tap, wifi_leave, 0);
}

static void scan_tick() {
  ScanState st = net_scan_state();
  if (st == SCAN_DONE || st == SCAN_FAILED) wifi_list_open();
}

static void wifi_open() {
  net_scan();
  info_open("Wi-Fi", nullptr, 0, "Scanning", "", "press to go back", true, main_open, main_open);
  tickFn = scan_tick;
}

// ------------------------------------------------------------ phone page
// The page has Wi-Fi and the dial's settings. On Wi-Fi it's served over your network;
// without Wi-Fi the dial runs its own network: scan the first QR code to join it and the phone opens
// the page by itself (the second QR code is there in case it doesn't).
enum { PH_LAN, PH_SCAN, PH_APWAIT, PH_JOIN, PH_JOINED };
static int phStage = PH_LAN;
static Fn phBack = nullptr;
static uint32_t phAt = 0;
static char apSsid[33], apPass[16];
static bool phTrying = false, phTried = false;
static uint32_t phTryAt = 0;
static char phSsid[33], phPass[65];
static char phSub[96];

static void phone_close() {
  web_stop();
  net_hold(false);
  // back to the saved network, unless already on it (a successful join from the page saved it)
  if (phTried || phStage != PH_LAN)
    if (!(wifi_up() && WiFi.SSID() == S.ssid)) net_use_saved();
  phTrying = phTried = false;
  noIdle = false;
  phBack ? phBack() : main_open();
}

static void phone_tick();
static void phone_keep() { tickFn = phone_tick; noIdle = true; }   // info_open() resets both

static void phone_sub(const char *fmt, const char *a = "") {
  snprintf(phSub, sizeof(phSub), fmt, a);
  lv_label_set_text(iSub, phSub);
}

static void phone_show_join() {
  phStage = PH_JOIN;
  char qr[96], sub[40];
  snprintf(qr, sizeof(qr), "WIFI:T:WPA;S:%s;P:%s;;", apSsid, apPass);   // phone cameras join from this
  snprintf(sub, sizeof(sub), "password  %s", apPass);
  info_open("Scan to join", nullptr, 0, apSsid, sub, "press to stop", false, phone_close, phone_close);
  info_qr(qr);
  phone_keep();
}

static void phone_show_page() {
  phStage = PH_JOINED;
  info_open("Phone joined", nullptr, 0, "192.168.4.1", "the page should open by itself", "press to stop", false, phone_close, phone_close);
  info_qr("http://192.168.4.1/");
  phone_keep();
}

static void phone_tick() {
  web_loop();
  if (phStage == PH_SCAN) {
    ScanState st = net_scan_state();
    if (st == SCAN_BUSY && millis() - phAt < 10000) return;
    // start on the channel of the network it will most likely join, so joining doesn't move it
    uint8_t ch = 0;
    for (int i = 0; i < net_scan_count(); i++) if (!strcmp(net_scan_net(i).ssid, S.ssid)) { ch = net_scan_net(i).ch; break; }
    if (!ch && net_scan_count()) ch = net_scan_net(0).ch;          // strongest
    web_ap_start(ch ? ch : 1, apSsid, sizeof(apSsid), apPass, sizeof(apPass));
    phStage = PH_APWAIT;
    phAt = millis();
    return;
  }
  if (phStage == PH_APWAIT) {
    int r = web_ap_poll();
    if (r == 0 && millis() - phAt < 8000) return;
    if (r != 1) {
      info_open("Set up from phone", LV_SYMBOL_WARNING, C_BAD, "Couldn't start", net_ap_error()[0] ? net_ap_error() : "the setup network",
                "press to go back", false, phone_close, phone_close);
      return;
    }
    phone_show_join();
    return;
  }
  if (phStage == PH_JOIN && web_ap_clients() > 0) phone_show_page();
  else if (phStage == PH_JOINED && web_ap_clients() == 0 && !phTrying) phone_show_join();

  if (web_take_saved()) {
    settings_get(S);
    backlight(S.bright);
    leds_flash(C_OK);
    phone_sub("Saved");
  }
  if (web_take_wifi(phSsid, sizeof(phSsid), phPass, sizeof(phPass))) {
    net_try(phSsid, phPass);
    phTrying = phTried = true;
    phTryAt = millis();
    phone_sub("Joining %s", phSsid);
    leds_set(RING_BREATHE, C_ACCENT, 0, 1400);
  }
  if (phTrying) {
    if (net_try_pending()) { phTryAt = millis(); return; }
    uint32_t el = millis() - phTryAt;
    char why[128];
    if (el > 1500 && wifi_up() && WiFi.SSID() == phSsid) {
      phTrying = false;
      settings_get(S);
      strlcpy(S.ssid, phSsid, sizeof(S.ssid));
      strlcpy(S.pass, phPass, sizeof(S.pass));
      save();
      String ip = WiFi.localIP().toString();
      web_set_wifi(WW_OK, phSsid, ip.c_str());
      leds_set(RING_OFF, C_ACCENT, 0, 1400);
      leds_flash(C_OK);
      phone_sub("On %s", phSsid);
    } else if (setup_wifi_fail_text(el, why, sizeof(why))) {
      phTrying = false;
      web_set_wifi(WW_FAIL, phSsid, why);
      leds_set(RING_OFF, C_ACCENT, 0, 1400);
      leds_flash(C_BAD);
      phone_sub("Couldn't join %s" SEP "see the phone", phSsid);
      // over your network the page just lost the dial: go back to the network that works
      if (phStage == PH_LAN) net_use_saved();
    }
    return;
  }
  // nobody has used the page for a while: close it, the setup network above all
  if (millis() - web_last_request() > 10 * 60000UL && millis() - phAt > 10 * 60000UL) phone_close();
}

static void phone_open(Fn back) {
  phBack = back;
  phAt = millis();
  phTrying = phTried = false;
  web_setup(true);
  if (wifi_up()) {
    phStage = PH_LAN;
    web_begin();
    char url[40];
    snprintf(url, sizeof(url), "http://%s/", WiFi.localIP().toString().c_str());
    info_open("Scan with your phone", nullptr, 0, url, "on the same Wi-Fi as the dial", "press when done", false, phone_close, phone_close);
    info_qr(url);
    phone_keep();
    return;
  }
  // no Wi-Fi: the dial's own network. Hold background reconnects (they'd move the radio's channel),
  // and look around first to pick the channel.
  // Bluetooth and the setup network don't fit in RAM together: Bluetooth pauses until this page closes.
  phStage = PH_SCAN;
  ble_pause();
  net_hold(true);
  net_scan();
  info_open("Set up from phone", nullptr, 0, "Starting", ble_paused() ? "the setup network" SEP "Bluetooth paused" : "the setup network",
            "press to cancel", true, phone_close, phone_close);
  phone_keep();
}

// ============================================================ reminders
enum { RM_WATER, RM_EYES, RM_STRETCH, RM_MOVE, RM_START, RM_END, RM_TZ, RM_BACK, RM_COUNT };
static const char *const REM_LABEL[4] = { LV_SYMBOL_TINT "  Water", LV_SYMBOL_EYE_CLOSE "  Eye breaks",
                                          LV_SYMBOL_SHUFFLE "  Stretch", LV_SYMBOL_GPS "  Move" };
static int remSel = 0, remIdx = 0;

static int remind_count() { return RM_COUNT; }

static void remind_row(int i, Row &r) {
  r.color = C_TEXT; r.detail[0] = 0;
  char clock[8];
  if (i < 4) {
    snprintf(r.text, sizeof(r.text), "%s", REM_LABEL[i]);
    if (S.remind_min[i]) snprintf(r.detail, sizeof(r.detail), "every %d min", S.remind_min[i]);
    else snprintf(r.detail, sizeof(r.detail), "off");
    return;
  }
  switch (i) {
    case RM_START: snprintf(r.text, sizeof(r.text), LV_SYMBOL_BELL "  Active from");
                   snprintf(r.detail, sizeof(r.detail), "%d:00" SEP PET_NAME " wakes up", S.hour_start); break;
    case RM_END:   snprintf(r.text, sizeof(r.text), LV_SYMBOL_BELL "  Active until");
                   snprintf(r.detail, sizeof(r.detail), "%d:00" SEP "then sleeps, no reminders", S.hour_end); break;
    case RM_TZ:    snprintf(r.text, sizeof(r.text), LV_SYMBOL_GPS "  Time zone");
                   if (care_clock_text(clock, sizeof(clock))) snprintf(r.detail, sizeof(r.detail), "%s" SEP "%s", care_tz_name(S.tz), clock);
                   else snprintf(r.detail, sizeof(r.detail), "%s" SEP "clock not set yet", care_tz_name(S.tz));
                   break;
    case RM_BACK:  snprintf(r.text, sizeof(r.text), LV_SYMBOL_LEFT "  Back");
                   snprintf(r.detail, sizeof(r.detail), "timers only run in active hours"); break;
  }
}

static void fmt_every(int v, char *m, size_t mn, char *s, size_t sn) {
  if (v) snprintf(m, mn, "%d min", v); else snprintf(m, mn, "Off");
  static const char *const WHY[4] = { "a glass of water", "20 seconds looking far away", "a one-minute stretch", "a short walk" };
  snprintf(s, sn, v ? PET_NAME " asks for %s this often" : "no reminder for %s", WHY[remIdx]);
}
static void save_every(int v) { S.remind_min[remIdx] = v; save(); remind_open(); }

static void fmt_hour(int v, char *m, size_t mn, char *s, size_t sn) {
  snprintf(m, mn, "%d:00", v);
  snprintf(s, sn, "%s", S.hour_start == S.hour_end ? "start = end: always active" : "local time, from the network");
}
static void save_start(int v) { S.hour_start = v; save(); remind_open(); }
static void save_end(int v) { S.hour_end = v; save(); remind_open(); }

static int tz_count() { return care_tz_count() + 1; }
static void tz_row(int i, Row &r) {
  if (i == care_tz_count()) {
    snprintf(r.text, sizeof(r.text), LV_SYMBOL_LEFT "  Back");
    r.detail[0] = 0; r.color = C_TEXT;
    return;
  }
  snprintf(r.text, sizeof(r.text), "%s", care_tz_name(i));
  snprintf(r.detail, sizeof(r.detail), "%s", i == S.tz ? "current" : "");
  r.color = i == S.tz ? C_OK : C_TEXT;
}
static void tz_tap(int i) {
  if (i < care_tz_count()) { S.tz = i; save(); leds_flash(C_OK); }
  remind_open();
}

static void remind_tap(int i) {
  remSel = i;
  if (i < 4) {
    remIdx = i;
    static const char *const T[4] = { "Water", "Eye breaks", "Stretch", "Move" };
    value_open(T[i], 0, 120, 5, S.remind_min[i], fmt_every, nullptr, save_every, false, remind_open);
    return;
  }
  switch (i) {
    case RM_START: value_open("Active from", 0, 23, 1, S.hour_start, fmt_hour, nullptr, save_start, false, remind_open); break;
    case RM_END:   value_open("Active until", 1, 24, 1, S.hour_end, fmt_hour, nullptr, save_end, false, remind_open); break;
    case RM_TZ:    list_open("Time zone", tz_count, tz_row, tz_tap, remind_open, S.tz); break;
    case RM_BACK:  main_open(); break;
  }
}

static void remind_open() {
  list_open("Reminders", remind_count, remind_row, remind_tap, main_open, remSel);
  tickFn = main_tick;
}

// ============================================================ home screen
enum { HS_STYLE, HS_CLOCK, HS_DATE, HS_STATUS, HS_NEXT, HS_BACK, HS_COUNT };
static int homeSel = 0;
static int home_count() { return HS_COUNT; }
static void home_row(int i, Row &r) {
  r.color = C_TEXT; r.detail[0] = 0;
  static const char *const CLOCK[3] = { "off", "12-hour", "24-hour" };
  switch (i) {
    case HS_STYLE:  snprintf(r.text, sizeof(r.text), LV_SYMBOL_IMAGE "  Clock style");
                    snprintf(r.detail, sizeof(r.detail), "%s" SEP "press to preview", clockface_style_name(S.clock_style)); break;
    case HS_CLOCK:  snprintf(r.text, sizeof(r.text), LV_SYMBOL_BELL "  Clock format");
                    snprintf(r.detail, sizeof(r.detail), "%s" SEP "press to change", CLOCK[S.clock_mode % 3]); break;
    case HS_DATE:   snprintf(r.text, sizeof(r.text), LV_SYMBOL_LIST "  Date");
                    snprintf(r.detail, sizeof(r.detail), "%s" SEP "under the clock", S.show_date ? "on" : "off"); break;
    case HS_STATUS: snprintf(r.text, sizeof(r.text), LV_SYMBOL_WIFI "  Status icons");
                    snprintf(r.detail, sizeof(r.detail), "%s" SEP "Wi-Fi, HA, paused", S.show_status ? "on" : "off"); break;
    case HS_NEXT:   snprintf(r.text, sizeof(r.text), LV_SYMBOL_EYE_OPEN "  Next break");
                    snprintf(r.detail, sizeof(r.detail), "%s" SEP "countdown at the bottom", S.show_next ? "on" : "off"); break;
    case HS_BACK:   snprintf(r.text, sizeof(r.text), LV_SYMBOL_LEFT "  Back");
                    snprintf(r.detail, sizeof(r.detail), "to Settings"); break;
  }
}
static void home_tap(int i) {
  homeSel = i;
  switch (i) {
    case HS_STYLE:  clockpick_open(); return;
    case HS_CLOCK:  S.clock_mode = (S.clock_mode + 1) % 3; break;
    case HS_DATE:   S.show_date = !S.show_date; break;
    case HS_STATUS: S.show_status = !S.show_status; break;
    case HS_NEXT:   S.show_next = !S.show_next; break;
    case HS_BACK:   main_open(); return;
  }
  save();
  leds_flash(C_OK);
  list_render();
}
static void home_open() { list_open("Home screen", home_count, home_row, home_tap, main_open, homeSel); }

// ------------------------------------------------------------ clock style picker: the real face, Wisp in front
static ClockFace *cFace;
static lv_obj_t *cImg, *cName, *cNote;
static int cSel = 0, cFrame = 0;

static void clock_frame_cb(lv_timer_t *) {
  const PetAnim *a = pet_pose_anim(0);
  static int dir = 1;
  cFrame = pet_anim_next(a, cFrame, &dir);
  lv_image_set_src(cImg, a->frames[cFrame]);
}

static void clockpick_render() {
  clockface_set(cFace, cSel, S.clock_mode ? S.clock_mode : 1);
  clockface_update(cFace, true);
  char b[48];
  snprintf(b, sizeof(b), "%s%s", clockface_style_name(cSel), cSel == S.clock_style ? "  " LV_SYMBOL_OK : "");
  lv_label_set_text(cName, b);
  lv_label_set_text(cNote, clockface_style_note(cSel));
  leds_set(RING_POSITION, C_ACCENT, (uint16_t)(cSel * 1000 / CS_COUNT));
}

static void clockpick_open() {
  cSel = S.clock_style;
  show(V_CLOCK);
  const PetAnim *a = pet_pose_anim(0);
  lv_obj_set_pos(cImg, (360 - a->w) / 2, 272 - a->h);
  lv_timer_set_period(cTimer, 1000 / a->fps);
  lv_timer_resume(cTimer);
  clockpick_render();
}

static void clockpick_input(InputEvent e) {
  if (e == IN_CW) { cSel = (cSel + 1) % CS_COUNT; clockpick_render(); }
  else if (e == IN_CCW) { cSel = (cSel + CS_COUNT - 1) % CS_COUNT; clockpick_render(); }
  else if (e == IN_PRESS) { S.clock_style = cSel; if (!S.clock_mode) S.clock_mode = 1; save(); leds_flash(C_OK); home_open(); }
  else if (e == IN_HOLD) home_open();
}

// ============================================================ poses
static lv_obj_t *pImg, *pEmote, *pName, *pNote, *pIdx;
static int poseSel = 0, pFrame = 0;
static const PetAnim *pAnim = nullptr;

static void pose_frame_cb(lv_timer_t *) {
  if (!pAnim) return;
  static int dir = 1;
  if (pFrame == 0) dir = 1;
  pFrame = pet_anim_next(pAnim, pFrame, &dir);
  lv_image_set_src(pImg, pAnim->frames[pFrame]);
  if (!lv_obj_has_flag(pEmote, LV_OBJ_FLAG_HIDDEN)) {       // the bubble follows the face
    int x, y; pet_emote_pos(pAnim, pFrame, x, y);
    lv_obj_set_pos(pEmote, x, y);
  }
}

static void pose_render() {
  pAnim = pet_pose_anim(poseSel);
  pFrame = 0;
  lv_image_set_src(pImg, pAnim->frames[0]);
  lv_obj_set_pos(pImg, (360 - pAnim->w) / 2, 272 - pAnim->h);
  lv_timer_set_period(pTimer, 1000 / pAnim->fps);
  const lv_image_dsc_t *e = pet_pose_emote(poseSel);
  if (e) {
    int x, y; pet_emote_pos(pAnim, 0, x, y);
    lv_image_set_src(pEmote, e);
    lv_obj_set_pos(pEmote, x, y);
    lv_obj_remove_flag(pEmote, LV_OBJ_FLAG_HIDDEN);
  } else lv_obj_add_flag(pEmote, LV_OBJ_FLAG_HIDDEN);
  lv_label_set_text(pName, pet_pose_name(poseSel));
  char note[120];
  int fb = 0;                                       // which loop a missing clip borrows
  for (int i = 0; i < pet_pose_count(); i++) if (pet_pose_has_clip(i) && pet_pose_anim(i) == pAnim) { fb = i; break; }
  if (pet_pose_has_clip(poseSel)) snprintf(note, sizeof(note), "When: %s", pet_pose_use(poseSel));
  else snprintf(note, sizeof(note), "When: %s\nNo clip yet: %s + bubble", pet_pose_use(poseSel), pet_pose_name(fb));
  lv_label_set_text(pNote, note);
  char idx[16];
  snprintf(idx, sizeof(idx), "%d / %d", poseSel + 1, pet_pose_count());
  lv_label_set_text(pIdx, idx);
  leds_set(RING_POSITION, C_ACCENT, (uint16_t)(poseSel * 1000 / pet_pose_count()));
}

static void poses_open() {
  show(V_PREVIEW);
  lv_timer_resume(pTimer);
  pose_render();
}

static void preview_input(InputEvent e) {
  int n = pet_pose_count();
  if (e == IN_CW) { poseSel = (poseSel + 1) % n; pose_render(); }
  else if (e == IN_CCW) { poseSel = (poseSel - 1 + n) % n; pose_render(); }
  else if (e == IN_PRESS || e == IN_HOLD) main_open();
}

// ============================================================ hub (what a press on the pet screen opens)
enum { HB_ACT, HB_SETTINGS, HB_BACK, HB_COUNT };
static int hubSel = 0;
static int hub_count() { return HB_COUNT; }
static void hub_row(int i, Row &r) {
  r.color = C_TEXT; r.detail[0] = 0;
  switch (i) {
    case HB_ACT: {
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_PLAY "  Activities");
      int u = care_most_urgent();
      if (u >= 0) { snprintf(r.detail, sizeof(r.detail), "%s is due", care_need_name((Need)u)); r.color = care_need_color((Need)u); }
      else snprintf(r.detail, sizeof(r.detail), "water, breaks, play, stats");
      break;
    }
    case HB_SETTINGS:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_SETTINGS "  Settings");
      snprintf(r.detail, sizeof(r.detail), "Wi-Fi, reminders, poses, LEDs");
      break;
    case HB_BACK:
      snprintf(r.text, sizeof(r.text), LV_SYMBOL_LEFT "  Back");
      snprintf(r.detail, sizeof(r.detail), "to " PET_NAME);
      break;
  }
}
static void hub_tap(int i) {
  hubSel = i;
  switch (i) {
    case HB_ACT: show(V_LIST); app_show_pet(); pet_open_ring(); break;
    case HB_SETTINGS: mainSel = 0; main_open(); break;
    case HB_BACK: setup_exit(); break;
  }
}
static void hub_open() {
  list_open("Menu", hub_count, hub_row, hub_tap, setup_exit, hubSel);
  tickFn = main_tick;
}

// ============================================================ screen
static void setup_exit() {
  bool hadAp = web_ap_active();
  web_stop();
  net_hold(false);
  if (hadAp || phTried) {                 // left with the phone page up: back to the saved network
    settings_get(S);
    if (!(wifi_up() && WiFi.SSID() == S.ssid)) net_use_saved();
    phTried = phTrying = false;
  }
  wifi_restore();
  // drop any live preview that wasn't saved
  settings_get(S);
  settings_preview_led_offset(S.led_offset);
  settings_preview_led_bright(S.led_bright);
  backlight(S.bright);
  show(V_LIST);
  app_show_pet();
}

void setup_create() {
  scr = lv_obj_create(nullptr);
  lv_obj_set_style_bg_color(scr, lv_color_hex(PET_BG), 0);
  lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
  lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);
  for (int i = 0; i < V_COUNT; i++) views[i] = mk_view();

  // list
  lv_obj_t *v = views[V_LIST];
  lArc = mk_arc(v, 344, 4);
  lv_arc_set_bg_angles(lArc, 0, 360);
  mk_pill(v, 300, 46, 0);
  static const lv_font_t *RF[5] = { &lv_font_montserrat_14, &lv_font_montserrat_20, &lv_font_montserrat_28,
                                    &lv_font_montserrat_20, &lv_font_montserrat_14 };
  for (int k = 0; k < 5; k++) lRows[k] = mk_label(v, RF[k], ROW_Y[k], ROW_W[k], C_SUB);
  lTitle = mk_label(v, &lv_font_montserrat_14, -142, 200, C_ACCENT);
  lDetail = mk_label(v, &lv_font_montserrat_14, 126, 214, C_SUB);

  // character wheel
  v = views[V_TEXT];
  TF[0] = TF[6] = &lv_font_montserrat_14;
  TF[1] = TF[5] = &lv_font_montserrat_20;
  TF[2] = TF[4] = &lv_font_montserrat_28;
  TF[3] = &lv_font_montserrat_48;
  mk_pill(v, 72, 72, 22);
  for (int k = 0; k < 7; k++) {
    tItems[k] = mk_label(v, TF[k], 22, k == 3 ? 72 : 48, C_SUB);
    lv_label_set_long_mode(tItems[k], LV_LABEL_LONG_CLIP);
    lv_obj_set_height(tItems[k], LV_SIZE_CONTENT);    // fonts change per item; keep each one centred
    lv_obj_align(tItems[k], LV_ALIGN_CENTER, TXPOS[k], 22);
  }
  tTitle = mk_label(v, &lv_font_montserrat_14, -120, 250, C_ACCENT);
  tValue = mk_label(v, &lv_font_montserrat_28, -74, 300, C_TEXT);
  tPage = mk_label(v, &lv_font_montserrat_14, 76, 120, C_ACCENT);
  tHint = mk_label(v, &lv_font_montserrat_14, 124, 240, C_DIM);
  lv_label_set_text(tHint, "press: type" SEP LV_SYMBOL_OK " saves" SEP "hold: back");

  // value dial
  v = views[V_VALUE];
  vArc = mk_arc(v, 330, 14);
  lv_arc_set_bg_angles(vArc, 135, 45);
  vMarker = mk_label(v, &lv_font_montserrat_28, -124, 60, 0xffffff);
  lv_label_set_text(vMarker, LV_SYMBOL_UP);
  vTitle = mk_label(v, &lv_font_montserrat_20, -62, 240, C_TEXT);
  vVal = mk_label(v, &lv_font_montserrat_48, 4, 240, 0xffffff);
  vSub = mk_label(v, &lv_font_montserrat_14, 62, 220, C_SUB, true);
  vHint = mk_label(v, &lv_font_montserrat_14, 128, 200, C_DIM);
  lv_label_set_text(vHint, "press: save" SEP "hold: cancel");

  // pose preview
  v = views[V_PREVIEW];
  pImg = lv_image_create(v);
  pEmote = lv_image_create(v);
  pName = mk_label(v, &lv_font_montserrat_28, -128, 220, C_TEXT);
  pIdx = mk_label(v, &lv_font_montserrat_14, -154, 100, C_ACCENT);
  pNote = mk_label(v, &lv_font_montserrat_14, 112, 230, C_SUB, true);
  lv_obj_t *ph = mk_label(v, &lv_font_montserrat_14, 146, 180, C_DIM);
  lv_label_set_text(ph, "turn: next" SEP "press: back");
  pTimer = lv_timer_create(pose_frame_cb, 83, nullptr);
  lv_timer_pause(pTimer);

  // clock style picker
  v = views[V_CLOCK];
  cFace = clockface_create(v);
  cImg = lv_image_create(v);
  cName = mk_label(v, &lv_font_montserrat_20, 112, 220, C_TEXT);
  cNote = mk_label(v, &lv_font_montserrat_14, 136, 210, C_SUB);
  lv_obj_t *ch = mk_label(v, &lv_font_montserrat_14, 156, 160, C_DIM);
  lv_label_set_text(ch, "press: use" SEP "hold: back");
  cTimer = lv_timer_create(clock_frame_cb, 83, nullptr);
  lv_timer_pause(cTimer);

  // info card
  v = views[V_INFO];
  iTitle = mk_label(v, &lv_font_montserrat_14, -128, 220, C_ACCENT);
  iIcon = mk_label(v, &lv_font_montserrat_48, -60, 120, C_ACCENT);
  iSpin = lv_spinner_create(v);
  lv_obj_set_size(iSpin, 70, 70);
  lv_obj_align(iSpin, LV_ALIGN_CENTER, 0, -60);
  lv_spinner_set_anim_params(iSpin, 1000, 60);
  lv_obj_set_style_arc_width(iSpin, 6, LV_PART_MAIN);
  lv_obj_set_style_arc_width(iSpin, 6, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(iSpin, lv_color_hex(C_PILL), LV_PART_MAIN);
  lv_obj_set_style_arc_color(iSpin, lv_color_hex(C_ACCENT), LV_PART_INDICATOR);
  iMain = mk_label(v, &lv_font_montserrat_28, 6, 280, C_TEXT);
  iSub = mk_label(v, &lv_font_montserrat_20, 50, 260, C_SUB, true);
  iHint = mk_label(v, &lv_font_montserrat_14, 132, 220, C_DIM);
  iQrBox = lv_obj_create(v);               // white quiet zone around the code so phones read it
  lv_obj_remove_style_all(iQrBox);
  lv_obj_set_size(iQrBox, 164, 164);
  lv_obj_set_style_bg_color(iQrBox, lv_color_white(), 0);
  lv_obj_set_style_bg_opa(iQrBox, LV_OPA_COVER, 0);
  lv_obj_set_style_radius(iQrBox, 10, 0);
  lv_obj_align(iQrBox, LV_ALIGN_CENTER, 0, -32);
  iQr = lv_qrcode_create(iQrBox);
  lv_qrcode_set_size(iQr, 140);
  lv_qrcode_set_dark_color(iQr, lv_color_black());
  lv_qrcode_set_light_color(iQr, lv_color_white());
  lv_obj_center(iQr);
  lv_obj_add_flag(iQrBox, LV_OBJ_FLAG_HIDDEN);
}

lv_obj_t *setup_screen() { return scr; }

void setup_enter_hub() {
  settings_get(S);
  lastInput = millis();
  resetArmed = false;
  hubSel = 0;
  hub_open();
}

void setup_enter() {
  settings_get(S);
  lastInput = millis();
  resetArmed = false;
  mainSel = 0;
  remSel = 0;
  main_open();
}

void setup_input(InputEvent e) {
  lastInput = millis();
  if (e == IN_CW || e == IN_CCW) resetArmed = false;
  switch (view) {
    case V_LIST: list_input(e); break;
    case V_TEXT: text_input(e); break;
    case V_VALUE: value_input(e); break;
    case V_INFO: info_input(e); break;
    case V_PREVIEW: preview_input(e); break;
    case V_CLOCK: clockpick_input(e); break;
    default: break;
  }
}

void setup_tick() {
  if (lv_screen_active() != scr) return;
  if (view == V_LIST) list_tick();
  if (view == V_CLOCK) {
    static uint32_t last = 0;
    if (millis() - last > 1000) { last = millis(); clockface_update(cFace); }
  }
  if (tickFn) tickFn();
  web_loop();
  if (!noIdle && millis() - lastInput > IDLE_BACK_MS) setup_exit();
}
