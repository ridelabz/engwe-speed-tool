#!/usr/bin/env python3
"""Fails if compiled code outside ble-android references Bluetooth, reflection, class loading or native code.

Usage: check_bytecode.py [--variant <variant>] [<repo root>]   (needs the JDK's javap)

After `./gradlew :app:assemblePrTest` (the default variant, prTest), or with `--variant release` after
`./gradlew :app:assembleRelease`, as the release workflow does.

The source check (check_source_boundaries.py) reads text, which can hide a call behind unusual syntax.
A compiled class names every class, field and method it uses in its constant pool, including the
method handles and method types behind invokedynamic. This reads, with `javap -v`, the constant pools,
member declarations and code of the classes compiled for the test build from this repository's own
sources: the app's `compile<Variant>*` outputs and the pure-Kotlin modules' main classes. Classes are
chosen by where they were built, not by package, because any source file can declare any package.
ble-android and libraries aren't read. The descriptors of the JDK's lambda and string-concatenation
bootstrap methods, which name method handles by design, are not checked; their names are.

Rejected references:
- anything in android.bluetooth,
- reflection: java.lang.reflect, method handles, kotlin-reflect, and java.lang.Class methods other than
  a few harmless ones (resources, names, isInstance, cast),
- class loading (any ClassLoader, dalvik.system),
- native code and processes: native methods, System/Runtime load and exec, ProcessBuilder, sun.misc,
  jdk.internal,
- raw Binder calls (IBinder.transact, ServiceManager),
- writing to the system log or the console (android.util.Log, System.out/err, printStackTrace,
  java.util.logging, PrintStream.print*),
- the ways to the app's data directory: parent and data-directory getters, resolveSibling,
- outside the protocol module, whose sanitiser logs them: the bytes of an outgoing frame
  (OutgoingFrame.getBytes). Only ble-android, which isn't read here, hands frames to the radio,
- outside the session module: any member of a Bluetooth link (BikeLink), and any `connect` taking a
  BikeAddress, whatever class it is on ("bike link"),
- outside the session module and the log writer's: any call whose parameters include an OutgoingFrame,
  except into the protocol module ("frame handover"). Frames passed as a generic type (a lambda, a
  channel, a collection) aren't seen; the type check covers named parameters only.
The last two keep app code from reaching a bike except through a session. These three are defence in
depth: the frame's hex is also public (OutgoingFrame.toString is not, but the sanitiser's log form is),
and what keeps bytes off the radio is that only ble-android has Bluetooth and its links accept only
OutgoingFrame. Exemptions go by build location (EXEMPT), never by package.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

JVM_MODULES = ("protocol", "session", "reporting")
APP_MARKER = "io.github.ridelabz.engwespeedtool.MainActivity"
CLASS_METHODS_ALLOWED = (
    "getResource",
    "getResourceAsStream",
    "getSimpleName",
    "getName",
    "desiredAssertionStatus",
    "isInstance",
    "cast",
)
RULES: dict[str, re.Pattern[str]] = {
    "Bluetooth API": re.compile(r"android[/.]bluetooth[/.]"),
    "reflection": re.compile(
        r"java[/.]lang[/.]reflect[/.]|java[/.]lang[/.]invoke[/.](?:MethodHandles?|VarHandle|MethodType)\b"
        r"|kotlin[/.]reflect[/.](?:full|jvm)[/.]"
        r"|android[/.]util[/.]Property\b|android[/.]animation[/.](?:ObjectAnimator|PropertyValuesHolder)\b"
        r"|java/lang/Class\.(?!(?:" + "|".join(CLASS_METHODS_ALLOWED) + r"):)"
    ),
    "class loading": re.compile(r"ClassLoader|dalvik[/.]system[/.]"),
    "native code or processes": re.compile(
        r"java/lang/System\.(?:load|loadLibrary):|java/lang/Runtime\.(?:exec|load|loadLibrary):"
        r"|java[/.]lang[/.]ProcessBuilder|sun[/.]misc[/.]|jdk[/.]internal[/.]"
    ),
    "raw Binder calls": re.compile(r"android/os/IBinder\.transact:|android[/.]os[/.]ServiceManager"),
    # The sanitised session log is the only record: nothing to logcat or the console (Kotlin's print and
    # println are inlined to System.out).
    "system log": re.compile(
        r"android[/.]util[/.]Log\b|java/lang/System\.(?:out|err):|java/lang/Throwable\.printStackTrace:"
        r"|java[/.]util[/.]logging[/.]|java/io/PrintStream\.print"
    ),
    # Ways from another directory of the app to its data directory, where the rider's acceptance is kept.
    # The app's store uses neither: it opens its preferences by name. Matched by name and descriptor, not by
    # class: Kotlin names the receiver's type (Application.getDataDir, a subclass of File), and the
    # descriptor keeps other parents (View.getParent, a navigation destination's) out.
    "data directory": re.compile(
        r"\.getParentFile:\(\)Ljava/io/File;|\.getParent:\(\)L(?:java/lang/String|java/nio/file/Path);"
        r"|\.(?:getRoot|resolveSibling):\([^)]*\)Ljava/nio/file/Path;|kotlin/io/FilesKt[\w$]*\.resolveSibling:"
        r"|\.getDataDir:\(\)Ljava/io/File;|\.dataDir:Ljava/lang/String;|\.getDataDirectory:\(\)Ljava/io/File;"
    ),
}
# Apply to every module read here except the ones EXEMPT names.
EXTRA_RULES: dict[str, re.Pattern[str]] = {
    "outgoing frame bytes": re.compile(r"engwespeedtool[/.]protocol[/.]OutgoingFrame\.getBytes\b"),
    "bike link": re.compile(
        r"engwespeedtool[/.]session[/.]\w*(?:BikeLink\.|BikeTransport\.connect\b)"
        r"|\.connect(?:\$\w+)?:\([^)]*Lio/github/ridelabz/engwespeedtool/session/BikeAddress;"
    ),
    "frame handover": re.compile(
        r"^(?!\"?io/github/ridelabz/engwespeedtool/protocol/)[\w/$\"]+\.[\w$<>\"]+:\([^)]*"
        r"Lio/github/ridelabz/engwespeedtool/protocol/OutgoingFrame;"
    ),
}
# Module (first path part under the repository root) -> extra rules its classes may break.
EXEMPT: dict[str, set[str]] = {
    "protocol": {"outgoing frame bytes"},
    "session": {"bike link", "frame handover"},
    "reporting": {"frame handover"},
}
CLASS_HEADER = re.compile(
    r"^(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp|synthetic)\s+)*"
    r"(?:class|interface|enum|@interface)\s+([\w.$]+)"
)
CONSTANT = re.compile(r"^\s*#\d+ = (\w+)\s+(.*)$")
REFERENCE = re.compile(r"//\s+(?:Method|InterfaceMethod|Field|class)\s+(\S+)")
DECLARATION = re.compile(r"^  (?! )(.*);$")
NATIVE = re.compile(r"\bnative\b")
SKIPPED_CONSTANTS = {"Utf8", "String", "Integer", "Long", "Float", "Double", "NameAndType", "Module", "Package"}
MEMBER_CONSTANTS = {"Fieldref", "Methodref", "InterfaceMethodref", "MethodHandle"}
# Bootstrap methods of lambdas, string templates and records. Their descriptors name method handles.
BOOTSTRAP_OWNERS = {
    "java/lang/invoke/LambdaMetafactory",
    "java/lang/invoke/StringConcatFactory",
    "java/lang/runtime/ObjectMethods",
    "java/lang/runtime/SwitchBootstraps",
}
# Classes every lambda's class names (bootstrap owners, and the lookup class in InnerClasses). Calls to
# their methods are member references and are still checked.
HARMLESS_CLASSES = BOOTSTRAP_OWNERS | {"java/lang/invoke/MethodHandles", "java/lang/invoke/MethodHandles$Lookup"}


def member_targets(text: str) -> list[str]:
    """`owner.name:descriptor` -> what to check; only owner and name for bootstrap methods."""
    m = re.match(r'^("[^"]*"|[^.]+)\.(.*)$', text)
    if m and m.group(1).strip('"') in BOOTSTRAP_OWNERS:
        return [f"{m.group(1)}.{m.group(2).split(':', 1)[0]}"]
    return [text]


def constant_targets(kind: str, rest: str) -> list[str]:
    if kind in SKIPPED_CONSTANTS:
        return []
    comment = rest.split("//", 1)[1].strip() if "//" in rest else rest.strip()
    if kind == "Class":
        name = comment.strip('"')
        return [] if name in HARMLESS_CLASSES else [name]
    if kind in MEMBER_CONSTANTS:
        return member_targets(re.sub(r"^REF_\w+\s+", "", comment))
    return [comment]  # MethodType, InvokeDynamic, Dynamic and anything unknown: check all of it


def class_files(root: Path, variant: str = "prTest") -> list[Path]:
    """The classes to read: the pure-Kotlin modules' main classes and the app's, compiled for [variant]."""
    compile_task = "compile" + variant[:1].upper() + variant[1:]
    files: list[Path] = []
    for module in JVM_MODULES:
        found = sorted((root / module / "build/classes/kotlin/main").rglob("*.class"))
        if not found and any((root / module / "src/main").rglob("*.kt")):
            raise SystemExit(f"check_bytecode: no compiled classes for {module}; build first")
        files += found
    app = [
        p
        for p in (root / "app/build").rglob("*.class")
        if variant in p.parts and any(part.startswith(compile_task) for part in p.parts)
    ]
    return files + sorted(app)


def rules_for(path: Path, root: Path) -> dict[str, re.Pattern[str]]:
    """The rules for a class file, chosen by the module it was built in."""
    exempt = EXEMPT.get(path.relative_to(root).parts[0], set())
    return {**RULES, **{name: rx for name, rx in EXTRA_RULES.items() if name not in exempt}}


def check_javap(text: str, rules: dict[str, re.Pattern[str]] | None = None) -> list[str]:
    """Problems in `javap -v -p` output, one per class, rule and reference. [rules] defaults to every rule."""
    if rules is None:
        rules = {**RULES, **EXTRA_RULES}
    problems: list[str] = []
    current = "?"
    in_body = False
    for line in text.splitlines():
        if line.startswith("Classfile "):
            in_body = False  # a new class, whatever happened before
            continue
        if not in_body and (m := CLASS_HEADER.match(line)):
            current = m.group(1)
            continue
        if line in ("{", "}"):
            in_body = line == "{"
            continue
        targets: list[str] = []
        if not in_body:
            if c := CONSTANT.match(line):
                targets += constant_targets(c.group(1), c.group(2))
        else:
            targets += [m.group(1) for m in REFERENCE.finditer(line)]
            if (d := DECLARATION.match(line)) and "//" not in line:
                if NATIVE.search(d.group(1)):
                    problems.append(f"{current}: native code or processes ({d.group(1).strip()})")
                targets.append(d.group(1))
            if line.strip().startswith("flags:") and "ACC_NATIVE" in line:
                problems.append(f"{current}: native code or processes (ACC_NATIVE)")
        for target in targets:
            for name, rx in rules.items():
                if rx.search(target):
                    problems.append(f"{current}: {name} ({target})")
    return list(dict.fromkeys(problems))


def javap() -> str:
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin/javap").exists():
        return str(Path(home) / "bin/javap")
    found = shutil.which("javap")
    if not found:
        raise SystemExit("check_bytecode: javap not found")
    return found


def main(argv: list[str]) -> int:
    args = argv[1:]
    variant = "prTest"
    if args[:1] == ["--variant"]:
        if len(args) < 2 or not re.fullmatch(r"[a-z][A-Za-z]*", args[1]):
            raise SystemExit("check_bytecode: --variant needs a build variant, such as prTest or release")
        variant, args = args[1], args[2:]
    root = Path(args[0]) if args else Path(__file__).resolve().parent.parent
    root = root.resolve()
    files = class_files(root, variant)
    problems: list[str] = []
    classes: set[str] = set()
    groups: dict[str, list[Path]] = {}
    for f in files:
        groups.setdefault(f.relative_to(root).parts[0], []).append(f)
    for group in groups.values():
        rules = rules_for(group[0], root)
        for i in range(0, len(group), 200):
            chunk = [str(f) for f in group[i : i + 200]]
            out = subprocess.run([javap(), "-v", "-p", *chunk], check=True, capture_output=True, text=True).stdout
            classes.update(m.group(1) for line in out.splitlines() if (m := CLASS_HEADER.match(line)))
            problems += check_javap(out, rules)
    if APP_MARKER not in classes:
        print(f"check_bytecode: the app's {variant} classes weren't found ({APP_MARKER} missing)")
        return 1
    for p in problems:
        print(p)
    if problems:
        print(f"{len(problems)} bytecode problem(s) outside ble-android; see scripts/check_bytecode.py")
        return 1
    print(f"bytecode: ok ({len(classes)} classes)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
