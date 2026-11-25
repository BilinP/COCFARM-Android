"""The number scrollers' ranges: for every number in Settings (and the tour's loot),
the lowest and highest value it scrolls to and the step between them, by Town Hall
where the game's numbers grow with it. All of it is in this one file.

Each scroller gets a list of choices, made here, so the screen never works numbers
out itself. A choice's value is written exactly as the settings file's values are
shown ("300,000", "10", 150), so picking the value already there changes nothing.

What a saved value does on a scroller:
- one of the choices: picked as it is;
- inside the range but between two steps (typed before scrollers, or read from the
  game, e.g. a storage's 4,321,987): kept, as an extra choice in its place, so
  opening Settings never changes it;
- outside the range: shown at the nearest end, and saved as that the next time
  anything is saved (never just from opening Settings).

The bounds stay inside farmbot/form.py's limits (shared with Windows, so not changed
here): every choice passes the same checks as before.
"""

from __future__ import annotations

MAX_TOWN_HALL = 19  # the table's top row; higher Town Halls (and an unknown one) use it


def _by_town_hall(rows: dict[int, tuple[int, int]]) -> dict[int, tuple[int, int]]:
    """{first Town Hall: (step, highest)} → the row for each Town Hall 1-19."""
    out, row = {}, None
    for th in range(1, MAX_TOWN_HALL + 1):
        row = rows.get(th, row)
        out[th] = row
    return out


# ---------------------------------------------------------------- loot, by Town Hall
# Rows are (step, highest), starting at 0; a row holds until the next Town Hall named.

# Loot a base must offer, gold and elixir (search.min_gold / min_elixir). Steps grow with
# the Town Hall: 1k while bases hold thousands (TH 1-3), 10k while they hold tens to
# hundreds of thousands (TH 4-8), 50k, then 100k from TH 15, where a good base offers
# 1-3 million. Every suggested amount (farmbot.config.SEARCH_BY_TOWN_HALL) is a step.
# The highest: about three times the suggested amount, more than bases usually offer.
BASE_LOOT = _by_town_hall({
    1: (1_000, 10_000), 2: (1_000, 25_000), 3: (1_000, 50_000),
    4: (10_000, 150_000), 5: (10_000, 250_000), 6: (10_000, 400_000), 7: (10_000, 500_000), 8: (10_000, 700_000),
    9: (50_000, 1_000_000), 10: (50_000, 1_200_000), 11: (50_000, 1_500_000), 12: (50_000, 1_800_000),
    13: (50_000, 2_000_000), 14: (50_000, 2_500_000),
    15: (100_000, 3_000_000), 17: (100_000, 3_500_000), 18: (100_000, 4_000_000),
})

# Dark elixir a base must offer (search.min_dark): its own smaller steps, as bases offer
# hundreds of it at TH 7 and tens of thousands at the top. Unlocked at TH 7; below that
# the TH 7 row (the setting stays, at 0). Every suggested amount (500-4,000) is a step.
BASE_DARK = _by_town_hall({
    1: (100, 5_000),
    9: (500, 15_000),
    13: (1_000, 30_000), 16: (1_000, 50_000),
})

# What the storages hold, all of them and the Town Hall's own (storage.gold / elixir), and
# the upgrade amounts measured against them (upgrade.start_* / reserve_*). Roughly the full
# storages at each Town Hall, rounded up (a TH 17-18 gold storage holds 5.5 million, four of
# them); a storage read from the game in between two steps is kept as it is.
STORAGE = _by_town_hall({
    1: (500, 5_000), 2: (1_000, 20_000), 3: (5_000, 200_000), 4: (10_000, 600_000),
    5: (25_000, 1_500_000), 6: (50_000, 2_500_000), 7: (50_000, 4_500_000),
    8: (100_000, 6_000_000), 9: (100_000, 9_000_000), 10: (100_000, 10_000_000),
    11: (250_000, 12_000_000), 12: (250_000, 14_000_000), 13: (250_000, 16_000_000), 14: (250_000, 18_000_000),
    15: (500_000, 22_000_000), 16: (500_000, 24_000_000), 17: (500_000, 26_000_000), 18: (500_000, 28_000_000),
})

# The dark elixir storage and the Town Hall's (storage.dark, upgrade.*_dark). From TH 7.
STORAGE_DARK = _by_town_hall({
    1: (1_000, 40_000), 8: (1_000, 80_000),
    9: (5_000, 200_000), 10: (5_000, 250_000), 11: (5_000, 300_000),
    12: (10_000, 350_000), 13: (10_000, 400_000), 14: (10_000, 450_000), 15: (10_000, 600_000),
})

# A run's goal (goal.amount): several storages' worth, so twice the storage step, four
# times its top. Gold or elixir use STORAGE, dark elixir STORAGE_DARK.
GOAL_STEP, GOAL_TOP = 2, 4

# The builder base's storages (storage.builder_gold / builder_elixir): not tied to the Town
# Hall (the Builder Hall is its own). Up to 8 million, by 50k.
BUILDER_STORAGE = (50_000, 8_000_000)


