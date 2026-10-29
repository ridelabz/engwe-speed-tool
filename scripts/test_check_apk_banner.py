"""Tests for check_apk_banner.py using aapt2-style `dump resources` text (python3 -m unittest)."""

import unittest

from check_apk_banner import check_dump

PR = '() "TEST BUILD · PR #%1$d · not reviewed"'
LOCAL = '() "TEST BUILD · local · not reviewed"'
NAME = '() "TEST ENGWE Speed Tool"'
COLOR = "() #FF9A5B00"


def dump(
    pr_values: list[str], local_values: list[str] | None = None, extra: str = "", name: str = NAME, color: str = COLOR
) -> str:
    local_values = [LOCAL] if local_values is None else local_values
    lines = [
        "Binary APK",
        "Package name=io.github.ridelabz.engwespeedtool.test id=7f",
        "  type color id=03 entryCount=1",
        "    resource 0x7f030000 color/ic_launcher_background",
        f"      {color}",
        "  type string id=0b entryCount=5",
        "    resource 0x7f0b0000 string/app_name",
        f"      {name}",
        "    resource 0x7f0b0001 string/launcher_name",
        '      () "TEST Speed Tool"',
        "    resource 0x7f0b0002 string/test_build_banner_local",
        *[f"      {v}" for v in local_values],
        "    resource 0x7f0b0003 string/test_build_banner_pr",
        *[f"      {v}" for v in pr_values],
        "  type xml id=0c entryCount=1",
        "    resource 0x7f0c0000 xml/data_extraction_rules",
        "      () (file) res/xml/data_extraction_rules.xml type=XML",
    ]
    return "\n".join(lines) + "\n" + extra


class BannerTest(unittest.TestCase):
    def test_default_only(self) -> None:
        self.assertEqual(check_dump(dump([PR])), [])

    def test_flag_disabled_header_without_values(self) -> None:
        self.assertEqual(check_dump(dump([PR, "Flag disabled values:"], [LOCAL, "Flag disabled values:"])), [])

    def test_rejections(self) -> None:
        cases = {
            "locale override": dump([PR, '(de) "Version"']),
            "other qualifier": dump([PR, '(night) ""']),
            "disabled-flag value": dump([PR, "Flag disabled values:", '() "x"']),
            "changed text": dump(['() "Version"']),
            "missing": dump([]).replace("string/test_build_banner_pr", "string/other"),
            "missing value": dump([]),
            "local changed": dump([PR], ['() "x"']),
            "duplicate entry": dump([PR], extra="    resource 0x7f0b0009 string/test_build_banner_pr PUBLIC\n"),
            "release name": dump([PR], name='() "ENGWE Speed Tool (Unofficial)"'),
            "case-only change": dump(['() "test build · PR #%1$d · not reviewed"']),
            "name override": dump([PR], name=NAME + '\n      (v33) "ENGWE Speed Tool (Unofficial)"'),
            "release icon colour": dump([PR], color="() #ff0e5e5a"),
            "icon colour override": dump([PR], color=COLOR + "\n      (night) #ff0e5e5a"),
        }
        for name, text in cases.items():
            with self.subTest(name=name):
                self.assertNotEqual(check_dump(text), [])


if __name__ == "__main__":
    unittest.main()
