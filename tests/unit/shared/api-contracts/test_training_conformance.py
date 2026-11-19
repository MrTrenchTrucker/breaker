"""Conformance of the six training schemas and their Kotlin types.

Every comparison runs THE production comparator (api_contracts_support.compare_fields);
nothing is re-implemented here. A both-ways guard proves the six Kotlin types and
the six schemas exist, so a missing side cannot pass by comparing nothing.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import (
    load_spec, get_schemas, parse_all_kotlin, compare_fields, check_coverage,
)

DATA_CLASSES = ("SampleReceipt", "TrainingJob", "TrainingJobStatus")
ENUMS = ("TrainingKind", "TrainingJobState", "TrainingJobFailure")


class TrainingConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.spec = load_spec()
        cls.schemas = get_schemas(cls.spec)
        cls.kotlin = parse_all_kotlin()

    def _compare(self, name):
        kt = self.kotlin[name]
        self.assertEqual(kt.get("type"), "data_class", f"{name} must be a data class in Kotlin")
        self.assertTrue(compare_fields(name, self.schemas[name], kt, self.kotlin, self.schemas),
                        f"{name} must compare clean through the production comparator")

    def _compare_enum(self, name):
        kt = self.kotlin[name]
        self.assertEqual(kt.get("type"), "enum", f"{name} must be an enum in Kotlin")
        spec_values = self.schemas[name].get("enum")
        self.assertEqual(spec_values, kt.get("values"), f"{name}: spec and Kotlin enum values differ")

    def test_sample_receipt_fields_match_spec(self):
        self._compare("SampleReceipt")

    def test_training_job_fields_match_spec(self):
        self._compare("TrainingJob")

    def test_training_job_status_fields_match_spec(self):
        self._compare("TrainingJobStatus")

    def test_training_kind_enum_matches_kotlin(self):
        self._compare_enum("TrainingKind")

    def test_training_job_state_enum_matches_kotlin(self):
        self._compare_enum("TrainingJobState")

    def test_training_job_failure_enum_matches_kotlin(self):
        self._compare_enum("TrainingJobFailure")

    def test_status_failure_field_is_the_optional_failure_enum(self):
        fields = {f[0]: f for f in self.kotlin["TrainingJobStatus"]["fields"]}
        self.assertEqual(fields["failure"][1:], ("TrainingJobFailure", True))
        self.assertEqual(fields["state"][1:], ("TrainingJobState", False))

    def test_status_init_rule_ties_failure_to_the_failed_state(self):
        rules = " ".join(self.kotlin["TrainingJobStatus"].get("init_rules", []))
        self.assertIn("FAILED", rules)
        self.assertIn("failure", rules)

    def test_all_six_schemas_and_all_six_kotlin_types_exist(self):
        for name in DATA_CLASSES + ENUMS:
            with self.subTest(name=name):
                self.assertIn(name, self.schemas, f"{name} has no spec schema")
                self.assertIn(name, self.kotlin, f"{name} has no Kotlin type")

    def test_both_ways_coverage_holds_with_the_training_types(self):
        self.assertTrue(check_coverage(self.schemas, self.kotlin))


if __name__ == "__main__":
    unittest.main()
