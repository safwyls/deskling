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

One firmware carries all three companions (their frames compressed, unpacked as they play); choose on the dial in Settings > Companion, in the app, or on the firmware page. Build with PlatformIO in `firmware/`: `pio run`; see [`firmware/README.md`](firmware/README.md).

## The app

Build in Android Studio or with `./gradlew :app:assembleDebug` in `app/`; see [`app/README.md`](app/README.md).
The app carries all three pets, each in its own colours. Pair it from the dial: Settings > Phone app > Pair a phone shows a 6-digit code.

The companion lives on one device at a time and travels: leave it at a dial, bring it along on the phone when you go,
leave it at another dial, or give it to another phone by link. Each device shows it only while it's there.
See *Companions travel* in [`firmware/README.md`](firmware/README.md) for the protocol.

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
firmware and the Android app, packs the pet loops from the app's assets and publishes `site/` to GitHub Pages on
every push that touches the site, the firmware or the app.

The app is only built when its signing key is in the repository's Actions secrets: `DESKLING_KEYSTORE_B64` (the
keystore, base64), `DESKLING_KEYSTORE_PASSWORD`, `DESKLING_KEY_ALIAS` and `DESKLING_KEY_PASSWORD`. Every release
must be signed with the same key, or Android won't install it over the previous one. To run it locally, see [`site/README.md`](site/README.md).

## Licence

MIT: see [`LICENSE`](LICENSE). That covers Deskling's own code, the stand's models, the art and the animations.
The libraries vendored in `firmware/lib/` keep their own licences: LVGL (MIT), ArduinoJson (MIT), LovyanGFX
(FreeBSD, with parts under its original authors' terms) and Adafruit NeoPixel (LGPL 3.0).
