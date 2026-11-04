# Deskling: CrowPanel 1.46" firmware

A desk pet for the Elecrow CrowPanel 1.46" rotary display (ESP32-S3) that nudges you to drink water, rest your eyes, stretch and move, with an Android companion app that shares the same Wisp.

## Controls

There are five actions, and they mean the same thing on every screen:

| Action | Meaning |
|---|---|
| **Rotate** | Move the highlight, or change a value. |
| **Press** the knob | Do the highlighted thing: open, toggle, confirm, type the character. |
| **Tap** the screen | Same as a press, everywhere except Wisp's bare home screen. There, a tap answers what Wisp is asking for (logs a glass of water, or starts the eye break, stretch or move), or pats Wisp when nothing is due. Only a full knob press opens the menu. |
| **Hold** the knob, or **hold** the screen (0.6 s) | Back one level. It never saves. On a value it cancels and restores the old value. On the typing wheel, if you've typed something, the first hold asks and a second hold discards. |
| **Back item** | Every list and wheel has one, so holding is never required. |

From anywhere, holding repeatedly gets back to Wisp. A randomised test of 30,000 inputs checked this from 696 different places: it never took more than 5 holds.

**Home screen (Wisp)**
- **Press:** opens the menu (Activities, Settings, Back).
- **Rotate:** opens the activities ring directly.
- **Tap:** when a need is due, starts it (water logs a glass straight away); otherwise pats Wisp, which counts for happiness once a minute.
- **Activities ring:**
  - **Water:** log a glass.
  - **Eye break:** a 20-second look-away.
  - **Stretch:** 60 seconds of guided prompts.
  - **Move:** an away timer; press when you're back. Under 2 minutes doesn't count.
  - **Play:** catch the light as it passes the top; it uses the LED ring.
  - **Stats, Bedtime/Wake, Pause/Resume, Back.**
- **During an activity:** press to finish (or catch, in the game), hold to stop. Stats close on a press or a hold.

**Care (src/care.cpp)**
- **Needs:** water, eye break, stretch and move, every 45 / 20 / 50 / 60 min by default (Settings > Reminders). The timers only run during active hours (default 8:00 to 20:00, network time) and not while paused or at bedtime. Each day starts fresh.
- **When one falls due:** Wisp's mood changes, a thought bubble shows the need, the LED ring breathes in that need's colour, and a dozing screen wakes for a minute. It re-cues every 15 minutes while anything stays overdue.
- **Happiness** drifts down about 10 points an hour, plus 15 an hour while something has been ignored for 10+ minutes. Breaks done on time give the most back; very early ones give a little, so they can't be farmed. Some breaks include others: Move also checks off Stretch and the eye break (`COVERS` in `care.cpp`).
- **Sit/stand (optional):** for a standing desk. Off until turned on (Settings > Reminders > Sit / stand, the setup page, or the app). It times how long you've been sitting or standing (active hours only) and nudges you to switch after each one's limit (45 and 15 min by default), with a cue, the status line, a bubble and the LED ring, in its own teal. You say when you switch: tap the pet when nothing else is due, the Sit/Stand item in the knob's ring, or the app. It never affects the mood; switching when it's time gives a little happiness. Phone API: `posture` in `state`, `postureOn`/`sitMin`/`standMin` in `settings` and `set`, op `posture` with `standing`.
- **XP** levels Wisp up. A day with 4+ breaks logged extends the streak.
- **Moods:** content, happy, thirsty, tired, restless, sad, mad, asleep. Restless shows the Stretch clip when a stretch is the most urgent need and the Move clip when moving is. Every mood has a thought bubble that floats with Wisp; Thirsty's is part of its clip. Happy shows its heart only right after a pat or a finished break, not while Wisp is simply very happy. Everything is kept in flash across reboots.

**Settings**
- **Set up from phone** (first in Settings): one page with Wi-Fi, reminders, active hours, time zone, clock, brightness, nap and knob direction. LED alignment stays on the dial, where you can watch the ring.
  - On Wi-Fi: a QR code for the page at the dial's address on your network.
  - Not on Wi-Fi: the dial starts its own network, `Deskling-XXXX`, with a new random password each time. Scan the first QR code with the phone camera to join it. The page then opens by itself as a sign-in page; if it doesn't, the second QR code (`http://192.168.4.1/`) opens it. The network starts on your router's channel, so joining your Wi-Fi from the page doesn't drop the phone.
  - Joining from the page tries the network once. On success it's saved and the page says so; on failure the page shows the reason. The dial's screen shows the same.
  - Background reconnects pause while the setup network is up. Leaving the page, or 10 minutes without a page request, shuts the setup network down and goes back to the saved network.
