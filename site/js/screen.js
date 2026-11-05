import { PACK_EXT } from './hosting.js';
// The dial's round screen, drawn in a canvas: the pet's loop, its shadow and thought bubble, and optionally the
// home screen around it (clock, status line). Same layout as the firmware (src/pet.cpp): a 360 px round screen,
// the pet standing on a ground line, the bubble up and to the right of its face.

export const PETS = {
  wisp:  { name: 'Wisp',  bg: '#111a2c', text: '#e8eef8', sub: '#8a94a8', accent: '#9fd8f7', led: '#4cc8ff', dim: '#4a5468',
           shadow: [70, 105, 160, 110 / 255], thirstyBubble: false,
           clk: { big: '#27395c', split: '#34496f', hand: '#2f4268', tick: '#3a4e78', word: '#3e5279', wordHi: '#6680b4' } },
  crumb: { name: 'Crumb', bg: '#1a2c20', text: '#f2ecda', sub: '#9aa88f', accent: '#f3b54a', led: '#ffa630', dim: '#4c5c4e',
           shadow: [4, 9, 5, 150 / 255], thirstyBubble: false,
           clk: { big: '#2f4935', split: '#3d5b43', hand: '#37543d', tick: '#48684a', word: '#4d6a52', wordHi: '#84a476' } },
  sprig: { name: 'Sprig', bg: '#32223a', text: '#f5f1e3', sub: '#ab9aae', accent: '#a9db7f', led: '#7cff4a', dim: '#655269',
           shadow: [10, 4, 14, 150 / 255], thirstyBubble: true,
           clk: { big: '#45324f', split: '#55405f', hand: '#4d3a58', tick: '#624c6c', word: '#664f70', wordHi: '#a58cae' } },
};

export const NEED_COLORS = { water: '#3fa9ff', eyes: '#ffc457', stretch: '#5cf08a', move: '#c77dff', posture: '#4fd6c8' };

// Poses as the pet screen picks them: a loop and a bubble (the firmware's POSES table).
export const POSES = {
  content: { loop: 'walk', bubble: 'emote_content' },
  happy:   { loop: 'happy', bubble: 'emote_happy' },
  thirsty: { loop: 'thirsty', bubble: null },        // Wisp's and Crumb's clips draw their own
  tired:   { loop: 'tired', bubble: 'emote_eyes' },
  stretch: { loop: 'stretch', bubble: 'emote_stretch' },
  move:    { loop: 'move', bubble: 'emote_move' },
  sad:     { loop: 'sad', bubble: 'emote_sad' },
  mad:     { loop: 'mad', bubble: 'emote_mad' },
  asleep:  { loop: 'sleep', bubble: 'emote_asleep' },
};

const base = new URL('..', import.meta.url);
const cache = new Map();

/** Load one loop (meta + decoded frames), once. */
export function loadLoop(pet, loop) {
  const key = `${pet}/${loop}`;
  if (!cache.has(key)) {
    cache.set(key, (async () => {
      const at = (ext) => new URL(`pets/${pet}/${loop}.${ext}`, base);
      const [meta, buf] = await Promise.all([
        fetch(at('json')).then((r) => r.json()),
        fetch(at(PACK_EXT)).then((r) => r.arrayBuffer()),
      ]);
      // One file per loop: the frames are WebP images back to back, offsets in the JSON.
      const frames = await Promise.all(meta.frames.map(([o, n]) =>
        createImageBitmap(new Blob([buf.slice(o, o + n)], { type: 'image/webp' }))));
      return { meta, frames };
    })());
  }
  return cache.get(key);
}

const emotes = new Map();
function loadEmote(name) {
  if (!emotes.has(name)) {
    emotes.set(name, (async () => createImageBitmap(await (await fetch(new URL(`emotes/${name}.png`, base))).blob()))());
  }
  return emotes.get(name);
}

/**
 * A round dial screen in a canvas. setPet/setPose switch what it shows; it animates on its own clock
 * (12 or 24 fps as the loop asks) and pauses when off screen or when motion is reduced (first frame only).
 */
export class Screen {
  constructor(canvas, { pet = 'wisp', pose = 'content', home = false, status = null, size = 360, onFrame = null } = {}) {
    this.c = canvas;
    this.c.width = size; this.c.height = size;
    this.g = canvas.getContext('2d');
    this.size = size;
    this.home = home;              // draw the clock and status line too
    this.status = status;          // { text, color } for the bottom line
    this.face = null;              // optional: the firmware's own home screen, see setFace()
    this.onFrame = onFrame;
    this.frame = 0; this.dir = 1; this.last = 0;
    this.visible = true;
    this.still = matchMedia('(prefers-reduced-motion: reduce)').matches;
    this.set(pet, pose);
    if ('IntersectionObserver' in window && !onFrame) {
      new IntersectionObserver(([e]) => { this.visible = e.isIntersecting; }).observe(canvas);
    }
    this.tick = this.tick.bind(this);
    requestAnimationFrame(this.tick);
  }

