#!/usr/bin/env python3
"""Draw Wisp's emote bubbles and write them as LVGL ARGB8888 images (src/emotes.c).

Each emote is a thought bubble in the style of the one baked into the thirsty clip: an off-white, softly
mottled circle with two small dots trailing down and left toward Wisp's head, and an icon inside.
Drawn at 4x and scaled down for clean edges.
Usage: python3 tools/make_emotes.py src/emotes.c [preview.png]
"""
import sys
from PIL import Image, ImageDraw, ImageFont

S = 52          # output size, px
K = 4           # supersampling
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def canvas():
    """A blank layer in the icons' drawing coordinates (52 px, centred around 26,24); the bubble comes later."""
    im = Image.new("RGBA", (S * K, S * K), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


# the bubble, in output px
EW, EH = 44, 58                     # emote image size
BR, BCX, BCY = 19, 23, 20           # bubble circle
DOTS = ((13.0, 44.0, 3.4), (6.5, 52.5, 2.4))   # trailing dots: x, y, r (the last points at Wisp)
FILL = (234, 240, 245)
ICON = 24                           # the icon fits in a square this size, centred in the circle


def bubble():
    import random
    rnd = random.Random(7)
    im = Image.new("RGBA", (EW * K, EH * K), (0, 0, 0, 0))
    mask = Image.new("L", (EW * K, EH * K), 0)
    md = ImageDraw.Draw(mask)
    md.ellipse([(BCX - BR) * K, (BCY - BR) * K, (BCX + BR) * K, (BCY + BR) * K], fill=250)
    for x, y, r in DOTS:
        md.ellipse([(x - r) * K, (y - r) * K, (x + r) * K, (y + r) * K], fill=250)
    # soft mottling: a few random brightness blobs, blurred, like the painted bubble in the clip
    tex = Image.new("L", (8, 8))
    tex.putdata([rnd.randint(0, 255) for _ in range(64)])
    tex = tex.resize((EW * K, EH * K), Image.BICUBIC)
    col = Image.new("RGBA", (EW * K, EH * K))
    px = []
    for v in tex.getdata():
        d = (v - 128) * 7 // 128
        px.append((FILL[0] + d, FILL[1] + d, min(255, FILL[2] + d), 255))
    col.putdata(px)
    im.paste(col, (0, 0), mask)
    return im


HI = False          # --app: keep the 4x drawing (for the phone app) instead of scaling to dial size


def compose(icon):
    """Crop the icon to what it draws, fit it in the bubble's circle, and return the emote at output size."""
    box = icon.getbbox()
    icon = icon.crop(box)
    k = ICON * K / max(icon.width, icon.height)
    icon = icon.resize((max(1, round(icon.width * k)), max(1, round(icon.height * k))), Image.LANCZOS)
    im = bubble()
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    layer.paste(icon, (round(BCX * K - icon.width / 2), round(BCY * K - icon.height / 2)))
    im.alpha_composite(layer)
    return im if HI else im.resize((EW, EH), Image.LANCZOS)


def P(x, y):
    return (x * K, y * K)


def drop(d, cx, cy, r, col):
    d.ellipse([(cx - r) * K, (cy - r) * K, (cx + r) * K, (cy + r) * K], fill=col)
    d.polygon([P(cx - r * 0.92, cy - r * 0.35), P(cx, cy - r * 2.3), P(cx + r * 0.92, cy - r * 0.35)], fill=col)


def heart(d, cx, cy, r, col):
    d.ellipse([(cx - r * 2) * K, (cy - r) * K, cx * K + K // 2, (cy + r) * K], fill=col)
    d.ellipse([cx * K - K // 2, (cy - r) * K, (cx + r * 2) * K, (cy + r) * K], fill=col)
    d.polygon([P(cx - r * 1.95, cy + r * 0.3), P(cx, cy + r * 2.6), P(cx + r * 1.95, cy + r * 0.3)], fill=col)


def water():
    im, d = canvas()
    drop(d, 26, 27, 8.5, (63, 150, 255, 255))
    d.ellipse([22 * K, 24 * K, 25 * K, 28 * K], fill=(220, 238, 255, 255))   # highlight
    return im


def eyes():
    im, d = canvas()
    col = (232, 150, 30, 255)
    # a closed, resting eye: a downward curve with lashes
    d.arc([12 * K, 8 * K, 40 * K, 30 * K], 20, 160, fill=col, width=3 * K)
    for x0, y0, x1, y1 in ((16, 26, 13, 31), (26, 30, 26, 35), (36, 26, 39, 31)):
        d.line([P(x0, y0), P(x1, y1)], fill=col, width=3 * K)
    return im


def stretch():
    im, d = canvas()
    col = (40, 180, 90, 255)
    d.ellipse([22 * K, 9 * K, 30 * K, 17 * K], fill=col)                       # head
    w = 3 * K
    d.line([P(26, 18), P(26, 31)], fill=col, width=w)                          # body
    d.line([P(26, 20), P(17, 11)], fill=col, width=w)                          # arms up
    d.line([P(26, 20), P(35, 11)], fill=col, width=w)
    d.line([P(26, 31), P(20, 39)], fill=col, width=w)                          # legs
    d.line([P(26, 31), P(32, 39)], fill=col, width=w)
    return im


def move():
    im, d = canvas()
    col = (160, 90, 230, 255)
    for (x, y) in ((19, 27), (32, 18)):                                        # two footprints
        d.ellipse([(x - 4.5) * K, (y - 2) * K, (x + 4.5) * K, (y + 10) * K], fill=col)
        for tx, ty, r in ((-3.6, -5.2, 2.1), (-0.6, -6.6, 1.8), (2.2, -6.0, 1.6), (4.4, -4.2, 1.4)):
            d.ellipse([(x + tx - r) * K, (y + ty - r) * K, (x + tx + r) * K, (y + ty + r) * K], fill=col)
    return im


def content():
    im, d = canvas()
    col = (40, 150, 220, 255)
    f = ImageFont.truetype(FONT, 30 * K)
    d.text((12 * K, 4 * K), "\u266a", font=f, fill=col)          # a little tune
    return im


def happy():
    im, d = canvas()
    heart(d, 26, 22, 6.2, (255, 90, 140, 255))
    return im


def sad():
    im, d = canvas()
    col = (110, 140, 190, 255)
    heart(d, 26, 22, 6.2, col)
    # crack
    d.line([P(26, 16), P(23, 22), P(28, 25), P(25, 33)], fill=(255, 255, 255, 255), width=2 * K)
    return im


def mad():
    im, d = canvas()
    col = (235, 60, 60, 255)
    # the anger mark: four short arcs, each bowing in toward the centre
    cx, cy, r = 26, 24, 9
    for sx, sy, a0 in ((1, 1, 180), (-1, 1, 270), (-1, -1, 0), (1, -1, 90)):
        ox, oy = cx + sx * 11, cy + sy * 11
        d.arc([(ox - r) * K, (oy - r) * K, (ox + r) * K, (oy + r) * K], a0, a0 + 90, fill=col, width=3 * K)
    return im


def asleep():
    im, d = canvas()
    col = (90, 120, 200, 255)
    f1 = ImageFont.truetype(FONT, 20 * K)
    f2 = ImageFont.truetype(FONT, 13 * K)
    d.text((14 * K, 12 * K), "Z", font=f1, fill=col)
    d.text((29 * K, 8 * K), "z", font=f2, fill=col)
    return im


# ---- plain icons for the care ring: white on transparent, recoloured on the device ----
I = 64
W = (255, 255, 255, 255)


def icanvas():
    im = Image.new("RGBA", (I * K, I * K), (0, 0, 0, 0))
    return im, ImageDraw.Draw(im)


def Q(x, y):
    return (x * K, y * K)


def i_water():
    im, d = icanvas()
    r = 15
    d.ellipse([(32 - r) * K, (38 - r) * K, (32 + r) * K, (38 + r) * K], fill=W)
    d.polygon([Q(32 - r * 0.92, 38 - r * 0.35), Q(32, 6), Q(32 + r * 0.92, 38 - r * 0.35)], fill=W)
    return im


def i_eyes():
    im, d = icanvas()
    d.arc([8 * K, 4 * K, 56 * K, 40 * K], 20, 160, fill=W, width=6 * K)
    for x0, y0, x1, y1 in ((14, 33, 9, 42), (32, 40, 32, 50), (50, 33, 55, 42)):
        d.line([Q(x0, y0), Q(x1, y1)], fill=W, width=6 * K)
    return im


def i_stretch():
    im, d = icanvas()
    w = 6 * K
    d.ellipse([25 * K, 4 * K, 39 * K, 18 * K], fill=W)
    d.line([Q(32, 20), Q(32, 42)], fill=W, width=w)
    d.line([Q(32, 24), Q(15, 8)], fill=W, width=w)
    d.line([Q(32, 24), Q(49, 8)], fill=W, width=w)
    d.line([Q(32, 41), Q(21, 59)], fill=W, width=w)
    d.line([Q(32, 41), Q(43, 59)], fill=W, width=w)
    return im


def i_move():
    im, d = icanvas()
    for (x, y) in ((22, 34), (43, 18)):
        d.ellipse([(x - 8) * K, (y - 3) * K, (x + 8) * K, (y + 19) * K], fill=W)
        for tx, ty, r in ((-6.5, -9, 3.6), (-1, -11.5, 3.1), (4, -10.5, 2.8), (8, -7.5, 2.4)):
            d.ellipse([(x + tx - r) * K, (y + ty - r) * K, (x + tx + r) * K, (y + ty + r) * K], fill=W)
    return im


def i_play():
    im, d = icanvas()
    d.ellipse([8 * K, 8 * K, 56 * K, 56 * K], outline=W, width=6 * K)      # a ball
    d.arc([-14 * K, 16 * K, 30 * K, 60 * K], 300, 30, fill=W, width=5 * K)
    d.arc([34 * K, 4 * K, 78 * K, 48 * K], 120, 210, fill=W, width=5 * K)
    return im


def i_stats():
    im, d = icanvas()
    for x, h in ((10, 22), (26, 38), (42, 50)):
        d.rounded_rectangle([x * K, (58 - h) * K, (x + 12) * K, 58 * K], radius=3 * K, fill=W)
    return im


def i_moon():
    im, d = icanvas()
    d.ellipse([8 * K, 8 * K, 56 * K, 56 * K], fill=W)
    d.ellipse([22 * K, 0 * K, 66 * K, 44 * K], fill=(0, 0, 0, 0))
    return im


def i_pause():
    im, d = icanvas()
    d.rounded_rectangle([14 * K, 10 * K, 27 * K, 54 * K], radius=3 * K, fill=W)
    d.rounded_rectangle([37 * K, 10 * K, 50 * K, 54 * K], radius=3 * K, fill=W)
    return im


def i_back():
    im, d = icanvas()
    w = 7 * K
    d.line([Q(50, 32), Q(16, 32)], fill=W, width=w)
    d.line([Q(30, 16), Q(14, 32), Q(30, 48)], fill=W, width=w, joint="curve")
    for x, y in ((14, 32),):
        r = 3.5
        d.ellipse([(x - r) * K, (y - r) * K, (x + r) * K, (y + r) * K], fill=W)
    return im


ICONS = [("icon_water", i_water), ("icon_eyes", i_eyes), ("icon_stretch", i_stretch), ("icon_move", i_move),
         ("icon_play", i_play), ("icon_stats", i_stats), ("icon_moon", i_moon), ("icon_pause", i_pause),
         ("icon_back", i_back)]


EMOTES = [("emote_water", water), ("emote_eyes", eyes), ("emote_stretch", stretch), ("emote_move", move),
          ("emote_happy", happy), ("emote_sad", sad), ("emote_mad", mad), ("emote_asleep", asleep),
          ("emote_content", content)]


def to_c(name, im):
    px = im.load()
    w, h = im.size
    data = bytearray()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            data += bytes((b, g, r, a))            # LVGL ARGB8888 is B, G, R, A in memory
    rows = ",".join("0x%02x" % v for v in data)
    return (f"static const uint8_t {name}_map[] = {{{rows}}};\n"
            f"const lv_image_dsc_t {name} = {{\n"
            f"  .header = {{ .magic = LV_IMAGE_HEADER_MAGIC, .cf = LV_COLOR_FORMAT_ARGB8888, .flags = 0, .w = {w}, .h = {h}, .stride = {w * 4} }},\n"
            f"  .data_size = sizeof({name}_map), .data = {name}_map,\n}};\n")


def app_export(d):
    """PNG bubbles (176x232) and ring icons (256x256) for the Android app."""
    global HI
    import os
    os.makedirs(d, exist_ok=True)
    HI = True
    for name, fn in EMOTES:
        compose(fn()).save(os.path.join(d, name + ".png"))
    for name, fn in ICONS:
        fn().save(os.path.join(d, name + ".png"))
    HI = False


def main():
    if sys.argv[1] == "--app":
        app_export(sys.argv[2])
        return
    out = sys.argv[1]
    imgs = []
    src = ["// Generated by tools/make_emotes.py: Wisp's emote bubbles (ARGB8888).", "#include \"emotes.h\"", ""]
    for name, fn in EMOTES:
        im = compose(fn())
        imgs.append(im)
        src.append(to_c(name, im))
    icons = []
    for name, fn in ICONS:
        im = fn()
        # moon: the cut-out ellipse must clear pixels, which ImageDraw does with fill alpha 0 in RGBA mode
        im = im.resize((I, I), Image.LANCZOS)
        icons.append(im)
        src.append(to_c(name, im))
    open(out, "w").write("\n".join(src))
    if len(sys.argv) > 2:
        sheet = Image.new("RGBA", (len(imgs) * (EW + 8) + 8, EH + 16), (17, 26, 44, 255))
        for i, im in enumerate(imgs):
            sheet.alpha_composite(im, (8 + i * (EW + 8), 8))
        row2 = Image.new("RGBA", (len(icons) * (I + 8) + 8, I + 16), (17, 26, 44, 255))
        for i, im in enumerate(icons):
            row2.alpha_composite(im, (8 + i * (I + 8), 8))
        both = Image.new("RGBA", (max(sheet.width, row2.width), sheet.height + row2.height), (17, 26, 44, 255))
        both.alpha_composite(sheet, (0, 0)); both.alpha_composite(row2, (0, sheet.height))
        both.resize((both.width * 2, both.height * 2), Image.NEAREST).save(sys.argv[2])


if __name__ == "__main__":
    main()