# ---------------------------------------------------------------- the other numbers
# (lowest, highest, step, {value: word shown in its place}). The same at every Town Hall:
# seconds, minutes, shares and counts don't grow with the village.
NONE, NEVER, OFF = "None", "Never", "Off"
NUMBERS = {
    # Alerts: a progress update every 5-100 attacks; one alert at most every 0-240 min.
    "notify.progress_every": (0, 100, 5, {0: NONE}),
    "notify.alert_cooldown_minutes": (0, 240, 5, {}),
    # Searching: own gold below 0-50% of the storages stops the search; give up after
    # 10-1000 bases, by 10.
    "search.min_own_gold_share": (0, 50, 1, {}),
    "search.max_searches": (10, 1000, 10, {}),
    # Heroes: abilities 0-60 s after landing, at 0-95% health (0: by time), at the latest 5-180 s.
    "attack.hero_ability_delay": (0, 60, 1, {}),
    "attack.ability_health": (0, 95, 5, {0: OFF}),
    "attack.ability_max_wait": (5, 180, 5, {}),
    # Hero walk: the army follows 5-120 s after her; Invisibility from 10-95% health, by 5.
    "attack.walk_time": (5, 120, 5, {}),
    "attack.walk_invisibility_health": (10, 95, 5, {}),
    # Earthquake Spells on one spot: 1-20.
    "attack.quakes_per_spot": (1, 20, 1, {}),
    # Ending the battle: 3-90 s without loot; leave at 0-100% of the loot (0: off), by 5%.
    "attack.no_loot_seconds": (3, 90, 1, {}),
    "attack.end_at_loot_share": (0, 100, 5, {0: OFF}),
    # League bonus: leave at 40-100% Overall Damage, by 5.
    "attack.league_bonus_damage": (40, 100, 5, {}),
    # Collecting every 1-50 rounds.
    "bot.collect_every": (1, 50, 1, {}),
    # Walls: keep 0-100% of the storages, by 5%.
    "walls.keep_gold": (0, 100, 5, {}),
    "walls.keep_elixir": (0, 100, 5, {}),
    # Builders upgrades may use: none, all but one, or 1-7 (six builders and B.O.B).
    "upgrade.builders": (-1, 7, 1, {-1: NONE, 0: "All but one"}),
    # Clan: request troops again after 5-240 min.
    "clan.request_every_minutes": (5, 240, 5, {}),
    # Clan Capital: heal at 10-95% health; lead the heal by 0-3 s, by half seconds.
    "capital.miner_heal_at": (10, 95, 5, {}),
    "capital.fortress_heal_at": (10, 95, 5, {}),
    "capital.heal_lead": (0, 3, 0.5, {}),
    # Builder battles: delays in whole seconds (0-30, or 0-60 for the hero's ability);
    # the hero swaps below 5-100% health; 1-10 troop abilities per round.
    "builder.troop_delay": (0, 30, 1, {}),
    "builder.switch_below": (5, 100, 5, {}),
    "builder.hero_ability_after": (0, 60, 1, {}),
    "builder.ability_delay": (0, 30, 1, {}),
    "builder.ability_batch": (1, 10, 1, {}),
    "builder.stage_delay": (0, 30, 1, {}),
    # Builder base collecting every 0-240 min (0: every battle); the cart every 1-50 battles.
    "builder.collect_minutes": (0, 240, 5, {0: "Every battle"}),
    "builder.cart_every_min": (1, 50, 1, {}),
    "builder.cart_every_max": (1, 50, 1, {}),
    "builder.walls_keep_gold": (0, 100, 5, {}),
    "builder.walls_keep_elixir": (0, 100, 5, {}),
    # The builder base's builders: none, all but one, or 1-3.
    "builder.upgrade_builders": (-1, 3, 1, {-1: NONE, 0: "All but one"}),
    # Battery: pause below 0-95% (0: never), by 5; above 30-60 °C by 1 (0: never).
    "battery.pause_below": (0, 95, 5, {0: NEVER}),
    "battery.pause_above": (30, 60, 1, {0: NEVER}),
    # Breaks: every 5-300 attacks, for 5-240 min, by 5; a day off every 2-30 days (0: never).
    "schedule.break_every_min": (5, 300, 5, {}),
    "schedule.break_every_max": (5, 300, 5, {}),
    "schedule.break_minutes_min": (5, 240, 5, {}),
    "schedule.break_minutes_max": (5, 240, 5, {}),
    "schedule.day_off_every": (2, 30, 1, {0: NEVER}),
    # Attacks per day: 0-1000, by 10 (0 to 0: no limit).
    "schedule.daily_attacks_min": (0, 1000, 10, {}),
    "schedule.daily_attacks_max": (0, 1000, 10, {}),
}

# Amounts that may be empty, and the word for it: the first choice.
EMPTY = {
    **{f"storage.{k}": "Not known" for k in ("gold", "elixir", "dark", "builder_gold", "builder_elixir")},
    **{f"upgrade.start_{k}": "Once full" for k in ("gold", "elixir", "dark")},
    **{f"upgrade.reserve_{k}": "All of it" for k in ("gold", "elixir", "dark")},
}

