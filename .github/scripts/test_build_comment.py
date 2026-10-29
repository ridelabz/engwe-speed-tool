#!/usr/bin/env python3
"""Creates or updates the one test-build comment on a pull request.

Runs in the privileged "Test build comment" workflow, which never checks out, builds or runs code from a
pull request. Everything this script trusts comes from GitHub's event payload and REST API, and its
rules come from main. The test build's own metadata is untrusted: it is only a cross-check.

Events:
  workflow_run (CI or Test build completed): find the pull request through the head SHA and update.
  pull_request_target (opened, reopened, synchronize, labeled, unlabeled): handle the approval label and
    update.

A pull request gets a download link when, for its current head commit, both workflows succeeded, the
build verifies, it targets main and either it changes no protected path or a maintainer approved that
exact commit with the approval label. The approval lives in a marker in the bot's own comment, so it can
only name the commit the comment showed when the label was added.

Output never repeats text from a pull request (titles, branch names, file names).
"""

from __future__ import annotations

import base64
import hashlib
import html
import io
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

WORKFLOWS = {"ci": ".github/workflows/ci.yml", "build": ".github/workflows/test-build.yml"}
MAIN = "main"
LABEL = "safe-to-test"
BOT_LOGIN = "github-actions[bot]"
APPROVER_ROLES = {"admin", "maintain"}
RULES_FILE = Path(__file__).resolve().parent.parent / "protected-paths.txt"

SHA_RE = re.compile(r"\A[0-9a-f]{40}\Z")
SHA256_RE = re.compile(r"\A[0-9a-f]{64}\Z")
MARKER_RE = re.compile(
    r"\A<!-- test-build-comment state=([a-z-]+) sha=([0-9a-f]{40}) approved=([0-9a-f]{40}|none) -->\n"
)

MAX_APK_ZIP = 200 * 1024 * 1024
MAX_METADATA_ZIP = 64 * 1024
MAX_METADATA = 4096
MAX_BLOB = 256 * 1024
MAX_JSON = 16 * 1024 * 1024
MAX_COMPARE_FILES = 300  # the compare API lists at most this many files
MAX_REASONS = 12

PROFILE_RE = re.compile(r"\Aprotocol/src/main/resources/profiles/[^/]*\.json\Z", re.IGNORECASE)
INDEX_RE = re.compile(r"\Aprotocol/src/main/resources/profiles/index\.txt\Z", re.IGNORECASE)
VALUES_RE = re.compile(r"(?:\A|/)res/values[^/]*/[^/]+\Z", re.IGNORECASE)
INDEX_LINE_RE = re.compile(r"\A[a-z0-9][a-z0-9-]*\.json\Z")
PROTECTED_RESOURCE_NAMES = ("test_build", "app_name", "ic_launcher", "legal_")
# Protected paths that run during the test build. Their content must equal main's, not just be unchanged
# by the pull request: otherwise the build's own metadata couldn't be trusted.
BUILD_PATTERNS = {
    ".github/**",
    "**/*.gradle*",
    "**/gradle.properties",
    "**/local.properties",
    "**/.gradle/**",
    "gradle/**",
    "gradlew*",
    "buildSrc/**",
    "build-logic/**",
    "scripts/**",
    "signing/**",
    ".gitattributes",
    ".gitmodules",
}
META_KEYS = {"schema", "pr", "head_sha", "built_sha", "base_ref", "apk_sha256"}
META_SCHEMA = 2


class ApiError(Exception):
    def __init__(self, status: int, what: str) -> None:
        super().__init__(f"HTTP {status} for {what}")
        self.status = status


class BuildError(Exception):
    """The test build can't be verified. Messages are fixed text from this script."""


def log(message: str) -> None:
    print(f"test-build-comment: {message}", flush=True)


# --- GitHub REST API -------------------------------------------------------------------------------


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _read_limited(resp: Any, limit: int) -> bytes:
    chunks, total = [], 0
    while True:
        chunk = resp.read(1 << 16)
        if not chunk:
            return b"".join(chunks)
        total += len(chunk)
        if total > limit:
            raise ApiError(0, "a response larger than allowed")
        chunks.append(chunk)


