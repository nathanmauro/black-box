#!/usr/bin/env python3
"""Verify the real formatter on disposable fixtures; never apply it to repository Java sources."""
import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "scripts/format/BlankBeforeReturn.java"


def run(*arguments, source=None, cwd=ROOT, check=True):
    result = subprocess.run(arguments, cwd=cwd, input=source, text=True, capture_output=True,
                            check=False, timeout=120)
    if check and result.returncode != 0:
        raise AssertionError(result.stdout + result.stderr)
    return result


class ReturnSpacingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT / "target").mkdir(exist_ok=True)
        cls.temporary = tempfile.TemporaryDirectory(prefix="format-fixtures-", dir=ROOT / "target")
        cls.directory = Path(cls.temporary.name)
        cls.classes = cls.directory / "helper"
        run("javac", "-d", str(cls.classes), str(HELPER))

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def format(self, source, check=True):
        return run("java", "-cp", str(self.classes), "BlankBeforeReturn", source=source, check=check)

    def test_first_return_and_nested_returns(self):
        before = "class A {\n    int f(boolean yes) {\n        if (yes) {\n            return 1;\n        }\n        return 2;\n    }\n}\n"
        after = self.format(before).stdout
        self.assertIn("if (yes) {\n\n            return", after)
        self.assertIn("        }\n\n        return", after)
        self.assertEqual(after, self.format(after).stdout)

    def test_leading_comments_stay_with_return_and_trailing_comment_stays_with_previous_code(self):
        before = "class A {\n    int f() {\n        int x = 1; // previous\n        /* explanation\n         * continued */\n        // final note\n        return x;\n    }\n}\n"
        after = self.format(before).stdout
        self.assertIn("int x = 1; // previous\n\n        /* explanation", after)
        self.assertIn("// final note\n        return x;", after)
        self.assertEqual(after, self.format(after).stdout)

    def test_literals_comments_and_text_blocks_are_not_return_statements(self):
        source = '''class A {
    String f() {
        String ordinary = "return fake; \\\" quoted";
        char quote = '\\'';
        String block = """
            return fake;
            // return comment-looking text
            /* return block-looking text */
            """;
        // return fake;
        return ordinary + quote + block;
    }
}
'''
        expected = source.replace("        // return fake;", "\n        // return fake;")
        self.assertEqual(expected, self.format(source).stdout)

    def test_non_block_return_gets_spacing_without_guessing_comment_attachment(self):
        source = "class A {\n    int f(boolean yes) {\n        if (yes)\n            return 1;\n        label:\n            return 2;\n    }\n}\n"
        after = self.format(source).stdout
        self.assertIn("if (yes)\n\n            return", after)
        self.assertIn("label:\n\n            return", after)

    def test_leading_comments_in_switch_and_unbraced_control_bodies_stay_attached(self):
        bodies = [
            "if (yes)\n            // reason\n            return 1;",
            "if (yes) {} else\n            // reason\n            return 1;",
            "while (yes)\n            // reason\n            return 1;",
            "for (int i = 0; i < 1; i++)\n            // reason\n            return 1;",
            "for (;;)\n            // reason\n            return 1;",
            "for (String item : new String[0])\n            // reason\n            return 1;",
            "do\n            // reason\n            return 1; while (yes);",
            "label:\n            // reason\n            return 1;",
            "switch (1) {\n            case 1:\n                // reason\n                return 1;\n        }",
        ]
        for body in bodies:
            with self.subTest(body=body):
                source = "class A {\n    int f(boolean yes) {\n        " + body + "\n        return 0;\n    }\n}\n"
                after = self.format(source).stdout
                self.assertNotIn("// reason\n\n", after)
                self.assertTrue("\n\n            // reason" in after or "\n\n                // reason" in after)
                self.assertEqual(after, self.format(after).stdout)

    def test_inline_unbraced_return_and_inline_leading_comment_are_split_safely(self):
        source = "class A {\n    int f(boolean yes) {\n        if (yes) /* explain */ return 1;\n        return 2;\n    }\n}\n"
        after = self.format(source).stdout
        self.assertIn("if (yes)\n\n            /* explain */ return 1;", after)
        self.assertEqual(after, self.format(after).stdout)

    def test_crlf_is_preserved(self):
        source = "class A {\r\n    int f() {\r\n        return 1;\r\n    }\r\n}\r\n"
        raw = subprocess.run(["java", "-cp", str(self.classes), "BlankBeforeReturn"],
                             input=source.encode(), capture_output=True, check=True, timeout=20).stdout
        self.assertEqual(source.replace("        return", "\r\n        return").encode(), raw)

    def test_parse_errors_and_escaped_trivia_fail_without_output(self):
        sources = ["class A { int f( { return 1; } }",
                   "class A {\n int f() {\\u0020\n  return 1;\n }\n}\n"]
        for source in sources:
            result = self.format(source, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")

    def test_real_maven_pipeline_is_idempotent_preserves_runtime_values_and_changes_no_repository_java(self):
        originals = {p: hashlib.sha256(p.read_bytes()).digest() for p in (ROOT / "src").rglob("*.java")}
        fixture = self.directory / "input"
        fixture.mkdir()
        program = fixture / "Program.java"
        program.write_text('''import java.util.function.IntSupplier;
public class Program {
public static void main(String[] args) { System.out.print(text()+number(true)+nested()); }
static String text() { String x="return literal;"; String block="""
  return text block;
  // not a comment
  """; // stays with assignment
// describes result
return x+block; }
static int number(boolean yes) { if(yes) return 7; label: return 8; }
static int nested() { IntSupplier s=()->{return 3;}; class Local {int value(){return 4;}}
switch(s.getAsInt()) {case 3: return new Local().value();default: return 0;} }
}
''')
        before_classes = self.directory / "before"
        after_classes = self.directory / "after"
        run("javac", "-d", str(before_classes), str(program))
        before_output = run("java", "-cp", str(before_classes), "Program").stdout
        include = str(fixture.relative_to(ROOT)) + "/*.java"
        command = ("mvn", "-q", "-Pjava-format", "-Djava.format.includes=" + include)
        first_check = run(*command, "spotless:check", check=False)
        self.assertNotEqual(first_check.returncode, 0, "Unformatted fixture should fail check before apply")
        run(*command, "spotless:apply")
        first = program.read_bytes()
        run(*command, "spotless:check")
        run(*command, "spotless:apply")
        self.assertEqual(first, program.read_bytes())
        run("javac", "-d", str(after_classes), str(program))
        self.assertEqual(before_output, run("java", "-cp", str(after_classes), "Program").stdout)
        self.assertEqual(originals, {p: hashlib.sha256(p.read_bytes()).digest()
                                    for p in (ROOT / "src").rglob("*.java")})
        self.assertIn(b"// describes result\n        return", first)


if __name__ == "__main__":
    unittest.main()
