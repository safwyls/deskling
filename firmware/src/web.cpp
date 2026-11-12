// Phone setup page on ESP-IDF's HTTP server. It runs on its own task with several sockets, so it doesn't
// depend on the UI loop and copes with browsers that open parallel or speculative connections (the
// polled, one-connection-at-a-time Arduino WebServer didn't: mobile Chrome gave up with ERR_TOO_MANY_RETRIES).
#include "web.h"
#include "settings.h"
#include "net.h"
#include "care.h"
#include "api.h"
#include "companion.h"
#include <WiFi.h>
#include <DNSServer.h>
#include <esp_http_server.h>
#include <lwip/sockets.h>
#include <esp_heap_caps.h>

int clockface_style_count();            // clockface.h pulls in LVGL; these two are all the page needs
const char *clockface_style_name(int style);

static httpd_handle_t srv = nullptr;
static volatile bool saved = false;
static Settings S;                    // only touched by the server task

// ---------- connection log, for chasing browser trouble: GET /log ----------
// Everything here runs on the server task (open/close callbacks and handlers alike), so no locking.
// The question it answers: does the dial close connections a browser opened but hasn't used yet?
// Chrome sends its next request down such a spare connection, finds it closed, retries, and after
// a few of those shows ERR_TOO_MANY_RETRIES.
struct LogEv { uint32_t ms; int16_t fd; uint16_t port; char kind; uint8_t peer; uint16_t ramK; char what[44]; };
static const int LOG_N = 64;
static LogEv *logv = nullptr;            // in PSRAM: internal RAM is what the setup network runs short of
static int logHead = 0, logCount = 0;
struct Conn { int fd; uint32_t openMs, lastMs; uint16_t reqs, port; uint8_t peer; };
static Conn conns[16];
static uint32_t nOpen = 0, nIdleClosed = 0, nPeerClosed = 0, nReqs = 0;

static Conn *conn_of(int fd) { for (auto &c : conns) if (c.fd == fd) return &c; return nullptr; }

static void log_ev(char kind, int fd, const char *fmt, ...) {
  if (!logv) logv = (LogEv *)heap_caps_calloc(LOG_N, sizeof(LogEv), MALLOC_CAP_SPIRAM);
  if (!logv) return;
  LogEv &e = logv[logHead];
  logHead = (logHead + 1) % LOG_N;
  if (logCount < LOG_N) logCount++;
  e.ms = millis(); e.kind = kind; e.fd = fd;
  Conn *c = conn_of(fd);
  e.port = c ? c->port : 0; e.peer = c ? c->peer : 0;
  e.ramK = heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024;
  va_list a; va_start(a, fmt); vsnprintf(e.what, sizeof(e.what), fmt, a); va_end(a);
}

static esp_err_t on_open(httpd_handle_t, int fd) {
  struct sockaddr_storage ss; socklen_t sl = sizeof(ss);
  uint8_t peer = 0; uint16_t port = 0;
  if (getpeername(fd, (struct sockaddr *)&ss, &sl) == 0) {
    if (ss.ss_family == AF_INET) { auto *a = (struct sockaddr_in *)&ss; peer = ntohl(a->sin_addr.s_addr) & 0xff; port = ntohs(a->sin_port); }
    else if (ss.ss_family == AF_INET6) { auto *a = (struct sockaddr_in6 *)&ss; peer = a->sin6_addr.s6_addr[15]; port = ntohs(a->sin6_port); }
  }
  for (auto &c : conns) if (c.fd <= 0) { c = { fd, millis(), millis(), 0, port, peer }; break; }
  nOpen++;
  int n = 0; for (auto &c : conns) n += c.fd > 0;
  log_ev('O', fd, "open (%d open)", n);
  return ESP_OK;
}

static void on_close(httpd_handle_t, int fd) {
  // Who's closing? If the browser already sent its FIN, a peek reads 0 bytes. If it would block,
  // the browser still has it open and it's the dial dropping it.
  char b; int r = recv(fd, &b, 1, MSG_PEEK | MSG_DONTWAIT);
  bool peerGone = r == 0;
  Conn *c = conn_of(fd);
  uint16_t reqs = c ? c->reqs : 0;
  uint32_t idle = c ? (millis() - c->lastMs) : 0;
  if (peerGone) nPeerClosed++;
  else if (!reqs) nIdleClosed++;
  log_ev(reqs ? 'C' : (peerGone ? 'c' : '!'), fd, "%s, %u req, idle %lu ms",
         peerGone ? "closed by phone" : (reqs ? "closed by dial" : "UNUSED, closed by dial"), reqs, (unsigned long)idle);
  if (c) c->fd = 0;
  close(fd);
}

