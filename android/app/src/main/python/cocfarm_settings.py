"""COCFARM's settings on Android: what the settings screen shows, read and
saved with the same rules as the Windows window (farmbot.form). The Java
side (SettingsActivity) draws whatever this describes, so a setting is
added here, once.

Each item: {"path", "kind", "title", "help"}, plus per kind:
  amount / number (+ "suffix") / text: a box to type in
  switch: on/off;  speed: 1 (slow) to 9 (fast)
  choice: "options" {value: label};  multi: "options", several can be picked
  ordered: like multi, but the order they're picked in counts (donations)
    (+ "pick_title": the picking dialog's title)
  hours: the 24 hours;  range: two numbers, "low" and "high" paths
  presets: a line of text per "levels" [key, title, what fits] (the armies by Clan Castle level)
and "depends": {"path": ..., "is": value or [values]} greys it out otherwise.
"""

from __future__ import annotations

import json
import os

from farmbot import __version__, catalog
from farmbot.castle import CASTLES
from farmbot.config import RESOURCES, load_config, save_config
from farmbot.config import BUILDER_UPGRADE_ORDER, UPGRADE_ORDER
from farmbot.form import SIDE_LABELS, apply_form, form_values, validate
from farmbot.human import HABITS
from farmbot.tasks.row_names import PICKS
from farmbot.units import SIEGE_MACHINES, SPELLS, TROOPS

SETTINGS_FILE = "farmbot.toml"
MONEY = {"gold": "Gold", "elixir": "Elixir", "both": "Either"}
BUILDERS_FIRST = {"defense": "Defenses", "resource": "Resources", "army": "Army", "none": "Any"}
UPGRADE_KINDS = {"pets": "Pets", "hero": "Heroes", "research": "Research", "building": "Buildings", "walls": "Walls"}
ORDER_HELP = ("Tap them in order: each takes what it can of the full storages before the next. Its switch above "
              "turns one off. Walls (the switch under Village) last get what's left; higher up, they come first.")
# Settings of the Windows window the phone doesn't show, and why.
NOT_ON_PHONE = {
    **{f"device.{name}": "the emulator's" for name in (
        "mode", "bluestacks_instance", "launch_emulator", "enable_adb", "mute", "ldplayer", "ldplayer_index",
        "mumu_index", "serial", "adb_path")},
    "bot.theme": "the window's look (the phone follows its own light or dark)",
    "notify.on_emulator_restart": "there's no emulator to restart",
    "upgrade.wake_up": "Windows' scheduled task opens COCFARM again",
}

# A phone's first farmbot.toml. Not the Windows one (made for an 860 x 672
# emulator): every phone screen is different, so the bot measures the size
# of the game's buttons itself, and everything else starts at the defaults.
PHONE_PROFILE = """\
# COCFARM settings for this phone, written the first time it runs. Change
# them in the app's Settings, or here.

[device]
ui_scale = 0.0  # measured from the game's buttons on this screen
window_scale = 0.0

# Once screenshots are scaled to the buttons' size, the game's corners line
# up as they do on the PC display (measured at 2400 x 1080).
[regions]
own_gold = [-215, 25, 150, 32]  # wide enough for 9 digits
own_elixir = [-215, 78, 150, 32]
own_dark = [-215, 130, 150, 32]  # 360 000 needs the width too
loot_gold = [68, 92, 126, 34]  # clear of the coin (read as a 1) and of a boost's cupcake
loot_elixir = [68, 134, 126, 34]
loot_dark = [68, 175, 126, 34]

[bar]
band = [0, -130, 0, 110]
card_width = 96
# The cards' edges are fainter than the PC's (live at 860 x 672 the spells
# weren't read at 40, and at 2412 x 1080 only 5 of 7 cards).
edge_contrast = 15
# A siege machine's card is 94; the Minion Prince's blue reached 100.
siege_hue = [85, 98]

# Upgrades are on: a full storage (less what's never spent) goes on storages
# and collectors first, then on the dearest upgrade it covers.
[upgrade]
enabled = true
first = "resource"

# Walls take what's above 70% of the storages, once the upgrades are done.
[walls]
enabled = true
"""


# The scouted loot's regions in phone settings written before 16 Nov 2026: their left
# edge took in the coin, read as a leading 1 (847 940 gold read as 1 847 940).
OLD_LOOT = {f"{name} = [63, {y}, 150, 34]": f"{name} = [68, {y}, 126, 34]"
            for name, y in (("loot_gold", 92), ("loot_elixir", 134), ("loot_dark", 175))}
# Phone settings written before 16 Nov 2026 have no edge_contrast (so 40, the PC's)
# and the PC's siege_hue: cards were missed, or a hero read as a siege machine.
FAINT_EDGES = "edge_contrast = 15\nsiege_hue = [85, 98]\n"
# Phone settings written before 17 Nov 2026 have no [upgrade]: upgrades are on by default
# now, so they're turned on (not walls: gold spent on them unasked would be a surprise).
UPGRADES_ON = "\n# Upgrades are on by default (added 17 Nov 2026).\n[upgrade]\nenabled = true\n"


# Settings per account (farmbot/accounts.py, as on Windows): the phone's own switch, not
# one of the form's fields (Windows has no switch for it).
PER_ACCOUNT = "profiles.per_account"
PHONE_ALERTS = "notify.phone"  # the phone's own notifications: Android only, so not in the shared form
# Android-only settings, kept out of the shared form (Windows has no control for them):
# saved and loaded beside it. Numbers: their range.
# Numbers: their range; "amount": 1.5m, 300k...; a set: the values it may take.
GOALS = {"off": "No goal", "gold": "Gold", "elixir": "Elixir", "dark": "Dark elixir"}
# "Suggested amounts" (as on Windows): on, the search minimums are the Town Hall's and
# follow it up (search.minimums_by_hand off); typing an amount turns it off. Not a
# config field of its own: shown and saved as the opposite of minimums_by_hand.
SUGGESTED = "search.suggested"
MINIMUMS = ("search.min_gold", "search.min_elixir", "search.min_dark")
ANDROID_ONLY = {PHONE_ALERTS: None, SUGGESTED: None, "battery.only_while_charging": None,
                "battery.pause_below": (0, 95), "battery.pause_above": (0, 60),
                "goal.resource": frozenset(GOALS), "goal.amount": "amount"}


def _android_shown(path: str, value):
    """An Android-only value as the screen shows it."""
    return f"{value:,}" if ANDROID_ONLY.get(path) == "amount" else value


def load_settings(folder: str):
    """The settings in use: the main file's, or (settings per account) the
    profile of the account played last."""
    from farmbot.accounts import load_active

    path = ensure_settings(folder)
    config = load_config(path)
    return load_active(path) if config.profiles.per_account else config


def ensure_settings(folder: str) -> str:
    """The settings file's path, written for a phone if there isn't one yet
    (one written by an older version gets its old loot regions and the
    troop bar's edge contrast mended, and upgrades turned on if it never
    had them)."""
    path = os.path.join(folder, SETTINGS_FILE)
    if not os.path.exists(path):
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(PHONE_PROFILE)
        return path
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    if not text.startswith("# COCFARM settings for this phone"):
        return path
    mended = text
    for old, new in OLD_LOOT.items():
        mended = mended.replace(old, new)
    if "edge_contrast" not in mended and "\n[bar]\n" in mended:
        mended = mended.replace("\n[bar]\n", "\n[bar]\n" + FAINT_EDGES, 1)
    if "\n[upgrade]" not in mended:
        mended = mended.rstrip("\n") + "\n" + UPGRADES_ON
    if mended != text:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(mended)
    return path


def _item(path, kind, title, help_text="", **extra):
    return {"path": path, "kind": kind, "title": title, "help": help_text, **extra}


def _depends(path, value=True):
    return {"path": path, "is": value}


def _storages(village: str) -> dict:
    """What the storages hold (the keep-shares above are of these): read
    from the game, or typed in."""
    prefix = "builder_" if village == "builder" else ""
    read = f"storage.{prefix}read"
    kinds = {"gold": "Gold", "elixir": "Elixir", **({"dark": "Dark elixir"} if village == "home" else {})}
    return {"title": "Storages", "items": [
        _item(read, "switch", "Read from the game", "Once a day, the first time it's in this village, it taps your "
              "loot at the top right to see what your storages hold. Off: it keeps the amounts below."),
        *(_item(f"storage.{prefix}{kind}", "amount", f"{name} storages hold", "Filled in from the game. Wrong? "
                "Type the right amount and turn Read from the game off, so it's kept. Empty: not known yet.")
          for kind, name in kinds.items()),
    ]}


