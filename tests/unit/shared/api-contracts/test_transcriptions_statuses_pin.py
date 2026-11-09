"""Contract pins for POST /v1/audio/transcriptions: the response status
set, the 400/500 rows referencing the Error schema with error-only
examples, and the Error schema itself.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_transcriptions_statuses_pin
"""
import os
import sys
import unittest
from pathlib import Path

HERE = os.path.abspath(__file__)
ROOT = HERE
for _ in range(5):
    ROOT = os.path.dirname(ROOT)
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)
TEST_DIR = str(Path(__file__).resolve().parent)
if TEST_DIR not in sys.path:
    sys.path.insert(0, TEST_DIR)

import yaml_subset

SPEC_PATH = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions"
EXPECTED_STATUSES = {"202", "400", "401", "413", "429", "500"}
_ERROR_SCHEMA = "#/components/schemas/Error"


def _spec():
    with open(SPEC_PATH, encoding="utf-8") as fh:
        return yaml_subset.loads(fh.read(), "openapi.yaml")


def _operation():
    paths = _spec().get("paths", {})
    if TRANSCRIPTIONS_PATH not in paths:
        raise AssertionError(f"{TRANSCRIPTIONS_PATH} is missing from the spec paths")
    op = paths[TRANSCRIPTIONS_PATH].get("post")
    if not isinstance(op, dict):
        raise AssertionError(f"{TRANSCRIPTIONS_PATH} has no post operation")
    return op


def _responses():
    return _operation().get("responses", {})


def _schema_of(status):
    responses = _responses()
    if status not in responses:
        raise AssertionError(f"{status} response missing")
    media = responses[status].get("content", {}).get("application/json", {})
    return media.get("schema", {})


class TranscriptionsStatusesPinTest(unittest.TestCase):
    """Pins the response status set and the 400/500 Error rows."""

    def test_post_operation_exists(self):
        """Catches a missing path or wrong method."""
        self.assertIsInstance(_operation(), dict,
                              f"{TRANSCRIPTIONS_PATH} must expose a post operation")

    def test_response_status_set_is_exact(self):
        """Catches a missing, extra, or renamed response status.

        Red on a deliberate break: remove any row, or add an extra row
        (for example a 503), and this set no longer matches. Green on
        the correct spec.
        """
        actual = set(_responses().keys())
        self.assertEqual(
            actual, EXPECTED_STATUSES,
            f"response statuses are {sorted(actual)}, expected {sorted(EXPECTED_STATUSES)}",
        )

    def test_202_schema_is_job_accepted(self):
        """The 202 row references JobAccepted (example owned elsewhere)."""
        schema = _schema_of("202")
        self.assertEqual(
            schema.get("$ref"), "#/components/schemas/JobAccepted",
            "202 schema must be JobAccepted",
        )

    def test_400_refs_error(self):
        """The 400 row references the Error schema."""
        schema = _schema_of("400")
        self.assertEqual(
            schema.get("$ref"), _ERROR_SCHEMA,
            "400 schema must be Error",
        )

    def test_400_description_names_missing_or_empty(self):
        """The 400 description covers missing and empty uploads.

        The description text itself is not pinned; only that it covers
        missing-or-empty. Wording may change without failing this test.
        """
        desc = _responses()["400"].get("description", "")
        self.assertIn(
            "missing", desc.lower(), "400 description must name missing",
        )
        self.assertIn(
            "empty", desc.lower(), "400 description must name empty",
        )

    def test_400_example_is_error_only(self):
        """The 400 example carries only 'error' (the 429 precedent)."""
        example = (
            _responses()["400"]
            .get("content", {})
            .get("application/json", {})
            .get("example", {})
        )
        self.assertEqual(
            set(example.keys()), {"error"}, "400 example must be error-only",
        )
        self.assertEqual(
            example.get("error"), "the file part is required",
            "400 example error text",
        )

    def test_500_refs_error(self):
        """The 500 row references the Error schema."""
        schema = _schema_of("500")
        self.assertEqual(
            schema.get("$ref"), _ERROR_SCHEMA,
            "500 schema must be Error",
        )

    def test_500_description_names_unreadable_upload(self):
        """The 500 description covers an unreadable upload.

        The description text itself is not pinned; only that it covers
        unreadable-upload. Wording may change without failing this test.
        """
        desc = _responses()["500"].get("description", "")
        self.assertIn(
            "could not read", desc.lower(),
            "500 description must name unreadable upload",
        )

    def test_500_example_is_error_only(self):
        """The 500 example carries only 'error' (the 429 precedent)."""
        example = (
            _responses()["500"]
            .get("content", {})
            .get("application/json", {})
            .get("example", {})
        )
        self.assertEqual(
            set(example.keys()), {"error"}, "500 example must be error-only",
        )
        self.assertEqual(
            example.get("error"), "the upload could not be read",
            "500 example error text",
        )

    def test_401_413_429_ref_error(self):
        """The pre-existing error rows still reference Error."""
        for status in ("401", "413", "429"):
            schema = _schema_of(status)
            self.assertEqual(
                schema.get("$ref"), _ERROR_SCHEMA,
                f"{status} schema must be Error",
            )

    def test_error_schema_resolves(self):
        """The Error $ref target exists in components/schemas."""
        schemas = _spec().get("components", {}).get("schemas", {})
        self.assertIn("Error", schemas, "Error schema must exist")
        props = schemas["Error"].get("properties", {})
        self.assertIn("error", props, "Error schema must have error property")
        self.assertEqual(
            props["error"].get("type"), "string",
            "Error.error must be a string",
        )


if __name__ == "__main__":
    unittest.main()
