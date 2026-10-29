#!/usr/bin/env python3
"""Triage of new and edited issues: labels from the issue forms' answers, and requests for what's missing.

Runs from main on `issues` events (never code from a pull request). The issue's text is untrusted input: it
is read here as data from the event file, never placed in a shell command or a workflow expression, and only
fixed texts and labels built from a short list of allowed characters are written back.

- Labels from the answers: `state:unverified`, and `model:<model identity>` and `firmware:<IoT version>` only
  when a maintainer has created that label: text anyone can type never becomes a new label.
- A compatibility or problem report without a session log, or a compatibility report without the model
  identity, gets the `needs-logs` label and one comment saying what's missing; the label (and `stale`) goes
  once both are there.
- An issue labelled `question` gets one comment pointing to Discussions, and is closed.

Environment: GH_TOKEN, GITHUB_EVENT_PATH, GITHUB_REPOSITORY, GITHUB_API_URL, GITHUB_SERVER_URL.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any

# The forms' field labels the triage reads, by the field's ID in the forms (a test keeps them in step).
FIELDS = {
    "model_identity": "Model identity (filled in by the app)",
    "verification": "Verification shown by the app (filled in by the app)",
    "iot_firmware": "IoT firmware version",
    "log": "Session log",
}
COMPATIBILITY = "compatibility"
PROBLEM = "bug"
QUESTION = "question"
NEEDS_LOGS = "needs-logs"
STALE = "stale"
BOT_LOGIN = "github-actions[bot]"
MAX_RESPONSE = 4 << 20
UNVERIFIED = "state:unverified"
NO_RESPONSE = "_No response_"
MAX_LABEL = 50
MAX_BODY = 65536

NEEDS_LOGS_MARKER = "<!-- issue-triage:needs-logs -->"
QUESTION_MARKER = "<!-- issue-triage:question -->"
LABEL_COLOURS = {"model": "1d76db", "firmware": "5319e7", "state": "fbca04", NEEDS_LOGS: "d93f0b"}


def log(message: str) -> None:
    print(f"issue-triage: {message}", flush=True)


# --- Reading the form's answers --------------------------------------------------------------------


def answers(body: str) -> dict[str, str]:
    """The answers of a form-rendered issue body: each `### <label>` heading and the text under it."""
    found: dict[str, str] = {}
    label: str | None = None
    lines: list[str] = []
    for line in body[:MAX_BODY].splitlines():
        if line.startswith("### ") and line[4:].strip():
            if label is not None:
                found[label] = "\n".join(lines).strip()
            label, lines = line[4:].strip(), []
        elif label is not None:
            lines.append(line)
    if label is not None:
        found[label] = "\n".join(lines).strip()
    return {k: v for k, v in found.items() if v and v != NO_RESPONSE}


def answer(found: dict[str, str], field: str) -> str | None:
    return found.get(FIELDS[field])


# Shapes of identifiers, as the app refuses them: a Bluetooth address (any separator), a run of 12 or more hex
# digits, an advertised name's suffix (ENGWE, then a dash, a space or nothing, then 1-4 hex digits), 8 or more
# digits in a row.
_IDENTIFYING = [
    re.compile(r"[0-9A-Fa-f]{2}(?:[:\-. ][0-9A-Fa-f]{2}){5}"),
    re.compile(r"[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}\.[0-9A-Fa-f]{4}"),
    re.compile(r"[0-9A-Fa-f]{12,}"),
    re.compile(r"(?i)ENGWE[- ]?[0-9a-f]{1,4}(?![0-9a-z])"),
    re.compile(r"[0-9]{8,}"),
]


def _label_part(text: str, limit: int) -> str | None:
    """[text] as a label part: only letters, digits, `.`, `_` and `-`, within [limit], nothing shaped like an
    identifier; else None."""
    value = text.strip()
    if not value or len(value) > limit or not re.fullmatch(r"[A-Za-z0-9._-]+", value):
        return None
    if any(p.search(value) for p in _IDENTIFYING):
        return None
    return value


def model_label(found: dict[str, str]) -> str | None:
    model = answer(found, "model_identity")
    part = model and _label_part(model, MAX_LABEL - len("model:"))
    return f"model:{part}" if part else None


def firmware_label(found: dict[str, str]) -> str | None:
    firmware = answer(found, "iot_firmware")
    part = firmware and _label_part(firmware, 20)
    # A version has a digit; anything else isn't a version.
    return f"firmware:{part}" if part and any(c.isdigit() for c in part) else None


def has_model(found: dict[str, str]) -> bool:
    return bool((answer(found, "model_identity") or "").strip())


def unverified(found: dict[str, str]) -> bool:
    value = answer(found, "verification")
    return value is not None and value.strip().lower().startswith("unverified")


def has_log(found: dict[str, str]) -> bool:
    """An attachment link in the log's answer (GitHub's upload field leaves one)."""
    value = answer(found, "log") or ""
    return bool(re.search(r"https://github\.com/(?:user-attachments/(?:files|assets)|[\w.-]+/[\w.-]+/files)/", value))


@dataclass(frozen=True)
class Triage:
    add: frozenset[str]
    remove: frozenset[str]
    missing: tuple[str, ...]
    # Labels added only if they already exist (a maintainer created them): never made from issue text.
    if_existing: frozenset[str] = frozenset()


def triage(labels: set[str], body: str) -> Triage:
    """What to change on an issue with [labels] and [body]."""
    found = answers(body)
    add: set[str] = set()
    remove: set[str] = set()
    existing: set[str] = set()
    missing: list[str] = []
    report = COMPATIBILITY in labels or PROBLEM in labels
    if report:
        for label in (model_label(found), firmware_label(found)):
            if label:
                existing.add(label)
        if unverified(found):
            add.add(UNVERIFIED)
        if not has_log(found):
            missing.append("the session log")
        if COMPATIBILITY in labels and not has_model(found):
            missing.append("the model identity")
        if missing:
            add.add(NEEDS_LOGS)
        else:
            remove.update({NEEDS_LOGS, STALE} & labels)
    return Triage(frozenset(add - labels), frozenset(remove), tuple(missing), frozenset(existing - labels))


def needs_logs_comment(missing: tuple[str, ...]) -> str:
    what = " and ".join(missing)
    return (
        f"{NEEDS_LOGS_MARKER}\nThanks for the report. It's missing {what}. In the app, open **Report** (or "
        "**Diagnostics**), save the session log after checking its preview, and edit this issue to attach "
        "it; the app also fills in the model identity. Reports without a log can't count towards "
        "verifying a model.\n\nThis project is community-supported: there is no individual support."
    )


def question_comment(server: str, repo: str) -> str:
    return (
        f"{QUESTION_MARKER}\nThanks for asking. Questions are answered in "
        f"[Discussions]({server}/{repo}/discussions), where other riders can help too, so this issue is "
        "closed. Bugs and compatibility reports are welcome as issues.\n\n"
        "This project is community-supported: there is no individual support."
    )


# --- GitHub REST API -------------------------------------------------------------------------------


class ApiError(Exception):
    def __init__(self, status: int, what: str) -> None:
        super().__init__(f"HTTP {status} for {what}")
        self.status = status


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
    """Minimal REST client: no redirects (the token never goes elsewhere), bounded responses."""

    def __init__(self, token: str, api_url: str, repo: str) -> None:
        self._token = token
        self._base = api_url.rstrip("/")
        self.repo = repo
        self._opener = urllib.request.build_opener(_NoRedirect)

    def call(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
        headers = {
            "User-Agent": "issue-triage",
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self._token}",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        url = f"{self._base}/repos/{self.repo}/{path}"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            resp = self._opener.open(req, timeout=60)
        except urllib.error.HTTPError as e:
            e.close()
            return e.code, None
        with resp:
            raw = _read_limited(resp, MAX_RESPONSE)
            return resp.status, json.loads(raw) if raw else None


def _quoted(label: str) -> str:
    return urllib.parse.quote(label, safe="")


def label_exists(api: Api, label: str) -> bool:
    status, _ = api.call("GET", f"labels/{_quoted(label)}")
    if status not in (200, 404):
        raise ApiError(status, "a label")
    return status == 200


def ensure_label(api: Api, label: str) -> None:
    if label_exists(api, label):
        return
    colour = LABEL_COLOURS.get(label.split(":")[0], LABEL_COLOURS.get(label, "ededed"))
    status, _ = api.call("POST", "labels", {"name": label, "color": colour})
    if status not in (201, 422):  # 422: created meanwhile
        raise ApiError(status, "a label")


def has_comment(api: Api, number: int, marker: str) -> bool:
    page = 1
    while page <= 10:
        status, comments = api.call("GET", f"issues/{number}/comments?per_page=100&page={page}")
        if status != 200:
            raise ApiError(status, "the comments")
        if any(marker in (c.get("body") or "") and (c.get("user") or {}).get("login") == BOT_LOGIN for c in comments):
            return True
        if len(comments) < 100:
            return False
        page += 1
    return False


def comment_once(api: Api, number: int, marker: str, text: str) -> None:
    if has_comment(api, number, marker):
        return
    status, _ = api.call("POST", f"issues/{number}/comments", {"body": text})
    if status != 201:
        raise ApiError(status, "a comment")


def run(api: Api, event: dict[str, Any], server: str) -> None:
    issue = event.get("issue") or {}
    if issue.get("pull_request") is not None:
        return
    number = int(issue["number"])
    labels = {str(label.get("name")) for label in issue.get("labels") or []}
    action = event.get("action")
    if action == "labeled" and (event.get("label") or {}).get("name") == QUESTION:
        comment_once(api, number, QUESTION_MARKER, question_comment(server, api.repo))
        status, _ = api.call("PATCH", f"issues/{number}", {"state": "closed", "state_reason": "not_planned"})
        if status != 200:
            raise ApiError(status, "closing the issue")
        return
    if action not in ("opened", "edited", "reopened"):
        return
    result = triage(labels, str(issue.get("body") or ""))
    for label in sorted(result.add):
        ensure_label(api, label)
    add = sorted(result.add | {label for label in result.if_existing if label_exists(api, label)})
    if add:
        status, _ = api.call("POST", f"issues/{number}/labels", {"labels": add})
        if status != 200:
            raise ApiError(status, "adding labels")
    for label in sorted(result.remove):
        status, _ = api.call("DELETE", f"issues/{number}/labels/{_quoted(label)}")
        if status not in (200, 404):
            raise ApiError(status, "removing a label")
    if result.missing:
        comment_once(api, number, NEEDS_LOGS_MARKER, needs_logs_comment(result.missing))
    # Counts only: the labels may come from what someone typed, and this log is public.
    log(f"issue {number}: {len(add)} label(s) added, {len(result.remove)} removed, {len(result.missing)} missing")


def main() -> int:
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as f:
        event = json.load(f)
    api = Api(os.environ["GH_TOKEN"], os.environ.get("GITHUB_API_URL", "https://api.github.com"), os.environ["GITHUB_REPOSITORY"])
    run(api, event, os.environ.get("GITHUB_SERVER_URL", "https://github.com"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