PAGES = [
    {"title": "Farming", "groups": [
        {"title": "What to farm", "items": [
            _item("bot.mode", "choice", "Village", "Which village the bot plays. Auto switch: one village until its "
                  "storages are full, then the other.",
                  options={"home": "Home village", "builder": "Builder base", "auto": "Auto switch"}),
            _item("bot.auto_first", "choice", "Start with", "Auto switch farms this village first, then the other "
                  "one once the storages below are full. It stops when both are full.",
                  options={"home": "Home village", "builder": "Builder base"}, depends=_depends("bot.mode", "auto")),
            _item("bot.stop_when_full", "multi", "Stop when these storages are full", "It stops once every one you "
                  "picked is full; with Auto switch it moves on to the other village first. None: never stops for "
                  "this.", options={k: k.title() if k != "dark" else "Dark elixir" for k in RESOURCES}),
        ]},
        {"title": "Goal", "items": [
            _item("goal.resource", "choice", "Farm until", "Stops by itself once this run has gained the amount "
                  "below. Home shows how far it's got, and about when it'll get there.", options=GOALS),
            _item("goal.amount", "amount", "This much", "Gained this run, from attacks (and builder battles for "
                  "gold and elixir). Accepts 15m or 800k.", depends=_depends("goal.resource", ["gold", "elixir", "dark"])),
        ]},
        {"title": "Alerts", "items": [
            _item(PHONE_ALERTS, "switch", "On this phone", "As the phone's own notifications. Nothing to set up."),
            _item("notify.discord_webhook", "text", "Also on Discord", "Optional. Server Settings → Integrations → "
                  "Webhooks → New Webhook → Copy Webhook URL, then paste it here."),
            _item("notify.on_storage_full", "switch", "When storage is full"),
            _item("notify.on_stopped", "switch", "When the bot stops", "After a run, or when a problem stops it."),
            _item("notify.daily_summary", "switch", "Daily summary", "Yesterday's attacks and loot, after midnight."),
            _item("notify.on_raids", "switch", "After Clan Capital raids",
                  "The districts raided, each with its % destroyed before and after."),
            _item("notify.progress_every", "number", "Progress update every",
                  "Attacks, loot and the run so far, every so many attacks. 0: none.", suffix="attacks"),
            _item("notify.on_critical_error", "switch", "When a round fails",
                  "An alert when an error makes COCFARM restart the game, or stops the bot."),
            _item("notify.on_deploy_failed", "switch", "When troops won't deploy",
                  "An alert when no troop or hero would go down in a battle, after every retry."),
            _item("notify.alert_cooldown_minutes", "number", "Same alert at most every",
                  "Repeats inside this time aren't sent; the next alert says how many there were.", suffix="min"),
            _item("", "button", "Send a test alert", "Shows one on this phone, and posts one to the webhook if set.",
                  action="test_alert"),
        ]},
        {"title": "Accounts", "items": [
            _item(PER_ACCOUNT, "switch", "Settings per account",
                  "For more than one Clash of Clans account: each gets its own settings, told apart by the "
                  "player name at the top left. An account seen for the first time starts from the settings in "
                  "use. Settings here are for the account the bot played last. Off by default."),
            _item("", "button", "Copy settings between accounts", "One account's settings to another (or to all "
                  "the others). Each keeps its own storage sizes.", action="copy_account_settings",
                  depends=_depends(PER_ACCOUNT)),
        ]},
        {"title": "COCFARM", "items": [
            _item("bot.check_updates", "switch", "Check for updates",
                  "Looks for a new version on GitHub now and then (at most hourly) and offers it on the home screen."),
            _item("", "button", "Check now", "", action="check_update"),
            _item("", "button", "Back up settings", "Saves every setting to a file you pick (Downloads, Drive...). "
                  "Restore puts them back, here or on another phone; this phone's own screen measurements stay.",
                  action="backup_settings"),
            _item("", "button", "Restore settings", "", action="restore_settings"),
            _item("", "button", "Show the welcome tour", "The few steps a new install starts with: what to "
                  "farm, and what COCFARM needs on this phone.", action="welcome_tour_settings"),
        ]},
    ]},
    {"title": "Home village", "groups": [
        {"title": "Bases to attack", "items": [
            _item("search.maintain_only", "switch", "Only look after the village",
                  "On: it only collects, donates and looks after the village, and never attacks."),
            _item(SUGGESTED, "switch", "Suggested amounts", "From your Town Hall, and they follow it as it goes "
                  "up. Typing your own amount turns this off.", depends=_depends("search.maintain_only", False)),
            _item("", "button", "Amounts for every Town Hall", "", action="suggested_table",
                  depends=_depends("search.maintain_only", False)),
            _item("search.min_gold", "amount", "Gold", "Bases offering less gold are skipped. 0 ignores gold. "
                  "Accepts 500k or 1.5m."),
            _item("search.min_elixir", "amount", "Elixir", "Bases offering less elixir are skipped. 0 ignores it."),
            _item("search.min_dark", "amount", "Dark elixir", "Bases offering less dark elixir are skipped. 0 ignores it."),
            _item("search.match_any", "switch", "Any one is enough",
                  "On: attack when any one amount above is met. Off: all of them must be met."),
            _item("search.min_own_gold_share", "number", "Don't search while my gold is below",
                  "A share of what your gold storages hold: each Next costs gold. 0% always searches.", suffix="%"),
            _item("search.sound_on_match", "switch", "Sound on match",
                  "Plays the notification sound when it finds a base worth attacking."),
            _item("search.max_searches", "number", "Give up searching after",
                  "Bases to skip before going back to the village and starting over.", suffix="bases"),
        ]},
        {"title": "Troops", "items": [
            _item("attack.sides", "multi", "Deploy from", "Troops spread over the sides you pick.",
                  options=SIDE_LABELS),
            _item("attack.placement", "choice", "Troops go", "Spread: round the sides above. Toward the loot: in "
                  "from the edge nearest each big heap of loot seen on the base, split between them.",
                  options={"spread": "Spread", "loot": "Toward the loot"}),
            _item("attack.waves", "choice", "Waves", "Split each troop type into this many waves.",
                  options={"1": "1", "2": "2", "3": "3"}),
            _item("attack.troop_speed", "speed", "Troop speed", "How quickly single troops go down."),
            _item("attack.wave_speed", "speed", "Wave speed", "How quickly one wave follows another."),
            _item("redline.enabled", "switch", "Follow the red line",
                  "Deploy along the thin boundary the game draws around a base."),
            _item("zone.enabled", "switch", "Otherwise deploy around the buildings",
                  "Off: use the fixed deploy lines from farmbot.toml."),
        ]},
        {"title": "Spells and siege", "items": [
            _item("attack.spells", "choice", "Spells to cast", "Bat Spells only: other spells stay in the bar.",
                  options={"bat": "Bat Spells only", "all": "All spells"}),
            _item("attack.spell_placement", "choice", "Spells go", "One spot: every spell at once, on one spot. "
                  "Spread: over the base.", options={"together": "One spot", "spread": "Spread"}),
            _item("attack.spell_spot", "choice", "The spot", "Middle: the centre of the base. Random: a new spot "
                  "each battle, within the middle half of the base.", options={"middle": "Middle", "random": "Random"},
                  depends=_depends("attack.spell_placement", "together")),
            _item("attack.use_siege", "switch", "Use siege machines", "Off: siege machines stay in the bar (and the "
                  "Clan Castle troops riding in them with them)."),
            _item("attack.super_troops", "switch", "Activate super troops", "Before a battle, boosts the super "
                  "troops in your army for 25,000 dark elixir each. Never with a Super Potion or gems."),
        ]},
        {"title": "Heroes", "items": [
            _item("attack.hero_abilities", "switch", "Use hero abilities",
                  "Fires each hero's special ability once during the battle."),
            _item("attack.hero_ability_delay", "number", "Ability after", "Seconds after the heroes land.",
                  suffix="s", depends=_depends("attack.hero_abilities")),
            _item("attack.ability_health", "number", "Or when health drops to", "Fires a hero's ability once it's "
                  "this hurt instead. 0% uses the time.", suffix="%", depends=_depends("attack.hero_abilities")),
            _item("attack.ability_max_wait", "number", "At the latest after", "Heroes still unhurt by then fire anyway.",
                  suffix="s", depends=_depends("attack.hero_abilities")),
        ]},
        {"title": "Ending the battle", "items": [
            _item("attack.speed_up", "switch", "Play the last minute at 4x",
                  "Presses the 1x button when it shows, with a minute of the battle left."),
            _item("attack.end_when_no_loot", "switch", "Leave when the loot stops",
                  "Ends the battle early once no more loot is coming in."),
            _item("attack.no_loot_seconds", "number", "No loot for", "Seconds without any loot before leaving.",
                  suffix="s", depends=_depends("attack.end_when_no_loot")),
            _item("attack.end_at_loot_share", "number", "Leave once this much loot is taken",
                  "Share of the base's loot to take before leaving. 0 waits for the rules above.", suffix="%"),
            _item("attack.star_bonus", "switch", "Earn the daily Star Bonus", "Until the day's five stars are lit "
                  "(the strip above Battle), battles are fought for stars and never left early over loot."),
        ]},
        {"title": "Collecting", "items": [
            _item("bot.collect_resources", "switch", "Collect resources",
                  "Empties full collectors between attacks: one tap on a bubble collects them all."),
            _item("bot.collect_gold", "switch", "Gold", "From the gold mines.", depends=_depends("bot.collect_resources")),
            _item("bot.collect_elixir", "switch", "Elixir", "From the elixir collectors.",
                  depends=_depends("bot.collect_resources")),
            _item("bot.collect_dark", "switch", "Dark elixir", "From the dark elixir drills.",
                  depends=_depends("bot.collect_resources")),
            _item("bot.collect_ice", "switch", "Ice", "From the ice collectors.",
                  depends=_depends("bot.collect_resources")),
            _item("bot.collect_every", "number", "Every", "1 = every round. Always the first round after Start.",
                  suffix="rounds", depends=_depends("bot.collect_resources")),
            _item("bot.collect_loot_cart", "switch", "Collect the loot cart",
                  "Empties the loot cart when it shows \"Collect\"."),
            _item("bot.use_snacks", "switch", "Use snacks and potions", "When the box left of Build shows a "
                  "number: uses every item marked Use (green). Ones already working or not usable yet are left."),
            _item("bot.clear_gravestones", "switch", "Clear gravestones",
                  "After someone attacks you, taps a gravestone once: that clears them all."),
            _item("bot.collect_treasury", "switch", "Collect the Clan Castle treasury", "Every few hours, unless "
                  "the storages are full. Loot in the treasury is protected; in the storages it can be raided."),
        ]},
        {"title": "Walls", "items": [
            _item("walls.enabled", "switch", "Upgrade walls with spare loot",
                  "Between attacks it upgrades as many walls as the spare loot covers, in one go. On for a new "
                  "phone: only what's above the shares kept below."),
            _item("walls.use", "choice", "Pay with", "Which resource may go on walls.", options=MONEY,
                  depends=_depends("walls.enabled")),
            _item("walls.keep_gold", "number", "Keep this much gold",
                  "A share of what your gold storages hold; walls only get what's above it.", suffix="%",
                  depends=_depends("walls.enabled")),
            _item("walls.keep_elixir", "number", "Keep this much elixir",
                  "A share of what your elixir storages hold; walls only get what's above it.", suffix="%",
                  depends=_depends("walls.enabled")),
            _item("walls.only_when_full", "switch", "Only when storage is full",
                  "Spends gold or elixir on walls only once its storages are full.", depends=_depends("walls.enabled")),
        ]},
        {"title": "Upgrades", "items": [
            _item("upgrade.enabled", "switch", "Upgrade when storage is full",
                  "Spends a full storage's resource on the dearest upgrades it covers, while builders are free. "
                  "On by default, never spending gems. Runs before walls, keeping one builder for them."),
            _item("upgrade.first", "choice", "Builders go to first", "That kind of building is upgraded first, then "
                  "the others; Any: the dearest of anything.", options=BUILDERS_FIRST,
                  depends=_depends("upgrade.enabled")),
            _item("upgrade.buildings", "switch", "Buildings", "With the free builders.",
                  depends=_depends("upgrade.enabled")),
            _item("upgrade.lab", "switch", "Research", "In the laboratory, when it's free (elixir or dark elixir).",
                  depends=_depends("upgrade.enabled")),
            _item("upgrade.pets", "switch", "Pets", "With the dark elixir, when the pet house is free.",
                  depends=_depends("upgrade.enabled")),
            _item("upgrade.heroes", "switch", "Heroes", "With elixir or dark elixir, while a builder is free.",
                  depends=_depends("upgrade.enabled")),
            _item("upgrade.order", "ordered", "What to upgrade first", ORDER_HELP, options=UPGRADE_KINDS,
                  pick_title="Tap them, first to last", depends=_depends("upgrade.enabled")),
            *(_item(f"upgrade.start_{kind}", "amount", f"Start with {name.lower()} at", "Upgrades may start "
                    "once you have this much, before the storages are full. Empty: once full.",
                    depends=_depends("upgrade.enabled"))
              for kind, name in (("gold", "Gold"), ("elixir", "Elixir"), ("dark", "Dark elixir"))),
            *(_item(f"upgrade.reserve_{kind}", "amount", f"Never spend {name.lower()} below", "Upgrades only get "
                    "what's above this amount. Empty: all of it.", depends=_depends("upgrade.enabled"))
              for kind, name in (("gold", "Gold"), ("elixir", "Elixir"), ("dark", "Dark elixir"))),
            _item("upgrade.only", "ordered", "Only these buildings", "Pick buildings in order: the first gets a "
                  "builder first, and every other building is passed over. None picked: any building.",
                  options={name: name for name in PICKS["buildings"]}, pick_title="Tap them, first to last",
                  depends=_depends("upgrade.buildings")),
            _item("upgrade.research_only", "ordered", "Only this research", "Pick troops, spells and siege "
                  "machines in order: the laboratory researches the first it can pay for, and nothing else. None "
                  "picked: any.", options={name: name for name in PICKS["research"]},
                  pick_title="Tap them, first to last", depends=_depends("upgrade.lab")),
            _item("bot.read_village", "switch", "Read the village data once a day", "Profile → Settings → More "
                  "Settings → Data Export → Copy: the game's own list of your Town Hall and buildings. What your Town "
                  "Hall hasn't unlocked yet is skipped. Whatever you had copied on the phone is put back."),
            _item("upgrade.new_buildings", "switch", "Build new buildings", "Buildings your Town Hall has "
                  "unlocked but you haven't built yet come first, put down where the game places them.",
                  depends=_depends("upgrade.enabled")),
            _item("upgrade.town_hall", "switch", "Upgrade the Town Hall", "First among the buildings, once gold "
                  "is full.", depends=_depends("upgrade.enabled")),
            _item("upgrade.wait_for_town_hall", "switch", "Rest while the Town Hall upgrades", "Once gold and "
                  "elixir are full and nothing is left to upgrade, the game is closed until the Town Hall is "
                  "done. It looks in every 2 hours.", depends=_depends("upgrade.enabled")),
            _item("upgrade.apprentice", "switch", "Put the Builder's Apprentice to work", "When he's idle, on "
                  "the upgrade with the most time left. Nothing is paid.", depends=_depends("upgrade.enabled")),
            _item("upgrade.lab_assistant", "switch", "Put the Lab Assistant to work", "When he's idle, on the "
                  "research with the most time left. Nothing is paid.", depends=_depends("upgrade.enabled")),
            _item("upgrade.builders", "number", "Builders to use", "How many builders upgrades may keep busy; the "
                  "rest stay free for walls or for you. 0: all but one.", suffix="builders",
                  depends=_depends("upgrade.enabled")),
        ]},
        _storages("home"),
        {"title": "Clan", "items": [
            _item("clan.donate", "switch", "Donate to requests", "Fills every open request in clan chat. Each unit "
                  "costs the elixir or dark elixir shown; it never pays with gems."),
            _item("clan.donate_main", "ordered", "Main troops", "Pick the ones to give first, in order: they go "
                  "first, as many as fit.", options={u.name: u.name for u in TROOPS}),
            _item("clan.donate_siege", "ordered", "Siege machines", "In order. A request has room for one.",
                  options={u.name: u.name for u in SIEGE_MACHINES}),
            _item("clan.donate_spells", "ordered", "Main spells", "The spells to give, in order.",
                  options={u.name: u.name for u in SPELLS}),
            _item("clan.donate_presets", "presets", "Armies by Clan Castle level", "With no main troops picked, "
                  "a request gets the army set here for the requester's Clan Castle level, split by commas, first "
                  "first: a name alone gives as many as fit, \"1 Super Yeti\" one at most.",
                  levels=[[str(c.level), f"Level {c.level}", f"{c.troops} troops · {c.spells} spells · {c.siege} "
                           f"siege · lab cap {c.lab_cap}"] for c in CASTLES]),
            _item("clan.donate_fill", "switch", "Fill the rest", "After the main troops, fills the space left with "
                  "the biggest troops that still fit.", depends=_depends("clan.donate")),
            _item("clan.request_troops", "switch", "Request Clan Castle troops",
                  "Asks the clan for troops, then again as soon as the game lets you."),
            _item("clan.request_every_minutes", "number", "Request again after",
                  "Minutes between requests when the Request button's timer can't be read.", suffix="min",
                  depends=_depends("clan.request_troops")),
        ]},
        {"title": "Ranked battles and clan wars", "items": [
            _item("ranked.enabled", "switch", "Make the day's ranked attacks", "Before farming, each ranked attack "
                  "you have left is fought for stars with the army trained. Sign up for the tournament in the game."),
            _item("war.enabled", "switch", "Make your war attacks", "As soon as battle day starts, each war attack "
                  "you have left is fought for stars with the army trained."),
            _item("war.target", "choice", "Which base", "Recommended: the base the game marks for you, then the "
                  "nearest to it. Bottom: from the bottom of the war map up. Most stars: where an attack likely "
                  "adds the most.", options={"mirror": "Recommended", "bottom": "Bottom", "gain": "Most stars"},
                  depends=_depends("war.enabled")),
            _item("war.max_stars", "choice", "Attack bases with at most", "0: only bases nobody took a star from "
                  "yet. 3: any base.", options={str(n): f"{n} ★" for n in range(4)},
                  depends=_depends("war.enabled")),
        ]},
        {"title": "Clan Capital raids", "items": [
            _item("capital.raid", "switch", "Raid the Clan Capital on Raid Weekends", "Friday to Monday: every "
                  "raid attack, with the capital army set in the game. Never spends Capital Gold."),
            _item("capital.district_order", "choice", "Which district first", "Most destroyed: the open district "
                  "nearest to falling (finishing one earns a bonus attack). Most stars: by stars alone.",
                  options={"destroyed": "Most destroyed", "stars": "Most stars"}, depends=_depends("capital.raid")),
            _item("capital.miner_heal_at", "number", "Heal Super Miners at", "Heals go just ahead of the miners; "
                  "the first sooner once one falls under this.", suffix="%", depends=_depends("capital.raid")),
            _item("capital.fortress_heal_at", "number", "Heal Flying Fortresses at",
                  "A heal goes on the fortresses once one falls under this.", suffix="%",
                  depends=_depends("capital.raid")),
            _item("capital.late_heal", "switch", "Late heal for Flying Fortresses", "A heal still unused 45 s in "
                  "goes on the first fortress under 85%, rather than never.", depends=_depends("capital.raid")),
            _item("capital.heal_lead", "number", "Lead the fortresses' heal by", "The heal lands where the "
                  "fortresses are heading this many seconds later. 0: on them.", suffix="s",
                  depends=_depends("capital.raid")),
            _item("capital.spells_near_drop", "switch", "Skeleton spell near the troops", "Off: on the most packed "
                  "buildings anywhere in view.", depends=_depends("capital.raid")),
            _item("capital.lightning_near_drop", "switch", "Lightning near the troops", "Off: on the most packed "
                  "buildings anywhere in view.", depends=_depends("capital.raid")),
        ]},
    ]},
    {"title": "Builder base", "groups": [
        {"title": "Troops", "items": [
            _item("builder.corner", "choice", "Go in from", "Auto picks the map corner farthest from the enemy "
                  "buildings.", options={"auto": "Auto", "left": "Left", "top": "Top", "right": "Right"}),
            _item("builder.drop_distance", "choice", "Drop point", "Far: out at the map's edge, so troops walk "
                  "longer first (witches summon more bats). Close: just off the buildings.",
                  options={"close": "Close", "far": "Far"}),
            _item("builder.use_hero", "switch", "Hero goes first", "Off: troops only."),
            _item("builder.troop_speed", "speed", "Troop speed", "How quickly the troops go down, one after another."),
            _item("builder.troop_delay", "number", "Troops follow after", "Seconds after the hero lands.", suffix="s"),
        ]},
        {"title": "Heroes", "items": [
            _item("builder.first_hero", "choice", "First hero", "The hero for the first base. Keep: whichever is set "
                  "in the game.", options={"any": "Keep", "machine": "Battle Machine", "copter": "Battle Copter"}),
            _item("builder.switch_hero", "switch", "Switch hero when hurt",
                  "Before a base, swaps to your other builder hero if this one's health is low."),
            _item("builder.switch_below", "number", "Switch below", "Hero health that triggers the swap.", suffix="%",
                  depends=_depends("builder.switch_hero")),
            _item("builder.hero_ability", "switch", "Hero ability", "Fired once, when it has charged."),
            _item("builder.hero_ability_after", "number", "Fire it after",
                  "Seconds after the hero lands: how long its ability takes to charge.", suffix="s",
                  depends=_depends("builder.hero_ability")),
        ]},
        {"title": "Abilities and speed", "items": [
            _item("builder.troop_abilities", "switch", "Troop abilities",
                  "Taps the cards of troops still on the field, a few at a time."),
            _item("builder.ability_delay", "number", "Between taps", "Seconds from one round of ability taps to the "
                  "next.", suffix="s", depends=_depends("builder.troop_abilities")),
            _item("builder.ability_batch", "number", "Troop abilities per tap round", "How many troop cards are "
                  "tapped together.", depends=_depends("builder.troop_abilities")),
            _item("builder.stage_delay", "number", "Wait before the second base",
                  "Seconds after the first base falls, while the second loads.", suffix="s"),
            _item("builder.speed_up", "switch", "Play at 4x speed", "Presses the 1x button whenever it shows."),
            _item("builder.return_home_after", "switch", "Sail home when the bot stops",
                  "Ends in the home village rather than the builder base."),
        ]},
        {"title": "Village", "items": [
            _item("builder.collect_resources", "switch", "Collect the mines",
                  "The gold mines, elixir collectors and gem mine, before each builder battle."),
            _item("builder.collect_minutes", "number", "Every", "Time since the last collection. 0 = before each "
                  "battle. Always the first battle after Start.", suffix="min",
                  depends=_depends("builder.collect_resources")),
            _item("builder.collect_cart", "switch", "Collect the elixir cart",
                  "The cart by the dock that holds your defense rewards."),
            _item("", "range", "Every", "A random number of battles in this range each time.",
                  low="builder.cart_every_min", high="builder.cart_every_max", suffix="battles",
                  depends=_depends("builder.collect_cart")),
            _item("builder.walls", "switch", "Upgrade builder base walls with spare loot",
                  "Before each battle it upgrades as many walls as the spare loot covers, in one go. Off by "
                  "default: it spends loot."),
            _item("builder.walls_use", "choice", "Pay with", "Which resource may go on walls.", options=MONEY,
                  depends=_depends("builder.walls")),
            _item("builder.walls_keep_gold", "number", "Keep this much gold",
                  "A share of what your builder gold storages hold; walls only get what's above it.", suffix="%",
                  depends=_depends("builder.walls")),
            _item("builder.walls_keep_elixir", "number", "Keep this much elixir",
                  "A share of what your builder elixir storages hold; walls only get what's above it.", suffix="%",
                  depends=_depends("builder.walls")),
        ]},
        {"title": "Upgrades", "items": [
            _item("builder.upgrade", "switch", "Upgrade when storage is full",
                  "Spends a full gold or elixir storage on the dearest upgrades it covers, while builders are free. "
                  "Off by default: it spends loot. Runs before walls, keeping one builder for them."),
            _item("builder.upgrade_first", "choice", "Builders go to first", "That kind of building is upgraded "
                  "first, then the others; Any: the dearest of anything.", options=BUILDERS_FIRST,
                  depends=_depends("builder.upgrade")),
            _item("builder.upgrade_lab", "switch", "Research", "In the star laboratory, when it's free (elixir).",
                  depends=_depends("builder.upgrade")),
            _item("builder.upgrade_heroes", "switch", "Heroes", "With elixir, while a builder is free.",
                  depends=_depends("builder.upgrade")),
            _item("builder.upgrade_buildings", "switch", "Buildings", "With the free builders.",
                  depends=_depends("builder.upgrade")),
            _item("builder.upgrade_order", "ordered", "What to upgrade first", ORDER_HELP,
                  options={k: UPGRADE_KINDS[k] for k in BUILDER_UPGRADE_ORDER}, pick_title="Tap them, first to last",
                  depends=_depends("builder.upgrade")),
            _item("builder.upgrade_builders", "number", "Builders to use", "How many of the builder base's builders "
                  "upgrades may keep busy; the rest stay free for walls or for you. 0: all but one.",
                  suffix="builders", depends=_depends("builder.upgrade")),
        ]},
        _storages("builder"),
    ]},
    {"title": "When to play", "groups": [
        {"title": "Battery and heat", "items": [
            _item("battery.only_while_charging", "switch", "Only while charging",
                  "Pauses when the phone is unplugged, and carries on when it's plugged in again."),
            _item("battery.pause_below", "number", "Pause when the battery is below",
                  "Carries on 5 points higher. 0: never.", suffix="%"),
            _item("battery.pause_above", "number", "Pause when the phone is hotter than",
                  "The battery's temperature; carries on 5° cooler. 0: never.", suffix="°C"),
        ]},
        {"title": "Playing hours", "items": [
            _item("schedule.enabled", "switch", "Only play at certain hours",
                  "Off: plays whenever it's running. On: only in the hours you pick (this device's time)."),
            _item("schedule.active_hours", "hours", "Hours", "", depends=_depends("schedule.enabled")),
        ]},
        {"title": "Breaks", "items": [
            _item("schedule.breaks", "switch", "Take breaks", "Rests now and then, at random, to play more like a person."),
            _item("", "range", "Every", "A new random number of attacks after each break.",
                  low="schedule.break_every_min", high="schedule.break_every_max", suffix="attacks",
                  depends=_depends("schedule.breaks")),
            _item("", "range", "For", "Minutes, picked at random.", low="schedule.break_minutes_min",
                  high="schedule.break_minutes_max", suffix="min", depends=_depends("schedule.breaks")),
            _item("schedule.day_off_every", "number", "A day off about every",
                  "About one day in this many, it doesn't play at all. 0 = never.", suffix="days"),
        ]},
        {"title": "When to stop", "items": [
            _item("", "range", "Attacks per day", "Picks a number in this range each day, then waits for the next "
                  "day. 0 to 0 = no limit.", low="schedule.daily_attacks_min", high="schedule.daily_attacks_max",
                  suffix="attacks"),
        ]},
    ]},
    {"title": "Like a person", "groups": [
        # (Its tile's line: its one switch's help is too long for a tile's two lines.)
        {"title": "Like a person", "summary": "Every habit below, on or off at once", "items": [
            _item("human.enabled", "switch", "Play like a person", "Off: every habit below is off, and it plays "
                  "the same way every time, as fast as it can."),
        ]},
        *({"title": title, "items": [
            _item(f"human.{h.key}", "switch", h.title, h.text, depends=_depends("human.enabled"))
            for h in HABITS if h.group == group]}
          for group, title in (("touch", "Touch"), ("village", "In the village"), ("battle", "In battle"))),
    ]},
]


