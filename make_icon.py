"""Draws the ALGS View launcher icon: four white feed tiles (one main, three in a strip) on indigo.

Writes Android adaptive-icon layers (108dp foreground PNGs per density; the indigo background is a
color resource) and legacy square/round icons, plus a preview.
Usage: python make_icon.py <res dir> <preview.png>
"""
import os
import sys

from PIL import Image, ImageDraw

RES, PREVIEW = sys.argv[1], sys.argv[2]
S = 1728                      # 108dp at 16x, downscaled later for smooth edges
C = S // 2
INDIGO = (58, 47, 191)
WHITE = (255, 255, 255)


def foreground() -> Image.Image:
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    width = S * 0.50          # stays inside the adaptive icon's 66dp circle
    height = width * 0.62
    gap = S * 0.018
    radius = S * 0.022
    left, top = C - width / 2, C - height / 2
    strip = (height - 2 * gap) / 3
    main_w = width - gap - strip * 1.25
    d.rounded_rectangle((left, top, left + main_w, top + height), radius=radius, fill=WHITE)
    x = left + main_w + gap
    for i in range(3):
        y = top + i * (strip + gap)
        d.rounded_rectangle((x, y, left + width, y + strip), radius=radius, fill=WHITE)
    return img


def save(img: Image.Image, path: str, size: int):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(path)


fg = foreground()
densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# Legacy icons show the middle 72dp of the 108dp canvas.
crop = int(S * 18 / 108)
full = Image.new("RGBA", (S, S), INDIGO + (255,))
full.alpha_composite(fg)
legacy = full.crop((crop, crop, S - crop, S - crop))
square_mask = Image.new("L", legacy.size, 0)
ImageDraw.Draw(square_mask).rounded_rectangle((0, 0, legacy.size[0] - 1, legacy.size[1] - 1), radius=legacy.size[0] // 5, fill=255)
round_mask = Image.new("L", legacy.size, 0)
ImageDraw.Draw(round_mask).ellipse((0, 0, legacy.size[0] - 1, legacy.size[1] - 1), fill=255)

for name, k in densities.items():
    save(fg, f"{RES}/mipmap-{name}/ic_launcher_foreground.png", round(108 * k))
    sq = legacy.copy(); sq.putalpha(square_mask)
    save(sq, f"{RES}/mipmap-{name}/ic_launcher.png", round(48 * k))
    rd = legacy.copy(); rd.putalpha(round_mask)
    save(rd, f"{RES}/mipmap-{name}/ic_launcher_round.png", round(48 * k))

# Preview: how the launcher's circle mask shows it.
circle = Image.new("L", (S, S), 0)
r = int(S * 33 / 108)  # the adaptive icon's visible circle is 66dp across
ImageDraw.Draw(circle).ellipse((C - r, C - r, C + r, C + r), fill=255)
out = Image.new("RGBA", (S, S), (240, 240, 240, 255))
out.paste(full, (0, 0), circle)
out.resize((512, 512), Image.LANCZOS).save(PREVIEW)
print("ok")
