#!/usr/bin/env python3
"""Prints the notes of a draft GitHub Release: what to check before installing, then SAFETY.md in full.

Usage: release_notes.py <version> <owner/repository> [<SAFETY.md> [<CHANGELOG.md>]]

The legal and safety text (the statements, the firmware caveat, unverified models) comes word for word
from SAFETY.md, which a test keeps identical to the app's own text; its links to files in the repository
point at the release's tag. "What's new" is the version's section of CHANGELOG.md; without one, a
placeholder for the maintainer to replace in the draft before publishing it.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
REPOSITORY = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
# A Markdown link to a file in the repository (not a web address or an anchor).
RELATIVE_LINK = re.compile(r"\]\((?![a-z]+:|#)([^)\s]+)\)")
PLACEHOLDER = "_Write what changed before publishing this draft._"


def changes(version: str, changelog: str) -> str | None:
    """The body of CHANGELOG.md's section `## <version>`, without its heading; None if there is none."""
    lines = [line.rstrip() for line in changelog.splitlines()]
    try:
        start = lines.index(f"## {version}") + 1
    except ValueError:
        return None
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("## ")), len(lines))
    body = "\n".join(lines[start:end]).strip()
    return body or None


def notes(version: str, repository: str, safety: str, changelog: str = "") -> str:
    if not VERSION.fullmatch(version):
        raise ValueError(f"not a version: {version!r}")
    if not REPOSITORY.fullmatch(repository):
        raise ValueError(f"not a repository: {repository!r}")
    files = f"https://github.com/{repository}/blob/v{version}/"
    safety = RELATIVE_LINK.sub(lambda m: f"]({files}{m.group(1)})", safety)
    lines = safety.strip().splitlines()
    if not lines or lines[0] != "# Legal & safety":
        raise ValueError("SAFETY.md doesn't start with its title")
    # One level down, under the release's own title.
    body = "\n".join("#" + line if line.startswith("#") else line for line in lines)
    return "\n".join(
        [
            f"# ENGWE Speed Tool {version}",
            "",
            "Unofficial. Not made by, endorsed by or affiliated with ENGWE.",
            "",
            "**Read the legal and safety text below before installing.** The app asks you to accept it "
            "before it connects to a bike.",
            "",
            "## Download",
            "",
            f"- `engwe-speed-tool-{version}.apk`: check it against `SHA256SUMS`, and check that its signing "
            "certificate's SHA-256 is the one in the README.",
            "",
            "## What's new",
            "",
            changes(version, changelog) or PLACEHOLDER,
            "",
            body,
            "",
        ]
    )


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3, 4):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    safety = Path(argv[2] if len(argv) >= 3 else "SAFETY.md").read_text(encoding="utf-8")
    changelog_file = Path(argv[3] if len(argv) == 4 else "CHANGELOG.md")
    changelog = changelog_file.read_text(encoding="utf-8") if changelog_file.exists() else ""
    try:
        sys.stdout.write(notes(argv[0], argv[1], safety, changelog))
    except ValueError as e:
        print(e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