def _paths() -> list[str]:
    out = []
    for page in PAGES:
        for group in page["groups"]:
            for item in group["items"]:
                if item["kind"] == "range":
                    out += [item["low"], item["high"]]
                elif item["kind"] != "button":  # a button does something; it has no value
                    out.append(item["path"])
    return out


def _json(value):
    return value if isinstance(value, (bool, int, float, str, list, dict, type(None))) else str(value)


def _ordered(pages: list) -> list:
    """Options as [value, label] pairs: the screen keeps their order (a JSON
    object's keys may come out in any order)."""
    return [{**page, "groups": [{**group, "items": [
        {**item, "options": list(item["options"].items())} if "options" in item else item
        for item in group["items"]]} for group in page["groups"]]} for page in pages]


def info(folder: str) -> str:
    """For the home screen: COCFARM's version, and the button pictures still missing."""
    from farmbot.vision import TemplateLibrary

    cfg = load_config(ensure_settings(folder))
    library = TemplateLibrary(os.path.join(folder, cfg.bot.templates))
    missing = [] if cfg.search.maintain_only else catalog.missing(library, required_only=True)
    return json.dumps({"version": __version__, "missing": [p.title for p in missing]})


UPDATE_ASSET = "COCFARM-android.apk"  # the Android app, attached to COCFARM's GitHub releases
# The app for one processor only (about half the size), by what platform.machine() says.
ABI_ASSETS = {"aarch64": "COCFARM-android-arm64.apk", "arm64": "COCFARM-android-arm64.apk",
              "x86_64": "COCFARM-android-x86_64.apk"}


