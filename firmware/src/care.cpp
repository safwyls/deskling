#include "care.h"
#include "config.h"
#include "settings.h"
#include <time.h>
#include <Preferences.h>
#include <WiFi.h>
#include <sys/time.h>

static const char *const NEED_NAME[NEED_COUNT] = { "Water", "Eye break", "Stretch", "Move" };
static const uint32_t NEED_COLOR[NEED_COUNT] = { 0x3fa9ff, 0xffc457, 0x5cf08a, 0xc77dff };
static const char *const MOOD_NAME[MOOD_COUNT] = { "Content", "Happy", "Thirsty", "Tired", "Restless", "Sad", "Mad", "Asleep" };

struct Tz { const char *name, *posix; };
static const Tz TZS[] = {
  { "US Pacific", "PST8PDT,M3.2.0,M11.1.0" }, { "US Mountain", "MST7MDT,M3.2.0,M11.1.0" },
  { "Arizona", "MST7" },                      { "US Central", "CST6CDT,M3.2.0,M11.1.0" },
  { "US Eastern", "EST5EDT,M3.2.0,M11.1.0" }, { "UTC", "UTC0" },
  { "UK", "GMT0BST,M3.5.0/1,M10.5.0" },       { "Central Europe", "CET-1CEST,M3.5.0,M10.5.0/3" },
};
static const int TZ_COUNT = sizeof(TZS) / sizeof(TZS[0]);

// What survives a reboot
struct Saved {
  uint32_t magic;
  uint32_t elapsed[NEED_COUNT];   // active seconds since each was last done
  float happy;
  uint32_t xp;
  uint16_t today[NEED_COUNT];
  int32_t day;                    // local yyyymmdd the counts belong to (0 = no clock yet)
  uint16_t streak;
  uint8_t bedtime;
};
static const uint32_t MAGIC = 0xD35C0002;
static Saved st;

static Preferences prefs;
static uint32_t lastSecond = 0, lastSave = 0;
static bool dirty = false;
static int32_t stepsDay = 0;               // steps from the phone, for one local day
static uint32_t stepsCount = 0;
static uint32_t pauseUntil = 0;            // millis
static uint32_t lastPet = 0, lastDone = 0;
static volatile uint32_t eventCount = 0;
static bool wasDue[NEED_COUNT];
static uint32_t lastCueMs = 0;             // millis() of the last cue, for pacing
static uint8_t remind[NEED_COUNT], hourStart = 8, hourEnd = 20, tzIdx = 0;
static uint32_t cfgVer = 0;
// Sit/stand alternation: not a need (no mood, nothing runs down), just a nudge when it's time to switch.
static bool postureOn = false, standing = false, postWasDue = false;
static uint8_t sitMin = 45, standMin = 15;
static uint32_t postSec = 0;               // active seconds in the current posture
static uint16_t standsToday = 0;
static bool ntpStarted = false, wasInHours = true;   // true: a reboot during the day keeps a bedtime you set
// Where the companion is (see care.h, "travel")
static char cid[17], where[33];
static uint32_t epoch = 1;
static bool here = true;
// Woken by hand outside active hours: awake (timers running) until the hours next begin, or bedtime
static bool wokeEarly = false;

static void save() {
  prefs.putBytes("state", &st, sizeof(st));
  prefs.putBool("p_st", standing);
  prefs.putUInt("p_sec", postSec);
  prefs.putUShort("p_cnt", standsToday);
  dirty = false;
  lastSave = millis();
}

static void apply_tz() {
  setenv("TZ", TZS[tzIdx < TZ_COUNT ? tzIdx : 0].posix, 1);
  tzset();
}

static void load_settings() {
  static Settings s;
  settings_get(s);
  cfgVer = settings_version();
  for (int i = 0; i < NEED_COUNT; i++) remind[i] = s.remind_min[i];
  hourStart = s.hour_start; hourEnd = s.hour_end;
  postureOn = s.posture_on; sitMin = s.sit_min ? s.sit_min : 45; standMin = s.stand_min ? s.stand_min : 15;
  if (s.tz != tzIdx) { tzIdx = s.tz; apply_tz(); }
}

