// Colours, one set per pet (the build environment defines PET_CRUMB for Crumb; Wisp is the default).
// The setup page uses the same colours (the _CSS strings). The four need colours (care.cpp) are shared:
// they mean the same thing whichever pet is on the dial.
#pragma once

#if defined(PET_CRUMB)
// Crumb: a pond at dusk. Moss and loam, the amber of his shell, the cream of his face.
#define TH_BG       0x1a2c20
#define TH_PANEL    0x101b13
#define TH_RAISED   0x26392d
#define TH_LINE     0x3a5644
#define TH_ACCENT   0xf3b54a
#define TH_TEXT     0xf2ecda
#define TH_SUB      0x9aa88f
#define TH_DIM      0x4c5c4e
#define TH_SOFT_OK  0x9ed98a      // setup page "done" text
#define TH_SOFT_BAD 0xff9a85
// clock faces: quiet shades of the background so the pet stays in front
#define TH_CLK_BIG   0x2f4935
#define TH_CLK_SPLIT 0x3d5b43
#define TH_CLK_HAND  0x37543d
#define TH_CLK_TICK  0x48684a
#define TH_CLK_WORD  0x4d6a52
#define TH_CLK_WORDHI 0x84a476
// LED ring at rest: awake, and dimmed for the night
#define TH_LED_IDLE  0xffa630
#define TH_LED_NIGHT 0x5a3410
#define TH_BG_CSS     "#1a2c20"
#define TH_PANEL_CSS  "#101b13"
#define TH_RAISED_CSS "#26392d"
#define TH_LINE_CSS   "#3a5644"
#define TH_ROW_CSS    "#203226"
#define TH_ACCENT_CSS "#f3b54a"
#define TH_TEXT_CSS   "#f2ecda"
#define TH_SUB_CSS    "#9aa88f"
#define TH_OK_CSS     "#9ed98a"
#define TH_BAD_CSS    "#ff9a85"
#else
// Wisp: night sky. Navy, ice blue.
#define TH_BG       0x111a2c
#define TH_PANEL    0x0b1220
#define TH_RAISED   0x1e2a42
#define TH_LINE     0x2a3a5a
#define TH_ACCENT   0x9fd8f7
#define TH_TEXT     0xe8eef8
#define TH_SUB      0x8a94a8
#define TH_DIM      0x4a5468
#define TH_SOFT_OK  0x7ee0a0
#define TH_SOFT_BAD 0xff8f8f
#define TH_CLK_BIG   0x27395c
#define TH_CLK_SPLIT 0x34496f
#define TH_CLK_HAND  0x2f4268
#define TH_CLK_TICK  0x3a4e78
#define TH_CLK_WORD  0x3e5279
#define TH_CLK_WORDHI 0x6680b4
#define TH_LED_IDLE  0x4cc8ff
#define TH_LED_NIGHT 0x2a4a8a
#define TH_BG_CSS     "#111a2c"
#define TH_PANEL_CSS  "#0b1220"
#define TH_RAISED_CSS "#1e2a42"
#define TH_LINE_CSS   "#2a3a5a"
#define TH_ROW_CSS    "#1a2640"
#define TH_ACCENT_CSS "#9fd8f7"
#define TH_TEXT_CSS   "#e8eef8"
#define TH_SUB_CSS    "#8a94a8"
#define TH_OK_CSS     "#7ee0a0"
#define TH_BAD_CSS    "#ff8f8f"
#endif