def update_assets(machine: str | None = None) -> tuple[str, ...]:
    """What to download to update, first choice first: the app for this
    phone's processor, then the one for every phone (all a release from
    before 17 Nov 2026 has)."""
    import platform

    own = ABI_ASSETS.get((machine if machine is not None else platform.machine()).lower())
    return (own, UPDATE_ASSET) if own else (UPDATE_ASSET,)
UPDATE_DIR = "_update"
# The Android app's releases live in a repository of their own; the Windows
# app's (BilinP/COCFARM, farmbot's default) never have an app for phones, and
# every Windows COCFARM takes that repository's latest release as its update.
UPDATE_REPO = "BilinP/COCFARM-Android"
WINDOWS_REPO = "BilinP/COCFARM"


def update_repo(cfg) -> str:
    """Where to look for a newer app: this app's repository, unless the
    settings name another one (a fork's)."""
    repo = (cfg.bot.update_repo or "").strip()
    return UPDATE_REPO if repo in ("", WINDOWS_REPO) else repo


def history_summary(folder: str) -> str:
    """The attack history for the History screen: totals for today, the last
    7 days and all time, gains per hour, the last 14 days, and recent attacks."""
    from datetime import date, timedelta

    from farmbot import history
    from farmbot.tasks.search import Loot

    cfg = load_config(ensure_settings(folder))
    rows = history.read_all(os.path.join(folder, cfg.bot.history)) if cfg.bot.history else []
    days = history.daily_totals(rows)
    today = date.today()

    def total(since):
        count, loot = 0, Loot(0, 0, 0)
        for day, (n, gained) in days.items():
            if since is None or day >= since:
                count, loot = count + n, history.add(loot, gained)
        return {"attacks": count, "gained": [loot.gold, loot.elixir, loot.dark]}

    hourly = history.per_hour([r for r in rows if r.time.date() >= today - timedelta(days=6)])
    chart = []
    for back in range(13, -1, -1):
        day = today - timedelta(days=back)
        n, gained = days.get(day, (0, Loot(0, 0, 0)))
        chart.append({"day": day.isoformat(), "attacks": n, "gained": [gained.gold, gained.elixir, gained.dark]})
    recent = [{"time": r.time.isoformat(timespec="minutes"), "target": [r.target.gold, r.target.elixir, r.target.dark],
               "gained": [r.gained.gold, r.gained.elixir, r.gained.dark] if r.gained else None}
              for r in reversed(rows[-100:])]
    return json.dumps({"today": total(today), "week": total(today - timedelta(days=6)), "all": total(None),
                       "hour": [hourly.gold, hourly.elixir, hourly.dark] if hourly else None,
                       "days": chart, "recent": recent})


