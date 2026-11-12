// The desktop app page: a download for the system you're on, the others below it, their checksums, and the
// screenshot in your companion's colours.
import { DESKTOP_BASE } from './hosting.js';
import { onPet, currentPet } from './app.js';

const NAMES = { windows: 'Windows', mac: 'macOS', appimage: 'Linux (AppImage)', deb: 'Debian and Ubuntu (.deb)' };

function system() {
  const p = `${navigator.userAgentData?.platform || ''} ${navigator.platform || ''} ${navigator.userAgent}`;
  if (/Win/i.test(p)) return 'windows';
  if (/Mac/i.test(p) && !/iPhone|iPad/i.test(p)) return 'mac';
  if (/Ubuntu|Debian|Mint|Pop/i.test(p)) return 'deb';
  if (/Linux|X11|CrOS/i.test(p) && !/Android/i.test(p)) return 'appimage';
  return null;
}

export async function init(root) {
  const shot = root.querySelector('#desk-shot');
  const showPet = (p) => { shot.src = `img/desktop-${p}.webp`; };
  showPet(currentPet());
  onPet(showPet);

  let m = null;
  try { const r = await fetch(`${DESKTOP_BASE}manifest.json`); if (r.ok) m = await r.json(); } catch { /* not published here */ }
  if (!m || !m.files) return;            // the page already points at the source
  const base = new URL(DESKTOP_BASE, location.href);
  const link = (key) => {
    const a = document.createElement('a');
    a.href = new URL(m.files[key].file, base).href;
    if (a.origin === location.origin) a.download = m.files[key].file;
    return a;
  };
  const mb = (key) => `${(m.files[key].size / 1e6).toFixed(0)} MB`;
  const keys = Object.keys(NAMES).filter((k) => m.files[k]);

  // the main button: this computer's download if there is one, else the first there is
  const mine = system();
  const main = keys.includes(mine) ? mine : null;
  const btn = root.querySelector('#desk-main'), meta = root.querySelector('#desk-meta');
  if (main) {
    const a = link(main);
    btn.href = a.href;
    if (a.download) btn.download = a.download; else btn.removeAttribute('download');
    btn.textContent = `Download for ${NAMES[main]}`;
    meta.textContent = `Version ${m.version}, ${mb(main)}`;
  } else {
    btn.remove();
    meta.textContent = `Version ${m.version}. Choose your system:`;
  }

  // the others, as plain links
  const others = root.querySelector('#desk-others');
  const rest = keys.filter((k) => k !== main);
  if (rest.length) {
    others.append(main ? 'Also for ' : '');
    rest.forEach((k, i) => {
      const a = link(k);
      a.textContent = NAMES[k];
      a.title = mb(k);
      if (i) others.append(i === rest.length - 1 ? ' and ' : ', ');
      others.append(a);
    });
    others.hidden = false;
  }

  const dl = root.querySelector('#desk-sha');
  dl.replaceChildren(...keys.flatMap((k) => {
    const dt = document.createElement('dt'), dd = document.createElement('dd');
    dt.textContent = m.files[k].file;
    dd.textContent = m.files[k].sha256;
    dd.className = 'hash';
    return [dt, dd];
  }));
}