  async set(pet, pose, status) {
    this.pet = pet; this.pose = pose;
    if (status !== undefined) this.status = status;
    const p = POSES[pose];
    const want = `${pet}/${p.loop}`;
    this.want = want;
    const [loop, bubble] = await Promise.all([
      loadLoop(pet, p.loop),
      (pose === 'thirsty' ? (PETS[pet].thirstyBubble ? 'emote_water' : null) : p.bubble) ? loadEmote(pose === 'thirsty' ? 'emote_water' : p.bubble) : null,
    ]);
    if (this.want !== want) return;          // switched again while loading
    this.loop = loop; this.bubble = bubble; this.frame = 0; this.dir = 1;
    this.draw();
  }

  /**
   * Draw the home screen the way the firmware does (src/clockface.cpp and status_render in src/pet.cpp):
   * { mode: 0 off | 1 12-hour | 2 24-hour, style: 0 Classic … 5 Words, date, wifi, phone, tz, next: { text, color } }.
   */
  setFace(face) { this.face = face; this.draw(); }

  stop() { this.stopped = true; }

  tick(t) {
    if (this.stopped) return;
    requestAnimationFrame(this.tick);
    if (!this.loop || this.still || !this.visible) return;
    const ms = 1000 / this.loop.meta.fps;
    if (t - this.last < ms) return;
    this.last = t;
    const n = this.loop.frames.length;
    if (!this.loop.meta.pingpong || n < 2) this.frame = (this.frame + 1) % n;
    else {
      let x = this.frame + this.dir;
      if (x >= n) { this.dir = -1; x = n - 2; } else if (x < 0) { this.dir = 1; x = 1; }
      this.frame = x;
    }
    this.draw();
  }

  draw() {
    const g = this.g, s = this.size / 360, P = PETS[this.pet];
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, this.size, this.size);
    g.save();
    g.beginPath(); g.arc(this.size / 2, this.size / 2, this.size / 2, 0, Math.PI * 2); g.clip();
    g.fillStyle = P.bg; g.fillRect(0, 0, this.size, this.size);
    g.scale(s, s);
    if (this.face) drawFace(g, P, this.face);
    else if (this.home) this.drawHome(P);
    if (this.loop) this.drawPet(P);
    g.restore();
    if (this.onFrame) this.onFrame();
  }

  drawHome(P) {
    const g = this.g;
    const now = new Date();
    let h = now.getHours() % 12; if (h === 0) h = 12;
    g.fillStyle = P.text; g.textAlign = 'center'; g.textBaseline = 'middle';
    g.font = '600 30px "Instrument Sans", system-ui, sans-serif';
    g.fillText(`${h}:${String(now.getMinutes()).padStart(2, '0')}`, 180, 50);
    if (this.status) {
      g.fillStyle = this.status.color || P.sub;
      g.font = '500 16px "Instrument Sans", system-ui, sans-serif';
      g.fillText(this.status.text, 180, 326);
    }
  }

  drawPet(P) {
    const g = this.g, { meta, frames } = this.loop;
    const i = Math.min(this.frame, frames.length - 1), img = frames[i], k = meta.scale;
    const GROUND = 264;                                      // where the shadow sits, as on the dial
    const left = 180 - meta.cx / k, top = GROUND - meta.ground / k;
    // shadow: narrower and fainter the higher the pet is off the ground (make_assets.py bakes the same on the dial)
    const devW = img.width / k, hgt = Math.max(0, (meta.ground - meta.bottoms[i]) / k);
    const kk = Math.max(0.45, 1 - hgt / 160), sw = Math.max(28, 0.32 * devW) * kk, sh = sw * 0.17;
    const [r, gg, b, a] = P.shadow;
    g.fillStyle = `rgba(${r},${gg},${b},${a * kk})`;
    g.beginPath(); g.ellipse(180, GROUND, sw, sh, 0, 0, Math.PI * 2); g.fill();
    g.drawImage(img, left, top, img.width / k, img.height / k);
    if (this.bubble) {
      const fx = left + (meta.ax + meta.hx[i]) / k, fy = top + (meta.ay + meta.hy[i]) / k;
      let x = fx + 25, y = fy - 87;
      if (y < 64 && this.home) { x += 64 - y; y = 64; }
      x = Math.min(x, 360 - 92);
      g.drawImage(this.bubble, x, Math.max(2, y), 44, 58);
    }
  }
}

