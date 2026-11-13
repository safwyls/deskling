// The phone app's link to the dial: JSON requests over Wi-Fi (POST /api, key in X-Deskling-Key) or Bluetooth
// (the same JSON, one message per line). Requests are carried out on the main loop, so the care model and
// settings are only ever touched from there.
//
// Pairing: Settings > Phone app shows a 6-digit code; the app sends {"op":"pair","code":"123456","phone":"Pixel"}
// and gets a key back, which it sends with every later request (Wi-Fi) or once per connection (Bluetooth:
// {"op":"hello","key":"..."}). Up to 4 phones.
#pragma once
#include <Arduino.h>

void api_begin();                     // after settings_begin() and care_begin()
void api_poll();                      // main loop: answers queued requests, starts the Wi-Fi API and mDNS

// From any task. authed: in, whether this connection already proved a key (or the request carried one);
// out, whether it has now (after hello/pair). Blocks until the main loop has answered (or 5 s).
bool api_call(const char *json, bool &authed, String &out);
bool api_key_ok(const char *hexKey);

// pairing, for the Phone app page
void api_pair_open(bool on);          // show a fresh code and accept pairing while on (closes itself after 3 min)
bool api_pair_is_open();
const char *api_pair_code();
const char *api_last_paired();        // name of the phone that paired while the page was open, or ""
int api_paired_count();
void api_forget_all();
#define DIAL_NAME_MAX 24              // fits a Bluetooth scan response with room to spare
const char *api_dial_name();          // what it's called: the owner's name for it, or its id ("Deskling-3456")
const char *api_dial_id();            // "Deskling-3456" (from the chip): mDNS host and setup network, never changes