def village_summary(folder: str) -> str:
    """The village data the bot read (the game's Data Export), for the Your
    village screen: the Town Hall, the day it was read, and what's in it."""
    from farmbot import townhall

    active = load_settings(folder).profiles.active
    if active:  # settings per account: each account's own, beside its profile
        from farmbot.accounts import Profiles

        kept = townhall.load_export(str(Profiles.beside(ensure_settings(folder)).village_path(active)))
    else:
        kept = townhall.load_export(os.path.join(folder, townhall.VILLAGE_FILE))
    if kept is None:
        return json.dumps({"town_hall": None, "read_on": "", "sections": [],
                           "note": "Not read yet. With \"Read the village data once a day\" on, the bot reads it "
                                   "in the game (Settings → More Settings → Data Export) the next time it's in "
                                   "the home village."})
    export, read_on = kept
    village = townhall.parse(json.dumps(export))
    locked = townhall.locked(village)
    sections = [("Not unlocked yet", [(townhall.NAMES[f], why) for f, why in locked.items()])] if locked else []
    sections += list(townhall.overview(export).items())
    return json.dumps({"town_hall": village.town_hall, "read_on": read_on, "note": "",
                       "sections": [{"title": title, "rows": [[name, detail[:1].upper() + detail[1:]]
                                                               for name, detail in rows]}
                                    for title, rows in sections if rows]})


def bug_report(folder: str, phone: str = "") -> str:
    """The log, with player names and other personal details taken out, and
    the settings (which are on and off): as much as a GitHub issue takes,
    for the bug report's "COCFARM report" box (as the Windows app copies).
    ``phone``: the phone's make, model, Android version and screen (lines, from the app)."""
    from pathlib import Path

    from farmbot import logshare

    cfg = load_config(ensure_settings(folder))
    files = logshare.log_files(Path(folder) / "logs" / "farmbot.log")
    sections = ["### Phone", "COCFARM's Android app", *[line for line in phone.splitlines() if line.strip()], "",
                *logshare.settings_section(cfg)]
    text, kept, _total = logshare.report(files=files, lines=None if files else [], sections=sections)
    return json.dumps({"text": text, "lines": kept})


def test_alert(folder: str) -> str:
    """Show a test alert on the phone and post one to the Discord webhook (each
    if turned on): what happened, in a few words."""
    from farmbot.notify import Notifier

    cfg = load_config(ensure_settings(folder)).notify
    said = []
    if cfg.phone:
        try:
            phone_alert("Alerts work", "COCFARM will tell you here when storage is full, when it stops, "
                        "and with a daily summary.", "info")
            said.append("✓ Shown on this phone.")
        except Exception as exc:  # no Java (a test), or notifications blocked
            said.append(f"✗ Not shown on the phone: {exc}")
    if cfg.discord_webhook:
        said.append("✓ Sent to Discord: check the channel." if Notifier(cfg).test()
                    else "✗ Discord didn't take it. Check the webhook.")
    return " ".join(said) or "Turn on alerts on this phone, or paste a Discord webhook."


def phone_alert(title: str, text: str, kind: str) -> None:
    """The phone's own notification (Bridge.alert); also what the bot's Notifier calls."""
    from java import jclass

    jclass("com.cocfarm.app.Bridge").alert(title, text, kind)


