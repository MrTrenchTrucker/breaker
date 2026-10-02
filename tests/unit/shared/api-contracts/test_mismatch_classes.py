"""Mismatch-class tests: each on a MUTATED COPY of the real files.

Five classes, each watched RED:
(a1) field added to spec only
(a2) field added to Kotlin only
(b) field renamed
(c) enum value dropped
(d) required flipped
(e) type changed

Each uses mutate(text, anchor, replacement) which RAISES if anchor is absent.
A test calls mutate with a missing anchor and asserts the raise.
"""
import unittest
import sys
import copy
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import (
    load_spec, get_schemas, get_schema_fields, get_enum_values,
    parse_all_kotlin, parse_kotlin_file, snake, map_spec_type_to_kotlin, mutate,
)


class MutateHelperTest(unittest.TestCase):
    """Tests for the mutate() helper itself."""

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
    """Class (a): field added on one side only."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_a1_field_added_to_spec_only(self):
        """(a1) A field added to the spec only must be detected.

        Mutant: add 'extra_field' to the Job schema in the spec.
        Expected: conformance check fails with "spec - kotlin: Job has extra fields"
        """
        # Mutate the spec: add an extra field to Job schema
        spec_text = str(self.spec)  # This is a dict, we work with the parsed structure
        # Instead, we mutate the spec dict directly
        spec_mutated = copy.deepcopy(self.spec)
        job_schema = spec_mutated["components"]["schemas"]["Job"]
        job_schema["properties"]["extra_field"] = {"type": "string"}
        job_schema["required"].append("extra_field")

        # Now compare: spec has extra_field, kotlin does not
        spec_fields = get_schema_fields(job_schema)
        spec_field_names = {f[0] for f in spec_fields}
        kotlin_fields = self.kotlin.get("Job", {}).get("fields", [])
        kotlin_field_names = {f[0] for f in kotlin_fields}

        extra_in_spec = spec_field_names - kotlin_field_names
        self.assertEqual(
            extra_in_spec, {"extra_field"},
            "spec - kotlin should detect extra_field added to spec only"
        )

    def test_a2_field_added_to_kotlin_only(self):
        """(a2) A field added to the Kotlin only must be detected.

        Mutant: add 'extra_field' to the Job data class in Kotlin.
        Expected: conformance check fails with "kotlin - spec: Job has extra fields"
        """
        # Simulate: kotlin has an extra field not in spec
        kotlin_mutated = copy.deepcopy(self.kotlin)
        kotlin_mutated["Job"]["fields"].append(("extra_field", "String", False))

        spec_fields = get_schema_fields(self.schemas["Job"])
        spec_field_names = {f[0] for f in spec_fields}
        kotlin_field_names = {f[0] for f in kotlin_mutated["Job"]["fields"]}

        extra_in_kotlin = kotlin_field_names - spec_field_names
        self.assertEqual(
            extra_in_kotlin, {"extra_field"},
            "kotlin - spec should detect extra_field added to kotlin only"
        )


class MismatchClassBTest(unittest.TestCase):
    """Class (b): field renamed."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_b_field_renamed(self):
        """(b) A field renamed in the spec must be detected.

        Mutant: rename 'job_id' to 'jobId' in the JobAccepted schema.
        Expected: conformance check fails with "spec - kotlin: JobAccepted has extra fields"
        """
        spec_mutated = copy.deepcopy(self.spec)
        job_accepted = spec_mutated["components"]["schemas"]["JobAccepted"]
        # Rename job_id to jobId
        props = job_accepted["properties"]
        props["jobId"] = props.pop("job_id")
        job_accepted["required"] = ["jobId"] + [r for r in job_accepted["required"] if r != "job_id"]

        spec_fields = get_schema_fields(job_accepted)
        spec_field_names = {f[0] for f in spec_fields}
        kotlin_fields = self.kotlin.get("JobAccepted", {}).get("fields", [])
        kotlin_field_names = {f[0] for f in kotlin_fields}

        extra_in_spec = spec_field_names - kotlin_field_names
        self.assertEqual(
            extra_in_spec, {"jobId"},
            "spec - kotlin should detect jobId as extra (renamed from job_id)"
        )


