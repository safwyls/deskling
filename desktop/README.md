# Deskling for the desktop

Your companion on Windows, macOS and Linux: drawn in a small see-through window with no taskbar or Dock entry,
above other windows if you like, with a tray icon for everything else. Built with [Tauri 2](https://tauri.app):
the system's own web view draws it, so the installers stay small.

- **Click** the companion for the ring of activities (water, eye break, stretch, move, sit or stand, play, bedtime,
  pause), as in the phone app. Click it again to pat it. The window lets clicks through everywhere else.
- **Right-click** runs the shortcut picked under *Right-click does* in the tray menu: a pat by default.
- **Drag** it anywhere; it remembers where.
- **The tray menu**: show or hide it, keep it above other windows, size (50 to 200%), companion, right-click
  shortcut, pause, bedtime, reminders (notifications, how often, active hours, sit and stand), giving it to another
  device, taking one in from a copied link, and starting when you log in.

It plays the same care game as the dial and the phone, and travels the same way: by a link
(`https://safwyls.github.io/deskling/#companion=…` or `deskling://companion?r=…`), which the app registers.

## Layout

| Path | What's in it |
| --- | --- |
| `web/app.js` | The app: care state and saving, the window's size and place, the tray menu, reminders, travel. |
| `web/pet.js` | The companion's window: drawing, the ring, the eye break and stretch timers, the catch game, the mouse. |
| `web/care.js` | The care game, ported from the Android app's `CareEngine`. |
| `web/record.js` | A travelling companion's record and links, as in the app's `CompanionRecord`. |
| `src-tauri/` | The Rust side: plugins (tray, notifications, autostart, deep links, single instance, store, dialogs, clipboard), no Dock icon on macOS, closing hides, and a 15-second tick that keeps reminders on time while the window is hidden. |
| `tools/assets.mjs` | Packs the pets' loops and bubbles from the Android app's assets into `web/pets/` and `web/emotes/` (not committed). |
| `tools/make_icons.py` | The tray icons and `build/icon.png` from the app's launcher art. |

## Building

You need Node 22 and Rust, plus on Linux the WebKitGTK and tray libraries:

```
sudo apt install libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev patchelf
```

Then, in `desktop/`:

```
npm ci
npm test          # the care game and the travel records
npm run dev       # run it
npm run build     # installers in src-tauri/target/release/bundle/
```

`npm run icons` remakes `src-tauri/icons/` from `build/icon.png`. CI builds the installers on each system and
puts them on the website (`site/desktop/`); see `.github/workflows/pages.yml`.

The installers aren't signed, so Windows SmartScreen and macOS Gatekeeper ask before the first run; the desktop
page on the website says how to get past them.
