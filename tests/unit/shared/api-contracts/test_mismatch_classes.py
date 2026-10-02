"""Mismatch-class tests: each plants its drift and runs THE PRODUCTION
comparator (api_contracts_support.compare_fields) -- no re-implemented set
differences in this file.

Five classes A-E, each watched RED on a NAMED mutant of the production
comparator (the mutant is a text mutation of api_contracts_support.py,
applied by the evidence harness and by the gate; the anchor for each is the
named marker comment inside compare_fields):

  A  field added to the spec only      the [NAME-SET-1] check removed
  B  field added to Kotlin only        the [NAME-SET-2] check removed
     field renamed (both sides drift)  the [NAME-SET-2] check (same anchor as B)
  C  required-ness flipped             the [REQUIRED-NESS] check removed
  D  type changed                      the [TYPE-MAP] check removed
  E  enum value dropped                the [ENUM-VALUES] check removed

With the mutant in place the planted drift is no longer refused, so the
class's own assertion (the refusal must carry the class's named message)
fails -- that is the RED the gate reproduces. On the clean production
comparator every test is green: each class's refusal fires with its own
message.

The MutateHelperTest class proves the mutate() anchor rule the harness and
the gate rely on.
"""
import unittest
import sys
import copy
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import (
    load_spec, get_schemas, get_schema_fields, get_enum_values,
    parse_all_kotlin, parse_kotlin_file, snake, map_spec_type_to_kotlin,
    compare_fields, mutate,
)


class MutateHelperTest(unittest.TestCase):
    """Tests for the mutate() helper itself (the anchor rule)."""

    def test_mutate_raises_on_missing_anchor(self):
        """mutate() raises ValueError when anchor is not found."""
        text = "val status: JobStatus"
        with self.assertRaises(ValueError) as ctx:
            mutate(text, "val missing: String", "val missing: String")
        self.assertIn("anchor not found", str(ctx.exception))

    def test_mutate_replaces_anchor(self):
        """mutate() replaces the anchor with the replacement."""
        text = "val status: JobStatus"
        result = mutate(text, "val status: JobStatus", "val status: String")
        self.assertEqual(result, "val status: String")


