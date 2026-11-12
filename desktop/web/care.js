// The care game, ported from the Android app's CareEngine.kt (itself the dial's care.cpp), so the companion
// behaves the same on the desktop: four needs counting active seconds, sit/stand, happiness, experience,
// streaks, active hours, bedtime, pauses. Pure: the clock comes in as arguments, so it tests without waiting.

export const NEEDS = [
  { key: 'water', title: 'Water', color: '#3fa9ff' },
  { key: 'eyes', title: 'Eye break', color: '#ffc457' },
  { key: 'stretch', title: 'Stretch', color: '#5cf08a' },
  { key: 'move', title: 'Move', color: '#c77dff' },
];
export const POSTURE_COLOR = '#4fd6c8';
export const WATER = 0, EYES = 1, STRETCH = 2, MOVE = 3;
/** Breaks that include others: a walk also checks off the stretch and the eye break (care.cpp COVERS). */
export const COVERS = { [MOVE]: [STRETCH, EYES] };

export function defaultConfig() {
  return { remindMin: [45, 20, 50, 60], hourStart: 8, hourEnd: 20, postureOn: false, sitMin: 45, standMin: 15 };
}

export function defaultState() {
  return {
    elapsed: [0, 0, 0, 0], happy: 70, xp: 0, today: [0, 0, 0, 0], day: 0, streak: 0, bedtime: false,
    pauseUntil: 0, lastPet: 0, lastUpdate: 0, wasInHours: true,
    standing: false, postureElapsed: 0, standsToday: 0, wokeEarly: false,
  };
}

const dateOf = (ms) => { const d = new Date(ms); return d.getFullYear() * 10000 + (d.getMonth() + 1) * 100 + d.getDate(); };
const nextDay = (ymd) => dateOf(new Date(Math.floor(ymd / 10000), Math.floor(ymd / 100) % 100 - 1, ymd % 100 + 1, 12).getTime());

export class CareEngine {
  constructor(config = defaultConfig(), st = defaultState()) {
    this.config = { ...defaultConfig(), ...config };
    this.st = { ...defaultState(), ...st };
  }

  // ---------- clock ----------
  rawInHours(ms) {
    const { hourStart: a, hourEnd: b } = this.config;
    if (a === b) return true;
    const h = new Date(ms).getHours();
    return a < b ? h >= a && h < b : h >= a || h < b;
  }
  /** Active hours, or woken by hand outside them (see wake). */
  inHours(ms) { return this.st.wokeEarly || this.rawInHours(ms); }
  paused(ms) { return this.st.pauseUntil > ms; }
  active(ms) { return this.inHours(ms) && !this.st.bedtime && !this.paused(ms); }

  // ---------- needs ----------
  interval(n) { return this.config.remindMin[n] * 60; }
  enabled(n) { return this.config.remindMin[n] > 0; }
  due(n) { return this.enabled(n) && this.st.elapsed[n] >= this.interval(n); }
  overdue(n) { return this.due(n) ? this.st.elapsed[n] - this.interval(n) : 0; }
  left(n) { return this.enabled(n) && !this.due(n) ? this.interval(n) - this.st.elapsed[n] : 0; }
  mostUrgent() {
    let best = null, ratio = 0;
    for (let n = 0; n < 4; n++) {
      if (!this.due(n)) continue;
      const r = this.st.elapsed[n] / this.interval(n);
      if (best === null || r > ratio) { best = n; ratio = r; }
    }
    return best;
  }
  mood(ms) {
    if (this.st.bedtime || !this.inHours(ms)) return 'Asleep';
    const u = this.paused(ms) ? null : this.mostUrgent();
    if (u !== null && this.overdue(u) >= Math.max(30 * 60, this.interval(u)) && this.st.happy < 30) return 'Mad';
    if (this.st.happy < 25) return 'Sad';
    if (u === WATER) return 'Thirsty';
    if (u === EYES) return 'Tired';
    if (u === STRETCH || u === MOVE) return 'Restless';
    return this.st.happy >= 80 ? 'Happy' : 'Content';
  }
  happiness() { return Math.round(this.st.happy); }
  levelNumber() { let l = 1, need = 100, xp = this.st.xp; while (xp >= need) { xp -= need; l++; need += 100; } return l; }

