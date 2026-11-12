#include "companion.h"
#include <Preferences.h>

// Wisp: night sky. Navy, ice blue.
static const Theme WISP_TH = {
  0x111a2c, 0x0b1220, 0x1e2a42, 0x2a3a5a, 0x1a2640, 0x9fd8f7, 0xe8eef8, 0x8a94a8, 0x4a5468,
  0x7ee0a0, 0xff8f8f,
  0x27395c, 0x34496f, 0x2f4268, 0x3a4e78, 0x3e5279, 0x6680b4,
  0x4cc8ff, 0x2a4a8a,
};
// Crumb: a pond at dusk. Moss and loam, the amber of his shell, the cream of his face.
static const Theme CRUMB_TH = {
  0x1a2c20, 0x101b13, 0x26392d, 0x3a5644, 0x203226, 0xf3b54a, 0xf2ecda, 0x9aa88f, 0x4c5c4e,
  0x9ed98a, 0xff9a85,
  0x2f4935, 0x3d5b43, 0x37543d, 0x48684a, 0x4d6a52, 0x84a476,
  0xffa630, 0x5a3410,
};
// Sprig: blossom at dusk. Plum shade, the green of its leaves, the cream of its face, mauve for quiet text.
static const Theme SPRIG_TH = {
  0x32223a, 0x21152a, 0x412f4b, 0x56405f, 0x3a2943, 0xa9db7f, 0xf5f1e3, 0xab9aae, 0x655269,
  0x9fe3b0, 0xff8fa3,
  0x45324f, 0x55405f, 0x4d3a58, 0x624c6c, 0x664f70, 0xa58cae,
  0x7cff4a, 0x2a4a14,
};

static const Companion WISP = { "wisp", "Wisp", &WISP_TH,
  &wisp_walk, &wisp_run, &wisp_jump, &wisp_sleep, &wisp_happy, &wisp_sad, &wisp_mad, &wisp_thirsty, &wisp_tired,
  &wisp_restless, &wisp_move, true };
// Crumb and Sprig have no Stretch or Move clips yet: the nearest loop stands in (hopping about; the walk).
// Sprig's thirsty clip has no bubble of its own, so the pose adds one.
static const Companion CRUMB = { "crumb", "Crumb", &CRUMB_TH,
  &crumb_walk, &crumb_run, &crumb_jump, &crumb_sleep, &crumb_happy, &crumb_sad, &crumb_mad, &crumb_thirsty, &crumb_tired,
  &crumb_jump, &crumb_run, true };
static const Companion SPRIG = { "sprig", "Sprig", &SPRIG_TH,
  &sprig_walk, &sprig_run, &sprig_jump, &sprig_sleep, &sprig_happy, &sprig_sad, &sprig_mad, &sprig_thirsty, &sprig_tired,
  &sprig_jump, &sprig_run, false };

const Companion *const COMPANIONS[] = { &WISP, &CRUMB, &SPRIG };
static const int N = sizeof(COMPANIONS) / sizeof(COMPANIONS[0]);
static int cur = 0;

int companion_count() { return N; }
int companion_find(const char *id) {
  for (int i = 0; i < N; i++) if (id && !strcmp(id, COMPANIONS[i]->id)) return i;
  return -1;
}

void companion_begin() {
  Preferences p;
  p.begin("deskling", true);
  String id = p.getString("pet", "");
  p.end();
  int i = companion_find(id.c_str());
  cur = i < 0 ? 0 : i;
}

const Companion *companion() { return COMPANIONS[cur]; }
int companion_index() { return cur; }

void companion_set(int i) {
  if (i < 0 || i >= N) return;
  Preferences p;
  p.begin("deskling", false);
  p.putString("pet", COMPANIONS[i]->id);
  p.end();
}
