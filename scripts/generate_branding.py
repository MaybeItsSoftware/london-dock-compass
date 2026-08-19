#!/usr/bin/env python3
"""Generate every London Dock Compass branding asset from one vector source.

The mark: a bicycle wheel (rim + eight spokes + hub) whose hub carries a
compass needle. Spokes sit at 22.5 degrees off vertical so the needle reads
clearly between them.

Requires rsvg-convert and cwebp (brew install librsvg webp).
"""

import subprocess
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BRANDING = ROOT / "branding"
RES = ROOT / "app/src/main/res"
FASTLANE = ROOT / "fastlane/metadata/android/en-US/images"

# --- palette -----------------------------------------------------------------
RASPBERRY = "#d62246"
CHALK = "#faf8f4"
WHITE = "#ffffff"

# --- mark geometry, in a 512x512 design space --------------------------------
C = 256.0
RIM_OUTER = 240.0
RIM_WIDTH = 28.0
RIM_R = RIM_OUTER - RIM_WIDTH / 2      # stroked circle radius

SPOKE_COUNT = 8
SPOKE_WIDTH = 5.5
SPOKE_INNER = 24.0
SPOKE_OUTER = 200.0
SPOKE_OFFSET = 22.5                     # degrees off vertical

NEEDLE_N = 175.0                        # north tip, from centre
NEEDLE_S = 76.0                         # south tail, from centre
NEEDLE_W = 20.0                         # half-width at the pivot
NEEDLE_SOFTEN = 7.0                     # stroke that rounds the tips
NEEDLE_GAP = 10.0                       # clearance cut out of the spokes

HUB_OUTER = 44.0
HUB_HOLE = 16.0

MARK_SPAN = (RIM_OUTER * 2) / 512.0     # fraction of the design box the mark fills


def _spokes() -> str:
    out = []
    for i in range(SPOKE_COUNT):
        a = math.radians(SPOKE_OFFSET + i * (360.0 / SPOKE_COUNT) - 90.0)
        dx, dy = math.cos(a), math.sin(a)
        out.append(
            '<line x1="%.2f" y1="%.2f" x2="%.2f" y2="%.2f"/>'
            % (C + dx * SPOKE_INNER, C + dy * SPOKE_INNER,
               C + dx * SPOKE_OUTER, C + dy * SPOKE_OUTER)
        )
    return "".join(out)


NEEDLE_PATH = "M%.1f,%.1f L%.1f,%.1f L%.1f,%.1f L%.1f,%.1f Z" % (
    C, C - NEEDLE_N,
    C + NEEDLE_W, C,
    C, C + NEEDLE_S,
    C - NEEDLE_W, C,
)


def mark(ink: str, scale: float = 1.0, uid: str = "m") -> str:
    """The mark itself, as SVG content inside a 512x512 viewBox.

    Every hole is a real hole: the needle clearance and the hub bore are cut
    with masks, so the mark drops onto any background without a matte.
    """
    box = '<rect x="0" y="0" width="512" height="512" fill="#fff"/>'
    return f'''
  <defs>
    <mask id="{uid}-needle" maskUnits="userSpaceOnUse" x="0" y="0" width="512" height="512">
      {box}
      <path d="{NEEDLE_PATH}" fill="#000" stroke="#000"
            stroke-width="{NEEDLE_GAP + NEEDLE_SOFTEN:.1f}" stroke-linejoin="round"/>
    </mask>
    <mask id="{uid}-hub" maskUnits="userSpaceOnUse" x="0" y="0" width="512" height="512">
      {box}
      <circle cx="{C}" cy="{C}" r="{HUB_HOLE}" fill="#000"/>
    </mask>
  </defs>
  <g transform="translate({C},{C}) scale({scale:.5f}) translate({-C},{-C})">
    <g mask="url(#{uid}-needle)" fill="none" stroke="{ink}">
      <circle cx="{C}" cy="{C}" r="{RIM_R}" stroke-width="{RIM_WIDTH}"/>
      <g stroke-width="{SPOKE_WIDTH}" stroke-linecap="round">{_spokes()}</g>
    </g>
    <g mask="url(#{uid}-hub)" fill="{ink}">
      <path d="{NEEDLE_PATH}" stroke="{ink}"
            stroke-width="{NEEDLE_SOFTEN}" stroke-linejoin="round"/>
      <circle cx="{C}" cy="{C}" r="{HUB_OUTER}"/>
    </g>
  </g>'''


