// The hero render: the printed shell and base (the real models from hardware/, converted to STL) with the
// CrowPanel 1.46" rotary display in them, whose screen is a live canvas running the pet. The display's lit
// skirt sits inside the shell under its collar, so its colour glows in the PETG around the display's edge.
// Bundled with three.js into js/dial3d.js by `npm run build` in site/.
import * as THREE from 'three';
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import { buildPanel, petgMaterial } from './parts3d.js';

export async function mountDial(canvas, screenCanvas, { led = '#4cc8ff', modelUrl }) {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.05;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;

  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

  const camera = new THREE.PerspectiveCamera(26, 1, 1, 2000);
  camera.up.set(0, 0, 1);
  const target = new THREE.Vector3(0, -2, 24);

  // light: a soft key from the front left, a rim from behind, and the shadow on the desk
  const key = new THREE.DirectionalLight(0xffffff, 1.6);
  key.position.set(-60, -90, 140);
  key.castShadow = true;
  key.shadow.mapSize.set(2048, 2048);
  Object.assign(key.shadow.camera, { left: -70, right: 70, top: 70, bottom: -70, near: 10, far: 400 });
  key.shadow.radius = 6;
  scene.add(key);
  const rim = new THREE.DirectionalLight(0xffffff, 0.9);
  rim.position.set(80, 120, 60);
  scene.add(rim);

  const desk = new THREE.Mesh(new THREE.PlaneGeometry(600, 600), new THREE.ShadowMaterial({ opacity: 0.28 }));
  desk.position.z = -0.05;
  desk.receiveShadow = true;
  scene.add(desk);

  // the printed shell and base plate
  const petg = petgMaterial(led);
  const loader = new STLLoader();
  const [shellGeo, baseGeo] = await Promise.all(['shell', 'base'].map(async (n) =>
    loader.parse(await (await fetch(modelUrl(n))).arrayBuffer())));
  for (const geo of [shellGeo, baseGeo]) {
    geo.computeVertexNormals();
    const m = new THREE.Mesh(geo, petg);
    m.castShadow = true; m.receiveShadow = true;
    scene.add(m);
  }

  // the display
  const screenTex = new THREE.CanvasTexture(screenCanvas);
  screenTex.colorSpace = THREE.SRGBColorSpace;
  screenTex.anisotropy = 4;
  const panel = buildPanel({ screenTexture: screenTex, led });
  scene.add(panel.group);

  // camera: looking down at the screen from a little to the side, drifting a little, following the pointer
  let px = 0, py = 0, tx = 0, ty = 0;
  const onMove = (e) => {
    const r = canvas.getBoundingClientRect();
    tx = ((e.clientX - r.left) / r.width - 0.5) * 2;
    ty = ((e.clientY - r.top) / r.height - 0.5) * 2;
  };
  const onLeave = () => { tx = 0; ty = 0; };
  canvas.addEventListener('pointermove', onMove);
  canvas.addEventListener('pointerleave', onLeave);

  function resize() {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * renderer.getPixelRatio()) || canvas.height !== Math.round(h * renderer.getPixelRatio())) {
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    }
  }

  let visible = true, raf = 0;
  const io = new IntersectionObserver(([e]) => { visible = e.isIntersecting; });
  io.observe(canvas);
  const t0 = performance.now();
  function frame(t) {
    raf = requestAnimationFrame(frame);
    if (!visible) return;
    resize();
    const s = reduce ? 0 : (t - t0) / 1000;
    px += (tx - px) * 0.05; py += (ty - py) * 0.05;
    const yaw = -0.34 + Math.sin(s * 0.25) * 0.08 + px * 0.2;
    const pitch = 0.78 - py * 0.06;                  // looking down at the screen, which faces up and forward
    const dist = 228;
    camera.position.set(target.x + Math.sin(yaw) * Math.cos(pitch) * dist,
      target.y - Math.cos(yaw) * Math.cos(pitch) * dist, target.z + Math.sin(pitch) * dist);
    camera.lookAt(target);
    screenTex.needsUpdate = true;
    renderer.render(scene, camera);
  }
  raf = requestAnimationFrame(frame);

  return {
    setLed(hex) {
      panel.setLed(hex);
      petg.emissive.set(hex);
    },
    stop() {
      cancelAnimationFrame(raf);
      io.disconnect();
      canvas.removeEventListener('pointermove', onMove);
      canvas.removeEventListener('pointerleave', onLeave);
      renderer.dispose();
    },
  };
}
