import tempfile
import unittest
from pathlib import Path

import check_ui_strings as c


def texts(source: str) -> list[str]:
    return [lit.text for lit in c.literals(source) if not lit.in_annotation]


class LiteralTest(unittest.TestCase):
    def test_plain_strings(self):
        self.assertEqual(texts('Text("Hello")\nval x = ""'), ["Hello", ""])

    def test_escapes(self):
        self.assertEqual(texts(r'val a = "say \"hi\"\n\u0041"'), ['say "hi"\nA'])

    def test_templates_leave_only_the_literal_parts(self):
        self.assertEqual(texts('val a = "$speed km/h"'), [" km/h"])
        self.assertEqual(texts('val a = "${f("x")}"'), ["x", ""])
        self.assertEqual(texts('val a = "${n}"'), [""])
        self.assertEqual(texts('val a = "${ mapOf(1 to 2).size }"'), [""])

    def test_tricky_templates(self):
        self.assertEqual(texts('val a = "${ if (a) "}" else "b" }tail"'), ["}", "b", "tail"])
        self.assertEqual(texts("val a = \"${ c == '}' }x\""), ["x"])
        self.assertEqual(texts('val a = "${ xs.map { "item" } /* "c" */ }"'), ["item", ""])
        self.assertEqual(texts('val a = "\\$name"'), ["$name"])

    def test_multi_dollar_strings(self):
        self.assertEqual(texts('val a = $$"$Hello $$name"'), ["$Hello "])
        self.assertEqual(texts('val a = $$"""cost: $5 $${x}"""'), ["cost: $5 "])

    def test_raw_strings(self):
        self.assertEqual(texts('val a = """line\n"quoted" $x"""'), ['line\n"quoted" '])
        self.assertEqual(texts('val a = """ends in a quote""""'), ['ends in a quote"'])

    def test_comments_and_chars_are_not_strings(self):
        source = '// "comment"\n/* "block /* nested */ still" */\nval c = \'"\'\nval d = \'\\\'\''
        self.assertEqual(texts(source), [])

    def test_line_numbers(self):
        self.assertEqual([lit.line for lit in c.literals('\n\nText(\n  "a"\n)')], [4])

    def test_annotation_arguments(self):
        lits = c.literals('@Suppress("Unused") fun f() = Text("Shown")\n@Preview(name = "x") @A.B("y")')
        self.assertEqual([(lit.text, lit.in_annotation) for lit in lits],
                         [("Unused", True), ("Shown", False), ("x", True), ("y", True)])

    def test_a_call_is_not_an_annotation(self):
        lits = c.literals('foo("text")\nobj.bar ("more")\n@param:StringRes val x = y("z")\n'
                          'this@App.show(Text("Hello"))\nsuper@X.f("y")')
        self.assertEqual([lit.in_annotation for lit in lits], [False, False, False, False, False])

    def test_annotations_with_use_site_targets(self):
        lits = c.literals('@file:Suppress("x")\n@get:JvmName("y") val a = 1\n@param:Ann(name = "z") val b: Int')
        self.assertEqual([lit.in_annotation for lit in lits], [True, True, True])


class CheckTest(unittest.TestCase):
    def test_reports_text_but_not_symbols(self):
        problems = c.check_source("a.kt", 'Text("Back")\nval sep = " · "\nval n = "%1\\$d"\nval e = ""')
        self.assertEqual(len(problems), 2)  # "Back" and the format with a letter
        self.assertIn("a.kt:1:", problems[0])

    def test_allowed_values(self):
        rel = "app/src/main/kotlin/io/github/ridelabz/engwespeedtool/ui/theme/Type.kt"
        self.assertEqual(c.check_source(rel, 'val t = "tnum"'), [])
        self.assertEqual(len(c.check_source(rel, 'val t = "kern"')), 1)

    def test_only_shipped_source_sets_of_the_app(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            for path in ("app/src/main/kotlin/a/A.kt", "app/src/prTest/kotlin/a/B.kt", "app/src/test/kotlin/a/C.kt",
                         "app/src/androidTest/kotlin/a/D.kt", "protocol/src/main/kotlin/a/E.kt"):
                f = root / path
                f.parent.mkdir(parents=True)
                f.write_text('val s = "Text"\n')
            problems = c.check(root)
            self.assertEqual([p.split(":")[0] for p in problems],
                             ["app/src/main/kotlin/a/A.kt", "app/src/prTest/kotlin/a/B.kt"])

    def test_the_repository_is_clean(self):
        self.assertEqual(c.check(Path(__file__).resolve().parent.parent), [])


if __name__ == "__main__":
    unittest.main()