def icon_svg(ink: str, fill: float, bg: str | None = None,
             shape: str = "square", uid: str = "m") -> str:
    """A 512x512 icon: optional background plate, mark scaled to `fill` of the box."""
    plate = ""
    if bg:
        if shape == "circle":
            plate = f'<circle cx="{C}" cy="{C}" r="256" fill="{bg}"/>'
        elif shape == "rounded":
            plate = f'<rect x="0" y="0" width="512" height="512" rx="92" ry="92" fill="{bg}"/>'
        else:
            plate = f'<rect x="0" y="0" width="512" height="512" fill="{bg}"/>'
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" '
        'width="512" height="512">' + plate + mark(ink, fill / MARK_SPAN, uid) + "</svg>"
    )


def feature_svg() -> str:
    """1024x500 Play Store feature graphic: mark, then the wordmark in slab serif."""
    mark_box = 356.0
    mark_x, mark_y = 62.0, (500 - mark_box) / 2
    inner = mark(RASPBERRY, 0.90 / MARK_SPAN, "f")
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 500" width="1024" height="500">
  <rect x="0" y="0" width="1024" height="500" fill="{CHALK}"/>
  <svg x="{mark_x}" y="{mark_y}" width="{mark_box}" height="{mark_box}" viewBox="0 0 512 512">{inner}</svg>
  <g fill="{RASPBERRY}" font-family="Rockwell, Superclarendon, Georgia, serif"
     font-weight="bold" font-size="60" letter-spacing="1">
    <text x="470" y="232">LONDON DOCK</text>
    <text x="470" y="304">COMPASS</text>
  </g>
  <text x="470" y="362" fill="#6e6b7c" font-family="Rockwell, Superclarendon, Georgia, serif"
        font-size="21" letter-spacing="3.6">THE NEAREST DOCK, ON YOUR WRIST</text>
