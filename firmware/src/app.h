// Shared app-level helpers: screen switching and backlight.
#pragma once
#include <lvgl.h>
#include "input.h"
#include "anims/anims.h"

#define PET_BG 0x111a2c   // must match BG in tools/make_assets.py

void app_show_pet();
void app_show_setup();
void app_show_hub();      // the menu a press on the pet screen opens
void backlight(int pct);

// Pet screen
void pet_create();
lv_obj_t *pet_screen();
void pet_enter();
void pet_input(InputEvent e);
void pet_tick();
void pet_activity();      // any input anywhere resets the sleep timer
void pet_open_ring();     // show the activities (care) ring
bool pet_is_idle();       // the bare home screen: no ring, stats or activity
void pet_remote_done(int need);   // a break logged on the phone: a happy moment here too
// Poses: the moods and activities, and what each one plays (for Settings > Poses)
int pet_pose_count();
const char *pet_pose_name(int i);
const char *pet_pose_use(int i);
bool pet_pose_has_clip(int i);                 // false: falls back to another loop plus a bubble
const PetAnim *pet_pose_anim(int i);
const lv_image_dsc_t *pet_pose_emote(int i);
void pet_emote_pos(const PetAnim *a, int frame, int &x, int &y);   // emote bubble position for that frame



// Settings
void setup_create();
lv_obj_t *setup_screen();
void setup_enter();
void setup_enter_hub();
void setup_input(InputEvent e);
void setup_tick();