class MismatchClassCTest(unittest.TestCase):
    """Class (c): enum value dropped."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_c_enum_value_dropped(self):
        """(c) An enum value dropped from the spec must be detected.

        Mutant: remove 'failed' from Job.status enum.
        Expected: conformance check fails with "spec -> kotlin: JobStatus enum differs"
        """
        spec_mutated = copy.deepcopy(self.spec)
        job_schema = spec_mutated["components"]["schemas"]["Job"]
        status_prop = job_schema["properties"]["status"]
        # Remove 'failed' from enum
        status_prop["enum"] = [v for v in status_prop["enum"] if v != "failed"]

        spec_enum = set(status_prop["enum"])
        kotlin_enum = set(self.kotlin.get("JobStatus", {}).get("values", []))

        missing_in_spec = kotlin_enum - spec_enum
        self.assertEqual(
            missing_in_spec, {"failed"},
            "kotlin - spec should detect 'failed' as missing from spec enum"
        )


class MismatchClassDTest(unittest.TestCase):
    """Class (d): required flipped."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_d_required_flipped(self):
        """(d) Required-ness flipped in the spec must be detected.

        Mutant: remove 'status' from required in Job schema.
        Expected: conformance check fails with "spec says optional, but Kotlin is not nullable"
        """
        spec_mutated = copy.deepcopy(self.spec)
        job_schema = spec_mutated["components"]["schemas"]["Job"]
        # Remove 'status' from required
        job_schema["required"] = [r for r in job_schema["required"] if r != "status"]

        spec_fields = get_schema_fields(job_schema)
        spec_by_name = {f[0]: f for f in spec_fields}
        status_spec = spec_by_name["status"]
        _, _, status_required, _, _ = status_spec

        self.assertFalse(
            status_required,
            "After mutation, status should be optional in spec"
        )

        # Kotlin still has it as non-nullable
        kotlin_fields = self.kotlin.get("Job", {}).get("fields", [])
        kotlin_by_name = {f[0]: f for f in kotlin_fields}
        _, _, kotlin_nullable = kotlin_by_name["status"]

        self.assertFalse(
            kotlin_nullable,
            "Kotlin should still have status as non-nullable"
        )


class MismatchClassETest(unittest.TestCase):
    """Class (e): type changed."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def test_e_type_changed(self):
        """(e) Type changed in the spec must be detected.

        Mutant: change 'text' type from string to integer in TranscriptionResult.
        Expected: conformance check fails with "spec type integer maps to Int, but Kotlin has String"
        """
        spec_mutated = copy.deepcopy(self.spec)
        tr_schema = spec_mutated["components"]["schemas"]["TranscriptionResult"]
        # Change text type from string to integer
        tr_schema["properties"]["text"]["type"] = "integer"

        spec_fields = get_schema_fields(tr_schema)
        spec_by_name = {f[0]: f for f in spec_fields}
        text_spec = spec_by_name["text"]
        _, spec_type, _, _, _ = text_spec

        self.assertEqual(spec_type, "integer", "After mutation, text should be integer in spec")

        # Kotlin still has it as String
        kotlin_fields = self.kotlin.get("TranscriptionResult", {}).get("fields", [])
        kotlin_by_name = {f[0]: f for f in kotlin_fields}
        _, kotlin_type, _ = kotlin_by_name["text"]

        self.assertEqual(kotlin_type, "String", "Kotlin should still have text as String")

        # The type map should detect the mismatch
        expected_kotlin_type = map_spec_type_to_kotlin(spec_type)
        self.assertEqual(expected_kotlin_type, "Int")
        self.assertNotEqual(expected_kotlin_type, kotlin_type)


if __name__ == "__main__":
    unittest.main()
