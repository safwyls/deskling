// The app page: the download button, its version and checksum, and the screenshot in your companion's colours.
import { APK_BASE } from './hosting.js';
import { onPet, currentPet } from './app.js';

export async function init(root) {
  const shot = root.querySelector('#app-shot');
  const showPet = (p) => { shot.src = `img/app-${p}.webp`; };
  showPet(currentPet());
  onPet(showPet);

  const btn = root.querySelector('#apk'), meta = root.querySelector('#apk-meta'), sha = root.querySelector('#apk-sha');
  let m = null;
  try { const r = await fetch('app/manifest.json'); if (r.ok) m = await r.json(); } catch { /* not published here */ }
  if (!m) {
    // nothing built into this copy of the site yet: point at the source instead of a dead link
    btn.removeAttribute('download');
    btn.href = 'https://github.com/safwyls/deskling/tree/main/app';
    btn.textContent = 'Get the source';
    meta.textContent = 'The app hasn’t been published to this site yet. You can build it from the source.';
    return;
  }
  btn.href = new URL(m.file, new URL(APK_BASE, location.href)).href;
  if (new URL(btn.href).origin !== location.origin) btn.removeAttribute('download');
  meta.textContent = `Version ${m.version}, ${(m.size / 1e6).toFixed(1)} MB, Android ${m.minAndroid} or newer`;
  sha.textContent = m.sha256;
}
