// The companions. Every one is in the firmware; the dial shows the one chosen in Settings (or by the app, or
// the website's installer), stored in NVS. Choosing another saves it and restarts, so everything is built once
// for the companion it shows: colours, name, loops.
#pragma once
#include <Arduino.h>
#include "anims/anims.h"

// Colours. The setup page uses the same ones. The four need colours (care.cpp) are shared: they mean the same
// thing whichever companion is on the dial.
struct Theme {
  uint32_t bg, panel, raised, line, row, accent, text, sub, dim;
  uint32_t softOk, softBad;                 // setup page "done" text
  uint32_t clkBig, clkSplit, clkHand, clkTick, clkWord, clkWordHi;   // clock faces: quiet shades of the background
  uint32_t ledIdle, ledNight;               // LED ring at rest: awake, and dimmed for the night
};

struct Companion {
  const char *id, *name;
  const Theme *theme;
  const PetAnim *walk, *run, *jump, *sleep, *happy, *sad, *mad, *thirsty, *tired;
  const PetAnim *restless, *move;           // a stand-in loop until the companion has its own
  bool thirstyHasBubble;                    // the thirsty clip draws its own water bubble
};

extern const Companion *const COMPANIONS[];
int companion_count();
int companion_find(const char *id);          // -1 if unknown
void companion_begin();                      // before anything draws: reads the choice from NVS
const Companion *companion();                // the one this dial is set to
int companion_index();
void companion_set(int i);                   // saves the choice; takes effect after a restart

// the current companion, for the screens
#define PET (companion())
#define TH (companion()->theme)
#define PET_ID (PET->id)
#define PET_NAME (PET->name)
#define PET_BG (TH->bg)       // the screen behind the pet (frames are transparent, so any colour works)
#define TH_BG (TH->bg)
#define TH_PANEL (TH->panel)
#define TH_RAISED (TH->raised)
#define TH_LINE (TH->line)
#define TH_ACCENT (TH->accent)
#define TH_TEXT (TH->text)
#define TH_SUB (TH->sub)
#define TH_DIM (TH->dim)
#define TH_CLK_BIG (TH->clkBig)
#define TH_CLK_SPLIT (TH->clkSplit)
#define TH_CLK_HAND (TH->clkHand)
#define TH_CLK_TICK (TH->clkTick)
#define TH_CLK_WORD (TH->clkWord)
#define TH_CLK_WORDHI (TH->clkWordHi)
#define TH_LED_IDLE (TH->ledIdle)
#define TH_LED_NIGHT (TH->ledNight)