void care_begin() {
  prefs.begin("care", false);
  if (prefs.getBytesLength("state") != sizeof(st) || prefs.getBytes("state", &st, sizeof(st)) != sizeof(st) || st.magic != MAGIC) {
    memset(&st, 0, sizeof(st));
    st.magic = MAGIC;
    st.happy = 70;
  }
  tzIdx = 255;
  load_settings();
  stepsDay = prefs.getInt("stepsDay", 0);
  stepsCount = prefs.getUInt("steps", 0);
  standing = prefs.getBool("p_st", false);
  postSec = prefs.getUInt("p_sec", 0);
  standsToday = prefs.getUShort("p_cnt", 0);
  if (prefs.getString("cid", cid, sizeof(cid)) != 16) {
    // a new dial: its own companion, here
    snprintf(cid, sizeof(cid), "%08lx%08lx", (unsigned long)esp_random(), (unsigned long)esp_random());
    prefs.putString("cid", cid);
    prefs.putUInt("epoch", epoch);
    prefs.putBool("here", true);
  }
  epoch = prefs.getUInt("epoch", 1);
  here = prefs.getBool("here", true);
  wokeEarly = prefs.getBool("woke", false);
  if (prefs.getString("where", where, sizeof(where)) == 0) where[0] = 0;
  for (int i = 0; i < NEED_COUNT; i++) wasDue[i] = care_need_due((Need)i);
  lastSecond = millis();
}

// ---------- clock ----------
bool care_clock_valid() { return time(nullptr) > 1700000000; }

static bool local_now(struct tm &t) {
  if (!care_clock_valid()) return false;
  time_t now = time(nullptr);
  localtime_r(&now, &t);
  return true;
}

bool care_clock_text(char *buf, size_t n) {
  struct tm t;
  if (!local_now(t)) return false;
  snprintf(buf, n, "%02d:%02d", t.tm_hour, t.tm_min);
  return true;
}

bool care_clock_format(char *buf, size_t n, const char *fmt) {
  struct tm t;
  if (!local_now(t)) return false;
  strftime(buf, n, fmt, &t);
  return true;
}

// days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm): no timegm() needed
static long days_from_civil(int y, int m, int d) {
  y -= m <= 2;
  long era = (y >= 0 ? y : y - 399) / 400;
  long yoe = y - era * 400;
  long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
  long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
  return era * 146097 + doe - 719468;
}

bool care_clock_parse_http_date(const char *s, time_t &utc) {
  static const char *const MON = "JanFebMarAprMayJunJulAugSepOctNovDec";
  char wd[4], mon[4];
  int d, y, hh, mm, ss;
  if (sscanf(s, "%3s, %d %3s %d %d:%d:%d", wd, &d, mon, &y, &hh, &mm, &ss) != 7) return false;
  const char *p = strstr(MON, mon);
  if (!p || (p - MON) % 3) return false;
  int m = (int)(p - MON) / 3 + 1;
  if (y < 2024 || d < 1 || d > 31 || hh > 23 || mm > 59 || ss > 60) return false;
  utc = (time_t)(days_from_civil(y, m, d) * 86400L + hh * 3600L + mm * 60L + ss);
  return true;
}

void care_clock_hint(time_t utc) {
  time_t now = time(nullptr);
  if (care_clock_valid() && labs((long)(now - utc)) < 120) return;
  struct timeval tv = { utc, 0 };
  settimeofday(&tv, nullptr);
  apply_tz();
}

const char *care_tz_name(int i) { return TZS[constrain(i, 0, TZ_COUNT - 1)].name; }
int care_tz_count() { return TZ_COUNT; }

static bool raw_in_hours();
bool care_in_hours() { return wokeEarly || raw_in_hours(); }
bool care_woke_early() { return wokeEarly; }

static bool raw_in_hours() {
  struct tm t;
  if (!local_now(t) || hourStart == hourEnd) return true;      // no clock yet: don't hold the reminders back
  int h = t.tm_hour;
  return hourStart < hourEnd ? (h >= hourStart && h < hourEnd) : (h >= hourStart || h < hourEnd);
}