static void log_req(httpd_req_t *req) {
  int fd = httpd_req_to_sockfd(req);
  char host[40] = "";
  httpd_req_get_hdr_value_str(req, "Host", host, sizeof(host));
  Conn *c = conn_of(fd);
  if (c) { c->reqs++; c->lastMs = millis(); }
  nReqs++;
  log_ev('R', fd, "%s %.20s%.20s", req->method == HTTP_POST ? "POST" : "GET", strcmp(host, "192.168.4.1") ? host : "", req->uri);
}

// The server answers these itself and drops the connection: worth seeing, a timeout most of all.
static esp_err_t on_http_err(httpd_req_t *req, httpd_err_code_t err) {
  log_ev('E', httpd_req_to_sockfd(req), "server error %d (408 = request timed out)", (int)err);
  httpd_resp_send_err(req, err, nullptr);
  return ESP_FAIL;
}

static esp_err_t log_get(httpd_req_t *req) {
  httpd_resp_set_hdr(req, "Connection", "close");
  httpd_sess_trigger_close(req->handle, httpd_req_to_sockfd(req));
  httpd_resp_set_type(req, "text/plain; charset=utf-8");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  String o;
  o.reserve(6000);
  char ln[120];
  snprintf(ln, sizeof(ln), "Deskling connection log, up %lu s, internal RAM %u KB free (lowest %u KB)\n",
           (unsigned long)(millis() / 1000), (unsigned)(heap_caps_get_free_size(MALLOC_CAP_INTERNAL) / 1024),
           (unsigned)(heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL) / 1024));
  o += ln;
  snprintf(ln, sizeof(ln), "%lu connections, %lu requests, %lu closed by the phone, %lu closed by the dial unused\n\n",
           (unsigned long)nOpen, (unsigned long)nReqs, (unsigned long)nPeerClosed, (unsigned long)nIdleClosed);
  o += ln;
  o += "  seconds  conn            RAM  event\n";
  int first = (logHead - logCount + LOG_N) % LOG_N;
  for (int i = 0; logv && i < logCount; i++) {
    const LogEv &e = logv[(first + i) % LOG_N];
    snprintf(ln, sizeof(ln), "%9.3f  %c .%u:%-5u fd%-2d %3uK  %s\n", e.ms / 1000.0, e.kind, e.peer, e.port, e.fd, e.ramK, e.what);
    o += ln;
  }
  o += "\nO open  R request  C/c closed after use (by dial/by phone)  ! closed by the dial before any request\n";
  return httpd_resp_send(req, o.c_str(), o.length());
}

static String esc(const char *s) {
  String o;
  for (; *s; s++) {
    switch (*s) {
      case '&': o += "&amp;"; break;
      case '<': o += "&lt;"; break;
      case '>': o += "&gt;"; break;
      case '"': o += "&quot;"; break;
      default: o += *s;
    }
  }
  return o;
}

// application/x-www-form-urlencoded value -> plain text, in place
static void url_decode(char *s) {
  char *o = s;
  for (; *s; s++) {
    if (*s == '+') *o++ = ' ';
    else if (*s == '%' && isxdigit((unsigned char)s[1]) && isxdigit((unsigned char)s[2])) {
      char h[3] = { s[1], s[2], 0 };
      *o++ = (char)strtol(h, nullptr, 16);
      s += 2;
    } else *o++ = *s;
  }
  *o = 0;
}

