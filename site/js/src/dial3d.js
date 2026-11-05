// The hero render: the printed retainer ring (the real STEP model, converted to STL) holding a modelled
// CrowPanel 1.46" rotary display, whose screen is a live canvas running the pet. Bundled with three.js into
// js/dial3d.js by `npm run build` in site/.
import * as THREE from 'three';
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';

// Ring geometry, measured from the model: its axis tilts 30° toward the front, the circle's centre sits on
// that axis, and the panel's body drops into it while the knob rests on top.
const AXIS = new THREE.Vector3(0.0013, -0.501, 0.8655).normalize();
const CENTRE = new THREE.Vector3(0.0016, -0.33, 21.76);    // on the axis, at H = 19 mm
const H0 = 19;
const RING_TOP = 23.3;                                      // H of the ring's top face

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
  const target = new THREE.Vector3(0, -4, 20);

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
  desk.position.z = -1.6;
  desk.receiveShadow = true;
  scene.add(desk);

  // the printed ring: translucent PETG, lit from inside by the knob's LEDs
  const ledColor = new THREE.Color(led);
  const ringMat = new THREE.MeshPhysicalMaterial({
    color: 0xf2f5f8, roughness: 0.42, transmission: 0.55, thickness: 2.4, ior: 1.57,
    emissive: ledColor.clone(), emissiveIntensity: 0.34, clearcoat: 0.2,
  });
  const stl = new STLLoader().parse(await (await fetch(modelUrl)).arrayBuffer());
  stl.computeVertexNormals();
  const ring = new THREE.Mesh(stl, ringMat);
  ring.castShadow = true; ring.receiveShadow = true;
  scene.add(ring);

  // the panel, built along +Y and turned onto the ring's axis
  const panel = new THREE.Group();
  const at = (h) => h - H0;                                   // heights along the axis, from the ring centre
  const body = new THREE.Mesh(new THREE.CylinderGeometry(25.7, 25.2, 16, 96),
    new THREE.MeshStandardMaterial({ color: 0x1d2026, roughness: 0.75, metalness: 0.1 }));
  body.position.y = at(RING_TOP - 8);
  const knob = new THREE.Mesh(new THREE.CylinderGeometry(30, 30, 9, 160, 1, false),
    new THREE.MeshStandardMaterial({ color: 0x2a2d33, roughness: 0.38, metalness: 0.55, normalMap: knurl(), normalScale: new THREE.Vector2(0.6, 0.6) }));
  knob.material.normalMap.repeat.set(1, 1);
  knob.position.y = at(RING_TOP + 0.6 + 4.5);
  const glass = new THREE.Mesh(new THREE.CylinderGeometry(27.6, 27.6, 0.8, 128),
    new THREE.MeshPhysicalMaterial({ color: 0x050607, roughness: 0.06, metalness: 0, clearcoat: 1, clearcoatRoughness: 0.04 }));
  glass.position.y = at(RING_TOP + 0.6 + 9 + 0.4);
  const screenTex = new THREE.CanvasTexture(screenCanvas);
  screenTex.colorSpace = THREE.SRGBColorSpace;
  screenTex.anisotropy = 4;
  const screen = new THREE.Mesh(new THREE.CircleGeometry(18.6, 96),
    new THREE.MeshBasicMaterial({ map: screenTex, toneMapped: false }));
  screen.rotation.x = -Math.PI / 2;
  screen.position.y = glass.position.y + 0.42;
  // a faint reflection layer over the screen, so it reads as under glass
  const sheen = new THREE.Mesh(new THREE.CircleGeometry(27.4, 96),
    new THREE.MeshPhysicalMaterial({ color: 0xffffff, roughness: 0.05, transparent: true, opacity: 0.06, clearcoat: 1 }));
  sheen.rotation.x = -Math.PI / 2;
  sheen.position.y = screen.position.y + 0.05;
  // the LED ring under the knob, and the light it throws into the PETG
  const ledRing = new THREE.Mesh(new THREE.TorusGeometry(27.2, 0.7, 12, 128),
    new THREE.MeshBasicMaterial({ color: ledColor, toneMapped: false }));
  ledRing.rotation.x = Math.PI / 2;
  ledRing.position.y = at(RING_TOP + 0.3);
  const glow = new THREE.PointLight(ledColor, 900, 70, 2);
  glow.position.y = at(RING_TOP - 2);
  for (const m of [body, knob, glass]) { m.castShadow = true; m.receiveShadow = true; }
  panel.add(body, knob, glass, screen, sheen, ledRing, glow);
  panel.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), AXIS);
  panel.position.copy(CENTRE);
  scene.add(panel);

  // camera: a three-quarter view from the front, drifting a little, and following the pointer a little
  let px = 0, py = 0, tx = 0, ty = 0;
  canvas.addEventListener('pointermove', (e) => {
    const r = canvas.getBoundingClientRect();
    tx = ((e.clientX - r.left) / r.width - 0.5) * 2;
    ty = ((e.clientY - r.top) / r.height - 0.5) * 2;
  });
  canvas.addEventListener('pointerleave', () => { tx = 0; ty = 0; });

  function resize() {
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * renderer.getPixelRatio()) || canvas.height !== Math.round(h * renderer.getPixelRatio())) {
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    }
  }

  let visible = true;
  new IntersectionObserver(([e]) => { visible = e.isIntersecting; }).observe(canvas);
  const t0 = performance.now();
  function frame(t) {
    requestAnimationFrame(frame);
    if (!visible) return;
    resize();
    const s = reduce ? 0 : (t - t0) / 1000;
    px += (tx - px) * 0.05; py += (ty - py) * 0.05;
    const yaw = -0.62 + Math.sin(s * 0.25) * 0.12 + px * 0.25;
    const pitch = 0.3 - py * 0.08;
    const dist = 176;
    camera.position.set(target.x + Math.sin(yaw) * Math.cos(pitch) * dist,
      target.y - Math.cos(yaw) * Math.cos(pitch) * dist, target.z + Math.sin(pitch) * dist);
    camera.lookAt(target);
    screenTex.needsUpdate = true;
    renderer.render(scene, camera);
  }
  requestAnimationFrame(frame);

  return {
    setLed(hex) {
      const c = new THREE.Color(hex);
      ledRing.material.color.copy(c);
      glow.color.copy(c);
      ringMat.emissive.copy(c);
    },
  };
}

/** A normal map of fine vertical ridges for the knob's grip. */
function knurl() {
  const c = document.createElement('canvas');
  c.width = 1024; c.height = 8;
  const g = c.getContext('2d');
  for (let x = 0; x < 1024; x++) {
    const v = Math.sin((x / 1024) * Math.PI * 2 * 180);
    const nx = Math.round(128 + v * 90);
    g.fillStyle = `rgb(${nx},128,255)`;
    g.fillRect(x, 0, 1, 8);
  }
  const t = new THREE.CanvasTexture(c);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  return t;
}
