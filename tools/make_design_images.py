#!/usr/bin/env python3
"""Draw a design's pictures on this computer: Home's wallpaper, the screensaver's picture and, for designs
that have one, the welcome screen. With a logo, the design takes its colours from the logo. Logos are never
stored in this repository: the pictures are written outside it, and the logo file is only read.

    make_design_images.py <design folder> <output folder> [--logo <file>]

How a logo is treated depends on the file:
  transparent PNG          used as it is
  dark logo on white       the white goes transparent, dark ink becomes the design's ink colour, and
                           coloured parts such as gold stay as they are
  logo on a dark colour    that background goes transparent, and the colours stay as they are

The glass in the "liquid" style is baked into the pictures here, because the GPUs in these boxes cannot blur
live. It costs the box nothing, and the frost is real: it is the wallpaper behind it, blurred.
"""
import colorsys
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

W, H = 1920, 1080
USAGE = "usage: make_design_images.py <design folder> <output folder> [--logo <file>]"


def rgb(value):
    return np.array([int(value[i:i + 2], 16) for i in (1, 3, 5)]) / 255


def hex_of(colour):
    return "#{:02X}{:02X}{:02X}".format(*(int(round(float(c) * 255)) for c in colour))


def source_pixels(path):
    """The logo as RGBA, with its background transparent, before any recolouring."""
    source = Image.open(path)
    if source.mode in ("RGBA", "LA", "PA") or "transparency" in source.info:
        rgba = np.asarray(source.convert("RGBA")).astype(float) / 255
        if rgba[..., 3].min() < 0.95:
            return rgba, "transparent"
    src = np.asarray(source.convert("RGB")).astype(float) / 255
    corners = np.array([src[0, 0], src[0, -1], src[-1, 0], src[-1, -1]]).mean(axis=0)
    if corners.mean() < 0.5:
        alpha = np.clip((np.abs(src - corners).max(axis=2) - 0.05) / 0.25, 0, 1)
        return np.dstack([src, alpha]), "dark"
    return np.dstack([src, np.clip((1 - src.min(axis=2) - 0.08) / 0.5, 0, 1)]), "light"