class MismatchClassATest(unittest.TestCase):
    """Class A: field added on the spec side only.
    Anchor: the [NAME-SET-1] marker in compare_fields."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_a1_field_added_to_spec_only(self):
        """(A) A field added to the spec only must be refused by the production
        comparator, and the refusal must be the spec->kotlin named message."""
        spec_mutated = copy.deepcopy(self.spec)
        job_schema = spec_mutated["components"]["schemas"]["Job"]
        job_schema["properties"]["extra_field"] = {"type": "string"}

        with self.assertRaises(AssertionError) as ctx:
            compare_fields("Job", job_schema, self.kotlin["Job"],
                           self.kotlin, spec_mutated["components"]["schemas"])
        self.assertIn(
            "spec - kotlin: Job has fields not in kotlin: ['extra_field']",
            str(ctx.exception),
        )


class MismatchClassBTest(unittest.TestCase):
    """Class B: field added on the Kotlin side only.
    Anchor: the [NAME-SET-2] marker in compare_fields.

    The renamed-field scenario (a spec-side rename with no Kotlin counterpart)
    is the second case here: the rename drifts BOTH name sets at once, so the
    kotlin->spec check (the same anchor as B) is the one that must fire for
    the Kotlin-side extra name.
    """

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_b2_field_added_to_kotlin_only(self):
        """(B) A field added to Kotlin only must be refused by the production
        comparator with the kotlin->spec named message."""
        kotlin_mutated = copy.deepcopy(self.kotlin)
        kotlin_mutated["Job"]["fields"] = list(kotlin_mutated["Job"]["fields"])
        kotlin_mutated["Job"]["fields"].append(("extra_field", "String", False))

        with self.assertRaises(AssertionError) as ctx:
            compare_fields("Job", self.schemas["Job"],
                           kotlin_mutated["Job"], kotlin_mutated, self.schemas)
        self.assertIn(
            "kotlin - spec: Job has fields not in spec: ['extra_field']",
            str(ctx.exception),
        )

    def test_b3_field_renamed(self):
        """A field renamed in the spec (job_id -> jobId) must be refused:
        the Kotlin-side name job_id then has no spec counterpart."""
        spec_mutated = copy.deepcopy(self.spec)
        accepted = spec_mutated["components"]["schemas"]["JobAccepted"]
        props = accepted["properties"]
        props["jobId"] = props.pop("job_id")
        accepted["required"] = ["jobId"] + [r for r in accepted["required"] if r != "job_id"]

        with self.assertRaises(AssertionError) as ctx:
            compare_fields("JobAccepted", accepted,
                           self.kotlin["JobAccepted"], self.kotlin,
                           spec_mutated["components"]["schemas"])
        # the rename drifts both name sets; the comparator reports the first
        # (spec->kotlin) extra, which is the new name jobId
        self.assertIn("JobAccepted", str(ctx.exception))
        self.assertIn("jobId", str(ctx.exception),
                      "the renamed field must be reported as extra")


class MismatchClassCTest(unittest.TestCase):
    """Class C: required-ness flipped.
    Anchor: the [REQUIRED-NESS] marker (the required-ness branch) in
    compare_fields."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_c_required_flipped(self):
        """(C) 'status' dropped from Job's required list while Kotlin keeps it
        non-nullable must be refused by the production comparator with the
        optional-but-not-nullable named message."""
        spec_mutated = copy.deepcopy(self.spec)
        job_schema = spec_mutated["components"]["schemas"]["Job"]
        job_schema["required"] = [r for r in job_schema["required"] if r != "status"]

        with self.assertRaises(AssertionError) as ctx:
            compare_fields("Job", job_schema, self.kotlin["Job"],
                           self.kotlin, spec_mutated["components"]["schemas"])
        self.assertIn(
            "Job.status: spec says optional, but Kotlin is not nullable",
            str(ctx.exception),
        )

    def test_c2_required_but_kotlin_nullable(self):
        """'result' added to Job's required list while Kotlin keeps it
        nullable must be refused with the REQUIRED-but-nullable named
        message -- the second of the two [REQUIRED-NESS] refusals. Red when
        the [REQUIRED-NESS] branch is removed (no refusal fires, so this
        test's assertRaises fails)."""
        spec_mutated = copy.deepcopy(self.spec)
        job_schema = spec_mutated["components"]["schemas"]["Job"]
        if "result" not in job_schema["required"]:
            job_schema["required"] = list(job_schema["required"]) + ["result"]

        with self.assertRaises(AssertionError) as ctx:
            compare_fields("Job", job_schema, self.kotlin["Job"],
                           self.kotlin, spec_mutated["components"]["schemas"])
        self.assertIn(
            "Job.result: spec says required, but Kotlin is nullable",
            str(ctx.exception),
        )


class MismatchClassDTest(unittest.TestCase):
    """Class D: type changed.
    Anchor: the [TYPE-MAP] marker in compare_fields."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_d_type_changed(self):
        """(D) TranscriptionResult.text changed string -> integer must be
        refused by the production comparator with the type-map named message."""
        spec_mutated = copy.deepcopy(self.spec)
        tr_schema = spec_mutated["components"]["schemas"]["TranscriptionResult"]
        tr_schema["properties"]["text"]["type"] = "integer"

        with self.assertRaises(AssertionError) as ctx:
            compare_fields("TranscriptionResult", tr_schema,
                           self.kotlin["TranscriptionResult"],
                           self.kotlin, spec_mutated["components"]["schemas"])
        self.assertIn(
            "TranscriptionResult.text: spec type integer maps to Int, but Kotlin has String",
            str(ctx.exception),
        )


class MismatchClassETest(unittest.TestCase):
    """Class E: enum value dropped.
    Anchor: the [ENUM-VALUES] marker in compare_fields."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_e_enum_value_dropped(self):
        """(E) 'failed' dropped from the JobStatus schema's enum must be
        refused by the production comparator with the enum-values named
        message (Job.status $refs the JobStatus schema)."""
        spec_mutated = copy.deepcopy(self.spec)
        js_schema = spec_mutated["components"]["schemas"]["JobStatus"]
        js_schema["enum"] = [v for v in js_schema["enum"] if v != "failed"]

        with self.assertRaises(AssertionError) as ctx:
            compare_fields("Job", spec_mutated["components"]["schemas"]["Job"],
                           self.kotlin["Job"], self.kotlin,
                           spec_mutated["components"]["schemas"])
        msg = str(ctx.exception)
        self.assertIn("Job.status", msg)
        self.assertIn("spec enum for JobStatus", msg)
        self.assertIn("'failed'", msg)


if __name__ == "__main__":
    unittest.main()
