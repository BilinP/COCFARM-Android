"""Times the bot's detection steps on the phone screenshots (tests/fixtures/phones,
kept on the capturing PC only), for android/PERF.md.

    python android/bench.py [--sizes 2340x1080,1600x720] [--repeat 5]

Each step runs ``repeat`` times on a fresh Game (so nothing is remembered
between runs unless the bot itself keeps it across screens, as the UI scale is);
the median is printed in milliseconds. It runs on the PC, so the times are the
PC's: compare them before and after a change, not with a phone.
"""

from __future__ import annotations

import argparse
import statistics
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "android" / "app" / "src" / "main" / "python"))

import cv2  # noqa: E402

from farmbot import armybar  # noqa: E402
from farmbot.config import load_config  # noqa: E402
from farmbot.game import Game, T  # noqa: E402
from farmbot.screens import identify  # noqa: E402
from farmbot.tasks import attack, builder, search  # noqa: E402
from farmbot.vision import TemplateLibrary, Vision  # noqa: E402

PHONES = ROOT / "tests" / "fixtures" / "phones"


class _Still:
    def __init__(self, screen):
        self.screen = screen

    def screenshot(self):
        return self.screen

    def swipe(self, *args):
        pass


def _ms(fn, repeat: int) -> float:
    times = []
    for _ in range(repeat):
        start = time.perf_counter()
        fn()
        times.append((time.perf_counter() - start) * 1000)
    return statistics.median(times)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sizes", default="")
    parser.add_argument("--repeat", type=int, default=5)
    args = parser.parse_args()
    sizes = args.sizes.split(",") if args.sizes else sorted(p.name for p in PHONES.iterdir() if p.is_dir())

    import tempfile

    import cocfarm_settings

    cfg = load_config(cocfarm_settings.ensure_settings(tempfile.mkdtemp()))
    start = time.perf_counter()
    library = TemplateLibrary(ROOT / "templates")
    vision = Vision(library, default_threshold=0.85)
    for name in list(vars(T).values()):
        if isinstance(name, str) and "/" in name and library.has(name):
            library.get(name)
    print(f"load every picture: {(time.perf_counter() - start) * 1000:.0f} ms")

    rows = []
    for size in sizes:
        shots = {n: cv2.imread(str(PHONES / size / f"{n}.png")) for n in ("village", "battle_menu", "attack",
                                                                           "builder_base")}
        if any(v is None for v in shots.values()):
            continue

        def game(name, scale=0.0):
            return Game(_Still(shots[name]), vision, ui_scale=scale)

        calibrated = game("village")
        identify(calibrated)
        scale = calibrated.ui_scale

        def ready(name):
            g = game(name, scale)
            g.refresh()
            return g

        steps = {
            "calibrate (village, scale unknown)": lambda: identify(game("village")),
            "name the screen (village)": lambda: identify(game("village", scale)),
            "name the screen (battle)": lambda: identify(game("attack", scale)),
            "own resources": lambda: search.own_resources(ready("village"), cfg.regions),
            "Find a Match button": lambda: ready("battle_menu").find(T.FIND_MATCH),
            "scouted loot": lambda: search.read_loot(ready("attack"), cfg.regions),
            "troop bar cards": lambda: armybar.find_cards(ready("attack").screen, cfg.bar),
            "deploy edges (red line)": lambda: attack.deploy_edges(ready("attack"), cfg),
            "builder base known": lambda: builder.in_builder_base(ready("builder_base")),
        }
        for step, fn in steps.items():
            rows.append((size, step, _ms(fn, args.repeat)))
    width = max(len(s) for _, s, _ in rows) if rows else 10
    for size, step, ms in rows:
        print(f"{size:>10}  {step:<{width}}  {ms:8.1f} ms")
    by_step: dict[str, list[float]] = {}
    for _, step, ms in rows:
        by_step.setdefault(step, []).append(ms)
    print("\nmedian over sizes:")
    for step, values in by_step.items():
        print(f"  {step:<{width}}  {statistics.median(values):8.1f} ms")
    print(f"  {'total':<{width}}  {sum(statistics.median(v) for v in by_step.values()):8.1f} ms")


if __name__ == "__main__":
    main()
