"""SAFETY.md holds the app's legal and safety text word for word, with the same text version.

The app's text lives in its protected legal resources; SAFETY.md repeats it for readers of the repository.
Each section is compared exactly and in order: the statements, the firmware caveat, the acknowledgement for
untested models and the unofficial lines. Every legal string is either in SAFETY.md or listed below as one
that isn't (button labels, screen furniture), so a new string fails until it is placed.
"""

from __future__ import annotations

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VALUES = ROOT / "app/src/main/res/values"

# Legal strings that SAFETY.md doesn't repeat: labels, instructions and formats of the app's screens.
NOT_IN_SAFETY = {
    "legal_title",
    "legal_intro",
    "legal_intro_changed",
    "legal_accept",
    "legal_accept_hint",
    "legal_accepted_on",
    "legal_statement_number",
    "legal_caveat_sheet_next",
    "legal_about_copyright",
    "legal_about_third_party",
    "unverified_continue",
    "unverified_continue_and_report",
}


def _android_text(raw: str) -> str:
    """A string resource's text as the app shows it, with no-break spaces as plain spaces."""
    text = re.sub(r"\\u([0-9A-Fa-f]{4})", lambda m: chr(int(m.group(1), 16)), raw)
    text = re.sub(r"\\(['\"@?])", r"\1", text)
    return text.replace("\u00a0", " ").strip()


def _strings(name: str) -> tuple[ET.Element, dict[str, str], dict[str, str]]:
    root = ET.parse(VALUES / name).getroot()
    raw = {e.get("name"): "".join(e.itertext()) for e in root.iter("string")}
    return root, raw, {k: _android_text(v) for k, v in raw.items()}


def _sections(markdown: str) -> dict[str, list[str]]:
    """The paragraphs under each heading (`#` or `##`), wrapped lines joined."""
    sections: dict[str, list[str]] = {}
    heading = ""
    paragraph: list[str] = []

    def end() -> None:
        if paragraph:
            sections.setdefault(heading, []).append(" ".join(paragraph))
            paragraph.clear()

    for line in markdown.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            end()
            heading = stripped.lstrip("#").strip()
            sections.setdefault(heading, [])
        elif not stripped or stripped == ">":
            end()
        else:
            # A list item starts a paragraph of its own.
            if re.match(r"\d+\. ", stripped):
                end()
            paragraph.append(stripped)
    end()
    return sections


class SafetyTextTest(unittest.TestCase):
    def setUp(self) -> None:
        self.safety = (ROOT / "SAFETY.md").read_text(encoding="utf-8")
        self.sections = _sections(self.safety)
        self.legal_root, self.legal_raw, self.legal = _strings("legal.xml")
        _, self.unverified_raw, self.unverified = _strings("legal_unverified.xml")

    def test_the_title_is_the_apps(self) -> None:
        self.assertTrue(self.safety.startswith(f"# {self.legal['legal_screen_title']}\n"))

    def test_statements_word_for_word_and_in_order(self) -> None:
        items = [e.text or "" for e in self.legal_root.find("array[@name='legal_statements']").iter("item")]
        statements = [self.legal[item.removeprefix("@string/")] for item in items]
        numbered = [p for p in self.sections["The statements"] if re.match(r"\d+\. ", p)]
        self.assertEqual([f"{n}. {s}" for n, s in enumerate(statements, start=1)], numbered)
        # After its one-paragraph introduction, the section holds the statements and nothing else.
        self.assertEqual(numbered, self.sections["The statements"][1:])
        everywhere = [line.strip() for line in self.safety.splitlines() if re.match(r"\s*\d+\. ", line)]
        self.assertEqual(numbered, everywhere, "numbered lines only in the statements")

    def test_text_version(self) -> None:
        version = self.legal_root.find("integer[@name='legal_text_version']").text
        self.assertIn(f"(text version {version})", self.safety)

    def test_the_caveat_word_for_word(self) -> None:
        paragraphs = self.sections[self.legal["legal_caveat_title"]]
        self.assertEqual([self.legal["legal_caveat"], self.legal["legal_caveat_sheet_version"]], paragraphs[1:])

    def test_the_acknowledgement_word_for_word(self) -> None:
        quoted = [
            line.strip().removeprefix(">").strip()
            for line in self.safety.splitlines()
            if line.strip().startswith(">") and line.strip() != ">"
        ]
        expected = [f"**{self.unverified['unverified_title']}**"] + [
            self.unverified[name]
            for name in [
                "unverified_untested",
                "unverified_might_be_permanent",
                "unverified_restore",
                "unverified_reports_help",
            ]
        ]
        self.assertEqual(expected, quoted)

    def test_the_unofficial_lines_word_for_word(self) -> None:
        self.assertEqual(
            [self.legal["legal_about_unofficial"], self.legal["legal_about_licence"]],
            self.sections["Unofficial"][1:],
        )

    def test_every_legal_string_is_placed(self) -> None:
        shown = {
            "legal_screen_title",
            "legal_caveat_title",
            "legal_caveat",
            "legal_caveat_sheet_version",
            "legal_about_unofficial",
            "legal_about_licence",
            "unverified_title",
            "unverified_untested",
            "unverified_might_be_permanent",
            "unverified_restore",
            "unverified_reports_help",
        }
        items = {e.text.removeprefix("@string/") for e in self.legal_root.iter("item")}
        names = set(self.legal) | set(self.unverified)
        self.assertEqual(names, shown | items | NOT_IN_SAFETY)

    def test_the_legal_files_hold_only_what_this_reads(self) -> None:
        for name in ["legal.xml", "legal_unverified.xml"]:
            root = ET.parse(VALUES / name).getroot()
            with self.subTest(name=name):
                self.assertTrue({e.tag for e in root} <= {"string", "integer", "array"}, {e.tag for e in root})

    def test_no_escape_is_left_over(self) -> None:
        for name, raw in {**self.legal_raw, **self.unverified_raw}.items():
            with self.subTest(name=name):
                text = _android_text(raw)
                self.assertNotIn("\\", text)
                # Android drops an unescaped ASCII quote: the app's texts use typographic quotes.
                self.assertNotIn('"', text)


if __name__ == "__main__":
    unittest.main()