class Api:
    """Minimal REST client. Redirects are followed only for downloads, and without the token."""

    def __init__(self, token: str, api_url: str, repo: str) -> None:
        self._token = token
        self._base = api_url.rstrip("/")
        self.repo = repo
        self._opener = urllib.request.build_opener(_NoRedirect)

    def _call(
        self, method: str, url: str, *, body: Any = None, auth: bool = True, limit: int = MAX_JSON
    ) -> tuple[int, Any, bytes]:
        headers = {"User-Agent": "test-build-comment", "Accept": "application/vnd.github+json"}
        if auth:
            headers["Authorization"] = f"Bearer {self._token}"
            headers["X-GitHub-Api-Version"] = "2022-11-28"
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            resp = self._opener.open(req, timeout=60)
        except urllib.error.HTTPError as e:
            try:
                body = e.read(1 << 16) if e.fp else b""
            finally:
                e.close()
            return e.code, e.headers, body
        with resp:
            return resp.status, resp.headers, _read_limited(resp, limit)

    def _url(self, path: str, params: dict[str, Any] | None) -> str:
        url = f"{self._base}/repos/{self.repo}/{path}"
        return url + ("?" + urllib.parse.urlencode(params) if params else "")

    def get_json(self, path: str, params: dict[str, Any] | None = None) -> Any:
        status, _, data = self._call("GET", self._url(path, params))
        if status != 200:
            raise ApiError(status, path.split("?")[0])
        return json.loads(data)

    def post_json(self, path: str, body: Any) -> Any:
        status, _, data = self._call("POST", self._url(path, None), body=body)
        if status not in (200, 201):
            raise ApiError(status, path)
        return json.loads(data)

    def patch_json(self, path: str, body: Any) -> Any:
        status, _, data = self._call("PATCH", self._url(path, None), body=body)
        if status != 200:
            raise ApiError(status, path)
        return json.loads(data)

    def delete(self, path: str) -> bool:
        """True if deleted, False if it didn't exist."""
        status, _, _ = self._call("DELETE", self._url(path, None))
        if status in (200, 204):
            return True
        if status == 404:
            return False
        raise ApiError(status, path)

    def download(self, path: str, limit: int) -> bytes:
        status, headers, data = self._call("GET", self._url(path, None), limit=limit)
        if status in (301, 302, 303, 307, 308):
            location = headers.get("Location", "")
            if not location.startswith("https://"):
                raise ApiError(status, "a download redirect")
            status, _, data = self._call("GET", location, auth=False, limit=limit)
        if status != 200:
            raise ApiError(status, "a download")
        return data


# --- Protected paths -------------------------------------------------------------------------------


@dataclass(frozen=True)
class Rule:
    pattern: str
    exception: bool
    regex: re.Pattern[str]


def glob_regex(pattern: str) -> re.Pattern[str]:
    """Anchored at the root. `*` and `?` stay within a segment; `**/` is any number of directories."""
    out, i = [], 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out.append("(?:[^/]+/)*")
            i += 3
        elif pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        elif pattern[i] == "?":
            out.append("[^/]")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.compile(r"\A" + "".join(out) + r"\Z", re.IGNORECASE | re.DOTALL)


def parse_rules(text: str) -> list[Rule]:
    rules = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        exception = s.startswith("!")
        pattern = s[1:] if exception else s
        if not pattern or pattern.startswith("/") or any(c in pattern for c in "\\[]{} "):
            raise ValueError(f"unsupported pattern: {s}")
        rules.append(Rule(pattern, exception, glob_regex(pattern)))
    if not rules:
        raise ValueError("no rules")
    return rules


def protecting_rule(path: str, rules: list[Rule]) -> str | None:
    """The rule that protects `path`, or None. The last matching line wins."""
    hit = None
    for rule in rules:
        if rule.regex.match(path):
            hit = None if rule.exception else rule.pattern
    return hit


# --- Content checks for files that stay open to contributors --------------------------------------


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise ValueError("duplicate key")
    return dict(pairs)


def _no_constants(name: str) -> Any:
    raise ValueError(f"constant {name}")


def strict_json(text: str) -> Any:
    return json.loads(text, object_pairs_hook=_strict_object, parse_constant=_no_constants)


