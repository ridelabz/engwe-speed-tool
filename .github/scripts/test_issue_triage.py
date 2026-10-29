"""Tests of the issue triage: answers read from a form-rendered body, labels, requests, and the API calls."""

from __future__ import annotations

import re
import sys
import unittest
import urllib.parse
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))

import issue_triage as t  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def body(**answers: str) -> str:
    """An issue body as GitHub renders a form: `### <label>`, a blank line, the answer."""
    labels = {
        "bike_model": "Bike model",
        "model_identity": t.FIELDS["model_identity"],
        "verification": t.FIELDS["verification"],
        "iot_firmware": t.FIELDS["iot_firmware"],
        "log": t.FIELDS["log"],
    }
    parts = []
    for key, value in answers.items():
        parts.append(f"### {labels[key]}\n\n{value}\n")
    return "\n".join(parts)


LOG = "[engwe-speed-tool-2026-10-16-1.log](https://github.com/user-attachments/files/123/engwe-speed-tool-2026-10-16-1.log)"


class TriageTest(unittest.TestCase):
    def test_a_complete_compatibility_report_gets_its_labels(self) -> None:
        result = t.triage(
            {"compatibility"},
            body(
                bike_model="ENGWE E26 3.0 Pro",
                model_identity="ENGWE_Test_model1",
                verification="unverified",
                iot_firmware="218CAN",
                log=LOG,
            ),
        )
        self.assertEqual({"state:unverified"}, set(result.add))
        self.assertEqual({"model:ENGWE_Test_model1", "firmware:218CAN"}, set(result.if_existing))
        self.assertEqual((), result.missing)

    def test_a_report_without_its_log_or_model_is_asked_for_them(self) -> None:
        result = t.triage({"compatibility"}, body(model_identity="_No response_", log="_No response_"))
        self.assertEqual({"needs-logs"}, set(result.add))
        self.assertEqual(("the session log", "the model identity"), result.missing)
        problem = t.triage({"bug"}, body(log="I forgot"))
        self.assertEqual(("the session log",), problem.missing)

    def test_needs_logs_and_stale_go_once_both_are_there(self) -> None:
        result = t.triage({"compatibility", "needs-logs", "stale"}, body(model_identity="ENGWE_Test_model1", log=LOG))
        self.assertEqual({"needs-logs", "stale"}, set(result.remove))
        self.assertNotIn("needs-logs", result.add)

    def test_a_model_that_cant_be_a_label_is_still_there(self) -> None:
        result = t.triage({"compatibility"}, body(model_identity="ENGWE/Test+model", log=LOG))
        self.assertEqual((), result.missing)
        self.assertEqual(frozenset(), result.if_existing)

    def test_untrusted_answers_never_become_labels(self) -> None:
        for value in [
            "ENGWE Test model",
            "$(curl evil)",
            "x" * 60,
            "ENGWE_ok\nsecond line",
            "`code`",
            "a,b",
            # Shaped like identifiers (made-up values): an address, its hex, a name suffix, a serial-like number.
            "F0-12-34-56-78-9A",
            "F0.12.34.56.78.9A",
            "F0123456789A",
            "f012.3456.789a",
            "ENGWE-9A",
            "ENGWE9A",
            "12345678",
        ]:
            with self.subTest(value=value):
                result = t.triage({"compatibility"}, body(model_identity=value, iot_firmware=value, log=LOG))
                labels = result.add | result.if_existing
                self.assertFalse(any(label.startswith(("model:", "firmware:")) for label in labels), labels)
        no_digit = t.triage({"bug"}, body(iot_firmware="latest", log=LOG))
        self.assertFalse(any(label.startswith("firmware:") for label in no_digit.add))

    def test_only_reports_are_triaged(self) -> None:
        result = t.triage({"enhancement"}, body(model_identity="ENGWE_Test_model1"))
        self.assertEqual(frozenset(), result.add)
        self.assertEqual((), result.missing)

    def test_a_verified_bike_gets_no_unverified_label(self) -> None:
        result = t.triage({"bug"}, body(verification="verified", log=LOG))
        self.assertNotIn("state:unverified", result.add)

    def test_a_log_is_an_attachment_link(self) -> None:
        self.assertTrue(t.has_log(t.answers(body(log=LOG))))
        self.assertFalse(t.has_log(t.answers(body(log="see https://example.com/files/log.log"))))


