"""Convert the processed animation loops into compressed LVGL 9 frames for the CrowPanel.

Each frame is 8-bit indexed with a transparent ARGB palette (LV_COLOR_FORMAT_I8): one palette shared by every
frame of a loop (so colours don't flicker between frames), no dithering, ground shadow baked in as
semi-transparent pixels. Frames are transparent, so the clock and anything else can sit behind the pet.
Measured on Wisp's walk loop: 39.8 dB PSNR against the original.

Every companion is built into the one firmware, which doesn't fit as raw frames (23 MB), so each frame's pixel
indices are stored as a raw deflate stream (about a fifth of the size). The firmware inflates a loop's frames
into PSRAM as it plays them (src/anims/anims.c), and drops loops it hasn't shown lately when it needs room.

usage: python3 make_assets.py [--gap N] [--shadow R,G,B,A] src/anims/<pet> walk=path/to/loop.js [run=...] ...
Writes src/anims/<pet>/<name>.c defining the PetAnim <pet>_<name>. The loop .js files are the ones produced
for the rig page (const X = {...frames as webp data URIs...}). A new pet also needs its table in
src/companion.cpp.
"""
import sys, json, base64, io, os, zlib
import numpy as np
from PIL import Image, ImageDraw

BG = (17, 26, 44)          # screen background, must match the firmware's PET_BG
GAP = 12                    # px between the loop's lowest point and the shadow centre line: Wisp hovers.
                            # --gap N overrides it (0 for a pet that stands on the ground, like Crumb)
SHADOW = (70, 105, 160, 110)  # ground shadow colour and its alpha at rest: a soft glow under Wisp.
                            # --shadow R,G,B,A overrides it (Crumb: a dark patch of ground)

def load(path):
    txt = open(path).read()
    return json.loads(txt.split(' = ', 1)[1].rstrip().rstrip(';'))

# The emote bubble relative to the face, in device px: must match pet_emote_pos() in src/pet.cpp (top-left at
# face + (25, -87)) and the bubble drawn by tools/make_emotes.py (44x58, circle r 19 at 23,20, two dots).
BUBBLE = [(25 + 23, -87 + 20, 19 + 2), (25 + 13, -87 + 44, 3.4 + 1), (25 + 6.5, -87 + 52.5, 2.4 + 1)]

