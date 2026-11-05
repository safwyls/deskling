# Deskling website

Static files, no framework or build step except two bundles.

- `index.html` is the shell; `pages/` holds each view's HTML, loaded by `js/app.js` from the URL hash
  (`#home`, `#flash`, `#wiki`, `#wiki-<page>`).
- `js/screen.js` draws the dial's round screen in a canvas, with the same layout as the firmware.
- `js/src/dial3d.js` is the 3D dial on the home page (three.js, the ring from `model/shell.stl`).
- `js/src/flasher.js` writes firmware over Web Serial (esptool-js). `js/nvs.js` builds the settings partition
  the dial reads on first boot; `npm run nvs-check` compares it byte for byte with Espressif's generator.
- The colours follow the chosen companion: `html[data-pet]` switches the CSS variables in `css/site.css`.

## Running it locally

```
npm install && npm run build                                             # the two bundles (committed too)
python3 tools/pack_pets.py ../app/app/src/main/assets/pets pets          # the animation loops
(cd ../firmware && pio run -e wisp -e crumb -e sprig)
python3 tools/firmware_to_site.py ../firmware fw                          # the firmware images + manifest
python3 -m http.server 8000
```

Web Serial needs a secure context: `http://localhost` counts, a LAN address doesn't. It also doesn't work
inside a sandboxed frame, so the flash page says so and links to the standalone site.

`js/hosting.js` says where the binary assets live; a host that can't serve `.bin` files can swap it.
