// Pet screen: Wisp, and the care game around it.
//
//   press (knob)  menu: Activities (the care ring), Home Assistant, Settings, Back
//   tap screen    on the bare home screen: starts what Wisp is asking for (the most urgent due need), or a pat
//                 when nothing is due; over the ring, an activity or stats a tap is a press
//   turn          shortcut to the care ring around the rim: water, eye break, stretch, move, play, stats,
//                 bedtime, pause, back. Press does the highlighted thing.
//   hold          back: closes the ring, stats, or stops an activity
//
// Wisp's mood comes from the care model (care.cpp) and picks the animation. Moods without their own clip
// yet fall back to an existing loop plus an emote bubble; see POSES.
#include "app.h"
#include "config.h"
#include "settings.h"
#include "leds.h"
#include "care.h"
#include "crashlog.h"
#include "emotes.h"
#include "anims/anims.h"
#include "clockface.h"
#include <WiFi.h>

static const uint32_t C_TEXT = 0xe8eef8, C_ACCENT = 0x9fd8f7, C_SUB = 0x8a94a8, C_DIM = 0x4a5468,
                      C_OK = 0x5cf08a, C_BAD = 0xff6b6b, C_PILL = 0x1e2a42;
static const int GROUND_Y = 272;        // where every loop's shadow sits
static const uint32_t RING_IDLE_MS = 6000;

// ============================================================ poses
// To give a mood its own clip: generate the loop, add it to src/anims, and put it in the first column.
enum PoseId { P_CONTENT, P_HAPPY, P_THIRSTY, P_TIRED, P_STRETCH, P_MOVE, P_SAD, P_MAD, P_ASLEEP, P_DASH, P_BOUNCE, P_COUNT };
struct Pose { const char *name; const PetAnim *anim; const PetAnim *fallback; const lv_image_dsc_t *emote; const char *use; };
static const Pose POSES[P_COUNT] = {
  { "Content",  &wisp_walk,     nullptr, &emote_content, "all needs met" },
  { "Happy",    &wisp_happy,    nullptr, &emote_happy,   "very happy (no bubble), and after a break or a pat (heart)" },
  { "Thirsty",  &wisp_thirsty,  nullptr, nullptr,        "water is due (the bubble is part of the clip)" },
  { "Tired",    &wisp_tired,    nullptr, &emote_eyes,    "an eye break is due" },
  { "Stretch",  &wisp_restless, nullptr, &emote_stretch, "a stretch is due" },
  { "Move",     &wisp_move,     nullptr, &emote_move,    "time to get up and move" },
  { "Sad",      &wisp_sad,      nullptr, &emote_sad,     "needs ignored for a while" },
  { "Mad",      &wisp_mad,      nullptr, &emote_mad,     "needs ignored a long time" },
  { "Asleep",   &wisp_sleep,    nullptr, &emote_asleep,  "night, bedtime, naps, eye breaks" },
  { "Dashing",  &wisp_run,      nullptr, nullptr,        "playing, out on a walk" },
  { "Bouncing", &wisp_jump,     nullptr, nullptr,        "celebrating, stretching" },
};
// restless is Stretch or Move, by whichever is more urgent (see pet_tick)
static const PoseId MOOD_POSE[MOOD_COUNT] = { P_CONTENT, P_HAPPY, P_THIRSTY, P_TIRED, P_STRETCH, P_SAD, P_MAD, P_ASLEEP };

int pet_pose_count() { return P_COUNT; }
const char *pet_pose_name(int i) { return POSES[i].name; }
const char *pet_pose_use(int i) { return POSES[i].use; }
bool pet_pose_has_clip(int i) { return POSES[i].anim != nullptr; }
const PetAnim *pet_pose_anim(int i) { return POSES[i].anim ? POSES[i].anim : POSES[i].fallback; }
const lv_image_dsc_t *pet_pose_emote(int i) { return POSES[i].emote; }

// Where the emote bubble goes for a frame: up and to the right of the face anchor, kept on the glass.
// The bubble follows the face from frame to frame (hx/hy, measured from the clip by make_assets.py).
void pet_emote_pos(const PetAnim *a, int frame, int &x, int &y) {
  int ix = (360 - a->w) / 2, iy = GROUND_Y - a->h;
  int f = frame >= 0 && frame < a->count ? frame : 0;
  int fx = a->ax + (a->hx ? a->hx[f] : 0), fy = a->ay + (a->hy ? a->hy[f] : 0);
  x = ix + fx + 25;              // the bubble's last trailing dot ends up just above and right of the face
  y = iy + fy - 87;
  if (y < 64) { x += 64 - y; y = 64; }   // below the clock row; a tall pose pushes the bubble out sideways instead of onto its head
  if (x > 360 - 92) x = 360 - 92;
}

// ============================================================ widgets
static lv_obj_t *scr, *img, *emote, *toast;
static lv_timer_t *timer;
static int pose = -1, frame = 0;
static const PetAnim *curAnim = nullptr;
static const lv_image_dsc_t *curEmote = nullptr;

