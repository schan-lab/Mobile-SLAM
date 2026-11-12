"""Generates the adaptive launcher icon (vector drawables) + a PNG preview."""
import math, sys
from PIL import Image, ImageDraw

RES = sys.argv[1]
PREVIEW = sys.argv[2]
CX, CY = 54.0, 54.0
A0, A1 = 0, 360                       # full 360-degree scan
# (radius, colour, dot radius, dot count)
RINGS = [(13, "#29D3E8", 1.5, 12), (21, "#2ECC71", 1.7, 18), (29, "#F5C842", 1.9, 24)]

def pt(r, deg):
    t = math.radians(deg)
    return CX + r * math.cos(t), CY + r * math.sin(t)

def circle(x, y, r):
    return f"M{x - r:.2f},{y:.2f}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0z"

def arc(r):
    return f"M{CX - r:.2f},{CY:.2f}a{r},{r} 0 1,0 {2 * r},0a{r},{r} 0 1,0 {-2 * r},0"

ARROW = f"M{CX},{CY - 7}L{CX + 5},{CY + 5}L{CX},{CY + 2.5}L{CX - 5},{CY + 5}z"

def dots(r, n):
    off = 7.5 * (r % 2)
    return [pt(r, off + 360.0 * i / n) for i in range(n)]

def foreground(mono=False):
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp" android:height="108dp"',
           '    android:viewportWidth="108" android:viewportHeight="108">']
    for r, col, dr, n in RINGS:
        c = "#FFFFFF" if mono else col
        out.append(f'  <path android:pathData="{arc(r)}" android:strokeColor="{c}" '
                   f'android:strokeAlpha="0.28" android:strokeWidth="1.2" android:strokeLineCap="round"/>')
        d = "".join(circle(x, y, dr) for x, y in dots(r, n))
        out.append(f'  <path android:pathData="{d}" android:fillColor="{c}"/>')
    out.append(f'  <path android:pathData="{circle(CX, CY + 1, 9)}" android:fillColor="{"#FFFFFF" if mono else "#FF4D5E"}" android:fillAlpha="0.18"/>')
    out.append(f'  <path android:pathData="{ARROW}" android:fillColor="{"#FFFFFF" if mono else "#FF4D5E"}"/>')
    out.append('</vector>\n')
    return "\n".join(out)

BACKGROUND = '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
  <path android:pathData="M0,0h108v108h-108z">
    <aapt:attr name="android:fillColor">
      <gradient android:type="linear" android:startX="0" android:startY="0"
          android:endX="108" android:endY="108"
          android:startColor="#0B0D12" android:endColor="#1C2E52"/>
    </aapt:attr>
  </path>
  <path android:pathData="M54,0L54,108M0,54L108,54"
      android:strokeColor="#3D8BFF" android:strokeAlpha="0.18" android:strokeWidth="0.8"/>
</vector>
'''

ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''

open(f"{RES}/drawable/ic_launcher_foreground.xml", "w").write(foreground())
open(f"{RES}/drawable/ic_launcher_monochrome.xml", "w").write(foreground(mono=True))
open(f"{RES}/drawable/ic_launcher_background.xml", "w").write(BACKGROUND)
for n in ("ic_launcher", "ic_launcher_round"):
    open(f"{RES}/mipmap-anydpi-v26/{n}.xml", "w").write(ADAPTIVE)

# ---- PNG preview (approximation of the vector, circular mask) ----
S = 8
im = Image.new("RGB", (108 * S, 108 * S))
d = ImageDraw.Draw(im)
for y in range(108 * S):
    for x in range(0, 108 * S, 4):
        t = (x + y) / (2 * 108 * S)
        c = tuple(int(a + (b - a) * t) for a, b in zip((11, 13, 18), (28, 46, 82)))
        d.line([(x, y), (x + 3, y)], fill=c)
ov = Image.new("RGBA", im.size); od = ImageDraw.Draw(ov)
for r, col, dr, n in RINGS:
    rgb = tuple(int(col[i:i + 2], 16) for i in (1, 3, 5))
    od.arc([(CX - r) * S, (CY - r) * S, (CX + r) * S, (CY + r) * S], 0, 360, fill=rgb + (72,), width=int(1.2 * S))
    for x, y in dots(r, n):
        od.ellipse([(x - dr) * S, (y - dr) * S, (x + dr) * S, (y + dr) * S], fill=rgb + (255,))
od.ellipse([(CX - 9) * S, (CY - 8) * S, (CX + 9) * S, (CY + 10) * S], fill=(255, 77, 94, 46))
od.polygon([(CX * S, (CY - 7) * S), ((CX + 5) * S, (CY + 5) * S), (CX * S, (CY + 2.5) * S), ((CX - 5) * S, (CY + 5) * S)], fill=(255, 77, 94, 255))
im = Image.alpha_composite(im.convert("RGBA"), ov)
mask = Image.new("L", im.size, 0); ImageDraw.Draw(mask).ellipse([9 * S, 9 * S, 99 * S, 99 * S], fill=255)
out = Image.new("RGBA", im.size, (255, 255, 255, 255)); out.paste(im, (0, 0), mask)
out.resize((256, 256), Image.LANCZOS).save(PREVIEW)
