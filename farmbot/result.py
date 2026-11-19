"""A battle's stars and Total damage, off its result screen.

The three stars sit over the "Victory"/"Defeat" ribbon: an earned one is
bright silver, one not earned a dark see-through shape. The middle star
carries "Total damage:" and the percentage in green. Both count up as the
screen opens, so a reading is taken once two in a row agree.
"""

from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

from farmbot import text
from farmbot.geometry import STANDARD_WIDTH, Rect

# On a screen STANDARD_WIDTH (860) wide: a spot on each star's body (left,
# the middle one's top point clear of its text, right).
STARS = ((325, 140), (430, 64), (535, 140))
STAR_HALF = 5
EARNED, UNEARNED = 200, 120  # a star's brightness: earned above, not earned below; between: no stars shown
DAMAGE = (390, 110, 475, 140)  # x0, y0, x1, y1 of the green "88%"
# The result screen grows from the screen's middle by the smaller of the
# width over 860 and the height over this: on a screen as narrow as the PC's
# (860 x 672) its width sets the size; on a phone's wide one, its height.
# Measured: the stars are 1.26x on a phone's 2340 x 1080 screen (1690 x 780 once
# scaled to the pictures), as big as the width alone would make them at 860.
LAYOUT_HEIGHT = 619
STANDARD_HEIGHT = 672


def _layout(screen: np.ndarray) -> tuple[float, float, float]:
    """How the 860 x 672 result layout sits on ``screen``: its size and middle."""
    height, width = screen.shape[:2]
    return min(width / STANDARD_WIDTH, height / LAYOUT_HEIGHT), width / 2, height / 2


def _at(screen: np.ndarray, x: float, y: float) -> tuple[int, int]:
    """A point of the 860 x 672 result layout, on ``screen``."""
    size, mid_x, mid_y = _layout(screen)
    return round(mid_x + (x - STANDARD_WIDTH / 2) * size), round(mid_y + (y - STANDARD_HEIGHT / 2) * size)
GREEN = ((35, 80, 120), (85, 255, 255))


@dataclass(frozen=True)
class Result:
    stars: int
    damage: int | None  # % (None: not read)


def stars(screen: np.ndarray) -> int | None:
    """Stars earned, or None if the three stars aren't plainly there."""
    s = _layout(screen)[0]
    value = cv2.cvtColor(screen, cv2.COLOR_BGR2HSV)[..., 2]
    count = 0
    for spot in STARS:
        (x, y), h = _at(screen, *spot), max(2, round(STAR_HALF * s))
        level = float(value[y - h:y + h, x - h:x + h].mean())
        if level >= EARNED:
            count += 1
        elif level > UNEARNED:
            return None
    return count


def split(mask: np.ndarray) -> list[np.ndarray]:
    """``mask`` as one digit, or as two where it is wider than tall: two
    digits touching ("71"), cut at the thinnest column of its middle half."""
    height, width = mask.shape
    if width <= height:
        return [mask]
    columns = (mask > 0).sum(axis=0)
    cut = width // 4 + int(np.argmin(columns[width // 4:width - width // 4]))
    halves = []
    for half in (mask[:, :cut], mask[:, cut:]):
        filled = np.flatnonzero((half > 0).any(axis=0))
        if filled.size:
            halves.append(half[:, filled[0]:filled[-1] + 1])
    return halves


def damage(screen: np.ndarray, font: text.Font) -> int | None:
    """The Total damage %, or None if it can't be read."""
    (x0, y0), (x1, y1) = _at(screen, *DAMAGE[:2]), _at(screen, *DAMAGE[2:])
    hsv = cv2.cvtColor(screen[y0:y1, x0:x1], cv2.COLOR_BGR2HSV)
    green = cv2.inRange(hsv, *GREEN)
    white = np.zeros((y1 - y0, x1 - x0, 3), np.uint8)
    white[green > 0] = 255  # the font knows white digits
    found = sorted(text.pieces(white, Rect(0, 0, x1 - x0, y1 - y0)), key=lambda p: p[0])
    if len(found) < 2:
        return None
    digits = ""
    masks = [half for _, mask in found[:-1] for half in split(mask)]  # the last is the % sign (it passes for an 8)
    for mask in masks:
        char, score = font.classify(mask)
        if not char.isdigit() or score < 0.4:
            return None
        digits += char
    return int(digits) if digits and int(digits) <= 100 else None


def read(screen: np.ndarray, font: text.Font | None) -> Result | None:
    """The stars and damage on a result ``screen``, or None (no stars there)."""
    earned = stars(screen)
    if earned is None:
        return None
    return Result(earned, damage(screen, font) if font is not None else None)
