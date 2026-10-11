"""The Kotlin reader is a CLOSED grammar, not a best-effort scanner.

Every test here runs the PRODUCTION reader (api_contracts_support.
parse_kotlin_file) on a mutated copy of a real contract file (or a minimal
Kotlin file for the construct shapes). On the earlier reader each of these
was silently skipped or mis-scoped -- that is the RED these tests reproduce.
On the closed reader the refusal names the file and the line, and the
constructs the grammar recognises (trailing comments on parameters,
') {' as the constructor end, whole-line comments) are read or refused as
the grammar's docstring states.

The reader's stated choices (also in the module docstring):
- a `var` parameter is a REFUSAL (parameters are val);
- a trailing // comment on a parameter line is STRIPPED and the parameter is
  READ;
- whole-line comments in the constructor region are recognised and SKIPPED
  (pinned by test_reader_skips_whole_line_comment_in_constructor);
- the constructor region ends at the first line that is exactly ')' or
  ') {';
- after ') {' the class body recognises only init blocks, and init blocks
  recognise only require/check calls.
"""
import unittest
import sys
import os
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import parse_kotlin_file, KOTLIN_DIR

JOB_KT = KOTLIN_DIR / "Job.kt"


def _write_kt(text):
    f = tempfile.NamedTemporaryFile(mode="w", suffix=".kt", delete=False)
    f.write(text)
    f.close()
    return f.name


def _parse_and_clean(text, label=""):
    name = _write_kt(text)
    try:
        return parse_kotlin_file(name)
    finally:
        os.unlink(name)