class FormsTest(unittest.TestCase):
    def test_the_labels_read_are_the_forms_labels(self) -> None:
        for form in ["compatibility_report.yml", "bug_report.yml"]:
            text = (ROOT / ".github/ISSUE_TEMPLATE" / form).read_text(encoding="utf-8")
            labels = dict(re.findall(r"\n    id: (\S+)\n    attributes:\n      label: (.+)\n", text))
            for field, label in t.FIELDS.items():
                with self.subTest(form=form, field=field):
                    self.assertEqual(label, labels.get(field))


class FakeApi:
    repo = "owner/repo"

    def __init__(self, comments: list[dict[str, Any]] | None = None, labels: set[str] | None = None) -> None:
        self.calls: list[tuple[str, str, Any]] = []
        self.comments = comments or []
        self.labels = labels or set()

    def call(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
        self.calls.append((method, path, body))
        if method == "GET" and path.startswith("labels/"):
            return (200, {}) if urllib.parse.unquote(path[len("labels/") :]) in self.labels else (404, None)
        if method == "GET" and "/comments" in path:
            return 200, self.comments
        if method == "POST" and path == "labels":
            return 201, {}
        if method == "POST" and path.endswith("/comments"):
            return 201, {}
        if method == "POST" and path.endswith("/labels"):
            return 200, []
        if method in ("PATCH", "DELETE"):
            return 200, {}
        return 500, None


def event(action: str, labels: list[str], text: str, label: str | None = None) -> dict[str, Any]:
    issue = {"number": 7, "labels": [{"name": n} for n in labels], "body": text}
    return {"action": action, "issue": issue, **({"label": {"name": label}} if label else {})}


class RunTest(unittest.TestCase):
    def test_labels_are_created_and_added_and_the_request_posted_once(self) -> None:
        api = FakeApi()
        t.run(api, event("opened", ["compatibility"], body(model_identity="ENGWE_Test_model1")), "https://github.com")
        methods = [(m, p) for m, p, _ in api.calls]
        self.assertIn(("POST", "labels"), methods)
        added = [b for m, p, b in api.calls if m == "POST" and p == "issues/7/labels"]
        self.assertEqual([{"labels": ["needs-logs"]}], added, "no model label nobody created")
        self.assertNotIn(("POST", "labels", {"name": "model:ENGWE_Test_model1", "color": "1d76db"}), api.calls)
        known = FakeApi(labels={"model:ENGWE_Test_model1"})
        t.run(known, event("opened", ["compatibility"], body(model_identity="ENGWE_Test_model1")), "https://github.com")
        added = [b for m, p, b in known.calls if m == "POST" and p == "issues/7/labels"]
        self.assertEqual([{"labels": ["model:ENGWE_Test_model1", "needs-logs"]}], added, "a label a maintainer made")
        comments = [b["body"] for m, p, b in api.calls if m == "POST" and p == "issues/7/comments"]
        self.assertEqual(1, len(comments))
        self.assertIn("the session log", comments[0])
        self.assertNotIn("ENGWE_Test_model1", comments[0], "nothing from the issue is echoed")

        bot = FakeApi([{"body": t.NEEDS_LOGS_MARKER, "user": {"login": "github-actions[bot]", "type": "Bot"}}])
        t.run(bot, event("edited", ["compatibility"], body(model_identity="ENGWE_Test_model1")), "https://github.com")
        self.assertFalse(any(m == "POST" and p == "issues/7/comments" for m, p, _ in bot.calls))

        # Neither a rider nor another bot can silence the request by quoting the marker.
        user = FakeApi(
            [
                {"body": t.NEEDS_LOGS_MARKER, "user": {"login": "rider", "type": "User"}},
                {"body": t.NEEDS_LOGS_MARKER, "user": {"login": "other[bot]", "type": "Bot"}},
            ],
        )
        t.run(user, event("opened", ["compatibility"], body()), "https://github.com")
        self.assertTrue(any(m == "POST" and p == "issues/7/comments" for m, p, _ in user.calls))

    def test_a_question_is_pointed_to_discussions_and_closed(self) -> None:
        api = FakeApi()
        t.run(api, event("labeled", ["question"], "How do I…?", label="question"), "https://github.com")
        comment = next(b["body"] for m, p, b in api.calls if m == "POST" and p == "issues/7/comments")
        self.assertIn("https://github.com/owner/repo/discussions", comment)
        self.assertIn(("PATCH", "issues/7", {"state": "closed", "state_reason": "not_planned"}), api.calls)

    def test_other_labels_and_pull_requests_change_nothing(self) -> None:
        api = FakeApi()
        t.run(api, event("labeled", ["compatibility"], body(), label="bug"), "https://github.com")
        pr = event("opened", ["compatibility"], body())
        pr["issue"]["pull_request"] = {}
        t.run(api, pr, "https://github.com")
        self.assertEqual([], api.calls)

    def test_comments_are_read_page_by_page(self) -> None:
        pages = [[{"body": "x", "user": {"login": "rider"}}] * 100, [{"body": t.NEEDS_LOGS_MARKER, "user": {"login": t.BOT_LOGIN}}]]

        class Paged(FakeApi):
            def call(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
                if method == "GET" and "/comments" in path:
                    page = int(urllib.parse.parse_qs(urllib.parse.urlparse(path).query)["page"][0])
                    return 200, pages[page - 1]
                return super().call(method, path, body)

        self.assertTrue(t.has_comment(Paged(), 7, t.NEEDS_LOGS_MARKER))

    def test_api_failures_name_no_issue_text(self) -> None:
        class Failing(FakeApi):
            def call(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
                return (500, None) if method == "POST" else super().call(method, path, body)

        with self.assertRaises(t.ApiError) as raised:
            t.run(Failing(), event("opened", ["bug"], body(verification="unverified secret-text")), "https://github.com")
        self.assertNotIn("secret-text", str(raised.exception))

    def test_the_heading_reader_is_linear(self) -> None:
        import time

        start = time.monotonic()
        t.answers("### " + " " * 60000 + "x\n" + "### a" + " " * 60000)
        self.assertLess(time.monotonic() - start, 1.0)


class WorkflowTest(unittest.TestCase):
    def test_the_issue_never_reaches_an_expression_and_permissions_are_narrow(self) -> None:
        text = (ROOT / ".github/workflows/issue-triage.yml").read_text(encoding="utf-8")
        expressions = re.findall(r"\$\{\{(.*?)\}\}", text)
        self.assertTrue(expressions)
        for expression in expressions:
            with self.subTest(expression=expression):
                self.assertRegex(
                    expression.strip(),
                    r"^(github\.token|github\.event\.issue\.number|github\.event\.action == 'labeled')$",
                )
        self.assertIn("permissions:\n  contents: read\n  issues: write\n", text)
        self.assertIn("ref: main", text)
        self.assertIn("persist-credentials: false", text)

    def test_the_stale_run_handles_the_triage_s_labels(self) -> None:
        text = (ROOT / ".github/workflows/stale.yml").read_text(encoding="utf-8")
        self.assertIn(f"only-issue-labels: {t.NEEDS_LOGS}\n", text)
        self.assertIn(f"stale-issue-label: {t.STALE}\n", text)


if __name__ == "__main__":
    unittest.main()
