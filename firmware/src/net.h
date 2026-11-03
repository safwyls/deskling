// Wi-Fi, on one background task that owns the radio: keeps the saved network joined (retrying with a
// backoff), scans, tests new credentials, and runs the dial's own setup network. The UI never blocks on it.
#pragma once
#include <Arduino.h>

void net_begin();

// ---- scanning ----
struct WifiNet { char ssid[33]; int8_t rssi; bool open; uint8_t ch; };
enum ScanState : uint8_t { SCAN_IDLE, SCAN_BUSY, SCAN_DONE, SCAN_FAILED };
void net_scan();                                // async; poll net_scan_state()
ScanState net_scan_state();
int net_scan_count();
const WifiNet &net_scan_net(int i);             // strongest first, one entry per name

// ---- joining ----
void net_try(const char *ssid, const char *pass);   // connect with these (not saved); watch WiFi.status()
bool net_try_pending();                         // the task hasn't started that attempt yet
void net_use_saved();                           // go back to the saved network
// While the dial runs its own setup network, background reconnects would move the radio's channel and
// drop the phone. Hold stops them; explicit tries and net_use_saved() still work.
void net_hold(bool on);

// What the Wi-Fi driver said about the current attempt, for the failure screen.
struct WifiDiag {
  uint8_t lastReason;        // last disconnect reason (0 = none yet); see esp_wifi_types WIFI_REASON_*
  uint8_t authFails;         // disconnects that mean "the password was refused"
  uint8_t expired;           // AUTH_EXPIRE: the router didn't answer the join request
  bool associated;           // joined the access point at least once
  bool gotIp;                // got an address from the router
};
WifiDiag net_diag();
const char *net_reason_name(uint8_t reason);

// ---- the setup network ----
enum ApState : int8_t { AP_FAILED = -1, AP_OFF, AP_STARTING, AP_UP };
void net_ap_start(const char *ssid, const char *pass, uint8_t channel);
void net_ap_stop();
ApState net_ap_state();
const char *net_ap_error();                     // why it failed, for the screen
