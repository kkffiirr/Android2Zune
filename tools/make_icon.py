"""Build the ZTransfer launcher icon: a white arrow pointing into the Zune logo, on a dark tile.

The logo path is rendered from zune.svg (same logo and renderer as the ScummVM-on-Zune project's make_icon.py).
Run from the repo root:  python tools/make_icon.py
Writes app/src/main/res/mipmap-*/ic_launcher*.png and the adaptive-icon XML + background colour.
"""
import os
import re
from PIL import Image, ImageDraw, ImageChops

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
SVG = open(os.path.join(HERE, "zune.svg")).read()
D = re.search(r'<path d="(M129[^"]+)"', SVG).group(1)          # the logo path (the first path is an empty frame)
TOK = re.compile(r'[MmLlHhVvCcSsZz]|-?(?:\d+\.?\d*|\.\d+)')
BG = (22, 22, 26)                                               # dark tile, same as the ScummVM badge
WHITE = (255, 255, 255, 255)


def flatten(d):
    toks = TOK.findall(d); i = 0; subs = []; cur = None; x = y = sx = sy = 0.0; cmd = None; lc = None

    def num():
        nonlocal i
        v = float(toks[i]); i += 1
        return v
    while i < len(toks):
        if re.fullmatch(r'[A-Za-z]', toks[i]):
            cmd = toks[i]; i += 1
        if cmd in 'Zz':
            if cur: subs.append(cur); cur = None
            x, y = sx, sy; continue
        if cmd in 'Mm':
            nx, ny = num(), num()
            if cmd == 'm': nx += x; ny += y
            if cur: subs.append(cur)
            x, y = nx, ny; sx, sy = x, y; cur = [(x, y)]
            cmd = 'l' if cmd == 'm' else 'L'
        elif cmd in 'Ll':
            nx, ny = num(), num()
            if cmd == 'l': nx += x; ny += y
            x, y = nx, ny; cur.append((x, y))
        elif cmd in 'Hh':
            nx = num(); x = x + nx if cmd == 'h' else nx; cur.append((x, y))
        elif cmd in 'Vv':
            ny = num(); y = y + ny if cmd == 'v' else ny; cur.append((x, y))
        elif cmd in 'CcSs':
            if cmd in 'Ss':
                q = [num() for _ in range(4)]
                if cmd == 's': q = [q[0] + x, q[1] + y, q[2] + x, q[3] + y]
                r1 = (2 * x - lc[0], 2 * y - lc[1]) if lc else (x, y)
                p = [r1[0], r1[1]] + q
            else:
                p = [num() for _ in range(6)]
                if cmd == 'c': p = [p[0] + x, p[1] + y, p[2] + x, p[3] + y, p[4] + x, p[5] + y]
            lc = (p[2], p[3])
            x0, y0 = x, y
            for s in range(1, 21):
                t = s / 20; u = 1 - t
                cur.append((u ** 3 * x0 + 3 * u * u * t * p[0] + 3 * u * t * t * p[2] + t ** 3 * p[4],
                            u ** 3 * y0 + 3 * u * u * t * p[1] + 3 * u * t * t * p[3] + t ** 3 * p[5]))
            x, y = p[4], p[5]
    if cur: subs.append(cur)
    return subs


def logo_rgba(px):
    """Zune logo at px x px, transparent background, even-odd fill and the SVG's vertical gradient."""
    K = 4; S = px * K; sc = S / 150.0
    mask = Image.new("1", (S, S), 0)
    for sub in flatten(D):
        m = Image.new("1", (S, S), 0)
        ImageDraw.Draw(m).polygon([(a * sc, b * sc) for a, b in sub], fill=1)
        mask = ImageChops.logical_xor(mask, m)
    mask = mask.convert("L").resize((px, px), Image.LANCZOS)
    stops = [(0, (0xee, 0x90, 0x37)), (.0996, (0xee, 0x8e, 0x3e)), (.259, (0xee, 0x88, 0x4f)), (.4583, (0xec, 0x7d, 0x63)),
             (.6897, (0xea, 0x6c, 0x76)), (.9452, (0xe6, 0x55, 0x8a)), (1, (0xe6, 0x4e, 0x8d))]
    y1, y2 = 139.5481 - 1.5, 11.2096 - 1.5
    grad = Image.new("RGB", (px, px))
    for row in range(px):
        yy = (row + .5) / px * 150
        t = min(1, max(0, (yy - y1) / (y2 - y1)))
        for k in range(len(stops) - 1):
            if stops[k][0] <= t <= stops[k + 1][0]:
                f = (t - stops[k][0]) / (stops[k + 1][0] - stops[k][0]); a, b = stops[k][1], stops[k + 1][1]
                c = tuple(int(a[j] + (b[j] - a[j]) * f) for j in range(3)); break
        ImageDraw.Draw(grad).line([(0, row), (px, row)], fill=c)
    out = grad.convert("RGBA"); out.putalpha(mask)
    return out


def art(px):
    """Arrow + logo on a transparent px x px canvas laid out in the 108-unit adaptive-icon frame (safe zone = centre 66)."""
    K = 4; W = px * K; u = W / 108.0
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cy = 54
    d.rounded_rectangle([24 * u, (cy - 3.8) * u, 37 * u, (cy + 3.8) * u], radius=1.4 * u, fill=WHITE)   # shaft
    d.polygon([(33 * u, (cy - 10) * u), (46 * u, cy * u), (33 * u, (cy + 10) * u)], fill=WHITE)          # head
    L = int(34 * u)
    img.alpha_composite(logo_rgba(L), (int(68 * u - L / 2), int(cy * u - L / 2)))
    return img.resize((px, px), Image.LANCZOS)


def legacy(px):
    """Full-bleed square icon: dark rounded tile with the central 72 units of the artwork."""
    K = 4; W = px * K
    tile = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle([0, 0, W - 1, W - 1], radius=int(W * 0.22), fill=BG + (255,))
    big = art(int(W * 108 / 72))                                  # frame scaled so 72 units fill the tile
    off = int((big.width - W) / 2)
    tile.alpha_composite(big.crop((off, off, off + W, off + W)))
    return tile.resize((px, px), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


if __name__ == "__main__":
    dens = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
    for name, s in dens.items():
        save(art(int(108 * s)), os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"))
        save(legacy(int(48 * s)), os.path.join(RES, f"mipmap-{name}", "ic_launcher.png"))
    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
    os.makedirs(os.path.join(RES, "values"), exist_ok=True)
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n')
    open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), "w").write(adaptive)
    open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#16161A</color>\n</resources>\n')
    save(legacy(512), os.path.join(HERE, "icon_preview_512.png"))
    print("icon written")