# How each is written in the settings, as farmbot.form shows it: "int" a number,
# "text" a number as text ("10", "0.5"), "amount" with commas ("300,000").
_TEXT = {"search.min_own_gold_share", "attack.hero_ability_delay", "attack.ability_health",
         "attack.ability_max_wait", "attack.no_loot_seconds", "attack.end_at_loot_share",
         "attack.walk_time", "attack.walk_invisibility_health", "attack.league_bonus_damage",
         "walls.keep_gold", "walls.keep_elixir", "capital.miner_heal_at", "capital.fortress_heal_at",
         "capital.heal_lead", "builder.troop_delay", "builder.switch_below", "builder.hero_ability_after",
         "builder.ability_delay", "builder.stage_delay", "builder.walls_keep_gold", "builder.walls_keep_elixir"}


def _row(table: dict, th: int | None) -> tuple[int, int]:
    return table[min(th or MAX_TOWN_HALL, MAX_TOWN_HALL)]


def bounds(path: str, th: int | None, resource: str = "gold") -> tuple[float, float, float, dict] | None:
    """(lowest, highest, step, {value: word}) for ``path`` at Town Hall ``th`` (None:
    not known yet, the top row, so nothing typed is cut off); None for a path that
    isn't a number. ``resource``: the goal's (gold, elixir or dark)."""
    if path in NUMBERS:
        return NUMBERS[path]
    if path in ("search.min_gold", "search.min_elixir"):
        step, top = _row(BASE_LOOT, th)
    elif path == "search.min_dark":
        step, top = _row(BASE_DARK, th)
    elif path in ("storage.builder_gold", "storage.builder_elixir"):
        step, top = BUILDER_STORAGE
    elif path == "goal.amount":
        step, top = _row(STORAGE_DARK if resource == "dark" else STORAGE, th)
        step, top = step * GOAL_STEP, top * GOAL_TOP
    elif path.startswith(("storage.", "upgrade.start_", "upgrade.reserve_")):
        step, top = _row(STORAGE_DARK if path.endswith("dark") else STORAGE, th)
    else:
        return None
    return 0, top, step, {}


def _written(path: str, number: float):
    if path in NUMBERS:
        return f"{number:g}" if path in _TEXT else int(number)
    if number == 0 and path in EMPTY:
        return ""
    return f"{int(number):,}"


def _shown(number: float, unit: str = "") -> str:
    """1500000 → "1.5m", 300000 → "300k", 2500 → "2,500", 0.5 → "0.5"."""
    if number >= 1_000_000:
        text = f"{number / 1_000_000:.2f}".rstrip("0").rstrip(".") + "m"
    elif number >= 10_000:
        text = f"{number / 1_000:.1f}".rstrip("0").rstrip(".") + "k"
    elif number == int(number):
        text = f"{int(number):,}"
    else:
        text = f"{number:g}"
    return f"{text} {unit}".strip() if unit and unit != "%" else text + (unit if unit == "%" else "")


def scroller(path: str, th: int | None, unit: str = "", resource: str = "gold") -> dict | None:
    """The scroller for ``path``: {"choices": [[value, label, number], ...], "low", "high"},
    choices from lowest to highest, value as the settings write it. An amount that may be empty starts with it
    ("", "Not known", -1); a word for a special value ("0: Never") is that choice's label,
    and a special value outside the range (battery's 0) comes first."""
    found = bounds(path, th, resource)
    if found is None:
        return None
    low, high, step, words = found
    choices = []
    if path in EMPTY:
        choices.append(["", EMPTY[path], -1])
        low = max(low, step)  # 0 is the empty one
    for value, word in sorted(words.items()):
        if value < low:
            choices.append([_written(path, value), word, value])
    count = int(round((high - low) / step))
    for i in range(count + 1):
        number = low + i * step
        number = int(number) if float(number).is_integer() else round(number, 3)
        choices.append([_written(path, number), words.get(number, _shown(number, unit)), number])
    # The stepped range's ends: a saved value outside them is shown at the nearer one.
    return {"choices": choices, "low": low, "high": high}


def _goal_resource(values: dict) -> str:
    return values.get("goal.resource") if values.get("goal.resource") in ("gold", "elixir", "dark") else "gold"


def attach(pages: list, th: int | None) -> None:
    """Give every number and amount in ``pages`` its scroller (for Town Hall ``th``);
    a range ("From"/"To") gets one for each end. The goal gets one per resource."""
    for item in (i for page in pages for group in page["groups"] for i in group["items"]):
        unit = item.get("suffix", "")
        if item["kind"] == "range":
            item["scroll_low"] = scroller(item["low"], th, unit)
            item["scroll_high"] = scroller(item["high"], th, unit)
        elif item["kind"] in ("number", "amount"):
            if item["path"] == "goal.amount":
                item["scroll_by"] = "goal.resource"
                item["scrolls"] = {r: scroller(item["path"], th, unit, r) for r in ("gold", "elixir", "dark")}
            else:
                item["scroll"] = scroller(item["path"], th, unit)
