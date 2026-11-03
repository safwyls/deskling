// Clock faces for the home screen, drawn behind Wisp in quiet colours.
// One face per parent; the pet screen has one and Settings has another for the live preview.
#pragma once
#include <lvgl.h>

enum ClockStyle : uint8_t { CS_CLASSIC, CS_BIG, CS_SPLIT, CS_ANALOG, CS_RIM, CS_WORDS, CS_COUNT };

struct ClockFace;
ClockFace *clockface_create(lv_obj_t *parent);       // a full-screen layer; create it before what should sit in front
void clockface_set(ClockFace *f, int style, int mode);   // mode: 0 off, 1 12-hour, 2 24-hour
void clockface_update(ClockFace *f, bool force = false); // call about once a second; redraws when the minute changes
void clockface_show(ClockFace *f, bool on);
const char *clockface_style_name(int style);
int clockface_style_count();
const char *clockface_style_note(int style);
