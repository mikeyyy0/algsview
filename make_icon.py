"""Draws the ALGS View launcher icon: a green Nessie plush, seen from the side, on indigo.

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
GREEN = (92, 184, 48)
CREAM = (240, 232, 208)
BLACK = (12, 12, 14)
WHITE = (255, 255, 255)


def bez(p0, p1, p2, p3, n=40):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u**3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t**3 * p3[0],
                    u**3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t**3 * p3[1]))
    return out


def foreground() -> Image.Image:
    """Nessie side-on, facing left: green head, neck and body with a pointed tail and front flipper,
    cream throat and belly, one round black eye. Everything sits inside the adaptive icon's 66dp circle."""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    u = S / 108                      # 1dp

    def curve(*points):
        return bez(*[(C + x * u, C + y * u) for x, y in points])

    # The upper outline both colours share: snout, over the head, down the neck, along the back to the tail.
    top = (curve((-21, -18), (-21, -29), (-5, -30), (-4, -18))
           + curve((-4, -18), (-4, -12), (-4, -7), (-3, -3))
           + curve((-3, -3), (8, -7), (14, 3), (26, 11)))
    # The whole animal in cream first.
    belly = (curve((26, 11), (20, 13), (14, 12), (8, 12))
             + curve((8, 12), (0, 18), (-15, 20), (-17, 8))
             + curve((-17, 8), (-18, 1), (-16, -6), (-16, -13))
             + curve((-16, -13), (-16, -15), (-20, -15), (-21, -18)))
    d.polygon(top + belly, fill=CREAM)
    # Green over it, leaving the throat and belly showing. The flipper hangs from the body's lower edge.
    side = (curve((26, 11), (22, 12), (18, 11), (14, 10))
            + curve((14, 10), (12, 14), (9, 18), (6, 22))
            + curve((6, 22), (-1, 17), (-7, 10), (-10, 3))
            + curve((-10, 3), (-11, -2), (-11, -8), (-11, -13))
            + curve((-11, -13), (-13, -16), (-18, -16), (-21, -18)))
    d.polygon(top + side, fill=GREEN)
    # The eye, with a small catch-light.
    d.ellipse((C - 13.6 * u, C - 23.2 * u, C - 8.4 * u, C - 18 * u), fill=BLACK)
    d.ellipse((C - 12.4 * u, C - 22.2 * u, C - 11 * u, C - 20.8 * u), fill=WHITE)
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
