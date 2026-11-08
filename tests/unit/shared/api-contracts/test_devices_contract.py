"""Contract pins for GET /v1/auth/devices: the path, its security, the
DeviceList and DeviceRow schemas, and the fixed 401, 403 and 503 bodies.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_devices_contract
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
DEVICES_PATH = "/v1/auth/devices"
ERROR_REF = "#/components/schemas/Error"
DEVICE_LIST_REF = "#/components/schemas/DeviceList"
DEVICE_ROW_REF = "#/components/schemas/DeviceRow"
UNAUTHORIZED = {"error": "unauthorized", "message": "Invalid or missing user session token"}
FORBIDDEN = {"error": "forbidden", "message": "User session token required; an agent token is refused"}
UNAVAILABLE = {"error": "unavailable", "message": "The service is unavailable, try again later"}
_SPEC_CACHE = []


def _spec():
    if not _SPEC_CACHE:
        with open(SPEC_PATH, encoding="utf-8") as fh:
            _SPEC_CACHE.append(yaml_subset.loads(fh.read(), "openapi.yaml"))
    return _SPEC_CACHE[0]


def _operation():
    paths = _spec().get("paths", {})
    if DEVICES_PATH not in paths:
        raise AssertionError(f"{DEVICES_PATH} is missing from the spec paths")
    op = paths[DEVICES_PATH].get("get")
    if not isinstance(op, dict):
        raise AssertionError(f"{DEVICES_PATH} has no get operation")
    return op


def _schemas():
    return _spec().get("components", {}).get("schemas", {})


class DevicesContractTest(unittest.TestCase):
    def test_path_exists_and_is_a_get(self):
        """Catches a missing path, a wrong method, or a path moved away from get."""
        op = _operation()
        self.assertIsInstance(op, dict, f"{DEVICES_PATH} must expose a get operation")

    def test_operation_id_is_list_devices(self):
        """Catches a renamed operationId (the client wires by this name)."""
        op = _operation()
        self.assertEqual(op.get("operationId"), "listDevices", "operationId")

    def test_security_requires_bearer_auth(self):
        """Catches an open route or a missing bearerAuth requirement."""
        op = _operation()
        self.assertEqual(op.get("security"), [{"bearerAuth": []}], f"{DEVICES_PATH} security")

    def test_200_schema_is_device_list(self):
        """Catches a 200 answer bound to another schema or to an inline shape."""
        responses = _operation().get("responses", {})
        self.assertIn("200", responses, "200 response missing")
        media = responses["200"].get("content", {}).get("application/json", {})
        self.assertEqual(media.get("schema", {}).get("$ref"), DEVICE_LIST_REF, "200 schema")

    def test_device_list_has_only_devices(self):
        """Catches extra members on DeviceList, a missing required list, or a wrong cap."""
        schemas = _schemas()
        self.assertIn("DeviceList", schemas, "DeviceList schema missing")
        device_list = schemas["DeviceList"]
        self.assertEqual(device_list.get("type"), "object", "DeviceList type")
        self.assertEqual(device_list.get("additionalProperties"), "false", "DeviceList additionalProperties")
        self.assertEqual(device_list.get("required"), ["devices"], "DeviceList required")
        props = device_list.get("properties", {})
        self.assertEqual(set(props.keys()), {"devices"}, "DeviceList properties")
        devices = props.get("devices", {})
        self.assertEqual(devices.get("type"), "array", "devices type")
        self.assertEqual(devices.get("maxItems"), "50", "devices maxItems")
        self.assertEqual(devices.get("items", {}).get("$ref"), DEVICE_ROW_REF, "devices items ref")

    def test_device_row_has_exactly_four_members(self):
        """Catches a row with a missing, extra or renamed member."""
        schemas = _schemas()
        self.assertIn("DeviceRow", schemas, "DeviceRow schema missing")
        device_row = schemas["DeviceRow"]
        self.assertEqual(device_row.get("type"), "object", "DeviceRow type")
        self.assertEqual(device_row.get("additionalProperties"), "false", "DeviceRow additionalProperties")
        # The spec convention is that a nullable member is not listed as required,
        # so name (nullable) sits outside the required list while still being one
        # of the four properties.
        required = device_row.get("required")
        self.assertEqual(required, ["id", "last_seen", "current"], "DeviceRow required")
        props = device_row.get("properties", {})
        self.assertEqual(set(props.keys()), {"id", "name", "last_seen", "current"}, "DeviceRow properties")

    def test_device_row_field_shapes(self):
        """Catches a wrong field type, format, or name bounds on DeviceRow."""
        schemas = _schemas()
        props = schemas["DeviceRow"]["properties"]
        id_field = props["id"]
        self.assertEqual(id_field.get("type"), "integer", "id type")
        self.assertEqual(id_field.get("format"), "int64", "id format")
        name_field = props["name"]
        self.assertEqual(name_field.get("type"), "string", "name type")
        self.assertEqual(name_field.get("nullable"), "true", "name nullable")
        self.assertEqual(name_field.get("minLength"), "1", "name minLength")
        self.assertEqual(name_field.get("maxLength"), "64", "name maxLength")
        self.assertEqual(props["last_seen"].get("type"), "string", "last_seen type")
        self.assertEqual(props["current"].get("type"), "boolean", "current type")

    def test_401_example_body(self):
        """Catches the logout 401 text drifting onto this route."""
        media = _operation()["responses"]["401"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "401 schema")
        self.assertEqual(media.get("example"), UNAUTHORIZED, "401 example")

    def test_403_example_body(self):
        """Catches the agent-token refusal text drifting off this route."""
        media = _operation()["responses"]["403"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "403 schema")
        self.assertEqual(media.get("example"), FORBIDDEN, "403 example")

    def test_503_example_body(self):
        """Catches a store-failure body that differs from the fixed unavailable answer."""
        media = _operation()["responses"]["503"]["content"]["application/json"]
        self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, "503 schema")
        self.assertEqual(media.get("example"), UNAVAILABLE, "503 example")


if __name__ == "__main__":
    unittest.main()
