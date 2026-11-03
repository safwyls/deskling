"""Turn a magenta-backdrop, in-place animation clip into a device-ready loop for the rig page.

usage: python3 process_clip.py FRAMES_DIR OUT_JS CONST_NAME SCALE [--min L] [--max L] [--avoid a-b] [--fps 12|24]
       [--strip N] [--keep F] [--min-area PX]
FRAMES_DIR holds 1024-px-wide PNG frames extracted from the clip.
SCALE is screen px per frame px. The loop is the cleanest repeat between --min and --max frames long.
"""
import sys, glob, json, io, base64, argparse
import numpy as np
from PIL import Image
from scipy import ndimage

ap = argparse.ArgumentParser()
ap.add_argument('frames'); ap.add_argument('out'); ap.add_argument('name'); ap.add_argument('scale', type=float)
ap.add_argument('--min', type=int, default=14); ap.add_argument('--max', type=int, default=60)
ap.add_argument('--avoid', default=''); ap.add_argument('--fps', type=int, default=12)
ap.add_argument('--strip', type=int, default=1, help='remove streaks thinner than about 2*N+1 px')
ap.add_argument('--keep', type=float, default=0.03, help='drop separate pieces smaller than this fraction of the body')
ap.add_argument('--range', default='', help='use exactly frames a-b instead of searching for a loop')
ap.add_argument('--pingpong', action='store_true', help='play forward then backward (for clips with no clean loop '
                'point); the device does the reversing, so it costs no extra frames')
ap.add_argument('--min-area', type=int, default=400, help='drop separate pieces smaller than this many px '
                '(lower it to keep tears, sparkles and anger marks)')
a = ap.parse_args()

fs = sorted(glob.glob(a.frames + '/*.png'))
A = lambda i: np.array(Image.open(fs[i]).convert('RGB')).astype(float)
f0 = A(len(fs) // 3)
edge = np.concatenate([f0[:30].reshape(-1, 3), f0[-30:].reshape(-1, 3), f0[:, :30].reshape(-1, 3), f0[:, -30:].reshape(-1, 3)])
BG = np.median(edge, 0)

avoid = set()
if a.avoid:
    lo, hi = map(int, a.avoid.split('-')); avoid = set(range(lo, hi + 1))

if a.range:
    i0, i1 = map(int, a.range.split('-')); err = 0.0
    if a.pingpong: i1 += 1                         # back-and-forth plays the last frame too
else:
    # loop point: the two most similar frames the right distance apart, with no avoided frame in between
    C = [A(i)[::4, ::4].mean(-1) for i in range(len(fs))]
    cands = []
    for i in range(len(C)):
        for j in range(i + a.min, min(len(C), i + a.max + 1)):
            if avoid & set(range(i, j + 1)): continue
            cands.append((np.abs(C[i] - C[j]).mean(), i, j))
    cands.sort(); err, i0, i1 = cands[0]

disk = lambda r: (lambda y, x: x * x + y * y <= r * r)(*np.ogrid[-r:r + 1, -r:r + 1])
def key(c):
    d = np.abs(c - BG).sum(-1)
    # anything in the backdrop's colour family (baked shadows, darker/lighter magenta) counts as background
    tint = ((c[..., 0] - c[..., 1]) > 60) & ((c[..., 2] - c[..., 1]) > 20)
    d = np.where(tint, 0, d)
    fg = d > 90
    if a.strip: fg = ndimage.binary_opening(fg, structure=disk(a.strip))
    lab, n = ndimage.label(fg); areas = ndimage.sum(fg, lab, range(1, n + 1))
    fg = np.isin(lab, 1 + np.nonzero(areas > max(a.min_area, a.keep * areas.max()))[0])   # drops detached motion marks
    # enclosed areas (eyes, wing gaps, a translucent bubble) take their alpha from colour distance, not a fill
    filled = ndimage.binary_fill_holes(fg)
    soft = np.clip((d - 40) / 90, 0, 1) * ndimage.binary_dilation(filled, iterations=2)
    al = np.maximum(soft, ndimage.binary_erosion(fg, iterations=1).astype(float))
    col = np.where(al[..., None] < 1, (c - (1 - al[..., None]) * BG) / np.maximum(al[..., None], 1e-3), c)
    return Image.fromarray(np.dstack([np.clip(col, 0, 255), al * 255]).astype('uint8'), 'RGBA')

def rle(data, th=4):
    o = 0; i = 0; n = len(data)
    while i < n:
        j = i
        while j < n and data[j] == data[i] and j - i < 127: j += 1
        if j - i >= th: o += 2; i = j; continue
        k = i
        while k < n and k - i < 127:
            r = 1
            while k + r < n and data[k + r] == data[k] and r < th: r += 1
            if r >= th: break
            k += 1
        o += 1 + (k - i); i = k
    return o

step = 24 // a.fps
idxs = list(range(i0, i1, step))
ims = [key(A(i)) for i in idxs]
boxes = np.array([im.getbbox() for im in ims])
x0, y0 = boxes[:, 0].min() - 4, boxes[:, 1].min() - 4; x1, y1 = boxes[:, 2].max() + 4, boxes[:, 3].max() + 4
mc = []; cxs = []
for i, im in zip(idxs, ims):
    nv = A(i).sum(-1) < 150; ys, xs = np.nonzero(nv); mc.append((xs.mean(), ys.mean()))
    cxs.append(np.nonzero(np.array(im)[..., 3] > 128)[1].mean())
AX, AY = np.array(mc).mean(0) - [x0, y0]; CX = float(np.mean(cxs) - x0)
ims = [im.crop((x0, y0, x1, y1)) for im in ims]; W0, H0 = ims[0].size
out = []; sizes = []; bottoms = []
for im in ims:
    b2 = im.convert('RGBa').resize((round(W0 * a.scale * 2), round(H0 * a.scale * 2)), Image.LANCZOS).convert('RGBA')
    buf = io.BytesIO(); b2.save(buf, 'WEBP', quality=90, method=6)
    out.append('data:image/webp;base64,' + base64.b64encode(buf.getvalue()).decode())
    dv = im.convert('RGBa').resize((round(W0 * a.scale), round(H0 * a.scale)), Image.LANCZOS).convert('RGBA'); dv = dv.crop(dv.getbbox())
    sizes.append(rle(bytes(dv.quantize(128, method=Image.Quantize.FASTOCTREE).tobytes())) + 512)
    bottoms.append(int(np.nonzero((np.array(im)[..., 3] > 128).any(1))[0].max() - AY))
loop = {'fps': a.fps, 'w': W0, 'h': H0, 'ax': float(AX), 'ay': float(AY), 'cx': CX, 'scale': a.scale,
        'below': max(bottoms), 'bottoms': bottoms, 'pingpong': bool(a.pingpong), 'frames': out}
open(a.out, 'w').write(f'const {a.name} = ' + json.dumps(loop) + ';\n')
ims[0].save(a.out + '.preview.png')
print(f'bg {BG.round()} | loop {i0}->{i1} ({i1 - i0} frames, err {err:.2f}) | kept {len(out)} @ {a.fps} fps | device {sum(sizes) / 1024:.1f} KB')
