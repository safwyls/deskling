// Gather what the desktop app draws from the Android app's assets (the one source for the art):
//   web/pets/<pet>/<loop>.json + .bin   each loop's WebP frames back to back, offsets in the JSON (as on the website)
//   web/emotes/*.png                 thought bubbles and the activity icons
// Run by `npm run assets` (and before every build). web/pets/ and web/emotes/ are not committed.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const src = path.join(root, '..', 'app', 'app', 'src', 'main', 'assets');
const PETS = ['wisp', 'crumb', 'sprig'];
const LOOPS = ['walk', 'happy', 'thirsty', 'tired', 'stretch', 'move', 'sad', 'mad', 'sleep'];

let n = 0;
for (const pet of PETS) {
  const out = path.join(root, 'web', 'pets', pet);
  fs.mkdirSync(out, { recursive: true });
  for (const loop of LOOPS) {
    const dir = path.join(src, 'pets', pet, loop);
    const meta = JSON.parse(fs.readFileSync(path.join(dir, 'meta.json'), 'utf8'));
    const blobs = Array.from({ length: meta.count }, (_, i) => fs.readFileSync(path.join(dir, `${String(i).padStart(3, '0')}.webp`)));
    let at = 0;
    meta.frames = blobs.map((b) => { const r = [at, b.length]; at += b.length; return r; });
    fs.writeFileSync(path.join(out, `${loop}.json`), JSON.stringify(meta));
    fs.writeFileSync(path.join(out, `${loop}.bin`), Buffer.concat(blobs));
    n++;
  }
}
const emotes = path.join(root, 'web', 'emotes');
fs.mkdirSync(emotes, { recursive: true });
for (const f of fs.readdirSync(path.join(src, 'emotes'))) fs.copyFileSync(path.join(src, 'emotes', f), path.join(emotes, f));
console.log(`packed ${n} loops into web/pets/, copied the emotes to web/emotes/`);