// ---------- needs ----------
static uint32_t interval_s(Need n) { return (uint32_t)remind[n] * 60; }
bool care_need_enabled(Need n) { return remind[n] > 0; }
bool care_need_due(Need n) { return care_need_enabled(n) && st.elapsed[n] >= interval_s(n); }
uint32_t care_need_overdue_s(Need n) { return care_need_due(n) ? st.elapsed[n] - interval_s(n) : 0; }
uint32_t care_need_left_s(Need n) { return care_need_enabled(n) && !care_need_due(n) ? interval_s(n) - st.elapsed[n] : 0; }

int care_need_level(Need n) {
  if (!care_need_enabled(n)) return 100;
  uint32_t iv = interval_s(n);
  return st.elapsed[n] >= iv ? 0 : 100 - (int)(st.elapsed[n] * 100 / iv);
}

int care_most_urgent() {
  int best = -1;
  float bestRatio = 0;
  for (int i = 0; i < NEED_COUNT; i++) {
    if (!care_need_due((Need)i)) continue;
    float r = (float)st.elapsed[i] / interval_s((Need)i);
    if (best < 0 || r > bestRatio) { best = i; bestRatio = r; }
  }
  return best;
}

const char *care_need_name(Need n) { return NEED_NAME[n]; }
uint32_t care_need_color(Need n) { return NEED_COLOR[n]; }

// ---------- state ----------
bool care_paused() { return pauseUntil && (int32_t)(pauseUntil - millis()) > 0; }
uint32_t care_pause_left_s() { return care_paused() ? (pauseUntil - millis()) / 1000 : 0; }
bool care_bedtime() { return st.bedtime; }
bool care_active() { return here && !st.bedtime && !care_paused() && care_in_hours(); }

Mood care_mood() {
  if (st.bedtime || !care_in_hours()) return MOOD_ASLEEP;
  int u = care_paused() ? -1 : care_most_urgent();
  if (u >= 0) {
    uint32_t over = care_need_overdue_s((Need)u);
    uint32_t longTime = max<uint32_t>(30 * 60, interval_s((Need)u));
    if (over >= longTime && st.happy < 30) return MOOD_MAD;
  }
  if (st.happy < 25) return MOOD_SAD;
  if (u == NEED_WATER) return MOOD_THIRSTY;
  if (u == NEED_EYES) return MOOD_TIRED;
  if (u == NEED_STRETCH || u == NEED_MOVE) return MOOD_RESTLESS;
  if (st.happy >= 80) return MOOD_HAPPY;
  return MOOD_CONTENT;
}

const char *care_mood_name(Mood m) { return MOOD_NAME[m]; }
int care_happiness() { return (int)(st.happy + 0.5f); }
// level n needs 100 * n XP more than the one before: 100, 300, 600, 1000...
int care_level() { int l = 1; uint32_t need = 100, xp = st.xp; while (xp >= need) { xp -= need; l++; need += 100; } return l; }
int care_level_progress() { uint32_t need = 100, xp = st.xp; while (xp >= need) { xp -= need; need += 100; } return (int)(xp * 100 / need); }
int care_today(Need n) { return st.today[n]; }
int care_streak() { return st.streak; }
uint32_t care_event() { return eventCount; }
// Bumps on anything you did (a break, a pat, a game, pause, bedtime) and on a new day: the Bluetooth link
// tells a connected phone, so it fetches the new state at once.
static volatile uint32_t changeCount = 0;
static void changed() { changeCount = changeCount + 1; }
uint32_t care_change() { return changeCount; }
uint32_t care_last_done_ms() { return lastDone; }

// ---------- actions ----------
static void add_happy(float d) { st.happy = constrain(st.happy + d, 0.0f, 100.0f); }

