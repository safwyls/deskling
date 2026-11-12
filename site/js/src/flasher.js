// Writes a Deskling firmware (and optionally the dial's settings) to the CrowPanel over Web Serial, with
// Espressif's esptool-js. Bundled into js/flasher.js by `npm run build` in site/.
import { ESPLoader, Transport } from 'esptool-js';
import { dialSettingsNvs } from '../nvs.js';

export function serialStatus() {
  if (!('serial' in navigator)) return 'unsupported';     // Firefox, Safari, phones
  if (window.top !== window) return 'framed';             // inside another page's frame: the port picker is blocked
  return 'ok';
}

async function bytes(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`couldn't load ${url} (${r.status})`);
  return new Uint8Array(await r.arrayBuffer());
}

/**
 * opts: { pet, mode: 'install'|'update', settings, onLog(text), onProgress(fraction, label) }
 * install: bootloader, partition table, boot data, the app, and the settings with the chosen companion
 *          (replaces everything on the dial)
 * update:  the app only (settings, pairing, the companion and its progress are kept)
 */
export async function flash({ pet, mode, settings, onLog, onProgress }) {
  const base = new URL('../fw/', import.meta.url);
  const manifest = await (await fetch(new URL('manifest.json', base))).json();
  const app = manifest.app;
  onProgress(0, 'Loading the firmware');
  const files = [];
  if (mode === 'install') {
    files.push({ label: 'bootloader', address: manifest.parts.bootloader.address, data: await bytes(new URL(manifest.parts.bootloader.file, base)) });
    files.push({ label: 'partition table', address: manifest.parts.partitions.address, data: await bytes(new URL(manifest.parts.partitions.file, base)) });
    files.push({ label: 'settings', address: manifest.parts.nvs.address, data: dialSettingsNvs({ ...settings, pet }) });
    files.push({ label: 'boot data', address: manifest.parts.otadata.address, data: await bytes(new URL(manifest.parts.otadata.file, base)) });
  }
  files.push({ label: 'firmware', address: app.address, data: await bytes(new URL(app.file, base)) });

  onProgress(0, 'Choose the dial in the browser’s list');
  const port = await navigator.serial.requestPort();
  const transport = new Transport(port, false);
  const terminal = { clean() {}, writeLine: (s) => onLog(s), write: (s) => onLog(s) };
  const loader = new ESPLoader({ transport, baudrate: 921600, romBaudrate: 115200, terminal });
  try {
    onProgress(0, 'Connecting');
    const chip = await loader.main();
    onLog(`Connected: ${chip}`);
    if (!/ESP32-S3/i.test(chip)) throw new Error(`that's an ${chip}, not the CrowPanel's ESP32-S3`);
    const total = files.reduce((n, f) => n + f.data.length, 0);
    const before = files.map((_, i) => files.slice(0, i).reduce((n, f) => n + f.data.length, 0));
    await loader.writeFlash({
      fileArray: files.map((f) => ({ data: f.data, address: f.address })),
      flashMode: 'keep', flashFreq: 'keep', flashSize: 'keep',
      eraseAll: false, compress: true,
      reportProgress: (i, written, size) => {
        const done = before[i] + (written / size) * files[i].data.length;
        onProgress(done / total, `Writing the ${files[i].label}`);
      },
    });
    onProgress(1, 'Restarting the dial');
    await loader.after('hard_reset');
  } finally {
    try { await transport.disconnect(); } catch { /* already gone */ }
  }
}