- **Phone app:** pairs the Deskling Android app (Settings > Phone app > Pair a phone shows a 6-digit code; up to 4 phones; Forget phones unpairs them all). The app shares Wisp with the dial: breaks logged on the phone count here, with the time they happened, and its steps show on the stats screen. It reaches the dial over Bluetooth or on your network (`POST /api`, announced over mDNS as `_deskling._tcp`). Protocol: `src/api.h`. Bluetooth takes about 75 KB of internal RAM, so it pauses while the dial runs its own setup network and comes back when that closes.
- **Wi-Fi:** scans, lists networks by strength, and types the password on a character wheel. It tests the connection before saving, so a typo doesn't drop you off your working network. A test is tried once and never retried in the background. Reconnecting to the saved network backs off from 5 s to a minute, because some routers block a device that keeps retrying a bad login. When a join fails, the screen gives the driver's reason. `AUTH_EXPIRE` means the router never answered, which is not a password problem. About shows the dial's MAC address so you can find it in the router's device list.
- **LED alignment:** turn until the lit LED sits by the arrow at the top of the screen. The menus' LED position follows this.
- **Knob direction, screen brightness, LED brightness, nap timer.**
- **About:** IP address, signal, Bluetooth status, MAC address and free memory.
- **Reset settings:** press twice. Everything goes back to the defaults in `config.h`.

**Lists:**
- **Long names** scroll once the highlight rests for a moment, in Settings.
- **Long lists** (40+ items) speed up when the knob is turned quickly.

