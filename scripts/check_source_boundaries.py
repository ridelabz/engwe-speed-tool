#!/usr/bin/env python3
"""Fails if code outside the Bluetooth module could reach Bluetooth GATT, or any code pairs a bike or logs.

Usage: check_source_boundaries.py [<repo root>]

Only `ble-android` may talk to the bike. Everywhere else, shipped source sets (every set except the
test ones) may hold only Kotlin, resources, assets and the manifest, and the Kotlin may not mention:
- Bluetooth GATT or its write and notification calls,
- class objects, reflection, method handles or class loading (ways to call GATT without naming it),
- native code, raw Binder calls or process execution,
- suppressed visibility errors (the Android modules also compile with warnings as errors, which
  rejects any error suppression),
- aliased imports of platform packages, and setting window content (only MainActivity may, so every
  screen stays inside the test-build banner frame),
- any `.bytes` (meant for an outgoing frame's bytes; text can't tell types apart, the bytecode check
  can), except in the protocol module, whose sanitiser logs them,
- the Bluetooth link types (`BikeLink`, `BikeTransport`), except in the session module, so that app code
  reaches a bike only through a session,
- the Android Bluetooth entry point (`AndroidBluetooth`), except in the app's protected bike package,
  which creates every session with the rider's stored acceptance,
- the app's store (`AppStore`), Android's preferences and the usual ways to the app's data directory
  (data-directory and parent getters, `resolveSibling`, a `..` path part, `/data/` paths), except in that
  package, so that nothing else can change what the sessions read as the rider's acceptance,
- recording the acceptance (`acceptRisk`), except in that package and on the risk-acceptance screen,
- recording the firmware caveat or an unverified bike's acknowledgement (`recordFirmwareCaveat`,
  `recordUnverifiedAcknowledgement`, the store's `seeCaveat`/`acknowledge`), except in that package, on the
  acknowledgement screen and in the legal screens (where the caveat sheet lives): the sessions read these
  before every speed or light command,
- making an advertisement (`Advertisement(…)`, `Advertisement.paired`), except in the protocol and session
  modules and the app's bike package: a session connects to the bike of the advertisement it is given, so
  only the code that reads advertisements or lists paired bikes may make one.
In every module's shipped Kotlin, ble-android's too, nothing may pair or unpair a bike with the phone (the
app uses Android's bonds as they are, never makes or removes one; reading them is fine), and nothing may
write to the system log or the console (`android.util.Log`, `java.util.logging`, `print`/`println`,
`System.out`/`System.err`, `printStackTrace`): the session log, which is sanitised, is the only record.
The Java files ble-android may hold are read for these two rules too.
Each Kotlin file's package must also match its directory. In Gradle build files, test fixtures may only
be test dependencies: their code is never checked, because it never ships.

This is a tripwire with readable messages, not a proof: it reads source text. The test build also
runs check_bytecode.py on the compiled classes, which syntax can't fool. Exceptions are listed in
ALLOWED, by file and rule; this script is a protected path, so adding one needs a maintainer's review.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

EXEMPT_MODULES = {"ble-android"}
SKIP_DIRS = {"build", ".gradle", ".git", ".kotlin", ".idea"}
SHIPPED_CHILDREN = {"kotlin", "res", "resources", "assets", "AndroidManifest.xml"}
CODE_SUFFIXES = {".java", ".aidl", ".so", ".dex", ".jar", ".aar", ".apk", ".class", ".c", ".cc", ".cpp", ".h"}

RULES: dict[str, re.Pattern[str]] = {
    "Bluetooth GATT": re.compile(
        r"android\.bluetooth|BLUETOOTH_SERVICE|BluetoothGatt|connectGatt|writeCharacteristic|writeDescriptor"
        r"|ReliableWrite|setCharacteristicNotification"
    ),
    "class objects": re.compile(
        r"\.java\b|\.javaClass\b|javaObjectType|javaPrimitiveType|\bgetClass\b|java\.lang\.Class\b|\bforName\b"
    ),
    "reflection": re.compile(
        r"java\.lang\.reflect|kotlin\.reflect|java\.lang\.invoke|MethodHandle|\bReflection\b|\bProxy\b"
        r"|getDeclared\w+|\bdeclared(?:Method|Field|Constructor|Class)e?s?\b|\bget(?:Method|Field|Constructor)s?\b"
        r"|\.(?:methods|constructors)\b"
    ),
    "class loading": re.compile(r"ClassLoader|\bloadClass\b|dalvik\.system|DexFile"),
    "native code": re.compile(r"\bexternal\s+fun\b|\bloadLibrary\b|System\s*\.\s*load\b|sun\.misc|jdk\.internal"),
    "raw Binder calls": re.compile(r"\bIBinder\b|\btransact\b|ServiceManager"),
    "process execution": re.compile(r"\bgetRuntime\b|ProcessBuilder"),
    # Suppressing visibility errors would reach `internal` code, such as the frame encoder.
    "suppressed compiler errors": re.compile(r"INVISIBLE_|\bERROR_SUPPRESSION\b|-Xdont-warn-on-error-suppression"),
    "aliased platform import": re.compile(r"^[ \t]*import[ \t]+(?:java|javax|android|dalvik|kotlin|sun|jdk)\.\S+[ \t]+as\b", re.M),
    "window content": re.compile(r"\bsetContent\b|setContentView|addContentView|decorView|WindowManager", re.I),
    # Matches any `.bytes`: the source can't tell an outgoing frame from other types (the bytecode check can).
    "outgoing frame bytes": re.compile(r"(?:\.|::)bytes\b"),
    # Any link or transport type (an Android one too), but the fake transport that demo mode builds on.
    "bike link": re.compile(r"\b(?!Fake)\w*(?:BikeLink|BikeTransport)\b"),
    "Bluetooth entry": re.compile(r"\bAndroidBluetooth\b"),
    # Also the ways from another directory of the app to its data directory: the data-directory getters,
    # parent getters (`parent`, `parentFile`, `getParent…`), `resolveSibling`, a `..` between quotes or
    # separators, and absolute paths under /data/. Accepted false positives: `.parent` of any type (a view,
    # a navigation destination) and string ranges such as "a".."z". Out of reach here and in the bytecode
    # check: string surgery on a path (substringBeforeLast, removeSuffix) and escaped dots ("\u002e").
    "app store": re.compile(
        r"\bAppStore\b|SharedPreferences|PreferenceManager|PreferenceDataStore|\bdataStore\b|shared_prefs"
        r"|\bdataDir(?:ectory)?\b|\bgetDataDir(?:ectory)?\b|(?:\.|::)parent\b|\bparentFile\b"
        r"|\bgetParent(?:File)?\b|\bresolveSibling\b|[\"/\\]\.\.(?:[\"/\\$]|$)|\"/data/",
        re.M,
    ),
    "risk acceptance": re.compile(r"\bacceptRisk\b"),
    "consent record": re.compile(r"\brecord(?:FirmwareCaveat|UnverifiedAcknowledgement)\b|\bseeCaveat\b|\.acknowledge\s*\("),
    # Also an alias of the type, which could construct one under another name.
    "bike advertisement": re.compile(
        r"\bAdvertisement\s*\(|::\s*Advertisement\b|\bAdvertisement\.(?:Companion\.)?paired\b"
        r"|^[ \t]*import[ \t]+\S*\bAdvertisement[ \t]+as\b|\btypealias\b[^\n]*\bAdvertisement\b",
        re.M,
    ),
}
# Rules for every module's shipped Kotlin, ble-android's too.
EVERYWHERE: dict[str, re.Pattern[str]] = {
    "bonding": re.compile(
        r"\b(?:createBond|removeBond|cancelBondProcess|setPairingConfirmation|setPin)\b|ACTION_PAIRING_REQUEST"
    ),
    "system log": re.compile(
        r"android\.util\.Log\b|\bLog\.(?:v|d|i|w|e|wtf|println)\b|\bprintln\b|\bprint\s*\(|System\.(?:out|err)\b"
        r"|\bprintStackTrace\b|java\.util\.logging"
    ),
}

# Where a rule's code may live, for its message; the other rules allow only ble-android.
SCOPE = {
    "outgoing frame bytes": "the protocol module",
    "bike link": "the session module",
    "Bluetooth entry": "the app's bike package",
    "app store": "the app's bike package",
    "risk acceptance": "the app's bike package and the risk-acceptance screen",
    "consent record": "the app's bike package, the acknowledgement screen and the legal screens",
    "bike advertisement": "the protocol and session modules and the app's bike package",
}
# Reviewed exceptions for whole directories (with a trailing slash): prefix -> rules its files may break.
PATH_ALLOWED: dict[str, set[str]] = {
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/bike/": {
        "Bluetooth entry",
        "app store",
        "risk acceptance",
        "consent record",
        "bike advertisement",
    },
    # The firmware caveat sheet records that the rider saw it.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/legal/": {"consent record"},
}
PACKAGE = re.compile(r"^[ \t]*(?:\*/[ \t]*)?package[ \t]+([\w.`]+)", re.M)

# Reviewed exceptions for whole modules: module -> rules its shipped code may break.
MODULE_ALLOWED: dict[str, set[str]] = {
    "protocol": {"outgoing frame bytes", "bike advertisement"},
    "session": {"bike link", "bike advertisement"},
}
# The only lines of a Gradle build file that may mention test fixtures.
# One call per line; a trailing `//` comment is ignored.
_PROJECT = r"(?:project\(\"[:\w-]+\"\)|projects(?:\.\w+)+)"
TEST_FIXTURE_LINES = re.compile(
    r"^[ \t]*(?:`java-test-fixtures`"
    rf"|testImplementation\(testFixtures\({_PROJECT}\)\)"
    rf"|testFixtures(?:Api|Implementation|CompileOnly|RuntimeOnly)\((?:libs(?:\.\w+)+|{_PROJECT})\)"
    r")[ \t]*$"
)

# Reviewed exceptions: file -> rules it may break.
ALLOWED: dict[str, set[str]] = {
    # Reads the bundled profiles through the class's resources.
    "protocol/src/main/kotlin/io/github/ridelabz/engwespeedtool/protocol/ProfileRegistry.kt": {"class objects"},
    # Hosts every screen inside the test-build banner frame.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/MainActivity.kt": {"window content"},
    # Records the acceptance once every statement is ticked.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/onboarding/RiskAcceptanceScreen.kt": {"risk acceptance"},
    # Records the acknowledgement when the rider continues.
    "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/onboarding/UnverifiedAcknowledgementScreen.kt": {
        "consent record"
    },
}


def is_test_set(name: str) -> bool:
    return name.startswith("test") or name.startswith("androidTest")


def source_sets(root: Path):
    """Yield (module, set name, set dir) for every `src/<set>` directory outside build output."""
    stack = [root]
    while stack:
        d = stack.pop()
        for child in sorted(d.iterdir()):
            if not child.is_dir() or child.is_symlink() or child.name in SKIP_DIRS:
                continue
            if child.name == "src":
                module = child.parent.relative_to(root).as_posix()
                for s in sorted(child.iterdir()):
                    if s.is_dir():
                        yield module, s.name, s
            else:
                stack.append(child)


def gradle_files(root: Path):
    stack = [root]
    while stack:
        d = stack.pop()
        for child in sorted(d.iterdir()):
            if child.is_dir() and not child.is_symlink() and child.name not in SKIP_DIRS and child.name != "src":
                stack.append(child)
            elif child.is_file() and child.name.endswith(".gradle.kts"):
                yield child


def check(root: Path) -> list[str]:
    problems = []
    for f in gradle_files(root):
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), start=1):
            if re.search(r"(?i)test[-_]?fixtures", line) and not line.lstrip().startswith("//"):
                if not TEST_FIXTURE_LINES.match(re.sub(r"[ \t]*//.*$", "", line)):
                    problems.append(f"{f.relative_to(root).as_posix()}:{n}: test fixtures used outside tests")
    for module, set_name, set_dir in source_sets(root):
        if is_test_set(set_name):
            continue
        for f in sorted(p for p in set_dir.rglob("*") if p.suffix in (".kt", ".java")):
            rel = f.relative_to(root).as_posix()
            if f.is_symlink() or not f.is_file():
                # Outside ble-android the main loop reports symbolic links; here they are reported for it.
                if f.is_symlink() and module.split("/")[0] in EXEMPT_MODULES:
                    problems.append(f"{rel}: symbolic link")
                continue
            text = re.sub(r"[ \t]*\.[ \t]*", ".", f.read_text(encoding="utf-8").replace("`", ""))
            for name, rx in EVERYWHERE.items():
                lines = sorted({text.count("\n", 0, m.start()) + 1 for m in rx.finditer(text)})
                problems += [f"{rel}:{n}: {name} (no module may)" for n in lines]
        if module in EXEMPT_MODULES or module.split("/")[0] in EXEMPT_MODULES:
            continue
        for child in sorted(set_dir.iterdir()):
            if child.name not in SHIPPED_CHILDREN:
                problems.append(f"{child.relative_to(root).as_posix()}: only Kotlin, resources and assets may ship here")
        for f in sorted(p for p in set_dir.rglob("*") if p.is_file() or p.is_symlink()):
            rel = f.relative_to(root).as_posix()
            if f.is_symlink():
                problems.append(f"{rel}: symbolic link")
                continue
            if f.suffix.lower() in CODE_SUFFIXES:
                problems.append(f"{rel}: {f.suffix} files may only live in ble-android")
                continue
            in_kotlin = f.relative_to(set_dir).parts[0] == "kotlin"
            if in_kotlin and f.suffix != ".kt":
                problems.append(f"{rel}: only .kt files belong in a kotlin source directory")
                continue
            if f.suffix != ".kt":
                continue
            # Backticks only quote identifiers; dropping them keeps `android`.bluetooth recognisable.
            # Spaces around dots are dropped too (newlines stay, so line numbers hold).
            text = re.sub(r"[ \t]*\.[ \t]*", ".", f.read_text(encoding="utf-8").replace("`", ""))
            allowed = ALLOWED.get(rel, set()) | MODULE_ALLOWED.get(module, set())
            for prefix, rules in PATH_ALLOWED.items():
                if rel.startswith(prefix):
                    allowed |= rules
            # Whole-file matching, so a call split across lines is still found.
            for name, rx in RULES.items():
                if name in allowed:
                    continue
                lines = sorted({text.count("\n", 0, m.start()) + 1 for m in rx.finditer(text)})
                where = SCOPE.get(name, "ble-android")
                problems += [f"{rel}:{n}: {name} outside {where}" for n in lines]
            if in_kotlin:
                packages = PACKAGE.findall(text)
                directory = ".".join(f.relative_to(set_dir / "kotlin").parent.parts)
                if packages != ([directory] if directory else []):
                    problems.append(f"{rel}: needs exactly one package line, matching the directory")
    return problems


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent.parent
    problems = check(root.resolve())
    for p in problems:
        print(p)
    if problems:
        print(f"{len(problems)} source boundary problem(s); see scripts/check_source_boundaries.py")
        return 1
    print("source boundaries: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