def profile_reasons(text: str) -> list[str]:
    try:
        doc = strict_json(text)
    except (ValueError, RecursionError):
        return ["a bike profile that isn't strict JSON"]
    if not isinstance(doc, dict):
        return ["a bike profile that isn't a JSON object"]
    reasons = []
    if doc.get("verified") is not False:
        reasons.append("a bike profile marked verified")
    # Any value: the key's presence offers the assist control for the model.
    if "assist_levels" in doc:
        reasons.append("a bike profile that enables assist control")
    # Any value: the key's presence may offer switching the bike off and on (a lock and an unlock).
    if "lock_control" in doc:
        reasons.append("a bike profile that enables lock control")
    layout = doc.get("status_layout")
    fields = layout.get("fields") if isinstance(layout, dict) else None
    if not isinstance(fields, list):
        return reasons + ["a bike profile without a field list"]
    for f in fields:
        if not isinstance(f, dict):
            reasons.append("a bike profile with a malformed field")
            continue
        name = f.get("name")
        if not isinstance(name, str) or name.lower().startswith("power"):
            reasons.append("a bike profile that maps the power flags")
        if f.get("verified") is not False:
            reasons.append("a bike profile with verified fields")
    return sorted(set(reasons))


def index_reasons(text: str) -> list[str]:
    if not text.isascii():
        return ["a profile index that isn't plain ASCII"]
    for line in re.split(r"\r\n|\n|\r", text):
        s = line.strip()
        if s and not s.startswith("#") and not INDEX_LINE_RE.match(s):
            return ["a profile index entry that isn't a plain file name"]
    return []


def values_reasons(text: str) -> list[str]:
    low = html.unescape(text).lower()
    if "<!doctype" in low or "<!entity" in low:
        return ["a resource file with a document type declaration"]
    if any(name in low for name in PROTECTED_RESOURCE_NAMES):
        return ["a resource that names the app, its launcher icon, the test-build banner or the legal text"]
    return []


def content_checker(path: str) -> Callable[[str], list[str]] | None:
    if PROFILE_RE.match(path):
        return profile_reasons
    if INDEX_RE.match(path):
        return index_reasons
    if VALUES_RE.search(path):
        return values_reasons
    return None


def read_blob(api: Api, sha: Any) -> str | None:
    if not isinstance(sha, str) or not SHA_RE.match(sha):
        return None
    blob = api.get_json(f"git/blobs/{sha}")
    size = blob.get("size")
    if blob.get("encoding") != "base64" or type(size) is not int or size > MAX_BLOB:
        return None
    data = base64.b64decode(blob.get("content") or "")
    if len(data) != size:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def file_reasons(api: Api, files: list[Any], rules: list[Rule]) -> list[str]:
    """Reasons from one compare's file list: protected paths (old names included) and content checks."""
    reasons: list[str] = []
    for f in files:
        if not isinstance(f, dict):
            reasons.append("a changed file that couldn't be read")
            continue
        names = [n for n in (f.get("filename"), f.get("previous_filename")) if isinstance(n, str)]
        if not names:
            reasons.append("a changed file without a name")
            continue
        hits = [r for r in (protecting_rule(n, rules) for n in names) if r]
        if hits:
            reasons.extend(f"`{r}`" for r in hits)
            continue
        checkers = {c for c in (content_checker(n) for n in names) if c}
        if not checkers:
            continue
        if f.get("status") == "removed":
            if checkers != {values_reasons}:
                reasons.append("a removed bike profile file")
            continue
        text = read_blob(api, f.get("sha"))
        if text is None:
            reasons.append("a file whose content couldn't be checked")
            continue
        for check in checkers:
            reasons.extend(check(text))
    return reasons


def tree_entries(api: Api, ref: str) -> dict[str, tuple[Any, Any]] | None:
    """Path -> (mode, blob SHA) for every file, link and submodule; None if the tree can't be listed whole."""
    tree = api.get_json(f"git/trees/{ref}", {"recursive": 1})
    entries = tree.get("tree")
    if tree.get("truncated") or not isinstance(entries, list):
        return None
    return {
        e["path"]: (e.get("mode"), e.get("sha"))
        for e in entries
        if isinstance(e, dict) and isinstance(e.get("path"), str) and e.get("type") != "tree"
    }