static void done_one(Need n) {
  uint32_t iv = interval_s(n);
  bool due = care_need_due(n);
  uint32_t over = care_need_overdue_s(n);
  if (!care_need_enabled(n) || (due && over < 10 * 60) || (!due && st.elapsed[n] * 2 >= iv)) {
    add_happy(12); st.xp += 10;               // on time, or at least half-way through the interval
  } else if (due) {
    add_happy(8); st.xp += 6;                 // late, still counts
  } else {
    add_happy(3); st.xp += 2;                 // early: a little, so it can't be farmed
  }
  st.elapsed[n] = 0;
  wasDue[n] = false;
  if (st.today[n] < 999) st.today[n]++;
}

static void done_at_one(Need n, uint32_t age) {
  // only count it in today's tally if it happened today (local time)
  bool today = true;
  if (care_clock_valid()) {
    time_t then = time(nullptr) - (time_t)age;
    struct tm t; localtime_r(&then, &t);
    today = (t.tm_year + 1900) * 10000 + (t.tm_mon + 1) * 100 + t.tm_mday == st.day;
  }
  if (age >= st.elapsed[n]) {
    // already reset since then (logged here, or a new day): credit it, leave the timer alone
    add_happy(3); st.xp += 2;
  } else {
    uint32_t iv = interval_s(n), then = st.elapsed[n] - age;      // how far along the timer was at the time
    bool due = care_need_enabled(n) && then >= iv;
    if (!care_need_enabled(n) || (due && then - iv < 10 * 60) || (!due && then * 2 >= iv)) { add_happy(12); st.xp += 10; }
    else if (due) { add_happy(8); st.xp += 6; }
    else { add_happy(3); st.xp += 2; }
    st.elapsed[n] = age;
    wasDue[n] = care_need_due(n);
  }
  if (today && st.today[n] < 999) st.today[n]++;
}

// Breaks that include others: a walk gets you out of your chair and your eyes off the screen, so logging
// Move also checks off Stretch and the eye break. Each is scored as if done on its own (early ones give
// little, so this can't be farmed). The app has the same table (CareEngine.COVERS).
static const uint8_t COVERS[NEED_COUNT] = { 0, 0, 0, (1 << NEED_STRETCH) | (1 << NEED_EYES) };

uint8_t care_covers(Need n) { return n < NEED_COUNT ? COVERS[n] : 0; }

void care_done(Need n) {
  if (n >= NEED_COUNT) return;
  done_one(n);
  for (int m = 0; m < NEED_COUNT; m++) if (COVERS[n] & (1 << m)) done_one((Need)m);
  lastDone = millis();
  changed();
  save();
}

void care_done_at(Need n, uint32_t age) {
  if (n >= NEED_COUNT) return;
  if (age < 5) { care_done(n); return; }
  done_at_one(n, age);
  for (int m = 0; m < NEED_COUNT; m++) if (COVERS[n] & (1 << m)) done_at_one((Need)m, age);
  lastDone = millis();
  changed();
  save();
}

uint32_t care_need_elapsed_s(Need n) { return n < NEED_COUNT ? st.elapsed[n] : 0; }
uint32_t care_need_interval_s(Need n) { return n < NEED_COUNT ? interval_s(n) : 0; }
uint32_t care_xp() { return st.xp; }

void care_set_steps(int32_t day, uint32_t steps) {
  if (day < stepsDay || (day == stepsDay && steps == stepsCount)) return;     // a stale report from an older day
  stepsDay = day; stepsCount = steps;
  prefs.putInt("stepsDay", day);
  prefs.putUInt("steps", steps);
}
int32_t care_today_date() {
  struct tm t;
  if (!local_now(t)) return 0;
  return (t.tm_year + 1900) * 10000 + (t.tm_mon + 1) * 100 + t.tm_mday;
}
uint32_t care_steps_today() { int32_t d = care_today_date(); return d && d == stepsDay ? stepsCount : 0; }

bool care_pet() {
  if (lastPet && millis() - lastPet < 60000) return false;
  lastPet = millis();
  add_happy(2);
  st.xp += 1;
  dirty = true;
  changed();
  return true;
}

void care_played(int hits, int rounds) {
  add_happy(min(20, hits * 4));
  st.xp += hits;
  (void)rounds;
  changed();
  save();
}

