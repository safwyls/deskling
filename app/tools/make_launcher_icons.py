#!/usr/bin/env python3
"""Launcher icons for each companion, from its own animation frames.

    python3 tools/make_launcher_icons.py        (run in app/)

Writes res/drawable-nodpi/ic_launcher_<pet>_fg.png (the art) and _mono.png (a white silhouette for Android 13's
themed icons), 432 px = the adaptive icon's 108 dp at xxxhdpi. The art is fitted inside the 66 dp safe zone in
the middle, so no launcher mask crops it. Wisp's icon predates this script and is kept as drawn.
"""
from pathlib import Path
from PIL import Image

RES = Path(__file__).resolve().parent.parent / 'app/src/main/res/drawable-nodpi'
PETS = Path(__file__).resolve().parent.parent / 'app/src/main/assets/pets'
CANVAS = 432
# (pet, loop, frame, longest side in px): a frame that reads at icon size, sized to sit inside the safe circle
PICKS = [('crumb', 'jump', 4, 236), ('sprig', 'walk', 0, 232)]


def main():
    for pet, loop, frame, longest in PICKS:
        art = Image.open(PETS / pet / loop / f'{frame:03d}.webp').convert('RGBA')
        art = art.crop(art.getbbox())
        k = longest / max(art.size)
        art = art.resize((round(art.width * k), round(art.height * k)), Image.LANCZOS)
        fg = Image.new('RGBA', (CANVAS, CANVAS), (0, 0, 0, 0))
        fg.alpha_composite(art, ((CANVAS - art.width) // 2, (CANVAS - art.height) // 2 + 6))   # a touch low, like Wisp
        fg.save(RES / f'ic_launcher_{pet}_fg.png', optimize=True)
        alpha = fg.getchannel('A')
        mono = Image.new('RGBA', (CANVAS, CANVAS), (255, 255, 255, 0))
        mono.putalpha(alpha)
        mono.save(RES / f'ic_launcher_{pet}_mono.png', optimize=True)
        print(pet, 'icon', art.size)


if __name__ == '__main__':
    main()