def face_track(frames, anchor):
    """Where the emote bubble goes in each frame, as an offset from the face anchor, so it floats with Wisp.
    Follows the face (the dark mask around the eyes, the only large near-black area in every clip), loosely
    for clips that move a lot, then lifts it up and right in any frame where it would cover something Wisp
    draws (body, tears, sparkles, motion lines)."""
    pts = []
    for f in frames:
        a = np.asarray(f)
        m = (a[..., 3] > 200) & (a[..., :3].max(-1) < 70)
        if m.sum() < 30: pts.append(None); continue
        ys, xs = np.nonzero(m)
        pts.append((xs.mean(), ys.mean()))
    known = [i for i, p in enumerate(pts) if p is not None]
    n = len(frames)
    if not known: return np.zeros((n, 2))
    for i, p in enumerate(pts):                         # face hidden in this frame: use the nearest one seen
        if p is None: pts[i] = pts[min(known, key=lambda k: abs(k - i))]
    P = np.array(pts)
    sm = np.array([(P[(i - 1) % n] + 2 * P[i] + P[(i + 1) % n]) / 4 for i in range(n)])
    mean = sm.mean(0)
    spread = np.abs(sm - mean).max()
    damp = 0.8 if spread < 20 else 0.3                  # a spinning clip: drift with it, don't whirl around
    off = (sm - mean) * damp
    # lift out of the way where needed
    lift = np.zeros((n, 2))
    for i, f in enumerate(frames):
        solid = np.asarray(f)[..., 3] > 100
        H, W = solid.shape
        def clear(dx, dy):
            for bx, by, r in BUBBLE:
                cx, cy = anchor[0] + off[i, 0] + bx + dx, anchor[1] + off[i, 1] + by + dy
                x0, x1 = int(max(0, cx - r)), int(min(W, cx + r + 1))
                y0, y1 = int(max(0, cy - r)), int(min(H, cy + r + 1))
                if x0 >= x1 or y0 >= y1: continue
                yy, xx = np.mgrid[y0:y1, x0:x1]
                if (solid[y0:y1, x0:x1] & ((xx - cx) ** 2 + (yy - cy) ** 2 <= r * r)).any(): return False
            return True
        best = None
        for step in range(0, 61, 2):                    # smallest move first: up, up-right, right
            for dx, dy in ((0, -step), (step // 2, -step), (step, -step // 2), (step, 0)):
                if clear(dx, dy): best = (dx, dy); break
            if best: break
        lift[i] = best or (0, -60)
    # hold each lift a frame either side, so the bubble eases instead of popping
    held = np.array([[max(lift[(i + k) % n, 0] for k in (-1, 0, 1)), min(lift[(i + k) % n, 1] for k in (-1, 0, 1))] for i in range(n)])
    return off + held                                   # per frame, from the anchor the firmware places it by

def build(name, L, indexed=True):
    sc = L['scale']
    frames = [Image.open(io.BytesIO(base64.b64decode(f.split(',', 1)[1]))).convert('RGBA') for f in L['frames']]
    w, h = round(L['w'] * sc), round(L['h'] * sc)
    frames = [f.resize((w, h), Image.LANCZOS) for f in frames]
    ax, ay = L['ax'] * sc, L['ay'] * sc
    below = L.get('below', h - ay)
    bottoms = L.get('bottoms') or [below] * len(frames)
    cx = (L.get('cx', L['ax'])) * sc
    ground = ay + below * sc + GAP                      # shadow centre line, in frame coords
    shw = max(28, 0.32 * w)                             # resting shadow half-width
    H = int(np.ceil(ground + 8)); W = w
    out = []
    for f, b in zip(frames, bottoms):
        c = Image.new('RGBA', (W, H), (0, 0, 0, 0) if indexed else BG + (255,))
        hgt = max(0.0, ground - (ay + b * sc))          # how far this frame's lowest point is above the ground
        k = max(0.45, 1 - hgt / 160)
        sw, sh = shw * k, shw * k * 0.17
        sh_layer = Image.new('RGBA', (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(sh_layer).ellipse((cx - sw, ground - sh, cx + sw, ground + sh), fill=SHADOW[:3] + (int(SHADOW[3] * k),))
        c.alpha_composite(sh_layer)
        c.alpha_composite(f, (0, 0))
        out.append(c if indexed else c.convert('RGB'))
    # trim the shared bounding box of visible pixels across every frame
    mask = np.zeros((H, W), bool)
    if indexed:
        for o in out: mask |= np.asarray(o)[..., 3] > 8
    else:
        bgarr = np.array(BG)
        for o in out: mask |= np.abs(np.asarray(o).astype(int) - bgarr).sum(-1) > 6
    ys, xs = np.nonzero(mask)
    x0, x1, y0, y1 = max(0, xs.min() - 2), min(W, xs.max() + 3), max(0, ys.min() - 2), min(H, ys.max() + 3)
    out = [o.crop((x0, y0, x1, y1)) for o in out]
    rel = np.clip(np.round(face_track(frames, (ax, ay))), -127, 127).astype(int)
    return out, (ax - x0, ay - y0), L['fps'], bool(L.get('pingpong')), (rel[:, 0].tolist(), rel[:, 1].tolist())

def quantize_shared(frames):
    """One 256-entry ARGB palette for all frames; returns (palette bytes in LVGL's B,G,R,A order, [index arrays])."""
    W, H = frames[0].size
    mos = Image.new('RGBA', (W, H * len(frames)))
    for i, f in enumerate(frames): mos.paste(f, (0, i * H))
    a = np.array(mos); a[a[..., 3] < 8] = 0                    # near-invisible -> fully transparent
    q = Image.fromarray(a).quantize(colors=256, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE)
    pal = np.zeros((256, 4), np.uint8)
    p = np.array(q.getpalette('RGBA'), np.uint8).reshape(-1, 4)
    pal[:len(p)] = p
    idx = np.array(q, np.uint8)
    bgra = pal[:, [2, 1, 0, 3]].tobytes()
    return bgra, [idx[i * H:(i + 1) * H].tobytes() for i in range(len(frames))]

def c_bytes(b, per_line=40000):
    return ','.join(f'0x{x:02x}' for x in b)

def write_packed(out_dir, anim, name, pal, idxs, w, h, anchor, fps, pingpong=False, track=None):
    """Write <out_dir>/<anim>.c with the PetAnim <name>: the palette, then each frame's indices deflated
    separately (so the firmware can unpack the frame it needs). Returns (raw bytes, stored bytes)."""
    zs = []
    for b in idxs:
        c = zlib.compressobj(9, zlib.DEFLATED, -15, 9)   # raw deflate: no header or checksum to skip
        zs.append(c.compress(b) + c.flush())
    off = [0]
    for z in zs: off.append(off[-1] + len(z))
    n = len(idxs)
    hx, hy = track or ([0] * n, [0] * n)
    L = ['// Generated by tools/make_assets.py - do not edit.', '#include "../anims.h"', '',
         f'static const uint8_t {name}_pal[1024] = {{{c_bytes(pal)}}};',
         f'static const uint8_t {name}_z[] = {{{c_bytes(b"".join(zs))}}};',
         f'static const uint32_t {name}_zoff[] = {{ ' + ', '.join(map(str, off)) + ' };',
         f'static const int8_t {name}_hx[] = {{ ' + ', '.join(map(str, hx)) + ' };',
         f'static const int8_t {name}_hy[] = {{ ' + ', '.join(map(str, hy)) + ' };',
         f'static AnimSlot {name}_slot;',
         f'const PetAnim {name} = {{ {name}_pal, {name}_z, {name}_zoff, {n}, {fps}, {w}, {h}, {anchor[0]:.0f}, {anchor[1]:.0f}, '
         f'{int(pingpong)}, {name}_hx, {name}_hy, &{name}_slot }};']
    open(os.path.join(out_dir, f'{anim}.c'), 'w').write('\n'.join(L) + '\n')
    return (1024 + w * h) * n, 1024 + off[-1] + 4 * (n + 1)

def write_c(out_dir, anim, name, frames, anchor, fps, pingpong=False, track=None):
    W, H = frames[0].size
    pal, idxs = quantize_shared(frames)
    return write_packed(out_dir, anim, name, pal, idxs, W, H, anchor, fps, pingpong, track)

if __name__ == '__main__':
    args = sys.argv[1:]
    if '--gap' in args:
        i = args.index('--gap'); GAP = int(args[i + 1]); del args[i:i + 2]
    if '--shadow' in args:
        i = args.index('--shadow'); SHADOW = tuple(int(v) for v in args[i + 1].split(',')); del args[i:i + 2]
    out_dir = args[0].rstrip('/'); os.makedirs(out_dir, exist_ok=True)
    pet = os.path.basename(out_dir)
    raw = stored = 0
    for arg in args[1:]:
        anim, path = arg.split('=', 1)
        frames, anchor, fps, pp, track = build(anim, load(path), True)
        r, z = write_c(out_dir, anim, f'{pet}_{anim}', frames, anchor, fps, pp, track); raw += r; stored += z
        frames[0].save(os.path.join(out_dir, f'{anim}_preview.png'))
        print(f'{pet}_{anim}: {len(frames)} frames {frames[0].size} @ {fps} fps = {r / 1024:.0f} KB, {z / 1024:.0f} KB stored, '
              f'face moves x {min(track[0])}..{max(track[0])} y {min(track[1])}..{max(track[1])}')
    print(f'total {raw / 1048576:.2f} MB of frames, {stored / 1048576:.2f} MB stored')
