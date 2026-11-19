"""Makes the Android app's icon and logo pictures from COCFARM's crest
(farmbot/assets/cocfarm.png, 512 px, shared with Windows: read, never changed).

    python android/make_icons.py

Writes, under android/app/src/main/res:
  mipmap-*/ic_launcher_foreground.webp   the adaptive icon's crest, on a 108 dp canvas
  mipmap-*/ic_launcher_monochrome.webp   the same as one colour (Android 13's themed icons)
  mipmap-*/ic_launcher.webp              a whole icon for Android 7 (no adaptive icons)
  drawable-nodpi/cocfarm_logo.webp       the crest for the app's top bar and launch screen

The adaptive icon's background is a vector (drawable/ic_launcher_background.xml),
and mipmap-anydpi-v26/ic_launcher.xml puts the layers together.

The crest's farthest point (a sword's tip) is kept inside the 66 dp circle that
every launcher's mask leaves whole. Needs Pillow and numpy.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = Path(__file__).parent
CREST = HERE.parent / "farmbot" / "assets" / "cocfarm.png"
RES = HERE / "app" / "src" / "main" / "res"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
CANVAS_DP = 108  # an adaptive icon's layers
SAFE_RADIUS_DP = 33  # inside every mask
LEGACY_DP = 48
LOGO_PX = 384  # the top bar (32-40 dp) and the launch screen's crest (about 120 dp)

# The background's colours (drawable/ic_launcher_background.xml has the same).
NAVY_EDGE = (11, 18, 32)
NAVY_MIDDLE = (32, 50, 92)
GLOW = (245, 184, 61)


def crest() -> Image.Image:
    return Image.open(CREST).convert("RGBA")


def _reach(image: Image.Image) -> float:
    """How far the crest reaches from its middle, as a share of its width."""
    alpha = np.array(image)[..., 3] > 40
    ys, xs = np.nonzero(alpha)
    middle = (image.width - 1) / 2
    return float(np.hypot(xs - middle, ys - middle).max()) / image.width


def _shadow(layer: Image.Image, radius: float, offset: float, strength: float) -> Image.Image:
    """A soft shadow under ``layer``'s shape, then the layer on it."""
    alpha = layer.getchannel("A").point(lambda a: int(a * strength))
    shadow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    shadow.putalpha(alpha)
    shadow = shadow.filter(ImageFilter.GaussianBlur(radius))
    moved = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    moved.alpha_composite(shadow, (0, int(round(offset))))
    moved.alpha_composite(layer)
    return moved


def foreground(px_per_dp: float, image: Image.Image) -> Image.Image:
    size = round(CANVAS_DP * px_per_dp)
    width = SAFE_RADIUS_DP / _reach(image) * px_per_dp
    scaled = image.resize((round(width), round(width)), Image.LANCZOS)
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    at = (size - scaled.width) // 2
    layer.alpha_composite(scaled, (at, at))
    return _shadow(layer, radius=1.6 * px_per_dp, offset=1.2 * px_per_dp, strength=0.55)


def monochrome(px_per_dp: float, image: Image.Image) -> Image.Image:
    """One colour, the detail kept as shades: bright gold solid, the dark shield fainter."""
    size = round(CANVAS_DP * px_per_dp)  # (no shadow in a one-colour icon)
    width = SAFE_RADIUS_DP / _reach(image) * px_per_dp
    scaled = np.array(image.resize((round(width), round(width)), Image.LANCZOS)).astype(np.float32)
    lum = (0.299 * scaled[..., 0] + 0.587 * scaled[..., 1] + 0.114 * scaled[..., 2]) / 255
    alpha = scaled[..., 3] / 255 * np.clip(0.30 + 0.85 * lum, 0, 1)
    shape = np.zeros((scaled.shape[0], scaled.shape[1], 4), np.uint8)
    shape[..., :3] = 255
    shape[..., 3] = (alpha * 255).astype(np.uint8)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    at = (size - shape.shape[1]) // 2
    out.alpha_composite(Image.fromarray(shape, "RGBA"), (at, at))
    return out


def background(size: int) -> Image.Image:
    """The vector background, drawn as pixels (for the Android 7 icon)."""
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    middle = (size - 1) / 2
    r = np.hypot(x - middle, y - middle * 0.9) / (size * 0.62)
    t = np.clip(r, 0, 1)[..., None]
    navy = np.array(NAVY_MIDDLE, np.float32) * (1 - t) + np.array(NAVY_EDGE, np.float32) * t
    glow = np.clip(1 - np.hypot(x - middle, y - middle) / (size * 0.32), 0, 1)[..., None] ** 2 * 0.22
    rgb = navy * (1 - glow) + np.array(GLOW, np.float32) * glow
    out = np.dstack([rgb, np.full((size, size), 255, np.float32)]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def legacy(px_per_dp: float, image: Image.Image) -> Image.Image:
    """Android 7's icon: the background in a rounded square, the crest on it."""
    size = round(LEGACY_DP * px_per_dp)
    inset = round(2 * px_per_dp)  # (the launcher's own margin)
    inner = size - 2 * inset
    tile = background(inner)
    mask = Image.new("L", (inner, inner), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, inner - 1, inner - 1), radius=inner * 0.22, fill=255)
    tile.putalpha(mask)
    width = round(inner * 0.80)
    scaled = image.resize((width, width), Image.LANCZOS)
    crest_layer = Image.new("RGBA", (inner, inner), (0, 0, 0, 0))
    crest_layer.alpha_composite(scaled, ((inner - width) // 2, (inner - width) // 2))
    tile.alpha_composite(_shadow(crest_layer, 1.2 * px_per_dp, 0.8 * px_per_dp, 0.5))
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.alpha_composite(tile, (inset, inset))
    return out


def _save(picture: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    picture.save(path, "WEBP", lossless=True, quality=100, method=6)


def main() -> None:
    image = crest()
    for name, scale in DENSITIES.items():
        folder = RES / f"mipmap-{name}"
        _save(foreground(scale, image), folder / "ic_launcher_foreground.webp")
        _save(monochrome(scale, image), folder / "ic_launcher_monochrome.webp")
        _save(legacy(scale, image), folder / "ic_launcher.webp")
    _save(image.resize((LOGO_PX, LOGO_PX), Image.LANCZOS), RES / "drawable-nodpi" / "cocfarm_logo.webp")


if __name__ == "__main__":
    main()
