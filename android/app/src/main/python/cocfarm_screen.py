"""The game's part of the phone's screen.

Android letterboxes an app that's held sideways on a screen whose shape it
doesn't fit (a foldable's inner screen, a tablet held the other way, a
phone's "full screen apps" setting turned off for the game). The capture is
then the whole display with black bars around the game. (Split screen puts
another app beside the game, not bars: that can't be told from the picture,
and the accessibility service never reads other apps' windows.) The bot's eyes and
fingers work on the game alone: its screenshots are cut to it, and taps are
moved back by the bars' size (cocfarm_android.AndroidDevice).

No Java here, so the PC's tests can use it as it is."""

from __future__ import annotations

import numpy as np

BAR_DARK = 10  # a bar's pixels are darker than this (0-255, brightest channel)
BAR_SHARE = 0.85  # ...nearly all of them: the gesture pill or the status bar's icons may lie on a bar
BAR_MIN = 8  # px: a thinner dark edge is the game's own
GAME_MIN = 0.5  # the game keeps at least this share of the width and of the height
STEADY = 3  # looks in a row with the same bars before the cut changes
LOOK_EVERY = 10  # once the bars are known, every this many screenshots (and whenever the size changes)
STEP = 4  # px between the rows and columns looked at first; the edges are then found to the pixel


def _run(dark: np.ndarray) -> int:
    """How many leading entries of ``dark`` are True."""
    if dark.all():
        return len(dark)
    return int(np.argmin(dark))


def _dark_line(line: np.ndarray) -> bool:
    """A whole row or column (H x 3 or W x 3) is a bar's."""
    return float((line[::2].max(axis=-1) < BAR_DARK).mean()) >= BAR_SHARE


def _edge(image: np.ndarray, coarse: int, axis: int, from_end: bool) -> int:
    """The bar's exact size in px along ``axis`` (0: rows, 1: columns), from the end
    if ``from_end``, given ``coarse`` dark lines in a row at every STEP-th line."""
    if coarse == 0:
        return 0
    n = image.shape[axis]
    size = (coarse - 1) * STEP + 1  # lines known dark: 0, STEP, ..., (coarse - 1) * STEP
    while size < min(coarse * STEP, n):
        k = n - 1 - size if from_end else size
        if not _dark_line(image[k] if axis == 0 else image[:, k]):
            break
        size += 1
    return size


def content_box(image: np.ndarray) -> tuple[int, int, int, int] | None:
    """The black bars round the game on ``image``: (left, top, right, bottom)
    in px, each 0 with none. None when there's no telling: the screen is
    (nearly) all dark, as while the game loads."""
    h, w = image.shape[:2]
    if image.ndim == 2:
        image = image[:, :, None]
    # Rows and columns first at every STEP-th: a few ms on a phone's screenshot.
    small = image[::STEP, ::STEP].max(axis=2) < BAR_DARK
    rows = small.mean(axis=1) >= BAR_SHARE
    cols = small.mean(axis=0) >= BAR_SHARE
    # The last row looked at may lie inside the bottom bar while the bar's first
    # rows are missed: the exact edges come from the full screenshot.
    top = _edge(image, _run(rows), 0, False)
    left = _edge(image, _run(cols), 1, False)
    bottom = _bar_from_end(image, rows, h, 0)
    right = _bar_from_end(image, cols, w, 1)
    if h - top - bottom < h * GAME_MIN or w - left - right < w * GAME_MIN:
        return None
    box = tuple(v if v >= BAR_MIN else 0 for v in (left, top, right, bottom))
    return box  # type: ignore[return-value]


def _bar_from_end(image: np.ndarray, coarse_dark: np.ndarray, n: int, axis: int) -> int:
    """A bar at the bottom or the right: its exact size from the coarse lines
    (0, STEP, 2 STEP...), whose last may stop short of the screen's end."""
    count = _run(coarse_dark[::-1])
    if count == 0:
        # The last line looked at isn't dark, but the screen's very last lines may be.
        last = (len(coarse_dark) - 1) * STEP
        size = 0
        while size < n - 1 - last:
            k = n - 1 - size
            if not _dark_line(image[k] if axis == 0 else image[:, k]):
                break
            size += 1
        return size
    first_dark = (len(coarse_dark) - count) * STEP  # the first line looked at that's dark, from the end's run
    size = n - first_dark
    while size < n:  # lines before it, down to the last one that wasn't dark
        k = n - 1 - size
        if k <= first_dark - STEP or not _dark_line(image[k] if axis == 0 else image[:, k]):
            break
        size += 1
    return size


def cut(image: np.ndarray, box: tuple[int, int, int, int]) -> np.ndarray:
    left, top, right, bottom = box
    if not any(box):
        return image
    h, w = image.shape[:2]
    return image[top:h - bottom, left:w - right]


class Letterbox:
    """The bars in use, changed only once the same new bars show on STEADY
    screenshots in a row: a dark moment in the game (a loading screen, a
    fade) never moves the bot's idea of where the game is. The first bars
    seen are taken at once, before the UI scale is measured on them."""

    def __init__(self) -> None:
        self.box: tuple[int, int, int, int] = (0, 0, 0, 0)
        self.known = False
        self._seen: tuple[int, int, int, int] | None = None
        self._count = 0
        self._shots = 0
        self._shape: tuple[int, ...] = ()

    @property
    def offset(self) -> tuple[int, int]:
        """What to add to a point on the game to tap it on the screen."""
        return self.box[0], self.box[1]

    def place(self, x: int, y: int) -> tuple[int, int]:
        """Point (x, y) on the game, on the screen, never left of or above the
        game: Android refuses a gesture at a negative point, and that ended
        a round mid-attack (a deploy point a few pixels past the top, on a
        Galaxy A16, 25 Nov 2026)."""
        left, top = self.offset
        return max(int(x), 0) + left, max(int(y), 0) + top

    def update(self, image: np.ndarray) -> bool:
        """Look at a new screenshot; True when the bars in use changed."""
        self._shots += 1
        if self.known and image.shape == self._shape and self._shots % LOOK_EVERY:
            return False  # (the bars stay put while the game plays: a look now and then is enough)
        self._shape = image.shape
        box = content_box(image)
        if box is not None and not self.known:
            self.known = True
            changed, self.box = box != self.box, box
            return changed
        if box is None or box == self.box:
            self._seen, self._count = None, 0
            return False
        if box != self._seen:
            self._seen, self._count = box, 0
        self._count += 1
        if self._count < STEADY:
            return False
        self.box, self._seen, self._count = box, None, 0
        return True

    def game(self, image: np.ndarray) -> np.ndarray:
        """``image`` cut to the game."""
        return cut(image, self.box)
