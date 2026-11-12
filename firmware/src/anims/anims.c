// Unpacks animation frames into PSRAM as they're shown (see anims.h).
//
// The first time round a loop each frame is inflated just before it's drawn: a few milliseconds per frame, well
// inside a 24 fps frame. After that the loop plays from PSRAM. Loops stay unpacked while they fit in BUDGET;
// past that, the least recently shown loop is dropped (its frames' data set to NULL, which LVGL draws as
// nothing until the frame is asked for again and unpacked anew). The loop being asked for is never dropped.
#include "anims.h"
#include <string.h>
#include <stdbool.h>
#include <esp_heap_caps.h>
#include "miniz.h"      // the inflater in the ESP32-S3's ROM

#define BUDGET (3u * 1024 * 1024)
#define MAX_LOADED 48
#define PSRAM (MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT)

static const PetAnim *loaded[MAX_LOADED];
static int nLoaded = 0;
static size_t held = 0;
static uint32_t clock_ = 0;
static tinfl_decompressor *inf = NULL;    // 11 KB of state: in PSRAM, not on the loop task's stack

static size_t frame_bytes(const PetAnim *a) { return 1024 + (size_t)a->w * a->h; }
static size_t anim_bytes(const PetAnim *a) { return frame_bytes(a) * a->count; }

static void drop(int k) {
  const PetAnim *a = loaded[k];
  AnimSlot *s = a->slot;
  for (int i = 0; i < a->count; i++) s->dsc[i].data = NULL;
  heap_caps_free(s->buf); s->buf = NULL;
  heap_caps_free(s->ready); s->ready = NULL;
  held -= anim_bytes(a);
  loaded[k] = loaded[--nLoaded];
}

// drop least recently used loops (never `keep`) until `need` more bytes fit the budget, or until none are left
static void make_room(const PetAnim *keep, size_t need, bool all) {
  while (nLoaded > 0 && (all || held + need > BUDGET || nLoaded >= MAX_LOADED)) {
    int lru = -1;
    for (int k = 0; k < nLoaded; k++)
      if (loaded[k] != keep && (lru < 0 || loaded[k]->slot->used < loaded[lru]->slot->used)) lru = k;
    if (lru < 0) return;
    drop(lru);
  }
}

static bool load(const PetAnim *a) {
  AnimSlot *s = a->slot;
  if (!s->dsc) {
    s->dsc = (lv_image_dsc_t *)heap_caps_calloc(a->count, sizeof(lv_image_dsc_t), PSRAM);
    if (!s->dsc) return false;
    for (int i = 0; i < a->count; i++) {
      lv_image_header_t *h = &s->dsc[i].header;
      h->magic = LV_IMAGE_HEADER_MAGIC; h->cf = LV_COLOR_FORMAT_I8; h->flags = 0;
      h->w = a->w; h->h = a->h; h->stride = a->w;
      s->dsc[i].data_size = frame_bytes(a);
    }
  }
  size_t need = anim_bytes(a);
  make_room(a, need, false);
  s->buf = (uint8_t *)heap_caps_malloc(need, PSRAM);
  if (!s->buf) { make_room(a, need, true); s->buf = (uint8_t *)heap_caps_malloc(need, PSRAM); }
  s->ready = (uint8_t *)heap_caps_calloc(a->count, 1, PSRAM);
  if (!s->buf || !s->ready) {
    heap_caps_free(s->buf); s->buf = NULL;
    heap_caps_free(s->ready); s->ready = NULL;
    return false;
  }
  loaded[nLoaded++] = a;
  held += need;
  return true;
}

static bool unpack(const PetAnim *a, int i) {
  if (!inf) inf = (tinfl_decompressor *)heap_caps_malloc(sizeof(tinfl_decompressor), PSRAM);
  if (!inf) return false;
  uint8_t *out = a->slot->buf + frame_bytes(a) * i;
  memcpy(out, a->pal, 1024);
  size_t inLen = a->zoff[i + 1] - a->zoff[i], outLen = (size_t)a->w * a->h;
  tinfl_init(inf);
  tinfl_status st = tinfl_decompress(inf, a->z + a->zoff[i], &inLen, out + 1024, out + 1024, &outLen,
                                     TINFL_FLAG_USING_NON_WRAPPING_OUTPUT_BUF);
  if (st != TINFL_STATUS_DONE || outLen != (size_t)a->w * a->h) memset(out + 1024, 0, (size_t)a->w * a->h);
  return true;
}

const lv_image_dsc_t *pet_anim_frame(const PetAnim *a, int i) {
  if (i < 0 || i >= a->count) i = 0;
  AnimSlot *s = a->slot;
  if (!s->buf && !load(a)) return NULL;
  if (!s->ready[i]) {
    if (!unpack(a, i)) return NULL;
    s->ready[i] = 1;
    s->dsc[i].data = s->buf + frame_bytes(a) * i;
  }
  s->used = ++clock_;
  return &s->dsc[i];
}

size_t pet_anim_cache_bytes() { return held; }
