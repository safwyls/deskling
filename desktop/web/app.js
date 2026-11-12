// Deskling for the desktop: the companion in a small see-through window with no taskbar entry, a tray icon for
// the settings, the care game with reminders as notifications, and travel by link (as between phones).
// Everything runs here, in the window's page, through Tauri's APIs (window.__TAURI__); the Rust side only
// sets up the plugins, keeps it out of the Dock on macOS, and sends a "tick" every 15 seconds that arrives
// even while the window is hidden.
import { CareEngine, NEEDS, POSTURE_COLOR, poseFor, defaultConfig, defaultState, MOVE } from './care.js';
import { PETS, SHARED, freshTravel, makeRecord, careFrom, linkFor, fromLink } from './record.js';

const T = window.__TAURI__;
const { getCurrentWindow, LogicalSize, LogicalPosition, primaryMonitor } = T.window;
const win = getCurrentWindow();
const isMac = /Mac/.test(navigator.platform), isLinux = /Linux/.test(navigator.platform);

// ---------------------------------------------------------------- what's kept
export const SIZES = [0.5, 0.75, 1, 1.25, 1.5, 2];
export const RIGHT_CLICK = [
  ['pet', 'Pat it'], ['water', 'Log water'], ['eyes', 'Eye break'], ['stretch', 'Stretch'], ['move', 'Log a walk'],
  ['play', 'Play'], ['pause', 'Pause or resume'], ['bed', 'Bedtime or wake up'], ['ring', 'Open the activities'], ['hide', 'Hide it'],
];
const store = await T.store.load('deskling.json', { autoSave: false });
const data = {
  pet: 'wisp', config: defaultConfig(), st: defaultState(), travel: null, lastShare: '', lastNotified: {},
  settings: { scale: 1, pinned: true, visible: true, rightClick: 'pet', notify: true, anchor: null },
  ...((await store.get('data')) || {}),
};
data.settings = { scale: 1, pinned: true, visible: true, rightClick: 'pet', notify: true, anchor: null, ...data.settings };
if (!data.travel) data.travel = freshTravel();
if (!PETS[data.pet]) data.pet = 'wisp';
const engine = new CareEngine(data.config, data.st);

let saveTimer = null;
function save(now = false) {
  data.config = engine.config; data.st = engine.st;
  const write = async () => { saveTimer = null; await store.set('data', data); await store.save(); };
  if (now) { clearTimeout(saveTimer); return write(); }
  if (!saveTimer) saveTimer = setTimeout(write, 1500);
}