static const char HEAD[] = R"(<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Deskling setup</title><style>)" R"(
body{font:16px system-ui,sans-serif;background:var(--bg);color:var(--text);margin:0;padding:24px;max-width:520px}
h1{font-size:22px;margin:0 0 4px}h2{font-size:18px;margin:34px 0 0;padding-top:18px;border-top:1px solid var(--line)}
p{color:var(--sub);margin:4px 0 18px}label{display:block;margin:16px 0 6px;color:var(--accent)}
input,textarea,select{box-sizing:border-box;width:100%;padding:10px;border-radius:8px;border:1px solid var(--line);
background:var(--panel);color:var(--text);font:15px ui-monospace,monospace}textarea{height:150px}select{font:15px system-ui,sans-serif}
input[type=range]{padding:0;border:0;background:none}input[type=checkbox]{width:auto;margin:0 8px 0 0;transform:scale(1.3)}
button{margin-top:22px;width:100%;padding:12px;border:0;border-radius:8px;background:var(--accent);color:var(--bg);font-size:17px}
small{color:var(--sub)}a{color:var(--accent)}
.box{border:1px solid var(--line);border-radius:8px;background:var(--panel);margin-top:6px}
.it{display:flex;align-items:center;gap:8px;padding:9px 10px;border-top:1px solid var(--row)}
.it:first-child{border-top:0}.it .n{flex:1;min-width:0}.it .n b{display:block;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.it .n i{display:block;font:12px ui-monospace,monospace;color:var(--sub);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.it button{margin:0;width:auto;padding:6px 10px;font-size:15px;background:var(--raised);color:var(--text)}
.it button.add{background:var(--accent);color:var(--bg)}.list{max-height:340px;overflow:auto}
.net{cursor:pointer}.net.on{background:var(--raised)}.net.on b{color:var(--accent)}
.row{display:flex;gap:10px;align-items:center}.row>*{flex:1}.chk{display:flex;align-items:center;margin:12px 0;color:var(--text)}
.msg{margin-top:10px;color:var(--sub);min-height:1.2em}.msg.ok{color:var(--ok)}.msg.bad{color:var(--bad)}
#status{color:var(--sub);padding:10px}.empty{color:var(--sub);padding:10px})";

// The page head: the styles above with the companion's colours.
static String head() {
  const Theme *t = TH;
  char vars[260];
  snprintf(vars, sizeof(vars), ":root{--bg:#%06lx;--panel:#%06lx;--raised:#%06lx;--line:#%06lx;--row:#%06lx;--accent:#%06lx;"
           "--text:#%06lx;--sub:#%06lx;--ok:#%06lx;--bad:#%06lx}</style></head><body>",
           (unsigned long)t->bg, (unsigned long)t->panel, (unsigned long)t->raised, (unsigned long)t->line, (unsigned long)t->row,
           (unsigned long)t->accent, (unsigned long)t->text, (unsigned long)t->sub, (unsigned long)t->softOk, (unsigned long)t->softBad);
  String h = HEAD;
  h += vars;
  return h;
}


// One request per connection. On the setup network every name resolves to the dial, so the phone's
// captive-portal checks and background apps all open connections here too. Kept open, they fill the
// server's slots, it drops the oldest (often one Chrome is about to reuse), and Chrome gives up with
// ERR_TOO_MANY_RETRIES. Closing after each response keeps slots free and nothing stale to reuse.
static void one_shot(httpd_req_t *req) {
  log_req(req);
  httpd_resp_set_hdr(req, "Connection", "close");
  httpd_sess_trigger_close(req->handle, httpd_req_to_sockfd(req));   // runs once this handler has returned
}

static esp_err_t closed_page(httpd_req_t *req) {
  log_ev('>', httpd_req_to_sockfd(req), "403, setup closed");
  httpd_resp_set_status(req, "403 Forbidden");
  httpd_resp_set_type(req, "text/html; charset=utf-8");
  char page[300];
  snprintf(page, sizeof(page), "<!doctype html><meta name=viewport content='width=device-width'>"
           "<body style='font:16px system-ui;background:#%06lx;color:#%06lx;padding:24px'>"
           "<h2>Deskling</h2><p>Open <b>Settings &gt; Set up from phone</b> on the dial first.</p>",
           (unsigned long)TH_BG, (unsigned long)TH_TEXT);
  return httpd_resp_sendstr(req, page);
}
#define SETUP_ONLY() do { if (!setupOn) return closed_page(req); } while (0)

static esp_err_t send_html(httpd_req_t *req, const String &h) {
  httpd_resp_set_type(req, "text/html; charset=utf-8");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_send(req, h.c_str(), h.length());
}


// ---------- shared with the UI ----------
static portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
static bool wifiReq = false;
static char reqSsid[33], reqPass[65];
static uint8_t wwState = WW_IDLE;
static char wwSsid[33], wwMsg[128];
static volatile uint32_t lastReq = 0;

static bool apOn = false, apPending = false;
static volatile bool setupOn = false;     // the setup page and its endpoints: only while Set up from phone is open
static char apSsid[33], apPass[16];
static DNSServer dns;

static void touch() { lastReq = millis(); }

// ---------- the page ----------
static const char PAGE_JS[] = R"JS(<script>
const $=id=>document.getElementById(id);
function esc(s){return s.replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]))}
let saved='',trying=false,lost=0;
function msg(id,t,c){const e=$(id);e.textContent=t;e.className='msg '+(c||'')}
function bars(r){return r>-55?'strong':r>-67?'good':r>-78?'weak':'very weak'}
async function nets(){
  try{
    const j=await (await fetch('/nets')).json();
    if(j.state=='busy'){$('nets').innerHTML='<div class=empty>Looking for networks...</div>';setTimeout(nets,800);return}
    saved=j.saved;
    if(!$('ssid').value&&saved)pick(saved);
    $('nets').innerHTML=j.items.length?j.items.map(n=>`<div class="it net" data-s="${esc(n.s)}"><div class=n><b>${esc(n.s)}</b>`+
      `<i>${bars(n.r)}${n.o?' · open':''}${n.s==saved?' · saved':''}</i></div></div>`).join(''):'<div class=empty>No networks found. Type the name below.</div>';
    document.querySelectorAll('.net').forEach(d=>{d.onclick=()=>pick(d.dataset.s);d.classList.toggle('on',d.dataset.s==$('ssid').value)});
  }catch(e){setTimeout(nets,1500)}
}
function pick(s){
  if(s!=$('ssid').value)$('pw').value='';      // a password typed for another network
  $('ssid').value=s;
  document.querySelectorAll('.net').forEach(d=>d.classList.toggle('on',d.dataset.s==s));
  $('pw').placeholder=s==saved?'saved: leave empty to keep it':'password (empty for an open network)';
}
$('ssid').oninput=()=>pick($('ssid').value);
$('rescan').onclick=async e=>{e.preventDefault();await fetch('/scan',{method:'POST'});setTimeout(nets,300)};
$('show').onchange=()=>$('pw').type=$('show').checked?'text':'password';
$('join').onclick=async()=>{
  const s=$('ssid').value.trim();if(!s){msg('wmsg','Pick a network first.','bad');return}
  try{await fetch('/wifi',{method:'POST',body:new URLSearchParams({ssid:s,pass:$('pw').value})})}
  catch(e){msg('wmsg','The dial didn\'t answer. Is the phone still on its network?','bad');return}
  trying=true;lost=0;msg('wmsg','Connecting to '+s+'...');status();
};
async function status(){
  let j;
  try{j=await (await fetch('/status')).json();lost=0}
  catch(e){
    if(trying&&++lost>2)msg('wmsg','Lost touch with the dial for a moment. Its screen shows the result; reconnect to its network if this page stays stuck.');
    setTimeout(status,trying?1000:4000);return}
  $('st').textContent=j.up?`On ${j.cur} at ${j.ip}`:'Not on Wi-Fi yet. Pick a network below.';
  const w=j.w;
  if(trying&&w.st=='ok'){trying=false;$('pw').value='';saved=w.ssid;pick(w.ssid);
    msg('wmsg','Connected to '+w.ssid+' and saved.'+(location.hostname=='192.168.4.1'?' On that network the dial is at '+j.ip+'.':''),'ok');nets()}
  else if(trying&&w.st=='fail'){trying=false;msg('wmsg',w.msg,'bad')}
  else if(trying)msg('wmsg','Connecting to '+w.ssid+'...');
  setTimeout(status,trying?1000:4000);
}
for(const id of ['br','lb']){const r=$(id),o=$(id+'v'),f=()=>o.textContent=id=='lb'?Math.round(r.value/2.55)+'%':r.value+'%';r.oninput=f;f()}
$('f').onsubmit=async e=>{
  e.preventDefault();
  try{const r=await fetch('/save',{method:'POST',body:new URLSearchParams(new FormData($('f')))});
    if(!r.ok)throw 0;
    msg('smsg','Saved to the dial.','ok');
  }catch(x){msg('smsg','Couldn\'t save. Is the phone still on the dial\'s network?','bad')}
};
nets();status();
</script>)JS";

