import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CareEngine, poseFor, WATER, EYES, STRETCH, MOVE } from '../web/care.js';

const at = (h, m = 0, day = 2) => new Date(2026, 10, day, h, m).getTime();

test('needs fall due after their interval of active time', () => {
  const e = new CareEngine();
  e.advanceTo(at(9));
  e.advanceTo(at(9, 46));
  assert.ok(e.due(WATER));
  assert.equal(e.mood(at(9, 46)), 'Tired');            // the eye break (every 20 min) is the most overdue
  e.done(EYES, at(9, 46));
  assert.equal(e.mood(at(9, 46)), 'Thirsty');
});

test('nothing counts outside active hours, and it sleeps', () => {
  const e = new CareEngine();
  e.advanceTo(at(21));
  e.advanceTo(at(23));
  assert.equal(e.st.elapsed[WATER], 0);
  assert.equal(e.mood(at(23)), 'Asleep');
});

test('a walk also checks off the stretch and the eye break', () => {
  const e = new CareEngine();
  e.advanceTo(at(9)); e.advanceTo(at(10, 10));
  e.done(MOVE, at(10, 10));
  for (const n of [MOVE, STRETCH, EYES]) assert.equal(e.st.elapsed[n], 0);
  assert.ok(e.st.elapsed[WATER] > 0);
  assert.equal(e.st.today[EYES], 1);
});

test('waking it at night keeps it up until the hours begin', () => {
  const e = new CareEngine();
  e.advanceTo(at(21));
  e.wake(at(21));
  e.advanceTo(at(22));
  assert.notEqual(e.mood(at(22)), 'Asleep');
  e.advanceTo(at(9, 0, 3));
  assert.equal(e.st.wokeEarly, false);
});

test('a pat counts once a minute', () => {
  const e = new CareEngine();
  e.advanceTo(at(9));
  assert.equal(e.pet(at(9)), true);
  assert.equal(e.pet(at(9) + 30e3), false);
});

test('a streak needs four breaks a day on consecutive days', () => {
  const e = new CareEngine();
  e.advanceTo(at(9));
  for (const n of [WATER, EYES, STRETCH, WATER]) e.done(n, at(10));
  e.advanceTo(at(9, 0, 3));
  assert.equal(e.st.streak, 1);
});

test('poses match the dial', () => {
  assert.deepEqual(poseFor('Thirsty', WATER, false, false, false), { loop: 'thirsty', emote: 'emote_water' });
  assert.deepEqual(poseFor('Thirsty', WATER, false, false, true), { loop: 'thirsty', emote: null });
  assert.deepEqual(poseFor('Restless', MOVE, false, false, true), { loop: 'move', emote: 'emote_move' });
  assert.equal(poseFor('Content', null, true, false, true).loop, 'happy');
});