def look_at_base(folder: str) -> str:
    """What the bot made of the last base it scouted: lines of text, and the
    picture with what it found drawn on (saved next to it)."""
    import cv2

    from farmbot.inspect import describe
    from farmbot.vision import TemplateLibrary, Vision

    cfg = load_config(ensure_settings(folder))
    screen = cv2.imread(os.path.join(folder, "cache", "base.png"))
    if screen is None:
        return json.dumps({"lines": ["No base yet: start farming, and the last base it scouts shows here."]})
    vision = Vision(TemplateLibrary(os.path.join(folder, cfg.bot.templates)), cfg.bot.threshold)
    lines, overlay = describe(screen, cfg, vision)
    cv2.imwrite(os.path.join(folder, "cache", "seen.jpg"), overlay, [cv2.IMWRITE_JPEG_QUALITY, 85])
    return json.dumps({"lines": lines, "picture": os.path.join(folder, "cache", "seen.jpg")})


def check_update(folder: str, force: bool = False) -> str:
    """Is a newer COCFARM for Android out? (GitHub is asked at most hourly,
    or once a minute when ``force``.)"""
    from pathlib import Path

    from farmbot import update

    cfg = load_config(ensure_settings(folder))
    if not (cfg.bot.check_updates or force):
        return json.dumps({"version": None, "note": "Update checks are off."})
    found = update.check(update_repo(cfg), force=force, state_path=Path(folder) / "update.json",
                         asset=update_assets())
    release = found.release
    if release and not release.download:  # a release for Windows only
        release = None
    return json.dumps({"version": release.version if release else None, "note": found.note,
                       "page": release.url if release else ""})


def download_update(folder: str) -> str:
    """Download the newer app (resuming a part download) and check it: its path."""
    from pathlib import Path

    from farmbot import update

    cfg = load_config(ensure_settings(folder))
    found = update.check(update_repo(cfg), state_path=Path(folder) / "update.json", asset=update_assets())
    if not found.release or not found.release.download:
        raise update.UpdateError("There's no newer version to download.")
    from java import jclass

    bridge = jclass("com.cocfarm.app.Bridge")
    path = update.download(found.release, Path(folder) / UPDATE_DIR, suffix=".apk",
                           progress=lambda done, size: bridge.updateProgress(int(done), int(size)))
    return str(path)


# ------------------------------------------------------------- tile labels

def _number(value) -> int:
    """A value as the screen holds it ("300,000", "1.5m", 15) as a number; 0 if it isn't one."""
    text = str(value or "0").strip().lower().replace(",", "").replace(" ", "")
    scale = {"k": 1_000, "m": 1_000_000}.get(text[-1:], 1)
    try:
        return round(float(text.rstrip("km") or 0) * scale)
    except ValueError:
        return 0


def _short(amount) -> str:
    """300000 → "300k", 1500000 → "1.5m"."""
    n = _number(amount)
    for size, unit in ((1_000_000, "m"), (1_000, "k")):
        if n >= size:
            return f"{n / size:.1f}".rstrip("0").rstrip(".") + unit
    return str(n)


def _label(group: dict, path: str, values: dict) -> str:
    for item in group["items"]:
        if item["path"] == path:
            options = item.get("options", {})
            options = dict(options) if not isinstance(options, dict) else options
            return str(options.get(values.get(path), values.get(path) or ""))
    return ""


def _on(item: dict, values: dict) -> bool:
    """Whether a setting counts as in use: on itself, and what it needs on too."""
    depends = item.get("depends")
    if depends and values.get(depends["path"]) != depends.get("value", True):
        return False
    return bool(values.get(item["path"]))


def _named(pairs, values: dict) -> tuple[str, str]:
    on = [name for path, name in pairs if values.get(path)]
    return (" · ".join(on), "on") if on else ("Off", "off")


def _special_state(title: str, group: dict, values: dict) -> tuple[str, str] | None:
    """A tile whose label says more than on or off: what it's set to."""
    v = values
    if title == "What to farm":
        return _label(group, "bot.mode", v), "info"
    if title == "Goal":
        what, much = v.get("goal.resource"), _number(v.get("goal.amount"))
        if what not in ("gold", "elixir", "dark") or not much:
            return "Off", "off"
        return f"{_short(v.get('goal.amount'))} {GOALS[what].lower()}", "on"
    if title == "Battery and heat":
        low, hot = _number(v.get("battery.pause_below")), _number(v.get("battery.pause_above"))
        parts = (["⚡"] if v.get("battery.only_while_charging") else []) + \
                ([f"{low}%"] if low else []) + ([f"{hot}°C"] if hot else [])
        text = "Charging only" if parts == ["⚡"] else " · ".join(parts).replace("⚡ · ", "⚡ ")
        return (text, "on") if parts else ("Off", "off")
    if title == "Alerts":
        phone, discord = bool(v.get(PHONE_ALERTS)), bool(str(v.get("notify.discord_webhook") or "").strip())
        if not phone and not discord:
            return "Off", "off"
        n = sum(1 for i in group["items"] if i["kind"] == "switch" and i["path"] != PHONE_ALERTS and _on(i, v))
        where = "Phone + Discord" if phone and discord else "Phone" if phone else "Discord"
        return (f"{where} · {n} on", "on") if n else ("Off", "off")
    if title == "COCFARM":
        return ("Updates on", "on") if v.get("bot.check_updates") else ("Updates off", "off")
    if title == "Bases to attack":
        if v.get("search.maintain_only"):
            return "No attacks", "off"
        dark = f" / {_short(v.get('search.min_dark'))}" if _number(v.get("search.min_dark")) else ""
        # (No "Min": on a narrow tile it left no room beside it for the name.)
        return f"{_short(v.get('search.min_gold'))} / {_short(v.get('search.min_elixir'))}{dark}", "info"
    paths = {i["path"] for i in group["items"]}
    if "builder.corner" in paths:
        corner = _label(group, "builder.corner", v)
        return ("Auto corner" if v.get("builder.corner") == "auto" else f"From the {corner.lower()}"), "info"
    if "attack.sides" in paths:
        n = len(v.get("attack.sides") or [])
        return f"{n} side{'s' * (n != 1)}", "info"
    if title == "Spells and siege":
        return _label(group, "attack.spells", v) or "Off", "info"
    if title == "Storages":
        read = any(v.get(i["path"]) for i in group["items"] if i["kind"] == "switch")
        return ("From game", "on") if read else ("Typed in", "info")
    if title == "Clan":
        return _named((("clan.donate", "Donate"), ("clan.request_troops", "Request")), v)
    if title == "Ranked battles and clan wars":
        return _named((("ranked.enabled", "Ranked"), ("war.enabled", "Wars")), v)
    if title == "Playing hours":
        if not v.get("schedule.enabled"):
            return "Any time", "info"
        n = len(v.get("schedule.active_hours") or [])
        return f"{n} h a day", "on"
    if title == "When to stop":
        low, high = _number(v.get("schedule.daily_attacks_min")), _number(v.get("schedule.daily_attacks_max"))
        if not high:
            return "No limit", "info"
        return (f"{low}-{high} a day" if low != high else f"{high} a day"), "on"
    return None


def group_state(group: dict, values: dict) -> tuple[str, str]:
    """The label at the top right of a settings tile, and its tone ("on",
    "off" or "info"): On/Off for a group turned on and off as a whole, how
    many of its switches are on, or what it's set to."""
    special = _special_state(group["title"], group, values)
    if special is not None:
        return special
    items = group["items"]
    first = items[0] if items else None
    if first and first["kind"] == "switch" and "depends" not in first:
        needing = sum(1 for i in items[1:] if (i.get("depends") or {}).get("path") == first["path"])
        if len(items) == 1 or needing * 2 > len(items) - 1:
            return ("On", "on") if values.get(first["path"]) else ("Off", "off")
    switches = [i for i in items if i["kind"] == "switch"]
    if switches:
        n = sum(1 for i in switches if _on(i, values))
        if not n:
            return "Off", "off"
        return ("All on" if n == len(switches) else f"{n} of {len(switches)} on"), "on"
    choice = next((i for i in items if i["kind"] == "choice"), None)
    if choice:
        return _label(group, choice["path"], values), "info"
    return "", "info"


def _get(config, path: str):
    if path == SUGGESTED:
        return _suggested_now(config)
    section, name = path.split(".")
    return getattr(getattr(config, section), name)


def _suggested_now(config) -> bool:
    """Whether the bot still sets the amounts from the Town Hall: not marked as
    typed, and still amounts it set (a default or a Town Hall's). Older
    settings had no mark, so amounts typed back then count as typed, as the bot
    sees them (config.search_minimums_for leaves them alone)."""
    from farmbot.config import MAX_TOWN_HALL, search_minimums_for

    search = config.search
    if search.minimums_by_hand:
        return False
    current = {"gold": search.min_gold, "elixir": search.min_elixir, "dark": search.min_dark}
    # Amounts it set (also from the storages, before the Town Hall was read; with or without
    # dark, which the bot leaves out before dark elixir is unlocked) are replaced for some
    # Town Hall; typed ones never are.
    storage = config.storage.of("home")
    no_dark = {k: v for k, v in storage.items() if k != "dark"}
    return any(search_minimums_for(sizes, current, th)
               for sizes in (storage, no_dark) for th in (1, MAX_TOWN_HALL))


