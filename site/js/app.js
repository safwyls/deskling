// Router, theme and page set-up. Views are hash routes (#home, #flash, #app, #desktop, #wiki, #wiki-<page>) so
// the site works as one static page anywhere, including inside an artifact viewer.
import { Screen, PETS, NEED_COLORS } from './screen.js';

export const WIKI = [
  { group: 'Start here', pages: [
    ['overview', 'What you need'],
    ['print', 'Print the stand'],
    ['models', 'The 3D files'],
    ['display', 'Get the display ready'],
    ['flash', 'Flash the firmware'],
    ['setup', 'First run and Wi-Fi'],
    ['app', 'The phone app'],
    ['using', 'Living with Deskling'],
  ] },
  { group: 'Deep dives', pages: [
    ['firmware', 'Inside the firmware'],
    ['art', 'From sketches to characters'],
    ['animation', 'Clips to dial frames'],
    ['about', 'Open source and AI help'],
  ] },
];
const ALL = WIKI.flatMap((g) => g.pages);

// ---------- theme: the companion you picked ----------
let pet = 'wisp';
try { pet = localStorage.getItem('deskling-pet') || 'wisp'; } catch { /* storage blocked: start with Wisp */ }
if (!PETS[pet]) pet = 'wisp';
const listeners = new Set();
export function currentPet() { return pet; }
export function onPet(fn) { listeners.add(fn); return () => listeners.delete(fn); }
export function setPet(p) {
  if (!PETS[p] || p === pet) return;
  pet = p;
  document.documentElement.dataset.pet = p;
  setFavicon(p);
  try { localStorage.setItem('deskling-pet', p); } catch { /* fine */ }
  for (const fn of listeners) fn(p);
}
document.documentElement.dataset.pet = pet;
setFavicon(pet);

/** The tab icon is the dial's ring in the companion's colours. */
function setFavicon(p) {
  const { bg, accent, led } = PETS[p];
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><defs><radialGradient id="g"><stop offset=".78" stop-color="${led}" stop-opacity="0"/><stop offset=".88" stop-color="${led}" stop-opacity=".6"/><stop offset="1" stop-color="${led}" stop-opacity="0"/></radialGradient></defs><circle cx="32" cy="32" r="32" fill="url(#g)"/><circle cx="32" cy="32" r="24" fill="${bg}" stroke="${accent}" stroke-width="9"/></svg>`;
  const link = document.querySelector('link[rel="icon"]');
  if (link) link.href = `data:image/svg+xml,${encodeURIComponent(svg)}`;
}

// ---------- routing ----------
const main = document.getElementById('main');
let cleanup = [];

function route() {
  const h = location.hash.slice(1) || 'home';
  if (h === 'home' || h === '') return { view: 'home', file: 'pages/home.html' };
  if (h === 'flash') return { view: 'flash', file: 'pages/flash.html' };
  if (h === 'app') return { view: 'app', file: 'pages/app.html' };
  if (h === 'desktop') return { view: 'desktop', file: 'pages/desktop.html' };
  if (h.startsWith('companion=')) return { view: 'companion', file: 'pages/companion.html', code: h.slice(10) };
  if (h === 'wiki') return { view: 'wiki', file: 'pages/wiki/index.html', page: 'index' };
  if (h.startsWith('wiki-')) {
    const page = h.slice(5);
    if (ALL.some(([id]) => id === page)) return { view: 'wiki', file: `pages/wiki/${page}.html`, page };
  }
  return { view: 'home', file: 'pages/home.html' };
}

async function render() {
  for (const fn of cleanup) fn();
  cleanup = [];
  listeners.clear();
  const r = route();
  document.querySelectorAll('[data-nav]').forEach((a) => { if (a.dataset.nav === r.view) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
  let html;
  try { html = await (await fetch(r.file)).text(); } catch { html = '<div class="wrap loading">This page couldn’t load.</div>'; }
  if (r.view === 'wiki') html = wikiLayout(html, r.page);
  main.innerHTML = html;
  window.scrollTo(0, 0);
  const title = main.querySelector('[data-title]')?.dataset.title || main.querySelector('h1')?.textContent;
  document.title = r.view === 'home' ? 'Deskling: a desk pet that looks after you' : `${title || 'Deskling'} – Deskling`;
  if (r.view === 'home') (await import('./home.js')).init(main, { cleanup });
  if (r.view === 'flash') (await import('./flash-page.js')).init(main, { cleanup });
  if (r.view === 'app') (await import('./app-page.js')).init(main, { cleanup });
  if (r.view === 'desktop') (await import('./desktop-page.js')).init(main, { cleanup });
  if (r.view === 'companion') (await import('./companion-page.js')).init(main, { cleanup, code: r.code });
  if (r.view === 'wiki' && r.page === 'models') (await import('./models-page.js')).init(main, { cleanup });
  wirePetButtons(main);
  wireScreens(main);
}

function wikiLayout(html, page) {
  const nav = WIKI.map((g) => `<h4>${g.group}</h4>` + g.pages.map(([id, t]) =>
    `<a href="#wiki-${id}"${id === page ? ' aria-current="page"' : ''}>${t}</a>`).join('')).join('');
  const i = ALL.findIndex(([id]) => id === page);
  const prev = i > 0 ? ALL[i - 1] : null, next = i >= 0 && i < ALL.length - 1 ? ALL[i + 1] : (page === 'index' ? ALL[0] : null);
  const pager = page === 'index' ? '' : `<div class="pager">${prev ? `<a href="#wiki-${prev[0]}"><span>Previous</span><b>${prev[1]}</b></a>` : ''}${next ? `<a class="next" href="#wiki-${next[0]}"><span>Next</span><b>${next[1]}</b></a>` : ''}</div>`;
  return `<div class="wrap wiki"><nav class="side" aria-label="Build guide"><a href="#wiki"${page === 'index' ? ' aria-current="page"' : ''}>Guide home</a>${nav}</nav><article class="doc">${html}${pager}</article></div>`;
}

/** Any button with data-pet-choice switches the companion (and the site's colours). */
function wirePetButtons(root) {
  const sync = () => root.querySelectorAll('[data-pet-choice]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.petChoice === pet)));
  root.querySelectorAll('[data-pet-choice]').forEach((b) => b.addEventListener('click', () => setPet(b.dataset.petChoice)));
  sync();
  onPet(sync);
}

/** Any canvas with data-screen="pose" becomes a round dial screen; data-fixed-pet pins it to one pet. */
function wireScreens(root) {
  root.querySelectorAll('canvas[data-screen]').forEach((c) => {
    const fixed = c.dataset.fixedPet;
    const s = new Screen(c, { pet: fixed || pet, pose: c.dataset.screen, size: Number(c.dataset.size || 360) });
    cleanup.push(() => s.stop());
    if (!fixed) onPet((p) => s.set(p, c.dataset.screen));
  });
}

addEventListener('hashchange', render);
render();

export { NEED_COLORS };
