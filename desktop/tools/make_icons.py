#!/usr/bin/env python3
"""Make the desktop app's icons from the Android app's launcher art (run once; the PNGs are committed).

    python3 tools/make_icons.py

web/icons/tray-<pet>-{32,64}.png   the tray icon: the companion's face, no background
build/icon.png                      the app icon (1024 px): Wisp on its night-sky circle; `npm run icons`
                                    makes src-tauri/icons (the .ico, .icns and PNGs) from it
"""
from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parent.parent
art = root.parent / 'app' / 'app' / 'src' / 'main' / 'res' / 'drawable-nodpi'
BG = {'wisp': (17, 26, 44), 'crumb': (26, 44, 32), 'sprig': (50, 34, 58)}

(root / 'web' / 'icons').mkdir(parents=True, exist_ok=True)
(root / 'build').mkdir(exist_ok=True)
for pet in BG:
    fg = Image.open(art / f'ic_launcher_{pet}_fg.png').convert('RGBA')
    w = fg.width
    face = fg.crop((int(w * 0.19), int(w * 0.19), int(w * 0.81), int(w * 0.81)))   # the adaptive icon's safe zone
    bbox = face.getbbox()
    if bbox:                                                                          # trim to the art, keep it square
        l, t, r, b = bbox
        side = max(r - l, b - t)
        cx, cy = (l + r) // 2, (t + b) // 2
        face = face.crop((cx - side // 2, cy - side // 2, cx + side // 2, cy + side // 2))
    for px in (32, 64):
        face.resize((px, px), Image.LANCZOS).save(root / 'web' / 'icons' / f'tray-{pet}-{px}.png')
    if pet == 'wisp':
        S = 1024
        icon = Image.new('RGBA', (S, S), (0, 0, 0, 0))
        mask = Image.new('L', (S, S), 0)
        ImageDraw.Draw(mask).ellipse((40, 40, S - 40, S - 40), fill=255)
        disc = Image.new('RGBA', (S, S), BG[pet] + (255,))
        icon.paste(disc, (0, 0), mask)
        big = fg.resize((S, S), Image.LANCZOS)
        icon.alpha_composite(big)
        icon.save(root / 'build' / 'icon.png')
print('icons written')