// ---------------------------------------------------------------- what the window and the tray show
const minutes = (secs) => { const m = Math.ceil(secs / 60); return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`; };

function snapshot() {
  const now = Date.now(), e = engine, P = PETS[data.pet];
  e.advanceTo(now);
  const mood = e.mood(now), paused = e.paused(now);
  const urgent = paused ? null : e.mostUrgent();
  const postureDue = e.postureDue() && !paused;
  let status;
  if (mood === 'Asleep') status = e.st.bedtime ? 'Bedtime' : `Asleep until ${e.config.hourStart}:00`;
  else if (paused) status = `Paused, ${minutes((e.st.pauseUntil - now) / 1000)} left`;
  else if (urgent !== null) status = `${NEEDS[urgent].title} is due`;
  else if (postureDue) status = `Time to ${e.st.standing ? 'sit down' : 'stand up'}`;
  else {
    const next = [0, 1, 2, 3].filter((n) => e.enabled(n)).sort((a, b) => e.left(a) - e.left(b))[0];
    status = next === undefined ? 'All looked after' : `Next: ${NEEDS[next].title.toLowerCase()} in ${minutes(e.left(next))}`;
  }
  const t = data.travel;
  return {
    pet: data.pet, name: P.name, palette: P.palette, mood, status, urgent, postureDue, paused, bedtime: e.st.bedtime,
    asleep: mood === 'Asleep', postureOn: e.config.postureOn, standing: e.st.standing,
    pose: poseFor(mood, urgent, false, postureDue, P.thirstyHasBubble),
    needs: NEEDS.map((n, i) => ({ ...n, due: e.due(i) && !paused, enabled: e.enabled(i), today: e.st.today[i] })),
    level: e.levelNumber(), happy: e.happiness(), here: t.here, where: t.where,
    scale: data.settings.scale, rightClick: data.settings.rightClick, postureColor: POSTURE_COLOR,
  };
}

const listeners = { state: [], command: [] };
function broadcast() {
  const s = snapshot();
  for (const f of listeners.state) f(s);
  refreshTray(s);
  return s;
}
const command = (c) => { for (const f of listeners.command) f(c); };

// ---------------------------------------------------------------- the window
// It sits small around the companion; opening the activities grows it around the same centre, so it covers
// no more of the screen than it needs to.
let open = false;
const CLOSED = 260, OPEN = 420;            // at 100%, in screen points
const PET_Y_CLOSED = 0.55;                 // the companion's centre, down the small window

async function anchor() {
  const s = data.settings.scale;
  if (data.settings.anchor) return data.settings.anchor;
  const m = await primaryMonitor();          // first run: bottom right of the main screen
  const f = m?.scaleFactor || 1;
  const area = m?.workArea || { position: m?.position || { x: 0, y: 0 }, size: m?.size || { width: 1280 * f, height: 800 * f } };
  return { x: area.position.x / f + area.size.width / f - 150 * s, y: area.position.y / f + area.size.height / f - 140 * s };
}

function offset(isOpen = open) {
  const size = (isOpen ? OPEN : CLOSED) * data.settings.scale;
  return { size, dx: size / 2, dy: isOpen ? size / 2 : size * PET_Y_CLOSED };
}

// Opened near the edge of the screen, the window is moved in so the whole ring shows (the companion moves with it
// while it's open, then goes back).
async function placeWindow() {
  const a = await anchor(), o = offset();
  let x = a.x - o.dx, y = a.y - o.dy;
  const m = (await T.window.currentMonitor().catch(() => null)) || (await primaryMonitor().catch(() => null));
  if (m && open) {
    const f = m.scaleFactor || 1, area = m.workArea || { position: m.position, size: m.size };
    const left = area.position.x / f, top = area.position.y / f;
    x = Math.min(Math.max(x, left), left + area.size.width / f - o.size);
    y = Math.min(Math.max(y, top), top + area.size.height / f - o.size);
  }
  at = { x: Math.round(x), y: Math.round(y) };
  await win.setSize(new LogicalSize(Math.round(o.size), Math.round(o.size)));
  await win.setPosition(new LogicalPosition(at.x, at.y));
}

// Where the window is, in screen points: kept here from what we set and what the system reports as it's dragged
// (asking for it isn't reliable on Linux). After a drag, the companion's centre is remembered.
let at = { x: 0, y: 0 }, moveTimer = null;
win.onMoved(async ({ payload }) => {
  const f = await win.scaleFactor();
  at = { x: payload.x / f, y: payload.y / f };
  clearTimeout(moveTimer);
  moveTimer = setTimeout(() => {
    if (open) return;                        // it's only dragged while closed; moves while open are ours
    const o = offset();
    data.settings.anchor = { x: at.x + o.dx, y: at.y + o.dy };
    save();
  }, 400);
});

async function applyPin() {
  await win.setAlwaysOnTop(data.settings.pinned);
  if (isMac) await win.setVisibleOnAllWorkspaces(data.settings.pinned).catch(() => {});
}

async function setVisible(on) {
  data.settings.visible = on; save();
  if (on && data.travel.here) { await win.show(); await applyPin(); await placeWindow(); } else await win.hide();
  refreshTray();
}

// ---------------------------------------------------------------- actions (from the window, the tray)
const NEED_KEYS = { water: 0, eyes: 1, stretch: 2, move: 3 };

function act(a) {
  const now = Date.now(), e = engine, name = PETS[data.pet].name;
  let toast = null, react = false;
  if (!data.travel.here) return { toast: `${name} isn't here` };
  if (a.type in NEED_KEYS) {
    const n = NEED_KEYS[a.type];
    e.done(n, now);
    toast = n === MOVE ? 'Nice walk' : ['Water logged', 'Eyes rested', 'Stretched'][n];
    react = true;
    delete data.lastNotified[NEEDS[n].key];
  } else if (a.type === 'pet') {
    if (e.mood(now) === 'Asleep') toast = `Shh, ${name} is asleep`;
    else { react = true; toast = e.pet(now) ? `${name} likes that` : null; }
  } else if (a.type === 'played') {
    e.advanceTo(now); e.played(a.hits | 0); react = a.hits > 0;
    toast = `${a.hits} of 5 caught`;
  } else if (a.type === 'pause') {
    e.advanceTo(now);
    if (e.paused(now)) { e.pause(0, now); toast = 'Reminders back on'; } else { e.pause(60, now); toast = 'Paused for an hour'; }
  } else if (a.type === 'bed') {
    e.advanceTo(now);
    if (e.mood(now) === 'Asleep') { e.wake(now); toast = 'Good morning'; react = true; } else { e.setBedtime(true); toast = 'Goodnight'; }
  } else if (a.type === 'posture') {
    e.setPosture(!e.st.standing, now); toast = e.st.standing ? 'Standing' : 'Sitting'; react = true;
  }
  save();
  broadcast();
  return { toast, react };
}

