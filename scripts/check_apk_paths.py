#!/usr/bin/env python3
"""Fails if an APK holds a path from the machine that built it, or private text.

Usage: check_apk_paths.py <apk> [<apk> ...]

Every file in the APK is searched, as bytes read as UTF-8 and as UTF-16 (resource tables use both), for
home and user directories (`/home/<name>`, `/Users/<name>`, `C:\\Users\\<name>`, `/root/`), WSL paths and
CI workspace paths. Such a path names whoever built the APK, or where; it is reported by its kind, without
the name.

The environment variable APK_PRIVATE_TEXT may hold private text, one item per line (`#` starts a comment
line), for example a maintainer's names from a CI secret. Items are found ignoring case, and items with
digits (addresses, serials) also with separators removed. A find is reported without the text, so the
log doesn't repeat what the secret protects.
"""

from __future__ import annotations

import os
import re
import sys
import zipfile
from pathlib import Path

PATHS = {
    "a Linux home directory (/home/...)": re.compile(r"/home/[A-Za-z0-9._-]+"),
    "a macOS home directory (/Users/...)": re.compile(r"/Users/[A-Za-z0-9._-]+"),
    "a Windows user directory (C:\\Users\\...)": re.compile(r"[A-Za-z]:[\\/]+Users[\\/]+[A-Za-z0-9._ -]+", re.IGNORECASE),
    "root's home directory (/root/)": re.compile(r"/root/"),
    "a WSL path (\\\\wsl...)": re.compile(r"\\\\wsl(?:\.localhost|\$)", re.IGNORECASE),
    "a Windows drive in WSL (/mnt/<drive>/Users)": re.compile(r"/mnt/[a-z]/Users/", re.IGNORECASE),
    "a CI workspace (/runner/work/...)": re.compile(r"/runner/work/"),
}


def texts(data: bytes) -> list[str]:
    """The bytes read as UTF-8 and as UTF-16 (both byte alignments), so no encoding hides a path."""
    return [
        data.decode("utf-8", errors="replace"),
        data.decode("utf-16-le", errors="replace"),
        data[1:].decode("utf-16-le", errors="replace"),
    ]


def private_items(value: str | None) -> list[str]:
    lines = (line.strip() for line in (value or "").splitlines())
    return [line for line in lines if line and not line.startswith("#")]


def squash(text: str) -> str:
    """Letters and digits only: separators can't hide a value."""
    return re.sub(r"[^0-9a-z]", "", text.lower())


def has_private(text: str, items: list[str]) -> bool:
    low = text.lower()
    squashed: str | None = None
    for item in items:
        if item.lower() in low:
            return True
        if any(c.isdigit() for c in item) and len(squash(item)) >= 6:
            squashed = squashed if squashed is not None else squash(text)
            if squash(item) in squashed:
                return True
    return False


def check_entry(name: str, data: bytes, private: list[str]) -> list[str]:
    problems: list[str] = []
    kinds: set[str] = set()
    private_hit = False
    for text in texts(data):
        kinds.update(kind for kind, pattern in PATHS.items() if pattern.search(text))
        if not private_hit and has_private(text, private):
            private_hit = True
    problems += [f"{name}: a build machine's path: {kind}" for kind in sorted(kinds)]
    if private_hit:
        problems.append(f"{name}: holds private text")
    return problems


def check_apk(path: Path, private: list[str]) -> list[str]:
    problems: list[str] = []
    with zipfile.ZipFile(path) as apk:
        for info in apk.infolist():
            if not info.is_dir():
                problems += check_entry(info.filename, apk.read(info), private)
    return problems


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    private = private_items(os.environ.get("APK_PRIVATE_TEXT"))
    failed = False
    for arg in argv:
        problems = check_apk(Path(arg), private)
        for problem in problems:
            print(f"{arg}: {problem}")
        if problems:
            failed = True
        else:
            print(f"{arg}: no build paths" + (f", none of {len(private)} private items" if private else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
