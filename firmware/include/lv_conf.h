/* LVGL 9.1 configuration for Deskling on the CrowPanel 1.46" (360 x 360, RGB565). */
#ifndef LV_CONF_H
#define LV_CONF_H

#include <stdint.h>

#define LV_COLOR_DEPTH 16
#define LV_USE_OS LV_OS_NONE

/* Use the C library allocator: on the ESP32-S3 with PSRAM, large allocations land in PSRAM automatically. */
#define LV_USE_STDLIB_MALLOC  LV_STDLIB_CUSTOM      // src/lv_mem_psram.c: LVGL's heap lives in PSRAM
#define LV_USE_STDLIB_STRING  LV_STDLIB_CLIB
#define LV_USE_STDLIB_SPRINTF LV_STDLIB_CLIB

/* Redraw at up to ~60 Hz; the pet's frame timer decides the actual animation rate. */
#define LV_DEF_REFR_PERIOD 16

#define LV_USE_ARC   1
#define LV_USE_IMAGE 1
#define LV_USE_LABEL 1
#define LV_USE_QRCODE 1   /* Settings > Set up from phone */

/* Wisp's frames are 8-bit indexed with a transparent palette; LVGL decodes each one to ARGB8888 before
   drawing. Cache the decoded frames (in PSRAM) so a frame is decoded once, not once per render band. */
#define LV_CACHE_DEF_SIZE (2 * 1024 * 1024)

#define LV_FONT_MONTSERRAT_14 1
#define LV_FONT_MONTSERRAT_20 1
#define LV_FONT_MONTSERRAT_28 1
#define LV_FONT_MONTSERRAT_48 1
#define LV_FONT_DEFAULT &lv_font_montserrat_20

#define LV_USE_THEME_DEFAULT 1
#define LV_THEME_DEFAULT_DARK 1

#define LV_BUILD_EXAMPLES 0
#define LV_USE_DEMO_WIDGETS 0
#define LV_USE_LOG 0
#define LV_USE_ASSERT_NULL 1
#define LV_USE_ASSERT_MALLOC 1

#endif /* LV_CONF_H */