static lv_obj_t *mk_label(lv_obj_t *p, const lv_font_t *f, int y, int w, uint32_t col, bool wrap = false) {
  lv_obj_t *l = lv_label_create(p);
  lv_obj_set_style_text_font(l, f, 0);
  lv_obj_set_style_text_color(l, lv_color_hex(col), 0);
  lv_obj_set_style_text_align(l, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_set_width(l, w);
  lv_label_set_long_mode(l, wrap ? LV_LABEL_LONG_WRAP : LV_LABEL_LONG_DOT);
  if (!wrap) lv_obj_set_height(l, lv_font_get_line_height(f));
  lv_label_set_text(l, "");
  lv_obj_align(l, LV_ALIGN_CENTER, 0, y);
  return l;
}

static lv_obj_t *mk_layer() {
  lv_obj_t *o = lv_obj_create(scr);
  lv_obj_remove_style_all(o);
  lv_obj_set_size(o, 360, 360);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_remove_flag(o, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_add_flag(o, LV_OBJ_FLAG_HIDDEN);
  return o;
}

static lv_obj_t *mk_arc(lv_obj_t *p, int size, int width, uint32_t col) {
  lv_obj_t *a = lv_arc_create(p);
  lv_obj_set_size(a, size, size);
  lv_obj_remove_style(a, nullptr, LV_PART_KNOB);
  lv_obj_remove_flag(a, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_set_style_arc_width(a, width, LV_PART_MAIN);
  lv_obj_set_style_arc_width(a, width, LV_PART_INDICATOR);
  lv_obj_set_style_arc_color(a, lv_color_hex(C_PILL), LV_PART_MAIN);
  lv_obj_set_style_arc_color(a, lv_color_hex(col), LV_PART_INDICATOR);
  lv_obj_set_style_arc_rounded(a, true, LV_PART_INDICATOR);
  return a;
}

static void show(lv_obj_t *o, bool on) { on ? lv_obj_remove_flag(o, LV_OBJ_FLAG_HIDDEN) : lv_obj_add_flag(o, LV_OBJ_FLAG_HIDDEN); }

static uint32_t toastUntil = 0;
static void say(const char *txt, uint32_t ms = 2500) {
  lv_label_set_text(toast, txt);
  lv_obj_set_style_opa(toast, LV_OPA_COVER, 0);
  toastUntil = millis() + ms;
}

// ============================================================ animation
static void show_frame() { lv_image_set_src(img, curAnim->frames[frame]); }

static int frameDir = 1;
static void place_emote() {
  if (!curEmote) return;
  int x, y; pet_emote_pos(curAnim, frame, x, y);
  lv_obj_set_pos(emote, x, y);
}

static void frame_cb(lv_timer_t *) {
  frame = pet_anim_next(curAnim, frame, &frameDir);
  show_frame();
  place_emote();
}

static void set_pose(int p) {
  const PetAnim *a = pet_pose_anim(p);
  if (p == pose && a == curAnim) return;
  pose = p;
  if (a != curAnim) {
    curAnim = a;
    frame = 0; frameDir = 1;
    lv_obj_set_pos(img, (360 - a->w) / 2, GROUND_Y - a->h);
    show_frame();
    lv_timer_set_period(timer, 1000 / a->fps);
  }
}

static void bob_cb(void *o, int32_t v) { lv_obj_set_style_translate_y((lv_obj_t *)o, v, 0); }

static void set_emote(const lv_image_dsc_t *e) {
  if (e == curEmote) return;
  curEmote = e;
  if (!e) { show(emote, false); return; }
  lv_image_set_src(emote, e);
  place_emote();
  show(emote, true);
  lv_obj_fade_in(emote, 200, 0);
}

static uint32_t loop_ms(const PetAnim *a) { return 1000UL * a->count / a->fps; }

// ============================================================ state
enum Act : uint8_t { A_NONE, A_EYES, A_STRETCH, A_MOVE, A_PLAY };
static Act act = A_NONE;
static uint32_t actStart = 0;
static bool ringOn = false, statsOn = false;
static int ringSel = 0;
static uint32_t lastInput = 0, ringInput = 0, cueUntil = 0;
static uint32_t reactionUntil = 0;
static int reactionPose = P_BOUNCE;
static uint32_t lastEvent = 0;
static int brightNow = -1;
static RingMode ledMode = RING_OFF; static uint32_t ledColor = 1; static uint16_t ledPeriod = 0, ledValue = 0;

static void led(RingMode m, uint32_t c, uint16_t v = 0, uint16_t p = 4000) {
  if (m == ledMode && c == ledColor && v == ledValue && p == ledPeriod) return;
  ledMode = m; ledColor = c; ledValue = v; ledPeriod = p;
  leds_set(m, c, v, p);
}

static void react(int p, int loops = 2) {
  reactionPose = p;
  reactionUntil = millis() + loops * loop_ms(pet_pose_anim(p));
}

// ============================================================ care ring
enum RingItem { R_WATER, R_EYES, R_STRETCH, R_MOVE, R_PLAY, R_STATS, R_BED, R_PAUSE, R_BACK, R_COUNT };
static const lv_image_dsc_t *const RING_ICON[R_COUNT] = { &icon_water, &icon_eyes, &icon_stretch, &icon_move,
                                                          &icon_play, &icon_stats, &icon_moon, &icon_pause, &icon_back };
static lv_obj_t *ringLayer, *ringBg[R_COUNT], *ringImg[R_COUNT], *ringMeter[4];
static lv_obj_t *cIcon, *cName, *cDetail;
static const int RING_R = 145;

static void fmt_left(char *buf, size_t n, uint32_t s) {
  if (s >= 3600) snprintf(buf, n, "in %luh %02lum", (unsigned long)(s / 3600), (unsigned long)(s / 60 % 60));
  else snprintf(buf, n, "in %lu min", (unsigned long)((s + 59) / 60));
}

static void need_detail(Need n, char *buf, size_t sz) {
  char when[24];
  if (!care_need_enabled(n)) snprintf(when, sizeof(when), "reminder off");
  else if (care_need_due(n)) snprintf(when, sizeof(when), "due now");
  else fmt_left(when, sizeof(when), care_need_left_s(n));
  snprintf(buf, sz, "%s  " LV_SYMBOL_BULLET "  %d today", when, care_today(n));
}

static void ring_render() {
  for (int i = 0; i < R_COUNT; i++) {
    bool sel = i == ringSel;
    int sz = sel ? 54 : 44;
    lv_obj_set_size(ringBg[i], sz, sz);
    float a = (-90 + i * 360.0f / (int)R_COUNT) * 3.14159265f / 180;
    lv_obj_align(ringBg[i], LV_ALIGN_CENTER, (int)(RING_R * cosf(a)), (int)(RING_R * sinf(a)));
    uint32_t iconCol = C_SUB;
    if (i < 4 && care_need_due((Need)i)) iconCol = care_need_color((Need)i);
    lv_obj_set_style_bg_color(ringBg[i], lv_color_hex(sel ? C_ACCENT : C_PILL), 0);
    lv_obj_set_style_image_recolor(ringImg[i], lv_color_hex(sel ? PET_BG : iconCol), 0);
    lv_image_set_scale(ringImg[i], sel ? 112 : 92);
    if (i < 4) {
      lv_arc_set_value(ringMeter[i], care_need_level((Need)i));
      show(ringMeter[i], !sel && care_need_enabled((Need)i));
    }
  }
  // centre: the highlighted item, big
  char name[32], det[64];
  uint32_t col = C_ACCENT;
  switch (ringSel) {
    case R_WATER:   snprintf(name, sizeof(name), "Water"); need_detail(NEED_WATER, det, sizeof(det)); break;
    case R_EYES:    snprintf(name, sizeof(name), "Eye break"); need_detail(NEED_EYES, det, sizeof(det)); break;
    case R_STRETCH: snprintf(name, sizeof(name), "Stretch"); need_detail(NEED_STRETCH, det, sizeof(det)); break;
    case R_MOVE:    snprintf(name, sizeof(name), "Move"); need_detail(NEED_MOVE, det, sizeof(det)); break;
    case R_PLAY:    snprintf(name, sizeof(name), "Play"); snprintf(det, sizeof(det), "catch the light at the top"); break;
    case R_STATS:   snprintf(name, sizeof(name), "Stats");
                    snprintf(det, sizeof(det), "level %d  " LV_SYMBOL_BULLET "  %d%% happy", care_level(), care_happiness()); break;
    case R_BED:
      if (care_bedtime()) { snprintf(name, sizeof(name), "Wake up"); snprintf(det, sizeof(det), "reminders back on"); }
      else { snprintf(name, sizeof(name), "Bedtime"); snprintf(det, sizeof(det), "reminders off until morning"); }
      break;
    case R_PAUSE:
      if (care_paused()) { snprintf(name, sizeof(name), "Resume"); snprintf(det, sizeof(det), "paused, %lu min left", (unsigned long)((care_pause_left_s() + 59) / 60)); }
      else { snprintf(name, sizeof(name), "Pause"); snprintf(det, sizeof(det), "no reminders for an hour"); }
      break;
    case R_BACK:    snprintf(name, sizeof(name), "Back"); snprintf(det, sizeof(det), "close the activities"); break;
  }
  if (ringSel < 4) col = care_need_color((Need)ringSel);
  lv_image_set_src(cIcon, RING_ICON[ringSel]);
  lv_obj_set_style_image_recolor(cIcon, lv_color_hex(col), 0);
  lv_label_set_text(cName, name);
  lv_label_set_text(cDetail, det);
  led(RING_POSITION, C_ACCENT, (uint16_t)(ringSel * 1000 / R_COUNT));
}

static void toast_clear() { toastUntil = 0; lv_obj_set_style_opa(toast, LV_OPA_TRANSP, 0); }

static void ring_show(bool on) {
  ringOn = on;
  show(ringLayer, on);
  lv_obj_set_style_opa(img, on ? LV_OPA_10 : LV_OPA_COVER, 0);
  if (on) { show(emote, false); toast_clear(); }
  else curEmote = (const lv_image_dsc_t *)1;          // force the bubble to be re-evaluated
  if (on) {
    int u = care_most_urgent();
    if (u >= 0) ringSel = u;
    ringInput = millis();
    ring_render();
  }
}

// ============================================================ stats card
static lv_obj_t *statsLayer, *sLevel, *sLevelArc, *sHappy, *sHappyBar, *sNeedBar[4], *sNeedTxt[4], *sNeedImg[4], *sFoot;

static void stats_render() {
  char buf[64];
  snprintf(buf, sizeof(buf), "Level %d", care_level());
  lv_label_set_text(sLevel, buf);
  lv_arc_set_value(sLevelArc, care_level_progress());
  snprintf(buf, sizeof(buf), "%s  " LV_SYMBOL_BULLET "  %d%% happy", care_mood_name(care_mood()), care_happiness());
  lv_label_set_text(sHappy, buf);
  lv_bar_set_value(sHappyBar, care_happiness(), LV_ANIM_OFF);
  for (int i = 0; i < 4; i++) {
    Need n = (Need)i;
    lv_bar_set_value(sNeedBar[i], care_need_enabled(n) ? care_need_level(n) : 0, LV_ANIM_OFF);
    snprintf(buf, sizeof(buf), "%d", care_today(n));
    lv_label_set_text(sNeedTxt[i], buf);
    bool due = care_need_due(n);
    lv_obj_set_style_bg_color(sNeedBar[i], lv_color_hex(due ? C_BAD : care_need_color(n)), LV_PART_INDICATOR);
  }
  char clock[8] = "";
  bool hasClock = care_clock_text(clock, sizeof(clock));
  uint32_t steps = care_steps_today();
  if (steps)
    snprintf(buf, sizeof(buf), "%lu steps%s%s", (unsigned long)steps, care_streak() > 0 ? "  " LV_SYMBOL_BULLET "  " : "",
             care_streak() > 0 ? (String(care_streak()) + "-day streak").c_str() : "");
  else if (care_streak() > 0)
    snprintf(buf, sizeof(buf), "%d-day streak%s%s", care_streak(), hasClock ? "  " LV_SYMBOL_BULLET "  " : "", clock);
  else
    snprintf(buf, sizeof(buf), "%s", hasClock ? clock : "today's breaks on the right");
  lv_label_set_text(sFoot, buf);
}

static void toast_clear();
static void stats_show(bool on) {
  statsOn = on;
  if (on) toast_clear();
  show(statsLayer, on);
  show(img, !on);
  if (on) { show(emote, false); stats_render(); led(RING_LEVEL, C_ACCENT, (uint16_t)(care_happiness() * 10)); }
  else { curEmote = (const lv_image_dsc_t *)1; }      // force the emote to be re-evaluated
}

// ============================================================ activities
static lv_obj_t *actLayer, *aArc, *aTitle, *aPrompt, *aTime, *aHint, *gDot, *gMark;
static const char *const STRETCHES[] = { "Reach up high", "Roll your shoulders back", "Twist left and right", "Tilt your head side to side" };
static const uint32_t EYES_MS = 20000, STRETCH_MS = 60000, MOVE_MIN_MS = 120000;

// catch game
static const int GAME_ROUNDS = 5;
static int gRound = 0, gHits = 0;
static uint32_t gRoundStart = 0, gPauseUntil = 0;
static float gAngle = 180;

static uint32_t game_period() { uint32_t p = 1900; for (int i = 0; i < gRound; i++) p = p * 82 / 100; return p; }

static void act_end() {
  act = A_NONE;
  show(actLayer, false);
  backlight(settings_bright());
  brightNow = settings_bright();
}

static void act_finish(Need n, const char *msg) {
  care_done(n);
  act_end();
  react(P_HAPPY, 3);
  leds_flash(C_OK);
  say(msg);
}

static void game_end() {
  care_played(gHits, GAME_ROUNDS);
  act_end();
  react(P_DASH, 2);
  char buf[32];
  snprintf(buf, sizeof(buf), "%d of %d caught", gHits, GAME_ROUNDS);
  say(buf);
  leds_flash(gHits >= 3 ? C_OK : C_ACCENT);
}

static void act_start(Act a) {
  act = a;
  actStart = millis();
  ring_show(false);
  toast_clear();
  show(emote, false);
  show(actLayer, true);
  show(gDot, a == A_PLAY);
  show(gMark, a == A_PLAY);
  show(aArc, a != A_PLAY && a != A_MOVE);
  lv_label_set_text(aHint, a == A_MOVE ? "press when you're back" : a == A_PLAY ? "hold to stop" : "press: done  " LV_SYMBOL_BULLET "  hold: stop");
  switch (a) {
    case A_EYES:    lv_label_set_text(aTitle, "Eye break"); lv_label_set_text(aPrompt, "Look at something 20 feet away");
                    lv_obj_set_style_arc_color(aArc, lv_color_hex(care_need_color(NEED_EYES)), LV_PART_INDICATOR); break;
    case A_STRETCH: lv_label_set_text(aTitle, "Stretch with Wisp"); lv_label_set_text(aPrompt, STRETCHES[0]);
                    lv_obj_set_style_arc_color(aArc, lv_color_hex(care_need_color(NEED_STRETCH)), LV_PART_INDICATOR); break;
    case A_MOVE:    lv_label_set_text(aTitle, "Out for a walk"); lv_label_set_text(aPrompt, "Get up and move about"); break;
    case A_PLAY:    lv_label_set_text(aTitle, ""); gRound = 0; gHits = 0; gRoundStart = millis(); gPauseUntil = 0; break;
    default: break;
  }
}

static void act_tick() {
  uint32_t now = millis(), el = now - actStart;
  char buf[48];
  switch (act) {
    case A_EYES:
    case A_STRETCH: {
      uint32_t total = act == A_EYES ? EYES_MS : STRETCH_MS;
      if (el >= total) {
        act == A_EYES ? act_finish(NEED_EYES, "Eyes rested") : act_finish(NEED_STRETCH, "Nice stretch");
        return;
      }
      uint32_t left = (total - el + 999) / 1000;
      snprintf(buf, sizeof(buf), "%lu", (unsigned long)left);
      lv_label_set_text(aTime, buf);
      lv_arc_set_value(aArc, (int)((total - el) * 1000 / total));
      if (act == A_STRETCH) lv_label_set_text(aPrompt, STRETCHES[min<uint32_t>(el / 15000, 3)]);
      led(RING_LEVEL, care_need_color(act == A_EYES ? NEED_EYES : NEED_STRETCH), (uint16_t)((total - el) * 1000 / total));
      break;
    }
    case A_MOVE: {
      snprintf(buf, sizeof(buf), "%lu:%02lu", (unsigned long)(el / 60000), (unsigned long)(el / 1000 % 60));
      lv_label_set_text(aTime, buf);
      led(RING_BREATHE, care_need_color(NEED_MOVE), 0, 2400);
      bool dim = el > 20000 && now - lastInput > 20000;      // nobody's looking: save the screen
      int want = dim ? BACKLIGHT_ASLEEP : settings_bright();
      if (want != brightNow) { backlight(want); brightNow = want; }
      break;
    }
    case A_PLAY: {
      if (gPauseUntil && now < gPauseUntil) break;           // frozen to show the result of a press
      if (gPauseUntil) { gPauseUntil = 0; gRoundStart = now; }
      uint32_t per = game_period();
      uint32_t t = now - gRoundStart;
      if (t > per * 3) {                                     // three laps without a press: a miss
        gRound++;
        leds_flash(C_BAD);
        if (gRound >= GAME_ROUNDS) { game_end(); return; }
        gRoundStart = now;
        t = 0;
      }
      gAngle = fmodf(180.0f + 360.0f * t / per, 360.0f);    // starts at the bottom, runs clockwise
      {
        float r = (gAngle - 90) * 3.14159265f / 180;
        lv_obj_align(gDot, LV_ALIGN_CENTER, (int)(158 * cosf(r)), (int)(158 * sinf(r)));
      }
      snprintf(buf, sizeof(buf), "Round %d of %d  " LV_SYMBOL_BULLET "  %d caught", gRound + 1, GAME_ROUNDS, gHits);
      lv_label_set_text(aPrompt, buf);
      lv_label_set_text(aTime, "");
      led(RING_POSITION, 0xffffff, (uint16_t)(gAngle / 360.0f * 1000));
      break;
    }
    default: break;
  }
}

static void act_press() {
  uint32_t el = millis() - actStart;
  switch (act) {
    case A_EYES:
      if (el >= 15000) act_finish(NEED_EYES, "Eyes rested");
      else { act_end(); say("Eye break stopped"); }
      break;
    case A_STRETCH:
      if (el >= 30000) act_finish(NEED_STRETCH, "Nice stretch");
      else { act_end(); say("Stretch stopped"); }
      break;
    case A_MOVE:
      if (el >= MOVE_MIN_MS) act_finish(NEED_MOVE, "Welcome back");
      else { act_end(); say("Back already? Not counted"); }
      break;
    case A_PLAY: {
      if (gPauseUntil) break;
      float d = fminf(gAngle, 360 - gAngle);
      bool hit = d <= 24;
      if (hit) gHits++;
      leds_flash(hit ? C_OK : C_BAD);
      gRound++;
      if (gRound >= GAME_ROUNDS) game_end();
      else gPauseUntil = millis() + 600;
      break;
    }
    default: break;
  }
}

// ============================================================ ring actions
static void ring_do(int i) {
  switch (i) {
    case R_WATER:
      care_done(NEED_WATER);
      ring_show(false);
      react(P_HAPPY, 3);
      leds_flash(care_need_color(NEED_WATER));
      { char b[32]; snprintf(b, sizeof(b), "Glass %d today", care_today(NEED_WATER)); say(b); }
      break;
    case R_EYES: act_start(A_EYES); break;
    case R_STRETCH: act_start(A_STRETCH); break;
    case R_MOVE: act_start(A_MOVE); break;
    case R_PLAY: act_start(A_PLAY); break;
    case R_STATS: ring_show(false); stats_show(true); break;
    case R_BED:
      care_set_bedtime(!care_bedtime());
      ring_show(false);
      say(care_bedtime() ? "Goodnight" : "Good morning");
      break;
    case R_PAUSE:
      if (care_paused()) { care_pause(0); say("Reminders back on"); }
      else { care_pause(60); say("Paused for an hour"); }
      ring_show(false);
      break;
    case R_BACK: ring_show(false); break;
  }
}

void pet_open_ring() {
  lastInput = millis();
  if (!act) { stats_show(false); ring_show(true); }
}

// ============================================================ status (clock, date, icons, next break)
static ClockFace *clockFace;
static lv_obj_t *stLayer, *stRow, *stDate, *stWifi, *stFlag, *stNext;
static Settings stCfg;
static uint32_t stCfgVer = 0;

static void st_set(lv_obj_t *l, const char *txt, uint32_t col) {
  if (!txt) { lv_obj_add_flag(l, LV_OBJ_FLAG_HIDDEN); return; }
  lv_obj_remove_flag(l, LV_OBJ_FLAG_HIDDEN);
  lv_label_set_text(l, txt);
  lv_obj_set_style_text_color(l, lv_color_hex(col), 0);
}

static void status_render() {
  if (settings_version() != stCfgVer) {
    settings_get(stCfg); stCfgVer = settings_version();
    clockface_set(clockFace, stCfg.clock_style, stCfg.clock_mode);
    // the date/icon row sits under the small clock, or at the very top when the clock is big or off
    bool small = stCfg.clock_mode && (stCfg.clock_style == CS_CLASSIC || stCfg.clock_style == CS_RIM);
    lv_obj_align(stRow, LV_ALIGN_CENTER, 0, small ? -124 : -146);
  }
  clockface_update(clockFace);
  char buf[48];
  // date
  if (stCfg.show_date && care_clock_format(buf, sizeof(buf), "%a %b %d")) {
    size_t n = strlen(buf);
    if (n >= 2 && buf[n - 2] == '0') { buf[n - 2] = buf[n - 1]; buf[n - 1] = 0; }     // "Nov 01" -> "Nov 1"
    st_set(stDate, buf, C_SUB);
  } else st_set(stDate, nullptr, 0);
  // icons
  if (stCfg.show_status && stCfg.ssid[0]) {
    bool up = WiFi.status() == WL_CONNECTED;
    int rssi = up ? WiFi.RSSI() : -100;
    st_set(stWifi, LV_SYMBOL_WIFI, !up ? C_BAD : rssi < -75 ? 0xffc457 : C_SUB);
  } else st_set(stWifi, nullptr, 0);
  if (stCfg.show_status && care_paused()) st_set(stFlag, LV_SYMBOL_PAUSE, C_ACCENT);
  else if (stCfg.show_status && care_bedtime()) st_set(stFlag, LV_SYMBOL_EYE_CLOSE, C_ACCENT);
  else st_set(stFlag, nullptr, 0);
  // next break
  if (!stCfg.show_next) { st_set(stNext, nullptr, 0); return; }
  int u = care_most_urgent();
  if (care_mood() == MOOD_ASLEEP) {
    if (care_bedtime() || care_clock_valid()) { snprintf(buf, sizeof(buf), "Asleep until %d:00", stCfg.hour_start); st_set(stNext, buf, C_DIM); }
    else st_set(stNext, nullptr, 0);
  } else if (care_paused()) {
    snprintf(buf, sizeof(buf), "Paused, %lu min left", (unsigned long)((care_pause_left_s() + 59) / 60));
    st_set(stNext, buf, C_DIM);
  } else if (u >= 0) {
    snprintf(buf, sizeof(buf), "%s is due", care_need_name((Need)u));
    st_set(stNext, buf, care_need_color((Need)u));
  } else {
    int best = -1; uint32_t left = 0;
    for (int i = 0; i < NEED_COUNT; i++) {
      if (!care_need_enabled((Need)i)) continue;
      uint32_t l = care_need_left_s((Need)i);
      if (best < 0 || l < left) { best = i; left = l; }
    }
    if (best < 0) { st_set(stNext, nullptr, 0); return; }
    if (left >= 3600) snprintf(buf, sizeof(buf), "%s in %luh %02lum", care_need_name((Need)best), (unsigned long)(left / 3600), (unsigned long)(left / 60 % 60));
    else snprintf(buf, sizeof(buf), "%s in %lu min", care_need_name((Need)best), (unsigned long)((left + 59) / 60));
    st_set(stNext, buf, C_DIM);
  }
}

// ============================================================ screen
static void pat() {
  if (care_mood() == MOOD_ASLEEP) { say("Shh, Wisp is asleep"); return; }
  bool counted = care_pet();
  react(P_HAPPY, 2);
  if (counted) say("Wisp likes that", 1500);
}

void pet_create() {
  scr = lv_obj_create(nullptr);
  lv_obj_set_style_bg_color(scr, lv_color_hex(PET_BG), 0);
  lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
  lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);

  clockFace = clockface_create(scr);         // behind everything else
  img = lv_image_create(scr);
  lv_obj_remove_flag(img, LV_OBJ_FLAG_CLICKABLE);
  emote = lv_image_create(scr);
  lv_obj_remove_flag(emote, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_add_flag(emote, LV_OBJ_FLAG_HIDDEN);
  lv_anim_t a;
  lv_anim_init(&a);
  lv_anim_set_var(&a, emote);
  lv_anim_set_exec_cb(&a, bob_cb);
  lv_anim_set_values(&a, -3, 3);
  lv_anim_set_duration(&a, 900);
  lv_anim_set_playback_duration(&a, 900);
  lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
  lv_anim_set_path_cb(&a, lv_anim_path_ease_in_out);
  lv_anim_start(&a);

  // status: clock on top, date and icons under it, next break at the bottom
  stLayer = mk_layer();
  show(stLayer, true);
  stRow = lv_obj_create(stLayer);
  lv_obj_remove_style_all(stRow);
  lv_obj_set_size(stRow, 200, 20);
  lv_obj_align(stRow, LV_ALIGN_CENTER, 0, -124);
  lv_obj_set_flex_flow(stRow, LV_FLEX_FLOW_ROW);
  lv_obj_set_flex_align(stRow, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
  lv_obj_set_style_pad_column(stRow, 10, 0);
  lv_obj_remove_flag(stRow, LV_OBJ_FLAG_SCROLLABLE);
  lv_obj_remove_flag(stRow, LV_OBJ_FLAG_CLICKABLE);    // taps fall through to the screen (a pat)
  lv_obj_t **rowItems[] = { &stDate, &stWifi, &stFlag };
  for (auto pp : rowItems) {
    *pp = lv_label_create(stRow);
    lv_obj_set_style_text_font(*pp, &lv_font_montserrat_14, 0);
    lv_obj_add_flag(*pp, LV_OBJ_FLAG_HIDDEN);
  }
  stNext = mk_label(stLayer, &lv_font_montserrat_14, 146, 200, C_DIM);

  // care ring
  ringLayer = mk_layer();
  for (int i = 0; i < R_COUNT; i++) {
    lv_obj_t *b = lv_obj_create(ringLayer);
    lv_obj_remove_style_all(b);
    lv_obj_set_style_radius(b, LV_RADIUS_CIRCLE, 0);
    lv_obj_set_style_bg_opa(b, LV_OPA_COVER, 0);
    lv_obj_remove_flag(b, LV_OBJ_FLAG_SCROLLABLE);
    ringBg[i] = b;
    ringImg[i] = lv_image_create(b);
    lv_image_set_src(ringImg[i], RING_ICON[i]);
    lv_obj_set_style_image_recolor_opa(ringImg[i], LV_OPA_COVER, 0);
    lv_obj_center(ringImg[i]);
    if (i < 4) {
      ringMeter[i] = mk_arc(b, 44, 3, care_need_color((Need)i));
      lv_arc_set_bg_angles(ringMeter[i], 0, 360);
      lv_arc_set_rotation(ringMeter[i], 270);
      lv_arc_set_range(ringMeter[i], 0, 100);
      lv_obj_center(ringMeter[i]);
    }
  }
  cIcon = lv_image_create(ringLayer);
  lv_obj_set_style_image_recolor_opa(cIcon, LV_OPA_COVER, 0);
  lv_image_set_src(cIcon, &icon_water);
  lv_image_set_scale(cIcon, 220);
  lv_obj_align(cIcon, LV_ALIGN_CENTER, 0, -38);
  cName = mk_label(ringLayer, &lv_font_montserrat_28, 22, 200, C_TEXT);
  cDetail = mk_label(ringLayer, &lv_font_montserrat_14, 56, 200, C_SUB, true);

  // activities
  actLayer = mk_layer();
  aArc = mk_arc(actLayer, 340, 8, C_ACCENT);
  lv_arc_set_bg_angles(aArc, 0, 360);
  lv_arc_set_rotation(aArc, 270);
  lv_arc_set_range(aArc, 0, 1000);
  lv_obj_center(aArc);
  aTitle = mk_label(actLayer, &lv_font_montserrat_14, -142, 200, C_ACCENT);
  aPrompt = mk_label(actLayer, &lv_font_montserrat_20, -104, 250, C_TEXT, true);
  aTime = mk_label(actLayer, &lv_font_montserrat_28, 114, 160, 0xffffff);
  aHint = mk_label(actLayer, &lv_font_montserrat_14, 142, 200, C_DIM);
  gDot = lv_obj_create(actLayer);
  lv_obj_remove_style_all(gDot);
  lv_obj_set_size(gDot, 16, 16);
  lv_obj_set_style_radius(gDot, LV_RADIUS_CIRCLE, 0);
  lv_obj_set_style_bg_color(gDot, lv_color_white(), 0);
  lv_obj_set_style_bg_opa(gDot, LV_OPA_COVER, 0);
  gMark = mk_label(actLayer, &lv_font_montserrat_20, -136, 40, C_OK);
  lv_label_set_text(gMark, LV_SYMBOL_UP);

  // stats
  statsLayer = mk_layer();
  lv_obj_set_style_bg_color(statsLayer, lv_color_hex(PET_BG), 0);
  lv_obj_set_style_bg_opa(statsLayer, LV_OPA_COVER, 0);
  sLevelArc = mk_arc(statsLayer, 344, 6, C_ACCENT);
  lv_arc_set_bg_angles(sLevelArc, 0, 360);
  lv_arc_set_rotation(sLevelArc, 270);
  lv_arc_set_range(sLevelArc, 0, 100);
  lv_obj_center(sLevelArc);
  lv_obj_t *t = mk_label(statsLayer, &lv_font_montserrat_14, -132, 160, C_ACCENT);
  lv_label_set_text(t, "Wisp");
  sLevel = mk_label(statsLayer, &lv_font_montserrat_28, -104, 200, C_TEXT);
  sHappy = mk_label(statsLayer, &lv_font_montserrat_14, -70, 240, C_SUB);
  sHappyBar = lv_bar_create(statsLayer);
  lv_obj_set_size(sHappyBar, 220, 8);
  lv_obj_align(sHappyBar, LV_ALIGN_CENTER, 0, -50);
  lv_obj_set_style_bg_color(sHappyBar, lv_color_hex(C_PILL), 0);
  lv_obj_set_style_bg_color(sHappyBar, lv_color_hex(0xff5a8c), LV_PART_INDICATOR);
  static const lv_image_dsc_t *const NI[4] = { &icon_water, &icon_eyes, &icon_stretch, &icon_move };
  for (int i = 0; i < 4; i++) {
    int y = -14 + i * 32;
    sNeedImg[i] = lv_image_create(statsLayer);
    lv_image_set_src(sNeedImg[i], NI[i]);
    lv_image_set_scale(sNeedImg[i], 80);
    lv_obj_set_style_image_recolor(sNeedImg[i], lv_color_hex(care_need_color((Need)i)), 0);
    lv_obj_set_style_image_recolor_opa(sNeedImg[i], LV_OPA_COVER, 0);
    lv_obj_align(sNeedImg[i], LV_ALIGN_CENTER, -112, y);
    sNeedBar[i] = lv_bar_create(statsLayer);
    lv_obj_set_size(sNeedBar[i], 160, 8);
    lv_obj_align(sNeedBar[i], LV_ALIGN_CENTER, -6, y);
    lv_obj_set_style_bg_color(sNeedBar[i], lv_color_hex(C_PILL), 0);
    lv_bar_set_range(sNeedBar[i], 0, 100);
    sNeedTxt[i] = lv_label_create(statsLayer);
    lv_obj_set_style_text_font(sNeedTxt[i], &lv_font_montserrat_20, 0);
    lv_obj_set_style_text_color(sNeedTxt[i], lv_color_hex(C_TEXT), 0);
    lv_obj_align(sNeedTxt[i], LV_ALIGN_CENTER, 104, y);
  }
  sFoot = mk_label(statsLayer, &lv_font_montserrat_14, 122, 220, C_SUB);

  toast = lv_label_create(scr);
  lv_obj_set_style_text_font(toast, &lv_font_montserrat_20, 0);
  lv_obj_set_style_text_color(toast, lv_color_hex(C_ACCENT), 0);
  lv_obj_set_style_text_align(toast, LV_TEXT_ALIGN_CENTER, 0);
  lv_obj_set_width(toast, 210);                      // the screen is only ~220 px wide down there
  lv_label_set_long_mode(toast, LV_LABEL_LONG_WRAP);
  lv_obj_align(toast, LV_ALIGN_CENTER, 0, 112);
  lv_obj_set_style_opa(toast, LV_OPA_TRANSP, 0);

  timer = lv_timer_create(frame_cb, 83, nullptr);
  set_pose(P_CONTENT);
  lastInput = millis();
  lastEvent = care_event();

  static Settings st; settings_get(st);
  if (crashlog_bad()) say("Restarted unexpectedly. See Settings > Last restart", 9000);
  else if (!st.ssid[0]) say("Press for the menu", 7000);
}

lv_obj_t *pet_screen() { return scr; }

void pet_enter() {
  ledMode = (RingMode)255;          // re-send the ring state
  lv_timer_resume(timer);
}

bool pet_is_idle() { return !act && !statsOn && !ringOn; }   // the bare home screen

void pet_remote_done(int need) {
  if (need < 0 || need >= NEED_COUNT) return;
  leds_flash(care_need_color((Need)need));
  if (act || statsOn || ringOn) return;
  react(P_HAPPY, 2);
  char b[48];
  snprintf(b, sizeof(b), "%s logged on your phone", care_need_name((Need)need));
  say(b);
}

// Any input anywhere keeps Wisp awake.
void pet_activity() { lastInput = millis(); }

static bool dim_now() {
  uint32_t now = millis();
  if (act == A_MOVE) return brightNow == BACKLIGHT_ASLEEP;
  if (now < cueUntil) return false;
  uint32_t idle = now - lastInput;
  if (care_mood() == MOOD_ASLEEP) return idle > 30000;
  uint32_t nap = settings_nap_min();
  return nap > 0 && idle > nap * 60000UL;
}

void pet_input(InputEvent e) {
  uint32_t now = millis();
  bool wasDim = dim_now() && act != A_MOVE;
  lastInput = now;
  if (wasDim) return;                              // the first input only wakes the screen
  bool bare = !act && !statsOn && !ringOn;
  if (e == IN_TAP) {
    if (bare) {                                    // only the knob opens the menu
      // Wisp asking for something: the tap answers it (a glass logged, or that activity started)
      int u = care_paused() || care_mood() == MOOD_ASLEEP ? -1 : care_most_urgent();
      static const RingItem FOR_NEED[NEED_COUNT] = { R_WATER, R_EYES, R_STRETCH, R_MOVE };
      if (u >= 0) ring_do(FOR_NEED[u]);
      else pat();
      return;
    }
    e = IN_PRESS;                                  // over anything else a tap works like a press
  }
  if (act) {                                       // eye break, stretch, walk, game
    if (e == IN_PRESS) act_press();
    else if (e == IN_HOLD) { act_end(); say("Stopped"); }
    return;
  }
  if (statsOn) {
    if (e == IN_PRESS || e == IN_HOLD) stats_show(false);
    return;
  }
  if (ringOn) {
    if (e == IN_CW || e == IN_CCW) {
      ringSel = (ringSel + (e == IN_CW ? 1 : R_COUNT - 1)) % R_COUNT;
      ringInput = now;
      ring_render();
    } else if (e == IN_PRESS) ring_do(ringSel);
    else if (e == IN_HOLD) ring_show(false);
    return;
  }
  switch (e) {
    case IN_CW:
    case IN_CCW: ring_show(true); break;
    case IN_PRESS: lv_timer_pause(timer); app_show_hub(); break;
    case IN_HOLD: say("Press for the menu", 2000); break;
    default: break;
  }
}

void pet_tick() {
  care_tick();
  if (lv_screen_active() != scr) return;
  uint32_t now = millis();

  // a need just fell due (or has stayed overdue another 15 min): wake up and say so
  if (care_event() != lastEvent) {
    lastEvent = care_event();
    int u = care_most_urgent();
    if (u >= 0 && care_mood() != MOOD_ASLEEP && !act) {
      cueUntil = now + 60000;
      leds_flash(care_need_color((Need)u));
      char b[40];
      snprintf(b, sizeof(b), "Time for %s", u == NEED_EYES ? "an eye break" : u == NEED_WATER ? "water" : u == NEED_STRETCH ? "a stretch" : "a walk");
      say(b, 6000);
    }
  }

  if (act) act_tick();
  if (ringOn && now - ringInput > RING_IDLE_MS) ring_show(false);
  if (ringOn) {
    static uint32_t lastRing = 0;
    if (now - lastRing > 1000) { lastRing = now; ring_render(); }
  }
  if (statsOn) {
    static uint32_t lastStats = 0;
    if (now - lastStats > 1000) { lastStats = now; stats_render(); }
  }
  bool stOn = !ringOn && !statsOn && !act;
  if (stOn != !lv_obj_has_flag(stLayer, LV_OBJ_FLAG_HIDDEN)) { show(stLayer, stOn); clockface_show(clockFace, stOn); }
  if (stOn) {
    static uint32_t lastSt = 0;
    if (now - lastSt > 1000 || !lastSt) { lastSt = now; status_render(); }
  }

  // what Wisp is doing
  Mood m = care_mood();
  bool dim = dim_now();
  int p;
  if (act == A_EYES) p = P_ASLEEP;
  else if (act == A_STRETCH) p = P_BOUNCE;
  else if (act == A_MOVE || act == A_PLAY) p = P_DASH;
  else if (now < reactionUntil) p = reactionPose;
  else if (dim && (m == MOOD_CONTENT || m == MOOD_HAPPY)) p = P_ASLEEP;      // dozing at the desk
  else if (m == MOOD_RESTLESS) p = care_most_urgent() == NEED_MOVE ? P_MOVE : P_STRETCH;
  else p = MOOD_POSE[m];
  set_pose(p);

  // emote bubble
  const lv_image_dsc_t *e = nullptr;
  if (!act && !statsOn && !ringOn) {
    e = POSES[p].emote;
    if (p == P_HAPPY && now >= reactionUntil) e = nullptr;   // the heart means "you just did something"
  }
  set_emote(e);

  // screen brightness
  if (act != A_MOVE) {
    int want = dim ? BACKLIGHT_ASLEEP : settings_bright();
    if (want != brightNow) { backlight(want); brightNow = want; }
  }

  // LED ring, when nothing else owns it
  if (!ringOn && !statsOn && !act) {
    int u = care_most_urgent();
    if (m == MOOD_ASLEEP) led(RING_OFF, 0);
    else if (m == MOOD_MAD) led(RING_BREATHE, 0xff4040, 0, 900);
    else if (u >= 0) led(RING_BREATHE, care_need_color((Need)u), 0, care_need_overdue_s((Need)u) > 600 ? 1300 : 2200);
    else if (dim) led(RING_BREATHE, 0x2a4a8a, 0, 7000);
    else led(RING_BREATHE, 0x4cc8ff, 0, 4000);
  }

  if (toastUntil && now > toastUntil) { toastUntil = 0; lv_obj_fade_out(toast, 400, 0); }
}