def logo_colours(rgba):
    """The logo's main hues, largest first, ignoring greys. An empty list means a black and white logo."""
    pixels = rgba[rgba[..., 3] > 0.7][:, :3]
    if len(pixels) > 200000:
        pixels = pixels[:: len(pixels) // 200000]
    hsv = np.array([colorsys.rgb_to_hsv(*p) for p in pixels[:: max(1, len(pixels) // 40000)]])
    coloured = hsv[(hsv[:, 1] > 0.3) & (hsv[:, 2] > 0.12)]
    if not len(coloured):
        return []
    counts, edges = np.histogram(coloured[:, 0], bins=24, range=(0, 1))
    hues = []
    for i in np.argsort(-counts):
        if counts[i] < len(coloured) * 0.04:
            break
        hue = (edges[i] + edges[i + 1]) / 2
        if all(min(abs(hue - h), 1 - abs(hue - h)) > 0.08 for h, _ in hues):
            members = coloured[(coloured[:, 0] >= edges[i]) & (coloured[:, 0] < edges[i + 1])]
            hues.append((hue, float(np.median(members[:, 1]))))
    return hues


def muted(hue, saturation):
    """Yellow and gold turn olive or brown when darkened, so they stay close to a warm neutral instead."""
    return saturation * 0.5 if 0.06 < hue < 0.2 else saturation


def apply_palette(spec, hues):
    """Replace the design's colours with ones from the logo, dark enough for white text to stay readable."""
    if not hues:
        return spec
    first = hues[0]
    second = hues[1] if len(hues) > 1 else ((first[0] + 0.04) % 1, first[1] * 0.6)
    spec = dict(spec)
    spec["background"] = hex_of(colorsys.hsv_to_rgb(first[0], min(muted(first[0], first[1]), 0.62), 0.10))
    spec["glow"] = hex_of(colorsys.hsv_to_rgb(first[0], min(muted(first[0], first[1]), 0.72), 0.34))
    spec["glow2"] = hex_of(colorsys.hsv_to_rgb(second[0], min(muted(second[0], second[1]), 0.72), 0.34))
    return spec


def prepared_logo(rgba, kind, ink):
    if kind != "light":
        return rgba
    src = rgba[..., :3]
    coverage = 1 - src.min(axis=2)
    colourful = (src[..., 0] - src[..., 2]) / np.maximum(coverage, 0.02) > 0.25
    alpha = np.where(colourful, np.clip((coverage - 0.05) / 0.22, 0, 1), np.clip((coverage - 0.08) / 0.5, 0, 1))
    unmixed = np.clip((src - (1 - alpha[..., None])) / np.maximum(alpha[..., None], 0.02), 0, 1)
    return np.dstack([np.where(colourful[..., None], unmixed, ink), alpha])


def fitted(rgba, height, max_width=900):
    rows, cols = np.where(rgba[..., 3] > 0.3)
    if not len(rows):
        sys.exit("the logo file has nothing visible in it")
    crop = rgba[rows.min():rows.max() + 1, cols.min():cols.max() + 1]
    logo = Image.fromarray((crop * 255).round().astype("uint8"))
    width = round(logo.width * height / logo.height)
    if width > max_width:    # a wide logo stays clear of the clock and the top buttons
        width, height = max_width, round(logo.height * max_width / logo.width)
    return logo.resize((width, height), Image.LANCZOS)


def blobs(spec):
    """Dark ground with large soft pools of colour, which give glass something to refract."""
    y, x = np.mgrid[0:H, 0:W].astype(float)
    back = np.broadcast_to(rgb(spec["background"]), (H, W, 3)).copy()
    pools = [(330, 240, 720, "glow", 1.0), (1560, 300, 680, "glow2", 0.9), (1050, 980, 760, "glow", 0.7),
             (120, 980, 520, "glow2", 0.55)]
    for cx, cy, r, name, strength in pools:
        light = np.exp(-(((x - cx) / r) ** 2 + ((y - cy) / r) ** 2) * 1.6)[..., None] * strength
        back = back + (rgb(spec[name]) - rgb(spec["background"])) * light
    return back


def spot(spec, center_y):
    y, x = np.mgrid[0:H, 0:W]
    dist = np.sqrt(((x - W / 2) / 900) ** 2 + ((y - center_y) / 560) ** 2)
    light = np.clip(1 - dist, 0, 1)[..., None] ** 2
    base, glow = rgb(spec["background"]), rgb(spec["glow"])
    return base + (glow - base) * light


def canvas_from(back):
    # RGB_565 on these boxes bands a smooth dark gradient, so a little noise breaks the steps up.
    back = np.clip(back + np.random.default_rng(7).normal(0, 1.4 / 255, (H, W, 1)), 0, 1)
    return Image.fromarray((back * 255).round().astype("uint8")).convert("RGBA")


def rounded_mask(size, radius, inset=0, scale=4):
    w, h = size
    big = Image.new("L", (w * scale, h * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        (inset * scale, inset * scale, (w - inset) * scale - 1, (h - inset) * scale - 1), radius * scale, fill=255)
    return big.resize(size, Image.LANCZOS)


def glass(canvas, box, radius):
    """A pane of frosted glass: blurred backdrop, light from the top edge and a bright rim."""
    x0, y0, x1, y1 = box
    size = (x1 - x0, y1 - y0)
    mask = rounded_mask(size, radius)
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 120), (x0, y0 + 14), mask)
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(26)))
    frost = canvas.crop(box).convert("RGB").filter(ImageFilter.GaussianBlur(34))
    pane = np.asarray(frost).astype(float) / 255
    h, w = pane.shape[:2]
    pane = pane + (1 - pane) * 0.10
    sheen = np.clip(1 - np.linspace(0, 1, h) / 0.45, 0, 1)[:, None, None] ** 2 * 0.20
    pane = pane + (1 - pane) * sheen
    glassy = Image.fromarray((np.clip(pane, 0, 1) * 255).round().astype("uint8")).convert("RGBA")
    canvas.paste(glassy, (x0, y0), mask)
    rim = Image.new("L", size, 0)
    ImageDraw.Draw(rim).rectangle((0, 0, *size), fill=0)
    edge = ImageChops.subtract(mask, rounded_mask(size, max(radius - 2, 1), inset=2))
    fade = np.linspace(0.75, 0.22, h)[:, None]
    edge = Image.fromarray((np.asarray(edge).astype(float) * fade).astype("uint8"))
    canvas.paste((255, 255, 255, 255), (x0, y0), edge)


def picture(spec, center_y, logo, panels=()):
    canvas = canvas_from(blobs(spec) if spec.get("style") == "liquid" else spot(spec, center_y))
    for panel in panels:
        glass(canvas, tuple(panel[:4]), panel[4])
    if logo is not None:
        canvas.alpha_composite(logo, ((W - logo.width) // 2, center_y - logo.height // 2))
    # Dithered last, so the smooth glass panes do not band on the box's 16-bit screen buffer.
    final = np.asarray(canvas.convert("RGB")).astype(float) + np.random.default_rng(11).normal(0, 1.6, (H, W, 1))
    return Image.fromarray(np.clip(final, 0, 255).round().astype("uint8"))


def main():
    args = sys.argv[1:]
    logo_path = None
    if "--logo" in args:
        i = args.index("--logo")
        if i + 1 >= len(args):
            sys.exit(USAGE)
        logo_path = args[i + 1]
        del args[i:i + 2]
    if len(args) != 2:
        sys.exit(USAGE)
    design, out = Path(args[0]), Path(args[1])
    spec = json.loads((design / "images.json").read_text())
    logo = hero = None
    if logo_path:
        rgba, kind = source_pixels(logo_path)
        spec = apply_palette(spec, logo_colours(rgba))
        ready = prepared_logo(rgba, kind, rgb(spec["ink"]))
        logo = fitted(ready, spec["logo_height"])
        if spec.get("welcome_logo_height"):
            hero = fitted(ready, spec["welcome_logo_height"], 1000)
    out.mkdir(parents=True, exist_ok=True)
    picture(spec, spec["home_logo_center_y"], logo, spec.get("home_panels", ())).save(
        out / "wallpaper.jpg", quality=88, optimize=True)
    if spec.get("for_you_panel"):
        # The sheet slides over the dock, so its picture is the bottom of a plain wallpaper with one pane on it.
        top = spec["for_you_top"]
        pane = spec["for_you_panel"]
        picture(spec, spec["home_logo_center_y"], None, [pane]).crop((0, top, W, H)).save(
            out / "foryou.jpg", quality=88, optimize=True)
    picture(spec, spec["clock_logo_center_y"], logo).save(out / "clock.jpg", quality=88, optimize=True)
    if spec.get("welcome_panel"):
        # With a logo it is the hero of the screen, above a small pane. Without one, the pane takes the centre.
        pane = spec["welcome_panel_logo"] if hero is not None else spec["welcome_panel"]
        picture(spec, spec["welcome_logo_center_y"], hero, [pane]).save(out / "welcome.jpg", quality=88, optimize=True)
    config = out / "config.json"
    if config.is_file():
        settings = json.loads(config.read_text())
        settings["background_color"] = spec["background"]
        settings["welcome_logo"] = hero is not None
        config.write_text(json.dumps(settings, indent=2) + "\n")
    print(out)


if __name__ == "__main__":
    main()