def assess(api: Api, head_sha: str, rules: list[Rule], bases: list[str]) -> list[str]:
    """
    Why the pull request needs a maintainer's review; empty if it doesn't.

    The changes are compared with each of `bases` (main, and the commit the test build was merged into,
    which may be an older main). Files that run during the build (workflows, Gradle, scripts, signing)
    must also be identical to main's, so the build ran only reviewed build logic.
    """
    reasons: list[str] = []
    head_tree, main_tree = tree_entries(api, head_sha), tree_entries(api, MAIN)
    if head_tree is None or main_tree is None:
        reasons.append("a file tree too large to check")
    else:
        if any(mode in ("120000", "160000") for mode, _ in head_tree.values()):
            reasons.append("a symbolic link or submodule")
        build_rules = [r for r in rules if r.pattern in BUILD_PATTERNS and not r.exception]
        changed = (p for p in set(head_tree) | set(main_tree) if head_tree.get(p) != main_tree.get(p))
        if any(r.regex.match(p) for p in changed for r in build_rules):
            reasons.append("build files that differ from main's (updating the branch from main may help)")
    for base in dict.fromkeys(bases):
        try:
            compare = api.get_json(f"compare/{base}...{head_sha}", {"per_page": 1})
        except ApiError as e:
            if e.status in (404, 422):
                reasons.append("changes that couldn't be compared with main")
                continue
            raise
        files = compare.get("files")
        if not isinstance(files, list):
            reasons.append("changes that couldn't be compared with main")
            continue
        if len(files) >= MAX_COMPARE_FILES:
            reasons.append("more changed files than can be checked")
        reasons.extend(file_reasons(api, files, rules))
    return list(dict.fromkeys(reasons))


# --- Build verification ----------------------------------------------------------------------------


@dataclass(frozen=True)
class Build:
    run_id: int
    artifact_id: int
    apk_name: str
    apk_sha256: str


def run_path(run: dict[str, Any]) -> str:
    """The run's workflow file, without a `@ref` suffix should GitHub add one."""
    path = run.get("path")
    return path.split("@", 1)[0] if isinstance(path, str) else ""


def latest_run(api: Api, key: str, pr: dict[str, Any]) -> dict[str, Any] | None:
    """The newest pull-request run of one workflow for this pull request's head commit, repository and branch."""
    head = pr["head"]
    head_repo = (head.get("repo") or {}).get("full_name")
    path = WORKFLOWS[key]
    data = api.get_json(
        f"actions/workflows/{Path(path).name}/runs",
        {"head_sha": head["sha"], "event": "pull_request", "per_page": 50},
    )
    runs = [
        r
        for r in data.get("workflow_runs") or []
        if r.get("head_sha") == head["sha"]
        and run_path(r) == path
        and r.get("event") == "pull_request"
        and r.get("head_branch") == head.get("ref")
        and same_repo((r.get("head_repository") or {}).get("full_name"), head_repo or "")
        and type(r.get("id")) is int
    ]
    return max(runs, key=lambda r: r["id"]) if runs else None


def single_artifact(api: Api, run_id: int, name: str, limit: int) -> dict[str, Any]:
    data = api.get_json(f"actions/runs/{run_id}/artifacts", {"name": name, "per_page": 100})
    found = [a for a in data.get("artifacts") or [] if a.get("name") == name]
    if len(found) != 1:
        raise BuildError(f"expected exactly one {name} artifact")
    art = found[0]
    if art.get("expired") or type(art.get("id")) is not int:
        raise BuildError(f"the {name} artifact has expired")
    size = art.get("size_in_bytes")
    if type(size) is not int or size > limit:
        raise BuildError(f"the {name} artifact is too large")
    return art


def zip_entries(data: bytes, expected: set[str]) -> zipfile.ZipFile:
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        raise BuildError("an artifact isn't a valid zip") from None
    names = [i.filename for i in zf.infolist()]
    if len(names) != len(expected) or set(names) != expected:
        raise BuildError("an artifact has unexpected contents")
    return zf


def read_entry(zf: zipfile.ZipFile, name: str, limit: int, digest: Any = None) -> bytes:
    info = zf.getinfo(name)
    if info.file_size > limit or info.flag_bits & 0x1:
        raise BuildError("an artifact entry is too large or encrypted")
    out, total = [], 0
    with zf.open(info) as f:
        while chunk := f.read(1 << 16):
            total += len(chunk)
            if total > limit:
                raise BuildError("an artifact entry is too large")
            if digest is not None:
                digest.update(chunk)
            else:
                out.append(chunk)
    return b"".join(out)