// ---------------------------------------------------------------- reminders
const PROMPT = ['%s is thirsty: time for some water', 'Time for an eye break: look 20 feet away for 20 seconds', 'Time to stretch', 'Time to get up and move'];
let canNotify = false;

async function remind() {
  const now = Date.now(), e = engine, name = PETS[data.pet].name, last = data.lastNotified;
  const on = data.travel.here && data.settings.notify && canNotify;
  for (let n = 0; n < 4; n++) {
    const k = NEEDS[n].key;
    if (!e.due(n) || !e.active(now)) { delete last[k]; continue; }
    if (!on || now - (last[k] || 0) < 15 * 60e3) continue;
    last[k] = now;
    T.notification.sendNotification({ title: NEEDS[n].title, body: PROMPT[n].replace('%s', name) });
  }
  if (!e.postureDue() || !e.active(now)) delete last.posture;
  else if (on && now - (last.posture || 0) >= 15 * 60e3) {
    last.posture = now;
    T.notification.sendNotification({ title: e.st.standing ? 'Time to sit down' : 'Time to stand up',
      body: `${name} has been ${e.st.standing ? 'standing' : 'sitting'} with you ${Math.round(e.st.postureElapsed / 60)} min` });
  }
}

// ---------------------------------------------------------------- travel
async function giveAway() {
  const t = data.travel, name = PETS[data.pet].name;
  if (!t.here) return;
  const yes = await T.dialog.ask(
    `${name} leaves this computer now, as a link copied to your clipboard. Send it to the phone or computer that should have ${name}: it moves in there when the link is opened, with its level, streak and today's breaks. Until then you can take it back from the tray menu.`,
    { title: `Give ${name} to another device?`, kind: 'info', okLabel: 'Copy the link', cancelLabel: 'Cancel' });
  if (!yes) return;
  const now = Date.now();
  engine.advanceTo(now);
  const rec = makeRecord(t.cid, data.pet, t.epoch + 1, engine.st, now);
  data.travel = { cid: t.cid, epoch: t.epoch + 1, here: false, where: SHARED };
  data.lastShare = linkFor(rec);
  await T.clipboardManager.writeText(data.lastShare);
  await save(true);
  await win.hide();
  broadcast();
}

