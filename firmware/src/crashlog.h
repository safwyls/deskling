// Why the dial last restarted, and the crash dump the chip saves to flash when firmware panics.
// The dump survives reflashing the app, so a crash report from older firmware still shows up.
#pragma once
#include <Arduino.h>

void crashlog_begin();          // first thing in setup()
const char *crashlog_reason();  // "power on", "crash", "brownout (power dip)", ...
bool crashlog_bad();            // that restart was a crash, watchdog or brownout
bool crashlog_has_dump();       // a crash dump is stored in flash
const char *crashlog_summary(); // one or two short lines for the screen
const char *crashlog_qr();      // the full report on one line, for a QR code
void crashlog_clear();          // erase the stored dump
// Note internal RAM just before something risky (kept in RTC memory, so it survives a crash and reset);
// the next boot's report includes it. crashlog_note_done() when it went fine.
void crashlog_note(const char *where);
void crashlog_note_done();
// Internal RAM now: free, smallest free since boot, largest block (bytes).
void crashlog_heap(uint32_t &freeB, uint32_t &minB, uint32_t &largest);
