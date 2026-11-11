// The 3D files page: an orbitable view of the stand's printed parts, together or one at a time, with an
// exploded view that shows how they go together. Bundled into js/models3d.js by `npm run build`.
import * as THREE from 'three';
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import { AXIS, buildPanel } from './parts3d.js';

// two versions of the stand, and the file behind each part: 'usbc' (the USB-C stand: shell and deck) and 'cable'
// (the display's own cable, from the v7 design)
const VARIANTS = {
  usbc: { shell: 'shell_usbc', base: 'base_usbc', boards: 'breakout', cable: 'cable_usbc' },
  cable: { shell: 'shell_cable', base: 'base_cable', cable: 'cable' },
};
const INSIDE = ['boards', 'cable'];

// how far each part moves in the exploded view (mm): the display lifts out of the top along its axis, and the
// base drops straight down with what sits on it
const DOWN = (z) => new THREE.Vector3(0, 0, z);
function explodeOf(n) {
  if (n === 'display') return AXIS.clone().multiplyScalar(48);
  if (n === 'shell') return DOWN(0);
  return DOWN(-64);
}

export async function mountModels(canvas, { modelUrl, accent = '#9fd8f7', onLost = () => {}, variant: startVariant = 'usbc' }) {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const lite = matchMedia('(pointer: coarse)').matches;
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'low-power' });
  renderer.setPixelRatio(Math.min(devicePixelRatio, lite ? 1.5 : 2));
  let raf = 0;
  canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); cancelAnimationFrame(raf); onLost(); });
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;

  const scene = new THREE.Scene();
  scene.environment = new THREE.PMREMGenerator(renderer).fromScene(new RoomEnvironment(), 0.04).texture;
  const key = new THREE.DirectionalLight(0xffffff, 1.4);
  key.position.set(-80, -120, 160);
  scene.add(key, new THREE.HemisphereLight(0xffffff, 0x334455, 0.5));

  const camera = new THREE.PerspectiveCamera(30, 1, 1, 3000);
  camera.up.set(0, 0, 1);
  const controls = new OrbitControls(camera, canvas);
  controls.enableDamping = true;
  controls.minDistance = 60;
  controls.maxDistance = 600;

  const loader = new STLLoader();
  const geos = {};
  const load = (n) => (geos[n] ??= fetch(modelUrl(n)).then((r) => {
    if (!r.ok) throw new Error(`${n}: ${r.status}`);
    return r.arrayBuffer();
  }).then((b) => { const g = loader.parse(b); g.computeVertexNormals(); return g; }));
  const need = (v) => Object.values(VARIANTS[v]);
  await Promise.all(need(startVariant).map(load));

  const shellMat = new THREE.MeshStandardMaterial({ color: 0xe9eef4, roughness: 0.5, transparent: true, opacity: 1 });
  const empty = new THREE.BufferGeometry();
  const parts = {
    shell: new THREE.Mesh(empty, shellMat),
    base: new THREE.Mesh(empty, new THREE.MeshStandardMaterial({ color: new THREE.Color(accent), roughness: 0.6 })),
    display: buildPanel({ lit: false }).group,
    // stand-ins for the parts you buy: the USB-C breakout and the display's cable
    boards: new THREE.Mesh(empty, new THREE.MeshStandardMaterial({ color: 0x2b7a57, roughness: 0.6 })),
    cable: new THREE.Mesh(empty, new THREE.MeshStandardMaterial({ color: 0x2a2f38, roughness: 0.7 })),
  };
  for (const p of Object.values(parts)) { p.userData.home = p.position.clone(); scene.add(p); }

  let variant = startVariant;
  async function useVariant(v) {
    const files = VARIANTS[v];
    const geo = await Promise.all(Object.values(files).map(load));
    variant = v;
    Object.keys(files).forEach((n, i) => { parts[n].geometry = geo[i]; });
  }
  const has = (n) => n === 'display' || n in VARIANTS[variant];
  await useVariant(startVariant);

  let show = 'all', exploded = false, explodeT = 0;
  const visibleParts = () => (show === 'all' ? ['shell', 'base', 'display', ...INSIDE]
    : show === 'base' ? ['base', ...INSIDE] : [show]).filter(has);

  function frameView(instant) {
    // frame the parts where they're heading, not where they are mid-animation
    const t = exploded && show === 'all' ? 1 : 0;
    const box = new THREE.Box3();
    for (const n of visibleParts()) {
      const p = parts[n], was = p.position.clone();
      p.position.copy(p.userData.home).addScaledVector(explodeOf(n), t);
      p.updateMatrixWorld(true);
      box.expandByObject(p);
      p.position.copy(was);
      p.updateMatrixWorld(true);
    }
    const sphere = box.getBoundingSphere(new THREE.Sphere());
    const dist = sphere.radius / Math.sin(THREE.MathUtils.degToRad(camera.fov / 2)) * 0.78;
    const dir = new THREE.Vector3(-0.55, -0.85, 0.62).normalize();
    goal = { target: sphere.center.clone(), position: sphere.center.clone().addScaledVector(dir, dist) };
    if (instant) { controls.target.copy(goal.target); camera.position.copy(goal.position); goal = null; }
  }
  let goal = null;

  function apply() {
    for (const [n, p] of Object.entries(parts)) p.visible = visibleParts().includes(n);
    // with everything shown, the shell goes see-through so the parts inside it are visible
    shellMat.opacity = show === 'all' ? 0.32 : 1;
    shellMat.depthWrite = show !== 'all';
  }

  function resize() {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * renderer.getPixelRatio()) || canvas.height !== Math.round(h * renderer.getPixelRatio())) {
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    }
  }

  function frame() {
    raf = requestAnimationFrame(frame);
    resize();
    const want = exploded && show === 'all' ? 1 : 0;
    explodeT += reduce ? want - explodeT : (want - explodeT) * 0.12;
    for (const [n, p] of Object.entries(parts)) p.position.copy(p.userData.home).addScaledVector(explodeOf(n), explodeT);
    if (goal) {
      const k = reduce ? 1 : 0.12;
      controls.target.lerp(goal.target, k);
      camera.position.lerp(goal.position, k);
      if (camera.position.distanceTo(goal.position) < 0.2) goal = null;
    }
    controls.update();
    renderer.render(scene, camera);
  }
  resize();
  apply();
  frameView(true);
  raf = requestAnimationFrame(frame);
  controls.addEventListener('start', () => { goal = null; });

  return {
    show(name) { show = name; apply(); frameView(false); },
    async variant(v) { await useVariant(v); apply(); frameView(false); },
    explode(on) { exploded = on; if (show === 'all') frameView(false); },
    stop() { cancelAnimationFrame(raf); controls.dispose(); renderer.dispose(); },
  };
}
