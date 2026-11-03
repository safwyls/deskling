#!/usr/bin/env python3
"""Export Wisp's loops and bubbles for the Android app (app/src/main/assets/wisp/).

The app gets the clips' own WebP frames (about twice the dial's resolution) and, per loop, a JSON file with
the same layout data the dial uses: face anchor, ground line, per-frame lowest point (for the shadow),
back-and-forth flag, and the per-frame bubble offsets computed by make_assets.face_track, scaled up.

usage: python3 tools/export_app_assets.py OUT_DIR name=path/to/loop.js ...
"""
import sys, os, json, base64, io
from PIL import Image
sys.path.insert(0, os.path.dirname(__file__))
import make_assets as MA

def export(out_dir, name, path):
    L = MA.load(path)
    sc = L['scale']
    jsf = [Image.open(io.BytesIO(base64.b64decode(f.split(',', 1)[1]))).convert('RGBA') for f in L['frames']]
    dev_w = round(L['w'] * sc)
    k = jsf[0].width / dev_w                              # app px per dial px
    # bubble offsets exactly as the dial computes them, from the dial-size frames
    dev = [f.resize((dev_w, round(L['h'] * sc)), Image.LANCZOS) for f in jsf]
    off = MA.face_track(dev, (L['ax'] * sc, L['ay'] * sc))
    d = os.path.join(out_dir, name)
    os.makedirs(d, exist_ok=True)
    for i, f in enumerate(L['frames']):
        open(os.path.join(d, f'{i:03d}.webp'), 'wb').write(base64.b64decode(f.split(',', 1)[1]))
    below = L.get('below', L['h'] - L['ay'])
    meta = {
        'name': name, 'fps': L['fps'], 'count': len(jsf), 'w': jsf[0].width, 'h': jsf[0].height,
        'ax': L['ax'] * sc * k, 'ay': L['ay'] * sc * k,
        'cx': L.get('cx', L['ax']) * sc * k,
        'ground': (L['ay'] * sc + below * sc + MA.GAP) * k,
        'bottoms': [(L['ay'] * sc + b * sc) * k for b in (L.get('bottoms') or [below] * len(jsf))],
        'pingpong': bool(L.get('pingpong')),
        'hx': [round(x * k, 1) for x in off[:, 0]], 'hy': [round(y * k, 1) for y in off[:, 1]],
        'scale': k,                                   # app px per dial px, for the bubble geometry
    }
    json.dump(meta, open(os.path.join(d, 'meta.json'), 'w'))
    return sum(os.path.getsize(os.path.join(d, f)) for f in os.listdir(d))

if __name__ == '__main__':
    out = sys.argv[1]
    tot = 0
    for a in sys.argv[2:]:
        n, p = a.split('=', 1)
        sz = export(out, n, p); tot += sz
        print(f'{n}: {sz / 1024:.0f} KB')
    print(f'total {tot / 1048576:.1f} MB')