def validate_metadata(raw: bytes, pr_number: int, head_sha: str) -> dict[str, Any]:
    if len(raw) > MAX_METADATA:
        raise BuildError("the build metadata is too large")
    try:
        meta = strict_json(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError, RecursionError):
        raise BuildError("the build metadata isn't valid JSON") from None
    if not isinstance(meta, dict) or set(meta) != META_KEYS:
        raise BuildError("the build metadata has unexpected fields")
    if type(meta["schema"]) is not int or meta["schema"] != META_SCHEMA:
        raise BuildError("the build metadata has an unknown schema")
    if type(meta["pr"]) is not int or meta["pr"] != pr_number:
        raise BuildError("the build metadata names another pull request")
    for key in ("head_sha", "built_sha"):
        if not isinstance(meta[key], str) or not SHA_RE.match(meta[key]):
            raise BuildError("the build metadata has a malformed commit")
    if meta["head_sha"] != head_sha:
        raise BuildError("the build metadata names another commit")
    if meta["base_ref"] != MAIN:
        raise BuildError("the build wasn't made against main")
    if not isinstance(meta["apk_sha256"], str) or not SHA256_RE.match(meta["apk_sha256"]):
        raise BuildError("the build metadata has a malformed hash")
    return meta


def load_metadata(api: Api, run: dict[str, Any], pr_number: int, head_sha: str) -> dict[str, Any]:
    art = single_artifact(api, run["id"], "pr-metadata", MAX_METADATA_ZIP)
    data = api.download(f"actions/artifacts/{art['id']}/zip", MAX_METADATA_ZIP)
    zf = zip_entries(data, {"pr-metadata.json"})
    return validate_metadata(read_entry(zf, "pr-metadata.json", MAX_METADATA), pr_number, head_sha)


def merge_base_of_build(api: Api, built_sha: str, head_sha: str) -> str:
    """
    The commit the build merged this head into. The build must be a merge of this head into a commit
    that main contains; that commit can be older than main's current head.
    """
    try:
        commit = api.get_json(f"commits/{built_sha}", {"per_page": 1})
        parents = [p.get("sha") for p in commit.get("parents") or []]
        if len(parents) != 2 or parents[1] != head_sha or not SHA_RE.match(parents[0] or ""):
            raise BuildError("the build wasn't a merge of this commit")
        compare = api.get_json(f"compare/{parents[0]}...{MAIN}", {"per_page": 1})
    except ApiError:
        raise BuildError("the built merge commit couldn't be checked") from None
    if compare.get("status") not in ("ahead", "identical"):
        raise BuildError("the build's base isn't part of main")
    return parents[0]


def verify_apk(api: Api, run: dict[str, Any], pr_number: int, meta: dict[str, Any]) -> Build:
    apk_name = f"engwe-speed-tool-test-pr{pr_number}.apk"
    art = single_artifact(api, run["id"], f"test-apk-pr{pr_number}", MAX_APK_ZIP)
    # Kept in memory only: nothing from the artifact is written to disk, let alone to the checkout.
    data = api.download(f"actions/artifacts/{art['id']}/zip", MAX_APK_ZIP)
    zf = zip_entries(data, {apk_name, "SHA256SUMS"})
    digest = hashlib.sha256()
    read_entry(zf, apk_name, MAX_APK_ZIP, digest)
    apk_sha256 = digest.hexdigest()
    if read_entry(zf, "SHA256SUMS", 1024) != f"{apk_sha256}  {apk_name}\n".encode():
        raise BuildError("the APK doesn't match its checksum file")
    if meta["apk_sha256"] != apk_sha256:
        raise BuildError("the APK doesn't match the build metadata")
    return Build(run["id"], art["id"], apk_name, apk_sha256)


# --- The comment -----------------------------------------------------------------------------------


@dataclass(frozen=True)
class Marker:
    state: str
    sha: str
    approved: str | None


def parse_marker(body: str) -> Marker | None:
    m = MARKER_RE.match(body)
    if not m:
        return None
    return Marker(m.group(1), m.group(2), None if m.group(3) == "none" else m.group(3))


def find_comments(api: Api, pr_number: int) -> list[dict[str, Any]]:
    """The bot's own comments with a marker. Comments by anyone else are ignored, whatever they contain."""
    found = []
    for page in range(1, 31):
        items = api.get_json(f"issues/{pr_number}/comments", {"per_page": 100, "page": page})
        for c in items:
            user = c.get("user") or {}
            if user.get("login") == BOT_LOGIN and user.get("type") == "Bot" and parse_marker(c.get("body") or ""):
                found.append(c)
        if len(items) < 100:
            break
    return found


