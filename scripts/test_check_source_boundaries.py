"""Tests for check_source_boundaries.py (python3 -m unittest)."""

import tempfile
import unittest
from pathlib import Path

from check_source_boundaries import ALLOWED, MODULE_ALLOWED, check

APP_KT = "app/src/main/kotlin/x/Screen.kt"
PKG = "package x\n\n"


class BoundaryTest(unittest.TestCase):
    def run_on(self, files: dict[str, str]) -> list[str]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for rel, text in files.items():
                p = root / rel
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(text)
            return check(root)

    def test_plain_app_code_passes(self) -> None:
        files = {
            APP_KT: PKG + "import androidx.compose.foundation.layout.Box as LayoutBox\n"
            "fun screen(onClick: () -> Unit) { onClick.invoke(); val s = layout.fields }\n",
            "app/src/main/res/values/strings.xml": "<resources/>",
            "app/src/main/AndroidManifest.xml": "<manifest/>",
            "app/src/main/assets/x.txt": "x",
            "protocol/src/main/resources/profiles/x.json": "{}",
        }
        self.assertEqual(self.run_on(files), [])

    def test_ble_module_and_tests_are_exempt(self) -> None:
        files = {
            "ble-android/src/main/kotlin/Link.kt": "import android.bluetooth.BluetoothGatt\n",
            "ble-android/src/main/kotlin/W.kt": "fun f() = Runtime.getRuntime()\n",
            "ble-android/src/main/java/X.java": "class X {}",
            "app/src/test/kotlin/T.kt": "val c = T::class.java.declaredMethods\n",
            "app/src/testPrTest/kotlin/T.kt": "val c = Class.forName(\"x\")\n",
            "app/src/androidTest/kotlin/T.kt": "val g: BluetoothGatt? = null\n",
        }
        self.assertEqual(self.run_on(files), [])

    def test_no_module_pairs_or_logs(self) -> None:
        for module in ["app", "ble-android", "protocol", "session", "reporting"]:
            for name, line in [
                ("bonding", "device.createBond()"),
                ("bonding", "device.`removeBond`()"),
                ("bonding", "val a = BluetoothDevice.ACTION_PAIRING_REQUEST"),
                ("system log", "import android.util.Log"),
                ("system log", "Log.d(TAG, msg)"),
                ("system log", "println(frame)"),
                ("system log", "System.err.write(b)"),
                ("system log", "e.printStackTrace()"),
                ("system log", "print(x)"),
                ("system log", "System.out.println(x)"),
                ("system log", "val l = java.util.logging.Logger.getLogger(\"x\")"),
            ]:
                with self.subTest(module=module, line=line):
                    rel = f"{module}/src/main/kotlin/x/F.kt"
                    problems = self.run_on({rel: PKG + line + "\n"})
                    self.assertIn(f"{rel}:3: {name} (no module may)", problems)
        # Tests may print; other shipped source sets may not; Java in ble-android is read too.
        self.assertEqual(self.run_on({"ble-android/src/test/kotlin/T.kt": "fun t() = println(1)\n"}), [])
        rel = "app/src/prTest/kotlin/x/F.kt"
        self.assertIn(f"{rel}:3: system log (no module may)", self.run_on({rel: PKG + "println(1)\n"}))
        rel = "ble-android/src/main/java/x/B.java"
        self.assertIn(f"{rel}:1: bonding (no module may)", self.run_on({rel: "class B { void f(D d) { d.createBond(); } }\n"}))
        # Reading bonds is fine: the paired-bike path needs it.
        reading = "val b = adapter.bondedDevices\nval s = device.bondState == BluetoothDevice.BOND_BONDED\n"
        reading += "val a = BluetoothDevice.ACTION_BOND_STATE_CHANGED\n"
        self.assertEqual(self.run_on({"ble-android/src/main/kotlin/x/R.kt": PKG + reading}), [])
        # Other words that only look alike.
        self.assertEqual(self.run_on({APP_KT: PKG + "val printer = 1\nval bond = 2\nval logLine = 3\n"}), [])

    def test_forbidden_code(self) -> None:
        cases = {
            "Bluetooth GATT": [
                "import android.bluetooth.BluetoothGatt",
                "import android.bluetooth.*",
                "import `android`.bluetooth.BluetoothManager",
                "import android . bluetooth . BluetoothManager",
                "val m = getSystemService(Context.BLUETOOTH_SERVICE)",
                "import android.bluetooth.BluetoothGatt as G",
                "device.connectGatt(context, false, cb)",
                "gatt.writeCharacteristic(c, v, t)",
                "gatt.`writeDescriptor`(d)",
                "gatt.executeReliableWrite()",
            ],
            "class objects": ["val k = X::class.java", "val k = x.javaClass", "val k = kc.java", "Class.forName(n)"],
            "reflection": [
                "import java.lang.reflect.Method",
                "import kotlin.reflect.full.memberFunctions",
                "c.getDeclaredMethod(n)",
                "c.getMethod(n)",
                "c.declaredMethods",
                "java.lang.invoke.MethodHandles.lookup()",
                "Proxy.newProxyInstance(a, b, c)",
            ],
            "class loading": ["dalvik.system.DexClassLoader(p, null, null, l)", "classLoader.loadClass(n)"],
            "native code": ['System.loadLibrary("x")', "external fun f(): Int", "System . load(p)"],
            "raw Binder calls": ["binder.transact(1, data, reply, 0)", "val b: IBinder? = null"],
            "process execution": ['Runtime.getRuntime().exec("x")', "ProcessBuilder(cmd)", "Rt.getRuntime()"],
            "aliased platform import": ["import java.lang.Runtime as Rt2", "  import android.content.Context as C"],
            "window content": [
                "(LocalContext.current as ComponentActivity).setContent { }",
                "activity.window.decorView",
                "activity.window.getDecorView()",
                "setContentView(view)",
            ],
            "suppressed compiler errors": ['@file:Suppress("INVISIBLE_REFERENCE")', '@Suppress("INVISIBLE_MEMBER")'],
            "outgoing frame bytes": [
                "link.send(frame.bytes)",
                "val b = frame . bytes",
                "val get = OutgoingFrame::bytes",
                "val b = command.toFrame().bytes",
            ],
        }
        for rule, lines in cases.items():
            for line in lines:
                with self.subTest(line=line):
                    problems = self.run_on({APP_KT: f"{PKG}fun f() {{\n{line}\n}}\n"})
                    where = {"outgoing frame bytes": "the protocol module"}.get(rule, "ble-android")
                    self.assertIn(f"{APP_KT}:4: {rule} outside {where}", problems)
                    self.assertTrue(all(p.startswith(f"{APP_KT}:4: ") for p in problems), problems)

    def test_calls_split_across_lines(self) -> None:
        text = PKG + "fun f() {\n    Runtime\n        .getRuntime()\n    System\n        .load(p)\n}\n"
        self.assertEqual(
            sorted(self.run_on({APP_KT: text})),
            [f"{APP_KT}:5: process execution outside ble-android", f"{APP_KT}:6: native code outside ble-android"],
        )

    def test_package_must_match_directory(self) -> None:
        for text in (
            "package io.github.ridelabz.engwespeedtool\n",
            "val x = 1\n",
            "package x.y\n",
            "/*\npackage x\n*/\npackage io.github.ridelabz.engwespeedtool\n",
            "/*\npackage x\n*/ package io.github.ridelabz.engwespeedtool\n",
        ):
            with self.subTest(text=text):
                self.assertEqual(
                    self.run_on({APP_KT: text}), [f"{APP_KT}: needs exactly one package line, matching the directory"]
                )
        self.assertEqual(self.run_on({APP_KT: "package `x`\n"}), [])
        self.assertEqual(self.run_on({"app/src/main/kotlin/Root.kt": "val x = 1\n"}), [])
        self.assertEqual(
            self.run_on({"app/src/main/assets/x.kt": "val x = 1\n"}), [], "not compiled, so no package needed"
        )

    def test_every_shipped_set_counts(self) -> None:
        for set_name in ("main", "debug", "release", "prTest"):
            path = f"app/src/{set_name}/kotlin/X.kt"
            with self.subTest(set_name=set_name):
                self.assertEqual(self.run_on({path: "val k = X::class.java\n"}), [f"{path}:1: class objects outside ble-android"])

    def test_main_activity_may_set_content(self) -> None:
        path = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/MainActivity.kt"
        text = "package io.github.ridelabz.engwespeedtool\n\nfun f() = setContent { }\n"
        self.assertEqual(self.run_on({path: text}), [])
        self.assertEqual(self.run_on({path: text + "val k = X::class.java\n"}), [f"{path}:4: class objects outside ble-android"])

    def test_other_modules_count(self) -> None:
        path = "session/src/main/kotlin/X.kt"
        self.assertEqual(self.run_on({path: "val k = X::class.java\n"}), [f"{path}:1: class objects outside ble-android"])
        path = "session/src/main/kotlin/a/b/X.kt"
        self.assertEqual(self.run_on({path: "package a.b\nval k = X::class.java\n"}), [f"{path}:2: class objects outside ble-android"])

    def test_code_outside_kotlin_is_rejected(self) -> None:
        problems = self.run_on(
            {
                "app/src/main/java/X.java": "class X {}",
                "app/src/main/kotlin/Y.java": "class Y {}",
                "app/src/main/kotlin/Z.txt": "",
                "app/src/main/aidl/I.aidl": "",
                "app/src/main/jniLibs/arm64-v8a/libx.so": "",
                "app/src/main/assets/payload.dex": "",
            }
        )
        self.assertIn("app/src/main/java: only Kotlin, resources and assets may ship here", problems)
        self.assertIn("app/src/main/aidl: only Kotlin, resources and assets may ship here", problems)
        self.assertIn("app/src/main/jniLibs: only Kotlin, resources and assets may ship here", problems)
        self.assertIn("app/src/main/kotlin/Y.java: .java files may only live in ble-android", problems)
        self.assertIn("app/src/main/kotlin/Z.txt: only .kt files belong in a kotlin source directory", problems)
        self.assertIn("app/src/main/assets/payload.dex: .dex files may only live in ble-android", problems)

    def test_symlinks_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "app/src/main/kotlin").mkdir(parents=True)
            (root / "outside.kt").write_text("val k = X::class.java\n")
            (root / "app/src/main/kotlin/L.kt").symlink_to(root / "outside.kt")
            self.assertEqual(check(root), ["app/src/main/kotlin/L.kt: symbolic link"])

    def test_allowed_exception_is_narrow(self) -> None:
        path = "protocol/src/main/kotlin/io/github/ridelabz/engwespeedtool/protocol/ProfileRegistry.kt"
        self.assertEqual(ALLOWED[path], {"class objects"})
        pkg = "package io.github.ridelabz.engwespeedtool.protocol\n"
        self.assertEqual(self.run_on({path: pkg + "val r = X::class.java.getResource(n)\n"}), [])
        self.assertEqual(
            self.run_on({path: pkg + "val r = X::class.java.getDeclaredMethod(n)\n"}), [f"{path}:2: reflection outside ble-android"]
        )

    def test_only_the_session_module_names_bike_links(self) -> None:
        text = "package a\nfun f(link: BikeLink, t: BikeTransport) = Unit\n"
        self.assertEqual(self.run_on({"session/src/main/kotlin/a/S.kt": text}), [])
        path = "app/src/main/kotlin/a/S.kt"
        self.assertEqual(self.run_on({path: text}), [f"{path}:2: bike link outside the session module"])
        android = "package a\nval t = AndroidBikeTransport(context)\nfun f(l: AndroidBikeLink) = Unit\n"
        self.assertEqual(
            self.run_on({path: android}),
            [f"{path}:2: bike link outside the session module", f"{path}:3: bike link outside the session module"],
        )
        demo = "package a\nval s = BikeSession(scope, FakeBikeTransport(bike), registry, log, { true })\n"
        self.assertEqual(self.run_on({path: demo}), [], "demo mode builds a session on the fake")
        self.assertEqual(self.run_on({path: "package a\nval c = BikeLinkContract\n"}), [])

    def test_only_the_app_bike_package_reaches_android_bluetooth(self) -> None:
        text = "package a\nval s = AndroidBluetooth.get(context).session(scope, profiles, log, { true })\n"
        path = "app/src/main/kotlin/a/S.kt"
        self.assertEqual(self.run_on({path: text}), [f"{path}:2: Bluetooth entry outside the app's bike package"])
        bike = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/bike/BikeServices.kt"
        pkg = "package io.github.ridelabz.engwespeedtool.bike\n"
        self.assertEqual(self.run_on({bike: pkg + "val b = AndroidBluetooth.get(context)\n"}), [])
        lookalike = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/bikeish/S.kt"
        self.assertEqual(
            self.run_on({lookalike: "package io.github.ridelabz.engwespeedtool.bikeish\nval b = AndroidBluetooth\n"}),
            [f"{lookalike}:2: Bluetooth entry outside the app's bike package"],
        )

    def test_only_protected_code_records_consent(self) -> None:
        pkg = "package io.github.ridelabz.engwespeedtool.bike\n"
        bike = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/bike/BikeServices.kt"
        calls = (
            "fun a() = caveat.recordFirmwareCaveat()",
            "fun a() = handle.recordUnverifiedAcknowledgement()",
            "fun a() = store.seeCaveat(1)",
            "fun a() = store.acknowledge(bike, 1)",
        )
        self.assertEqual(self.run_on({bike: pkg + "\n".join(calls) + "\n"}), [])
        screen = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/onboarding/UnverifiedAcknowledgementScreen.kt"
        on_screen = "package io.github.ridelabz.engwespeedtool.ui.onboarding\n"
        self.assertEqual(self.run_on({screen: on_screen + calls[1] + "\n"}), [])
        sheet = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/legal/FirmwareCaveatSheet.kt"
        self.assertEqual(self.run_on({sheet: "package io.github.ridelabz.engwespeedtool.ui.legal\n" + calls[0] + "\n"}), [])
        controller = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/AppController.kt"
        for call in calls:
            with self.subTest(call=call):
                self.assertEqual(
                    self.run_on({controller: "package io.github.ridelabz.engwespeedtool.ui\n" + call + "\n"}),
                    [
                        f"{controller}:2: consent record outside the app's bike package, "
                        "the acknowledgement screen and the legal screens"
                    ],
                )
        self.assertEqual(
            self.run_on({"app/src/test/kotlin/a/T.kt": "package a\n" + calls[0] + "\n"}), [], "tests may"
        )
        self.assertEqual(
            self.run_on({controller: "package io.github.ridelabz.engwespeedtool.ui\nfun a() = acknowledged()\n"}), []
        )

    def test_only_the_app_bike_package_changes_the_store(self) -> None:
        bike = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/bike/BikeServices.kt"
        pkg = "package io.github.ridelabz.engwespeedtool.bike\n"
        self.assertEqual(self.run_on({bike: pkg + "val s = AppStore.get(context)\nfun a() = acceptRisk(today)\n"}), [])
        path = "app/src/main/kotlin/a/S.kt"
        self.assertEqual(
            self.run_on({path: "package a\nval s = AppStore.get(context)\nfun a() = services.acceptRisk(today)\n"}),
            [
                f"{path}:2: app store outside the app's bike package",
                f"{path}:3: risk acceptance outside the app's bike package and the risk-acceptance screen",
            ],
        )
        screen = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/onboarding/RiskAcceptanceScreen.kt"
        self.assertEqual(ALLOWED[screen], {"risk acceptance"})
        on_screen = "package io.github.ridelabz.engwespeedtool.ui.onboarding\n"
        self.assertEqual(self.run_on({screen: on_screen + "fun a() = services.acceptRisk(today)\n"}), [])
        self.assertEqual(
            self.run_on({screen: on_screen + "val s = AppStore.get(context)\n"}),
            [f"{screen}:2: app store outside the app's bike package"],
        )
        self.assertEqual(self.run_on({"app/src/test/kotlin/a/T.kt": "package a\nval s = AppStore(prefs)\n"}), [], "tests may")
        for line in (
            "val p = context.getSharedPreferences(name, 0)",
            "val p = PreferenceManager.getDefaultSharedPreferences(context)",
            "val p: SharedPreferences = x",
            "val d = context.dataStore",
            'val f = File(context.dataDir, "shared_prefs/app_store.xml")',
            "val f = File(context.getDataDir(), name)",
            "val f = File(context.filesDir.parentFile, name)",
            "val f = File(context.filesDir.getParentFile(), name)",
            "val f = File(context.filesDir.parent, name)",
            "val f = File(context.filesDir.getParent(), name)",
            "val f = context.filesDir.toPath().parent",
            'val f = File(context.filesDir, "..")',
            'val f = File(context.filesDir, "../files")',
            'val f = File(context.cacheDir, "..\\\\files")',
            'val f = File("..$sep")',
            'val f = File("$dir/..")',
            'val f = File("x/../..")',
            "val f = File::parentFile",
            "val f = with(d) { parentFile }",
            "val f = d.resolveSibling(name)",
            "val f = Environment.getDataDirectory()",
            "val f = Environment.dataDirectory",
            'val f = File("/data/user/0/" + name)',
            'val f = """..',
        ):
            with self.subTest(line=line):
                self.assertEqual(
                    self.run_on({path: f"package a\n{line}\n"}), [f"{path}:2: app store outside the app's bike package"]
                )
        self.assertEqual(self.run_on({bike: pkg + "val p = context.getSharedPreferences(name, 0)\n"}), [])
        # Ranges and other dots are not ways up.
        for line in (
            "val r = 0..10",
            "val r = '0'..'9'",
            'val s = "a..b"',
            'val s = "..."',
            "// more to come ...",
            "val p = navigator.parents",
            "val q = x.parentId",
        ):
            with self.subTest(line=line):
                self.assertEqual(self.run_on({path: f"package a\n{line}\n"}), [])

    def test_only_protected_code_makes_advertisements(self) -> None:
        path = "app/src/main/kotlin/a/S.kt"
        for line in (
            "session.start(Advertisement(address, -60))",
            "session.start(Advertisement.paired(address))",
            "val make = ::Advertisement",
            "import io.github.ridelabz.engwespeedtool.session.Advertisement as Ad",
            "typealias Ad = Advertisement",
            "val a = Advertisement.Companion.paired(address)",
        ):
            with self.subTest(line=line):
                self.assertEqual(
                    self.run_on({path: f"package a\n{line}\n"}),
                    [f"{path}:2: bike advertisement outside the protocol and session modules and the app's bike package"],
                )
        reading = "package a\nimport io.github.ridelabz.engwespeedtool.session.Advertisement\nfun f(ad: Advertisement) = ad.heard && ad.rssi != null\n"
        self.assertEqual(self.run_on({path: reading}), [], "reading an advertisement is fine")
        bike = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/bike/AppStore.kt"
        pkg = "package io.github.ridelabz.engwespeedtool.bike\n"
        self.assertEqual(self.run_on({bike: pkg + "val a = Advertisement.paired(address)\n"}), [])
        for module in ("protocol", "session"):
            with self.subTest(module=module):
                self.assertEqual(self.run_on({f"{module}/src/main/kotlin/a/S.kt": "package a\nval a = Advertisement.paired(x)\n"}), [])
        self.assertEqual(self.run_on({"reporting/src/main/kotlin/a/S.kt": "package a\nval a = Advertisement(x, 1)\n"}),
                         ["reporting/src/main/kotlin/a/S.kt:2: bike advertisement outside the protocol and session modules and the app's bike package"])

    def test_test_fixtures_are_only_test_dependencies(self) -> None:
        ok = {
            "session/build.gradle.kts": "plugins {\n    `java-test-fixtures`\n}\n"
            "dependencies {\n    testFixturesApi(libs.x)\n    testFixturesImplementation(libs.y)\n}\n",
            "ble-android/build.gradle.kts": "dependencies {\n    testImplementation(testFixtures(project(\":session\")))\n"
            "    testImplementation(testFixtures(projects.session)) // the contract\n}\n",
        }
        self.assertEqual(self.run_on(ok), [])
        for line in (
            'implementation(testFixtures(project(":session")))',
            'api(project(path = ":session", configuration = "testFixturesRuntimeElements"))',
            'testImplementation(testFixtures(project(":session"))); implementation(testFixtures(project(":session")))',
            'testFixturesApi(libs.x); implementation(testFixtures(project(":session")))',
            'testFixturesImplementation(libs.y) ; api(testFixtures(project(":session")))',
            "testFixturesApi(files(\"x.jar\"))",
            "implementation(files(\"session/build/libs/session-test-fixtures.jar\"))",
        ):
            with self.subTest(line=line):
                problems = self.run_on({"app/build.gradle.kts": f"dependencies {{\n    {line}\n}}\n"})
                self.assertEqual(problems, ["app/build.gradle.kts:2: test fixtures used outside tests"])

    def test_only_the_protocol_module_reads_frame_bytes(self) -> None:
        self.assertEqual(MODULE_ALLOWED["protocol"], {"outgoing frame bytes", "bike advertisement"})
        text = "package a\nfun f(frame: OutgoingFrame) = frame.bytes\n"
        self.assertEqual(self.run_on({"protocol/src/main/kotlin/a/S.kt": text}), [])
        for module in ("session", "reporting", "app"):
            path = f"{module}/src/main/kotlin/a/S.kt"
            with self.subTest(module=module):
                self.assertEqual(self.run_on({path: text}), [f"{path}:2: outgoing frame bytes outside the protocol module"])
        self.assertEqual(self.run_on({"session/src/test/kotlin/a/S.kt": text}), [], "tests may compare bytes")
        self.assertEqual(self.run_on({"session/src/testFixtures/kotlin/a/S.kt": text}), [], "so may test fixtures")
        self.assertEqual(self.run_on({"ble-android/src/main/kotlin/a/S.kt": text}), [])
        self.assertEqual(self.run_on({APP_KT: PKG + "val n = byteCount + bytesWritten + x.bytesLeft\n"}), [])

    def test_this_repository_passes(self) -> None:
        self.assertEqual(check(Path(__file__).resolve().parent.parent), [])


if __name__ == "__main__":
    unittest.main()