void care_pause(int minutes) { pauseUntil = minutes > 0 ? millis() + (uint32_t)minutes * 60000UL : 0; changed(); }

static void set_woke(bool on) {
  if (wokeEarly == on) return;
  wokeEarly = on;
  prefs.putBool("woke", on);
}

void care_wake() {
  st.bedtime = 0;
  if (!raw_in_hours()) set_woke(true);
  changed();
  save();
}

void care_set_bedtime(bool on) {
  if (on) set_woke(false);
  if (st.bedtime == on) return;
  st.bedtime = on;
  changed();
  save();
}

// ---------- tick ----------
static void new_day(int32_t today) {
  if (st.day) {
    int total = 0;
    for (int i = 0; i < NEED_COUNT; i++) total += st.today[i];
    // consecutive days: today must directly follow the saved day (works across month ends via mktime)
    struct tm t = {};
    t.tm_year = st.day / 10000 - 1900; t.tm_mon = st.day / 100 % 100 - 1; t.tm_mday = st.day % 100 + 1; t.tm_hour = 12;
    mktime(&t);
    int32_t next = (t.tm_year + 1900) * 10000 + (t.tm_mon + 1) * 100 + t.tm_mday;
    st.streak = (total >= 4 && next == today) ? st.streak + 1 : (total >= 4 ? 1 : 0);
  }
  st.day = today;
  memset(st.today, 0, sizeof(st.today));
  standsToday = 0;
  dirty = true;
  changed();
}

void care_tick() {
  uint32_t now = millis();
  if (settings_version() != cfgVer) load_settings();
  if (!ntpStarted && WiFi.status() == WL_CONNECTED) {
    // public pools first, then the router (many serve NTP), for networks that keep devices off the internet.
    // SNTP keeps the pointer, so the router's address lives in a static buffer.
    static char gw[16];
    strlcpy(gw, WiFi.gatewayIP().toString().c_str(), sizeof(gw));
    configTzTime(TZS[tzIdx < TZ_COUNT ? tzIdx : 0].posix, "pool.ntp.org", "time.google.com", gw);
    ntpStarted = true;
  }
  if (!here) { lastSecond = now; return; }        // away: everything waits until the companion is back
  if (now - lastSecond < 1000) return;
  uint32_t secs = (now - lastSecond) / 1000;
  lastSecond += secs * 1000;
  if (secs > 5) secs = 5;                   // never fast-forward a lot after a stall

  struct tm t;
  if (local_now(t)) {
    int32_t today = (t.tm_year + 1900) * 10000 + (t.tm_mon + 1) * 100 + t.tm_mday;
    if (today != st.day) new_day(today);
  }
  if (wokeEarly && raw_in_hours()) set_woke(false);   // the active hours caught up with you
  bool inHours = care_in_hours();
  if (inHours && !wasInHours) {
    // a new active day: bedtime ends, and the timers start fresh rather than carrying last night's debts
    st.bedtime = 0;
    memset(st.elapsed, 0, sizeof(st.elapsed));
    if (st.happy < 60) st.happy = 60;
    for (int i = 0; i < NEED_COUNT; i++) wasDue[i] = false;
    standing = false; postSec = 0; postWasDue = false;    // mornings start sitting down
    save();
  }
  wasInHours = inHours;

  if (care_active()) {
    bool newlyDue = false, neglected = false, anyDue = false;
    for (int i = 0; i < NEED_COUNT; i++) {
      Need n = (Need)i;
      if (!care_need_enabled(n)) continue;
      st.elapsed[i] += secs;
      bool due = care_need_due(n);
      if (due && !wasDue[i]) newlyDue = true;
      wasDue[i] = due;
      anyDue |= due;
      neglected |= due && care_need_overdue_s(n) > 10 * 60;
    }
    if (postureOn) {
      postSec += secs;
      bool due = care_posture_due();
      if (due && !postWasDue) newlyDue = true;
      postWasDue = due;
      anyDue |= due;                               // cues like a need, but never sours the mood
    }
    // cues: when something falls due (at most every 2 min), then every 15 min while anything is overdue
    uint32_t sinceCue = now - lastCueMs;
    if ((newlyDue && (lastCueMs == 0 || sinceCue >= 120000)) || (anyDue && lastCueMs && sinceCue >= 15 * 60000UL)) {
      eventCount = eventCount + 1;
      lastCueMs = now ? now : 1;
    }
    add_happy(-1.0f / 360 * secs);                 // a slow drift down: about 10 points an hour
    if (neglected) add_happy(-0.25f / 60 * secs);  // plus 15 an hour while something has been ignored 10+ min
    dirty = true;
  }
  if (dirty && now - lastSave > 10 * 60000UL) save();
}

