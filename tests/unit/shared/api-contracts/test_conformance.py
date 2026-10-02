"""Conformance tests: openapi.yaml and the Kotlin types agree field for field.

Every field comparison runs THE production comparator
(api_contracts_support.compare_fields) -- no re-implemented set differences
here. The comparator raises AssertionError with a named message on the first
mismatch; these tests assert it does NOT raise on the clean tree, and the
mismatch-class tests (test_mismatch_classes.py) assert the named messages on
planted drift.

Both-ways coverage: EVERY components/schemas entry must map to a Kotlin
type and every Kotlin type must have a schema -- the 5 hand-picked schemas of
slice 1 are no longer the coverage surface.
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
    compare_fields, check_coverage, effective_enum,
)


class ConformanceTest(unittest.TestCase):
    """Spec and Kotlin agree on name, type, required-ness and enum values."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def _compare(self, schema_name):
        """Run the production comparator for one schema name."""
        spec_schema = self.schemas[schema_name]
        kt = self.kotlin[schema_name]
        self.assertEqual(kt.get("type"), "data_class",
                         f"{schema_name} must be a data class in Kotlin")
        self.assertTrue(
            compare_fields(schema_name, spec_schema, kt, self.kotlin, self.schemas),
            f"{schema_name} must compare clean through the production comparator"
        )

    def test_job_fields_match_spec(self):
        """Job data class fields match the spec's Job schema (production comparator)."""
        self._compare("Job")

    def test_transcription_result_fields_match_spec(self):
        """TranscriptionResult fields match the spec (production comparator)."""
        self._compare("TranscriptionResult")

    def test_segment_fields_match_spec(self):
        """Segment fields match the spec (production comparator)."""
        self._compare("Segment")

    def test_health_fields_match_spec(self):
        """Health fields match the spec (production comparator)."""
        self._compare("Health")

    def test_job_accepted_fields_match_spec(self):
        """JobAccepted fields match the spec (production comparator)."""
        self._compare("JobAccepted")

    def test_api_error_fields_match_spec(self):
        """ApiError (the Kotlin name for the Error schema) matches the spec."""
        spec_schema = self.schemas["Error"]
        kt = self.kotlin["ApiError"]
        self.assertEqual(kt.get("type"), "data_class", "ApiError must be a data class")
        self.assertTrue(
            compare_fields("ApiError", spec_schema, kt, self.kotlin, self.schemas),
            "Error schema <-> ApiError must compare clean through the production comparator"
        )

    def test_job_status_enum_matches_named_schema(self):
        """The named JobStatus schema's enum values match the Kotlin JobStatus enum."""
        js_schema = self.schemas.get("JobStatus", {})
        spec_enum = js_schema.get("enum", [])
        self.assertEqual(len(spec_enum), 4, "Spec JobStatus must have 4 enum values")

        job_status = self.kotlin.get("JobStatus", {})
        self.assertEqual(job_status.get("type"), "enum", "JobStatus must be an enum")
        kotlin_values = job_status.get("values", [])

        self.assertEqual(spec_enum, kotlin_values, "spec -> kotlin: JobStatus enum differs")
        self.assertEqual(kotlin_values, spec_enum, "kotlin -> spec: JobStatus enum differs")

    def test_job_status_field_refs_named_schema(self):
        """Job.status is the JobStatus $ref (no inline enum)."""
        status_prop = self.schemas["Job"]["properties"]["status"]
        self.assertIsNone(status_prop.get("enum"),
                          "Job.status must not carry its own inline enum")
        refs = [item.get("$ref") for item in status_prop.get("allOf", [])]
        self.assertEqual(refs, ["#/components/schemas/JobStatus"],
                         "Job.status must be an allOf $ref to the JobStatus schema")

    def test_job_accepted_status_narrowing(self):
        """JobAccepted.status keeps the queued-only narrowing.

        The spec narrows the JobStatus $ref with a sibling enum [queued];
        the Kotlin side must be the JobStatus enum type (not a String), and
        the construction-time rule (JobAccepted init) keeps the same
        narrowing. Loosening the spec (removing the sibling enum) or making
        the Kotlin side a String turns this RED through the production
        comparator.
        """
        status_prop = self.schemas["JobAccepted"]["properties"]["status"]
        refs = [item.get("$ref") for item in status_prop.get("allOf", [])]
        self.assertEqual(refs, ["#/components/schemas/JobStatus"],
                         "JobAccepted.status must $ref the JobStatus schema")
        self.assertEqual(effective_enum(status_prop), ["queued"],
                         "JobAccepted.status must narrow JobStatus to [queued]")

        # the production comparator must accept the clean pair and refuse a
        # String side for the enum property
        self.assertTrue(
            compare_fields("JobAccepted", self.schemas["JobAccepted"],
                           self.kotlin["JobAccepted"], self.kotlin, self.schemas)
        )
        # the Kotlin side keeps the narrowing at construction: the init rule
        # is surfaced by the production reader and must name QUEUED
        ja_rules = " ".join(self.kotlin["JobAccepted"].get("init_rules", []))
        self.assertIn("QUEUED", ja_rules,
                      "JobAccepted's init must carry the queued-only rule "
                      f"(without it the queued-only narrowing is not "
                      f"enforced); got: {ja_rules!r}")
        string_side = copy.deepcopy(self.kotlin)
        string_side["JobAccepted"]["fields"] = [
            ("job_id", "String", False), ("status", "String", False)
        ]
        with self.assertRaises(AssertionError) as ctx:
            compare_fields("JobAccepted", self.schemas["JobAccepted"],
                           string_side["JobAccepted"], string_side, self.schemas)
        self.assertIn("Kotlin has String", str(ctx.exception),
                      "the comparator must refuse a spec enum property backed by a Kotlin String")

    def test_comparator_refuses_spec_enum_vs_kotlin_string(self):
        """A spec enum backed by a Kotlin String is refused by the
        production comparator."""
        spec_schema = self.schemas["Job"]
        kotlin_side = copy.deepcopy(self.kotlin)
        kotlin_side["Job"]["fields"] = [
            ("status", "String", False), ("result", "TranscriptionResult", True),
            ("error", "String", True)
        ]
        with self.assertRaises(AssertionError) as ctx:
            compare_fields("Job", spec_schema, kotlin_side["Job"],
                           kotlin_side, self.schemas)
        msg = str(ctx.exception)
        self.assertIn("Job.status", msg)
        self.assertIn("JobStatus", msg)
        self.assertIn("Kotlin has String", msg)

    # ------------------------------------------------------------------
    # Both-ways coverage: every schema has a Kotlin type, and every
    # Kotlin type has a schema.
    # ------------------------------------------------------------------

    def test_every_schema_has_a_kotlin_type(self):
        """spec -> kotlin: every components/schemas entry maps to a Kotlin type.

        Runs the production both-ways check (check_coverage): the Error
        schema maps to the Kotlin type named ApiError; every other schema
        to the same-named type.
        """
        self.assertTrue(check_coverage(self.schemas, self.kotlin))

    def test_every_kotlin_type_has_a_schema(self):
        """kotlin -> spec: every Kotlin type maps back to a schema.

        Same production check (both directions are one pass): a Kotlin type
        without a schema is refused by check_coverage.
        """
        kotlin_side = copy.deepcopy(self.kotlin)
        kotlin_side["Orphan"] = {"type": "data_class", "fields": [], "init_rules": []}
        with self.assertRaises(AssertionError) as ctx:
            check_coverage(self.schemas, kotlin_side)
        self.assertIn("Kotlin type Orphan has no spec schema", str(ctx.exception))

    def test_job_contract_carries_failure_reason(self):
        """The contract carries its failure-reason field, both sides.

        The spec's Job schema has the nullable `error` property, the Kotlin
        Job has the nullable `error` field, and the Kotlin type carries the
        both-ways init rule (error non-null exactly when status is FAILED)
        as surfaced by the production reader.
        """
        error_prop = self.schemas["Job"]["properties"].get("error")
        self.assertIsNotNone(error_prop, "the Job schema must have the error property")
        self.assertEqual(error_prop.get("type"), "string")
        self.assertTrue(error_prop.get("nullable", False),
                        "the error property must be nullable")

        job = self.kotlin.get("Job", {})
        field_names = {f[0] for f in job.get("fields", [])}
        self.assertIn("error", field_names, "the Kotlin Job must have the error field")
        error_field = [f for f in job["fields"] if f[0] == "error"][0]
        self.assertTrue(error_field[2], "the Kotlin error field must be nullable")

        # the both-way failure-reason rule, surfaced by the production reader
        rules = job.get("init_rules", [])
        self.assertTrue(
            any("FAILED" in r and "error" in r for r in rules),
            "the Job init rules must carry the both-way failed<->error rule, "
            f"got: {rules!r}"
        )
        self.assertTrue(
            any("DONE" in r and "result" in r for r in rules),
            "the Job init rules must keep the DONE-requires-result rule, "
            f"got: {rules!r}"
        )


if __name__ == "__main__":
    unittest.main()
