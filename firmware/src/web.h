// Phone setup page: Wi-Fi and the dial's settings on one page.
// It is served over your network when the dial is on Wi-Fi, or over the dial's own setup network
// ("Deskling-XXXX", with a DNS catch-all so the phone opens the page by itself) when it isn't.
#pragma once
#include <Arduino.h>

void web_begin();           // the page only, on whatever network the dial is on
// The dial's own network as well. channel: the router's, so joining it later doesn't move the network
// out from under the phone. Fills in the name and password to show on the screen.
// Asynchronous: poll web_ap_poll() until it says 1 (up, page served) or -1 (failed).
bool web_ap_start(uint8_t channel, char *ssid, size_t ssidLen, char *pass, size_t passLen);
int web_ap_poll();
void web_setup(bool on);    // serve the setup page (only while Set up from phone is open)
void web_stop_ap();         // the setup network and DNS
void web_stop();            // setup page and setup network; the server stays while on Wi-Fi (the app's API)
bool web_ap_active();
int web_ap_clients();       // phones joined to the setup network
void web_loop();            // call often while the page is up (answers DNS)
uint32_t web_last_request(); // millis() of the last page request

bool web_take_saved();      // true once after the dial settings were saved

// The page asking to join a Wi-Fi network. The UI runs the attempt and reports back.
bool web_take_wifi(char *ssid, size_t ssidLen, char *pass, size_t passLen);
enum WebWifiState : uint8_t { WW_IDLE, WW_TRYING, WW_OK, WW_FAIL };
void web_set_wifi(WebWifiState st, const char *ssid, const char *msg);