// ---------------------------------------------------------------- the firmware's home screen
const STYLE = { CLASSIC: 0, BIG: 1, SPLIT: 2, ANALOG: 3, RIM: 4, WORDS: 5 };
const MONT = (px) => `500 ${px}px Montserrat, "Instrument Sans", system-ui, sans-serif`;   // LVGL's built-in font
const HOUR_WORD = ['twelve', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve'];
const PAST = ['', 'five past', 'ten past', 'quarter past', 'twenty past', 'twenty-five past', 'half past'];
const TO = ['', 'twenty-five to', 'twenty to', 'quarter to', 'ten to', 'five to'];

/** Hours, minutes and the date line ("Thu Nov 5") right now in an IANA time zone. */
export function timeIn(tz) {
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: tz, hour: 'numeric', minute: 'numeric', hourCycle: 'h23', weekday: 'short', month: 'short', day: 'numeric',
  }).formatToParts(new Date()).map((p) => [p.type, p.value]));
  return { H: Number(parts.hour) % 24, M: Number(parts.minute), date: `${parts.weekday} ${parts.month} ${parts.day}` };
}

// The big and split clocks are pre-rendered digits on the dial (tools/make_clock.py): Inter Display Black with
// figures 72 px tall, and Inter Display Thin at 96 px. Each glyph image is its ink width plus 2 px either side.
const digitFonts = new Map();
function digitFont(g, weight, height) {
  const key = `${weight}/${height}`;
  if (digitFonts.has(key)) return digitFonts.get(key);
  g.font = `${weight} 100px Inter, system-ui, sans-serif`;
  const m = g.measureText('0'), h100 = m.actualBoundingBoxAscent + m.actualBoundingBoxDescent;
  const f = { font: `${weight} ${(100 * height / h100).toFixed(2)}px Inter, system-ui, sans-serif`, height };
  if (document.fonts?.check?.(f.font)) digitFonts.set(key, f);      // only cache once the real font is in
  return f;
}

function digitRow(g, text, weight, height, gap, colour) {
  const f = digitFont(g, weight, height);
  g.font = f.font; g.textBaseline = 'alphabetic'; g.textAlign = 'left';
  const asc = g.measureText('0').actualBoundingBoxAscent;
  const glyphs = [...text].map((c) => {
    const m = g.measureText(c);
    return { c, left: m.actualBoundingBoxLeft, w: m.actualBoundingBoxLeft + m.actualBoundingBoxRight + 4 };
  });
  const width = glyphs.reduce((a, x) => a + x.w, 0) + gap * (glyphs.length - 1);
  return {
    width,
    draw(cx, cy) {
      g.font = f.font; g.fillStyle = colour; g.textBaseline = 'alphabetic'; g.textAlign = 'left';
      let x = cx - width / 2;
      const base = cy - f.height / 2 + asc;
      for (const gl of glyphs) { g.fillText(gl.c, x + 2 + gl.left, base); x += gl.w + gap; }
    },
  };
}

const atAngle = (deg, r) => { const a = (deg - 90) * Math.PI / 180; return [180 + r * Math.cos(a), 180 + r * Math.sin(a)]; };
function dot(g, x, y, d, colour, alpha = 1) { g.globalAlpha = alpha; g.fillStyle = colour; g.beginPath(); g.arc(x, y, d / 2, 0, Math.PI * 2); g.fill(); g.globalAlpha = 1; }
function hand(g, deg, len, width, colour) {
  const [x, y] = atAngle(deg, len);
  g.strokeStyle = colour; g.lineWidth = width; g.lineCap = 'round';
  g.beginPath(); g.moveTo(180, 180); g.lineTo(x, y); g.stroke();
}

function wifiIcon(g, x, y, colour) {             // LV_SYMBOL_WIFI, about 16 x 12
  g.strokeStyle = colour; g.fillStyle = colour; g.lineWidth = 2; g.lineCap = 'round';
  for (const r of [3.5, 7, 10.5]) { g.beginPath(); g.arc(x, y + 5, r, -Math.PI * 0.78, -Math.PI * 0.22); g.stroke(); }
  g.beginPath(); g.arc(x, y + 5, 1.6, 0, Math.PI * 2); g.fill();
}
function bluetoothIcon(g, x, y, colour) {        // LV_SYMBOL_BLUETOOTH, about 8 x 14
  g.strokeStyle = colour; g.lineWidth = 1.8; g.lineJoin = 'round'; g.lineCap = 'round';
  g.beginPath(); g.moveTo(x - 4, y - 3.5); g.lineTo(x + 3.5, y + 3); g.lineTo(x, y + 6.5); g.lineTo(x, y - 6.5); g.lineTo(x + 3.5, y - 3); g.lineTo(x - 4, y + 3.5); g.stroke();
}

