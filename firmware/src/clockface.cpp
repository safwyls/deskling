#include "clockface.h"
#include "clock_digits.h"
#include "care.h"
#include <math.h>
#include <string.h>
#include <stdio.h>

static const char *const NAMES[CS_COUNT] = { "Classic", "Big", "Split", "Analog", "Rim", "Words" };
static const char *const NOTES[CS_COUNT] = {
  "small, at the top", "heavy digits, Wisp floats in front", "hours and minutes either side",
  "faint hands behind Wisp", "minutes around the edge", "quarter past nine",
};
const char *clockface_style_name(int s) { return NAMES[s % CS_COUNT]; }
int clockface_style_count() { return CS_COUNT; }
const char *clockface_style_note(int s) { return NOTES[s % CS_COUNT]; }

static const uint32_t COL_BIG = 0x27395c, COL_SPLIT = 0x34496f, COL_HAND = 0x2f4268, COL_TICK = 0x3a4e78,
                      COL_RIM = 0x9fd8f7, COL_WORD = 0x3e5279, COL_WORD_HI = 0x6680b4, COL_CLASSIC = 0xe8eef8;

struct ClockFace {
  lv_obj_t *layer;
  int style = -1, mode = 1;
  int lastMin = -1;
  bool shown = true;
  // classic
  lv_obj_t *small;
  // digits (big / split)
  lv_obj_t *row, *rowL, *rowR;
  lv_obj_t *dig[5], *digL[2], *digR[2];
  // analog
  lv_obj_t *ticks[12], *hHand, *mHand, *hub;
  lv_point_precise_t hPts[2], mPts[2];
  // rim
  lv_obj_t *rimArc, *rimDot, *rimTicks[12];
  // words
  lv_obj_t *w1, *w2;
};

static lv_obj_t *box(lv_obj_t *p) {
  lv_obj_t *o = lv_obj_create(p);
  lv_obj_remove_style_all(o);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_CLICKABLE);
  return o;
}