static void opt(String &h, int v, const char *label, int cur) {
  h += "<option value="; h += v; if (v == cur) h += " selected"; h += ">"; h += esc(label); h += "</option>";
}
static void every_select(String &h, const char *name, int cur) {
  h += "<select name="; h += name; h += ">";
  char b[24];
  for (int v = 0; v <= 120; v += 5) { if (v) snprintf(b, sizeof(b), "every %d min", v); else strcpy(b, "off"); opt(h, v, b, cur); }
  if (cur % 5) { snprintf(b, sizeof(b), "every %d min", cur); opt(h, cur, b, cur); }
  h += "</select>";
}
static void hour_select(String &h, const char *name, int from, int to, int cur) {
  h += "<select name="; h += name; h += ">";
  char b[16];
  for (int v = from; v <= to; v++) { if (v == 24) strcpy(b, "midnight"); else snprintf(b, sizeof(b), "%02d:00", v); opt(h, v, b, cur); }
  h += "</select>";
}
static void check(String &h, const char *name, bool on, const char *label) {
  h += "<label class=chk><input type=checkbox name="; h += name; h += " value=1"; if (on) h += " checked"; h += ">"; h += label; h += "</label>";
}

static esp_err_t root_get(httpd_req_t *req) {
  one_shot(req);
  SETUP_ONLY();
  touch();
  // On the setup network every name resolves to the dial. Send the phone's captive-portal checks
  // (captive.apple.com, connectivitycheck.gstatic.com, ...) to the page's real address, which makes the
  // phone open it by itself. Requests to an IP address are left alone: that's someone at the page already,
  // possibly over the home network once the dial has joined it.
  if (apOn) {
    char host[64];
    bool name = false;
    if (httpd_req_get_hdr_value_str(req, "Host", host, sizeof(host)) == ESP_OK)
      for (const char *c = host; *c && *c != ':'; c++) name |= isalpha((unsigned char)*c);
    if (name) {
      log_ev('>', httpd_req_to_sockfd(req), "302 to the page");
      httpd_resp_set_status(req, "302 Found");
      httpd_resp_set_hdr(req, "Location", "http://192.168.4.1/");
      httpd_resp_set_hdr(req, "Cache-Control", "no-store");
      return httpd_resp_send(req, nullptr, 0);
    }
  }
  settings_get(S);
  String h = head();
  h.reserve(16000);
  h += "<h1>Deskling</h1><p id=st>Asking the dial...</p>";

  h += "<h2>Wi-Fi</h2><div class='box list' id=nets><div class=empty>Looking for networks...</div></div>";
  h += "<small><a href=# id=rescan>Scan again</a></small>";
  h += "<label>Network</label><input id=ssid autocomplete=off autocapitalize=off placeholder='pick one above, or type its name'>";
  h += "<label>Password</label><input id=pw type=password autocomplete=off autocapitalize=off placeholder=password>";
  h += "<label class=chk><input type=checkbox id=show>Show password</label>";
  h += "<button type=button id=join>Connect</button><div class=msg id=wmsg></div>";

  h += "<form id=f method=post action=/save>";
  h += "<h2>Reminders</h2><input type=hidden name=dial value=1>";
  static const char *const NEED[4] = { "Water", "Eye break", "Stretch", "Move" };
  for (int i = 0; i < 4; i++) {
    char nm[4]; snprintf(nm, sizeof(nm), "r%d", i);
    h += "<label>"; h += NEED[i]; h += "</label>";
    every_select(h, nm, S.remind_min[i]);
  }
  check(h, "po", S.posture_on, "Sit/stand reminders <small>for a standing desk</small>");
  h += "<div class=row><div><label>Sit for</label><select name=sit>";
  { char b[16]; for (int v = 15; v <= 120; v += 5) { snprintf(b, sizeof(b), "%d min", v); opt(h, v, b, S.sit_min); } }
  h += "</select></div><div><label>Stand for</label><select name=stand>";
  { char b[16]; for (int v = 5; v <= 90; v += 5) { snprintf(b, sizeof(b), "%d min", v); opt(h, v, b, S.stand_min); } }
  h += "</select></div></div>";
  h += "<label>Active hours <small>reminders only run between these</small></label><div class=row>";
  hour_select(h, "hs", 0, 23, S.hour_start);
  h += "<span style='flex:0'>to</span>";
  hour_select(h, "he", 1, 24, S.hour_end);
  h += "</div><label>Time zone</label><select name=tz>";
  for (int i = 0; i < care_tz_count(); i++) opt(h, i, care_tz_name(i), S.tz);
  h += "</select>";

  h += "<h2>Home screen</h2><label>Clock</label><select name=cm>";
  opt(h, 0, "off", S.clock_mode); opt(h, 1, "12-hour", S.clock_mode); opt(h, 2, "24-hour", S.clock_mode);
  h += "</select><label>Clock style</label><select name=cs>";
  for (int i = 0; i < clockface_style_count(); i++) opt(h, i, clockface_style_name(i), S.clock_style);
  h += "</select>";
  check(h, "sd", S.show_date, "Show the date");
  check(h, "ss", S.show_status, "Show the Wi-Fi icon");
  check(h, "sn", S.show_next, "Show the next reminder");

  h += "<h2>Dial</h2>";
  h += "<label>Screen brightness <small id=brv></small></label><input type=range name=br id=br min=10 max=100 step=5 value=";
  h += S.bright;
  h += "><label>LED brightness <small id=lbv></small></label><input type=range name=lb id=lb min=5 max=255 step=5 value=";
  h += S.led_bright;
  h += "><label>Nap after</label><select name=nap>";
  { char b[16]; opt(h, 0, "never", S.nap_min);
    for (int v : { 1, 2, 5, 10, 15, 20, 30, 45, 60 }) { snprintf(b, sizeof(b), "%d min", v); opt(h, v, b, S.nap_min); }
    bool known = false; for (int v : { 0, 1, 2, 5, 10, 15, 20, 30, 45, 60 }) known |= v == S.nap_min;
    if (!known) { snprintf(b, sizeof(b), "%d min", S.nap_min); opt(h, S.nap_min, b, S.nap_min); } }
  h += "</select>";
  check(h, "inv", S.enc_invert, "Reverse the knob");
  h += "<small>LED alignment is set on the dial, where you can see the ring move.</small>";

  h += "<button>Save</button><div class=msg id=smsg></div></form>";
  h += PAGE_JS;
  h += "</body></html>";
  return send_html(req, h);
}

