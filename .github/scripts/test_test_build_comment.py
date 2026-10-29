"""Tests for test_build_comment.py (python3 -m unittest, from .github/scripts)."""

from __future__ import annotations

import base64
import copy
import hashlib
import io
import json
import re
import unittest
import warnings
import zipfile
from pathlib import Path

import test_build_comment as tbc

tbc.log = lambda message: None  # keep test output readable

ROOT = Path(__file__).resolve().parent.parent.parent
REPO = "owner/app"
HEAD = "a" * 40
MERGE = "b" * 40
BASE = "c" * 40
NEWER = "d" * 40
APK_NAME = "engwe-speed-tool-test-pr7.apk"
APK = b"PK-not-really-an-apk"
APK_SHA = hashlib.sha256(APK).hexdigest()


def make_zip(entries: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    return buf.getvalue()


def metadata(**overrides: object) -> dict:
    meta = {"schema": 2, "pr": 7, "head_sha": HEAD, "built_sha": MERGE, "base_ref": "main", "apk_sha256": APK_SHA}
    meta.update(overrides)
    return meta


def blob(text: str) -> dict:
    data = text.encode()
    return {"encoding": "base64", "size": len(data), "content": base64.encodebytes(data).decode()}


class FakeApi:
    """Answers the REST calls the bot makes, from in-memory state, and records the writes."""

    def __init__(self) -> None:
        self.repo = REPO
        self.prs: dict[int, dict] = {}
        self.comments: dict[int, list[dict]] = {}
        self.runs: dict[str, list[dict]] = {"ci.yml": [], "test-build.yml": []}
        self.artifacts: dict[int, list[dict]] = {}
        self.downloads: dict[int, bytes] = {}
        self.compare: dict[str, dict] = {}
        self.trees: dict[str, dict] = {}
        self.blobs: dict[str, dict] = {}
        self.commits: dict[str, dict] = {}
        self.roles: dict[str, str] = {}
        self.events: dict[int, list[dict]] = {}
        self.head_queries: dict[str, list[dict]] = {}
        self.writes: list[tuple] = []
        self.head_after_first_read: str | None = None
        self._pr_reads = 0
        self._next_id = 500

    def _id(self) -> int:
        self._next_id += 1
        return self._next_id

    def add_run(
        self,
        workflow: str,
        run_id: int,
        head: str,
        conclusion: str | None,
        status: str = "completed",
        branch: str = "feature",
        repo: str = "contributor/app",
    ) -> None:
        self.runs[workflow].append(
            {
                "id": run_id,
                "head_sha": head,
                "head_branch": branch,
                "head_repository": {"full_name": repo},
                "path": f".github/workflows/{workflow}",
                "event": "pull_request",
                "status": status,
                "conclusion": conclusion,
            }
        )

    def add_artifact(self, run_id: int, name: str, data: bytes, **extra: object) -> int:
        art_id = self._id()
        art = {"id": art_id, "name": name, "expired": False, "size_in_bytes": len(data)}
        art.update(extra)
        self.artifacts.setdefault(run_id, []).append(art)
        self.downloads[art_id] = data
        return art_id

    def get_json(self, path: str, params: dict | None = None):
        params = params or {}
        if m := re.fullmatch(r"pulls/(\d+)", path):
            self._pr_reads += 1
            pr = copy.deepcopy(self.prs[int(m.group(1))])
            if self.head_after_first_read and self._pr_reads > 1:
                pr["head"]["sha"] = self.head_after_first_read
            return pr
        if path == "pulls":
            return self.head_queries.get(params["head"], [])
        if m := re.fullmatch(r"issues/(\d+)/comments", path):
            items = self.comments.get(int(m.group(1)), [])
            page = params.get("page", 1)
            return copy.deepcopy(items[(page - 1) * 100 : page * 100])
        if m := re.fullmatch(r"actions/workflows/([\w.-]+)/runs", path):
            return {"workflow_runs": [r for r in self.runs[m.group(1)] if r["head_sha"] == params["head_sha"]]}
        if m := re.fullmatch(r"actions/runs/(\d+)/artifacts", path):
            return {"artifacts": [a for a in self.artifacts.get(int(m.group(1)), []) if a["name"] == params["name"]]}
        if m := re.fullmatch(r"compare/(.+)", path):
            if m.group(1) not in self.compare:
                raise tbc.ApiError(404, path)
            return self.compare[m.group(1)]
        if m := re.fullmatch(r"git/trees/(\w+)", path):
            return self.trees[m.group(1)]
        if m := re.fullmatch(r"git/blobs/(\w+)", path):
            return self.blobs[m.group(1)]
        if m := re.fullmatch(r"commits/(\w+)", path):
            if m.group(1) not in self.commits:
                raise tbc.ApiError(404, path)
            return self.commits[m.group(1)]
        if m := re.fullmatch(r"issues/(\d+)/events", path):
            items = self.events.get(int(m.group(1)), [])
            page = params.get("page", 1)
            return copy.deepcopy(items[(page - 1) * 100 : page * 100])
        if m := re.fullmatch(r"collaborators/(.+)/permission", path):
            if m.group(1) not in self.roles:
                raise tbc.ApiError(404, path)
            return {"role_name": self.roles[m.group(1)]}
        raise AssertionError(f"unexpected GET {path}")

    def post_json(self, path: str, body: dict):
        m = re.fullmatch(r"issues/(\d+)/comments", path)
        assert m, path
        comment = {"id": self._id(), "user": {"login": "github-actions[bot]", "type": "Bot"}, "body": body["body"]}
        self.comments.setdefault(int(m.group(1)), []).append(comment)
        self.writes.append(("POST", path))
        return comment

    def patch_json(self, path: str, body: dict):
        m = re.fullmatch(r"issues/comments/(\d+)", path)
        assert m, path
        for items in self.comments.values():
            for c in items:
                if c["id"] == int(m.group(1)):
                    c["body"] = body["body"]
                    self.writes.append(("PATCH", path))
                    return c
        raise AssertionError("no such comment")

    def delete(self, path: str) -> bool:
        m = re.fullmatch(r"issues/(\d+)/labels/(.+)", path)
        assert m, path
        pr = self.prs[int(m.group(1))]
        self.events.setdefault(int(m.group(1)), []).append(
            {"event": "unlabeled", "label": {"name": m.group(2)}, "actor": {"login": "github-actions[bot]"}}
        )
        before = len(pr["labels"])
        pr["labels"] = [lbl for lbl in pr["labels"] if lbl["name"] != m.group(2)]
        self.writes.append(("DELETE", path))
        return len(pr["labels"]) < before

    def download(self, path: str, limit: int) -> bytes:
        m = re.fullmatch(r"actions/artifacts/(\d+)/zip", path)
        assert m, path
        data = self.downloads[int(m.group(1))]
        if len(data) > limit:
            raise tbc.ApiError(0, "too large")
        return data

    def bot_bodies(self, pr: int = 7) -> list[str]:
        return [c["body"] for c in self.comments.get(pr, []) if c["user"]["login"] == "github-actions[bot]"]


def world(files: list[dict] | None = None, *, base_ref: str = "main", labels: tuple[str, ...] = ()) -> FakeApi:
    """Pull request #7 at HEAD with both workflows green and a valid build."""
    api = FakeApi()
    api.prs[7] = {
        "number": 7,
        "state": "open",
        "head": {"sha": HEAD, "ref": "feature", "repo": {"full_name": "contributor/app"}},
        "base": {"ref": base_ref, "repo": {"full_name": REPO}},
        "labels": [{"name": name} for name in labels],
    }
    add_build(api, 11, 12, HEAD, MERGE)
    api.compare[f"main...{HEAD}"] = {"status": "ahead", "files": files or [{"filename": "README.md", "status": "modified"}]}
    api.compare[f"{BASE}...{HEAD}"] = copy.deepcopy(api.compare[f"main...{HEAD}"])
    api.compare[f"{BASE}...main"] = {"status": "ahead"}
    api.trees[HEAD] = {"truncated": False, "tree": [{"path": "README.md", "mode": "100644", "sha": "1" * 40}]}
    api.trees["main"] = copy.deepcopy(api.trees[HEAD])
    api.roles["maintainer"] = "admin"
    api.roles["helper"] = "write"
    return api


def add_build(api: FakeApi, ci_run: int, build_run: int, head: str, merge: str) -> None:
    """Green CI and Test build runs for `head`, with a valid APK built as `merge` (BASE + head)."""
    api.add_run("ci.yml", ci_run, head, "success")
    api.add_run("test-build.yml", build_run, head, "success")
    sums = f"{APK_SHA}  {APK_NAME}\n".encode()
    api.add_artifact(build_run, "test-apk-pr7", make_zip({APK_NAME: APK, "SHA256SUMS": sums}))
    meta = json.dumps(metadata(head_sha=head, built_sha=merge)).encode()
    api.add_artifact(build_run, "pr-metadata", make_zip({"pr-metadata.json": meta}))
    api.commits[merge] = {"parents": [{"sha": BASE}, {"sha": head}]}


def context(api: FakeApi) -> tbc.Context:
    rules = tbc.parse_rules((ROOT / ".github/protected-paths.txt").read_text())
    return tbc.Context(api, rules, "https://github.com")


def state_of(body: str) -> tbc.Marker:
    marker = tbc.parse_marker(body)
    assert marker is not None
    return marker


PROTECTED_FILE = {"filename": ".github/workflows/ci.yml", "status": "modified"}


def label_event(action: str, label: str = tbc.LABEL, sender: str = "maintainer", head: str = HEAD) -> dict:
    return {
        "action": action,
        "pull_request": {"number": 7, "head": {"sha": head}},
        "label": {"name": label},
        "sender": {"login": sender},
    }


def run_event(path: str = ".github/workflows/test-build.yml", **overrides: object) -> dict:
    run = {
        "path": path,
        "event": "pull_request",
        "head_sha": HEAD,
        "head_branch": "feature",
        "head_repository": {"owner": {"login": "contributor"}},
        "repository": {"full_name": REPO},
        "pull_requests": [],
    }
    run.update(overrides)
    return {"workflow_run": run}


class RulesTest(unittest.TestCase):
    rules = tbc.parse_rules((ROOT / ".github/protected-paths.txt").read_text())

    def protected(self, path: str) -> bool:
        return tbc.protecting_rule(path, self.rules) is not None

    def test_protected_paths(self) -> None:
        for path in [
            ".github/workflows/ci.yml",
            ".github/CODEOWNERS",
            "build.gradle.kts",
            "settings.gradle.kts",
            "app/build.gradle.kts",
            "app/sub/x.gradle",
            "gradle.properties",
            "app/gradle.properties",
            "local.properties",
            "app/local.properties",
            ".gradle/configuration-cache/x",
            "gradle/libs.versions.toml",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradlew",
            "gradlew.bat",
            "buildSrc/src/main/kotlin/X.kt",
            "build-logic/x",
            "scripts/check_apk_manifest.py",
            "signing/public-test.keystore",
            "app/proguard-rules.pro",
            "app/src/main/resources/META-INF/proguard/x.pro",
            "app/src/main/kotlin/META-INF/x",
            "app/src/main/resources/x.txt",
            "app/libs/x.jar",
            "app/src/main/jniLibs/arm64-v8a/libx.so",
            "app/src/main/cpp/x.c",
            "app/src/main/aidl/x/IBinder.aidl",
            "app/src/main/AndroidManifest.xml",
            "app/src/debug/AndroidManifest.xml",
            "elsewhere/AndroidManifest.xml",
            "app/src/main/res/xml/data_extraction_rules.xml",
            "app/src/main/res/xml-v31/data_extraction_rules.xml",
            "other/res/xml-night/x.xml",
            "app/src/prTest/res/values/strings.xml",
            "app/src/main/res/values/test_build.xml",
            "app/src/main/res/values/app_name.xml",
            "app/src/main/res/values/ic_launcher_colors.xml",
            "app/src/main/res/values-night/ic_launcher_colors.xml",
            "app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml",
            "app/src/main/res/drawable/ic_launcher_foreground.xml",
            "app/lint.xml",
            "lint.xml",
            ".editorconfig",
            "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/MainActivity.kt",
            "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/TestBuildBanner.kt",
            "ble-android/src/main/kotlin/X.kt",
            "protocol/src/main/kotlin/io/github/ridelabz/engwespeedtool/protocol/BikeCommand.kt",
            "protocol/src/test/kotlin/X.kt",
            "protocol/api/protocol.api",
            "protocol/src/main/resources/other.json",
            "protocol/src/main/resources/profiles/sub/x.json",
            "protocol/src/main/resources/profiles/x.txt",
            "session/src/main/kotlin/X.kt",
            "reporting/src/main/kotlin/X.kt",
            "LICENSE",
            "SAFETY.md",
            "app/src/main/res/values-de/legal.xml",
            "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/onboarding/RiskAcceptanceScreen.kt",
            "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/legal/LegalScreen.kt",
            # Case doesn't help.
            "APP/SRC/MAIN/ANDROIDMANIFEST.XML",
            ".GitHub/workflows/x.yml",
        ]:
            with self.subTest(path=path):
                self.assertTrue(self.protected(path))

    def test_open_paths(self) -> None:
        for path in [
            "README.md",
            "PROTOCOL.md",
            "docs/log-format.md",
            "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/dashboard/DashboardScreen.kt",
            "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/onboarding/WelcomeScreen.kt",
            "app/src/main/res/values/strings.xml",
            "app/src/main/res/values-de/strings.xml",
            "app/src/main/res/drawable/x.xml",
            "app/src/test/kotlin/X.kt",
            "protocol/src/main/resources/profiles/new-bike.json",
            "protocol/src/main/resources/profiles/index.txt",
            "x/.github/y",  # only the root .github is automation
        ]:
            with self.subTest(path=path):
                self.assertFalse(self.protected(path))

    def test_glob(self) -> None:
        self.assertTrue(tbc.glob_regex("a/*.kt").match("a/b.kt"))
        self.assertFalse(tbc.glob_regex("a/*.kt").match("a/b/c.kt"))
        self.assertTrue(tbc.glob_regex("**/x").match("x"))
        self.assertTrue(tbc.glob_regex("**/x").match("a/b/x"))
        self.assertFalse(tbc.glob_regex("**/x").match("a/bx"))
        self.assertTrue(tbc.glob_regex("a/**").match("a/b/c"))
        self.assertTrue(tbc.glob_regex("a/?.kt").match("a/b.kt"))
        self.assertFalse(tbc.glob_regex("a/?.kt").match("a//.kt"))
        self.assertTrue(tbc.glob_regex("a/**").match("a/b\nc"))

    def test_last_match_wins(self) -> None:
        rules = tbc.parse_rules("a/**\n!a/open/*\na/open/closed\n")
        self.assertEqual(tbc.protecting_rule("a/x", rules), "a/**")
        self.assertIsNone(tbc.protecting_rule("a/open/y", rules))
        self.assertEqual(tbc.protecting_rule("a/open/closed", rules), "a/open/closed")

    def test_rejects_unsupported_patterns(self) -> None:
        for bad in ["/abs", "a[bc]", "a\\b", "!", "x y", "a{b,c}"]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                tbc.parse_rules(bad)
        with self.assertRaises(ValueError):
            tbc.parse_rules("# only comments\n")

    def test_codeowners_mirrors_the_rules(self) -> None:
        expected = []
        for rule in self.rules:
            pattern = rule.pattern if rule.pattern.startswith("**/") else "/" + rule.pattern
            expected.append(pattern if rule.exception else f"{pattern} @ridelabz")
        lines = (ROOT / ".github/CODEOWNERS").read_text().splitlines()
        actual = [line.strip() for line in lines if line.strip() and not line.startswith("#")]
        self.assertEqual(actual, expected)


REFERENCE_PROFILE = (ROOT / "protocol/src/main/resources/profiles/engwe-e26-3-pro.json").read_text()


def replace_once(text: str, old: str, new: str) -> str:
    """Like str.replace, but fails unless `old` occurs exactly once, so a case can't silently test nothing."""
    assert text.count(old) == 1, old
    return text.replace(old, new)


def unverified_profile(**changes: object) -> str:
    doc = json.loads(REFERENCE_PROFILE)
    doc["verified"] = False
    doc["status_layout"]["fields"] = [
        dict(f, verified=False) for f in doc["status_layout"]["fields"] if not f["name"].startswith("power")
    ]
    doc.pop("assist_levels", None)
    doc.pop("lock_control", None)
    doc.update(changes)
    return json.dumps(doc)


class ContentTest(unittest.TestCase):
    def test_reference_profile_needs_review(self) -> None:
        reasons = tbc.profile_reasons(REFERENCE_PROFILE)
        self.assertIn("a bike profile marked verified", reasons)
        self.assertIn("a bike profile that maps the power flags", reasons)
        self.assertIn("a bike profile that enables assist control", reasons)
        self.assertIn("a bike profile that enables lock control", reasons)

    def test_lock_control_needs_review_whatever_its_value(self) -> None:
        for value in [True, False, None, "true", 1]:
            with self.subTest(value=value):
                self.assertEqual(
                    tbc.profile_reasons(unverified_profile(lock_control=value)),
                    ["a bike profile that enables lock control"],
                )

    def test_assist_control_needs_review_whatever_its_value(self) -> None:
        for value in [5, 1, 0, None, "5", [5]]:
            with self.subTest(value=value):
                self.assertEqual(
                    tbc.profile_reasons(unverified_profile(assist_levels=value)),
                    ["a bike profile that enables assist control"],
                )

    def test_unverified_profile_without_power_flags_is_open(self) -> None:
        self.assertEqual(tbc.profile_reasons(unverified_profile()), [])

    def test_profile_tricks(self) -> None:
        open_profile = unverified_profile()
        for text in [
            replace_once(open_profile, '"verified": false, "status_layout"', '"verified": 0, "status_layout"'),
            open_profile[:-1] + ', "verified": true}',  # duplicate key: parsers disagree on which wins
            replace_once(open_profile, f'"schema": {json.loads(open_profile)["schema"]}', '"schema": NaN'),
            "\ufeff" + open_profile,
            "[]",
            "{",
            unverified_profile(status_layout={"fields": "x"}),
            unverified_profile(status_layout={"payload_length": 1, "fields": [{"name": "Power_Flag_A"}]}),
            unverified_profile(status_layout={"payload_length": 1, "fields": [{"name": 3, "verified": False}]}),
            unverified_profile(status_layout={"payload_length": 1, "fields": ["speed"]}),
        ]:
            with self.subTest(text=text[:60]):
                self.assertNotEqual(tbc.profile_reasons(text), [])

    def test_index(self) -> None:
        self.assertEqual(tbc.index_reasons("# comment\nengwe-e26-3-pro.json\n\nnew-bike.json\r\n"), [])
        for text in ["../x.json", "sub/x.json", "X.json", "x.json.txt", "x\u2028.json", "/x.json", ".x.json"]:
            with self.subTest(text=text):
                self.assertNotEqual(tbc.index_reasons(text), [])

    def test_values(self) -> None:
        self.assertEqual(tbc.values_reasons('<resources><string name="title">Hi</string></resources>'), [])
        for text in [
            '<string name="test_build_banner_pr">x</string>',
            '<string name="TEST_BUILD_banner_pr">x</string>',
            '<string name="test&#95;build_banner_pr">x</string>',
            '<string name="test&#x5F;build_banner_pr">x</string>',
            '<!DOCTYPE resources [<!ENTITY u "_">]><string name="test&u;build">x</string>',
            '<string name="app_name">ENGWE Speed Tool (Unofficial)</string>',
            '<color name="ic_launcher_background">#0E5E5A</color>',
            '<string name="legal_statement_warranty">x</string>',
            '<string-array name="risk"><item>@string/legal_accept</item></string-array>',
        ]:
            with self.subTest(text=text):
                self.assertNotEqual(tbc.values_reasons(text), [])


class AssessTest(unittest.TestCase):
    def assess(self, api: FakeApi, bases: tuple[str, ...] = ("main",)) -> list[str]:
        return tbc.assess(api, HEAD, context(api).rules, list(bases))

    def test_open_change(self) -> None:
        self.assertEqual(self.assess(world()), [])

    def test_protected_path_and_rename_source(self) -> None:
        api = world([{"filename": "docs/x.kt", "previous_filename": "ble-android/src/main/X.kt", "status": "renamed"}])
        self.assertEqual(self.assess(api), ["`ble-android/**`"])

    def test_too_many_files(self) -> None:
        api = world([{"filename": f"docs/{i}.md", "status": "added"} for i in range(300)])
        self.assertIn("more changed files than can be checked", self.assess(api))

    def test_symlink_or_submodule(self) -> None:
        for mode in ("120000", "160000"):
            api = world()
            api.trees[HEAD]["tree"].append({"path": "app/src/main/kotlin/link", "mode": mode})
            with self.subTest(mode=mode):
                self.assertIn("a symbolic link or submodule", self.assess(api))

    def test_truncated_tree(self) -> None:
        api = world()
        api.trees[HEAD]["truncated"] = True
        self.assertIn("a file tree too large to check", self.assess(api))

    def test_missing_file_list(self) -> None:
        api = world()
        del api.compare[f"main...{HEAD}"]["files"]
        self.assertEqual(self.assess(api), ["changes that couldn't be compared with main"])

    def test_change_hidden_from_main_but_inside_the_build(self) -> None:
        # The build merged into an older main; main has since taken in a protected commit of this branch.
        api = world()
        api.compare[f"{BASE}...{HEAD}"]["files"] = [PROTECTED_FILE, {"filename": "README.md", "status": "modified"}]
        self.assertEqual(self.assess(api), [])
        self.assertEqual(self.assess(api, ("main", BASE)), ["`.github/**`"])

    def test_build_files_must_equal_mains(self) -> None:
        for path in (".github/workflows/test-build.yml", "app/build.gradle.kts", "scripts/x.py", "gradle/x.toml"):
            api = world()
            api.trees[HEAD]["tree"].append({"path": path, "mode": "100644", "sha": "2" * 40})
            api.trees["main"]["tree"].append({"path": path, "mode": "100644", "sha": "3" * 40})
            with self.subTest(path=path):
                self.assertEqual(self.assess(api), ["build files that differ from main's (updating the branch from main may help)"])
        api = world()
        api.trees[HEAD]["tree"].append({"path": "protocol/x.kt", "mode": "100644", "sha": "2" * 40})
        self.assertEqual(self.assess(api), [], "only build files need to equal main's")

    def test_build_patterns_are_protected_rules(self) -> None:
        patterns = {r.pattern for r in context(world()).rules if not r.exception}
        self.assertLessEqual(tbc.BUILD_PATTERNS, patterns)

    def test_compare_failure_is_protected(self) -> None:
        api = world()
        del api.compare[f"main...{HEAD}"]
        self.assertEqual(self.assess(api), ["changes that couldn't be compared with main"])

    def test_profile_content(self) -> None:
        path = "protocol/src/main/resources/profiles/new-bike.json"
        api = world([{"filename": path, "status": "added", "sha": "e" * 40}])
        api.blobs["e" * 40] = blob(unverified_profile())
        self.assertEqual(self.assess(api), [])
        api.blobs["e" * 40] = blob(REFERENCE_PROFILE)
        self.assertIn("a bike profile marked verified", self.assess(api))

    def test_removed_profile(self) -> None:
        path = "protocol/src/main/resources/profiles/engwe-e26-3-pro.json"
        self.assertEqual(self.assess(world([{"filename": path, "status": "removed"}])), ["a removed bike profile file"])

    def test_unreadable_content(self) -> None:
        api = world([{"filename": "app/src/main/res/values/strings.xml", "status": "modified", "sha": "f" * 40}])
        api.blobs["f" * 40] = {"encoding": "base64", "size": 2, "content": base64.b64encode(b"\xff\xfe").decode()}
        self.assertEqual(self.assess(api), ["a file whose content couldn't be checked"])
        api.compare[f"main...{HEAD}"]["files"][0]["sha"] = None
        self.assertEqual(self.assess(api), ["a file whose content couldn't be checked"])

    def test_banner_override(self) -> None:
        api = world([{"filename": "app/src/main/res/values-de/strings.xml", "status": "added", "sha": "f" * 40}])
        api.blobs["f" * 40] = blob('<string name="test_build_banner_pr">ok</string>')
        self.assertEqual(self.assess(api), ["a resource that names the app, its launcher icon, the test-build banner or the legal text"])


class MetadataTest(unittest.TestCase):
    def check(self, raw: bytes) -> dict:
        return tbc.validate_metadata(raw, 7, HEAD)

    def test_valid(self) -> None:
        self.assertEqual(self.check(json.dumps(metadata()).encode())["apk_sha256"], APK_SHA)

    def test_malformed_fixtures_are_rejected(self) -> None:
        good = metadata()
        fixtures = {
            "not json": b"{",
            "not utf-8": b"\xff",
            "array": b"[]",
            "too large": json.dumps(dict(good, pad="x" * 5000)).encode(),
            "extra field": json.dumps(dict(good, extra=1)).encode(),
            "missing field": json.dumps({k: v for k, v in good.items() if k != "built_sha"}).encode(),
            "duplicate key": json.dumps(good)[:-1].encode() + b', "pr": 8}',
            "old schema": json.dumps(dict(good, schema=1)).encode(),
            "schema as bool": json.dumps(dict(good, schema=True)).encode(),
            "pr as string": json.dumps(dict(good, pr="7")).encode(),
            "pr as bool": json.dumps(dict(good, pr=True)).encode(),
            "other pr": json.dumps(dict(good, pr=8)).encode(),
            "other head": json.dumps(dict(good, head_sha=NEWER)).encode(),
            "short head": json.dumps(dict(good, head_sha=HEAD[:39])).encode(),
            "uppercase built sha": json.dumps(dict(good, built_sha=MERGE.upper())).encode(),
            "other base": json.dumps(dict(good, base_ref="feature")).encode(),
            "short hash": json.dumps(dict(good, apk_sha256=APK_SHA[:63])).encode(),
            "hash with newline": json.dumps(dict(good, apk_sha256=APK_SHA + "\n")).encode(),
            "nan": json.dumps(good).replace('"pr": 7', '"pr": NaN').encode(),
        }
        for name, raw in fixtures.items():
            with self.subTest(name=name), self.assertRaises(tbc.BuildError):
                self.check(raw)


class BuildTest(unittest.TestCase):
    def verify(self, api: FakeApi) -> tbc.Build:
        meta = tbc.load_metadata(api, {"id": 12}, 7, HEAD)
        self.assertEqual(tbc.merge_base_of_build(api, meta["built_sha"], HEAD), BASE)
        return tbc.verify_apk(api, {"id": 12}, 7, meta)

    def test_valid(self) -> None:
        build = self.verify(world())
        self.assertEqual((build.run_id, build.apk_name, build.apk_sha256), (12, APK_NAME, APK_SHA))

    def replace(self, api: FakeApi, name: str, data: bytes) -> None:
        art = next(a for a in api.artifacts[12] if a["name"] == name)
        api.downloads[art["id"]] = data
        art["size_in_bytes"] = len(data)

    def test_rejections(self) -> None:
        sums = f"{APK_SHA}  {APK_NAME}\n".encode()
        cases = {
            "apk swapped": lambda a: self.replace(
                a, "test-apk-pr7", make_zip({APK_NAME: b"evil", "SHA256SUMS": sums})
            ),
            "swapped consistently": lambda a: self.replace(
                a,
                "test-apk-pr7",
                make_zip(
                    {APK_NAME: b"evil", "SHA256SUMS": f"{hashlib.sha256(b'evil').hexdigest()}  {APK_NAME}\n".encode()}
                ),
            ),
            "extra entry": lambda a: self.replace(
                a, "test-apk-pr7", make_zip({APK_NAME: APK, "SHA256SUMS": sums, "x": b""})
            ),
            "duplicate entry": lambda a: self.replace(a, "test-apk-pr7", dup_zip(sums)),
            "not a zip": lambda a: self.replace(a, "test-apk-pr7", b"nope"),
            "two apk artifacts": lambda a: a.add_artifact(12, "test-apk-pr7", b"x"),
            "no metadata": lambda a: a.artifacts[12].pop(),
            "expired": lambda a: a.artifacts[12][0].update(expired=True),
            "metadata names pr 8": lambda a: self.replace(
                a, "pr-metadata", make_zip({"pr-metadata.json": json.dumps(metadata(pr=8)).encode()})
            ),
            "built commit isn't a merge of head": lambda a: a.commits.update(
                {MERGE: {"parents": [{"sha": BASE}, {"sha": NEWER}]}}
            ),
            "built commit unknown": lambda a: a.commits.clear(),
            "base not in main": lambda a: a.compare.update({f"{BASE}...main": {"status": "diverged"}}),
        }
        for name, tamper in cases.items():
            api = world()
            tamper(api)
            with self.subTest(name=name), self.assertRaises(tbc.BuildError):
                self.verify(api)


def dup_zip(sums: bytes) -> bytes:
    buf = io.BytesIO()
    with warnings.catch_warnings(), zipfile.ZipFile(buf, "w") as zf:
        warnings.simplefilter("ignore")  # "Duplicate name" is the point here
        zf.writestr(APK_NAME, APK)
        zf.writestr("SHA256SUMS", sums)
        zf.writestr(APK_NAME, b"evil")
    return buf.getvalue()


class FlowTest(unittest.TestCase):
    def test_open_change_gets_a_link(self) -> None:
        api = world()
        self.assertEqual(tbc.update(context(api), 7), "ready")
        [body] = api.bot_bodies()
        self.assertEqual(state_of(body), tbc.Marker("ready", HEAD, None))
        self.assertIn(APK_SHA, body)
        self.assertIn(f"https://github.com/{REPO}/actions/runs/12/artifacts/", body)
        self.assertIn("build_tested=PR%20%237", body)
        self.assertIn("TEST BUILD · PR #7 · not reviewed", body)

    def test_comment_is_updated_not_duplicated(self) -> None:
        api = world()
        tbc.update(context(api), 7)
        tbc.update(context(api), 7)
        self.assertEqual(len(api.bot_bodies()), 1)
        self.assertEqual(api.writes, [("POST", "issues/7/comments")])

    def test_protected_change_is_withheld(self) -> None:
        api = world([PROTECTED_FILE])
        self.assertEqual(tbc.update(context(api), 7), "withheld")
        [body] = api.bot_bodies()
        self.assertIn("Waiting for maintainer safety review", body)
        self.assertIn("`.github/**`", body)
        self.assertNotIn("artifacts/", body)
        self.assertNotIn(APK_SHA, body)

    def test_states(self) -> None:
        cases = {
            "pending": lambda a: a.runs["ci.yml"].__setitem__(0, dict(a.runs["ci.yml"][0], status="in_progress")),
            "failed": lambda a: a.runs["test-build.yml"][0].update(conclusion="failure"),
            "unverifiable": lambda a: a.commits.clear(),
        }
        for state, change in cases.items():
            api = world()
            change(api)
            with self.subTest(state=state):
                self.assertEqual(tbc.update(context(api), 7), state)
                self.assertNotIn("artifacts/", api.bot_bodies()[0])
        api = world(base_ref="feature")
        self.assertEqual(tbc.update(context(api), 7), "not-main")

    def test_missing_build_run_is_pending(self) -> None:
        api = world()
        api.runs["test-build.yml"].clear()
        self.assertEqual(tbc.update(context(api), 7), "pending")

    def test_latest_run_counts(self) -> None:
        api = world()
        api.add_run("ci.yml", 99, HEAD, "failure")
        self.assertEqual(tbc.update(context(api), 7), "failed")

    def test_runs_of_another_branch_with_the_same_commit_are_ignored(self) -> None:
        for change in ({"head_branch": "other"}, {"head_repository": {"full_name": "someone/app"}}):
            api = world()
            api.runs["test-build.yml"][0].update(change)
            with self.subTest(change=change):
                self.assertEqual(tbc.update(context(api), 7), "pending")

    def test_build_merged_into_an_older_main_is_checked_against_that_base(self) -> None:
        api = world()
        api.compare[f"{BASE}...{HEAD}"]["files"] = [PROTECTED_FILE]
        self.assertEqual(tbc.update(context(api), 7), "withheld")

    def test_run_of_another_workflow_file_is_ignored(self) -> None:
        api = world()
        api.runs["ci.yml"][0]["path"] = ".github/workflows/fake.yml"
        self.assertEqual(tbc.update(context(api), 7), "pending")

    def test_head_moving_during_the_check_writes_nothing(self) -> None:
        api = world()
        api.head_after_first_read = NEWER
        self.assertIsNone(tbc.update(context(api), 7))
        self.assertEqual(api.writes, [])

    def test_closed_pull_request_is_left_alone(self) -> None:
        api = world()
        api.prs[7]["state"] = "closed"
        self.assertIsNone(tbc.update(context(api), 7))
        self.assertEqual(api.writes, [])

    def test_marker_in_someone_elses_comment_is_ignored(self) -> None:
        api = world([PROTECTED_FILE], labels=(tbc.LABEL,))
        forged = f"<!-- test-build-comment state=withheld sha={HEAD} approved={HEAD} -->\nhi\n"
        api.comments[7] = [{"id": 1, "user": {"login": "contributor", "type": "User"}, "body": forged}]
        self.assertEqual(tbc.update(context(api), 7), "withheld")
        # The label doesn't belong to a commit the bot's own comment named, so it goes too.
        self.assertEqual(api.writes, [("DELETE", "issues/7/labels/safe-to-test"), ("POST", "issues/7/comments")])


class WorkflowRunTest(unittest.TestCase):
    def test_fork_pull_request_found_through_head(self) -> None:
        api = world()
        api.head_queries["contributor:feature"] = [{"number": 7}]
        tbc.handle_workflow_run(context(api), run_event())
        self.assertEqual(state_of(api.bot_bodies()[0]).state, "ready")

    def test_listed_pull_request(self) -> None:
        api = world()
        tbc.handle_workflow_run(context(api), run_event(".github/workflows/ci.yml", pull_requests=[{"number": 7}]))
        self.assertEqual(len(api.bot_bodies()), 1)

    def test_ignored_runs(self) -> None:
        events = {
            "other workflow with the same name": run_event(".github/workflows/other.yml", pull_requests=[{"number": 7}]),
            "push": run_event(event="push", pull_requests=[{"number": 7}]),
            "other repository": run_event(repository={"full_name": "x/y"}, pull_requests=[{"number": 7}]),
            "malformed sha": run_event(head_sha="xyz", pull_requests=[{"number": 7}]),
            "stale head": run_event(head_sha=NEWER, pull_requests=[{"number": 7}]),
            "no pull request": run_event(),
        }
        for name, event in events.items():
            api = world()
            with self.subTest(name=name):
                tbc.handle_workflow_run(context(api), event)
                self.assertEqual(api.writes, [])

    def test_path_with_a_ref_suffix(self) -> None:
        api = world()
        tbc.handle_workflow_run(
            context(api), run_event(".github/workflows/ci.yml@refs/heads/main", pull_requests=[{"number": 7}])
        )
        self.assertEqual(len(api.bot_bodies()), 1)

    def test_two_matching_pull_requests_do_nothing(self) -> None:
        api = world()
        api.prs[8] = copy.deepcopy(api.prs[7]) | {"number": 8}
        tbc.handle_workflow_run(context(api), run_event(pull_requests=[{"number": 7}, {"number": 8}]))
        self.assertEqual(api.writes, [])

    def test_output_never_repeats_pull_request_text(self) -> None:
        api = world()
        api.head_queries["contributor:@everyone <img src=x>"] = [{"number": 7}]
        tbc.handle_workflow_run(context(api), run_event(head_branch="@everyone <img src=x>"))
        self.assertNotIn("everyone", api.bot_bodies()[0])


def push(api: FakeApi, head: str, merge: str, ci_run: int, build_run: int) -> None:
    """A new head commit with the same changes and green builds."""
    api.prs[7]["head"]["sha"] = head
    for base in ("main", BASE):
        api.compare[f"{base}...{head}"] = copy.deepcopy(api.compare[f"{base}...{HEAD}"])
    api.trees[head] = copy.deepcopy(api.trees[HEAD])
    add_build(api, ci_run, build_run, head, merge)


class LabelTest(unittest.TestCase):
    def withheld(self) -> FakeApi:
        api = world([PROTECTED_FILE])
        tbc.update(context(api), 7)
        self.assertEqual(state_of(api.bot_bodies()[0]).state, "withheld")
        return api

    def label(self, api: FakeApi, sender: str = "maintainer", head: str = HEAD) -> None:
        api.prs[7]["labels"].append({"name": tbc.LABEL})
        api.events.setdefault(7, []).append({"event": "labeled", "label": {"name": tbc.LABEL}, "actor": {"login": sender}})
        tbc.handle_pull_request_target(context(api), label_event("labeled", sender=sender, head=head))

    def test_maintainer_label_releases_the_link_for_that_commit(self) -> None:
        api = self.withheld()
        self.label(api)
        body = api.bot_bodies()[0]
        self.assertEqual(state_of(body), tbc.Marker("ready", HEAD, HEAD))
        self.assertIn("A maintainer allowed a test build of this commit", body)
        # A later completion event keeps the approval while the label stays.
        tbc.handle_workflow_run(context(api), run_event(pull_requests=[{"number": 7}]))
        self.assertEqual(state_of(api.bot_bodies()[0]).state, "ready")

    def test_new_push_removes_the_label_and_regates(self) -> None:
        api = self.withheld()
        self.label(api)
        push(api, NEWER, "e" * 40, 21, 22)
        api.runs["ci.yml"][-1]["status"] = "in_progress"
        tbc.handle_pull_request_target(context(api), label_event("synchronize", head=NEWER))
        self.assertEqual(api.prs[7]["labels"], [])
        self.assertEqual(state_of(api.bot_bodies()[0]), tbc.Marker("pending", NEWER, None))
        api.runs["ci.yml"][-1]["status"] = "completed"
        tbc.handle_workflow_run(context(api), run_event(head_sha=NEWER, pull_requests=[{"number": 7}]))
        self.assertEqual(state_of(api.bot_bodies()[0]), tbc.Marker("withheld", NEWER, None))

    def test_label_left_from_an_earlier_commit_is_removed_by_any_update(self) -> None:
        api = self.withheld()
        self.label(api)
        push(api, NEWER, "e" * 40, 21, 22)  # the push event that removes the label never ran
        tbc.handle_workflow_run(context(api), run_event(head_sha=NEWER, pull_requests=[{"number": 7}]))
        self.assertEqual(api.prs[7]["labels"], [])
        body = api.bot_bodies()[0]
        self.assertEqual(state_of(body), tbc.Marker("withheld", NEWER, None))
        self.assertIn("the pull request changed after it was added", body)

    def test_approval_needs_the_label_to_stay(self) -> None:
        api = self.withheld()
        self.label(api)
        api.prs[7]["labels"] = []
        tbc.handle_pull_request_target(context(api), label_event("unlabeled"))
        self.assertEqual(state_of(api.bot_bodies()[0]), tbc.Marker("withheld", HEAD, None))

    def test_label_without_approval_marker_does_not_release(self) -> None:
        api = self.withheld()
        api.prs[7]["labels"].append({"name": tbc.LABEL})  # e.g. the labeled event never ran
        tbc.handle_workflow_run(context(api), run_event(pull_requests=[{"number": 7}]))
        self.assertEqual(state_of(api.bot_bodies()[0]).state, "withheld")

    def test_marker_and_label_written_with_a_workflow_token_do_not_release(self) -> None:
        api = self.withheld()
        comment = api.comments[7][0]
        comment["body"] = comment["body"].replace("approved=none", f"approved={HEAD}")
        api.prs[7]["labels"].append({"name": tbc.LABEL})
        api.events[7] = [{"event": "labeled", "label": {"name": tbc.LABEL}, "actor": {"login": "github-actions[bot]"}}]
        tbc.handle_workflow_run(context(api), run_event(pull_requests=[{"number": 7}]))
        self.assertEqual(state_of(api.bot_bodies()[0]), tbc.Marker("withheld", HEAD, None))

    def test_marker_naming_another_commit_never_approves(self) -> None:
        api = self.withheld()
        comment = api.comments[7][0]
        comment["body"] = comment["body"].replace(f"sha={HEAD}", f"sha={NEWER}").replace("approved=none", f"approved={HEAD}")
        api.prs[7]["labels"].append({"name": tbc.LABEL})
        api.events[7] = [{"event": "labeled", "label": {"name": tbc.LABEL}, "actor": {"login": "maintainer"}}]
        tbc.handle_workflow_run(context(api), run_event(pull_requests=[{"number": 7}]))
        self.assertEqual(state_of(api.bot_bodies()[0]), tbc.Marker("withheld", HEAD, None))
        self.assertEqual(api.prs[7]["labels"], [])

    def test_two_marker_comments_disable_approval(self) -> None:
        api = self.withheld()
        self.label(api)
        self.assertEqual(state_of(api.bot_bodies()[0]).state, "ready")
        api.comments[7].append(copy.deepcopy(api.comments[7][0]) | {"id": 9999})
        tbc.handle_workflow_run(context(api), run_event(pull_requests=[{"number": 7}]))
        body = api.bot_bodies()[0]
        self.assertEqual(state_of(body), tbc.Marker("withheld", HEAD, None))
        self.assertIn("more than one test-build comment", body)

    def test_non_maintainer_label_is_removed(self) -> None:
        for sender in ("helper", "stranger", "github-actions[bot]"):
            api = self.withheld()
            with self.subTest(sender=sender):
                self.label(api, sender=sender)
                self.assertEqual(api.prs[7]["labels"], [])
                body = api.bot_bodies()[0]
                self.assertEqual(state_of(body), tbc.Marker("withheld", HEAD, None))
                self.assertIn("only maintainers can approve", body)

    def test_label_for_a_commit_the_comment_did_not_show_is_removed(self) -> None:
        api = self.withheld()
        push(api, NEWER, "e" * 40, 21, 22)  # pushed just before the label; the comment still names HEAD
        self.label(api, head=NEWER)
        self.assertEqual(api.prs[7]["labels"], [])
        body = api.bot_bodies()[0]
        self.assertEqual(state_of(body).approved, None)
        self.assertIn("approve only once this comment asks for a safety review", body)

    def test_label_while_checks_are_pending_is_removed(self) -> None:
        api = world([PROTECTED_FILE])
        api.runs["ci.yml"][0]["status"] = "in_progress"
        tbc.update(context(api), 7)
        self.assertEqual(state_of(api.bot_bodies()[0]).state, "pending")
        self.label(api)
        self.assertEqual(api.prs[7]["labels"], [])
        self.assertEqual(state_of(api.bot_bodies()[0]).approved, None)

    def test_label_before_any_comment_is_removed(self) -> None:
        api = world([PROTECTED_FILE])
        self.label(api)
        self.assertEqual(api.prs[7]["labels"], [])
        self.assertEqual(state_of(api.bot_bodies()[0]), tbc.Marker("withheld", HEAD, None))

    def test_other_labels_are_ignored(self) -> None:
        api = self.withheld()
        writes = list(api.writes)
        tbc.handle_pull_request_target(context(api), label_event("labeled", label="bug"))
        self.assertEqual(api.writes, writes)


class ApiTest(unittest.TestCase):
    class Recording(tbc.Api):
        def __init__(self, responses: list[tuple[int, dict, bytes]]) -> None:
            super().__init__("secret-token", "https://api.example", REPO)
            self.responses = responses
            self.calls: list[tuple[str, str, bool]] = []

        def _call(self, method, url, *, body=None, auth=True, limit=tbc.MAX_JSON):
            self.calls.append((method, url, auth))
            return self.responses.pop(0)

    def test_download_follows_https_redirect_without_the_token(self) -> None:
        api = self.Recording([(302, {"Location": "https://blob.example/x?sig=1"}, b""), (200, {}, b"zip")])
        self.assertEqual(api.download("actions/artifacts/5/zip", 10), b"zip")
        self.assertEqual(
            api.calls,
            [
                ("GET", f"https://api.example/repos/{REPO}/actions/artifacts/5/zip", True),
                ("GET", "https://blob.example/x?sig=1", False),
            ],
        )

    def test_download_refuses_other_redirects_and_errors(self) -> None:
        for responses in (
            [(302, {"Location": "http://blob.example/x"}, b"")],
            [(302, {}, b"")],
            [(404, {}, b"")],
            [(302, {"Location": "https://blob.example/x"}, b""), (403, {}, b"")],
        ):
            with self.subTest(responses=responses), self.assertRaises(tbc.ApiError):
                self.Recording(responses).download("actions/artifacts/5/zip", 10)

    def test_get_json_needs_200(self) -> None:
        with self.assertRaises(tbc.ApiError):
            self.Recording([(500, {}, b"{}")]).get_json("pulls/7")
        self.assertEqual(self.Recording([(200, {}, b'{"a": 1}')]).get_json("pulls/7"), {"a": 1})

    def test_read_limit(self) -> None:
        self.assertEqual(tbc._read_limited(io.BytesIO(b"x" * 10), 10), b"x" * 10)
        with self.assertRaises(tbc.ApiError):
            tbc._read_limited(io.BytesIO(b"x" * 11), 10)

    def test_real_client_refuses_to_follow_redirects(self) -> None:
        handler = tbc._NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "Found", {}, "https://elsewhere.example"))


if __name__ == "__main__":
    unittest.main()
