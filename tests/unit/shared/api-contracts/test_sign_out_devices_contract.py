"""Contract pins for DELETE /v1/auth/devices/{id} and POST /v1/auth/devices/sign-out-all:
the paths, their methods, security, parameters, the fixed error bodies, and the
204 responses that carry no content.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_sign_out_devices_contract
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
DEVICE_PATH = "/v1/auth/devices/{id}"
SIGN_OUT_ALL_PATH = "/v1/auth/devices/sign-out-all"
ERROR_REF = "#/components/schemas/Error"
UNAUTHORIZED = {"error": "unauthorized", "message": "Invalid or missing user session token"}
FORBIDDEN = {"error": "forbidden", "message": "User session token required; an agent token is refused"}
NOT_FOUND = {"error": "not_found", "message": "No such device"}
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


class SignOutDevicesContractTest(unittest.TestCase):
    def test_sign_out_device_path_exists_and_is_a_delete(self):
        """Catches a missing path, a wrong method, or a route that is not a delete."""
        op = _operation(DEVICE_PATH, "delete")
        self.assertIsInstance(op, dict, f"{DEVICE_PATH} must expose a delete operation")

    def test_sign_out_device_operation_id_is_sign_out_device(self):
        """Catches a renamed operationId (the client wires by this name)."""
        op = _operation(DEVICE_PATH, "delete")
        self.assertEqual(op.get("operationId"), "signOutDevice", "operationId")

    def test_sign_out_device_security_requires_bearer_auth(self):
        """Catches an open route or a missing bearerAuth requirement."""
        op = _operation(DEVICE_PATH, "delete")
        self.assertEqual(op.get("security"), [{"bearerAuth": []}], f"{DEVICE_PATH} security")

    def test_sign_out_device_id_parameter_is_a_path_int64(self):
        """Catches a missing, moved, or mistyped id parameter (place, kind, required flag)."""
        op = _operation(DEVICE_PATH, "delete")
        params = op.get("parameters", [])
        self.assertEqual(len(params), 1, "parameters list length")
        param = params[0]
        self.assertEqual(param.get("name"), "id", "parameter name")
        self.assertEqual(param.get("in"), "path", "parameter in")
        self.assertEqual(param.get("required"), "true", "parameter required")
        schema = param.get("schema", {})
        self.assertEqual(schema.get("type"), "integer", "id schema type")
        self.assertEqual(schema.get("format"), "int64", "id schema format")

    def test_sign_out_device_204_has_no_content(self):
        """Catches a 204 answer that wrongly carries a body."""
        responses = _operation(DEVICE_PATH, "delete").get("responses", {})
        self.assertIn("204", responses, "204 response missing")
        self.assertNotIn("content", responses["204"], "204 content")

    def test_sign_out_device_401_body(self):
        """Catches the 401 body drifting off the fixed session-required answer."""
        media = _operation(DEVICE_PATH, "delete")["responses"]["401"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "401 schema")
        self.assertEqual(media.get("example"), UNAUTHORIZED, "401 example")

    def test_sign_out_device_403_body(self):
        """Catches the 403 body drifting off the fixed agent-refused answer."""
        media = _operation(DEVICE_PATH, "delete")["responses"]["403"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "403 schema")
        self.assertEqual(media.get("example"), FORBIDDEN, "403 example")

    def test_sign_out_device_404_body(self):
        """Catches a 404 body that echoes the id or differs from the fixed not_found answer."""
        media = _operation(DEVICE_PATH, "delete")["responses"]["404"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "404 schema")
        self.assertEqual(media.get("example"), NOT_FOUND, "404 example")

    def test_sign_out_device_503_body(self):
        """Catches a 503 body that differs from the fixed unavailable answer."""
        media = _operation(DEVICE_PATH, "delete")["responses"]["503"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "503 schema")
        self.assertEqual(media.get("example"), UNAVAILABLE, "503 example")

    def test_sign_out_all_path_exists_and_is_a_post(self):
        """Catches a missing path, a wrong method, or a route that is not a post."""
        op = _operation(SIGN_OUT_ALL_PATH, "post")
        self.assertIsInstance(op, dict, f"{SIGN_OUT_ALL_PATH} must expose a post operation")

    def test_sign_out_all_operation_id_is_sign_out_all_devices(self):
        """Catches a renamed operationId (the client wires by this name)."""
        op = _operation(SIGN_OUT_ALL_PATH, "post")
        self.assertEqual(op.get("operationId"), "signOutAllDevices", "operationId")

    def test_sign_out_all_security_requires_bearer_auth(self):
        """Catches an open route or a missing bearerAuth requirement."""
        op = _operation(SIGN_OUT_ALL_PATH, "post")
        self.assertEqual(op.get("security"), [{"bearerAuth": []}], f"{SIGN_OUT_ALL_PATH} security")

    def test_sign_out_all_204_has_no_content(self):
        """Catches a 204 answer that wrongly carries a body."""
        responses = _operation(SIGN_OUT_ALL_PATH, "post").get("responses", {})
        self.assertIn("204", responses, "204 response missing")
        self.assertNotIn("content", responses["204"], "204 content")

    def test_sign_out_all_401_403_503_bodies(self):
        """Catches any of the three fixed error bodies drifting off this route."""
        responses = _operation(SIGN_OUT_ALL_PATH, "post")["responses"]
        expected = {"401": UNAUTHORIZED, "403": FORBIDDEN, "503": UNAVAILABLE}
        for code, body in expected.items():
            media = responses[code]["content"]["application/json"]
            self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, f"{code} schema")
            self.assertEqual(media.get("example"), body, f"{code} example")

    def test_sign_out_all_has_no_parameters(self):
        """Catches a stray parameters key on a route that takes none."""
        op = _operation(SIGN_OUT_ALL_PATH, "post")
        self.assertNotIn("parameters", op, f"{SIGN_OUT_ALL_PATH} parameters")


if __name__ == "__main__":
    unittest.main()