def _set(config, path: str, value) -> None:
    if path == SUGGESTED:
        config.search.minimums_by_hand = not value
        return
    section, name = path.split(".")
    setattr(getattr(config, section), name, value)


def town_hall(folder: str) -> int | None:
    """The Town Hall in the village data the bot read (this account's), or None."""
    from farmbot import townhall

    active = load_settings(folder).profiles.active
    if active:
        from farmbot.accounts import Profiles

        kept = townhall.load_export(str(Profiles.beside(ensure_settings(folder)).village_path(active)))
    else:
        kept = townhall.load_export(os.path.join(folder, townhall.VILLAGE_FILE))
    if kept is None:
        return None
    try:
        return townhall.parse(json.dumps(kept[0])).town_hall or None
    except (ValueError, KeyError, TypeError):
        return None


def suggested_amounts(th: int | None) -> dict:
    """The search minimums "Suggested amounts" sets, as the screen shows them."""
    from farmbot.config import SEARCH_DEFAULTS, town_hall_minimums

    amounts = town_hall_minimums(th) if th else SEARCH_DEFAULTS
    return {f"search.min_{k}": f"{v:,}" for k, v in amounts.items()}


def _suggested_help(th: int | None) -> str:
    amounts = suggested_amounts(th)
    if th is None:
        where = (f"Your Town Hall isn't known yet (the bot reads it from the game): {amounts['search.min_gold']} "
                 "gold and elixir. ")
    else:
        where = f"Town Hall {th}: {amounts['search.min_gold']} gold and {amounts['search.min_elixir']} elixir. "
    return where + "They follow your Town Hall as it goes up. Typing your own amount turns this off."


def suggested_table(folder: str) -> str:
    """ "Amounts for every Town Hall": what a base must offer, by Town Hall, yours marked."""
    from farmbot.config import MAX_TOWN_HALL, town_hall_minimums

    th = town_hall(folder)
    lines = ["What a base must offer, by Town Hall: about 1 base in 4 does, from the bases COCFARM's "
             "farms searched. Dark elixir stays 0.", ""]
    for n in range(1, MAX_TOWN_HALL + 1):
        amount = town_hall_minimums(n)["gold"]
        lines.append(f"{'▶ ' if n == th else ''}Town Hall {n}: {amount:,} gold and elixir"
                     + ("  (yours)" if n == th else ""))
    return "\n".join(lines)


def _android_only(form: dict) -> tuple[dict, dict]:
    """The Android-only values taken out of ``form``, checked: (values, {path: mistake})."""
    values, errors = {}, {}
    for path, limits in ANDROID_ONLY.items():
        if path not in form:
            continue
        raw = form.pop(path)
        if limits is None:
            values[path] = bool(raw)
            continue
        if limits == "amount":
            from farmbot.form import parse_amount

            try:
                values[path] = parse_amount(raw)
            except ValueError as exc:
                errors[path] = str(exc)[:1].upper() + str(exc)[1:] + "."
            continue
        if isinstance(limits, frozenset):
            if raw in limits:
                values[path] = raw
            else:
                errors[path] = "Pick one of the choices."
            continue
        try:
            number = int(str(raw).strip() or 0)
        except ValueError:
            errors[path] = "A whole number, please."
            continue
        if not limits[0] <= number <= limits[1]:
            errors[path] = f"From {limits[0]} to {limits[1]}."
            continue
        values[path] = number
    return values, errors


def group_states(values_json: str) -> str:
    """Every tile's label, as {"page/group": [text, tone, changed]}, for the
    values on screen; ``changed``: a setting in it isn't COCFARM's default."""
    values = json.loads(values_json)
    changed = changed_paths(values)
    return json.dumps({f"{page['title']}/{group['title']}": [
        *group_state(group, values), any(p in changed for p in _group_paths(group))]
        for page in PAGES for group in page["groups"]})


_DEFAULTS: list = []  # the settings a new phone starts with (PHONE_PROFILE), read once

# One-tap setups at the top of Settings: (name, what it does, the values it sets as the
# screen shows them). Everything else stays as it is. "Gold and elixir" and "Dark elixir"
# get their amounts from the Town Hall (presets()); the screen leaves out a setup that's
# already in place.
PRESETS = [
    ("Gold and elixir", "Farms the home village with your Town Hall's suggested amounts, which follow it up.",
     {"bot.mode": "home", "search.maintain_only": False, SUGGESTED: True, "search.match_any": False}),
    ("Dark elixir", "Farms the home village for dark elixir only: gold and elixir are ignored.",
     {"bot.mode": "home", "search.maintain_only": False, SUGGESTED: False, "search.min_gold": "0",
      "search.min_elixir": "0", "search.match_any": False}),
    ("Builder base", "Plays builder base battles only.", {"bot.mode": "builder", "search.maintain_only": False}),
    ("Both villages", "The home village until gold and elixir are full, then the builder base.",
     {"bot.mode": "auto", "bot.auto_first": "home", "bot.stop_when_full": ["gold", "elixir"],
      "search.maintain_only": False}),
    ("Just look after it", "No attacks: collects, upgrades and does the village jobs, then waits.",
     {"bot.mode": "home", "search.maintain_only": True}),
    ("Play like a person", "Varied taps and timing, and breaks now and then.",
     {"human.enabled": True, "schedule.breaks": True}),
]


# Dark elixir a base should offer for "Dark elixir", by Town Hall (drills come at 7, and
# bases offer more of it as Town Halls go up); 13 and up: DARK_TOP. A judgment call, not
# measured like the gold and elixir amounts.
DARK_BY_TOWN_HALL = {7: 500, 8: 1_000, 9: 1_500, 10: 2_000, 11: 2_500, 12: 3_000}
DARK_TOP = 4_000
DARK_FROM = 7  # no dark elixir below this Town Hall: "Dark elixir" isn't offered
BUILDER_FROM = 4  # the Builder Base opens at Town Hall 4 (the boat): before, no builder setups


def _dark_amount(th: int | None) -> int:
    if th is None:
        return 3_000
    return DARK_BY_TOWN_HALL.get(th, DARK_TOP if th > 12 else DARK_BY_TOWN_HALL[DARK_FROM])


def _fit_presets(th: int | None) -> list:
    """PRESETS for this Town Hall: amounts filled in, and the ones it can't use yet left out."""
    out = []
    for name, about, values in PRESETS:
        values = dict(values)
        if name == "Gold and elixir":
            values.update(suggested_amounts(th))  # (dark elixir: 0)
            amount = values["search.min_gold"]
            about += (f" Town Hall {th}: {amount} gold and elixir." if th else
                      f" Your Town Hall isn't known yet: {amount} gold and elixir until it is.")
        elif name == "Dark elixir":
            if th is not None and th < DARK_FROM:
                continue
            dark = _dark_amount(th)
            values["search.min_dark"] = f"{dark:,}"
            about += f" Bases with at least {dark:,} dark elixir" + (f" (Town Hall {th})." if th else ".")
        elif values.get("bot.mode") in ("builder", "auto") and th is not None and th < BUILDER_FROM:
            continue
        out.append((name, about, values))
    return out


def presets(folder: str | None = None) -> str:
    """The quick setups: [{name, about, values, changes}], ``changes`` naming
    the settings each one sets by group, "Bases to attack: Gold, Elixir" (for
    the question before it's applied). With ``folder``: fitted to the Town
    Hall the bot read there (see _fit_presets)."""
    where = {}
    for page in PAGES:
        for group in page["groups"]:
            for item in group["items"]:
                where.setdefault(item["path"], (group["title"], item["title"]))

    def changes(values):
        by_group: dict = {}
        for p in values:
            group, title = where.get(p, ("", p))
            by_group.setdefault(group, []).append(title)
        return [f"{g}: {', '.join(t)}" if g else ", ".join(t) for g, t in by_group.items()]

    fitted = _fit_presets(town_hall(folder) if folder else None)
    return json.dumps([{"name": name, "about": about, "values": values, "changes": changes(values)}
                       for name, about, values in fitted])


def _defaults():
    if not _DEFAULTS:
        import tempfile

        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, SETTINGS_FILE)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(PHONE_PROFILE)
            _DEFAULTS.append(load_config(path))
    return _DEFAULTS[0]


def _group_paths(group: dict) -> list[str]:
    out = []
    for item in group["items"]:
        if item["kind"] == "range":
            out += [item["low"], item["high"]]
        elif item["kind"] != "button" and item["path"]:
            out.append(item["path"])
    return out