class ReaderClosedGrammarTest(unittest.TestCase):
    """The production reader, closed grammar, pinned construct by construct."""

    def test_reader_refuses_var_field(self):
        """A `var` constructor parameter is refused (RED on base, which
        silently returned the unchanged field list)."""
        text = JOB_KT.read_text(encoding="utf-8")
        mutant = text.replace(
            "    val status: JobStatus,",
            "    val status: JobStatus,\n    var retries: Int = 0,",
        )
        self.assertNotEqual(mutant, text, "mutation anchor missing (vacuous)")
        name = _write_kt(mutant)
        try:
            with self.assertRaises(ValueError) as ctx:
                parse_kotlin_file(name)
            msg = str(ctx.exception)
            self.assertIn(
                os.path.basename(name), msg,
                "refusal must name the file it is refusing",
            )
            self.assertIn("var", msg, "refusal must say what was refused")
            self.assertRegex(msg, r":\d+:", "refusal must name the line")
        finally:
            os.unlink(name)

    def test_reader_reads_trailing_comment_field(self):
        """C1: a `val` with a trailing comment is READ (comment stripped),
        not refused. RED on base: the field is missing from the parse
        (base form probed: the parameter line with a trailing // comment
        and no default does not match the old parameter pattern)."""
        text = (
            "package test\n"
            "\n"
            "data class Foo(\n"
            "    val a: Int,\n"
            "    val note: String // keep\n"
            ")\n"
        )
        parsed = _parse_and_clean(text)
        (class_name, fields, rules) = parsed["data_classes"][0]
        by_name = {f[0]: f for f in fields}
        self.assertIn("note", by_name,
                      "the trailing-comment parameter must be read as a field")
        self.assertEqual(by_name["note"][1], "String",
                         "the comment must be stripped, leaving type String")
        self.assertFalse(by_name["note"][2], "note is non-nullable")
        self.assertIn("a", by_name)

    def test_reader_reads_trailing_comment_with_default(self):
        """C1 (second form): trailing comment after a default value is
        stripped the same way; the default is part of the parameter, not
        the field's type."""
        text = (
            "package test\n"
            "\n"
            "data class Foo(\n"
            "    val a: Int,\n"
            '    val note: String = "x" // keep\n'
            ")\n"
        )
        parsed = _parse_and_clean(text)
        by_name = {f[0]: f for f in parsed["data_classes"][0][1]}
        self.assertIn("note", by_name,
                      "the default-with-comment parameter must be read")
        self.assertEqual(by_name["note"][1], "String")

    def test_reader_refuses_trailing_comment_on_var(self):
        """The comment-strip does not rescue a `var`: it is still a refusal."""
        text = (
            "package test\n"
            "\n"
            "data class Foo(\n"
            "    val a: Int,\n"
            "    var retries: Int = 0 // keep\n"
            ")\n"
        )
        name = _write_kt(text)
        try:
            with self.assertRaises(ValueError) as ctx:
                parse_kotlin_file(name)
            self.assertIn("var", str(ctx.exception))
        finally:
            os.unlink(name)

    def test_reader_stops_constructor_at_brace_init(self):
        """The constructor region ends at ') {': a body-level local is NOT
        read as a phantom field (base ran the scan past ') {' and read the
        local as a parameter)."""
        text = (
            "package test\n"
            "\n"
            "data class Foo(\n"
            "    val a: Int\n"
            ") {\n"
            "    val x: Int = 0\n"
            "}\n"
        )
        name = _write_kt(text)
        try:
            try:
                parsed = parse_kotlin_file(name)
            except ValueError:
                return  # the closed reader refuses body-level properties: correct
            fields = parsed["data_classes"][0][1]
            names = {f[0] for f in fields}
            self.assertNotIn("x", names,
                             "phantom field read past ') {' -- the scan did not stop")
        finally:
            os.unlink(name)

    def test_reader_refuses_unknown_line_in_constructor(self):
        """A construct the grammar does not list (a companion object inside
        the constructor region) is refused with file and line."""
        text = (
            "package test\n"
            "\n"
            "data class Foo(\n"
            "    val a: Int,\n"
            "    companion object {\n"
            "    }\n"
            ")\n"
        )
        name = _write_kt(text)
        try:
            with self.assertRaises(ValueError) as ctx:
                parse_kotlin_file(name)
            msg = str(ctx.exception)
            self.assertIn(".kt", msg, "refusal must name the file")
            self.assertRegex(msg, r":\d+:", "refusal must name the line")
            self.assertIn(
                "unrecognised line in constructor parameter region", msg,
                "the generic constructor-line refusal must name its own message")
        finally:
            os.unlink(name)

    def test_reader_refuses_class_level_property(self):
        """A class-level property outside an init block is a refusal (the
        grammar's class body is init-only)."""
        text = (
            "package test\n"
            "\n"
            "data class Foo(\n"
            "    val a: Int\n"
            ") {\n"
            "    val cached: Int = 1\n"
            "    init {\n"
            "    }\n"
            "}\n"
        )
        name = _write_kt(text)
        try:
            with self.assertRaises(ValueError) as ctx:
                parse_kotlin_file(name)
            self.assertIn("class body", str(ctx.exception))
        finally:
            os.unlink(name)

    def test_reader_skips_whole_line_comment_in_constructor(self):
        """PIN: whole-line comments in the constructor region are recognised
        and skipped (the stated choice), so parameters after them are read."""
        text = (
            "package test\n"
            "\n"
            "data class Foo(\n"
            "    val a: Int,\n"
            "    // a whole-line comment\n"
            "    val b: Int\n"
            ")\n"
        )
        parsed = _parse_and_clean(text)
        names = {f[0] for f in parsed["data_classes"][0][1]}
        self.assertEqual(names, {"a", "b"},
                         "whole-line comment skipped; both parameters read")

    def test_real_files_parse_clean_and_counted(self):
        """The vacuity guard: every real contract file parses, and the parse
        counts what the files should have found."""
        for kt in sorted(KOTLIN_DIR.glob("*.kt")):
            parsed = parse_kotlin_file(kt)
            self.assertTrue(
                parsed["enums"] or parsed["data_classes"],
                f"{kt.name}: vacuity guard tripped -- nothing recognised"
            )
        # the specific counts the contract promises
        job = [d for d in parse_kotlin_file(JOB_KT)["data_classes"] if d[0] == "Job"][0]
        self.assertEqual({f[0] for f in job[1]},
                         {"status", "result", "error"},
                         "Job must expose exactly status, result, error")
        self.assertTrue(any("FAILED" in r and "error" in r for r in job[2]),
                        "Job's init must carry the failed<->error both-way rule")