static lv_obj_t *digit_row(lv_obj_t *p, int gap) {
  lv_obj_t *r = box(p);
  lv_obj_set_size(r, LV_SIZE_CONTENT, LV_SIZE_CONTENT);
  lv_obj_set_flex_flow(r, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(r, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(r, gap, 0);
  return r;
}

static lv_obj_t *digit(lv_obj_t *row, uint32_t col) {
  lv_obj_t *d = lv_image_create(row);
  lv_obj_set_style_image_recolor(d, lv_color_hex(col), 0);
  lv_obj_set_style_image_recolor_opa(d, LV_OPA_COVER, 0);
  return d;
}

static lv_obj_t *dot(lv_obj_t *p, int size, uint32_t col, lv_opa_t opa) {
  lv_obj_t *o = box(p);
  lv_obj_set_size(o, size, size);
  lv_obj_set_style_radius(o, LV_RADIUS_CIRCLE, 0);
  lv_obj_set_style_bg_color(o, lv_color_hex(col), 0);
  lv_obj_set_style_bg_opa(o, opa, 0);
  return o;
}

static void at_angle(lv_obj_t *o, float deg, int r) {        // deg: 0 = 12 o'clock, clockwise
  float a = (deg - 90) * 3.14159265f / 180;
  lv_obj_align(o, LV_ALIGN_CENTER, (int)lroundf(r * cosf(a)), (int)lroundf(r * sinf(a)));
}

static lv_obj_t *hand(lv_obj_t *p, int width, uint32_t col, lv_point_precise_t *pts) {
  lv_obj_t *l = lv_line_create(p);
  lv_obj_set_size(l, 360, 360);
  lv_obj_set_pos(l, 0, 0);
  lv_obj_set_style_line_width(l, width, 0);
  lv_obj_set_style_line_color(l, lv_color_hex(col), 0);
  lv_obj_set_style_line_rounded(l, true, 0);
  lv_line_set_points(l, pts, 2);
  return l;
}

static void set_hand(lv_obj_t *l, lv_point_precise_t *pts, float deg, int len) {
  float a = (deg - 90) * 3.14159265f / 180;
  pts[0].x = 180; pts[0].y = 180;
  pts[1].x = 180 + len * cosf(a); pts[1].y = 180 + len * sinf(a);
  lv_line_set_points(l, pts, 2);
}

ClockFace *clockface_create(lv_obj_t *parent) {
  ClockFace *f = new ClockFace();
  f->layer = box(parent);
  lv_obj_set_size(f->layer, 360, 360);
  lv_obj_set_pos(f->layer, 0, 0);

  f->small = lv_label_create(f->layer);
  lv_obj_set_style_text_font(f->small, &lv_font_montserrat_20, 0);
  lv_obj_set_style_text_color(f->small, lv_color_hex(COL_CLASSIC), 0);
  lv_obj_align(f->small, LV_ALIGN_CENTER, 0, -146);

  f->row = digit_row(f->layer, 4);
  for (auto &d : f->dig) d = digit(f->row, COL_BIG);
  lv_obj_align(f->row, LV_ALIGN_CENTER, 0, -60);

  f->rowL = digit_row(f->layer, 2);
  f->rowR = digit_row(f->layer, 2);
  for (int i = 0; i < 2; i++) { f->digL[i] = digit(f->rowL, COL_SPLIT); f->digR[i] = digit(f->rowR, COL_SPLIT); }

  for (int i = 0; i < 12; i++) {
    f->ticks[i] = dot(f->layer, i % 3 == 0 ? 8 : 4, COL_TICK, LV_OPA_COVER);
    at_angle(f->ticks[i], i * 30, 160);
  }
  f->hHand = hand(f->layer, 9, COL_HAND, f->hPts);
  f->mHand = hand(f->layer, 6, COL_HAND, f->mPts);
  f->hub = dot(f->layer, 16, COL_HAND, LV_OPA_COVER);
  lv_obj_center(f->hub);

  for (int i = 0; i < 12; i++) {
    f->rimTicks[i] = dot(f->layer, 3, COL_RIM, LV_OPA_30);
    at_angle(f->rimTicks[i], i * 30, 162);
  }
  f->rimArc = lv_arc_create(f->layer);
  lv_obj_set_size(f->rimArc, 348, 348);
  lv_obj_center(f->rimArc);
  lv_obj_remove_style(f->rimArc, nullptr, LV_PART_KNOB);
  lv_obj_remove_flag(f->rimArc, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_arc_opa(f->rimArc, LV_OPA_TRANSP, LV_PART_MAIN);
  lv_obj_set_style_arc_width(f->rimArc, 5, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(f->rimArc, lv_color_hex(COL_RIM), LV_PART_INDICATOR);
  lv_obj_set_style_arc_opa(f->rimArc, LV_OPA_40, LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(f->rimArc, true, LV_PART_INDICATOR);
  lv_arc_set_rotation(f->rimArc, 270);
  lv_arc_set_bg_angles(f->rimArc, 0, 360);
  lv_arc_set_range(f->rimArc, 0, 60);
  f->rimDot = dot(f->layer, 14, COL_RIM, LV_OPA_70);

  f->w1 = lv_label_create(f->layer);
  lv_obj_set_style_text_font(f->w1, &lv_font_montserrat_20, 0);
  lv_obj_set_style_text_color(f->w1, lv_color_hex(COL_WORD), 0);
  lv_obj_align(f->w1, LV_ALIGN_CENTER, 0, -112);
  f->w2 = lv_label_create(f->layer);
  lv_obj_set_style_text_font(f->w2, &lv_font_montserrat_48, 0);
  lv_obj_set_style_text_color(f->w2, lv_color_hex(COL_WORD_HI), 0);
  lv_obj_align(f->w2, LV_ALIGN_CENTER, 0, -74);

  clockface_set(f, CS_CLASSIC, 1);
  return f;
}

static void vis(lv_obj_t *o, bool on) { on ? lv_obj_remove_flag(o, LV_OBJ_FLAG_HIDDEN) : lv_obj_add_flag(o, LV_OBJ_FLAG_HIDDEN); }

void clockface_set(ClockFace *f, int style, int mode) {
  if (style == f->style && mode == f->mode) return;
  f->style = style % CS_COUNT; f->mode = mode;
  bool on = mode != 0;
  vis(f->small, on && (f->style == CS_CLASSIC || f->style == CS_RIM));
  vis(f->row, on && f->style == CS_BIG);
  vis(f->rowL, on && f->style == CS_SPLIT);
  vis(f->rowR, on && f->style == CS_SPLIT);
  for (auto t : f->ticks) vis(t, on && f->style == CS_ANALOG);
  vis(f->hHand, on && f->style == CS_ANALOG);
  vis(f->mHand, on && f->style == CS_ANALOG);
  vis(f->hub, on && f->style == CS_ANALOG);
  for (auto t : f->rimTicks) vis(t, on && f->style == CS_RIM);
  vis(f->rimArc, on && f->style == CS_RIM);
  vis(f->rimDot, on && f->style == CS_RIM);
  vis(f->w1, on && f->style == CS_WORDS);
  vis(f->w2, on && f->style == CS_WORDS);
  // the rim style keeps the small time at the top, a little quieter
  lv_obj_set_style_text_color(f->small, lv_color_hex(f->style == CS_RIM ? 0x8a94a8 : COL_CLASSIC), 0);
  clockface_update(f, true);
}

void clockface_show(ClockFace *f, bool on) { f->shown = on; vis(f->layer, on); }

static void set_digits(lv_obj_t *const *slots, int n, const char *txt, const lv_image_dsc_t *const *set) {
  int k = 0;
  for (const char *p = txt; *p && k < n; p++) {
    int i = *p == ':' ? 10 : (*p >= '0' && *p <= '9') ? *p - '0' : -1;
    if (i < 0) continue;
    lv_image_set_src(slots[k], set[i]);
    vis(slots[k], true);
    k++;
  }
  for (; k < n; k++) vis(slots[k], false);
}

static const char *const HOUR_WORD[13] = { "twelve", "one", "two", "three", "four", "five", "six", "seven",
                                          "eight", "nine", "ten", "eleven", "twelve" };

void clockface_update(ClockFace *f, bool force) {
  if (!f->mode) return;
  char buf[16];
  if (!care_clock_format(buf, sizeof(buf), "%H:%M")) {        // no time yet: say so rather than show nothing
    lv_label_set_text(f->small, "--:--");
    vis(f->small, true);
    vis(f->layer, f->shown);
    f->lastMin = -1;
    return;
  }
  vis(f->small, f->style == CS_CLASSIC || f->style == CS_RIM);
  int H = atoi(buf), M = atoi(buf + 3);
  if (!force && M == f->lastMin) return;
  f->lastMin = M;
  int h12 = H % 12 == 0 ? 12 : H % 12;
  int hShown = f->mode == 1 ? h12 : H;

  char t[12];
  if (f->mode == 1) snprintf(t, sizeof(t), "%d:%02d %s", h12, M, H < 12 ? "AM" : "PM");
  else snprintf(t, sizeof(t), "%02d:%02d", H, M);
  lv_label_set_text(f->small, t);

  switch (f->style) {
    case CS_BIG:
      snprintf(t, sizeof(t), f->mode == 1 ? "%d:%02d" : "%02d:%02d", hShown, M);
      set_digits(f->dig, 5, t, clock_big);
      lv_obj_align(f->row, LV_ALIGN_CENTER, 0, -60);
      break;
    case CS_SPLIT: {
      snprintf(t, sizeof(t), f->mode == 1 ? "%d" : "%02d", hShown);
      set_digits(f->digL, 2, t, clock_thin);
      snprintf(t, sizeof(t), "%02d", M);
      set_digits(f->digR, 2, t, clock_thin);
      lv_obj_update_layout(f->rowL);
      // hours hug the left of centre, minutes the right; Wisp covers the inner edges
      lv_obj_align(f->rowL, LV_ALIGN_CENTER, -14 - lv_obj_get_width(f->rowL) / 2, -6);
      lv_obj_align(f->rowR, LV_ALIGN_CENTER, 14 + lv_obj_get_width(f->rowR) / 2, -6);
      break;
    }
    case CS_ANALOG:
      set_hand(f->hHand, f->hPts, (H % 12) * 30 + M * 0.5f, 92);
      set_hand(f->mHand, f->mPts, M * 6, 148);
      break;
    case CS_RIM:
      lv_arc_set_value(f->rimArc, M == 0 ? 60 : M);
      at_angle(f->rimDot, (H % 12) * 30 + M * 0.5f, 146);
      break;
    case CS_WORDS: {
      int m5 = (M + 2) / 5 * 5, hr = H;
      if (m5 == 60) { m5 = 0; hr++; }
      if (m5 > 30) hr++;
      const char *hw = HOUR_WORD[hr % 12 == 0 ? 12 : hr % 12];
      static const char *const PAST[7] = { "", "five past", "ten past", "quarter past", "twenty past", "twenty-five past", "half past" };
      static const char *const TO[6] = { "", "twenty-five to", "twenty to", "quarter to", "ten to", "five to" };
      lv_label_set_text(f->w1, m5 == 0 ? "it's" : m5 <= 30 ? PAST[m5 / 5] : TO[(m5 - 30) / 5]);
      lv_label_set_text(f->w2, hw);
      break;
    }
    default: break;
  }
  vis(f->layer, f->shown);
}