def _same(a, b) -> bool:
    norm = lambda v: list(v) if isinstance(v, tuple) else v
    return norm(a) == norm(b)


def changed_paths(values: dict) -> set[str]:
    """The settings on screen that differ from a new phone's (one that can't
    be read counts as changed)."""
    form = dict(values)
    form.pop(PER_ACCOUNT, None)
    android_only, android_errors = _android_only(form)
    parsed, errors = validate(form)
    default = _defaults()
    out = set(errors) | set(android_errors)
    out |= {p for p, v in {**parsed, **android_only}.items() if not _same(v, _get(default, p))}
    if values.get(PER_ACCOUNT):
        out.add(PER_ACCOUNT)
    return out


def group_defaults(page_title: str, group_title: str) -> str:
    """A group's settings as a new phone has them, as the screen shows them
    ({path: value}): "Reset this group"."""
    default = _defaults()
    shown = {**form_values(default), PER_ACCOUNT: False,
             **{p: _android_shown(p, _get(default, p)) for p in ANDROID_ONLY}}
    for page in PAGES:
        for group in page["groups"]:
            if page["title"] == page_title and group["title"] == group_title:
                return json.dumps({p: _json(shown[p]) for p in _group_paths(group) if p in shown})
    return "{}"


def accounts(folder: str) -> str:
    """The accounts COCFARM has seen (settings per account): {names, active}."""
    from farmbot.accounts import Profiles

    path = ensure_settings(folder)
    return json.dumps({"names": Profiles.beside(path).names(), "active": load_config(path).profiles.active})


def copy_account(folder: str, source: str, targets_json: str) -> str:
    """Give each of ``targets`` (a JSON list) account ``source``'s settings
    (each keeps its own storage sizes): what happened, "✓ ..." or "✗ ...".
    The account being played gets them straight away."""
    from farmbot import accounts as _accounts

    path = ensure_settings(folder)
    profiles = _accounts.Profiles.beside(path)
    names = set(profiles.names())
    targets = [t for t in json.loads(targets_json) if t in names and t != source]
    if source not in names or not targets:
        return "✗ Pick two accounts COCFARM has seen."
    shared = load_config(path)
    try:
        for target in targets:
            profiles.copy(source, target, shared)
    except (OSError, ValueError) as exc:
        return f"✗ Couldn't copy them: {exc}"
    import cocfarm_android

    running = cocfarm_android.running_config
    active = shared.profiles.active
    if running is not None and running.profiles.active in targets:
        _accounts.take(profiles.load(running.profiles.active, load_config(path)), running)
    who = targets[0] if len(targets) == 1 else f"{len(targets)} accounts"
    note = " The screen now shows them." if active in targets else ""
    return f"✓ {source}'s settings copied to {who}.{note}"


BACKUP_KIND = "COCFARM settings"


def backup(folder: str) -> str:
    """Every setting as the screen shows it, as a file's text: "Back up settings".
    Only the settings, not this phone's measurements (screen scale, loot
    regions), so a backup suits another phone too."""
    from datetime import date

    values = json.loads(load(folder))["values"]
    return json.dumps({"kind": BACKUP_KIND, "version": __version__, "saved": date.today().isoformat(),
                       "values": values}, indent=1, ensure_ascii=False) + "\n"


def restore(folder: str, text: str) -> str:
    """Put a backup's settings back (and into a running bot): what happened,
    "✓ ..." or "✗ ...". Settings the backup doesn't have, or this version
    doesn't know, stay as they are; nothing changes if any value is wrong."""
    try:
        data = json.loads(text)
    except ValueError:
        return "✗ That isn't a COCFARM settings backup."
    if not isinstance(data, dict) or data.get("kind") != BACKUP_KIND or not isinstance(data.get("values"), dict):
        return "✗ That isn't a COCFARM settings backup."
    current = json.loads(load(folder))["values"]
    given = {p: v for p, v in data["values"].items() if p in current}
    if not given:
        return "✗ The backup has no settings this version knows."
    errors = json.loads(save(folder, json.dumps({**current, **given})))
    if errors:
        titles = {i["path"]: i["title"] for page in PAGES for g in page["groups"] for i in g["items"]}
        said = "; ".join(f"{titles.get(p, p)}: {m}" for p, m in list(errors.items())[:3])
        return f"✗ Not restored, nothing changed. {said}"
    when = data.get("saved") or "an earlier day"
    changed = sum(1 for p, v in given.items() if current.get(p) != v)
    return f"✓ Restored the backup from {when}: {changed} setting{'s' if changed != 1 else ''} changed."


def load(folder: str) -> str:
    """The settings screen, with this device's current values, as JSON."""
    config = load_settings(folder)
    values = form_values(config)
    values[PER_ACCOUNT] = config.profiles.per_account
    for p in ANDROID_ONLY:
        values[p] = _android_shown(p, _get(config, p))
    pages = _ordered(PAGES)
    th = town_hall(folder)
    if values[SUGGESTED]:  # what the bot sets them to (on Windows, _fit_search_minimums)
        values.update(suggested_amounts(th))
    for item in (i for page in pages for group in page["groups"] for i in group["items"]):
        if item["path"] == PER_ACCOUNT and config.profiles.per_account and config.profiles.active:
            item.update(help=item["help"] + f" Now: {config.profiles.active}.")
        if item["path"] == SUGGESTED:
            # Turned on: these go in. Typing in one of them: it turns off (both done on the screen).
            item.update(help=_suggested_help(th), fills=suggested_amounts(th), off_when_typed=list(MINIMUMS))
    return json.dumps({"pages": pages, "values": {p: _json(values[p]) for p in _paths()}})


def save(folder: str, values_json: str) -> str:
    """Check the values the screen sends; save them if they're all right.
    Returns {path: message} for each mistake (empty once saved). A running
    bot gets the new values too."""
    form = json.loads(values_json)
    # The picker drops a kind that isn't tapped; it goes back at the end, as in an older file.
    for path, kinds in (("upgrade.order", UPGRADE_ORDER), ("builder.upgrade_order", BUILDER_UPGRADE_ORDER)):
        if isinstance(form.get(path), list):
            given = [k for k in dict.fromkeys(form[path]) if k in kinds]
            form[path] = given + [k for k in kinds if k not in given]
    per_account = form.pop(PER_ACCOUNT, None)
    android_only, android_errors = _android_only(form)
    _, errors = validate(form)
    errors.update(android_errors)
    if errors:
        return json.dumps(errors)
    from farmbot.accounts import save_active

    path = ensure_settings(folder)
    config = load_settings(folder)
    apply_form(config, form)
    if per_account is not None:
        config.profiles.per_account = bool(per_account)
    for p, value in android_only.items():
        _set(config, p, value)
    if not config.profiles.per_account and config.profiles.active:
        # Turned off: the account in use's settings become the phone's own again.
        config.profiles.active = ""
    save_active(config, path)
    import cocfarm_android

    running = cocfarm_android.running_config
    if running is not None:
        apply_form(running, form)
        running.profiles.per_account, running.profiles.active = config.profiles.per_account, config.profiles.active
        for p, value in android_only.items():
            _set(running, p, value)
    return "{}"


# ------------------------------------------------------------------ the welcome tour
# As on Windows (farmbot/onboarding.py decides who gets it): only a new install, never an
# update, and until it's finished or skipped.


def tour_due(folder: str) -> bool:
    """At the app's start, before anything writes the settings file: True when
    the welcome tour should show (a new install that hasn't finished it)."""
    from farmbot import onboarding

    return onboarding.start_if_new(os.path.join(folder, SETTINGS_FILE))


def tour_finish(folder: str) -> None:
    """Finished or skipped: the tour doesn't show again."""
    from farmbot import onboarding

    onboarding.finish(os.path.join(folder, SETTINGS_FILE))


def tour_values(folder: str) -> str:
    """The tour's last step, "What to farm": the village, the suggested amounts and
    the three minimums as the screen shows them, and what "Suggested" fills in."""
    values = json.loads(load(folder))["values"]
    th = town_hall(folder)
    return json.dumps({"mode": values.get("bot.mode"), "suggested": values.get(SUGGESTED),
                       "amounts": {p: values.get(p) for p in MINIMUMS}, "fills": suggested_amounts(th),
                       "help": _suggested_help(th)})


def tour_save(folder: str, given_json: str) -> str:
    """Save the tour's choices ({path: value}, as the screen shows them) over the
    current settings: {path: mistake}, empty once saved."""
    current = json.loads(load(folder))["values"]
    given = {p: v for p, v in json.loads(given_json).items() if p in current}
    values = {**current, **given}
    if values.get("bot.mode") == "auto" and not values.get("bot.stop_when_full"):
        values["bot.stop_when_full"] = ["gold", "elixir"]  # as Windows' tour: Auto needs storages to switch on
    return save(folder, json.dumps(values))
