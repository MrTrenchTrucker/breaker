"""Unit tests for the fixed 503 of the auth routes that read or write a store.

Register, salt, logout, hand-off start and hand-off exchange answer 503 with one
fixed body when the store fails, the same body login already documents. The
code and the contract must agree, so the spec carries the response on each.

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
LOGIN_PATH = "/v1/auth/login"
STORE_PATHS = (
    "/v1/auth/register",
    "/v1/auth/salt",
    "/v1/auth/logout",
    "/v1/auth/handoff/start",
    "/v1/auth/handoff/exchange",
)
ERROR_REF = "#/components/schemas/Error"
FIXED_BODY = {"error": "unavailable", "message": "The service is unavailable, try again later"}
_SPEC_CACHE = []


def _spec():
    if not _SPEC_CACHE:
        with open(SPEC_PATH, encoding="utf-8") as fh:
            _SPEC_CACHE.append(yaml_subset.loads(fh.read(), "openapi.yaml"))
    return _SPEC_CACHE[0]


def _unavailable(path):
    post = _spec().get("paths", {}).get(path, {}).get("post")
    if not isinstance(post, dict):
        raise AssertionError(f"{path} has no post operation")
    responses = post.get("responses", {})
    if "503" not in responses:
        raise AssertionError(f"{path} documents no 503")
    return responses["503"]


class AuthUnavailableContractTest(unittest.TestCase):
    def test_each_store_route_documents_the_fixed_503_body(self):
        """Catches a store route whose 503 is missing or carries another body."""
        for path in STORE_PATHS:
            media = _unavailable(path)["content"]["application/json"]
            self.assertEqual(media.get("example"), FIXED_BODY, f"{path} 503 example")

    def test_each_store_503_uses_the_shared_error_schema(self):
        """Catches a 503 that points at another schema than the shared Error."""
        for path in STORE_PATHS:
            media = _unavailable(path)["content"]["application/json"]
            self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, f"{path} 503 schema")

    def test_each_store_503_says_the_body_is_fixed(self):
        """Catches a 503 description that no longer promises a fixed body with no failure text."""
        for path in STORE_PATHS:
            description = _unavailable(path).get("description", "")
            self.assertIn("The body is fixed", description, f"{path} 503 description")
            self.assertIn("no part of the failure is echoed", description, f"{path} 503 description")

    def test_the_five_503_examples_equal_the_login_one(self):
        """Catches the five answers drifting from the one login documents."""
        login = _unavailable(LOGIN_PATH)["content"]["application/json"]
        self.assertEqual(login.get("example"), FIXED_BODY, "login 503 example")
        for path in STORE_PATHS:
            media = _unavailable(path)["content"]["application/json"]
            self.assertEqual(media.get("example"), login.get("example"), f"{path} 503 example")


if __name__ == "__main__":
    unittest.main()
