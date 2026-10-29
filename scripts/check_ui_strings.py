#!/usr/bin/env python3
"""Fails if shipped app code holds text that should come from string resources.

Usage: check_ui_strings.py [<repo root>]

User-visible text lives in `res/values*/` so it can be translated and reviewed. This reads the Kotlin of
the app module's shipped source sets (every set except the test ones) and reports every string literal
that contains a letter, outside annotation arguments. Template expressions (`$x`, `${x}`) don't count;
the literal parts around them do. Exceptions are listed in ALLOWED by file and exact value.

Android lint's HardcodedText covers XML layouts; this covers Compose.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

APP_MODULE = "app"

# Reviewed exceptions: file -> literal values that are not user-visible text.
ALLOWED: dict[str, set[str]] = {
    # OpenType feature tag for tabular figures.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/theme/Type.kt": {"tnum"},
    # Preference file and keys, and a debug form that names no values.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/bike/AppStore.kt": {
        "Stored(bike=, named=, acceptance=, ",
        "acknowledged=, permissionRefused=, caveat=, ",
        "keep=/, warningsOff=/, report=, ",
        "keepAssist=/, ",
        "screenOn=, rideTest=/)",
        "firmware_tag",
        "app_store",
        "bike",
        "bike_name",
        "accepted_version",
        "accepted_on",
        "acknowledged_version",
        "log_saved_on",
        "log_saved_number",
        "bluetooth_permission_refused",
        "firmware_caveat_version",
        "keep_speed",
        "kept_kmh",
        "keep_assist",
        "kept_assist_level",
        "warnings_off",
        "warnings_version",
        "report_results",
        "keep_screen_on",
        "lock_explained",
        "show_more_data",
        "ride_test_kmh",
        "ride_test_log",
        "ride_tests_done",
    },
    # "null" for Appendable's contract; the stored logs' private directory.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/bike/BikeServices.kt": {"null", "session-logs"},
    # A MIME type and a file mode, for a log saved in Downloads.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/LogDownloads.kt": {"application/octet-stream", "wt"},
    # MIME types, a URI scheme and file modes.
    # The browser selector's link.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/SpeedToolApp.kt": {
        "application/octet-stream",
        "text/plain",
        "package",
        "wt",
        "r",
        "https://",
    },
}


@dataclass
class Literal:
    line: int
    text: str  # the literal parts, template expressions removed
    in_annotation: bool


class _Scanner:
    def __init__(self, source: str) -> None:
        self.s = source
        self.i = 0
        self.line = 1
        self.literals: list[Literal] = []
        # One entry per open parenthesis: whether it opened an annotation's argument list.
        self.parens: list[bool] = []

    def peek(self, offset: int = 0) -> str:
        j = self.i + offset
        return self.s[j] if j < len(self.s) else ""

    def advance(self, n: int = 1) -> str:
        chunk = self.s[self.i : self.i + n]
        self.line += chunk.count("\n")
        self.i += n
        return chunk

    def scan(self) -> list[Literal]:
        self.code(until_brace=False)
        return self.literals

    def code(self, until_brace: bool) -> None:
        """Reads code up to the end, or up to the `}` that closes a template expression."""
        depth = 0
        while self.i < len(self.s):
            c = self.peek()
            if c == "/" and self.peek(1) == "/":
                while self.i < len(self.s) and self.peek() != "\n":
                    self.advance()
            elif c == "/" and self.peek(1) == "*":
                self.block_comment()
            elif c == "$" and self._dollar_prefix() >= 2:
                n = self._dollar_prefix()
                self.advance(n)
                self.string(dollars=n)
            elif c == '"':
                self.string()
            elif c == "'":
                self.char()
            elif c == "`":
                self.advance()
                while self.i < len(self.s) and self.peek() not in "`\n":
                    self.advance()
                self.advance()
            elif c == "(":
                self.parens.append(self.opens_annotation())
                self.advance()
            elif c == ")":
                if self.parens:
                    self.parens.pop()
                self.advance()
            elif c == "{":
                depth += 1
                self.advance()
            elif c == "}":
                if until_brace and depth == 0:
                    self.advance()
                    return
                depth -= 1
                self.advance()
            else:
                self.advance()

    def opens_annotation(self) -> bool:
        """True if the `(` at the cursor follows `@Name`, `@a.b.Name` or `@target:Name` (spaces allowed before it).

        `this@Label.f(` and `super@Label.f(` are calls: an annotation's `@` never follows an identifier.
        """
        j = self.i - 1
        while j >= 0 and self.s[j] in " \t":
            j -= 1
        j = self._skip_back_name(j, dots=True)
        if j is None:
            return False
        if j >= 0 and self.s[j] == ":":  # use-site target, e.g. @param:StringRes(...) or @file:Suppress(...)
            j = self._skip_back_name(j - 1, dots=False)
            if j is None:
                return False
        if j < 0 or self.s[j] != "@":
            return False
        return j == 0 or not (self.s[j - 1].isalnum() or self.s[j - 1] in "_`")

    def _skip_back_name(self, j: int, dots: bool) -> int | None:
        """Moves back over a (dotted) name ending at index j; None if there is no name there."""
        end = j
        while j >= 0 and (self.s[j].isalnum() or self.s[j] == "_" or (dots and self.s[j] == ".")):
            j -= 1
        return None if j == end else j

    def _dollar_prefix(self) -> int:
        """Number of `$` at the cursor if a string literal follows them (Kotlin's multi-dollar strings)."""
        n = 0
        while self.peek(n) == "$":
            n += 1
        return n if self.peek(n) == '"' else 0

    def block_comment(self) -> None:
        self.advance(2)
        nesting = 1
        while self.i < len(self.s) and nesting:
            if self.peek() == "/" and self.peek(1) == "*":
                nesting += 1
                self.advance(2)
            elif self.peek() == "*" and self.peek(1) == "/":
                nesting -= 1
                self.advance(2)
            else:
                self.advance()

    def char(self) -> None:
        self.advance()
        if self.peek() == "\\":
            self.advance(2)
        while self.i < len(self.s) and self.peek() != "'":
            self.advance()
        self.advance()

    def string(self, dollars: int = 1) -> None:
        start_line = self.line
        in_annotation = any(self.parens)
        raw = self.s.startswith('"""', self.i)
        self.advance(3 if raw else 1)
        parts: list[str] = []
        while self.i < len(self.s):
            c = self.peek()
            if raw and self.s.startswith('"""', self.i):
                # A raw string may end in more quotes; the last three close it.
                while self.s.startswith('""""', self.i):
                    parts.append(self.advance())
                self.advance(3)
                break
            if not raw and c == '"':
                self.advance()
                break
            if not raw and c == "\\":
                escaped = self.advance(2)[1:]
                if escaped == "u":
                    parts.append(chr(int(self.advance(4), 16)))
                else:
                    parts.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b"}.get(escaped, escaped))
                continue
            if self.s.startswith("$" * dollars, self.i) and self.peek(dollars) == "{":
                self.advance(dollars + 1)
                self.code(until_brace=True)
                continue
            if self.s.startswith("$" * dollars, self.i) and (self.peek(dollars).isalpha() or self.peek(dollars) == "_"):
                self.advance(dollars)
                while self.peek().isalnum() or self.peek() == "_":
                    self.advance()
                continue
            parts.append(self.advance())
        self.literals.append(Literal(start_line, "".join(parts), in_annotation))


def literals(source: str) -> list[Literal]:
    return _Scanner(source).scan()


def check_source(rel: str, source: str) -> list[str]:
    allowed = ALLOWED.get(rel, set())
    problems = []
    for lit in literals(source):
        if lit.in_annotation or lit.text in allowed or not any(ch.isalpha() for ch in lit.text):
            continue
        problems.append(f"{rel}:{lit.line}: text belongs in string resources: {lit.text!r}")
    return problems


def is_test_set(name: str) -> bool:
    return name.startswith("test") or name.startswith("androidTest")


def check(root: Path) -> list[str]:
    problems = []
    src = root / APP_MODULE / "src"
    for set_dir in sorted(p for p in src.iterdir() if p.is_dir() and not is_test_set(p.name)):
        for f in sorted((set_dir / "kotlin").rglob("*.kt")) if (set_dir / "kotlin").is_dir() else []:
            rel = f.relative_to(root).as_posix()
            problems += check_source(rel, f.read_text(encoding="utf-8"))
    return problems


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent.parent
    problems = check(root.resolve())
    for p in problems:
        print(p)
    if problems:
        print(f"{len(problems)} literal(s) with text; move them to res/values (see scripts/check_ui_strings.py)")
        return 1
    print("ui strings: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
