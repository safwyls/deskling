// Home: the 3D dial whose screen plays a little story, recoloured with the chosen companion.
import { Screen, PETS, NEED_COLORS } from './screen.js';
import { currentPet, onPet } from './app.js';
import { MODEL } from './hosting.js';

const STORY = [
  { pose: 'content', status: (p) => ({ text: 'Water in 12 min', color: PETS[p].sub }), led: null,
    caption: (n) => `<b>${n} is content.</b> Nothing is due yet.` },
  { pose: 'thirsty', status: () => ({ text: 'Water is due', color: NEED_COLORS.water }), led: NEED_COLORS.water,
    caption: (n) => `<b>Thirsty.</b> Water is due, so the ring breathes blue.` },
  { pose: 'happy', status: (p) => ({ text: 'Glass 3 today', color: PETS[p].accent }), led: null,
    caption: (n) => `<b>Happy.</b> You drank a glass and tapped ${n}.` },
  { pose: 'tired', status: () => ({ text: 'Eye break is due', color: NEED_COLORS.eyes }), led: NEED_COLORS.eyes,
    caption: () => `<b>Tired.</b> Time to look away from the screen.` },
  { pose: 'stretch', status: () => ({ text: 'Stretch is due', color: NEED_COLORS.stretch }), led: NEED_COLORS.stretch,
    caption: () => `<b>Restless.</b> A stretch would help you both.` },
];

export async function init(root, { cleanup }) {
  const stage = root.querySelector('#stage');
  const caption = root.querySelector('#caption');
  const gl = stage.querySelector('canvas.gl');
  const screenCanvas = document.createElement('canvas');
  let pet = currentPet();
  let step = 0;
  const screen = new Screen(screenCanvas, { pet, pose: STORY[0].pose, home: true, size: 512, status: STORY[0].status(pet), onFrame: () => {} });
  cleanup.push(() => screen.stop());
  const { mountDial } = await import('./dial3d.js');
  let dial;
  try {
    dial = await mountDial(gl, screenCanvas, { led: PETS[pet].led, modelUrl: new URL('../' + MODEL, import.meta.url) });
  } catch (e) {
    // no WebGL: show the screen flat instead
    gl.replaceWith(Object.assign(screenCanvas, { className: 'round', style: 'width:70%;margin:10% auto' }));
  }
  const show = () => {
    const s = STORY[step];
    screen.set(pet, s.pose, s.status(pet));
    dial?.setLed(s.led || PETS[pet].led);
    caption.innerHTML = s.caption(PETS[pet].name);
  };
  show();
  const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const timer = still ? 0 : setInterval(() => { step = (step + 1) % STORY.length; show(); }, 5200);
  cleanup.push(() => clearInterval(timer));
  onPet((p) => { pet = p; show(); });
}
