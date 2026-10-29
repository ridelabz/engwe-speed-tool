"""Tests for release_notes.py and check_apk_paths.py (python3 -m unittest)."""

import io
import re
import unittest
import zipfile
from pathlib import Path

from check_apk_paths import check_apk, check_entry, private_items
from release_notes import PLACEHOLDER, notes

ROOT = Path(__file__).resolve().parent.parent


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.safety = (ROOT / "SAFETY.md").read_text(encoding="utf-8")

    def test_the_legal_text_and_the_firmware_caveat_word_for_word(self) -> None:
        text = notes("1.2.3", "owner/repo", self.safety)
        self.assertTrue(text.startswith("# ENGWE Speed Tool 1.2.3\n"))
        for line in self.safety.splitlines():
            if line and not line.startswith("#"):
                self.assertIn(re.sub(r"\]\((?![a-z]+:|#)", "](https://github.com/owner/repo/blob/v1.2.3/", line), text)
        self.assertIn("## Tested on one bike and firmware only", self.safety)
        self.assertIn("### Tested on one bike and firmware only", text)
        self.assertIn("engwe-speed-tool-1.2.3.apk", text)
        self.assertIn(PLACEHOLDER, text)

    def test_only_versions_and_the_real_safety_text(self) -> None:
        for bad in ["v1.2.3", "1.2", "1.2.3-test", "1.2.3\n# x"]:
            with self.assertRaises(ValueError):
                notes(bad, "owner/repo", self.safety)
        with self.assertRaises(ValueError):
            notes("1.2.3", "owner/repo", "# Something else\n")
        with self.assertRaises(ValueError):
            notes("1.2.3", "owner/repo/x", self.safety)

    def test_whats_new_comes_from_the_changelog(self) -> None:
        changelog = "# Changelog\n\n## 1.2.3\n\n- New thing.\n\n## 1.2.2\n\n- Old thing.\n"
        text = notes("1.2.3", "owner/repo", self.safety, changelog)
        self.assertIn("## What's new\n\n- New thing.\n\n## Legal & safety", text)
        self.assertNotIn("Old thing", text)
        self.assertNotIn(PLACEHOLDER, text)
        self.assertIn(PLACEHOLDER, notes("1.2.4", "owner/repo", self.safety, changelog), "no section: the placeholder")

    def test_the_repositorys_changelog_has_the_apps_version(self) -> None:
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        version = re.search(r'^ *versionName = "([0-9]+\.[0-9]+\.[0-9]+)"$', gradle, re.M).group(1)
        changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        self.assertNotIn(PLACEHOLDER, notes(version, "owner/repo", self.safety, changelog))

    def test_links_to_repository_files_point_at_the_tag(self) -> None:
        text = notes("1.2.3", "owner/repo", "# Legal & safety\n\nSee [the licence](LICENSE), [web](https://x.org), [here](#a).\n")
        self.assertIn("[the licence](https://github.com/owner/repo/blob/v1.2.3/LICENSE)", text)
        self.assertIn("[web](https://x.org)", text)
        self.assertIn("[here](#a)", text)


def apk(entries: dict[str, bytes]) -> io.BytesIO:
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as z:
        for name, content in entries.items():
            z.writestr(name, content)
    data.seek(0)
    return data


