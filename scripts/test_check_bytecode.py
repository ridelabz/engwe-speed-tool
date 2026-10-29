"""Tests for check_bytecode.py using javap -c -p text (python3 -m unittest)."""

import contextlib
import io
import tempfile
import unittest
from pathlib import Path

from check_bytecode import EXEMPT, RULES, arguments, check_javap, class_files, rules_for

LOOKUP = "Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
METAFACTORY = f"java/lang/invoke/LambdaMetafactory.metafactory:({LOOKUP}Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;"

CLEAN = f"""Classfile /build/classes/io/x/ProfileRegistry.class
  Last modified 15 Oct 2026; size 2000 bytes
  Compiled from "ProfileRegistry.kt"
public final class io.x.ProfileRegistry
  minor version: 0
  major version: 61
  flags: (0x0031) ACC_PUBLIC, ACC_FINAL, ACC_SUPER
Constant pool:
   #1 = Utf8               java/lang/reflect is only a name here
   #2 = Class              #3             // io/x/ProfileRegistry
   #4 = Methodref          #5.#6          // java/lang/Class.getResource:(Ljava/lang/String;)Ljava/net/URL;
   #7 = Methodref          #8.#9          // java/lang/Object.getClass:()Ljava/lang/Class;
  #10 = Methodref          #11.#12        // kotlin/jvm/internal/Reflection.getOrCreateKotlinClass:(Ljava/lang/Class;)Lkotlin/reflect/KClass;
  #13 = InterfaceMethodref #14.#15        // kotlin/reflect/KClass.getSimpleName:()Ljava/lang/String;
  #16 = Methodref          #17.#18        // java/lang/Enum.valueOf:(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;
  #19 = Class              #20            // java/lang/invoke/LambdaMetafactory
  #21 = Methodref          #19.#22        // {METAFACTORY}
  #23 = MethodHandle       6:#21          // REF_invokeStatic {METAFACTORY}
  #24 = Class              #25            // java/lang/invoke/MethodHandles$Lookup
  #26 = Class              #27            // java/lang/invoke/MethodHandles
  #28 = Class              #29            // java/lang/invoke/StringConcatFactory
  #30 = Methodref          #28.#31        // java/lang/invoke/StringConcatFactory.makeConcatWithConstants:({LOOKUP}Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;
  #32 = MethodType         #33            //  (Ljava/lang/Object;)Ljava/lang/Object;
  #34 = MethodHandle       6:#35          // REF_invokeStatic io/x/ProfileRegistry.f$lambda$0:(I)I
  #36 = InvokeDynamic      #0:#37         // #0:invoke:()Lkotlin/jvm/functions/Function0;
  #38 = String             #1             // java.lang.reflect is only text here
  #39 = NameAndType        #40:#41        // getMethods:()V
{{
  private final java.lang.String resource(java.lang.String);
    descriptor: (Ljava/lang/String;)Ljava/lang/String;
    flags: (0x0012) ACC_PRIVATE, ACC_FINAL
    Code:
      stack=2, locals=2, args_size=2
         0: ldc           #2                  // class io/x/ProfileRegistry
         2: invokevirtual #4                  // Method java/lang/Class.getResource:(Ljava/lang/String;)Ljava/net/URL;
         5: invokedynamic #36,  0             // InvokeDynamic #0:invoke:()Lkotlin/jvm/functions/Function0;
}}
SourceFile: "ProfileRegistry.kt"
InnerClasses:
  public static final #42= #24 of #26;    // Lookup=class java/lang/invoke/MethodHandles$Lookup of class java/lang/invoke/MethodHandles
BootstrapMethods:
  0: #23 REF_invokeStatic {METAFACTORY}
    Method arguments:
      #32 (Ljava/lang/Object;)Ljava/lang/Object;
      #34 REF_invokeStatic io/x/ProfileRegistry.f$lambda$0:(I)I
"""


def javap_class(name: str, pool: list[str], body: list[str]) -> str:
    return (
        "\n".join(
            [
                f"Classfile /build/{name}.class",
                f'  Compiled from "{name}.kt"',
                f"public final class {name}",
                "Constant pool:",
                *pool,
                "{",
                *body,
                "}",
            ]
        )
        + "\n"
    )


