"""Tests for check_apk_manifest.py using aapt2-style xmltree text (python3 -m unittest)."""

import unittest

from check_apk_manifest import DOMAINS, ManifestError, check_manifest, check_rules, resource_files

NS = "http://schemas.android.com/apk/res/android"
PKG = "io.github.ridelabz.engwespeedtool"


def manifest(*extra: str, app_attrs: str = "", manifest_attrs: str = "", scan_flags: str = "0x10000") -> str:
    lines = [
        "N: android=" + NS,
        "  E: manifest (line=2)",
        f'    A: package="{PKG}" (Raw: "{PKG}")',
        *[f"    {a}" for a in manifest_attrs.splitlines() if a],
        "    E: uses-permission (line=6)",
        f'      A: {NS}:name(0x01010003)="android.permission.BLUETOOTH_SCAN" (Raw: "x")',
        f"      A: {NS}:usesPermissionFlags(0x01010706)={scan_flags}",
        "    E: uses-permission (line=10)",
        f'      A: {NS}:name(0x01010003)="android.permission.BLUETOOTH_CONNECT" (Raw: "x")',
        "    E: permission (line=12)",
        f'      A: {NS}:name(0x01010003)="{PKG}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "x")',
        "    E: uses-permission (line=14)",
        f'      A: {NS}:name(0x01010003)="{PKG}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" (Raw: "x")',
        *extra,
        "    E: application (line=20)",
        f"      A: {NS}:allowBackup(0x01010280)=false",
        f"      A: {NS}:dataExtractionRules(0x0101063e)=@0x7f100000",
        *[f"      {a}" for a in app_attrs.splitlines() if a],
    ]
    return "\n".join(lines)


def rules(skip: str = "", include: bool = False) -> str:
    lines = ["E: data-extraction-rules (line=2)"]
    for section in ("cloud-backup", "device-transfer"):
        lines.append(f"  E: {section} (line=3)")
        if include:
            lines += ["    E: include (line=4)", '      A: domain="file" (Raw: "file")', '      A: path="." (Raw: ".")']
        for d in sorted(DOMAINS):
            if d != skip:
                lines += ["    E: exclude (line=5)", f'      A: domain="{d}" (Raw: "{d}")', '      A: path="." (Raw: ".")']
    return "\n".join(lines)


def internet(tag: str, extra: str = "") -> str:
    return f'    E: {tag} (line=16)\n      A: {NS}:name(0x01010003)="android.permission.INTERNET" (Raw: "x")' + extra


class CheckManifestTest(unittest.TestCase):
    def test_baseline_ok(self):
        self.assertEqual([], check_manifest(manifest(), [rules()], release=True))

    def test_internet_in_any_permission_element_fails(self):
        for tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"):
            problems = check_manifest(manifest(internet(tag)), [rules()], release=True)
            self.assertIn("permission not allowed: android.permission.INTERNET", problems, tag)
        with_max = internet("uses-permission", f"\n      A: {NS}:maxSdkVersion(0x01010271)=0x1c")
        self.assertTrue(check_manifest(manifest(with_max), [rules()], release=True))

    def test_other_namespace_cannot_shadow_android_attributes(self):
        shadow = internet("uses-permission", '\n      A: http://example.com/x:name="android.permission.BLUETOOTH_CONNECT" (Raw: "x")')
        self.assertIn("permission not allowed: android.permission.INTERNET", check_manifest(manifest(shadow), [rules()], release=True))
        backup = 'A: http://example.com/x:allowBackup="false" (Raw: "false")'
        text = manifest().replace(f"{NS}:allowBackup(0x01010280)=false", f"{NS}:allowBackup(0x01010280)=true") + "\n      " + backup
        self.assertIn("application must set allowBackup=false", check_manifest(text, [rules()], release=True))

    def test_duplicate_attribute_fails(self):
        dup = f'      A: {NS}:allowBackup(0x01010280)=true'
        with self.assertRaises(ManifestError):
            check_manifest(manifest(app_attrs=dup), [rules()], release=True)

    def test_declared_permissions_fail(self):
        extra = f'    E: permission-tree (line=17)\n      A: {NS}:name(0x01010003)="com.example.tree" (Raw: "x")'
        self.assertTrue(any("permission-tree" in p for p in check_manifest(manifest(extra), [rules()], release=True)))

    def test_scan_without_never_for_location_fails(self):
        problems = check_manifest(manifest(scan_flags="0x0"), [rules()], release=True)
        self.assertIn("BLUETOOTH_SCAN must declare usesPermissionFlags=neverForLocation", problems)

    def test_backup_and_rules(self):
        self.assertIn("application must set allowBackup=false", check_manifest(manifest().replace("=false", "=0xffffffff"), [rules()], True))
        self.assertIn("data-extraction rules file not found", check_manifest(manifest(), [], release=True))
        self.assertTrue(any("device_root" in p for p in check_manifest(manifest(), [rules(skip="device_root")], True)))
        self.assertTrue(any("must not include" in p for p in check_manifest(manifest(), [rules(include=True)], True)))

    def test_release_must_not_be_debuggable_and_no_shared_user(self):
        debuggable = f"A: {NS}:debuggable(0x0101000f)=true"
        self.assertIn("release-like builds must not be debuggable", check_manifest(manifest(app_attrs=debuggable), [rules()], True))
        self.assertEqual([], check_manifest(manifest(app_attrs=debuggable), [rules()], release=False))
        shared = f'A: {NS}:sharedUserId(0x0101000b)="com.example.shared" (Raw: "x")'
        self.assertIn("sharedUserId is not allowed", check_manifest(manifest(manifest_attrs=shared), [rules()], True))


    def test_namespaced_elements_fail_closed(self):
        ns_perm = '    E: http://example.com/x:uses-permission (line=16)\n      A: ' + NS + ':name(0x01010003)="android.permission.INTERNET" (Raw: "x")'
        with self.assertRaises(ManifestError):
            check_manifest(manifest(ns_perm), [rules()], release=True)
        ns_app = "    E: http://example.com/x:application (line=18)\n      A: " + NS + ":allowBackup(0x01010280)=true"
        with self.assertRaises(ManifestError):
            check_manifest(manifest(ns_app), [rules()], release=True)

    def test_every_rules_section_and_variant_is_checked(self):
        extra = rules() + '\n  E: cloud-backup (line=40)\n    E: include (line=41)\n      A: domain="file" (Raw: "file")\n      A: path="." (Raw: ".")'
        problems = check_rules(extra)
        self.assertTrue(any("exactly one <cloud-backup>" in p for p in problems), problems)
        self.assertTrue(any("must not include" in p for p in problems), problems)
        self.assertTrue(check_manifest(manifest(), [rules(), rules(include=True)], release=True))

    def test_resource_files_lists_every_configuration(self):
        dump = "\n".join([
            "Package name=io.github.ridelabz.engwespeedtool id=7f",
            "  type xml id=10 entryCount=2",
            "    resource 0x7f100000 xml/data_extraction_rules",
            "      () (file) res/xml/data_extraction_rules.xml type=XML",
            "      (v33) (file) res/xml-v33/data_extraction_rules.xml type=XML",
            "    resource 0x7f100001 xml/other",
            "      () (file) res/xml/other.xml type=XML",
        ])
        self.assertEqual(["res/xml/data_extraction_rules.xml", "res/xml-v33/data_extraction_rules.xml"], resource_files(dump, "0x7f100000"))


if __name__ == "__main__":
    unittest.main()
