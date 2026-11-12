// The companion on the desktop: its loop, shadow and thought bubble drawn the way the dial and the website draw
// them; a click opens a ring of activities around it (as in the phone app); a drag moves it; the right button runs
// the shortcut picked in the tray menu. What it records goes to app.js, which keeps the care, the tray and the window.
import { api, start } from './app.js';
const canvas = document.getElementById('c'), g = canvas.getContext('2d');
const ringEl = document.getElementById('ring'), toastEl = document.getElementById('toast'), stopEl = document.getElementById('stop');

let snap = await api.snapshot();
let mode = 'idle';                     // idle | ring | activity | game
let reactUntil = 0, toastUntil = 0;
let activity = null;                   // { type, label, color, start, secs }
let game = null;                       // { round, hits, start, pauseUntil, flash }

// ---------------------------------------------------------------- loops and bubbles
const PETS = new URL('pets/', location.href), EMOTES = new URL('emotes/', location.href);
const loops = new Map(), emotes = new Map();

function loadLoop(pet, loop) {
  const key = `${pet}/${loop}`;
  if (!loops.has(key)) loops.set(key, (async () => {
    const [meta, buf] = await Promise.all([
      fetch(new URL(`${key}.json`, PETS)).then((r) => r.json()),
      fetch(new URL(`${key}.bin`, PETS)).then((r) => r.arrayBuffer()),
    ]);
    const frames = await Promise.all(meta.frames.map(([o, n]) => createImageBitmap(new Blob([buf.slice(o, o + n)], { type: 'image/webp' }))));
    return { meta, frames };
  })());
  return loops.get(key);
}
function loadEmote(name) {
  if (!emotes.has(name)) emotes.set(name, fetch(new URL(`${name}.png`, EMOTES)).then((r) => r.blob()).then(createImageBitmap));
  return emotes.get(name);
}

let cur = { key: '', loop: null, bubble: null, frame: 0, dir: 1, last: 0 };
async function choosePose() {
  const pose = performance.now() < reactUntil ? { loop: 'happy', emote: 'emote_happy' } : snap.pose;
  const key = `${snap.pet}/${pose.loop}/${pose.emote}`;
  if (key === cur.key) return;
  cur.key = key;
  const [loop, bubble] = await Promise.all([loadLoop(snap.pet, pose.loop), pose.emote ? loadEmote(pose.emote) : null]);
  if (cur.key !== key) return;
  cur = { key, loop, bubble, frame: 0, dir: 1, last: 0 };
}

// ---------------------------------------------------------------- layout
const u = () => snap.scale;                                           // screen points per dial pixel
const W = () => innerWidth, H = () => innerHeight;
const open = () => mode !== 'idle';
const centre = () => ({ x: W() / 2, y: open() ? H() / 2 : H() * 0.55 });
const ringR = () => W() / 2 - itemSize() / 2 - 6 * u();
const itemSize = () => Math.max(44, 60 * u());

function sizeCanvas() {
  const d = devicePixelRatio || 1;
  canvas.width = Math.round(W() * d); canvas.height = Math.round(H() * d);
  document.documentElement.style.setProperty('--fs', `${Math.max(10, 12 * u()).toFixed(1)}px`);
  placeRing();
}
addEventListener('resize', sizeCanvas);

function applyPalette() {
  const p = snap.palette, s = document.documentElement.style;
  for (const k of ['panel', 'line', 'text', 'accent', 'dim']) s.setProperty(`--${k}`, p[k]);
}

/** Is (x, y) on the companion? Roughly its outline: a box around it, not the window's clear corners. */
function onPet(x, y) {
  const c = centre(), k = u();
  return Math.abs(x - c.x) < 95 * k && y > c.y - 80 * k && y < c.y + 72 * k;
}

