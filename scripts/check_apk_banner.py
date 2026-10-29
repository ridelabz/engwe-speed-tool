#!/usr/bin/env python3
"""Fails unless a test APK carries its markings exactly once, in the default configuration.

Usage: check_apk_banner.py <apk> [<apk> ...]   (needs ANDROID_HOME with build-tools)

The markings are the banner strings, the app name, the home-screen name and the launcher icon's
background colour. A translation, any other resource qualifier or a disabled-flag value could otherwise
replace them on some phones.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

EXPECTED = {
    "string/test_build_banner_pr": '"TEST BUILD · PR #%1$d · not reviewed"',
    "string/test_build_banner_local": '"TEST BUILD · local · not reviewed"',
    "string/app_name": '"TEST ENGWE Speed Tool"',
    "string/launcher_name": '"TEST Speed Tool"',
    "color/ic_launcher_background": "#ff9a5b00",
}
RESOURCE = re.compile(r"^( *)resource 0x[0-9a-f]{8} (\w+/\S+)(?: .*)?$")


def indent(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def banner_values(dump: str) -> dict[str, list[list[str]]]:
    """For each banner string: one list of value lines per resource entry with that name."""
    found: dict[str, list[list[str]]] = {name: [] for name in EXPECTED}
    lines = dump.splitlines()
    i = 0
    while i < len(lines):
        m = RESOURCE.match(lines[i])
        if not m or m.group(2) not in found:
            i += 1
            continue
        values = []
        j = i + 1
        while j < len(lines) and (not lines[j].strip() or indent(lines[j]) > len(m.group(1))):
            if lines[j].strip().startswith("("):
                values.append(lines[j].strip())
            j += 1
        found[m.group(2)].append(values)
        i = j
    return found


def check_dump(dump: str) -> list[str]:
    problems = []
    for name, entries in banner_values(dump).items():
        expected = [f"() {EXPECTED[name]}"]
        fold = str.lower if name.startswith("color/") else str  # aapt2 may print hex digits in either case
        if [[fold(v) for v in e] for e in entries] != [[fold(v) for v in expected]]:
            problems.append(f"{name}: expected exactly {expected}, found {entries}")
    return problems


def aapt2() -> Path:
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools"

    def version(p: Path) -> list[int]:
        return [int(x) for x in re.findall(r"\d+", p.name)]

    return max((p for p in tools.iterdir() if p.is_dir()), key=version) / "aapt2"


def main(argv: list[str]) -> int:
    failed = False
    for apk in argv[1:]:
        result = subprocess.run([str(aapt2()), "dump", "resources", apk], check=True, capture_output=True, text=True)
        for problem in check_dump(result.stdout):
            print(f"{apk}: {problem}")
            failed = True
    if not failed:
        print("test-build markings: ok")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