**Character wheel:** the wheel holds a few actions, then the characters of the current set.
- **Press:** types the highlighted character.
- **Just counter-clockwise of the first character:** delete, ✓ save, switch set (abc / ABC / 123 / #+=), then ✕ cancel.
- **Switching sets:** pressing "switch set" repeatedly cycles through the sets.

## Setup

Everything is set on the device and kept in flash (NVS), so one build works for anyone. `include/config.h` only holds defaults, used on a freshly erased board or after Reset settings.


**Flashing in a browser** (Chrome or Edge, https://espressif.github.io/esptool-js/):
- **First install:** `firmware.factory.bin` at 0x0, with "erase flash" ticked.
- **Updates:** `firmware.bin` at **0x10000**, without erasing. This keeps your settings. The full image at 0x0 overwrites the settings area, so it resets the dial.
- **When the partition table changes** (v5.7 grew the app partition from 10 to 14 MB): add `partitions.bin` at **0x8000** to the same flash as `firmware.bin` at 0x10000, without erasing. Settings are kept. An app bigger than the old partition doesn't boot until the new table is in.
- **Crashes:** Settings > Last restart says why the dial last restarted. After a crash it shows a QR code with the crash report (reason, task, program counter, backtrace) saved in flash; decode the addresses with `xtensa-esp32s3-elf-addr2line -pfiaC -e firmware.elf ADDR...` using the `.elf` of the firmware that crashed (its first 8 hex digits are in the report as `dump=`).

**Building:** one firmware per pet. Open the folder in VS Code with the PlatformIO extension, or use the CLI: `pio run -e wisp -t upload` (or `-e crumb`), then `pio device monitor`. The environment sets the pet's name (`PET_NAME`, `PET_ID`) and compiles only that pet's frames from `src/anims/<pet>/`; the dial reports `pet` in the phone API's `hello` and `state` so the app shows the same one.

**Themes:** each pet has its own colours, in `include/theme.h`: Wisp's night sky (navy, ice blue), Crumb's pond at dusk (moss, loam, shell amber, cream) and Sprig's blossom at dusk (plum, leaf green, cream, mauve). They cover the screens, clock faces, LED ring at rest and the setup page (as CSS variables). The four need colours are shared. The app's matching palettes are in `ui/AppState.kt`. App logs go to the UART0 header pins at 115200; boot messages and crash dumps also appear on the USB-C port.

### Build notes

- **Platform:** pioarduino (Arduino-ESP32 Core 3.3.8), the same core Elecrow's LVGL 9 example targets.
- **Libraries:** vendored in `lib/`, so nothing is downloaded from the library registry.
  - LovyanGFX 1.2.7 is Elecrow's copy, which includes the board's ST77961 panel driver. Its unused CJK font data is stubbed out to keep the folder small.
  - LVGL 9.1.0, Adafruit NeoPixel and ArduinoJson 7.2.1 are also vendored.
- **Partitions:** `partitions.csv` is Elecrow's layout with the app partition grown from 10 to 14 MB (the chip has 16 MB, and the rest was unused); a small SPIFFS area and the core dump partition follow it. The firmware is about 11.8 MB, mostly animation frames.
- **Hardware bring-up:** the power-rail order, LCD reset pulse and DMA flush follow Elecrow's example exactly.

## Adding or replacing animations

Animation frames are generated, never hand-edited:

```
# 1. clip -> frames (1024 px wide)
ffmpeg -i clip.mp4 -vf scale=1024:-1 frames/f%04d.png

# 2. key out the magenta, find the cleanest loop, write a loop file
#    SCALE = screen px per frame px (0.2 suits a ~1024 px Wisp)
#    --avoid a-b skips bad frames; --fps 12 or 24
python3 tools/process_clip.py frames loop_walk.js LOOP 0.2 --min 16 --max 60 --fps 12

# 3. loop files -> LVGL C sources (8-bit indexed with a transparent palette; --rgb565 for the old opaque format)
python3 tools/make_assets.py src/anims/wisp pet_walk=loop_walk.js pet_run=loop_run.js ...   # then drop the pet_ prefix from the file names
```

The processed loops are kept in `assets/loops/`. Regenerating everything from them (run in `firmware/`):

```
L=assets/loops
python3 tools/make_assets.py src/anims/wisp pet_walk=$L/wisp_loop.js pet_run=$L/wisp_run.js pet_jump=$L/wisp_jump.js \
  pet_sleep=$L/wisp_sleep.js pet_happy=$L/happy.js pet_sad=$L/sad.js pet_mad=$L/mad.js pet_thirsty=$L/thirsty.js \
  pet_tired=$L/tired.js pet_restless=$L/restless.js pet_move=$L/move.js
(cd src/anims/wisp && for f in pet_*.c; do mv $f ${f#pet_}; done; rm -f *_preview.png)
python3 tools/export_app_assets.py ../app/app/src/main/assets/pets/wisp walk=$L/wisp_loop.js run=$L/wisp_run.js \
  jump=$L/wisp_jump.js sleep=$L/wisp_sleep.js happy=$L/happy.js sad=$L/sad.js mad=$L/mad.js thirsty=$L/thirsty.js \
  tired=$L/tired.js stretch=$L/restless.js move=$L/move.js
python3 tools/make_emotes.py src/emotes.c && python3 tools/make_emotes.py --app ../app/app/src/main/assets/emotes
```

Crumb stands on the ground, so his shadow has no hover gap (`--gap 0`) and is a dark patch of ground rather than Wisp's blue glow (`--shadow`). Its Stretch and Move clips aren't made yet:
`src/anims/crumb/stand_ins.cpp` points them at the jump and walk loops until they are.

```
C=assets/loops/crumb
python3 tools/make_assets.py --gap 0 --shadow 4,9,5,150 src/anims/crumb pet_walk=$C/idle.js pet_run=$C/walk.js pet_jump=$C/jump.js \
  pet_sleep=$C/sleep.js pet_happy=$C/happy.js pet_sad=$C/sad.js pet_mad=$C/mad.js pet_thirsty=$C/thirsty.js pet_tired=$C/tired.js
(cd src/anims/crumb && for f in pet_*.c; do mv $f ${f#pet_}; done; rm -f *_preview.png)
python3 tools/export_app_assets.py --gap 0 ../app/app/src/main/assets/pets/crumb walk=$C/idle.js run=$C/walk.js \
  jump=$C/jump.js sleep=$C/sleep.js happy=$C/happy.js sad=$C/sad.js mad=$C/mad.js thirsty=$C/thirsty.js \
  tired=$C/tired.js stretch=$C/jump.js move=$C/walk.js
```

Sprig stands on the ground too. Its Stretch clip isn't made yet (the first one changed backdrop mid-clip): it stands
in with the bounce, and Move uses the run (`src/anims/sprig/stand_ins.cpp`). Its thirsty clip has no bubble of its
own, so it gets the water bubble like the other moods (`POSES` in `pet.cpp`, `Pet.thirstyClipHasBubble` in the app).

```
S=assets/loops/sprig
python3 tools/make_assets.py --gap 0 --shadow 10,4,14,150 src/anims/sprig pet_walk=$S/walk.js pet_run=$S/run.js \
  pet_jump=$S/jump.js pet_sleep=$S/sleep.js pet_happy=$S/happy.js pet_sad=$S/sad.js pet_mad=$S/mad.js \
  pet_thirsty=$S/thirsty.js pet_tired=$S/tired.js
(cd src/anims/sprig && for f in pet_*.c; do mv $f ${f#pet_}; done; rm -f *_preview.png)
python3 tools/export_app_assets.py --gap 0 ../app/app/src/main/assets/pets/sprig walk=$S/walk.js run=$S/run.js \
  jump=$S/jump.js sleep=$S/sleep.js happy=$S/happy.js sad=$S/sad.js mad=$S/mad.js thirsty=$S/thirsty.js \
  tired=$S/tired.js stretch=$S/jump.js move=$S/run.js
```

To add a loop:
1. Declare it in `src/anims/anims.h`, and give every pet's folder a `.c` (or a stand-in) for it.
2. Put it in the first column of its row in the `POSES` table in `src/pet.cpp`, with its bubble (or `nullptr` if the clip draws its own, like Thirsty).

`make_assets.py` also works out where the bubble goes in every frame: it follows the dark face mask (loosely for clips that move a lot, like Move), and lifts the bubble up and right in any frame where it would cover something Wisp draws. The positions are stored per frame (`hx`, `hy` in `PetAnim`).

Emotion clips keep their small detached marks (tears, sparkles, anger marks); the walk and run clips strip them as noise. The command for emotion clips:
`python3 tools/process_clip.py frames out.js NAME 0.2 --min 20 --max 72 --fps 12 --strip 0 --keep 0 --min-area 60`.

Some clips have no frame that matches an earlier one closely enough to loop without a visible jump (a slow droop, a long sip). For those, `--range A-B --pingpong` takes frames A to B and plays them forward then backward. Tired (frames 6–70) and thirsty (11–66) are built this way.

Every mood has its own clip: content (floating), happy, thirsty, tired, stretch, move, sad, mad, asleep, plus the dashing and bouncing activities. Move loops the whole 5 s clip (seam error 1.33), so it is the largest at 2.4 MB.

Settings > Poses plays every mood and activity the way the pet screen would, bubbles included. The bubbles (thought bubbles drawn to match the one in the thirsty clip) and ring icons come from `tools/make_emotes.py`.

Clips work best:
- in place, not travelling across the frame;
- on a flat magenta backdrop (green for Sprig);
- with a locked camera.

On the device, every loop is stored as 8-bit indexed images with a transparent palette: one shared palette per loop, so colours don't flicker between frames. Measured on the walk loop: 39.8 dB PSNR against the original.
- **Transparency** lets the clock sit behind Wisp.
- **Size:** about half of RGB565. Wisp's firmware with all eleven loops is 11.9 MB of the 14 MB app space (81%); Crumb's is 8.0 MB and Sprig's 9.6 MB.
- **Decoding:** LVGL decodes each frame once into a 2 MB PSRAM cache (`LV_CACHE_DEF_SIZE`).
- **Old format:** `make_assets.py --rgb565` still writes the old opaque frames.

## Layout

| File | Contents |
|---|---|
| `src/main.cpp` | Hardware bring-up, display flush, touch, app wiring |
| `src/board.h` | Pin map and LovyanGFX panel config |
| `src/input.cpp` | Knob task: rotation, press, hold (touch tap/hold are detected in `main.cpp`) |
| `src/pet.cpp` | Pet screen: poses, care ring, activities, stats |
| `src/care.cpp` | Care model: needs, mood, happiness, XP, clock |
| `src/emotes.c` | Generated thought bubbles and ring icons |
| `src/api.cpp` | The phone app's JSON API: pairing, keys, state, settings, breaks with timestamps, steps |
| `src/ble.cpp` | Bluetooth (BLE) transport for the same API |
| `src/crashlog.cpp` | Reset reason and the saved crash dump, for Settings > Last restart |
| `src/clockface.cpp` | Home screen clock styles |
| `src/clock_digits.c` | Generated big clock digits (A8) |
| `src/setup_ui.cpp` | Settings screens: list, character wheel, value dial, info/QR card |
| `src/settings.cpp` | Settings stored in NVS |
| `src/web.cpp` | Phone setup page and the dial's own setup network with its DNS catch-all (only while that screen is open) |
| `src/net.cpp` | Wi-Fi on a background task: joining with backoff, scans, credential tests, the setup network |
| `src/leds.cpp` | LED ring task |
| `src/anims/` | Generated animation frames |
| `tools/` | Clip processing and asset generation |

## Status

- **Confirmed on hardware:** the pet animations.
- **Not yet confirmed on hardware:** the Settings screens. Their layout was checked by rendering them with LVGL on a desktop.
