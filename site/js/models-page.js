// The 3D files page: mounts the parts viewer and wires its buttons.
import { MODEL_EXT } from './hosting.js';

export async function init(root, { cleanup }) {
  const box = root.querySelector('#viewer');
  if (!box) return;
  const canvas = box.querySelector('canvas');
  const accent = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#9fd8f7';
  let viewer;
  try {
    const { mountModels } = await import('./models3d.js');
    viewer = await mountModels(canvas, { accent, modelUrl: (n) => new URL(`../model/${n}.${MODEL_EXT}`, import.meta.url) });
  } catch (e) {
    box.querySelector('.viewer-hint').textContent = 'This browser can’t show 3D here (WebGL is off or unavailable). The files below still download.';
    canvas.remove();
    box.querySelector('.viewer-bar').remove();
    return;
  }
  cleanup.push(() => viewer.stop());
  const explode = box.querySelector('#explode');
  box.querySelectorAll('[data-show]').forEach((b) => b.addEventListener('click', () => {
    box.querySelectorAll('[data-show]').forEach((o) => o.setAttribute('aria-pressed', String(o === b)));
    viewer.show(b.dataset.show);
    explode.disabled = b.dataset.show !== 'all';
  }));
  explode.addEventListener('change', () => viewer.explode(explode.checked));
}
