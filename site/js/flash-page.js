// The firmware page: choices, the settings form, a live preview, and the write over Web Serial.
import { Screen, PETS } from './screen.js';
import { currentPet, onPet } from './app.js';
import { CAN_FLASH_HERE } from './hosting.js';

const $ = (root, s) => root.querySelector(s);
const mb = (n) => `${(n / 1048576).toFixed(1)} MB`;

export async function init(root, { cleanup }) {
  let pet = currentPet();
  const manifest = await (await fetch('fw/manifest.json')).json();
  for (const p of Object.keys(PETS)) {
    const el = $(root, `#size-${p}`);
    if (el) el.textContent = `Firmware ${manifest.version}, ${mb(manifest.apps[p].size)}`;
  }

  // ---------- form ----------
  const every = [0, 5, 10, 15, 20, 25, 30, 40, 45, 50, 60, 75, 90, 120];
  root.querySelectorAll('select[data-every]').forEach((s) => {
    for (const v of every) s.add(new Option(v ? `Every ${v} min` : 'Off', v, false, v === Number(s.dataset.every)));
  });
  root.querySelectorAll('select[data-range]').forEach((s) => {
    const [a, b, st] = s.dataset.range.split(',').map(Number);
    for (let v = a; v <= b; v += st) s.add(new Option(`${v} ${s.dataset.unit}`, v, false, v === Number(s.dataset.v)));
  });
  const hour = (h) => h === 0 || h === 24 ? 'Midnight' : h === 12 ? 'Noon' : h < 12 ? `${h} am` : `${h - 12} pm`;
  for (let h = 0; h < 24; h++) $(root, '#hs').add(new Option(hour(h), h, false, h === 8));
  for (let h = 1; h <= 24; h++) $(root, '#he').add(new Option(hour(h), h, false, h === 20));
  const outs = () => {
    $(root, 'output[for=br]').value = `${$(root, '#br').value}%`;
    $(root, 'output[for=lb]').value = `${Math.round($(root, '#lb').value / 2.55)}%`;
  };
  outs();
  root.querySelectorAll('input[type=range]').forEach((r) => r.addEventListener('input', outs));
  const postureFields = () => { for (const id of ['#sit', '#stand']) $(root, id).disabled = !$(root, '#post').checked; };
  $(root, '#post').addEventListener('change', postureFields);
  postureFields();

  const mode = () => root.querySelector('input[name=mode]:checked').value;
  const settings = () => ({
    ssid: $(root, '#ssid').value.trim(), pass: $(root, '#pass').value,
    remind: [0, 1, 2, 3].map((i) => Number($(root, `#r${i}`).value)),
    hourStart: Number($(root, '#hs').value), hourEnd: Number($(root, '#he').value), tz: Number($(root, '#tz').value),
    postureOn: $(root, '#post').checked, sitMin: Number($(root, '#sit').value), standMin: Number($(root, '#stand').value),
    clockMode: Number($(root, '#cm').value), clockStyle: Number($(root, '#cs').value),
    showDate: $(root, '#sd').checked, showStatus: $(root, '#ss').checked, showNext: $(root, '#sn').checked,
    bright: Number($(root, '#br').value), ledBright: Number($(root, '#lb').value), napMin: Number($(root, '#nap').value),
    knobInvert: $(root, '#inv').checked, ledOffset: -2,
  });

  // ---------- preview and summary ----------
  const preview = new Screen($(root, '#preview'), { pet, pose: 'happy', home: true, size: 360 });
  cleanup.push(() => preview.stop());
  const summary = () => {
    const m = mode(), s = settings(), P = PETS[pet];
    const rows = [['Companion', P.name], ['Firmware', `${manifest.version}, ${mb(manifest.apps[pet].size)}`]];
    if (m === 'install') {
      rows.push(['Wi-Fi', s.ssid || 'later, on the dial'], ['Awake', `${hour(s.hourStart)} to ${hour(s.hourEnd)}`],
        ['Sit/stand', s.postureOn ? `${s.sitMin} / ${s.standMin} min` : 'off']);
      const total = manifest.apps[pet].size + 0x5000 + 64 * 1024;
      rows.push(['To write', `about ${mb(total)}`]);
    } else rows.push(['Settings', 'kept as they are'], ['To write', mb(manifest.apps[pet].size)]);
    $(root, '#summary').innerHTML = rows.map(([k, v]) => `<div><span>${k}</span><b>${escapeHtml(v)}</b></div>`).join('');
    $(root, '#preview-caption').textContent = `${P.name} on your dial, in ${P.name}’s colours.`;
    $(root, '#settings-fields').disabled = m !== 'install';
    $(root, '#settings-off').hidden = m === 'install';
    $(root, '#settings-block').classList.toggle('collapsed', m !== 'install');
  };
  summary();
  root.addEventListener('input', summary);
  root.addEventListener('change', summary);
  onPet((p) => { pet = p; preview.set(p, 'happy'); summary(); });

  // ---------- can this browser write to USB devices here? ----------
  const { serialStatus } = await import('./flasher.js');
  const support = $(root, '#support'), go = $(root, '#go');
  const st = CAN_FLASH_HERE ? serialStatus() : 'framed';
  if (st !== 'ok') {
    support.hidden = false;
    support.innerHTML = st === 'unsupported'
      ? '<b>This browser can&rsquo;t talk to USB devices.</b> Writing firmware needs Web Serial, which Chrome and Edge have on desktop computers. Firefox, Safari and phones don&rsquo;t. You can still look around and set things up here.'
      : '<b>You&rsquo;re viewing a preview inside another page,</b> which isn&rsquo;t allowed to reach USB devices. Open the site on its own, at <a href="https://safwyls.github.io/deskling/#flash">safwyls.github.io/deskling</a>, to write firmware.';
    go.disabled = true;
  }

  // ---------- write ----------
  const bar = $(root, '#bar'), status = $(root, '#status'), log = $(root, '#log');
  go.addEventListener('click', async () => {
    const { flash } = await import('./flasher.js');
    go.disabled = true;
    log.style.display = 'block'; log.textContent = '';
    const s = settings();
    if (mode() === 'install' && s.ssid && s.pass && s.pass.length < 8) {
      status.textContent = 'That Wi-Fi password is shorter than 8 characters, which WPA2 doesn’t allow. Check it, or leave both fields empty.';
      go.disabled = false;
      return;
    }
    try {
      await flash({
        pet, mode: mode(), settings: s,
        onLog: (t) => { log.textContent += t.endsWith('\n') ? t : t + '\n'; log.scrollTop = log.scrollHeight; },
        onProgress: (f, label) => { bar.style.width = `${Math.round(f * 100)}%`; status.textContent = `${label}${f > 0 && f < 1 ? `, ${Math.round(f * 100)}%` : ''}`; },
      });
      status.innerHTML = `<b style="color:var(--text)">Done.</b> ${PETS[pet].name} is starting up on your dial. ${mode() === 'install' ? 'Next: <a href="#wiki-setup">first run</a>.' : ''}`;
    } catch (e) {
      const m = String(e?.message || e);
      status.textContent = /No port selected|NotFoundError/i.test(m)
        ? 'No display chosen. Press the button again and pick it from the list.'
        : /Failed to connect|Timeout|timed out/i.test(m)
          ? 'The display didn’t answer. Put it in download mode (hold BOOT, tap RESET, let go of BOOT) and try again.'
          : `That didn’t work: ${m}`;
    } finally {
      go.disabled = false;
    }
  });
}

function escapeHtml(s) { return String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }
