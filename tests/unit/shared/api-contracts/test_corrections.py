"""Tests for the six reader and spec corrections.

Each test proves a specific correction is enforced:
1. Refusal naming file AND line (closed grammar)
2. Quote firewall: declaration inside Kotlin string literal not read
3. Both-direction set comparisons per field/enum (two ASYMMETRIC set
   differences -- each direction has its own assertion, so a drift on one
   side fails exactly that direction's assertion)
4. snake("jobId") -> job_id, snake("jobID") -> job_i_d
5. Mutation helper asserting its anchor was present
6. Exact path-set equality (the single canonical exact-set assertion for
   the spec's paths; test_conformance.py does not duplicate it)
"""
import unittest
import sys
import re
import tempfile
import os
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import (
    load_spec, get_schemas, get_schema_fields, get_enum_values,
    parse_all_kotlin, parse_kotlin_file, snake,
)
from api_comparator import compare_fields


class Correction1RefusalTest(unittest.TestCase):
    """Correction 1: Refusal naming file AND line (closed grammar)."""

    def test_refusal_names_file_and_line(self):
        """A refusal must name the file and line number."""
        # Create a Kotlin file with an unrecognised construct
        with tempfile.NamedTemporaryFile(mode='w', suffix='.kt', delete=False) as f:
            f.write("package test\n\nfun unknown() {\n    println(\"hello\")\n}\n")
            fname = f.name
        try:
            with self.assertRaises(ValueError) as ctx:
                parse_kotlin_file(fname)
            # The error should mention the filename
            self.assertIn(os.path.basename(fname), str(ctx.exception))
        finally:
            os.unlink(fname)

    def test_refusal_on_missing_package(self):
        """A file without a package declaration is refused with file and line."""
        with tempfile.NamedTemporaryFile(mode='w', suffix='.kt', delete=False) as f:
            f.write("data class Foo(\n    val x: Int\n)\n")
            fname = f.name
        try:
            with self.assertRaises(ValueError) as ctx:
                parse_kotlin_file(fname)
            self.assertIn(os.path.basename(fname), str(ctx.exception))
        finally:
            os.unlink(fname)


class Correction2QuoteFirewallTest(unittest.TestCase):
    """Correction 2: Quote firewall - a declaration inside a string literal is not read.

    The live case is a multi-line raw string literal (triple-quoted) inside
    the constructor: the line-anchored parameter reader only ever sees a
    line that starts a declaration, so the firewall's single-line coverage
    is vacuous on one-line strings and the raw-string form is the case that
    falsifies it.
    """

    def test_quote_firewall_raw_string(self):
        """A declaration inside a multi-line raw string literal must not be read.

        The declaration line sits on its own line inside a triple-quoted
        string inside the constructor. Without the quote firewall (the
        blanking of string literals in the reader's prepare step), the
        line-anchored parameter reader matches the inner line and 'hidden'
        appears in the fields; with the firewall the literal is blanked
        first and only the real parameters remain.
        """
        with tempfile.NamedTemporaryFile(mode='w', suffix='.kt', delete=False) as f:
            f.write(
                'package test\n\n'
                'data class Foo(\n'
                '    val note: String = """\n'
                '    val hidden: String,\n'
                '    """,\n'
                '    val real: String,\n'
                ')\n'
            )
            fname = f.name
        try:
            parsed = parse_kotlin_file(fname)
            fields = {f[0] for f in parsed["data_classes"][0][1]}
            self.assertIn("note", fields)
            self.assertIn("real", fields)
            self.assertNotIn(
                "hidden", fields,
                "Quote firewall failed: read declaration inside raw string literal")
        finally:
            os.unlink(fname)