static void json_str(String &o, const char *s) {
  o += '"';
  for (; *s; s++) {
    unsigned char c = *s;
    if (c == '"' || c == '\\') { o += '\\'; o += (char)c; }
    else if (c < 0x20) { char b[8]; snprintf(b, sizeof(b), "\\u%04x", c); o += b; }
    else o += (char)c;
  }
  o += '"';
}

static bool form_int(const char *body, const char *key, int &out) {
  char v[16];
  if (httpd_query_key_value(body, key, v, sizeof(v)) != ESP_OK) return false;
  char *e; long x = strtol(v, &e, 10);
  if (e == v) return false;
  out = (int)x;
  return true;
}

static esp_err_t read_body(httpd_req_t *req, char *&body, size_t max) {
  body = nullptr;
  if (req->content_len > max) { httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "Too long"); return ESP_FAIL; }
  body = (char *)malloc(req->content_len + 1);
  if (!body) { httpd_resp_send_500(req); return ESP_FAIL; }
  size_t got = 0;
  while (got < req->content_len) {
    int r = httpd_req_recv(req, body + got, req->content_len - got);
    if (r == HTTPD_SOCK_ERR_TIMEOUT) continue;
    if (r <= 0) { free(body); body = nullptr; return ESP_FAIL; }
    got += r;
  }
  body[got] = 0;
  return ESP_OK;
}

