"""The Job init rules carry the both-way result rule, as the production reader returns them.

Job refuses a result that is not paired with the DONE status, in both
directions, the same way it pairs error with FAILED. These tests read the
Job init rules through parse_all_kotlin, the production Kotlin reader that
test_conformance.py uses, and check the condition strings it returns.
"""
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from api_contracts_support import parse_all_kotlin
from api_contracts_support import get_schemas, is_true, load_spec

ONE_WAY_RESULT_RULE = "status != JobStatus.DONE || result != null"


class JobResultRuleTest(unittest.TestCase):
    """The Job init rules: the result rule is both-way, the error rule is unchanged."""

    @classmethod
    def setUpClass(cls):
        cls.kotlin = parse_all_kotlin()
        cls.rules = cls.kotlin["Job"]["init_rules"]

    def _only_error_rule(self):
        """The one init rule that mentions FAILED and error, used as the model."""
        error_rules = [r for r in self.rules if "FAILED" in r and "error" in r]
        self.assertEqual(
            len(error_rules), 1,
            f"Job must carry exactly one FAILED/error rule, got: {self.rules!r}"
        )
        return error_rules[0]

    def test_result_rule_is_the_both_way_form_of_the_error_rule(self):
        """The reader returns the result rule as the error rule with DONE and result."""
        error_rule = self._only_error_rule()
        expected = (error_rule.replace("JobStatus.FAILED", "JobStatus.DONE")
                              .replace("(error != null)", "(result != null)"))
        self.assertIn(
            expected, self.rules,
            f"expected the both-way result rule {expected!r} among the Job "
            f"init rules, got: {self.rules!r}"
        )

    def test_one_way_result_rule_is_no_longer_an_init_rule(self):
        """The one-way rule (a DONE status needs a result) is gone from the Job init rules."""
        self.assertNotIn(
            ONE_WAY_RESULT_RULE, self.rules,
            f"the one-way result rule is still an init rule, got: {self.rules!r}"
        )

    def test_error_rule_is_still_the_both_way_rule(self):
        """The error rule keeps its both-way text: error is set exactly when status is FAILED."""
        self.assertEqual(
            self._only_error_rule(),
            "(status == JobStatus.FAILED) == (error != null)",
            "the Job error rule must stay the both-way FAILED/error rule"
        )

    def test_job_contract_carries_result_nullable(self):
        """The contract carries its result field, both sides.

        The spec's Job schema has the result property and its nullable flag is
        true; the Kotlin Job has the nullable result field, as the production
        reader returns it. This mirrors test_conformance.py:202-214 for the
        error field, except that the spec flag is read through is_true, so the
        string "false" that yaml_subset returns counts as not nullable. The
        flag check at test_conformance.py:213 is not changed by this test.
        """
        spec = load_spec()
        schemas = get_schemas(spec)
        result_prop = schemas["Job"]["properties"].get("result")
        self.assertIsNotNone(result_prop, "the Job schema must have the result property")
        self.assertTrue(is_true(result_prop.get("nullable")),
                        "the result property must be nullable")

        job = self.kotlin.get("Job", {})
        field_names = {f[0] for f in job.get("fields", [])}
        self.assertIn("result", field_names, "the Kotlin Job must have the result field")
        result_field = [f for f in job["fields"] if f[0] == "result"][0]
        self.assertTrue(result_field[2], "the Kotlin result field must be nullable")


if __name__ == "__main__":
    unittest.main()
