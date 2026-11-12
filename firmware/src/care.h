// Wisp's care model. The needs are yours: water, eye breaks, stretching and moving. Each runs down on a
// timer that only advances during active hours (and not while paused or at bedtime). Doing the thing and
// logging it on the dial refills it. Happiness, XP and daily counts make it a little game.
#pragma once
#include <Arduino.h>
#include <time.h>

enum Need : uint8_t { NEED_WATER, NEED_EYES, NEED_STRETCH, NEED_MOVE, NEED_COUNT };

enum Mood : uint8_t {
  MOOD_CONTENT,
  MOOD_HAPPY,
  MOOD_THIRSTY,     // water due
  MOOD_TIRED,       // eye break due
  MOOD_RESTLESS,    // stretch or move due
  MOOD_SAD,         // neglected for a while
  MOOD_MAD,         // neglected a long time
  MOOD_ASLEEP,      // bedtime or outside active hours
  MOOD_COUNT
};

void care_begin();
void care_tick();                       // call from the main loop; advances once a second

// needs
bool care_need_enabled(Need n);
int care_need_level(Need n);            // 100 = just done .. 0 = due
bool care_need_due(Need n);
uint32_t care_need_overdue_s(Need n);
uint32_t care_need_left_s(Need n);      // until due
int care_most_urgent();                 // the most overdue due need, or -1
const char *care_need_name(Need n);
uint32_t care_need_color(Need n);

// state
Mood care_mood();
const char *care_mood_name(Mood m);
int care_happiness();                   // 0-100
int care_level();
int care_level_progress();              // 0-100 within the level
int care_today(Need n);
int care_streak();                      // days in a row with at least 4 breaks logged
bool care_active();                     // timers running right now
bool care_in_hours();
bool care_paused();
uint32_t care_pause_left_s();
bool care_bedtime();
uint32_t care_change();                 // bumps on anything you did, and on a new day (for the phone link)
uint32_t care_event();                  // bumps when a need falls due, and again every 15 min it stays overdue
uint32_t care_last_done_ms();           // millis() of the last logged break (for a happy reaction)

// clock
bool care_clock_valid();
// A trusted UTC time from elsewhere (Home Assistant's HTTP Date header). Used when NTP hasn't set the clock,
// or it has drifted by more than two minutes.
void care_clock_hint(time_t utc);
bool care_clock_parse_http_date(const char *s, time_t &utc);    // "Sun, 02 Nov 2026 05:51:00 GMT"
bool care_clock_text(char *buf, size_t n);       // "14:05"
bool care_clock_format(char *buf, size_t n, const char *strftime_fmt);
const char *care_tz_name(int i);
int care_tz_count();

// actions
void care_done(Need n);                 // you did it (and whatever it covers, see care_covers)
uint8_t care_covers(Need n);            // a bit per need that doing n also checks off (Move: Stretch and Eyes)
bool care_pet();                        // a pat on the screen; false while on cooldown
void care_played(int hits, int rounds);
void care_pause(int minutes);           // 0 = resume
void care_set_bedtime(bool on);
// Sit/stand alternation (optional, Settings > Reminders). Not a need: it never affects the mood. The timer
// counts active time in the current posture; when it reaches that posture's limit it's "due" and cues like a
// need. You say when you switch (there's no sensor).
bool care_posture_on();
bool care_standing();
bool care_posture_due();
uint32_t care_posture_elapsed_s();
uint32_t care_posture_interval_s();     // the limit for the current posture
uint32_t care_posture_left_s();
void care_posture_set(bool standing);   // you switched
int care_stands_today();
uint32_t care_posture_color();
// A break done ageS seconds ago, logged on the phone. Scored for how due it was then; it only resets the
// timer if nothing more recent was logged here.
void care_done_at(Need n, uint32_t ageS);

// for the phone app
uint32_t care_need_elapsed_s(Need n);
uint32_t care_need_interval_s(Need n);
uint32_t care_xp();
// steps, from the phone (its step counter); kept per local day
void care_set_steps(int32_t day, uint32_t steps);
uint32_t care_steps_today();            // 0 if none for today
int32_t care_today_date();              // local yyyymmdd, 0 without a clock

// ---------- travel ----------
// The companion lives on one device at a time: this dial, a phone, another dial. Each has an id (made by
// whichever device first had it) and an epoch that goes up by one at every move, so wherever two devices
// disagree about where it is, the higher epoch is the newer news. While it's away the dial keeps the state
// it left with, frozen, and shows where it went.
struct CareSnapshot {
  uint32_t elapsed[NEED_COUNT];
  float happy;
  uint32_t xp;
  uint16_t today[NEED_COUNT];
  int32_t day;
  uint16_t streak;
  bool bedtime;
  bool standing;
  uint32_t postSec;
  uint16_t standsToday;
  uint32_t pauseS;                      // pause left, seconds
};
const char *care_cid();
uint32_t care_epoch();
bool care_here();
const char *care_where();               // when away: who has it ("Pixel 8", "Deskling-3456")
void care_snapshot(CareSnapshot &out);
uint32_t care_leave(const char *to);    // it goes with `to`: freezes here, returns the new epoch
void care_arrive(const char *cid, uint32_t epoch, const CareSnapshot &in);   // it moves in, at that epoch
void care_set_where(uint32_t epoch, const char *at);    // news of where it went since (if newer)