// ---------- sit/stand ----------
bool care_posture_on() { return postureOn; }
bool care_standing() { return standing; }
uint32_t care_posture_elapsed_s() { return postSec; }
uint32_t care_posture_interval_s() { return (uint32_t)(standing ? standMin : sitMin) * 60; }
bool care_posture_due() { return postureOn && postSec >= care_posture_interval_s(); }
uint32_t care_posture_left_s() { uint32_t iv = care_posture_interval_s(); return postSec >= iv ? 0 : iv - postSec; }
int care_stands_today() { return standsToday; }
uint32_t care_posture_color() { return 0x4fd6c8; }

void care_posture_set(bool stand) {
  if (stand == standing) return;
  if (care_posture_due()) { add_happy(6); st.xp += 4; }      // switched when it was time
  else add_happy(1);
  standing = stand;
  postSec = 0;
  postWasDue = false;
  if (stand && standsToday < 999) standsToday++;
  changed();
  save();
}

// ---------- travel ----------
const char *care_cid() { return cid; }
uint32_t care_epoch() { return epoch; }
bool care_here() { return here; }
const char *care_where() { return where; }

void care_snapshot(CareSnapshot &o) {
  memcpy(o.elapsed, st.elapsed, sizeof(o.elapsed));
  o.happy = st.happy; o.xp = st.xp;
  memcpy(o.today, st.today, sizeof(o.today));
  o.day = st.day; o.streak = st.streak; o.bedtime = st.bedtime;
  o.standing = standing; o.postSec = postSec; o.standsToday = standsToday;
  o.pauseS = care_pause_left_s();
  o.woke = wokeEarly;
}

static void save_travel() {
  prefs.putString("cid", cid);
  prefs.putUInt("epoch", epoch);
  prefs.putBool("here", here);
  prefs.putString("where", where);
}

uint32_t care_leave(const char *to) {
  if (!here) return epoch;
  save();                                   // the state as it leaves, kept: a retried leave hands over the same
  here = false;
  epoch++;
  strlcpy(where, to && *to ? to : "your phone", sizeof(where));
  save_travel();
  changed();
  return epoch;
}

void care_arrive(const char *id, uint32_t e, const CareSnapshot &in) {
  strlcpy(cid, id, sizeof(cid));
  memcpy(st.elapsed, in.elapsed, sizeof(st.elapsed));
  st.happy = constrain(in.happy, 0.0f, 100.0f); st.xp = in.xp;
  memcpy(st.today, in.today, sizeof(st.today));
  st.day = in.day; st.streak = in.streak; st.bedtime = in.bedtime;
  standing = in.standing; postSec = in.postSec; standsToday = in.standsToday;
  pauseUntil = in.pauseS ? millis() + in.pauseS * 1000UL : 0;
  set_woke(in.woke && !raw_in_hours());
  for (int i = 0; i < NEED_COUNT; i++) wasDue[i] = care_need_due((Need)i);
  postWasDue = care_posture_due();
  wasInHours = care_in_hours();           // arriving isn't the start of a new active day
  here = true;
  epoch = e;
  where[0] = 0;
  save();
  save_travel();
  lastSecond = millis();
  changed();
}

void care_set_where(uint32_t e, const char *at) {
  if (here || e <= epoch) return;
  epoch = e;
  strlcpy(where, at && *at ? at : "somewhere else", sizeof(where));
  save_travel();
  changed();
}