/** A shared companion moves in here (from a deskling:// link, a copied link, or taking our own back). */
async function receive(text) {
  const rec = fromLink(text);
  if (!rec) { await T.dialog.message('Copy the whole link, then try again.', { title: "That isn't a companion's link" }); return; }
  const t = data.travel, now = Date.now(), incoming = PETS[rec.pet].name;
  if (rec.cid === t.cid && t.epoch > rec.epoch) {
    await T.dialog.message(`${incoming} has moved on since it was made.`, { title: 'That link is out of date' });
    return;
  }
  if (!(rec.cid === t.cid && !t.here)) {
    const replacing = t.here && rec.cid !== t.cid;
    const yes = await T.dialog.ask(
      replacing ? `${PETS[data.pet].name} (level ${engine.levelNumber()}) lives here now: taking ${incoming} in replaces it for good.` : `${incoming} moves in on this computer.`,
      { title: `Take in ${incoming}?`, okLabel: replacing ? 'Replace' : 'Take in', cancelLabel: 'Not now' });
    if (!yes) return;
  }
  engine.st = careFrom(rec, now, (ms) => engine.rawInHours(ms));
  data.pet = rec.pet;
  data.travel = { cid: rec.cid, epoch: rec.epoch + 1, here: true, where: '' };
  data.lastNotified = {};
  await save(true);
  await setVisible(true);
  broadcast();
  command('arrived');
}

// ---------------------------------------------------------------- the tray
const { TrayIcon } = T.tray, { Menu, Submenu, MenuItem, CheckMenuItem, PredefinedMenuItem } = T.menu, { Image } = T.image;
let tray = null, trayKey = '', autostart = false;
const icons = {};
async function trayIcon(pet) {
  icons[pet] ||= Image.fromBytes(new Uint8Array(await (await fetch(`icons/tray-${pet}-64.png`)).arrayBuffer()));
  return icons[pet];
}

const sep = () => PredefinedMenuItem.new({ item: 'Separator' });
const item = (text, action, enabled = true) => MenuItem.new({ text, enabled, action });
const check = (text, checked, action) => CheckMenuItem.new({ text, checked, action });
const sub = async (text, items) => Submenu.new({ text, items: await Promise.all(items) });

async function buildMenu(s) {
  const st = data.settings, cfg = engine.config;
  const setCfg = (patch) => { engine.config = { ...engine.config, ...patch }; save(); broadcast(); };
  const every = (i) => sub(NEEDS[i].title, [0, 15, 20, 30, 45, 60, 90].map((m) =>
    check(m ? `Every ${m} min` : 'Off', cfg.remindMin[i] === m, () => { const r = [...engine.config.remindMin]; r[i] = m; setCfg({ remindMin: r }); })));
  const hours = [[7, 19], [8, 20], [9, 17], [9, 18], [10, 22], [0, 0]];
  const items = [];
  if (s.here) {
    items.push(item(`${s.name}: ${s.mood}`, null, false), item(s.status, null, false), sep(),
      check('Show on the desktop', st.visible, () => setVisible(!st.visible)),
      check('Keep it above other windows', st.pinned, async () => { st.pinned = !st.pinned; save(); await applyPin(); refreshTray(); }),
      sub('Size', SIZES.map((z) => check(`${Math.round(z * 100)}%`, st.scale === z, async () => { st.scale = z; save(); await placeWindow(); broadcast(); }))),
      sub('Companion', Object.entries(PETS).map(([id, p]) => check(p.name, data.pet === id, () => { data.pet = id; save(); broadcast(); }))),
      sub('Right-click does', RIGHT_CLICK.map(([id, label]) => check(label, st.rightClick === id, () => { st.rightClick = id; save(); broadcast(); }))),
      sep(),
      item(s.paused ? 'Resume reminders' : 'Pause for an hour', () => act({ type: 'pause' })),
      item(s.asleep ? 'Wake up' : 'Bedtime', () => act({ type: 'bed' })),
      sub('Reminders', [
        check('Notifications', st.notify, () => { st.notify = !st.notify; save(); refreshTray(); }),
        sep(),
        ...[0, 1, 2, 3].map(every),
        sep(),
        sub('Active hours', hours.map(([a, b]) => check(a === b ? 'All day' : `${a}:00 to ${b}:00`,
          cfg.hourStart === a && cfg.hourEnd === b, () => setCfg({ hourStart: a, hourEnd: b })))),
        check('Sit and stand', cfg.postureOn, () => setCfg({ postureOn: !cfg.postureOn })),
      ]),
      sep(),
      item(`Give ${s.name} to another device…`, giveAway));
  } else {
    items.push(item(s.where === SHARED ? `${s.name} left by a link` : `${s.name} is with ${s.where}`, null, false), sep());
    if (s.where === SHARED && data.lastShare) {
      items.push(item('Copy the link again', () => T.clipboardManager.writeText(data.lastShare)),
                 item(`Take ${s.name} back`, () => receive(data.lastShare)));
    }
  }
  items.push(
    item('Take in a companion from a copied link', async () => receive(await T.clipboardManager.readText())),
    sep(),
    check('Start when I log in', autostart, async () => {
      if (autostart) await T.autostart.disable(); else await T.autostart.enable();
      autostart = await T.autostart.isEnabled();
      refreshTray();
    }),
    item('Quit Deskling', async () => { await save(true); await T.core.invoke('quit'); }),
  );
  return Menu.new({ items: await Promise.all(items) });
}

