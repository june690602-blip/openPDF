"""CleanPDF asset renderer — all icons + store assets via 4x supersampling.

Design: deep-red (#D32F2F) ground, white document outline with a folded (dog-ear)
corner, two coral content lines, bold white "PDF" wordmark. Same line-art language
as CleanCAD, palette shifted blue -> red.

Outputs:
- app/src/main/res/mipmap-*/  : adaptive foreground + legacy square/round PNGs
- play-assets/store_icon_512.png        (Play store hi-res icon, full-bleed square)
- play-assets/icon_preview_512.png      (rounded preview)
- play-assets/feature_graphic_1024x500.png
"""
import os
from PIL import Image, ImageDraw, ImageFont

SS = 4
RES = "C:/dev/openPDF/app/src/main/res"
ASSETS = os.path.dirname(os.path.abspath(__file__))

RED = (211, 47, 47, 255)      # #D32F2F
WHITE = (255, 240, 237, 255)  # #FFF0ED
CORAL = (255, 138, 128, 255)  # #FF8A80
SLOGAN = (255, 224, 219, 255)


def font(paths, size):
    for p in paths:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


ARIAL_BD = ["C:/Windows/Fonts/arialbd.ttf"]
MALGUN_BD = ["C:/Windows/Fonts/malgunbd.ttf", "C:/Windows/Fonts/malgun.ttf"]


def text_centered(d, cx, cy, s, fnt, fill, tracking=0):
    ws = [d.textbbox((0, 0), c, font=fnt)[2] - d.textbbox((0, 0), c, font=fnt)[0] for c in s]
    total = sum(ws) + tracking * (len(s) - 1)
    fb = d.textbbox((0, 0), s, font=fnt)
    ty = cy - (fb[1] + fb[3]) // 2
    x = cx - total // 2
    for c, w in zip(s, ws):
        b = d.textbbox((0, 0), c, font=fnt)
        d.text((x - b[0], ty), c, font=fnt, fill=fill)
        x += w + tracking


def draw_doc(d, sf):
    """Draw the document mark in 512-space, scaled by sf onto the canvas."""
    P = lambda x, y: (x * sf, y * sf)
    t = max(1, int(26 * sf))
    outer = [(168, 140), (300, 140), (360, 200), (360, 372), (168, 372), (168, 140)]
    d.line([P(x, y) for x, y in outer], fill=WHITE, width=t, joint="curve")
    d.line([P(x, y) for x, y in [(300, 140), (300, 200), (360, 200)]],
           fill=WHITE, width=t, joint="curve")
    lw = max(1, int(17 * sf))
    rr = lw // 2
    for x1, y1, x2, y2 in [(202, 228, 298, 228), (202, 260, 282, 260)]:
        d.line([P(x1, y1), P(x2, y2)], fill=CORAL, width=lw)
        for cx, cy in [(x1, y1), (x2, y2)]:
            X, Y = P(cx, cy)
            d.ellipse([X - rr, Y - rr, X + rr, Y + rr], fill=CORAL)
    text_centered(d, int(264 * sf), int(322 * sf), "PDF",
                  font(ARIAL_BD, max(6, int(84 * sf))), WHITE, tracking=int(4 * sf))


def make(px, bg):
    """bg in {'rrect','square','circle',None(transparent foreground)}."""
    S = px * SS
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if bg == "rrect":
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(112 * S / 512), fill=RED)
    elif bg == "square":
        d.rectangle([0, 0, S - 1, S - 1], fill=RED)
    elif bg == "circle":
        d.ellipse([0, 0, S - 1, S - 1], fill=RED)
    draw_doc(d, S / 512.0)
    return img.resize((px, px), Image.LANCZOS)


def feature():
    W, H = 1024, 500
    img = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W * SS, H * SS], fill=RED)
    icon = make(300, None).resize((300 * SS, 300 * SS), Image.LANCZOS)
    img.alpha_composite(icon, (70 * SS, 100 * SS))
    d.text((410 * SS, 215 * SS), "CleanPDF Viewer", font=font(ARIAL_BD, 66 * SS),
           fill=WHITE, anchor="lm")
    d.text((410 * SS, 300 * SS), "광고 없는 무료 PDF 뷰어", font=font(MALGUN_BD, 38 * SS),
           fill=SLOGAN, anchor="lm")
    return img.resize((W, H), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


if __name__ == "__main__":
    dpis = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, m in dpis.items():
        fg, lg = int(108 * m), int(48 * m)
        save(make(fg, None), f"{RES}/mipmap-{name}/ic_launcher_foreground.png")
        save(make(lg, "square"), f"{RES}/mipmap-{name}/ic_launcher.png")
        save(make(lg, "circle"), f"{RES}/mipmap-{name}/ic_launcher_round.png")
    save(make(512, "square"), f"{ASSETS}/store_icon_512.png")
    save(make(512, "rrect"), f"{ASSETS}/icon_preview_512.png")
    save(feature(), f"{ASSETS}/feature_graphic_1024x500.png")
    print("OK: mipmaps + store_icon_512 + icon_preview_512 + feature_graphic_1024x500")
