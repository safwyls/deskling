// The companions, and the record that carries one between devices: the same shape as the dial's (firmware
// api.cpp put_companion) and the Android app's (data/Companion.kt), so a link made on any of them opens on any.
// Plain web APIs only, so it runs in the app's webview and under Node for the tests.
import { defaultState } from './care.js';

export const PETS = {
  wisp:  { name: 'Wisp',  thirstyHasBubble: true,
           palette: { bg: '#111a2c', panel: '#0b1220', raised: '#1e2a42', line: '#2a3a5a', accent: '#9fd8f7', text: '#e8eef8', dim: '#8a94a8', ok: '#7ee0a0', bad: '#ff8f8f', shadow: [70, 105, 160, 110 / 255] } },
  crumb: { name: 'Crumb', thirstyHasBubble: true,
           palette: { bg: '#1a2c20', panel: '#101b13', raised: '#26392d', line: '#3a5644', accent: '#f3b54a', text: '#f2ecda', dim: '#9aa88f', ok: '#9ed98a', bad: '#ff9a85', shadow: [4, 9, 5, 150 / 255] } },
  sprig: { name: 'Sprig', thirstyHasBubble: false,
           palette: { bg: '#32223a', panel: '#21152a', raised: '#412f4b', line: '#56405f', accent: '#a9db7f', text: '#f5f1e3', dim: '#ab9aae', ok: '#9fe3b0', bad: '#ff8fa3', shadow: [10, 4, 14, 150 / 255] } },
};

export const SITE = 'https://safwyls.github.io/deskling/';
/** Where a companion is that left by a link and hasn't arrived anywhere this device has heard of. */
export const SHARED = 'a link you shared';

export const isCid = (s) => typeof s === 'string' && /^[0-9a-f]{16}$/i.test(s);
export const newCid = () => [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, '0')).join('');
/** A device that has never seen a companion makes its own, here. */
export const freshTravel = () => ({ cid: newCid(), epoch: 1, here: true, where: '' });

/** The record of the companion as it is now (st advanced to `now` already), moving at `epoch`. */
export function makeRecord(cid, pet, epoch, st, now) {
  return {
    cid, pet, epoch,
    care: {
      elapsed: st.elapsed.map(Math.round), today: [...st.today], happy: st.happy, xp: st.xp, day: st.day, streak: st.streak,
      bedtime: st.bedtime, standing: st.standing, postSec: Math.round(st.postureElapsed), stands: st.standsToday,
      pause: Math.max(0, Math.floor((st.pauseUntil - now) / 1000)), woke: st.wokeEarly,
    },
  };
}

/** Null unless it's a whole, sensible record. */
export function readRecord(o) {
  if (!o || !isCid(o.cid) || !PETS[o.pet] || !(o.epoch > 0) || !o.care) return null;
  const c = o.care;
  if (!Array.isArray(c.elapsed) || c.elapsed.length !== 4 || !Array.isArray(c.today) || c.today.length !== 4) return null;
  return { cid: o.cid.toLowerCase(), pet: o.pet, epoch: Math.floor(o.epoch), care: c };
}

/** The care state a record carries, ready to run here from `now` (rawInHours: the engine's, to see if it's night). */
export function careFrom(rec, now, rawInHours) {
  const c = rec.care, s = defaultState();
  for (let i = 0; i < 4; i++) { s.elapsed[i] = Number(c.elapsed[i]) || 0; s.today[i] = Math.min(999, Number(c.today[i]) || 0); }
  s.happy = Math.min(100, Math.max(0, Number(c.happy ?? 70)));
  s.xp = Number(c.xp) || 0; s.day = Number(c.day) || 0; s.streak = Number(c.streak) || 0;
  s.bedtime = !!c.bedtime; s.standing = !!c.standing; s.postureElapsed = Number(c.postSec) || 0; s.standsToday = Number(c.stands) || 0;
  const pause = Math.min(24 * 3600, Math.max(0, Number(c.pause) || 0));
  s.pauseUntil = pause ? now + pause * 1000 : 0;
  s.wokeEarly = !!c.woke && !rawInHours(now);
  s.lastUpdate = now;
  s.wasInHours = s.wokeEarly || rawInHours(now);      // arriving isn't the start of a new active day
  return s;
}

/** base64url of the record's JSON (UTF-8). */
export function encode(o) {
  const bytes = new TextEncoder().encode(JSON.stringify(o));
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function decode(code) {
  const b64 = code.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64 + '='.repeat((4 - b64.length % 4) % 4));
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
}

/** The link another device opens: the website shows who's coming and hands it to the app. */
export const linkFor = (rec) => `${SITE}#companion=${encode(rec)}`;

/** A record from a shared link (the website's, deskling://companion?r=..., or just the code). */
export function fromLink(text) {
  const t = String(text || '').trim();
  const m = t.match(/(?:#companion=|[?&]r=)([A-Za-z0-9_-]+)/);
  const code = m ? m[1] : /^[A-Za-z0-9_-]{40,}$/.test(t) ? t : null;
  if (!code) return null;
  try { return readRecord(JSON.parse(decode(code))); } catch { return null; }
}
