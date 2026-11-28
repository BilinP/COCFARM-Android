"""COCFARM's bot on Android: the same farmbot package, with a device that
sees the screen through screen capture and taps through the app's
accessibility service (see Bridge.java). Called from BotService.java."""

from __future__ import annotations

import collections
import json
import logging
import os
from pathlib import Path
import threading
import time
from datetime import date
from logging.handlers import TimedRotatingFileHandler

import cv2
import numpy as np
from java import jarray, jclass, jint

from farmbot import __version__, accounts, catalog, history, redact
from farmbot.bot import Bot
from farmbot.geometry import Rect
from farmbot.config import load_config
from farmbot import friendly
from farmbot.device import REFOCUS_AWAY, GameNotInstalled
from farmbot.friendly import describe_status, explain
from farmbot.game import REFERENCE_HEIGHT, Game, GameNotInFront, Stopped
from farmbot.vision import TemplateLibrary, Vision

from farmbot.notify import Notifier

from cocfarm_screen import Letterbox
from cocfarm_settings import ensure_settings, load_settings, phone_alert

Bridge = jclass("com.cocfarm.app.Bridge")
log = logging.getLogger("farmbot.android")
_stop = threading.Event()
_pause = threading.Event()  # set: wait at the next safe point (the floating button)
_guard = {"held": "", "paused": False}  # the battery guard: why it's holding back, and whether it paused
GUARD_EVERY = 30  # seconds between looks at the battery
RESUME_MARGIN = 5  # % battery, or °C: how far back it must come before farming carries on
LOCK_LOOK = 1.0  # seconds between looks at a locked screen
UNLOCK_SETTLE = 1.0  # seconds for the game to come back to life once unlocked
BUTTON_GONE = 0.3  # seconds for the screen to be drawn again once the floating button is taken off
HOME_GRACE = 15.0  # seconds the home screen may be in front before the game is taken to have crashed or closed
WAIT_GIVE_UP = 30 * 60  # seconds a wait for the player (a locked screen, another app in front) lasts before the bot stops
running_config = None  # the farming bot's settings: the settings screen changes them as it saves
# The UI scale measured on each screen size. It is measured from the village's
# buttons, so a run that starts mid-battle or on the results screen needs it.
SCALES_FILE = "ui_scales.json"
CACHE = "cache"  # pictures for the home screen: the last base scouted
OWN_PACKAGE = "com.cocfarm.app"
LOG_LINES = 400  # the newest log lines the app can show

# What this run has done, for the home screen (see snapshot()).
_session: dict = {}
_lines: collections.deque = collections.deque(maxlen=LOG_LINES)
_line_count = 0  # lines logged so far: the app asks for the ones after the last it has
# The last screen the bot looked at, and when: saved (logs/SEEN_FILE) if it gives up, so
# the home screen can show what it saw and the report can carry it.
_seen: list = [None, 0.0]
SEEN_FILE = "last-screen.jpg"
SEEN_LONG_SIDE = 1280  # px: enough to read the screen, small enough to send


class _Lines(logging.Handler):
    """Keeps the newest log lines for the app's log screen."""

    def emit(self, record: logging.LogRecord) -> None:
        global _line_count
        try:
            stamp = time.strftime("%H:%M:%S", time.localtime(record.created))
            _lines.append((record.levelno, f"{stamp}  {record.getMessage()}"))
            _line_count += 1
        except Exception:  # never let the log break the bot
            pass


class PhoneClipboard:
    """The phone's own clipboard (text only), where the game's village export
    is copied to (farmbot.tasks.export)."""

    def available(self) -> bool:
        return True

    def text(self) -> str | None:
        text = Bridge.clipboardText()
        return None if text is None else str(text)

    def set_text(self, text: str) -> bool:
        return bool(Bridge.setClipboardText(text))