static esp_err_t save_post(httpd_req_t *req) {
  one_shot(req);
  SETUP_ONLY();
  touch();
  const size_t MAX_BODY = 6144;
  char *body;
  if (read_body(req, body, MAX_BODY) != ESP_OK) return ESP_FAIL;
  char *val = (char *)malloc(MAX_BODY);
  if (!val) { free(body); httpd_resp_send_500(req); return ESP_FAIL; }

  settings_get(S);
  int v;
  if (form_int(body, "dial", v)) {             // the dial settings were on the form (unticked boxes send nothing)
    char k[4];
    for (int i = 0; i < 4; i++) { snprintf(k, sizeof(k), "r%d", i); if (form_int(body, k, v)) S.remind_min[i] = constrain(v, 0, 120); }
    if (form_int(body, "hs", v)) S.hour_start = constrain(v, 0, 23);
    if (form_int(body, "he", v)) S.hour_end = constrain(v, 1, 24);
    if (form_int(body, "tz", v)) S.tz = constrain(v, 0, care_tz_count() - 1);
    if (form_int(body, "cm", v)) S.clock_mode = constrain(v, 0, 2);
    if (form_int(body, "cs", v)) S.clock_style = constrain(v, 0, clockface_style_count() - 1);
    if (form_int(body, "br", v)) S.bright = constrain(v, 10, 100);
    if (form_int(body, "lb", v)) S.led_bright = constrain(v, 5, 255);
    if (form_int(body, "nap", v)) S.nap_min = constrain(v, 0, 60);
    S.show_date = form_int(body, "sd", v);
    S.show_status = form_int(body, "ss", v);
    S.show_next = form_int(body, "sn", v);
    S.enc_invert = form_int(body, "inv", v);
    S.posture_on = form_int(body, "po", v);
    if (form_int(body, "sit", v)) S.sit_min = constrain(v, 15, 120);
    if (form_int(body, "stand", v)) S.stand_min = constrain(v, 5, 90);
  }
  free(body); free(val);
  settings_put(S);
  saved = true;

  String h = head();
  h += "<h1>Saved</h1><p>The dial has the new settings. You can close this page.</p>"
       "<p><a href=/>Back</a></p></body></html>";
  return send_html(req, h);
}

// ---------- Wi-Fi ----------
static esp_err_t nets_get(httpd_req_t *req) {
  one_shot(req);
  SETUP_ONLY();
  touch();
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  ScanState st = net_scan_state();
  if (st == SCAN_IDLE) { net_scan(); st = SCAN_BUSY; }
  if (st == SCAN_BUSY) return httpd_resp_sendstr(req, "{\"state\":\"busy\"}");
  settings_get(S);
  String o = "{\"state\":\"ok\",\"saved\":";
  json_str(o, S.ssid);
  o += ",\"items\":[";
  int n = st == SCAN_DONE ? net_scan_count() : 0;
  for (int i = 0; i < n; i++) {
    const WifiNet &w = net_scan_net(i);
    if (apOn && !strcmp(w.ssid, apSsid)) continue;
    if (o[o.length() - 1] == '}') o += ',';
    o += "{\"s\":"; json_str(o, w.ssid);
    o += ",\"r\":"; o += w.rssi;
    o += ",\"o\":"; o += w.open ? "1}" : "0}";
  }
  o += "]}";
  return httpd_resp_sendstr(req, o.c_str());
}