function drawFace(g, P, f) {
  const { H, M, date } = timeIn(f.tz || 'America/Los_Angeles');
  const on = f.mode !== 0, h12 = H % 12 === 0 ? 12 : H % 12, hShown = f.mode === 1 ? h12 : H;
  const pad = (n) => String(n).padStart(2, '0');
  const small = on && (f.style === STYLE.CLASSIC || f.style === STYLE.RIM);
  g.save();
  g.textAlign = 'center'; g.textBaseline = 'middle';
  if (on) switch (f.style) {
    case STYLE.CLASSIC: case STYLE.RIM:
      if (f.style === STYLE.RIM) {
        for (let i = 0; i < 12; i++) { const [x, y] = atAngle(i * 30, 162); dot(g, x, y, 3, P.accent, 0.3); }
        g.globalAlpha = 0.4; g.strokeStyle = P.accent; g.lineWidth = 5; g.lineCap = 'round';
        g.beginPath(); g.arc(180, 180, 174 - 2.5, -Math.PI / 2, -Math.PI / 2 + (M === 0 ? 60 : M) / 60 * Math.PI * 2); g.stroke();
        g.globalAlpha = 1;
        const [x, y] = atAngle((H % 12) * 30 + M * 0.5, 146); dot(g, x, y, 14, P.accent, 0.7);
      }
      g.font = MONT(20); g.fillStyle = f.style === STYLE.RIM ? P.sub : P.text;
      g.fillText(f.mode === 1 ? `${h12}:${pad(M)} ${H < 12 ? 'AM' : 'PM'}` : `${pad(H)}:${pad(M)}`, 180, 34);
      break;
    case STYLE.BIG:
      digitRow(g, f.mode === 1 ? `${hShown}:${pad(M)}` : `${pad(H)}:${pad(M)}`, 900, 72, 4, P.clk.big).draw(180, 120);
      break;
    case STYLE.SPLIT: {
      const L = digitRow(g, f.mode === 1 ? `${hShown}` : pad(H), 100, 96, 2, P.clk.split);
      const R = digitRow(g, pad(M), 100, 96, 2, P.clk.split);
      L.draw(180 - 14 - L.width / 2, 174); R.draw(180 + 14 + R.width / 2, 174);
      break;
    }
    case STYLE.ANALOG:
      for (let i = 0; i < 12; i++) { const [x, y] = atAngle(i * 30, 160); dot(g, x, y, i % 3 === 0 ? 8 : 4, P.clk.tick); }
      hand(g, (H % 12) * 30 + M * 0.5, 92, 9, P.clk.hand);
      hand(g, M * 6, 148, 6, P.clk.hand);
      dot(g, 180, 180, 16, P.clk.hand);
      break;
    case STYLE.WORDS: {
      let m5 = Math.floor((M + 2) / 5) * 5, hr = H;
      if (m5 === 60) { m5 = 0; hr++; }
      if (m5 > 30) hr++;
      g.font = MONT(20); g.fillStyle = P.clk.word;
      g.fillText(m5 === 0 ? "it's" : m5 <= 30 ? PAST[m5 / 5] : TO[(m5 - 30) / 5], 180, 68);
      g.font = MONT(48); g.fillStyle = P.clk.wordHi;
      g.fillText(HOUR_WORD[hr % 12 === 0 ? 12 : hr % 12], 180, 106);
      break;
    }
    default: break;
  }

  // the date and icon row: under the small clock, or at the very top when the clock is big or off
  g.textAlign = 'center'; g.textBaseline = 'middle';
  const items = [];
  g.font = MONT(14);
  if (f.date) items.push({ w: g.measureText(date).width, draw: (x, y) => { g.font = MONT(14); g.fillStyle = P.sub; g.fillText(date, x, y); } });
  if (f.wifi) items.push({ w: 16, draw: (x, y) => wifiIcon(g, x, y, P.sub) });
  if (f.phone) items.push({ w: 9, draw: (x, y) => bluetoothIcon(g, x, y, P.accent) });
  if (items.length) {
    const total = items.reduce((a, i) => a + i.w, 0) + 10 * (items.length - 1);
    let x = 180 - total / 2;
    const y = small ? 56 : 34;
    for (const it of items) { it.draw(x + it.w / 2, y); x += it.w + 10; }
  }
  if (f.next) { g.font = MONT(14); g.fillStyle = f.next.color || P.dim; g.fillText(f.next.text, 180, 326); }
  g.restore();
}