class AndroidDevice:
    """farmbot's Device, on the phone itself."""

    def __init__(self, package: str = "com.supercell.clashofclans"):
        self.package = package
        self.clipboard = PhoneClipboard()
        self.played = False  # the game has been opened this run (until then, COCFARM's screen is where Start was pressed)
        self.letterbox = Letterbox()  # black bars round the game (a foldable, a tablet)
        self._gave_up = False  # a wait on the player ran out (_hold)
        self.game_size: tuple[int, int] | None = None  # the game's part of the screen, once a screenshot showed it
        self.on_bars = None  # called with the game's (width, height) when the bars round it change
        # The phone's language, for farmbot.language's "the game may not be in English" (read over
        # an emulator's shell on Windows): Clash of Clans starts in it.
        self._android_language = _phone_language()
        # For a failure's log line, in place of the emulator setting (unused on a phone).
        self.phone_name = _phone_name()

    def _on_screen(self, x, y) -> tuple[int, int]:
        """A point on the game, where it is on the phone's screen (past any bars)."""
        return self.letterbox.place(x, y)

    def _in_game(self) -> None:
        """Touch only the game. While another app is in front (COCFARM's own
        screen, say), its taps would land on that app: wait for the game."""
        def elsewhere() -> bool:  # "": not known yet (the tap service just started), taken to be the game
            return str(Bridge.foregroundPackage()) not in ("", self.package)

        if not elsewhere():
            self._gave_up = False
            return
        log.info("Clash of Clans isn't in front: waiting for it before tapping")
        _status("Waiting for Clash of Clans to be in front")

        home = _home_package()
        since = time.monotonic()

        def still_away() -> bool:
            if self._wait_unlocked():
                _status("Waiting for Clash of Clans to be in front")
                since_reset()
            front = str(Bridge.foregroundPackage())
            if front not in ("", self.package) and front == home and time.monotonic() - since >= HOME_GRACE:
                # The home screen, with no app in use: the game crashed or closed (a player who
                # went to use another app has that app in front). The bot starts it again.
                raise GameNotInFront(f"the home screen ({front}) is in front instead of the game "
                                     "(it crashed or closed); not tapping")
            return elsewhere()

        def since_reset() -> None:
            nonlocal since
            since = time.monotonic()

        self._hold(still_away, 0.5, "Clash of Clans stayed in the background")
        log.info("Clash of Clans is back: carrying on")
        _status("Resuming")

    def _wait_unlocked(self) -> bool:
        """While the screen is off or locked, wait (saying why: taps can't reach a
        locked screen, and a screenshot of it shows no game). True if it waited."""
        if not _screen_locked():
            return False
        log.info("The screen is off or locked: waiting for it to be unlocked")
        _status("Screen locked: unlock the phone to carry on")
        self._hold(_screen_locked, LOCK_LOOK, "The phone's screen stayed locked")
        log.info("The screen is unlocked: carrying on")
        _status("Resuming")
        time.sleep(UNLOCK_SETTLE)  # (the game comes back to life)
        return True

    def _hold(self, busy, look: float, gave_up: str) -> None:
        """Wait while ``busy()``, looking every ``look`` seconds: these waits are on
        the player, so after WAIT_GIVE_UP the bot stops and says so (PlayerAway,
        which the bot never retries). Once one wait has given up, the next gives up at once."""
        give_up = time.monotonic() + (0 if self._gave_up else WAIT_GIVE_UP)
        while busy():
            if _stop.is_set():
                raise Stopped
            if time.monotonic() >= give_up:
                self._gave_up = True
                raise PlayerAway("%s for %d minutes: stopped" % (gave_up, WAIT_GIVE_UP // 60))
            time.sleep(look)
        self._gave_up = False

    def screenshot(self) -> np.ndarray:
        self._wait_unlocked()
        if self.played and str(Bridge.foregroundPackage()) == OWN_PACKAGE:
            # The player opened COCFARM mid-round: looking at its screen would fail the
            # round (a search's wait for Next runs out). Wait, then bring the game back.
            log.info("COCFARM's screen is open: waiting for it to close")
            _status("Waiting while COCFARM's screen is open")
            away = time.monotonic()
            self._hold(lambda: str(Bridge.foregroundPackage()) == OWN_PACKAGE, 0.5, "COCFARM's own screen stayed open")
            if str(Bridge.foregroundPackage()) not in ("", self.package):
                log.info("Bringing Clash of Clans back")
                self.start_app(self.package)
                # Back within seconds it picks up where it was; after longer it loads again (~15 s).
                time.sleep(3 if time.monotonic() - away < 10 else 20)
            _status("Resuming")
        frame = Bridge.screenshot()
        rows = np.frombuffer(bytes(frame.data), np.uint8).reshape(frame.height, frame.rowStride)
        rgba = rows[:, :frame.width * 4].reshape(frame.height, frame.width, 4)
        image = cv2.cvtColor(rgba, cv2.COLOR_RGBA2BGR)
        if self.letterbox.update(image):
            left, top, right, bottom = self.letterbox.box
            h, w = image.shape[:2]
            if any(self.letterbox.box):
                log.info("The game shows in %dx%d of the %dx%d screen (black bars: left %d, top %d, right %d, "
                         "bottom %d): looking at the game alone", w - left - right, h - top - bottom, w, h,
                         left, top, right, bottom)
            else:
                log.info("The game fills the screen again (%dx%d)", w, h)
            self.game_size = (w - left - right, h - top - bottom)
            if self.on_bars is not None:
                self.on_bars(*self.game_size)  # (its buttons' size to the height changed: the UI scale too)
        elif self.game_size is None:
            self.game_size = (image.shape[1], image.shape[0])
        image = self.letterbox.game(image)
        _seen[:] = [image, time.time()]  # (a new array each time: nothing to copy)
        return image

    def tap(self, x: int, y: int) -> None:
        self._in_game()
        Bridge.tap(*self._on_screen(x, y))

    def tap_many(self, points: list[tuple[int, int]], pause: float | list[float] = 0.0) -> None:
        self._in_game()
        if isinstance(pause, list):
            # A pause after each tap (uneven, as a person's): each tap its own gesture,
            # the pauses kept between them. Stop still cuts in between taps.
            gaps = pause + [0.0] * (len(points) - len(pause))
            for (x, y), gap in zip(points, gaps):
                if _stop.is_set():
                    raise Stopped
                self._in_game()
                sx, sy = self._on_screen(x, y)
                Bridge.tapMany(jarray(jint)([sx]), jarray(jint)([sy]), 0)
                if gap:
                    time.sleep(gap)
            return
        if points:
            moved = [self._on_screen(x, y) for x, y in points]
            xs = jarray(jint)([x for x, _ in moved])
            ys = jarray(jint)([y for _, y in moved])
            Bridge.tapMany(xs, ys, int(pause * 1000))

    def press(self, x: int, y: int, hold_ms: int) -> None:
        """A finger held on (x, y) for ``hold_ms`` ("Each tap held a moment", and
        "Hold to deploy", where the troop streams out under it)."""
        self._in_game()
        Bridge.press(*self._on_screen(x, y), int(hold_ms))

    def drag(self, points: list[tuple[int, int]], duration_ms: int) -> bool:
        """One finger down on the first point, through the others, and up on the
        last, over ``duration_ms`` ("Swipes curve", and "Hold to deploy" sliding
        along a side). False if Android didn't play it (the caller swipes plainly)."""
        self._in_game()
        if len(points) < 2:
            return False
        moved = [self._on_screen(x, y) for x, y in points]
        xs = jarray(jint)([x for x, _ in moved])
        ys = jarray(jint)([y for _, y in moved])
        return bool(Bridge.drag(xs, ys, int(duration_ms)))

    def zoom_out(self, times: int = 2) -> bool:
        self._in_game()
        return all(Bridge.pinch(*self.letterbox.box) for _ in range(times))  # (on the game, not on a bar)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> None:
        self._in_game()
        Bridge.swipe(*self._on_screen(x1, y1), *self._on_screen(x2, y2), int(duration_ms))

    def back(self) -> bool:
        self._in_game()
        return bool(Bridge.back())

    def uncover(self, bare: bool) -> None:
        """COCFARM's floating button off the game (``bare``) or back on it:
        the screenshots show it, and at first it sits where the game puts
        its clan war button. Once off, the next screenshot is one without it."""
        Bridge.buttonAside(bool(bare))
        if bare:
            time.sleep(BUTTON_GONE)

    def type_text(self, text: str) -> None:
        raise NotImplementedError("typing isn't needed on Android")

    def start_app(self, package: str) -> None:
        try:
            Bridge.startApp(package)
        except Exception as exc:  # (a Java IllegalStateException)
            if "isn't installed" in str(exc):
                raise GameMissing(package) from exc  # uninstalled mid-run, say: restarting won't help
            raise
        self.played = True

    def refocus_app(self, package: str) -> bool:
        """The game to the background (home) and straight back, without closing it:
        it drops a touch it thought was still held, after which pinches only drag the
        map (farmbot.device.AdbDevice.refocus_app, the same on a phone)."""
        if not Bridge.home():
            return False
        time.sleep(REFOCUS_AWAY)
        self.start_app(package)
        return True

    def game_shown(self) -> None:
        """The screenshots show the game (after start_app): it's in front."""
        Bridge.gameInFront(self.package)

    def stop_app(self, package: str) -> None:
        Bridge.stopApp(package)
        if not getattr(self, "_told_no_restart", False) and jclass("android.os.Build$VERSION").SDK_INT >= 34:
            # Android 14 and newer let an app end only its own processes: the game stays as it was.
            log.info("Android 14+ won't let COCFARM end the game: it's sent home, and picks up where it was")
            self._told_no_restart = True

    def foreground_package(self) -> str:
        app = str(Bridge.foregroundPackage())
        self.played |= app == self.package
        if app == OWN_PACKAGE and self.played:  # the player is looking at COCFARM: no reason to open the game over it
            log.info("COCFARM's screen is open: waiting for it to close")
            _status("Waiting while COCFARM's screen is open")
            self._hold(lambda: str(Bridge.foregroundPackage()) == OWN_PACKAGE, 0.5, "COCFARM's own screen stayed open")
            app = str(Bridge.foregroundPackage())
            _status("Resuming")
        return app

    def screen_size(self) -> tuple[int, int]:
        width, height = Bridge.screenSize()
        return int(width), int(height)


def read_text(image: np.ndarray) -> list[tuple[Rect, str]] | None:
    """The lines of text on ``image``, read by the phone's own text
    recognition (ML Kit, see Bridge.readText): farmbot.redact's reader."""
    ok, png = cv2.imencode(".png", image)
    if not ok:
        return None
    lines = []
    for line in str(Bridge.readText(png.tobytes())).splitlines():
        parts = line.split("\t", 4)
        if len(parts) == 5:
            x, y, w, h = (int(v) for v in parts[:4])
            lines.append((Rect(x, y, w, h), parts[4].strip()))
    return lines


def _logging(folder: str) -> None:
    root = logging.getLogger()
    if any(isinstance(h, TimedRotatingFileHandler) for h in root.handlers):
        return
    os.makedirs(os.path.join(folder, "logs"), exist_ok=True)
    handler = TimedRotatingFileHandler(os.path.join(folder, "logs", "farmbot.log"), when="midnight", backupCount=14,
                                       encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)-7s %(name)s: %(message)s"))
    root.addHandler(handler)
    root.addHandler(logging.StreamHandler())  # to logcat
    root.addHandler(_Lines())
    root.setLevel(logging.INFO)


def _scales(folder: str) -> dict[str, float]:
    try:
        with open(os.path.join(folder, SCALES_FILE), encoding="utf-8") as fh:
            return {str(k): float(v) for k, v in json.load(fh).items()}
    except (OSError, ValueError, AttributeError):
        return {}


def _home_package() -> str:
    try:
        return str(Bridge.homePackage())
    except Exception:  # (an older app's Bridge, or no telling)
        return ""


# Screen pixels per sp the Try Again and Reload Game pictures were taken at. Measured: the
# "Connection lost" dialog matched them at 1.46x at density 480 and 0.98x at 320 (font size 1).
DIALOG_PICTURE_SP = 2.06


def _dialog_scale() -> float:
    """The phone's own dialogs' size on its screen, to their pictures' (0: no telling)."""
    try:
        return round(float(Bridge.textPixels()) / DIALOG_PICTURE_SP, 3)
    except Exception:  # (an older app's Bridge, or the capture not running)
        return 0.0


def _scale_for(game: Game, folder: str, size: str) -> None:
    """The game's part of the screen changed size (black bars came or went):
    the UI scale remembered for that size, or measured again (0: unknown)."""
    scale = _scales(folder).get(size, 0.0)
    if scale == game.ui_scale:
        return
    log.info("UI scale for the game at %s: %s", size, "%.2f (remembered)" % scale if scale else "to be measured")
    game.ui_scale = scale
    game.vision.scales.clear()  # (sizes found before were relative to the old scale)
    game.vision.scales.update(game.picture_scales)
    game._calibration_tried.clear()
    game._seen_on = None


def _remember_scale(folder: str, size: str, scale: float) -> None:
    scales = _scales(folder)
    if scales.get(size) != scale:
        scales[size] = scale
        with open(os.path.join(folder, SCALES_FILE), "w", encoding="utf-8") as fh:
            json.dump(scales, fh, indent=1)


def _new_session() -> None:
    _session.clear()
    _session.update(started=time.time(), state="running", headline="Starting", detail="", bases=0, attacks=0,
                    gained=[0, 0, 0], base=None, attack=None, problem=None,
                    builder={"battles": 0, "second": 0, "gold": 0, "elixir": 0, "last": None})


def _phone_name() -> str:
    """"Samsung SM-A165F": the phone's maker and model ("" if not known)."""
    try:
        build = jclass("android.os.Build")
        maker, model = str(build.MANUFACTURER or "").strip(), str(build.MODEL or "").strip()
    except Exception:  # not known: the log line says "phone"
        return ""
    if maker and not model.lower().startswith(maker.lower()):
        model = f"{maker[:1].upper()}{maker[1:]} {model}"
    return model.strip()


def _phone_language() -> str | None:
    try:
        return str(jclass("java.util.Locale").getDefault().toLanguageTag()) or None
    except Exception:  # not known: no warning
        return None


def _screen_locked() -> bool:
    try:
        return bool(Bridge.screenLocked())
    except Exception:  # an older app without it, or no service yet
        return False


# What each self-check value means when it's a problem: (bad value, level, what to do).
class PlayerAway(GameNotInstalled):
    """A wait on the player ran out (the screen stayed locked, another app stayed
    in front). Like a missing game, restarting it won't help: the bot stops and
    says why (a GameNotInstalled, as GameNeedsUpdate is, so the bot stops on it)."""

    def __init__(self, message: str):
        RuntimeError.__init__(self, message)


class GameMissing(GameNotInstalled):
    """Clash of Clans isn't on the phone: the bot stops at once and says so."""

    def __init__(self, package: str):
        RuntimeError.__init__(self, f"Clash of Clans ({package}) isn't installed on this phone. "
                              "Install it from the Play Store and sign in, then press Start")


# What the phone's own waits stop with, in plain words (the shared rules are for an emulator).
PHONE_RULES = [
    (r"isn't installed on this phone",
     "Clash of Clans isn't on this phone",
     "COCFARM plays the game you have installed, and it isn't there (or the settings name another app). Install "
     "Clash of Clans from the Play Store and sign in, then press Start.", "retry", "Try again"),
    (r"screen stayed locked",
     "The phone stayed locked",
     "COCFARM can't see or touch the game on a locked screen, so after a long wait it stopped. Keep the screen "
     "on while it farms (the phone's screen timeout, or a charger with \"stay awake\").", "retry", "Try again"),
    (r"Clash of Clans stayed in the background",
     "Another app stayed in front of the game",
     "COCFARM only taps the game, so it waited for Clash of Clans to come back, then stopped. Open the game "
     "and press Start.", "retry", "Try again"),
    (r"COCFARM's own screen stayed open",
     "COCFARM's screen stayed open",
     "The bot waits while you look at COCFARM, so it doesn't play under you. It waited a long time, then "
     "stopped. Press Start, then go back to the game.", "retry", "Try again"),
]
friendly.RULES[:0] = [rule for rule in PHONE_RULES if rule not in friendly.RULES]


SELF_CHECK_WARNINGS = {
    "accessibility": ("off", logging.ERROR, "COCFARM's accessibility service is off: turn it on in Android's "
                      "Settings → Accessibility, or no tap reaches the game"),
    "capture": ("off", logging.ERROR, "screen capture isn't running: press Start again and allow it"),
    "battery_optimised": ("yes", logging.WARNING, "Android may stop COCFARM in the background: set its battery use "
                          "to Unrestricted (Settings → Apps → COCFARM → Battery)"),
    "notifications": ("off", logging.WARNING, "notifications are off for COCFARM: alerts and the running notice "
                      "won't show"),
    "locked": ("yes", logging.WARNING, "the screen is locked: COCFARM waits until it's unlocked"),
    "game": ("missing", logging.ERROR, "Clash of Clans isn't installed"),
}


def self_check(package: str) -> dict[str, str]:
    """Checks the phone once at the start and logs it: the Android version, the screen, the
    services the bot needs and the game. Anything wrong is logged as a warning with what to do."""
    try:
        text = str(Bridge.selfCheck(package))
    except Exception as exc:  # never stop a run over the check itself
        log.warning("Self-check couldn't run: %s", exc)
        return {}
    found = dict(line.split("=", 1) for line in text.splitlines() if "=" in line)
    log.info("Self-check: %s", ", ".join(f"{k} {v}" for k, v in found.items()))
    for key, (bad, level, advice) in SELF_CHECK_WARNINGS.items():
        if found.get(key) == bad:
            log.log(level, "Self-check: %s", advice)
    try:
        if float(found.get("font_scale", "1")) > 1.3:
            log.info("Self-check: a large font size is set; the game's own text is unaffected")
    except ValueError:
        pass
    return found


def _status(text: str) -> None:
    shown = describe_status(text)
    _session.update(state=shown.state, headline=shown.headline, detail=shown.detail)
    Bridge.status(shown.headline)


def _base_seen(folder: str, game: Game, cfg, loot, accepted: bool) -> None:
    _session["bases"] += 1
    search = cfg.search
    _session["base"] = {"gold": loot.gold, "elixir": loot.elixir, "dark": loot.dark, "accepted": accepted,
                        "minimums": [search.min_gold, search.min_elixir, search.min_dark], "time": time.time()}
    screen = game.screen
    if screen is not None:  # for the home screen, and "What the bot saw"
        try:
            os.makedirs(os.path.join(folder, CACHE), exist_ok=True)
            cv2.imwrite(os.path.join(folder, CACHE, "base.png"), screen)
            small = cv2.resize(screen, (720, round(screen.shape[0] * 720 / screen.shape[1])), interpolation=cv2.INTER_AREA)
            cv2.imwrite(os.path.join(folder, CACHE, "base.jpg"), small, [cv2.IMWRITE_JPEG_QUALITY, 80])
        except Exception as exc:
            log.debug("Couldn't keep the base's picture: %s", exc)
    if accepted and search.sound_on_match:
        Bridge.ding()


def attack_count() -> int:
    """This run's attacks (home and builder base), for the quick settings tile."""
    return int(_session.get("attacks", 0)) + int((_session.get("builder") or {}).get("battles", 0))


def _tile() -> None:
    try:
        Bridge.tileChanged()
    except Exception:  # no tile, or Java not there (a test)
        pass


def _attacked(record) -> None:
    _session["attacks"] += 1
    _tile()
    gained = record.gained
    if gained:
        _session["gained"] = [a + b for a, b in zip(_session["gained"], (gained.gold, gained.elixir, gained.dark))]
    _session["attack"] = {"time": time.time(), "target": [record.target.gold, record.target.elixir, record.target.dark],
                          "taken": record.taken, "gained": [gained.gold, gained.elixir, gained.dark] if gained else None}
    _check_goal()


GOAL_INDEX = {"gold": 0, "elixir": 1, "dark": 2}
GOAL_NAMES = {"gold": "gold", "elixir": "elixir", "dark": "dark elixir"}


def goal_progress(cfg=None) -> dict | None:
    """The run's goal and how far it's got: {resource, amount, got, done, eta}
    (``eta``: seconds left at this run's pace, or None before anything's
    gained), or None without a goal."""
    cfg = cfg or running_config
    goal = getattr(cfg, "goal", None) if cfg is not None else _session.get("goal_set")
    if goal is None:
        return None
    resource, amount = (goal.resource, goal.amount) if not isinstance(goal, dict) else (goal["resource"], goal["amount"])
    if resource not in GOAL_INDEX or amount <= 0:
        return None
    i = GOAL_INDEX[resource]
    got = _session.get("gained", [0, 0, 0])[i]
    builder = _session.get("builder") or {}
    got += {0: builder.get("gold", 0), 1: builder.get("elixir", 0)}.get(i, 0)
    done = got >= amount
    seconds = (time.time() - _session["started"]) if _session.get("started") else 0
    eta = None if done or got <= 0 or seconds <= 0 else int((amount - got) * seconds / got)
    return {"resource": resource, "amount": amount, "got": got, "done": done, "eta": eta}


def _check_goal() -> None:
    """Goal reached: say so, and stop (the attack is over: a safe point)."""
    cfg = running_config
    progress = goal_progress(cfg)
    if progress is None:
        return
    _session["goal_set"] = {"resource": progress["resource"], "amount": progress["amount"]}
    if not progress["done"] or _session.get("goal_reached"):
        return
    _session["goal_reached"] = True
    what = f"{_short(progress['amount'])} {GOAL_NAMES[progress['resource']]}"
    log.info("Goal reached: %s gained this run. Stopping.", what)
    if cfg.notify.phone:
        try:
            phone_alert("Goal reached", f"{what} gained this run. COCFARM stopped.", "good")
        except Exception as exc:  # notifications off: the home screen says it too
            log.debug("No alert for the goal: %s", exc)
    stop()


def _short(value: int) -> str:
    for size, suffix in ((1_000_000, "M"), (1_000, "k")):
        if value >= size:
            return f"{value / size:.1f}".rstrip("0").rstrip(".") + suffix
    return str(value)


def _builder_battle(battle: dict) -> None:
    b = _session["builder"]
    b["battles"] += 1
    _tile()
    b["second"] += battle.get("stages", 0) >= 2
    gained = battle.get("gained")
    if gained:
        b["gold"] += gained.gold
        b["elixir"] += gained.elixir
    b["last"] = {"stages": battle.get("stages", 0), "seconds": int(battle.get("seconds", 0)),
                 "gained": [gained.gold, gained.elixir] if gained else None}
    _check_goal()


# The UI scale every phone fixture measured (1.18-1.21): names are cut at it (accounts.name_ui_scale).
NAME_UI_SCALE = 1.18


def _account_seen(path: str, cfg):
    """Settings per account: the bot read a player name. Its profile (made the
    first time, as on Windows) is put in place in ``cfg``, which the bot holds."""
    profiles = accounts.Profiles.beside(path)

    def seen(mask, picture=None) -> str | None:
        try:
            name, made = accounts.for_account(profiles, cfg, mask, accounts.namer_for(picture))
            if name is None:
                return None
            if made:
                log.info("A new account: made the profile %s for it", name)
            if name != cfg.profiles.active:
                accounts.take(profiles.load(name, load_config(path)), cfg)
                cfg.profiles.active = name
                accounts.save_active(cfg, path)
                log.info("Settings: account %s's", name)
        except (OSError, ValueError) as exc:
            log.warning("Couldn't pick the account's settings: %s", exc)
            return None
        return name

    return seen


def _village_path(path: str, cfg):
    """Settings per account: each account's village export beside its profile
    (before any account is seen, the phone's own, as without them)."""
    from farmbot import instances, townhall

    return lambda profile: (accounts.Profiles.beside(path).village_path(profile) if profile
                            else instances.resolve(cfg, townhall.VILLAGE_FILE))


def run(folder: str) -> None:
    """Farm until stop() (or a problem). ``folder`` holds farmbot.toml,
    templates/ and the logs, as COCFARM's folder does on Windows."""
    global running_config
    _stop.clear()
    _pause.clear()
    _guard.update(held="", paused=False)
    _seen[:] = [None, 0.0]
    _new_session()
    os.chdir(folder)
    _logging(folder)
    redact.reader = read_text  # words on screen: the phone's text recognition, not Windows' OCR
    accounts.hud_height = REFERENCE_HEIGHT  # the player name is where the HUD puts it, not the screen's height
    accounts.name_ui_scale = NAME_UI_SCALE  # and cut at one fixed scale, so a name always looks the same
    log.info("COCFARM %s on Android", __version__)
    path = ensure_settings(folder)
    cfg = load_settings(folder)
    self_check(cfg.device.package)
    cfg.home = Path(folder)  # the village export and what's upgradable are kept here, as beside a Windows profile
    library = TemplateLibrary(cfg.bot.templates)
    missing = [] if cfg.search.maintain_only else catalog.missing(library, required_only=True)
    if missing:
        _problem("pictures missing: " + ", ".join(p.title for p in missing))
        Bridge.status("Add the button pictures first: " + ", ".join(p.title for p in missing) + ".")
        return
    _status("Starting")
    running_config = cfg
    _session["goal_set"] = {"resource": cfg.goal.resource, "amount": cfg.goal.amount}
    device = AndroidDevice(cfg.device.package)
    size = "%dx%d" % device.screen_size()
    game = Game(device, Vision(library, cfg.bot.threshold), stop_event=_stop,
                ui_scale=cfg.device.ui_scale or _scales(folder).get(size, 0.0),
                window_scale=cfg.device.window_scale, picture_scales=cfg.device.picture_scales)
    game.dialog_scale = _dialog_scale()
    if not cfg.device.ui_scale:
        device.on_bars = lambda width, height: _scale_for(game, folder, "%dx%d" % (width, height))
    done = threading.Event()
    threading.Thread(target=_watch_power, args=(cfg, done), name="battery", daemon=True).start()
    try:
        bot = Bot(game, cfg, on_status=_status, pause_event=_pause,
            notifier=Notifier(cfg.notify, local=phone_alert),
            on_base=lambda loot, ok: _base_seen(folder, game, cfg, loot, ok),
            on_attack=_attacked, on_builder=_builder_battle,
            on_account=_account_seen(path, cfg) if cfg.profiles.per_account else None,
            village_path=_village_path(path, cfg) if cfg.profiles.per_account else None)
        bot.pause_why = lambda: _guard["held"] if _guard["paused"] else ""
        bot.run()
    except Exception as exc:  # the bot gave up: say why, in plain words
        log.exception("The bot stopped")
        problem = _problem(str(exc), folder)
        Bridge.status(problem.title)
        return
    finally:
        done.set()
        running_config = None
        _session["ended"] = time.time()
        if not cfg.device.ui_scale and game.ui_scale:
            # Under the game's own size: letterboxed, its buttons are another share of the height.
            _remember_scale(folder, "%dx%d" % device.game_size if device.game_size else size, game.ui_scale)
    if _session.get("goal_reached"):
        _session.update(state="stopped", headline="Goal reached", detail="It stopped by itself.")
        Bridge.status("Goal reached")
        return
    _session.update(state="stopped", headline="Stopped", detail="")
    Bridge.status("Stopped")


def _problem(message: str, folder: str | None = None):
    problem = explain(message, "the game")
    picture, when = _save_seen(folder) if folder else ("", 0.0)
    _session.update(state="problem", headline=problem.title, detail=problem.why,
                    problem={"title": problem.title, "why": problem.why, "action": problem.action,
                             "details": problem.details, "picture": picture,
                             "seen": time.strftime("%H:%M", time.localtime(when)) if picture else ""})
    return problem


def _save_seen(folder: str) -> tuple[str, float]:
    """The last screen the bot looked at, as logs/SEEN_FILE (no larger than
    SEEN_LONG_SIDE): its path and when it was seen, or ("", 0) if there's none."""
    frame, when = _seen
    if frame is None:
        return "", 0.0
    try:
        high, wide = frame.shape[:2]
        scale = min(1.0, SEEN_LONG_SIDE / max(high, wide))
        if scale < 1.0:
            frame = cv2.resize(frame, (round(wide * scale), round(high * scale)), interpolation=cv2.INTER_AREA)
        path = os.path.join(folder, "logs", SEEN_FILE)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if not cv2.imwrite(path, frame, [cv2.IMWRITE_JPEG_QUALITY, 85]):
            return "", 0.0
        return path, when
    except (OSError, cv2.error) as exc:  # no picture is no reason to hide the problem
        log.warning("Couldn't keep the last screen: %s", exc)
        return "", 0.0


def snapshot(folder: str) -> str:
    """What this run has done, and today's totals, for the home screen."""
    out = dict(_session)
    out["paused"] = _pause.is_set()
    out["seconds"] = int((_session.get("ended") or time.time()) - _session["started"]) if _session.get("started") else 0
    out["lines"] = _line_count
    out["goal"] = goal_progress()
    out.pop("goal_set", None)
    cfg = running_config
    path = os.path.join(folder, cfg.bot.history if cfg else "attacks.csv")
    count, gained = history.totals_for(path, date.today())
    out["today"] = {"attacks": count, "gained": [gained.gold, gained.elixir, gained.dark]}
    return json.dumps(out)


def log_lines(after: int) -> str:
    """Log lines after the first ``after`` (as many as are still kept): [count, [[level, text], ...]]."""
    kept = list(_lines)
    first = _line_count - len(kept)  # the number of the oldest line kept
    new = kept[max(0, after - first):]
    return json.dumps([_line_count, new])


def power_hold(cfg, percent: int, charging: bool, celsius: float, held: str = "") -> str:
    """Why the phone shouldn't farm now ("": it may), from the battery
    (``cfg``: BatteryConfig). ``held``: the reason it's already waiting for,
    which then needs a margin before farming carries on, so it doesn't
    start and stop around the line."""
    if cfg.only_while_charging and not charging:
        return "Paused: not charging (plug the phone in)"
    if cfg.pause_below > 0:
        resume = min(100, cfg.pause_below + RESUME_MARGIN)
        if percent < (resume if held.startswith("Paused: battery") else cfg.pause_below):
            return f"Paused: battery {percent}% (resumes at {resume}%)"
    if cfg.pause_above > 0:
        resume = cfg.pause_above - RESUME_MARGIN
        if celsius > (resume if held.startswith("Paused: phone is hot") else cfg.pause_above):
            return f"Paused: phone is hot ({celsius:.0f}°C, resumes below {resume}°C)"
    return ""


def _check_power(cfg) -> None:
    """One look at the battery: pause farming, or let it carry on. A pause the
    player undoes (Resume) holds until the reason has gone, then counts again."""
    try:
        percent, charging, tenths = list(Bridge.battery())
    except Exception as exc:  # no reading: leave things as they are
        log.debug("Battery not read: %s", exc)
        return
    why = power_hold(cfg.battery, int(percent), bool(charging), tenths / 10, _guard["held"])
    if why and not _guard["held"]:
        log.info("%s", why)
        if not _pause.is_set():
            _pause.set()
            _guard["paused"] = True
            Bridge.heldBack(True)
            _status(why)
    elif not why and _guard["held"]:
        log.info("Battery all right again: %s%%, %.0f°C%s", percent, tenths / 10, ", charging" if charging else "")
        if _guard["paused"] and _pause.is_set():
            _pause.clear()
            Bridge.heldBack(False)
            _status("Carrying on")
        _guard["paused"] = False
    _guard["held"] = why


def _watch_power(cfg, done: threading.Event) -> None:
    while not done.is_set() and not _stop.is_set():
        _check_power(cfg)
        done.wait(GUARD_EVERY)


def toggle_pause(by: str = "") -> bool:
    """Pause at the next safe point (never mid-battle), or carry on. True if now paused.
    ``by``: what was pressed (the floating button, the notification, Home), for the log."""
    _guard["paused"] = False  # the player's call now, until the battery's reason changes
    if _pause.is_set():
        _pause.clear()
        log.info("Carry on pressed%s", f" ({by})" if by else "")
    else:
        _pause.set()
        log.info("Pause pressed%s: pausing after what it's doing now", f" ({by})" if by else "")
        Bridge.status("Pausing after what it's doing now…")
    return _pause.is_set()


def stop() -> None:
    _stop.set()
    Bridge.status("Stopping…")