static esp_err_t scan_post(httpd_req_t *req) {
  one_shot(req);
  SETUP_ONLY();
  touch();
  net_scan();
  return httpd_resp_sendstr(req, "ok");
}

static esp_err_t wifi_post(httpd_req_t *req) {
  one_shot(req);
  SETUP_ONLY();
  touch();
  char *body;
  if (read_body(req, body, 512) != ESP_OK) return ESP_FAIL;
  char ss[100] = "", pp[200] = "";
  httpd_query_key_value(body, "ssid", ss, sizeof(ss));
  httpd_query_key_value(body, "pass", pp, sizeof(pp));
  free(body);
  url_decode(ss); url_decode(pp);
  if (!ss[0] || strlen(ss) > 32 || strlen(pp) > 64) { httpd_resp_send_err(req, HTTPD_400_BAD_REQUEST, "bad network name or password"); return ESP_FAIL; }
  settings_get(S);
  if (!pp[0] && !strcmp(ss, S.ssid)) strlcpy(pp, S.pass, sizeof(pp));     // "leave empty to keep it"
  portENTER_CRITICAL(&mux);
  strlcpy(reqSsid, ss, sizeof(reqSsid)); strlcpy(reqPass, pp, sizeof(reqPass));
  wifiReq = true;
  wwState = WW_TRYING; strlcpy(wwSsid, ss, sizeof(wwSsid)); wwMsg[0] = 0;
  portEXIT_CRITICAL(&mux);
  return httpd_resp_sendstr(req, "ok");
}

static esp_err_t status_get(httpd_req_t *req) {
  one_shot(req);
  SETUP_ONLY();
  touch();
  static const char *const WW[] = { "idle", "trying", "ok", "fail" };
  uint8_t st; char ss[33], m[128];
  portENTER_CRITICAL(&mux);
  st = wwState; memcpy(ss, wwSsid, sizeof(ss)); memcpy(m, wwMsg, sizeof(m));
  portEXIT_CRITICAL(&mux);
  bool up = WiFi.status() == WL_CONNECTED;
  String o = "{\"w\":{\"st\":\""; o += WW[st % 4]; o += "\",\"ssid\":"; json_str(o, ss);
  o += ",\"msg\":"; json_str(o, m);
  o += "},\"up\":"; o += up ? "true" : "false";
  o += ",\"cur\":"; json_str(o, up ? WiFi.SSID().c_str() : "");
  o += ",\"ip\":"; json_str(o, up ? WiFi.localIP().toString().c_str() : "");
  o += "}";
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_sendstr(req, o.c_str());
}

// The phone app's API: one JSON request per POST, key in X-Deskling-Key (pairing itself needs none).
static esp_err_t api_post(httpd_req_t *req) {
  one_shot(req);
  char *body;
  if (read_body(req, body, 4096) != ESP_OK) return ESP_FAIL;
  char key[40] = "";
  httpd_req_get_hdr_value_str(req, "X-Deskling-Key", key, sizeof(key));
  bool authed = api_key_ok(key);
  String out;
  api_call(body, authed, out);
  free(body);
  httpd_resp_set_type(req, "application/json");
  httpd_resp_set_hdr(req, "Cache-Control", "no-store");
  return httpd_resp_send(req, out.c_str(), out.length());
}

static esp_err_t favicon_get(httpd_req_t *req) {
  one_shot(req);
  httpd_resp_set_status(req, "204 No Content");
  return httpd_resp_send(req, nullptr, 0);
}