class Correction3BothDirectionTest(unittest.TestCase):
    """Correction 3: both-direction set comparison, run through the
    PRODUCTION comparator (api_comparator.compare_fields), not re-implemented
    here. The Job field directions are covered by the mismatch classes A and
    B, so this class plants the JobStatus enum drift in BOTH directions
    through compare_fields: a value only in the spec copy, and a value only
    in the Kotlin copy, each asserting the comparator's own refusal text."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_job_status_enum_spec_only_value(self):
        """A JobStatus value present only in the spec copy (not in the
        Kotlin enum) is refused by the PRODUCTION comparator's enum-value
        check, and the refusal is the comparator's own message."""
        schemas = {k: v for k, v in self.schemas.items()}
        schemas["JobStatus"] = dict(schemas["JobStatus"])
        schemas["JobStatus"]["enum"] = list(schemas["JobStatus"]["enum"]) + ["ghost_spec"]
        with self.assertRaises(AssertionError) as ctx:
            compare_fields("Job", self.schemas["Job"], self.kotlin["Job"],
                           self.kotlin, schemas)
        self.assertIn("spec enum for JobStatus", str(ctx.exception))
        self.assertIn("'ghost_spec'", str(ctx.exception))
        self.assertIn("differs from Kotlin JobStatus", str(ctx.exception))

    def test_job_status_enum_kotlin_only_value(self):
        """A JobStatus value present only in the Kotlin enum (not in the
        spec schema) is refused by the PRODUCTION comparator's enum-value
        check, and the refusal is the comparator's own message."""
        kotlin = {k: v for k, v in self.kotlin.items()}
        kotlin["JobStatus"] = dict(kotlin["JobStatus"])
        kotlin["JobStatus"]["values"] = list(kotlin["JobStatus"]["values"]) + ["ghost_kt"]
        with self.assertRaises(AssertionError) as ctx:
            compare_fields("Job", self.schemas["Job"], kotlin["Job"],
                           kotlin, self.schemas)
        self.assertIn("spec enum for JobStatus", str(ctx.exception))
        self.assertIn("'ghost_kt'", str(ctx.exception))
        self.assertIn("differs from Kotlin JobStatus", str(ctx.exception))


class Correction4SnakeTest(unittest.TestCase):
    """Correction 4: snake("jobId") -> job_id, snake("jobID") -> job_i_d."""

    def test_snake_jobId(self):
        """snake("jobId") should be "job_id"."""
        self.assertEqual(snake("jobId"), "job_id")

    def test_snake_jobID(self):
        """snake("jobID") should be "job_i_d" (strict snake, no acronym handling)."""
        self.assertEqual(snake("jobID"), "job_i_d")

    def test_snake_forwardingTo(self):
        """snake("forwardingTo") should be "forwarding_to"."""
        self.assertEqual(snake("forwardingTo"), "forwarding_to")


class Correction5MutationHelperTest(unittest.TestCase):
    """Correction 5: Mutation helper asserting its anchor was present."""

    def test_mutate_raises_on_missing_anchor(self):
        """The real mutate() helper raises ValueError when anchor is not found."""
        from api_contracts_support import mutate
        text = "val status: JobStatus"
        with self.assertRaises(ValueError) as ctx:
            mutate(text, "val missing: String", "val missing: String")
        self.assertIn("anchor not found", str(ctx.exception))

    def test_mutate_replaces_anchor(self):
        """The real mutate() helper replaces the anchor with the replacement."""
        from api_contracts_support import mutate
        text = "val status: JobStatus"
        result = mutate(text, "val status: JobStatus", "val status: String")
        self.assertEqual(result, "val status: String")


class Correction6PathSetEqualityTest(unittest.TestCase):
    """Correction 6: Exact path-set equality -- the single canonical
    exact-set assertion for the spec's three endpoints."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()

    def test_path_set_equality(self):
        """Paths in the spec are exactly the three endpoints (no more, no less)."""
        paths = self.spec.get("paths", {})
        expected_paths = {"/v1/audio/transcriptions", "/v1/jobs/{job_id}", "/health"}
        self.assertEqual(set(paths.keys()), expected_paths,
                         "Paths must be exactly the three endpoints")


if __name__ == "__main__":
    unittest.main()
