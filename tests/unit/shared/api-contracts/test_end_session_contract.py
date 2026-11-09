"""Contract pins for DELETE /v1/admin/sessions/{id}:
the path, its method, security, parameter, the fixed error bodies, and the
204 response that carries no content.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_end_session_contract
"""
import os
import sys
import unittest

HERE = os.path.abspath(__file__)
ROOT = HERE
for _ in range(5):
    ROOT = os.path.dirname(ROOT)
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset

SPEC_PATH = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
SESSION_PATH = "/v1/admin/sessions/{id}"
ERROR_REF = "#/components/schemas/Error"
UNAUTHORIZED = {"error": "unauthorized", "message": "Invalid or missing token"}
FORBIDDEN = {"error": "forbidden", "message": "Admin role required"}
NOT_FOUND = {"error": "not_found", "message": "No such session"}
UNAVAILABLE = {"error": "unavailable", "message": "The service is unavailable, try again later"}
_SPEC_CACHE = []


def _spec():
    if not _SPEC_CACHE:
        with open(SPEC_PATH, encoding="utf-8") as fh:
            _SPEC_CACHE.append(yaml_subset.loads(fh.read(), "openapi.yaml"))
    return _SPEC_CACHE[0]


def _operation(path, method):
    paths = _spec().get("paths", {})
    item = paths.get(path)
    if not isinstance(item, dict):
        raise AssertionError(f"{path} is missing from the spec paths")
    op = item.get(method)
    if not isinstance(op, dict):
        raise AssertionError(f"{path} has no {method} operation")
    return op


class EndSessionContractTest(unittest.TestCase):
    def test_end_session_path_exists_and_is_a_delete(self):
        """Catches a missing path, a wrong method, or a route that is not a delete."""
        op = _operation(SESSION_PATH, "delete")
        self.assertIsInstance(op, dict, f"{SESSION_PATH} must expose a delete operation")

    def test_end_session_operation_id_is_end_session(self):
        """Catches a renamed operationId (the client wires by this name)."""
        op = _operation(SESSION_PATH, "delete")
        self.assertEqual(op.get("operationId"), "endSession", "operationId")

    def test_end_session_security_requires_bearer_auth(self):
        """Catches an open route or a missing bearerAuth requirement."""
        op = _operation(SESSION_PATH, "delete")
        self.assertEqual(op.get("security"), [{"bearerAuth": []}], f"{SESSION_PATH} security")

    def test_end_session_id_parameter_is_a_path_int64(self):
        """Catches a missing, moved, or mistyped id parameter (place, kind, required flag)."""
        op = _operation(SESSION_PATH, "delete")
        params = op.get("parameters", [])
        self.assertEqual(len(params), 1, "parameters list length")
        param = params[0]
        self.assertEqual(param.get("name"), "id", "parameter name")
        self.assertEqual(param.get("in"), "path", "parameter in")
        self.assertEqual(param.get("required"), "true", "parameter required")
        schema = param.get("schema", {})
        self.assertEqual(schema.get("type"), "integer", "id schema type")
        self.assertEqual(schema.get("format"), "int64", "id schema format")

    def test_end_session_204_has_no_content(self):
        """Catches a 204 answer that wrongly carries a body."""
        responses = _operation(SESSION_PATH, "delete").get("responses", {})
        self.assertIn("204", responses, "204 response missing")
        self.assertNotIn("content", responses["204"], "204 content")

    def test_end_session_401_body(self):
        """Catches the 401 body drifting off the fixed session-required answer."""
        media = _operation(SESSION_PATH, "delete")["responses"]["401"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "401 schema")
        self.assertEqual(media.get("example"), UNAUTHORIZED, "401 example")

    def test_end_session_403_body(self):
        """Catches the 403 body drifting off the fixed admin-refused answer."""
        media = _operation(SESSION_PATH, "delete")["responses"]["403"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "403 schema")
        self.assertEqual(media.get("example"), FORBIDDEN, "403 example")

    def test_end_session_404_body(self):
        """Catches a 404 body that echoes the id or differs from the fixed not_found answer."""
        media = _operation(SESSION_PATH, "delete")["responses"]["404"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "404 schema")
        self.assertEqual(media.get("example"), NOT_FOUND, "404 example")

    def test_end_session_503_body(self):
        """Catches a 503 body that differs from the fixed unavailable answer."""
        media = _operation(SESSION_PATH, "delete")["responses"]["503"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "503 schema")
        self.assertEqual(media.get("example"), UNAVAILABLE, "503 example")


if __name__ == "__main__":
    unittest.main()