void web_begin() {
  if (srv) return;
  portENTER_CRITICAL(&mux);
  wifiReq = false; wwState = WW_IDLE; wwSsid[0] = wwMsg[0] = 0;
  portEXIT_CRITICAL(&mux);
  touch();
  httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
  cfg.stack_size = 6144;                // handlers build pages on the heap; the stack stays small
  cfg.max_open_sockets = 10;            // of the 16 lwIP sockets; the server keeps 3 for itself
  cfg.max_uri_handlers = 12;
  cfg.lru_purge_enable = true;          // reuse the oldest socket rather than refuse a new one
  cfg.uri_match_fn = httpd_uri_match_wildcard;
  cfg.core_id = 0;                      // keep it off the UI core
  cfg.open_fn = on_open;
  cfg.close_fn = on_close;
  if (httpd_start(&srv, &cfg) != ESP_OK) { srv = nullptr; return; }
  httpd_uri_t u[] = {
    { .uri = "/log", .method = HTTP_GET, .handler = log_get, .user_ctx = nullptr },
    { .uri = "/favicon.ico", .method = HTTP_GET, .handler = favicon_get, .user_ctx = nullptr },
    { .uri = "/save", .method = HTTP_POST, .handler = save_post, .user_ctx = nullptr },
    { .uri = "/nets", .method = HTTP_GET, .handler = nets_get, .user_ctx = nullptr },
    { .uri = "/scan", .method = HTTP_POST, .handler = scan_post, .user_ctx = nullptr },
    { .uri = "/wifi", .method = HTTP_POST, .handler = wifi_post, .user_ctx = nullptr },
    { .uri = "/status", .method = HTTP_GET, .handler = status_get, .user_ctx = nullptr },
    { .uri = "/api", .method = HTTP_POST, .handler = api_post, .user_ctx = nullptr },
    { .uri = "/*", .method = HTTP_GET, .handler = root_get, .user_ctx = nullptr },   // anything else: the page
  };
  for (auto &x : u) httpd_register_uri_handler(srv, &x);
  for (httpd_err_code_t e : { HTTPD_400_BAD_REQUEST, HTTPD_408_REQ_TIMEOUT, HTTPD_411_LENGTH_REQUIRED, HTTPD_414_URI_TOO_LONG,
                              HTTPD_431_REQ_HDR_FIELDS_TOO_LARGE, HTTPD_500_INTERNAL_SERVER_ERROR, HTTPD_505_VERSION_NOT_SUPPORTED })
    httpd_register_err_handler(srv, e, on_http_err);
}

bool web_ap_start(uint8_t channel, char *ssid, size_t ssidLen, char *pass, size_t passLen) {
  if (!apOn && !apPending) {
    uint8_t mac[6];
    WiFi.macAddress(mac);
    snprintf(apSsid, sizeof(apSsid), "Deskling-%02X%02X", mac[4], mac[5]);
    static const char AL[] = "abcdefghjkmnpqrstuvwxyz23456789";      // nothing that reads two ways
    for (int i = 0; i < 8; i++) apPass[i] = AL[esp_random() % (sizeof(AL) - 1)];
    apPass[8] = 0;
    if (channel < 1 || channel > 13) channel = 1;
    net_ap_start(apSsid, apPass, channel);        // the Wi-Fi task owns the radio
    apPending = true;
  }
  strlcpy(ssid, apSsid, ssidLen);
  strlcpy(pass, apPass, passLen);
  return true;
}

int web_ap_poll() {
  if (apOn) return 1;
  if (!apPending) return -1;
  ApState st = net_ap_state();
  if (st == AP_FAILED) { apPending = false; return -1; }
  if (st != AP_UP) return 0;
  apPending = false;
  dns.setErrorReplyCode(DNSReplyCode::NoError);
  dns.start(53, "*", IPAddress(192, 168, 4, 1));
  apOn = true;
  web_begin();
  return srv ? 1 : -1;
}

bool web_ap_active() { return apOn || apPending; }
int web_ap_clients() { return apOn ? WiFi.softAPgetStationNum() : 0; }

void web_setup(bool on) { setupOn = on; }

void web_stop_ap() {
  if (apOn) dns.stop();
  if (apOn || apPending) net_ap_stop();
  apOn = apPending = false;
}

void web_stop() {
  web_setup(false);
  web_stop_ap();
  // the server itself stays up while the dial is on Wi-Fi: the phone app's API lives on it
  if (srv && WiFi.status() != WL_CONNECTED) { httpd_stop(srv); srv = nullptr; }
}

void web_loop() {}      // the HTTP server and the DNS server (AsyncUDP) have their own tasks
uint32_t web_last_request() { return lastReq; }

bool web_take_saved() {
  if (!saved) return false;
  saved = false;
  return true;
}

bool web_take_wifi(char *ssid, size_t ssidLen, char *pass, size_t passLen) {
  bool r = false;
  portENTER_CRITICAL(&mux);
  if (wifiReq) {
    wifiReq = false; r = true;
    strlcpy(ssid, reqSsid, ssidLen); strlcpy(pass, reqPass, passLen);
  }
  portEXIT_CRITICAL(&mux);
  return r;
}

void web_set_wifi(WebWifiState st, const char *ssid, const char *msg) {
  portENTER_CRITICAL(&mux);
  wwState = st;
  strlcpy(wwSsid, ssid ? ssid : "", sizeof(wwSsid));
  strlcpy(wwMsg, msg ? msg : "", sizeof(wwMsg));
  portEXIT_CRITICAL(&mux);
}