async function refreshTray(s = null) {
  if (!tray) return;
  s ||= snapshot();
  const key = JSON.stringify([s.pet, s.mood, s.status, s.here, s.where, s.paused, s.asleep, data.settings, autostart, engine.config, data.lastShare]);
  if (key === trayKey) return;
  const petChanged = !trayKey || JSON.parse(trayKey)[0] !== s.pet;
  trayKey = key;
  if (petChanged) await tray.setIcon(await trayIcon(s.pet));
  await tray.setTooltip(s.here ? `${s.name}: ${s.mood}. ${s.status}` : `${s.name} is ${s.where === SHARED ? 'left by a link' : `with ${s.where}`}`);
  await tray.setMenu(await buildMenu(s));
}

// ---------------------------------------------------------------- what the companion's page uses
export const api = {
  snapshot: async () => snapshot(),
  act: async (a) => act(a),
  layout: async (isOpen) => { if (open !== !!isOpen) { open = !!isOpen; await placeWindow(); } },
  startDrag: () => win.startDragging(),
  /** Where the cursor is, in the window's own points (it may be outside it). */
  cursor: async () => {
    const [c, f] = await Promise.all([T.window.cursorPosition(), win.scaleFactor()]);
    return { x: c.x / f - at.x, y: c.y / f - at.y };
  },
  passThrough: (on) => win.setIgnoreCursorEvents(on),
  hide: () => setVisible(false),
  onState: (f) => listeners.state.push(f),
  onCommand: (f) => listeners.command.push(f),
};

// ---------------------------------------------------------------- start
export async function start() {
  engine.advanceTo(Date.now());
  canNotify = await T.notification.isPermissionGranted() || (await T.notification.requestPermission()) === 'granted';
  autostart = await T.autostart.isEnabled().catch(() => false);
  tray = await TrayIcon.new({ id: 'deskling', icon: await trayIcon(data.pet), tooltip: 'Deskling', menuOnLeftClick: isMac || isLinux,
    action: (e) => { if (e.type === 'Click' && e.button === 'Left' && e.buttonState === 'Up') setVisible(!data.settings.visible); } });
  await refreshTray();
  await placeWindow();
  if (data.settings.visible && data.travel.here) { await win.show(); await applyPin(); await placeWindow(); }
  // the clock (from the Rust side, so it keeps time while the window is hidden), a second instance, links
  await T.event.listen('tick', async () => { await remind(); broadcast(); save(); });
  await T.event.listen('second-instance', () => setVisible(true));
  await T.deepLink.onOpenUrl((urls) => { const u = urls.find((x) => x.startsWith('deskling:')); if (u) receive(u); });
  const startUrls = await T.deepLink.getCurrent().catch(() => null);
  const first = startUrls?.find((x) => x.startsWith('deskling:'));
  if (first) receive(first);
  await remind();
}