class BytecodeTest(unittest.TestCase):
    def test_clean_code(self) -> None:
        self.assertEqual(check_javap(CLEAN), [])

    def test_rejected_constants(self) -> None:
        cases = {
            "Bluetooth API": [
                "   #5 = Methodref          #1.#2   // android/bluetooth/BluetoothDevice.connectGatt:(Landroid/content/Context;)V",
                "   #5 = Class              #1      // android/bluetooth/BluetoothManager",
                "   #5 = MethodHandle       5:#1    // REF_invokeVirtual android/bluetooth/BluetoothManager.getAdapter:()Landroid/bluetooth/BluetoothAdapter;",
                "   #5 = MethodType         #1      //  (Landroid/bluetooth/BluetoothManager;)Ljava/lang/Object;",
                "   #5 = InvokeDynamic      #0:#1   // #0:apply:(Landroid/bluetooth/BluetoothManager;)Ljava/util/function/Supplier;",
            ],
            "reflection": [
                "   #5 = Methodref          #1.#2   // java/lang/Class.getMethods:()[Ljava/lang/reflect/Method;",
                "   #5 = Methodref          #1.#2   // java/lang/Class.forName:(Ljava/lang/String;)Ljava/lang/Class;",
                "   #5 = MethodHandle       6:#1    // REF_invokeStatic java/lang/Class.forName:(Ljava/lang/String;)Ljava/lang/Class;",
                "   #5 = Class              #1      // java/lang/reflect/Method",
                "   #5 = Methodref          #1.#2   // java/lang/invoke/MethodHandles.lookup:()Ljava/lang/invoke/MethodHandles$Lookup;",
                "   #5 = Methodref          #1.#2   // java/lang/invoke/MethodHandles$Lookup.findVirtual:(Ljava/lang/Class;)V",
                "   #5 = Methodref          #1.#2   // kotlin/reflect/full/KClasses.getMemberFunctions:(Lkotlin/reflect/KClass;)V",
                "   #5 = Methodref          #1.#2   // android/util/Property.of:(Ljava/lang/Class;)Landroid/util/Property;",
                "   #5 = Class              #1      // android/animation/ObjectAnimator",
            ],
            "class loading": [
                "   #5 = Class              #1      // dalvik/system/InMemoryDexClassLoader",
                "   #5 = Methodref          #1.#2   // java/lang/ClassLoader.loadClass:(Ljava/lang/String;)Ljava/lang/Class;",
            ],
            "native code or processes": [
                "   #5 = Methodref          #1.#2   // java/lang/System.loadLibrary:(Ljava/lang/String;)V",
                "   #5 = MethodHandle       5:#1    // REF_invokeVirtual java/lang/Runtime.exec:(Ljava/lang/String;)Ljava/lang/Process;",
                "   #5 = Class              #1      // java/lang/ProcessBuilder",
                "   #5 = Fieldref           #1.#2   // sun/misc/Unsafe.theUnsafe:Lsun/misc/Unsafe;",
            ],
            "raw Binder calls": [
                "   #5 = InterfaceMethodref #1.#2   // android/os/IBinder.transact:(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z",
            ],
            "system log": [
                "   #5 = Methodref          #1.#2   // android/util/Log.d:(Ljava/lang/String;Ljava/lang/String;)I",
                "   #5 = Class              #1      // android/util/Log",
                "   #5 = Fieldref           #1.#2   // java/lang/System.out:Ljava/io/PrintStream;",
                "   #5 = Fieldref           #1.#2   // java/lang/System.err:Ljava/io/PrintStream;",
                "   #5 = Methodref          #1.#2   // java/lang/Throwable.printStackTrace:()V",
                "   #5 = Methodref          #1.#2   // java/util/logging/Logger.info:(Ljava/lang/String;)V",
                "   #5 = Methodref          #1.#2   // java/io/PrintStream.println:(Ljava/lang/Object;)V",
            ],
            "data directory": [
                "   #5 = Methodref          #1.#2   // java/io/File.getParentFile:()Ljava/io/File;",
                "   #5 = Methodref          #1.#2   // java/io/File.getParent:()Ljava/lang/String;",
                "   #5 = InterfaceMethodref #1.#2   // java/nio/file/Path.getParent:()Ljava/nio/file/Path;",
                "   #5 = Methodref          #1.#2   // kotlin/io/FilesKt__UtilsKt.resolveSibling:(Ljava/io/File;Ljava/lang/String;)Ljava/io/File;",
                "   #5 = Methodref          #1.#2   // android/content/Context.getDataDir:()Ljava/io/File;",
                "   #5 = Fieldref           #1.#2   // android/content/pm/ApplicationInfo.dataDir:Ljava/lang/String;",
                "   #5 = Methodref          #1.#2   // android/os/Environment.getDataDirectory:()Ljava/io/File;",
                "   #5 = MethodHandle       5:#1    // REF_invokeVirtual java/io/File.getParentFile:()Ljava/io/File;",
                "   #5 = Methodref          #1.#2   // android/app/Application.getDataDir:()Ljava/io/File;",
                "   #5 = Methodref          #1.#2   // io/x/Up.getParentFile:()Ljava/io/File;",
            ],
        }
        for rule, lines in cases.items():
            for line in lines:
                with self.subTest(line=line):
                    problems = check_javap(javap_class("io.x.Screen", [line], []))
                    self.assertTrue(problems, line)
                    self.assertTrue(all(p.startswith(f"io.x.Screen: {rule}") for p in problems), problems)

    def test_other_parents_are_not_the_data_directory(self) -> None:
        for line in (
            "   #5 = Methodref          #1.#2   // android/view/View.getParent:()Landroid/view/ViewParent;",
            "   #5 = Methodref          #1.#2   // androidx/navigation/NavDestination.getParent:()Landroidx/navigation/NavGraph;",
        ):
            with self.subTest(line=line):
                self.assertEqual(check_javap(javap_class("io.x.Screen", [line], [])), [])

    def test_rejected_members(self) -> None:
        cases = {
            "  public static final native int secret();": "native code or processes",
            "    flags: (0x0109) ACC_PUBLIC, ACC_STATIC, ACC_NATIVE": "native code or processes",
            "  private final android.bluetooth.BluetoothGatt gatt;": "Bluetooth API",
            "  public static final java.lang.reflect.Method[] ms(java.lang.Class<?>);": "reflection",
            "         0: invokevirtual #2 // Method java/lang/reflect/Method.invoke:(Ljava/lang/Object;)V": "reflection",
        }
        for line, rule in cases.items():
            with self.subTest(line=line):
                problems = check_javap(javap_class("io.x.Screen", [], [line]))
                self.assertTrue(problems, line)
                self.assertTrue(all(p.startswith(f"io.x.Screen: {rule}") for p in problems), problems)

    def test_outgoing_frame_bytes_only_in_the_protocol_module(self) -> None:
        frame = "io/github/ridelabz/engwespeedtool/protocol/OutgoingFrame"
        lines = [
            f"   #5 = Methodref          #1.#2   // {frame}.getBytes:()[B",
            f"   #5 = MethodHandle       5:#1    // REF_invokeVirtual {frame}.getBytes:()[B",
            f"         0: invokevirtual #2 // Method {frame}.getBytes:()[B",
        ]
        for line in lines:
            with self.subTest(line=line):
                text = javap_class("io.x.Session", [line] if "#5" in line else [], [] if "#5" in line else [line])
                problems = check_javap(text)
                self.assertTrue(problems, line)
                self.assertTrue(all(p.startswith("io.x.Session: outgoing frame bytes") for p in problems), problems)
                self.assertEqual(check_javap(text, RULES), [], "the protocol module's rules allow it")
        other = f"   #5 = Methodref          #1.#2   // {frame}.getCommand:()Lio/x/BikeCommand;"
        self.assertEqual(check_javap(javap_class("io.x.Session", [other], [])), [])

    def test_only_the_session_module_uses_bike_links(self) -> None:
        session = "io/github/ridelabz/engwespeedtool/session"
        FRAME = "Lio/github/ridelabz/engwespeedtool/protocol/OutgoingFrame"
        rejected = [
            f"   #5 = InterfaceMethodref #1.#2   // {session}/BikeLink.write:(Lio/x/OutgoingFrame;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            f"   #5 = InterfaceMethodref #1.#2   // {session}/BikeLink.getEvents:()Lkotlinx/coroutines/flow/Flow;",
            f"   #5 = InterfaceMethodref #1.#2   // {session}/BikeTransport.connect:(Lio/x/BikeAddress;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            f"   #5 = Methodref          #1.#2   // {session}/FakeBikeTransport.connect:(Lio/x/BikeAddress;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            # Whatever class a frame or an address goes through.
            f"   #5 = Methodref          #1.#2   // io/github/ridelabz/engwespeedtool/ble/AndroidBikeLink.write:({FRAME};Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            f"   #5 = MethodHandle       5:#1    // REF_invokeVirtual io/x/Wrapper.send:(I{FRAME};)V",
            f"   #5 = Methodref          #1.#2   // io/github/ridelabz/engwespeedtool/reporting/SessionLog.outgoing:({FRAME};)V",
            f"   #5 = Methodref          #1.#2   // io/github/ridelabz/engwespeedtool/ble/AndroidBikeTransport.connect:(L{session}/BikeAddress;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            f"         0: invokevirtual #2 // Method io/x/Radio.connect:(L{session}/BikeAddress;)V",
            f"   #5 = Methodref          #1.#2   // io/x/Radio.connect$default:(Lio/x/Radio;L{session}/BikeAddress;ILjava/lang/Object;)V",
        ]
        handovers = {
            f"   #5 = Methodref          #1.#2   // io/github/ridelabz/engwespeedtool/ble/AndroidBikeLink.write:({FRAME};Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            f"   #5 = MethodHandle       5:#1    // REF_invokeVirtual io/x/Wrapper.send:(I{FRAME};)V",
            f"   #5 = Methodref          #1.#2   // io/github/ridelabz/engwespeedtool/reporting/SessionLog.outgoing:({FRAME};)V",
        }
        for line in rejected:
            with self.subTest(line=line):
                pool = line.lstrip().startswith("#")
                problems = check_javap(javap_class("io.x.Screen", [line] if pool else [], [] if pool else [line]))
                rule = "frame handover" if line in handovers else "bike link"
                self.assertTrue(problems and all(p.startswith(f"io.x.Screen: {rule}") for p in problems), problems)
        allowed = [
            f"   #5 = Methodref          #1.#2   // {session}/BikeSession.start:(Lio/x/BikeAddress;)V",
            f"   #5 = Methodref          #1.#2   // {session}/FakeBikeTransport.\"<init>\":(Ljava/util/List;)V",
            f"   #5 = InterfaceMethodref #1.#2   // {session}/BikeTransport.getRadio:()Lkotlinx/coroutines/flow/StateFlow;",
            f"   #5 = Class              #1      // {session}/contract/BikeLinkContract",
            f"   #5 = Methodref          #1.#2   // io/github/ridelabz/engwespeedtool/protocol/OutgoingFrame.isEchoedBy:(Lio/x/Frame;)Z",
            f"   #5 = Methodref          #1.#2   // io/github/ridelabz/engwespeedtool/protocol/BikeCommand.toFrame:(){FRAME};",
            f"   #5 = Methodref          #1.#2   // io/github/ridelabz/engwespeedtool/protocol/Sanitiser.outgoing:({FRAME};)Lio/x/Loggable;",
            f"   #5 = MethodType         #1      //  ({FRAME};)V",
            f"   #5 = Methodref          #1.#2   // {session}/BikeSession.start:(L{session}/BikeAddress;)V",
        ]
        for line in allowed:
            with self.subTest(line=line):
                self.assertEqual(check_javap(javap_class("io.x.Screen", [line], [])), [])

    def test_exemptions_go_by_build_location(self) -> None:
        self.assertEqual(
            EXEMPT,
            {
                "protocol": {"outgoing frame bytes"},
                "session": {"bike link", "frame handover"},
                "reporting": {"frame handover"},
            },
        )
        root = Path("/repo")
        every = set(RULES) | {"outgoing frame bytes", "bike link", "frame handover"}
        cases = {
            "protocol/build/classes/kotlin/main/io/x/Sanitiser.class": every - {"outgoing frame bytes"},
            "session/build/classes/kotlin/main/io/x/BikeSession.class": every - {"bike link", "frame handover"},
            "reporting/build/classes/kotlin/main/io/x/SessionLog.class": every - {"frame handover"},
            "app/build/intermediates/built_in_kotlinc/prTest/compilePrTestKotlin/classes/io/x/Screen.class": every,
            # An app class in the protocol module's package still gets every rule.
            "app/build/intermediates/built_in_kotlinc/prTest/compilePrTestKotlin/classes/io/github/ridelabz/"
            "engwespeedtool/protocol/X.class": every,
        }
        for rel, expected in cases.items():
            with self.subTest(rel=rel):
                self.assertEqual(set(rules_for(root / rel, root)), expected)

    def test_descriptors_are_skipped_only_for_bootstrap_methods(self) -> None:
        descriptor = f"({LOOKUP})Ljava/lang/invoke/CallSite;"
        ok = f"   #5 = Methodref          #1.#2   // java/lang/invoke/LambdaMetafactory.metafactory:{descriptor}"
        self.assertEqual(check_javap(javap_class("io.x.Screen", [ok], [])), [])
        other = f"   #5 = Methodref          #1.#2   // io/x/Helper.metafactory:{descriptor}"
        problems = check_javap(javap_class("io.x.Screen", [other], []))
        self.assertTrue(problems and all(p.startswith("io.x.Screen: reflection") for p in problems), problems)

    def test_a_missing_brace_does_not_hide_the_next_class(self) -> None:
        first = javap_class("io.x.A", [], ["  public void f();"]).replace("}\n", "")
        second = javap_class("io.x.B", ["   #5 = Class              #1      // java/lang/ProcessBuilder"], [])
        self.assertEqual(check_javap(first + second), ["io.x.B: native code or processes (java/lang/ProcessBuilder)"])

    def test_header_forms(self) -> None:
        tail = "Constant pool:\n   #5 = Class              #1      // java/lang/ProcessBuilder\n"
        for header in ("public final class io.x.A", "final class io.x.A", "public interface io.x.A", "public abstract class io.x.A"):
            with self.subTest(header=header):
                text = f"Classfile /a.class\n{header}\n{tail}"
                self.assertEqual(check_javap(text), ["io.x.A: native code or processes (java/lang/ProcessBuilder)"])
        text = "Classfile /a.class\npublic final class io.x.A\n{\n}\nNestHost: class io/x/Outer\n" + tail
        self.assertEqual(check_javap(text), ["io.x.A: native code or processes (java/lang/ProcessBuilder)"])

    def test_unknown_constant_kinds_are_checked_whole(self) -> None:
        line = "   #5 = Dynamic            #0:#1   // #0:_:Landroid/bluetooth/BluetoothGatt;"
        self.assertTrue(check_javap(javap_class("io.x.Screen", [line], [])))

    def test_classes_are_chosen_by_build_location(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            wanted = [
                "protocol/build/classes/kotlin/main/a/A.class",
                "session/build/classes/kotlin/main/b/B.class",
                "reporting/build/classes/kotlin/main/c/C.class",
                "app/build/intermediates/built_in_kotlinc/prTest/compilePrTestKotlin/classes/io/x/ble/Fake.class",
                "app/build/intermediates/javac/prTest/compilePrTestJavaWithJavac/classes/io/x/BuildConfig.class",
            ]
            ignored = [
                "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/io/x/D.class",
                "app/build/intermediates/classes/prTest/transformPrTestClassesWithAsm/jars/x/Lib.class",
                "ble-android/build/intermediates/built_in_kotlinc/prTest/compilePrTestKotlin/classes/io/x/ble/L.class",
            ]
            for rel in wanted + ignored:
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_bytes(b"")
            self.assertEqual(sorted(p.relative_to(root).as_posix() for p in class_files(root)), sorted(wanted))

    def test_the_release_variant_reads_the_release_classes_only(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            wanted = [
                "protocol/build/classes/kotlin/main/a/A.class",
                "app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes/io/x/Screen.class",
                "app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes/io/x/BuildConfig.class",
            ]
            ignored = [
                "app/build/intermediates/built_in_kotlinc/prTest/compilePrTestKotlin/classes/io/x/Screen.class",
                "app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/io/x/D.class",
                "app/build/intermediates/built_in_kotlinc/releaseUnitTest/compileReleaseUnitTestKotlin/classes/T.class",
                "app/build/intermediates/classes/release/transformReleaseClassesWithAsm/jars/x/Lib.class",
                "ble-android/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes/io/x/L.class",
            ]
            for rel in wanted + ignored:
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_bytes(b"")
            found = sorted(p.relative_to(root).as_posix() for p in class_files(root, "release"))
            self.assertEqual(found, sorted(wanted))

    def test_a_checkout_under_a_folder_named_like_the_variant_reads_the_same_classes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "release" / "repo"
            wanted = "app/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes/io/x/S.class"
            ignored = "app/build/intermediates/built_in_kotlinc/releaseUnitTest/compileReleaseUnitTestKotlin/T.class"
            for rel in (wanted, ignored):
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_bytes(b"")
            self.assertEqual([p.relative_to(root).as_posix() for p in class_files(root, "release")], [wanted])

    def test_arguments_name_the_variant_and_the_root_in_any_order(self) -> None:
        self.assertEqual(arguments(["x"])[0], "prTest")
        self.assertEqual(arguments(["x", "--variant", "release"])[0], "release")
        self.assertEqual(arguments(["x", "--variant=release", "/tmp/r"]), ("release", Path("/tmp/r").resolve()))
        self.assertEqual(arguments(["x", "/tmp/r", "--variant", "release"]), ("release", Path("/tmp/r").resolve()))
        for bad in (["x", "--variant", "../x"], ["x", "--variant", ""], ["x", "--variant"], ["x", "a", "b"]):
            with self.assertRaises(SystemExit, msg=str(bad)), contextlib.redirect_stderr(io.StringIO()):
                arguments(bad)

    def test_missing_module_classes_fail_only_for_modules_with_sources(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual(class_files(root), [])
            (root / "session/src/main/kotlin/a").mkdir(parents=True)
            (root / "session/src/main/kotlin/a/A.kt").write_text("package a\n")
            with self.assertRaises(SystemExit):
                class_files(root)


if __name__ == "__main__":
    unittest.main()
