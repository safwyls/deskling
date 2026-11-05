# Deskling

A desk pet that looks after you. Wisp (or Crumb the turtle, or Sprig the sprout) lives on an Elecrow CrowPanel 1.46" rotary display (ESP32-S3) and gets
thirsty, tired or restless when it's time for water, an eye break, a stretch or a walk. Do the thing, log it on
the dial (or the phone), and Wisp perks up. An Android app shares the same Wisp, counts your steps, and reminds
you when you're away from the desk.

| Folder | What's in it |
| --- | --- |
| [`firmware/`](firmware/) | The dial: PlatformIO project (Arduino-ESP32 3.3, LVGL 9, LovyanGFX), the care game, settings on the knob, phone setup, Bluetooth and Wi-Fi sync. |
| [`app/`](app/) | Deskling for Android: Kotlin and Jetpack Compose. |
| [`firmware/assets/loops/`](firmware/assets/loops/) | The pets' processed animation loops (Wisp's at the top, Crumb's in `crumb/`, Sprig's in `sprig/`), the source both the dial's frames and the app's assets are generated from. |
| [`firmware/tools/`](firmware/tools/) | Clip processing, asset generation for the dial and the app, thought bubbles, clock digits. |
| [`site/`](site/) | The website: what Deskling is, a firmware page that writes to the dial over USB from the browser, and the build guide. |
| [`hardware/`](hardware/) | The printed retainer ring that holds the display, as STEP and STL. |

## Flashing the dial

The easy way is the website's firmware page (https://safwyls.github.io/deskling/#flash once Pages is on): pick a
companion, fill in Wi-Fi and reminders, and it writes everything over USB from Chrome or Edge.

By hand, in Chrome or Edge, https://espressif.github.io/esptool-js/:

- **First install:** `firmware.factory.bin` at 0x0, with "erase flash" ticked.
- **Updates:** `firmware.bin` at 0x10000, without erasing (keeps settings). When the partition table changes, add
  `partitions.bin` at 0x8000 to the same flash.

Each pet is its own firmware: build with PlatformIO in `firmware/`, `pio run -e wisp`, `pio run -e crumb` or `pio run -e sprig`; see [`firmware/README.md`](firmware/README.md).

## The app

Build in Android Studio or with `./gradlew :app:assembleDebug` in `app/`; see [`app/README.md`](app/README.md).
The app carries all three pets and shows whichever the paired dial runs (without a dial, pick one in Settings), each in its own colours.
Pair it from the dial: Settings > Phone app > Pair a phone shows a 6-digit code.

## Regenerating Wisp

```
# dial frames (8-bit indexed, with per-frame bubble positions)
python3 firmware/tools/make_assets.py firmware/src/anims wisp_walk=firmware/assets/loops/wisp_loop.js ...
# app assets (WebP frames + layout JSON) and bubbles
python3 firmware/tools/export_app_assets.py app/app/src/main/assets/wisp walk=firmware/assets/loops/wisp_loop.js ...
python3 firmware/tools/make_emotes.py --app app/app/src/main/assets/emotes
```

The full commands, with every loop, are in `firmware/README.md`.

## The website

Plain static files in `site/`, no framework. GitHub Actions (`.github/workflows/pages.yml`) builds the three
firmwares, packs the pet loops from the app's assets and publishes `site/` to GitHub Pages on every push that
touches the site, the firmware or the app's animations. To run it locally, see [`site/README.md`](site/README.md).