def role_of(api: Api, login: Any) -> str | None:
    if not isinstance(login, str) or not login:
        return None
    try:
        return api.get_json(f"collaborators/{urllib.parse.quote(login, safe='')}/permission").get("role_name")
    except ApiError as e:
        if e.status == 404:
            return None
        raise


def label_added_by_maintainer(api: Api, pr_number: int) -> bool:
    """Whether the approval label was last added by someone who may approve. Checked on every use, so a
    marker or label written with another workflow's token doesn't count."""
    last = None
    for page in range(1, 31):
        items = api.get_json(f"issues/{pr_number}/events", {"per_page": 100, "page": page})
        for e in items:
            if e.get("event") == "labeled" and (e.get("label") or {}).get("name") == LABEL:
                last = e
        if len(items) < 100:
            break
    return last is not None and role_of(api, (last.get("actor") or {}).get("login")) in APPROVER_ROLES


@dataclass(frozen=True)
class Outcome:
    state: str
    reasons: tuple[str, ...] = ()
    build: Build | None = None
    problem: str | None = None


def render(
    outcome: Outcome,
    *,
    pr_number: int,
    head_sha: str,
    approved: str | None,
    server: str,
    repo: str,
    note: str | None = None,
) -> str:
    lines = [
        f"<!-- test-build-comment state={outcome.state} sha={head_sha} approved={approved or 'none'} -->",
        "### Test build",
        "",
    ]
    if outcome.state == "pending":
        lines.append(f"Checks are running for commit `{head_sha}`. This comment is updated when they finish.")
    elif outcome.state == "failed":
        lines.append(f"No test build for commit `{head_sha}`: the checks didn't pass.")
    elif outcome.state == "not-main":
        lines.append("Test builds are offered only for pull requests into `main`.")
    elif outcome.state == "withheld":
        lines += [
            "**Waiting for maintainer safety review.** This pull request changes files that need a maintainer's",
            "review before a test build is offered:",
            "",
        ]
        shown = list(outcome.reasons[:MAX_REASONS])
        lines += [f"- {r}" for r in shown]
        if len(outcome.reasons) > len(shown):
            lines.append(f"- and {len(outcome.reasons) - len(shown)} more")
        lines += [
            "",
            f"A maintainer approves commit `{head_sha}` by adding the `{LABEL}` label. A new push removes the",
            "approval.",
        ]
    elif outcome.state == "unverifiable":
        lines += [
            f"The test build for commit `{head_sha}` couldn't be verified ({outcome.problem}), so no download",
            "link is offered. Re-running the Test build workflow may help.",
        ]
    elif outcome.state == "ready" and outcome.build is not None:
        b = outcome.build
        artifact_url = f"{server}/{repo}/actions/runs/{b.run_id}/artifacts/{b.artifact_id}"
        form_url = (
            f"{server}/{repo}/issues/new?template=compatibility_report.yml&build_tested="
            + urllib.parse.quote(f"PR #{pr_number}")
        )
        lines += [
            "> [!WARNING]",
            "> **Unreviewed test build.** Anyone can open a pull request. Install this build only if you trust",
            "> the change, and use it on private land at your own risk.",
            "",
        ]
        if approved == head_sha:
            lines += [
                "A maintainer allowed a test build of this commit after checking its safety-relevant changes.",
                "That isn't a full review.",
                "",
            ]
        lines += [
            "| | |",
            "| --- | --- |",
            f"| Commit | `{head_sha}` |",
            f"| Download | [{b.apk_name}.zip]({artifact_url}) (needs a GitHub login; expires 30 days after the build) |",
            f"| APK SHA-256 | `{b.apk_sha256}` |",
            "",
            "**Install**",
            "1. Download the zip from the link above and unzip it.",
            "2. Check that the APK's SHA-256 matches the value above, for example with `sha256sum` on a",
            "   computer. Install only an APK from this link.",
            "3. Open the APK on your Android phone and allow the install when asked. It installs next to the",
            "   release app as **TEST ENGWE Speed Tool**.",
            f"4. Every screen shows the banner **TEST BUILD · PR #{pr_number} · not reviewed**. If it doesn't,",
            "   uninstall the app.",
            "",
            "**Test**",
            "- Follow the test steps in the pull request description.",
            f'- Report what you saw with the [compatibility form]({form_url}). It fills in "PR #{pr_number}"',
            "  as the build tested.",
            "",
            "**Afterwards**",
            "",
            'Uninstall the test app. Its signing key is public, so anyone can sign an "update" for it: never',
            "install one from anywhere else.",
        ]
    else:
        raise ValueError(f"unknown state {outcome.state}")
    if note:
        lines += ["", f"_{note}_"]
    return "\n".join(lines) + "\n"


