import { PACK_EXT } from './hosting.js';
// The dial's round screen, drawn in a canvas: the pet's loop, its shadow and thought bubble, and optionally the
// home screen around it (clock, status line). Same layout as the firmware (src/pet.cpp): a 360 px round screen,
// the pet standing on a ground line, the bubble up and to the right of its face.

export const PETS = {
  wisp:  { name: 'Wisp',  bg: '#111a2c', text: '#e8eef8', sub: '#8a94a8', accent: '#9fd8f7', led: '#4cc8ff',
           shadow: [70, 105, 160, 110 / 255], thirstyBubble: false },
  crumb: { name: 'Crumb', bg: '#1a2c20', text: '#f2ecda', sub: '#9aa88f', accent: '#f3b54a', led: '#ffa630',
           shadow: [4, 9, 5, 150 / 255], thirstyBubble: false },
  sprig: { name: 'Sprig', bg: '#32223a', text: '#f5f1e3', sub: '#ab9aae', accent: '#a9db7f', led: '#7cff4a',
           shadow: [10, 4, 14, 150 / 255], thirstyBubble: true },
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
    if (this.home) this.drawHome(P);
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
