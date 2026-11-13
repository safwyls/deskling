// Bluetooth (BLE) link for the phone app: the same JSON requests as POST /api, one message per line.
// Service 6d7a0001-5f2b-4b6c-9e2d-3a1f0c0d5e11: the phone writes to ...0002 and gets answers as notifications
// on ...0003, in chunks; a newline ends each message. The first message on a connection is hello (with the
// phone's key) or pair.
#pragma once
#include <Arduino.h>

void ble_begin();               // after api_begin(); skipped if internal RAM is too short
const char *ble_status();       // "off", "on" (advertising), "connected"
const char *ble_detail();
// The dial can't run Bluetooth (~75 KB) and its own setup network (~40 KB) at once: Bluetooth pauses while the
// setup network is up. ble_resume() brings it back once that memory is free again (call it again if it can't yet).
void ble_pause();
void ble_resume();
bool ble_paused();
void ble_rename(const char *name);   // advertise under a new name (straight away, or once the phone disconnects)       // for About: whether it started, and the RAM it took or lacked