# --- Decisions -------------------------------------------------------------------------------------


@dataclass
class Context:
    api: Api
    rules: list[Rule]
    server: str

    @property
    def repo(self) -> str:
        return self.api.repo


def same_repo(a: Any, b: str) -> bool:
    return isinstance(a, str) and a.lower() == b.lower()


def decide(ctx: Context, pr: dict[str, Any], head_sha: str, approved: str | None) -> Outcome:
    if pr["base"]["ref"] != MAIN:
        return Outcome("not-main")
    runs = [latest_run(ctx.api, key, pr) for key in ("ci", "build")]
    if any(r is None or r.get("status") != "completed" for r in runs):
        return Outcome("pending")
    if any(r.get("conclusion") != "success" for r in runs):
        return Outcome("failed")
    try:
        meta = load_metadata(ctx.api, runs[1], pr["number"], head_sha)
        build_base = merge_base_of_build(ctx.api, meta["built_sha"], head_sha)
    except BuildError as e:
        return Outcome("unverifiable", problem=str(e))
    reasons = assess(ctx.api, head_sha, ctx.rules, [MAIN, build_base])
    if reasons and approved != head_sha:
        return Outcome("withheld", tuple(reasons))
    try:
        build = verify_apk(ctx.api, runs[1], pr["number"], meta)
    except BuildError as e:
        return Outcome("unverifiable", problem=str(e))
    return Outcome("ready", build=build)


NOTE_LABEL_OUTDATED = "The approval label was removed: the pull request changed after it was added."
NOTE_SEVERAL_COMMENTS = "There is more than one test-build comment, so approvals are ignored."


def update(
    ctx: Context,
    pr_number: int,
    *,
    expected_head: str | None = None,
    approve: str | None = None,
    note: str | None = None,
) -> str | None:
    """Recompute and write the comment for the pull request's current head. Returns the state."""
    pr = ctx.api.get_json(f"pulls/{pr_number}")
    head = pr["head"]["sha"]
    if pr.get("state") != "open" or not same_repo(pr["base"]["repo"]["full_name"], ctx.repo):
        log(f"#{pr_number}: not open in this repository")
        return None
    if not SHA_RE.match(head) or (expected_head is not None and head != expected_head):
        log(f"#{pr_number}: the head moved on; a later event handles it")
        return None
    comments = find_comments(ctx.api, pr_number)
    comment = comments[0] if comments else None
    marker = parse_marker(comment["body"]) if comment else None
    labelled = LABEL in {lbl.get("name") for lbl in pr.get("labels") or []}
    approved = None
    if len(comments) > 1:
        note = note or NOTE_SEVERAL_COMMENTS
    elif approve == head:
        approved = head
    elif (
        marker
        and marker.sha == head
        and marker.approved == head
        and labelled
        and label_added_by_maintainer(ctx.api, pr_number)
    ):
        approved = head
    if labelled and approve is None and (marker is None or marker.sha != head):
        # The label belongs to an earlier commit (the push event that should have removed it was dropped).
        remove_label(ctx, pr_number)
        approved = None
        note = note or NOTE_LABEL_OUTDATED
    outcome = decide(ctx, pr, head, approved)
    if ctx.api.get_json(f"pulls/{pr_number}")["head"]["sha"] != head:
        log(f"#{pr_number}: the head moved on while checking; a later event handles it")
        return None
    body = render(
        outcome, pr_number=pr_number, head_sha=head, approved=approved, server=ctx.server, repo=ctx.repo, note=note
    )
    if comment is None:
        ctx.api.post_json(f"issues/{pr_number}/comments", {"body": body})
    elif comment.get("body") != body:
        ctx.api.patch_json(f"issues/comments/{comment['id']}", {"body": body})
    log(f"#{pr_number} {head}: {outcome.state}" + (" (approved)" if approved else ""))
    return outcome.state


