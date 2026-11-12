// #companion=<code>: a companion shared from one phone to another. The code is the record the app made
// (app: data/Companion.kt): who it is and its care state, base64url JSON. This page only shows who's coming and
// hands the code to the app (deskling://companion?r=<code>); nothing is stored or sent anywhere.
import { Screen, PETS } from './screen.js';
import { setPet } from './app.js';

const $ = (root, s) => root.querySelector(s);

function decode(code) {
  try {
    const b64 = code.replace(/-/g, '+').replace(/_/g, '/');
    const json = new TextDecoder().decode(Uint8Array.from(atob(b64 + '='.repeat((4 - b64.length % 4) % 4)), (c) => c.charCodeAt(0)));
    const r = JSON.parse(json);
    return r && PETS[r.pet] && /^[0-9a-f]{16}$/i.test(r.cid) && r.care ? r : null;
  } catch { return null; }
}

function level(xp) { let l = 1, need = 100; while (xp >= need) { xp -= need; l++; need += 100; } return l; }

export function init(root, { cleanup, code }) {
  const r = decode(code || '');
  if (!r) {
    $(root, '#arrive-title').textContent = 'This link doesn’t carry a companion.';
    $(root, '#arrive-what').textContent = 'It may have been cut short when it was copied. Ask for it to be sent again.';
    for (const id of ['#arrive-open', '#arrive-copy', '#arrive-note']) $(root, id).hidden = true;
    return;
  }
  const P = PETS[r.pet];
  setPet(r.pet);
  const lv = level(Number(r.care.xp) || 0), streak = Number(r.care.streak) || 0;
  $(root, '#arrive-title').textContent = `${P.name} is coming to you.`;
  $(root, '#arrive-what').textContent = `Level ${lv}${streak ? `, on a ${streak}-day streak` : ''}. Open this on your Android phone and ${P.name} moves into the Deskling app, ready to go onto any dial you’ve paired.`;
  const s = new Screen($(root, '#arrive-pet'), { pet: r.pet, pose: 'happy', size: 300 });
  cleanup.push(() => s.stop());
  // Chrome on Android opens intent: links in the app; without the app it goes to the fallback, the app's page
  const android = /Android/i.test(navigator.userAgent);
  const back = encodeURIComponent(location.href.split('#')[0] + '#app');
  $(root, '#arrive-open').href = android
    ? `intent://companion?r=${code}#Intent;scheme=deskling;package=app.deskling;S.browser_fallback_url=${back};end`
    : `deskling://companion?r=${code}`;
  if (!android) $(root, '#arrive-open').classList.replace('primary', 'quiet');
  $(root, '#arrive-copy').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(location.href); $(root, '#arrive-copy').textContent = 'Copied'; } catch { $(root, '#arrive-copy').textContent = 'Copy it from the address bar'; }
  });
}