// ---------------------------------------------------------------- drawing
function draw(t) {
  requestAnimationFrame(draw);
  const L = cur.loop;
  if (L && t - cur.last >= 1000 / L.meta.fps) {
    cur.last = t;
    const n = L.frames.length;
    if (!L.meta.pingpong || n < 2) cur.frame = (cur.frame + 1) % n;
    else { let x = cur.frame + cur.dir; if (x >= n) { cur.dir = -1; x = n - 2; } else if (x < 0) { cur.dir = 1; x = 1; } cur.frame = x; }
  }
  if (reactUntil && performance.now() > reactUntil) { reactUntil = 0; choosePose(); }
  if (toastUntil && performance.now() > toastUntil) { toastUntil = 0; toastEl.hidden = true; }
  const d = devicePixelRatio || 1;
  g.setTransform(d, 0, 0, d, 0, 0);
  g.clearRect(0, 0, W(), H());
  if (mode === 'activity') drawActivity();
  if (mode === 'game') drawGame();
  if (L) drawPet(L);
}

function drawPet(L) {
  const { meta, frames } = L, i = Math.min(cur.frame, frames.length - 1), img = frames[i], k = meta.scale, s = u();
  const c = centre(), ground = c.y + 62 * s;
  const left = c.x - meta.cx / k * s, top = ground - meta.ground / k * s;
  // shadow: narrower and fainter the higher it is off the ground, as on the dial
  const devW = img.width / k, hgt = Math.max(0, (meta.ground - meta.bottoms[i]) / k);
  const kk = Math.max(0.45, 1 - hgt / 160), sw = Math.max(28, 0.32 * devW) * kk * s, sh = sw * 0.17;
  const [r, gg, b, a] = snap.palette.shadow;
  g.fillStyle = `rgba(${r},${gg},${b},${a * kk})`;
  g.beginPath(); g.ellipse(c.x, ground, sw, sh, 0, 0, Math.PI * 2); g.fill();
  g.drawImage(img, left, top, img.width / k * s, img.height / k * s);
  if (cur.bubble && (mode === 'idle' || mode === 'activity')) {   // not over the ring or the game
    const fx = left + (meta.ax + meta.hx[i]) / k * s, fy = top + (meta.ay + meta.hy[i]) / k * s;
    const bw = 44 * s, bh = 58 * s;
    const x = Math.min(fx + 25 * s, W() - bw - 2), y = Math.max(2, fy - 87 * s);
    g.drawImage(cur.bubble, x, y, bw, bh);
  }
}

function arc(radius, from, to, color, width, alpha = 1) {
  const c = centre();
  g.globalAlpha = alpha; g.strokeStyle = color; g.lineWidth = width; g.lineCap = 'round';
  g.beginPath(); g.arc(c.x, c.y, radius, (from - 90) * Math.PI / 180, (to - 90) * Math.PI / 180); g.stroke();
  g.globalAlpha = 1;
}

function label(text, y, size, color, weight = 600) {
  g.font = `${weight} ${size}px system-ui, sans-serif`; g.textAlign = 'center'; g.textBaseline = 'middle';
  g.lineWidth = 4; g.strokeStyle = 'rgba(0,0,0,0.45)'; g.strokeText(text, W() / 2, y);
  g.fillStyle = color; g.fillText(text, W() / 2, y);
}

// ---------------------------------------------------------------- the ring of activities
function ringItems() {
  const s = snap, needs = s.needs;
  const need = (i, type, text) => ({ text, color: needs[i].color, due: needs[i].due, run: type });
  return [
    need(0, 'water', 'Water'), need(1, 'eyes', 'Eyes'), need(2, 'stretch', 'Stretch'), need(3, 'move', 'Move'),
    ...(s.postureOn ? [{ text: s.standing ? 'Sit' : 'Stand', color: s.postureColor, due: s.postureDue, run: 'posture' }] : []),
    { text: 'Play', color: s.palette.accent, run: 'play' },
    { text: s.asleep ? 'Wake' : 'Bed', color: s.palette.accent, run: 'bed' },
    { text: s.paused ? 'Resume' : 'Pause', color: s.palette.accent, run: 'pause' },
  ];
}

const ICONS = { water: 'water', eyes: 'eyes', stretch: 'stretch', move: 'move', posture: 'posture', play: 'play', bed: 'moon', pause: 'pause' };

