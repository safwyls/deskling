import { test } from 'node:test';
import assert from 'node:assert/strict';
import { defaultState } from '../web/care.js';
import { makeRecord, fromLink, linkFor, careFrom, encode } from '../web/record.js';

const cid = '0123456789abcdef';

test('a record round-trips through a link', () => {
  const now = 1_800_000_000_000;
  const st = { ...defaultState(), elapsed: [1200, 0, 30, 0], today: [0, 3, 0, 1], xp: 450, streak: 2, happy: 81.5, standing: true,
               postureElapsed: 300, standsToday: 1, pauseUntil: now + 90_000 };
  const back = fromLink(linkFor(makeRecord(cid, 'sprig', 9, st, now)));
  assert.equal(back.cid, cid); assert.equal(back.pet, 'sprig'); assert.equal(back.epoch, 9);
  const s = careFrom(back, now + 1000, () => true);
  assert.equal(s.elapsed[0], 1200); assert.equal(s.today[1], 3); assert.equal(s.xp, 450); assert.equal(s.happy, 81.5);
  assert.equal(s.standing, true); assert.equal(s.postureElapsed, 300); assert.equal(s.pauseUntil, now + 1000 + 90_000);
});

test("the dial's and the phone's records read the same", () => {
  const dial = { cid, pet: 'crumb', epoch: 4, care: { elapsed: [10, 20, 30, 40], today: [1, 0, 2, 0], happy: 66.5, xp: 120, day: 20261111,
    streak: 1, bedtime: false, standing: false, postSec: 60, stands: 0, pause: 0 } };
  assert.equal(fromLink(`deskling://companion?r=${encode(dial)}`).pet, 'crumb');
  assert.equal(fromLink(encode(dial)).care.elapsed[2], 30);
});

test('nonsense is not a companion', () => {
  assert.equal(fromLink('https://example.com/#companion=bm9wZQ'), null);
  assert.equal(fromLink('hello'), null);
});
