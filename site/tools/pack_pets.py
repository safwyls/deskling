#!/usr/bin/env python3
"""Pack the phone app's pet loops into one file per loop for the website.

    python3 site/tools/pack_pets.py app/app/src/main/assets/pets site/pets

Each loop in the app is a folder of WebP frames plus meta.json. The site gets
pets/<pet>/<loop>.bin (the frames back to back) and <loop>.json (the same meta,
plus the byte offset and length of every frame), so a loop is two requests, not forty.
"""
import json, sys
from pathlib import Path

PETS = ['wisp', 'crumb', 'sprig']
LOOPS = ['walk', 'happy', 'thirsty', 'tired', 'stretch', 'move', 'sad', 'mad', 'sleep']   # what js/screen.js uses


def main(src: Path, out: Path) -> None:
    for pet in PETS:
        (out / pet).mkdir(parents=True, exist_ok=True)
        for loop in LOOPS:
            d = src / pet / loop
            meta = json.loads((d / 'meta.json').read_text())
            blobs = [(d / f'{i:03d}.webp').read_bytes() for i in range(meta['count'])]
            offsets, at = [], 0
            for b in blobs:
                offsets.append([at, len(b)])
                at += len(b)
            meta['frames'] = offsets
            (out / pet / f'{loop}.json').write_text(json.dumps(meta, separators=(',', ':')))
            (out / pet / f'{loop}.bin').write_bytes(b''.join(blobs))
    print(f'packed {len(PETS) * len(LOOPS)} loops into {out}')


if __name__ == '__main__':
    main(Path(sys.argv[1]), Path(sys.argv[2]))
