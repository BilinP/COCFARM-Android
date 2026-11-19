"""Imports every Python module the Android app ships, as the phone would.

Python import errors only show when the app runs, so this runs before the
APK is built (CI, android-apk). It imports each farmbot module copyBot
copies into the app (all of farmbot/ but app/bot-excludes.txt) and the
app's own modules (app/src/main/python), with:

- the farmbot files left out of the app made unimportable, so a shipped
  module importing one of them fails here, not on a phone;
- what Android's Python doesn't have made unimportable too (Qt, winreg,
  the Windows-only parts of the standard library);
- Chaquopy's ``java`` module stubbed (it only exists in the app).

Run it with Python 3.10 (the app's), from anywhere:

    python android/check_imports.py

Exit code 0 if everything imports.
"""

from __future__ import annotations

import fnmatch
import importlib
import importlib.abc
import sys
import traceback
import types
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
FARMBOT = ROOT / "farmbot"
APP_PYTHON = HERE / "app" / "src" / "main" / "python"
EXCLUDES = HERE / "app" / "bot-excludes.txt"

# Not on Android: Qt and its widgets, Windows' registry and console, pywin32.
# (msvcrt and _winapi are left alone: Windows' own subprocess needs them, and on Linux,
# as in CI, they are missing anyway.)
MISSING = ("PySide6", "shiboken6", "qfluentwidgets", "winreg", "winsound",
           "win32api", "win32con", "win32gui", "pywintypes")


def excluded_patterns() -> list[str]:
    lines = EXCLUDES.read_text(encoding="utf-8").splitlines()
    return [ln.strip() for ln in lines if ln.strip() and not ln.lstrip().startswith("#")]


def is_excluded(relative: str, patterns: list[str]) -> bool:
    for pattern in patterns:
        if pattern.startswith("**/"):
            if any(fnmatch.fnmatch(part, pattern[3:].split("/")[0]) for part in relative.split("/")[:-1]):
                return True
        elif fnmatch.fnmatch(relative, pattern):
            return True
    return False


def shipped_modules(patterns: list[str]) -> tuple[list[str], set[str]]:
    """(farmbot modules the app ships, farmbot modules left out)."""
    shipped, left_out = [], set()
    for path in sorted(FARMBOT.rglob("*.py")):
        relative = path.relative_to(FARMBOT).as_posix()
        if "__pycache__" in relative:
            continue
        name = "farmbot." + relative[:-3].replace("/", ".")
        name = name.removesuffix(".__init__")
        (left_out.add(name) if is_excluded(relative, patterns) else shipped.append(name))
    return shipped, left_out


class _Blocker(importlib.abc.MetaPathFinder):
    def __init__(self, blocked: set[str]):
        self.blocked = blocked

    def find_spec(self, fullname, path=None, target=None):
        top = fullname.split(".")[0]
        if fullname in self.blocked or top in MISSING:
            raise ModuleNotFoundError(f"{fullname} isn't in the Android app", name=fullname)
        return None


def _stub_java() -> None:
    java = types.ModuleType("java")

    def jclass(name):
        return type(name.rsplit(".", 1)[-1], (), {})

    java.jclass = jclass
    java.jarray = lambda kind: (lambda values: list(values))
    java.jint = int
    sys.modules["java"] = java


def main() -> int:
    if sys.version_info[:2] != (3, 10):
        print(f"note: the app runs Python 3.10; this is {sys.version.split()[0]}")
    patterns = excluded_patterns()
    shipped, left_out = shipped_modules(patterns)
    for name in MISSING:
        sys.modules.pop(name, None)
    sys.meta_path.insert(0, _Blocker(left_out))
    _stub_java()
    sys.path[:0] = [str(ROOT), str(APP_PYTHON)]
    app_modules = sorted(p.stem for p in APP_PYTHON.glob("*.py"))
    failed = []
    for name in shipped + app_modules:
        try:
            importlib.import_module(name)
        except Exception:
            failed.append(name)
            print(f"FAIL {name}\n{traceback.format_exc(limit=-3)}")
    print(f"{len(shipped) + len(app_modules) - len(failed)} imported, {len(failed)} failed "
          f"({len(left_out)} farmbot modules left out of the app)")
    if not failed:
        failed += check_calls(app_modules) + smoke_settings()
    return 1 if failed else 0


JAVA = HERE / "app" / "src" / "main" / "java"


def check_calls(app_modules: list[str]) -> list[str]:
    """Every Python function the Java side calls (``callAttr("name", ...)``)
    exists in one of the app's modules."""
    import re

    called = set()
    for path in JAVA.rglob("*.java"):
        called |= set(re.findall(r'callAttr\("([A-Za-z_]\w*)"', path.read_text(encoding="utf-8")))
    have = {name for module in app_modules for name in dir(sys.modules[module])}
    missing = sorted(called - have)
    for name in missing:
        print(f"FAIL the Java calls {name}(), which no app module has")
    print(f"{len(called) - len(missing)} of {len(called)} functions the Java calls found")
    return missing


def smoke_settings() -> list[str]:
    """The settings screen's calls, on a new, empty COCFARM folder: they read
    and write farmbot.toml, the history and the pictures as the phone does."""
    import json
    import tempfile

    import cocfarm_settings as settings

    failed = []
    with tempfile.TemporaryDirectory() as folder:
        for name, args in (("load", ()), ("info", ()), ("history_summary", ())):
            try:
                json.loads(str(getattr(settings, name)(folder, *args)))
            except Exception:
                failed.append(f"cocfarm_settings.{name}")
                print(f"FAIL cocfarm_settings.{name}()\n{traceback.format_exc(limit=-4)}")
        if not failed:  # the screen's values saved back unchanged: no mistakes, and saved
            try:
                values = json.loads(settings.load(folder))["values"]
                errors = json.loads(settings.save(folder, json.dumps(values)))
                if errors:
                    raise ValueError(f"its own values were refused: {errors}")
            except Exception:
                failed.append("cocfarm_settings.save")
                print(f"FAIL cocfarm_settings.save()\n{traceback.format_exc(limit=-4)}")
    print(f"settings calls: {4 - len(failed)} of 4 ran")
    return failed


if __name__ == "__main__":
    sys.exit(main())
