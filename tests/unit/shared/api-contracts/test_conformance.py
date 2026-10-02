"""Conformance tests: openapi.yaml and Kotlin types agree field for field.

Compares ALL THREE per field: name, type, and required-ness.
Each direction is asserted separately with specific messages.
"""
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import (
    load_spec, get_schemas, get_schema_fields, get_enum_values,
    parse_all_kotlin, parse_kotlin_file, snake, map_spec_type_to_kotlin,
)


class ConformanceTest(unittest.TestCase):
    """Tests that the spec and Kotlin types agree on name, type, and required-ness."""

    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def _compare_fields(self, schema_name, spec_schema, kotlin_data):
        """Compare fields between spec and Kotlin for a given schema.

        Asserts:
        1. spec - kotlin == set() (no extra fields in spec)
        2. kotlin - spec == set() (no extra fields in kotlin)
        3. For each field: type matches and required-ness matches
        """
        spec_fields = get_schema_fields(spec_schema)
        spec_field_names = {f[0] for f in spec_fields}

        kotlin_fields = kotlin_data.get("fields", [])
        kotlin_field_names = {f[0] for f in kotlin_fields}

        # Direction 1: spec - kotlin
        extra_in_spec = spec_field_names - kotlin_field_names
        self.assertEqual(
            extra_in_spec, set(),
            f"spec - kotlin: {schema_name} has extra fields not in kotlin: {extra_in_spec}"
        )

        # Direction 2: kotlin - spec
        extra_in_kotlin = kotlin_field_names - spec_field_names
        self.assertEqual(
            extra_in_kotlin, set(),
            f"kotlin - spec: {schema_name} has extra fields not in spec: {extra_in_kotlin}"
        )

        # Compare types and required-ness for each field
        spec_by_name = {f[0]: f for f in spec_fields}
        kotlin_by_name = {f[0]: f for f in kotlin_fields}

        for name in spec_field_names:
            spec_field = spec_by_name[name]
            kotlin_field = kotlin_by_name[name]

            spec_name, spec_type, spec_required, spec_fmt, spec_ref = spec_field
            kotlin_name, kotlin_type, kotlin_nullable = kotlin_field

            # Get the full property from the spec to check for enum and items
            prop = spec_schema.get("properties", {}).get(name, {})
            spec_enum = prop.get("enum")
            items = prop.get("items", {})
            items_ref = items.get("$ref") if isinstance(items, dict) else None

            # Map spec type to expected Kotlin type
            expected_kotlin_type = map_spec_type_to_kotlin(
                spec_type, spec_fmt, spec_ref, spec_enum, items_ref
            )

            # Special case: if spec has enum, the Kotlin type should be the enum class name
            # We need to find which enum class this maps to by checking the enum values
            if spec_enum:
                # Find the enum class in Kotlin that matches these values
                enum_values = set(spec_enum)
                for enum_name, enum_data in self.kotlin.items():
                    if enum_data.get("type") == "enum":
                        kotlin_enum_values = set(enum_data.get("values", []))
                        if kotlin_enum_values == enum_values:
                            expected_kotlin_type = enum_name
                            break

            # Compare types
            self.assertEqual(
                expected_kotlin_type, kotlin_type,
                f"{schema_name}.{name}: spec type {spec_type} maps to {expected_kotlin_type}, "
                f"but Kotlin has {kotlin_type}"
            )

            # Compare required-ness: required=True <-> non-nullable, required=False <-> nullable or default
            if spec_required:
                self.assertFalse(
                    kotlin_nullable,
                    f"{schema_name}.{name}: spec says required, but Kotlin is nullable"
                )
            else:
                # Optional: should be nullable or have a default
                self.assertTrue(
                    kotlin_nullable,
                    f"{schema_name}.{name}: spec says optional, but Kotlin is not nullable "
                    f"(should be nullable or have a default)"
                )

    def test_job_status_enum_matches_spec(self):
        """JobStatus enum values match the spec's Job.status enum."""
        job_schema = self.schemas.get("Job", {})
        status_prop = job_schema.get("properties", {}).get("status", {})
        spec_enum = status_prop.get("enum", [])
        self.assertEqual(len(spec_enum), 4, "Spec Job.status must have 4 enum values")

        job_status = self.kotlin.get("JobStatus", {})
        self.assertEqual(job_status.get("type"), "enum", "JobStatus must be an enum")
        kotlin_values = job_status.get("values", [])

        # Both directions
        self.assertEqual(spec_enum, kotlin_values, "spec -> kotlin: JobStatus enum differs")
        self.assertEqual(kotlin_values, spec_enum, "kotlin -> spec: JobStatus enum differs")

    def test_job_fields_match_spec(self):
        """Job data class fields match the spec's Job schema (name, type, required)."""
        job_schema = self.schemas.get("Job", {})
        job_kt = self.kotlin.get("Job", {})
        self.assertEqual(job_kt.get("type"), "data_class", "Job must be a data class")
        self._compare_fields("Job", job_schema, job_kt)

    def test_transcription_result_fields_match_spec(self):
        """TranscriptionResult data class fields match the spec (name, type, required)."""
        spec_schema = self.schemas.get("TranscriptionResult", {})
        kt = self.kotlin.get("TranscriptionResult", {})
        self.assertEqual(kt.get("type"), "data_class", "TranscriptionResult must be a data class")
        self._compare_fields("TranscriptionResult", spec_schema, kt)

    def test_segment_fields_match_spec(self):
        """Segment data class fields match the spec (name, type, required)."""
        spec_schema = self.schemas.get("Segment", {})
        kt = self.kotlin.get("Segment", {})
        self.assertEqual(kt.get("type"), "data_class", "Segment must be a data class")
        self._compare_fields("Segment", spec_schema, kt)

    def test_health_fields_match_spec(self):
        """Health data class fields match the spec (name, type, required)."""
        spec_schema = self.schemas.get("Health", {})
        kt = self.kotlin.get("Health", {})
        self.assertEqual(kt.get("type"), "data_class", "Health must be a data class")
        self._compare_fields("Health", spec_schema, kt)

    def test_job_accepted_fields_match_spec(self):
        """JobAccepted data class fields match the spec (name, type, required)."""
        spec_schema = self.schemas.get("JobAccepted", {})
        kt = self.kotlin.get("JobAccepted", {})
        self.assertEqual(kt.get("type"), "data_class", "JobAccepted must be a data class")
        self._compare_fields("JobAccepted", spec_schema, kt)

    def test_paths_exact_set_equality(self):
        """Paths in the spec are exactly the three endpoints (no more, no less)."""
        paths = self.spec.get("paths", {})
        expected_paths = {"/v1/audio/transcriptions", "/v1/jobs/{job_id}", "/health"}
        self.assertEqual(set(paths.keys()), expected_paths, "Paths must be exactly the three endpoints")


if __name__ == "__main__":
    unittest.main()
