// The 3D files page: an orbitable view of the stand's printed parts, together or one at a time, with an
// exploded view that shows how they go together. Bundled into js/models3d.js by `npm run build`.
import * as THREE from 'three';
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import { AXIS, buildPanel } from './parts3d.js';

// how far each part moves in the exploded view (mm): the display lifts out of the top, the retainer drops
// out of the bottom along the display's axis, and the base drops straight down
const EXPLODE = {
  display: AXIS.clone().multiplyScalar(48),
  retainer: AXIS.clone().multiplyScalar(-34),
  base: new THREE.Vector3(0, 0, -64),
  shell: new THREE.Vector3(),
};

export async function mountModels(canvas, { modelUrl, accent = '#9fd8f7' }) {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
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
  const load = async (n) => {
    const g = loader.parse(await (await fetch(modelUrl(n))).arrayBuffer());
    g.computeVertexNormals();
    return g;
  };
  const [shellGeo, retGeo, baseGeo] = await Promise.all(['shell', 'retainer', 'base'].map(load));

  const shellMat = new THREE.MeshStandardMaterial({ color: 0xe9eef4, roughness: 0.5, transparent: true, opacity: 1 });
  const parts = {
    shell: new THREE.Mesh(shellGeo, shellMat),
    retainer: new THREE.Mesh(retGeo, new THREE.MeshStandardMaterial({ color: new THREE.Color(accent), roughness: 0.55 })),
    base: new THREE.Mesh(baseGeo, new THREE.MeshStandardMaterial({ color: 0x8d9bb0, roughness: 0.6 })),
    display: buildPanel({ lit: false }).group,
  };
  for (const p of Object.values(parts)) { p.userData.home = p.position.clone(); scene.add(p); }

  let show = 'all', exploded = false, explodeT = 0;
  const visibleParts = () => (show === 'all' ? ['shell', 'retainer', 'base', 'display'] : [show]);

  function frameView(instant) {
    // frame the parts where they're heading, not where they are mid-animation
    const t = exploded && show === 'all' ? 1 : 0;
    const box = new THREE.Box3();
    for (const n of visibleParts()) {
      const p = parts[n], was = p.position.clone();
      p.position.copy(p.userData.home).addScaledVector(EXPLODE[n], t);
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

  let raf = 0;
  function frame() {
    raf = requestAnimationFrame(frame);
    resize();
    const want = exploded && show === 'all' ? 1 : 0;
    explodeT += reduce ? want - explodeT : (want - explodeT) * 0.12;
    for (const [n, p] of Object.entries(parts)) p.position.copy(p.userData.home).addScaledVector(EXPLODE[n], explodeT);
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
    explode(on) { exploded = on; if (show === 'all') frameView(false); },
    stop() { cancelAnimationFrame(raf); controls.dispose(); renderer.dispose(); },
  };
}