  // ---------- time passing ----------
  /** Advance to `now`, minute by minute, so active hours, pauses and new days land where they should. */
  advanceTo(now) {
    const st = this.st;
    if (!st.lastUpdate || now < st.lastUpdate) { st.lastUpdate = now; this.checkDay(now); st.wasInHours = this.inHours(now); return; }
    let t = st.lastUpdate;
    const limit = Math.min(now, t + 3 * 24 * 3600e3);
    while (t < limit) {
      const step = Math.min(60e3, limit - t), from = t;
      t += step;
      this.checkDay(t);
      if (st.wokeEarly && this.rawInHours(t)) st.wokeEarly = false;
      const ih = this.inHours(t);
      if (ih && !st.wasInHours) {                 // a new active day: fresh timers, bedtime over
        st.bedtime = false;
        st.elapsed = [0, 0, 0, 0];
        st.standing = false; st.postureElapsed = 0;
        if (st.happy < 60) st.happy = 60;
      }
      st.wasInHours = ih;
      if (this.active(from)) {
        const secs = step / 1000;
        let neglected = false;
        for (let n = 0; n < 4; n++) {
          if (!this.enabled(n)) continue;
          st.elapsed[n] += secs;
          neglected = neglected || (this.due(n) && this.overdue(n) > 600);
        }
        if (this.config.postureOn) st.postureElapsed += secs;
        this.addHappy(-secs / 360);
        if (neglected) this.addHappy(-0.25 / 60 * secs);
      }
    }
    st.lastUpdate = now;
  }

  checkDay(ms) {
    const today = dateOf(ms), st = this.st;
    if (today === st.day) return;
    if (st.day) {
      const total = st.today.reduce((a, b) => a + b, 0);
      st.streak = total >= 4 && nextDay(st.day) === today ? st.streak + 1 : total >= 4 ? 1 : 0;
    }
    st.day = today;
    st.today = [0, 0, 0, 0];
    st.standsToday = 0;
  }

  // ---------- sit/stand ----------
  postureInterval() { return (this.st.standing ? this.config.standMin : this.config.sitMin) * 60; }
  postureDue() { return this.config.postureOn && this.st.postureElapsed >= this.postureInterval(); }
  setPosture(standing, now) {
    this.advanceTo(now);
    if (standing === this.st.standing) return;
    if (this.postureDue()) { this.addHappy(6); this.st.xp += 4; } else this.addHappy(1);
    this.st.standing = standing;
    this.st.postureElapsed = 0;
    if (standing && this.st.standsToday < 999) this.st.standsToday++;
  }

  addHappy(d) { this.st.happy = Math.min(100, Math.max(0, this.st.happy + d)); }

  // ---------- actions ----------
  score(n, elapsedThen) {
    const iv = this.interval(n), wasDue = this.enabled(n) && elapsedThen >= iv;
    if (!this.enabled(n) || (wasDue && elapsedThen - iv < 600) || (!wasDue && elapsedThen * 2 >= iv)) { this.addHappy(12); this.st.xp += 10; }
    else if (wasDue) { this.addHappy(8); this.st.xp += 6; }
    else { this.addHappy(3); this.st.xp += 2; }
  }
  done(n, now) {
    this.advanceTo(now);
    for (const m of [n, ...(COVERS[n] || [])]) {
      this.score(m, this.st.elapsed[m]);
      this.st.elapsed[m] = 0;
      if (this.st.today[m] < 999) this.st.today[m]++;
    }
  }
  /** A pat; false while on its one-minute cooldown. */
  pet(now) {
    if (this.st.lastPet && now - this.st.lastPet < 60e3) return false;
    this.st.lastPet = now;
    this.addHappy(2); this.st.xp += 1;
    return true;
  }
  played(hits) { this.addHappy(Math.min(20, hits * 4)); this.st.xp += hits; }
  pause(minutes, now) { this.st.pauseUntil = minutes > 0 ? now + minutes * 60e3 : 0; }
  setBedtime(on) { this.st.bedtime = on; if (on) this.st.wokeEarly = false; }
  /** Wake up now: bedtime ends and, outside active hours, it stays up until they next begin (or bedtime). */
  wake(now) { this.st.bedtime = false; if (!this.rawInHours(now)) this.st.wokeEarly = true; }
}

/** What the companion shows (the dial's POSES): a loop and maybe a thought bubble. */
export function poseFor(mood, urgent, reacting, postureDue, thirstyHasBubble) {
  if (reacting) return { loop: 'happy', emote: 'emote_happy' };
  if (postureDue && urgent === null && mood !== 'Asleep') return { loop: mood === 'Happy' ? 'happy' : 'walk', emote: 'emote_posture' };
  switch (mood) {
    case 'Happy': return { loop: 'happy', emote: null };
    case 'Thirsty': return { loop: 'thirsty', emote: thirstyHasBubble ? null : 'emote_water' };
    case 'Tired': return { loop: 'tired', emote: 'emote_eyes' };
    case 'Restless': return urgent === MOVE ? { loop: 'move', emote: 'emote_move' } : { loop: 'stretch', emote: 'emote_stretch' };
    case 'Sad': return { loop: 'sad', emote: 'emote_sad' };
    case 'Mad': return { loop: 'mad', emote: 'emote_mad' };
    case 'Asleep': return { loop: 'sleep', emote: 'emote_asleep' };
    default: return { loop: 'walk', emote: 'emote_content' };
  }
}