</svg>'''


# --- store screenshots -------------------------------------------------------
# The marketing screenshots are composites: a device capture on black, stamped
# with the mark and wordmark. Only that stamp is ours to regenerate, so we clear
# its (fixed, measured) box and redraw. Re-running is idempotent.
STAMPS = {
    "phoneScreenshots": dict(mark=(539.5, 151.5, 72), word=(539.5, 223.5, 508)),
    "tenInchScreenshots": dict(mark=(1709.5, 169.5, 86), word=(1512.5, 253.5, 508)),
}


def wordmark_png(out: Path, width: int) -> int:
    """Render the wordmark to `out` at exactly `width` px; return its height."""
    svg = BRANDING / "_wordmark.svg"
    svg.write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="3200" height="300">'
        f'<text x="20" y="200" fill="{RASPBERRY}" font-size="160" font-weight="bold" '
        'font-family="Rockwell, Superclarendon, Georgia, serif" letter-spacing="2">'
        "LONDON DOCK COMPASS</text></svg>"
    )
    raw = out.with_suffix(".raw.png")
    subprocess.run(["rsvg-convert", "-w", "3200", "-h", "300", str(svg), "-o", str(raw)],
                   check=True)
    subprocess.run(["magick", str(raw), "-background", "none", "-trim", "+repage",
                    "-resize", f"{width}x", str(out)], check=True)
    raw.unlink()
    svg.unlink()
    h = subprocess.run(["magick", "identify", "-format", "%h", str(out)],
                       check=True, capture_output=True, text=True).stdout
    return int(h)


def restamp_screenshots() -> None:
    logo = BRANDING / "logo.svg"
    word = BRANDING / "_wordmark.png"
    for folder, spec in STAMPS.items():
        mx, my, md = spec["mark"]
        wx, wy, ww = spec["word"]
        wh = wordmark_png(word, int(ww))
        # logo.svg draws the mark at MARK_SPAN of its box, so oversize to compensate.
        box = round(md / MARK_SPAN)
        mark_png = BRANDING / "_mark.png"
        subprocess.run(["rsvg-convert", "-w", str(box), "-h", str(box), str(logo),
                        "-o", str(mark_png)], check=True)
        for shot in sorted((FASTLANE / folder).glob("*.png")):
            subprocess.run([
                "magick", str(shot),
                # wipe the old stamp (padded, to take the antialiased edges with it)
                "-fill", "black", "-draw",
                "rectangle %d,%d %d,%d" % (mx - md / 2 - 6, my - md / 2 - 6,
                                           mx + md / 2 + 6, my + md / 2 + 6),
                "-draw", "rectangle %d,%d %d,%d" % (wx - ww / 2 - 8, wy - 26,
                                                    wx + ww / 2 + 8, wy + 26),
                str(mark_png), "-geometry",
                "+%d+%d" % (round(mx - box / 2), round(my - box / 2)), "-composite",
                str(word), "-geometry",
                "+%d+%d" % (round(wx - ww / 2), round(wy - wh / 2)), "-composite",
                str(shot),
            ], check=True)
            print("  restamped", shot.relative_to(ROOT))
        mark_png.unlink()
    word.unlink()


# --- splash vector -----------------------------------------------------------
def splash_vector(scale: float = 0.683) -> str:
    """The mark as an Android VectorDrawable, for the Android 12+ splash screen.

    The splash background is solid black (values/styles.xml), so the needle
    clearance and hub bore are painted black rather than masked — VectorDrawable
    has no mask primitive, and an opaque cut is exact against a known backdrop.
    """
    spokes = []
    for i in range(SPOKE_COUNT):
        a = math.radians(SPOKE_OFFSET + i * (360.0 / SPOKE_COUNT) - 90.0)
        dx, dy = math.cos(a), math.sin(a)
        spokes.append(
            '        <path android:pathData="M%.2f,%.2f L%.2f,%.2f"\n'
            '              android:strokeColor="%s" android:strokeWidth="%s"\n'
            '              android:strokeLineCap="round" />'
            % (C + dx * SPOKE_INNER, C + dy * SPOKE_INNER,
               C + dx * SPOKE_OUTER, C + dy * SPOKE_OUTER, RASPBERRY.upper(), SPOKE_WIDTH)
        )

    def ring(r: float) -> str:
        return "M%.1f,%.1f a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 -%s,0" % (
            C - r, C, r, r, r * 2, r, r, r * 2)

    ink = RASPBERRY.upper()
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by scripts/generate_branding.py — do not edit by hand.
  The compass needle in a bicycle wheel; source of truth is branding/logo.svg.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="512"
    android:viewportHeight="512">

    <group android:pivotX="{C}" android:pivotY="{C}"
           android:scaleX="{scale}" android:scaleY="{scale}">

        <!-- rim -->
        <path android:pathData="{ring(RIM_R)}"
              android:strokeColor="{ink}" android:strokeWidth="{RIM_WIDTH}" />

        <!-- spokes, offset off vertical so the needle sits between them -->
{chr(10).join(spokes)}

        <!-- needle: black clearance, then the needle itself -->
        <path android:pathData="{NEEDLE_PATH}"
              android:fillColor="#000000" android:strokeColor="#000000"
              android:strokeWidth="{NEEDLE_GAP + NEEDLE_SOFTEN}"
              android:strokeLineJoin="round" />
        <path android:pathData="{NEEDLE_PATH}"
              android:fillColor="{ink}" android:strokeColor="{ink}"
              android:strokeWidth="{NEEDLE_SOFTEN}" android:strokeLineJoin="round" />

        <!-- hub, bored through -->
        <path android:pathData="{ring(HUB_OUTER)}" android:fillColor="{ink}" />
        <path android:pathData="{ring(HUB_HOLE)}" android:fillColor="#000000" />
    </group>
</vector>
'''