def remove_label(ctx: Context, pr_number: int) -> None:
    if ctx.api.delete(f"issues/{pr_number}/labels/{LABEL}"):
        log(f"#{pr_number}: removed the {LABEL} label")


def find_pull_requests(ctx: Context, run: dict[str, Any], head_sha: str) -> list[int]:
    """Open pull requests in this repository whose head is `head_sha`, confirmed through the API."""
    candidates = {p.get("number") for p in run.get("pull_requests") or []}
    head_repo = run.get("head_repository") or {}
    owner = (head_repo.get("owner") or {}).get("login")
    branch = run.get("head_branch")
    if isinstance(owner, str) and isinstance(branch, str) and owner and branch:
        for p in ctx.api.get_json("pulls", {"state": "open", "head": f"{owner}:{branch}", "per_page": 100}):
            candidates.add(p.get("number"))
    found = []
    for number in sorted(n for n in candidates if type(n) is int):
        pr = ctx.api.get_json(f"pulls/{number}")
        if (
            pr.get("state") == "open"
            and pr["head"]["sha"] == head_sha
            and same_repo(pr["base"]["repo"]["full_name"], ctx.repo)
        ):
            found.append(number)
    return found


def handle_workflow_run(ctx: Context, event: dict[str, Any]) -> None:
    run = event.get("workflow_run") or {}
    if run_path(run) not in WORKFLOWS.values() or run.get("event") != "pull_request":
        log("not a pull request run of a test-build workflow")
        return
    if not same_repo((run.get("repository") or {}).get("full_name"), ctx.repo):
        log("a run of another repository")
        return
    head = run.get("head_sha")
    if not isinstance(head, str) or not SHA_RE.match(head):
        log("malformed head commit")
        return
    numbers = find_pull_requests(ctx, run, head)
    if len(numbers) != 1:
        log(f"expected one open pull request for {head}, found {len(numbers)}")
        return
    update(ctx, numbers[0], expected_head=head)


NOTE_NOT_MAINTAINER = "The approval label was removed: only maintainers can approve a test build."
NOTE_STALE_LABEL = (
    "The approval label was removed: approve only once this comment asks for a safety review of the "
    "pull request's current commit, and check that it names the commit you reviewed."
)


def handle_pull_request_target(ctx: Context, event: dict[str, Any]) -> None:
    action = event.get("action")
    pr = event.get("pull_request") or {}
    number = pr.get("number")
    head = (pr.get("head") or {}).get("sha")
    if type(number) is not int or not isinstance(head, str) or not SHA_RE.match(head):
        log("malformed pull request event")
        return
    label = (event.get("label") or {}).get("name")
    if action in ("opened", "reopened", "synchronize"):
        remove_label(ctx, number)
        update(ctx, number, expected_head=head)
    elif action == "labeled" and label == LABEL:
        if role_of(ctx.api, (event.get("sender") or {}).get("login")) not in APPROVER_ROLES:
            remove_label(ctx, number)
            update(ctx, number, note=NOTE_NOT_MAINTAINER)
            return
        current = ctx.api.get_json(f"pulls/{number}")["head"]["sha"]
        comments = find_comments(ctx.api, number)
        marker = parse_marker(comments[0]["body"]) if len(comments) == 1 else None
        if current != head or marker is None or marker.sha != head or marker.state != "withheld":
            remove_label(ctx, number)
            update(ctx, number, note=NOTE_STALE_LABEL)
            return
        update(ctx, number, expected_head=head, approve=head)
    elif action == "unlabeled" and label == LABEL:
        update(ctx, number)
    else:
        log(f"nothing to do for {action}")


def main() -> int:
    event_name = os.environ["GITHUB_EVENT_NAME"]
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    api = Api(
        os.environ["GH_TOKEN"],
        os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        os.environ["GITHUB_REPOSITORY"],
    )
    rules = parse_rules(RULES_FILE.read_text(encoding="utf-8"))
    ctx = Context(api, rules, os.environ.get("GITHUB_SERVER_URL", "https://github.com"))
    if event_name == "workflow_run":
        handle_workflow_run(ctx, event)
    elif event_name == "pull_request_target":
        handle_pull_request_target(ctx, event)
    else:
        log(f"unexpected event {event_name}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
