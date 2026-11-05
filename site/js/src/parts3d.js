// Shared 3D pieces for the home page dial and the 3D files page: the stand's coordinate frame, the CrowPanel
// display model, and the translucent PETG with the LED glow in it.
//
// The frame comes from hardware/crowpanel_stand_v3.py. World is Z up with the desk at z = 0 and -Y toward the
// user. The display sits on an axis tipped 30° toward the user; "a" is the distance along that axis from the
// display's bottom plate, whose centre is at ORIGIN. All three printed parts are modelled in this same frame.
import * as THREE from 'three';

const TILT = Math.PI / 6;
export const AXIS = new THREE.Vector3(0, -Math.sin(TILT), Math.cos(TILT));
const R_OUT = 60.15 / 2 + 0.25 + 1.6;                        // cup outer radius
export const ORIGIN = new THREE.Vector3(0, 0, 8.5 + 2.4 * Math.cos(TILT) + R_OUT * Math.sin(TILT));
export const at = (a) => ORIGIN.clone().addScaledVector(AXIS, a);

// The CrowPanel 1.46" rotary display, from Elecrow's STEP (radii and heights in mm along the axis):
const BASE_BAND = { r: 30.075, a0: 0, a1: 7 };       // fixed base, stops against the shell's printed shoulder
export const SKIRT = { r: 28.83, a0: 7, a1: 12.1 };  // the turning, lit skirt: this is where the LEDs glow
const CORE = { r: 23.6, a0: 12.1, a1: 17 };
const GRIP = { r: 27.0, a0: 17, a1: 28.3 };          // black grip band, the only part above the shell's collar
export const SCREEN_A = GRIP.a1;

function band({ r, a0, a1 }, material, segments = 128) {
  const m = new THREE.Mesh(new THREE.CylinderGeometry(r, r, a1 - a0, segments), material);
  m.position.y = (a0 + a1) / 2;
  return m;
}

/** Side profile for a LatheGeometry: radius r from y0 to y1, top edge rounded by e. */
function roundedProfile(r, y0, y1, e) {
  const pts = [new THREE.Vector2(0, y0), new THREE.Vector2(r, y0), new THREE.Vector2(r, y1 - e)];
  for (let i = 1; i <= 8; i++) {
    const t = (i / 8) * Math.PI / 2;
    pts.push(new THREE.Vector2(r - e + Math.cos(t) * e, y1 - e + Math.sin(t) * e));
  }
  pts.push(new THREE.Vector2(0, y1));
  return pts;
}

/**
 * The display, placed in the stand. With a screen texture it gets a live screen under glass; without one it's
 * a plain proxy (for the 3D files page). Returns { group, setLed }.
 */
export function buildPanel({ screenTexture = null, led = '#4cc8ff', lit = true } = {}) {
  const g = new THREE.Group();
  const dark = new THREE.MeshStandardMaterial({ color: 0x2a2d33, roughness: 0.7, metalness: 0.05 });
  // smooth moulded plastic, not knurled
  const gripMat = new THREE.MeshPhysicalMaterial({ color: 0x1d2025, roughness: 0.5, metalness: 0, clearcoat: 0.2, clearcoatRoughness: 0.5 });
  const skirtMat = lit
    ? new THREE.MeshBasicMaterial({ color: new THREE.Color(led), toneMapped: false })
    : new THREE.MeshStandardMaterial({ color: 0xdfe6ee, roughness: 0.4 });
  const grip = new THREE.Mesh(new THREE.LatheGeometry(roundedProfile(GRIP.r, GRIP.a0, GRIP.a1, 1.4), 160), gripMat);
  g.add(band(BASE_BAND, dark), band(SKIRT, skirtMat), band(CORE, dark), grip);

  // cover glass and screen
  const glass = new THREE.Mesh(new THREE.CircleGeometry(GRIP.r - 1.5, 128),
    new THREE.MeshPhysicalMaterial({ color: 0x050607, roughness: 0.08, metalness: 0, clearcoat: 0.5, clearcoatRoughness: 0.05, envMapIntensity: 0.12 }));
  glass.rotation.x = -Math.PI / 2;
  glass.position.y = SCREEN_A + 0.02;
  g.add(glass);
  if (screenTexture) {
    const screen = new THREE.Mesh(new THREE.CircleGeometry(18.5, 96), new THREE.MeshBasicMaterial({ map: screenTexture, toneMapped: false }));
    screen.rotation.x = -Math.PI / 2;
    screen.position.y = SCREEN_A + 0.06;
    const sheen = new THREE.Mesh(new THREE.CircleGeometry(GRIP.r - 1.5, 96),
      new THREE.MeshPhysicalMaterial({ color: 0xffffff, roughness: 0.05, transparent: true, opacity: 0.025, clearcoat: 1 }));
    sheen.rotation.x = -Math.PI / 2;
    sheen.position.y = SCREEN_A + 0.1;
    g.add(screen, sheen);
  }
  g.traverse((m) => { if (m.isMesh) { m.castShadow = true; m.receiveShadow = true; } });

  g.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), AXIS);
  g.position.copy(ORIGIN);
  return { group: g, setLed: (hex) => { if (lit) skirtMat.color.set(hex); } };
}

/**
 * Translucent PETG lit from inside by the display's skirt: the emissive colour fades with distance from the
 * skirt, so the glow sits in the shell around the display's edge rather than over the whole part.
 */
export function petgMaterial(led = '#4cc8ff', { falloff = 7 } = {}) {
  const mat = new THREE.MeshPhysicalMaterial({
    color: 0xf2f5f8, roughness: 0.42, transmission: 0.55, thickness: 2.4, ior: 1.57, clearcoat: 0.2,
    emissive: new THREE.Color(led), emissiveIntensity: 1.6,
  });
  const centre = at((SKIRT.a0 + SKIRT.a1) / 2);
  mat.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, {
      uRingC: { value: centre }, uAxis: { value: AXIS.clone() }, uRingR: { value: SKIRT.r }, uFall: { value: falloff },
    });
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vWPos;')
      .replace('#include <project_vertex>', '#include <project_vertex>\nvWPos = (modelMatrix * vec4(transformed, 1.0)).xyz;');
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vWPos;\nuniform vec3 uRingC;\nuniform vec3 uAxis;\nuniform float uRingR;\nuniform float uFall;')
      .replace('#include <emissivemap_fragment>', `#include <emissivemap_fragment>
        {
          vec3 d = vWPos - uRingC;
          float h = dot(d, uAxis);
          float rr = length(d - h * uAxis) - uRingR;
          totalEmissiveRadiance *= exp(-sqrt(h * h + rr * rr) / uFall);
        }`);
  };
  return mat;
}
