#!/usr/bin/env python3
"""Draw the TV launcher banner (res/drawable-xhdpi/tv_banner.png).

    python3 android/tools/make-banner.py

Android TV and Fire TV launchers show an app by a 320x180 dp banner, not its
icon; without one the app is missing from the leanback launcher or shown as a
stretched icon. This draws the launcher icon's spectrum trace (the geometry of
res/drawable/ic_launcher_foreground.xml) beside the name.

A PNG rather than a vector because launchers render banners in their own
process and text in a vector would have to be paths. The text is drawn here
with the build machine's DejaVu Sans Bold, so the app carries no font; only
the pixels are committed. Rerun after changing the name or the icon.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent.parent / "testapp/src/main/res/drawable-xhdpi/tv_banner.png"
W, H = 640, 360          # 320x180 dp at xhdpi, the density TV launchers ask for
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
SS = 4                   # draw large and shrink, for antialiasing

img = Image.new("RGB", (W * SS, H * SS), "black")
d = ImageDraw.Draw(img)

scale = 2.6 * SS
ox, oy = 0, (H * SS - 108 * scale) / 2
P = lambda x, y: (ox + x * scale, oy + y * scale)
trace = [(30, 66), (40, 64), (46, 66), (52, 40), (58, 66), (66, 62), (72, 66), (78, 64)]
d.line([P(*p) for p in trace], fill="#4FC3F7", width=int(4 * scale), joint="curve")
d.line([P(30, 74), P(78, 74)], fill="#FFD54F", width=int(3 * scale))

tx = ox + 88 * scale
size = 84
while ImageFont.truetype(FONT, size * SS).getlength("OpenHamShack") > W * SS - tx - 24 * SS:
    size -= 2
f1 = ImageFont.truetype(FONT, size * SS)
f2 = ImageFont.truetype(FONT, int(size * 0.7) * SS)
d.text((tx, H * SS / 2 - 6 * SS), "OpenHamShack", font=f1, fill="white", anchor="ls")
d.text((tx + 2 * SS, H * SS / 2 + 58 * SS), "Icom", font=f2, fill="#FFD54F", anchor="ls")

OUT.parent.mkdir(parents=True, exist_ok=True)
img.resize((W, H), Image.LANCZOS).save(OUT, optimize=True)
print(OUT)