function buildRing() {
  ringEl.replaceChildren(...ringItems().map((it) => {
    const b = document.createElement('button');
    const ic = document.createElement('span');
    ic.className = 'ic';
    ic.style.maskImage = ic.style.webkitMaskImage = `url("${new URL(`icon_${ICONS[it.run]}.png`, EMOTES)}")`;
    const tx = document.createElement('span');
    tx.textContent = it.text;
    b.append(ic, tx);
    b.style.setProperty('--c', it.color);
    if (it.due) b.classList.add('due');
    b.addEventListener('click', (e) => { e.stopPropagation(); run(it.run); });
    return b;
  }));
  placeRing();
}

function placeRing() {
  const bs = [...ringEl.children], n = bs.length, sz = itemSize(), r = ringR(), c = { x: W() / 2, y: H() / 2 };
  bs.forEach((b, i) => {
    const a = -Math.PI / 2 + 2 * Math.PI * i / n;
    b.style.width = b.style.height = `${sz}px`;
    b.style.left = `${c.x + r * Math.cos(a) - sz / 2}px`;
    b.style.top = `${c.y + r * Math.sin(a) - sz / 2}px`;
  });
}

function setMode(m) {
  const wasOpen = open();
  mode = m;
  if (open() !== wasOpen) api.layout(open());
  ringEl.classList.toggle('open', m === 'ring');
  if (m === 'ring') buildRing(); else setTimeout(() => { if (mode !== 'ring') ringEl.replaceChildren(); }, 170);
  stopEl.hidden = !(m === 'activity' || m === 'game');
  toastEl.style.bottom = stopEl.hidden ? '4px' : '40px';    // above the Stop button
}

// ---------------------------------------------------------------- what things do
async function act(a) {
  const r = await api.act(a);
  if (r?.react) { reactUntil = performance.now() + 2500; choosePose(); }
  if (r?.toast) toast(r.toast);
}

function toast(text) {
  toastEl.textContent = text; toastEl.hidden = false;
  toastUntil = performance.now() + 2600;
}

function run(what) {
  switch (what) {
    case 'eyes': return startActivity('eyes', 'Look 20 feet away', 1, 20);
    case 'stretch': return startActivity('stretch', 'Stretch tall, roll your shoulders', 2, 30);
    case 'play': return startGame();
    case 'ring': return setMode(mode === 'ring' ? 'idle' : 'ring');
    case 'hide': setMode('idle'); return api.hide();
    default: setMode('idle'); return act({ type: what });
  }
}

function startActivity(type, text, need, secs) {
  if (snap.asleep) { setMode('idle'); return toast(`${snap.name} is asleep`); }
  activity = { type, text, color: snap.needs[need].color, start: performance.now(), secs };
  setMode('activity');
}

function drawActivity() {
  const a = activity, el = (performance.now() - a.start) / 1000, left = Math.max(0, Math.ceil(a.secs - el)), k = u();
  arc(ringR(), 0, 360, snap.palette.line, 8 * k, 0.8);
  arc(ringR(), 0, 360 * Math.min(1, el / a.secs), a.color, 8 * k);
  label(`${left}`, centre().y - 120 * k, Math.max(16, 26 * k), '#fff');
  label(a.text, centre().y + 92 * k, Math.max(11, 13 * k), snap.palette.text, 500);
  if (el >= a.secs) { const t = a.type; activity = null; setMode('idle'); act({ type: t }); }
}

// the dial's catch game: a light runs round the ring; click anywhere while it's in the zone at the top
const ROUNDS = 5, ZONE = 30;
const period = () => 1900 * Math.pow(0.82, game.round);
function startGame() {
  if (snap.asleep) { setMode('idle'); return toast(`${snap.name} is asleep`); }
  game = { round: 0, hits: 0, start: performance.now(), pauseUntil: 0, flash: null, angle: 180 };
  setMode('game');
}
function gameAngle(now) { return (180 + 360 * (now - game.start) / period()) % 360; }