class ApkPathsTest(unittest.TestCase):
    def test_build_machine_paths_in_any_encoding(self) -> None:
        for path in [
            "/home/someone/projects/app",
            "/Users/someone/app",
            "C:\\Users\\someone\\app",
            "c:/users/someone/app",
            "/root/.gradle",
            "\\\\wsl.localhost\\Debian\\home",
            "/mnt/d/Users/rider",
            "/home/runner/work/app/app",
        ]:
            for encoding in ["utf-8", "utf-16-le"]:
                with self.subTest(path=path, encoding=encoding):
                    self.assertTrue(check_entry("classes.dex", b"\x00\x01" + path.encode(encoding), []))
            with self.subTest(path=path, odd=True):
                self.assertTrue(check_entry("resources.arsc", b"\x00" + path.encode("utf-16-le"), []))

    def test_ordinary_content_passes(self) -> None:
        for text in ["https://github.com/ridelabz/engwe-speed-tool", "res/drawable/ic_home.xml", "kotlin/Users.kt"]:
            self.assertEqual(check_entry("classes.dex", text.encode(), []), [], text)

    def test_private_text_is_reported_without_the_text(self) -> None:
        items = private_items("# a comment\n\nSecret Name\nAB:CD:EF:01:23:45\n")
        self.assertEqual(items, ["Secret Name", "AB:CD:EF:01:23:45"])
        for content in ["x secret name y", "abcdef012345", "AB-CD-EF-01-23-45"]:
            with self.subTest(content=content):
                problems = check_entry("classes.dex", content.encode("utf-16-le"), items)
                self.assertEqual(problems, ["classes.dex: holds private text"])
        self.assertEqual(check_entry("classes.dex", b"a comment, nothing here", items), [])

    def test_every_file_in_the_apk(self) -> None:
        data = apk({"AndroidManifest.xml": b"\x00", "assets/x.txt": b"/home/someone/x"})
        problems = check_apk(data, [])  # type: ignore[arg-type]
        self.assertEqual(problems, ["assets/x.txt: a build machine's path: a Linux home directory (/home/...)"])
        self.assertNotIn("someone", " ".join(problems), "the name isn't repeated")


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.build = self.text[self.text.index("\n  build:\n") : self.text.index("\n  release:\n")]
        self.release = self.text[self.text.index("\n  release:\n") :]

    def test_only_version_tags_and_the_key_only_after_the_build(self) -> None:
        self.assertIn('on:\n  push:\n    tags: ["v*"]\n\npermissions:\n  contents: read\n', self.text)
        self.assertNotIn("pull_request", self.text)
        self.assertIn("    needs: build\n    runs-on: ubuntu-latest\n", self.release)
        self.assertIn("    environment: release\n", self.release)
        self.assertIn("      contents: write", self.release)
        self.assertEqual(self.text.count("persist-credentials: false"), 2)
        self.assertIn("cache-disabled: true", self.build)

    def test_gradle_never_runs_where_the_secrets_are(self) -> None:
        self.assertIn("./gradlew", self.build)
        self.assertNotIn("secrets.", self.build)
        self.assertNotIn("environment:", self.build)
        self.assertNotIn("contents: write", self.build)
        self.assertNotIn("gradle", self.release.lower().replace("gradle/build.gradle.kts", ""))

    def test_actions_are_pinned_to_commits(self) -> None:
        uses = re.findall(r"uses: (\S+)", self.text)
        self.assertTrue(uses)
        for action in uses:
            self.assertRegex(action, r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$")

    def test_the_key_reaches_only_the_signing_step(self) -> None:
        for step in re.split(r"\n      - ", self.release):
            secrets = set(re.findall(r"secrets\.(\w+)", step))
            if "RELEASE_KEYSTORE_BASE64" in secrets:
                self.assertTrue(step.startswith("name: Sign\n"), step[:40])
                self.assertIn('trap \'rm -f "$KEYSTORE"\' EXIT', step)
            elif secrets:
                self.assertEqual(secrets, {"APK_PRIVATE_TEXT"}, step[:40])
                self.assertTrue(step.startswith("name: Check the APK\n"), step[:40])


class SafetyLinksTest(unittest.TestCase):
    def test_safety_text_has_only_plain_links_the_notes_can_rewrite(self) -> None:
        text = (ROOT / "SAFETY.md").read_text(encoding="utf-8")
        self.assertNotIn("![", text, "no images")
        self.assertNotRegex(text, r"(?m)^\s*\[[^\]]+\]:", "no reference-style links")
        for target in re.findall(r"\]\(([^)]*)\)", text):
            self.assertRegex(target, r"^(https://\S+|[A-Za-z0-9_][A-Za-z0-9_./-]*)$", "a plain link without a title")


if __name__ == "__main__":
    unittest.main()
