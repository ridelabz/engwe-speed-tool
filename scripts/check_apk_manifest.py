#!/usr/bin/env python3
"""Fails if an APK asks for more than the app needs or lets its data leave the phone.

Usage: check_apk_manifest.py [--release] <apk> [<apk> ...]   (needs ANDROID_HOME with build-tools)

- Permissions: only BLUETOOTH_SCAN (with neverForLocation), BLUETOOTH_CONNECT and the app-private
  DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION that AndroidX declares. Every `uses-permission*` element
  counts, and the app may declare no other permission, group or tree.
- Only attributes in the Android namespace are read; a duplicate attribute fails the check.
- The application has allowBackup=false and data-extraction rules that exclude every domain from
  both cloud backup and device-to-device transfer.
- No sharedUserId. With --release: not debuggable.
"""

import os
import re
import subprocess
import sys
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android:"
NEVER_FOR_LOCATION = 0x10000
REQUIRED = {"android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"}
DOMAINS = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"}
ATTR = re.compile(r"^\s*A: (\S+?)(?:\(0x[0-9a-f]+\))?=(.*)$")
ELEM = re.compile(r"^\s*E: (\S+)")


class ManifestError(Exception):
    pass


def parse_xmltree(text: str) -> list[tuple[str, dict[str, str]]]:
    """Elements in document order, each with attributes keyed by qualified name."""
    result: list[tuple[str, dict[str, str]]] = []
    for line in text.splitlines():
        if m := ELEM.match(line):
            if ":" in m.group(1):
                # Android matches elements by local name, so a namespaced element could act as a real one.
                raise ManifestError(f"namespaced element <{m.group(1)}> not allowed")
            result.append((m.group(1), {}))
        elif (m := ATTR.match(line)) and result:
            attrs = result[-1][1]
            if m.group(1) in attrs:
                raise ManifestError(f"duplicate attribute {m.group(1)} on <{result[-1][0]}>")
            attrs[m.group(1)] = m.group(2)
    return result


def value(raw: str | None) -> str:
    if raw is None:
        return ""
    raw = raw.strip()
    if raw.startswith('"'):
        return raw.split('"')[1]
    if m := re.search(r"0x([0-9a-f]+)", raw):
        return str(int(m.group(1), 16))
    return raw


def check_manifest(manifest: str, rules: list[str], release: bool) -> list[str]:
    """``rules``: every configuration variant of the data-extraction rules file (xmltree text)."""
    elems = parse_xmltree(manifest)
    a = lambda attrs, name: attrs.get(ANDROID + name)  # noqa: E731
    manifest_attrs = next((attrs for e, attrs in elems if e == "manifest"), {})
    package = value(manifest_attrs.get("package"))
    private_permission = f"{package}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    allowed = REQUIRED | {private_permission}
    problems = []
    if a(manifest_attrs, "sharedUserId") is not None:
        problems.append("sharedUserId is not allowed")
    used: dict[str, dict[str, str]] = {}
    for e, attrs in elems:
        if e.startswith("uses-permission"):
            used[value(a(attrs, "name"))] = attrs
        elif e in ("permission", "permission-group", "permission-tree"):
            if not (e == "permission" and value(a(attrs, "name")) == private_permission):
                problems.append(f"declares <{e}> {value(a(attrs, 'name'))}")
    for name in sorted(set(used) - allowed):
        problems.append(f"permission not allowed: {name}")
    for name in sorted(REQUIRED - set(used)):
        problems.append(f"missing permission: {name}")
    scan = used.get("android.permission.BLUETOOTH_SCAN")
    if scan is not None:
        flags = int(value(a(scan, "usesPermissionFlags")) or 0)
        if not flags & NEVER_FOR_LOCATION:
            problems.append("BLUETOOTH_SCAN must declare usesPermissionFlags=neverForLocation")
    app = next((attrs for e, attrs in elems if e == "application"), {})
    if value(a(app, "allowBackup")) not in ("0", "false"):
        problems.append("application must set allowBackup=false")
    if release and value(a(app, "debuggable")) not in ("", "0", "false"):
        problems.append("release-like builds must not be debuggable")
    if a(app, "dataExtractionRules") is None:
        problems.append("application must declare dataExtractionRules")
    elif not rules:
        problems.append("data-extraction rules file not found")
    else:
        for variant in rules:
            problems += check_rules(variant)
    return problems


def check_rules(rules: str) -> list[str]:
    problems = []
    elems = parse_xmltree(rules)
    sections = ("cloud-backup", "device-transfer", "cross-platform-transfer")
    for section in ("cloud-backup", "device-transfer"):
        starts = [i for i, (e, _) in enumerate(elems) if e == section]
        if len(starts) != 1:
            problems.append(f"data-extraction rules must have exactly one <{section}>, found {len(starts)}")
        for start in starts:
            end = next((i for i, (e, _) in enumerate(elems) if i > start and e in sections), len(elems))
            body = elems[start + 1 : end]
            if any(e == "include" for e, _ in body):
                problems.append(f"<{section}> must not include anything")
            excluded = {value(attrs.get("domain")) for e, attrs in body if e == "exclude" and value(attrs.get("path")) == "."}
            for domain in sorted(DOMAINS - excluded):
                problems.append(f"<{section}> does not exclude domain {domain}")
    if any(e == "cross-platform-transfer" for e, _ in elems):
        problems.append("cross-platform transfer must not be configured")
    return problems


def aapt2() -> str:
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools"
    key = lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)]  # noqa: E731
    return str(sorted((p for p in tools.iterdir() if (p / "aapt2").exists()), key=key)[-1] / "aapt2")


def run(*args: str) -> str:
    return subprocess.run([aapt2(), *args], check=True, capture_output=True, text=True).stdout


def rules_resource_id(manifest: str) -> str | None:
    app = next((attrs for e, attrs in parse_xmltree(manifest) if e == "application"), {})
    ref = re.search(r"@0x([0-9a-f]+)", app.get(ANDROID + "dataExtractionRules", ""))
    return "0x" + ref.group(1) if ref else None


def resource_files(resources_dump: str, res_id: str) -> list[str]:
    """Every configuration variant's file for a resource in `aapt2 dump resources` output."""
    files = []
    inside = False
    for line in resources_dump.splitlines():
        stripped = line.strip()
        if stripped.startswith("resource "):
            inside = stripped.startswith(f"resource {res_id} ")
        elif stripped.startswith("type ") or stripped.startswith("Package ") or stripped.startswith("Flag "):
            inside = False
        elif inside and (m := re.search(r"\(file\) (\S+)", stripped)):
            files.append(m.group(1))
    return files


def check_apk(apk: str, release: bool) -> list[str]:
    manifest = run("dump", "xmltree", "--file", "AndroidManifest.xml", apk)
    res_id = rules_resource_id(manifest)
    paths = resource_files(run("dump", "resources", apk), res_id) if res_id else []
    rules = [run("dump", "xmltree", "--file", path, apk) for path in paths]
    return check_manifest(manifest, rules, release)


def main(argv: list[str]) -> int:
    release = "--release" in argv
    apks = [a for a in argv if a != "--release"]
    failed = False
    for apk in apks:
        try:
            problems = check_apk(apk, release)
        except ManifestError as e:
            problems = [str(e)]
        print(f"{apk}: {'OK' if not problems else 'FAILED'}")
        for p in problems:
            print(f"  - {p}")
        failed |= bool(problems)
    return 1 if failed or not apks else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