# --- in-app vectors ----------------------------------------------------------
# The direction arrow points NORTH at rest, so the only maths at the call site is
# "bearing minus heading". The old drawable pointed left and every rotation in the
# app carried a ninety degree correction along with it.
ARROW_PATH = "M20,0 L40,20 L40,35 L25,20 L25,52 L15,52 L15,20 L0,35 L0,20 Z"


def arrow_vector(ink: str) -> str:
    """The compass arrow used behind the dock readout."""
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by scripts/generate_branding.py — do not edit by hand.
  Points north at zero rotation; the screen rotates it by (bearing - heading).
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="40dp"
    android:height="52dp"
    android:viewportWidth="40"
    android:viewportHeight="52">
    <path
        android:pathData="{ARROW_PATH}"
        android:fillColor="{ink}" />
</vector>
'''


def mono_icon_vector() -> str:
    """A 24dp monochrome glyph for the complication.

    Watch faces tint monochromatic images to their own colour, so this is drawn
    in white and carries no brand colour of its own — the silhouette does the
    identifying at a size where the full wheel would turn to mush.
    """
    return '''<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by scripts/generate_branding.py — do not edit by hand.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="40"
    android:viewportHeight="52">
    <path
        android:pathData="%s"
        android:fillColor="#FFFFFFFF" />
</vector>
''' % ARROW_PATH


# --- rendering ---------------------------------------------------------------
def write_svg(name: str, body: str) -> Path:
    p = BRANDING / name
    p.write_text(body)
    return p


def png(svg: Path, out: Path, w: int, h: int | None = None) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["rsvg-convert", "-w", str(w), "-h", str(h or w), str(svg), "-o", str(out)],
        check=True,
    )


def webp(svg: Path, out: Path, w: int) -> None:
    tmp = out.with_suffix(".tmp.png")
    png(svg, tmp, w)
    subprocess.run(["cwebp", "-quiet", "-lossless", str(tmp), "-o", str(out)], check=True)
    tmp.unlink()


DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def main() -> None:
    BRANDING.mkdir(exist_ok=True)

    # Source-of-truth vectors, kept in the repo so the mark can be re-cut later.
    logo = write_svg("logo.svg", icon_svg(RASPBERRY, 0.94, uid="logo"))
    # Adaptive foreground: mark held inside the 66/108dp safe zone.
    fg = write_svg("ic_launcher_foreground.svg", icon_svg(WHITE, 0.575, uid="fg"))
    square = write_svg("icon_square.svg",
                       icon_svg(WHITE, 0.68, bg=RASPBERRY, shape="rounded", uid="sq"))
    circle = write_svg("icon_round.svg",
                       icon_svg(WHITE, 0.66, bg=RASPBERRY, shape="circle", uid="rd"))
    store = write_svg("icon_store.svg",
                      icon_svg(WHITE, 0.62, bg=RASPBERRY, shape="square", uid="st"))
    feature = write_svg("feature_graphic.svg", feature_svg())

    for density, factor in DENSITIES.items():
        d = RES / f"mipmap-{density}"
        webp(fg, d / "ic_launcher_foreground.webp", round(108 * factor))
        webp(square, d / "ic_launcher.webp", round(48 * factor))
        webp(circle, d / "ic_launcher_round.webp", round(48 * factor))

    png(store, ROOT / "app/src/main/ic_launcher-playstore.png", 512)
    png(store, FASTLANE / "icon.png", 512)
    png(feature, FASTLANE / "featureGraphic.png", 1024, 500)
    png(logo, BRANDING / "logo.png", 1024)

    (RES / "drawable/splash_icon.xml").write_text(splash_vector())
    (RES / "drawable/arrow.xml").write_text(arrow_vector(RASPBERRY.upper()))
    (RES / "drawable/ic_dock.xml").write_text(mono_icon_vector())

    restamp_screenshots()

    print("branding regenerated")


if __name__ == "__main__":
    main()
