"""Unit tests for the fixed 503 of the wrong-password limit route.

GET and PUT /v1/admin/wrong-pw-limit answer 503 with one fixed body when the
store fails (the route code does), the same body every other route documents.
The contract carries the response on both methods.

Run: python3 -m unittest discover -s tests/unit/shared/api-contracts -t tests/unit/shared/api-contracts
"""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset

SPEC_PATH = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
LIMIT_PATH = "/v1/admin/wrong-pw-limit"
ERROR_REF = "#/components/schemas/Error"
FIXED_BODY = {"error": "unavailable", "message": "The service is unavailable, try again later"}
_SPEC_CACHE = []


def _spec():
    if not _SPEC_CACHE:
        with open(SPEC_PATH, encoding="utf-8") as fh:
            _SPEC_CACHE.append(yaml_subset.loads(fh.read(), "openapi.yaml"))
    return _SPEC_CACHE[0]


def _unavailable(method):
    operation = _spec().get("paths", {}).get(LIMIT_PATH, {}).get(method)
    if not isinstance(operation, dict):
        raise AssertionError(f"{LIMIT_PATH} has no {method} operation")
    responses = operation.get("responses", {})
    if "503" not in responses:
        raise AssertionError(f"{LIMIT_PATH} {method} documents no 503")
    return responses["503"]


class AdminLimitUnavailableContractTest(unittest.TestCase):
    def test_both_limit_methods_document_the_fixed_503_body(self):
        """Catches a limit method whose 503 is missing or carries another body."""
        for method in ("get", "put"):
            media = _unavailable(method)["content"]["application/json"]
            self.assertEqual(media.get("example"), FIXED_BODY, f"{method} 503 example")

    def test_both_limit_503s_use_the_shared_error_schema(self):
        """Catches a 503 that points at another schema than the shared Error."""
        for method in ("get", "put"):
            media = _unavailable(method)["content"]["application/json"]
            self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, f"{method} 503 schema")

    def test_both_limit_503s_say_the_body_is_fixed(self):
        """Catches a 503 description that no longer promises a fixed body with no failure text."""
        for method in ("get", "put"):
            description = _unavailable(method).get("description", "")
            self.assertIn("The body is fixed", description, f"{method} 503 description")
            self.assertIn("no part of the failure is echoed", description, f"{method} 503 description")


if __name__ == "__main__":
    unittest.main()
