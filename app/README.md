# Deskling for Android

The Wisp care game on your phone, sharing one Wisp with your Deskling dial over Bluetooth or Wi-Fi, plus a step
counter that feeds Wisp's Move need.

**Status:** builds cleanly (no lint errors), and the care engine's unit tests pass. Not yet run on a phone.

## Building

Open this folder in Android Studio (Ladybug or newer) and run the `app` configuration, or from a terminal with the
Android SDK installed: `./gradlew :app:assembleDebug`, then `adb install app/build/outputs/apk/debug/app-debug.apk`.

Unit tests: `./gradlew :app:testDebugUnitTest`. Besides the care engine tests this renders the main screens on the JVM
(Robolectric) into `app/build/shots/`, to look at without a phone.

## What it does

- **Wisp:** the same loops, moods and thought bubbles as the dial, exported from the firmware's clips by
  `tools/export_app_assets.py` (bubbles and icons by `tools/make_emotes.py --app`). Tap Wisp to answer what it's
  asking for (log water, start an eye break, stretch or walk), or for a pat when nothing is due.
- **Activities:** eye break (20 s), stretch (60 s, four prompts), walk (counts after 2 min), and the catch game.
- **Stats:** level, happiness, today's breaks, streak, steps for the last 7 days.
- **Settings:** pair a dial, set the dial's Wi-Fi from the phone (it scans, you pick, type the password on the phone
  keyboard), every dial setting except LED alignment fine-tuning on the knob, and phone reminders.

## One Wisp, two places

The dial holds the truth whenever the phone can reach it. The phone sends what it logged (each break with the time
it happened, pats, games, pauses) and today's steps, then takes the dial's state and settings. Away from the dial
the phone runs its own copy of the care model (`care/CareEngine.kt`, a port of the firmware's `care.cpp`) and
queues what you log until they meet again.

- **Wi-Fi:** `POST http://<dial>/api` with the key in `X-Deskling-Key`. The dial announces itself as
  `_deskling._tcp` over mDNS.
- **Bluetooth:** service `6d7a0001-5f2b-4b6c-9e2d-3a1f0c0d5e11`; write requests to `...0002`, answers arrive as
  notifications on `...0003`; one JSON message per line.
- **Pairing:** on the dial, Settings > Phone app > Pair a phone shows a 6-digit code; enter it in the app.

The protocol is described in the firmware's `src/api.h`.

## Steps

The phone's hardware step counter, read every 15 minutes in the background and live while the app is open.
A walk (250 steps within 10 minutes by default, adjustable) logs the Move break, at most once per half Move
interval. Steps show on the dial's stats screen too.

## Reminders on the phone

By default only when you're away from the dial: no Bluetooth contact for 10 minutes. Each reminder has a Done
button. They can also be set to always or never.

## Notes

- Cleartext HTTP is allowed because the dial speaks plain HTTP on the local network.
- Over Bluetooth the link isn't encrypted; the key only works for a dial that paired with this phone, but someone
  listening nearby during a session could read the traffic (care state, settings, and Wi-Fi passwords you send
  the dial). Pairing again on the dial (Settings > Phone app > Forget phones) invalidates old keys.