function drawGame() {
  const now = performance.now(), k = u(), R = ringR(), c = centre();
  if (game.pauseUntil) {
    if (now >= game.pauseUntil) { game.pauseUntil = 0; game.flash = null; game.start = now; if (game.round >= ROUNDS) return endGame(); }
  } else {
    if (now - game.start > period() * 3) { judge(null); return; }       // three laps without a click: a miss
    game.angle = gameAngle(now);
  }
  arc(R, 0, 360, snap.palette.line, 6 * k, 0.9);
  const dotR = 9 * k, dotDeg = dotR / R * 180 / Math.PI;
  arc(R, -ZONE, ZONE, game.flash || snap.palette.ok, 18 * k, game.flash ? 0.95 : 0.5);
  const a = (game.angle - 90) * Math.PI / 180;
  g.fillStyle = '#fff'; g.beginPath(); g.arc(c.x + R * Math.cos(a), c.y + R * Math.sin(a), dotR, 0, Math.PI * 2); g.fill();
  label(`Round ${Math.min(game.round + 1, ROUNDS)} of ${ROUNDS}  •  ${game.hits} caught`, c.y + 96 * k, Math.max(11, 12 * k), snap.palette.text, 500);
  game.dotDeg = dotDeg;
}

/** A click (judged the moment it lands), or a miss when null. */
function judge(at) {
  if (game.pauseUntil) return;
  let hit = false;
  if (at !== null) {
    game.angle = gameAngle(at);
    hit = Math.min(game.angle, 360 - game.angle) <= ZONE + (game.dotDeg || 3);
  }
  if (hit) game.hits++;
  game.flash = hit ? snap.palette.ok : snap.palette.bad;
  game.round++;
  game.pauseUntil = performance.now() + 600;
}

function endGame() {
  const hits = game.hits;
  game = null; setMode('idle');
  act({ type: 'played', hits });
}

stopEl.addEventListener('click', (e) => {
  e.stopPropagation();
  activity = null; game = null; setMode('idle'); toast('Stopped');
});

// ---------------------------------------------------------------- pointer: click, drag, right-click
// A press on the companion that moves more than a few points hands the window to the system to drag (it moves
// smoothly that way on every platform); one that doesn't is a click.
let down = null;
canvas.addEventListener('pointerdown', (e) => {
  if (e.button !== 0) return;
  if (mode === 'game') { judge(performance.now()); return; }
  down = { x: e.screenX, y: e.screenY, pet: onPet(e.clientX, e.clientY) };
});
canvas.addEventListener('pointermove', (e) => {
  if (!down || !down.pet || mode !== 'idle' || !(e.buttons & 1)) return;
  if (Math.hypot(e.screenX - down.x, e.screenY - down.y) < 4) return;
  down = null;
  api.startDrag();
});
canvas.addEventListener('pointerup', () => {
  const d = down; down = null;
  if (!d) return;
  if (mode === 'idle') { if (d.pet) setMode('ring'); }
  else if (mode === 'ring') { if (d.pet) { setMode('idle'); act({ type: 'pet' }); } else setMode('idle'); }
});

// The window is a square around the companion, mostly clear. While nothing is open, clicks on the clear part go
// through to whatever is underneath: the cursor is checked a few times a second and the window lets the mouse
// through whenever it isn't over the companion.
let through = false;
async function passThrough() {
  let want = false;
  if (mode === 'idle' && !down && document.visibilityState === 'visible') {
    const p = await api.cursor().catch(() => null);
    if (p) want = !onPet(p.x, p.y);
  }
  if (want !== through) { through = want; await api.passThrough(want).catch(() => {}); }
}
setInterval(passThrough, 80);
addEventListener('contextmenu', (e) => {
  e.preventDefault();
  if (mode === 'game' || mode === 'activity') return;
  run(snap.rightClick);
});
addEventListener('keydown', (e) => { if (e.key === 'Escape' && mode === 'ring') setMode('idle'); });
addEventListener('blur', () => { if (mode === 'ring') setMode('idle'); });

// ---------------------------------------------------------------- state from the main process
function refresh() {
  applyPalette();
  canvas.title = `${snap.name}: ${snap.mood}. ${snap.status}`;
  if (mode === 'ring') buildRing();
  sizeCanvas();
  choosePose();
}
api.onState((s) => { snap = s; refresh(); });
api.onCommand((c) => {
  if (c === 'ring' && mode === 'idle') setMode('ring');
  if (c.startsWith('run:')) run(c.slice(4));
  if (c === 'stop') stopEl.click();
  if (c === 'arrived') { reactUntil = performance.now() + 3000; choosePose(); toast(`${snap.name} is here`); }
});

refresh();
requestAnimationFrame(draw);
await start();
