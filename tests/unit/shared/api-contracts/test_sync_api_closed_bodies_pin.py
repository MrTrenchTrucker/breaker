"""Contract pin: the sync auth request bodies are closed.

SaltRequest and LoginRequest must carry `additionalProperties: false`, matching
the house style of DeviceList/DeviceRow: the Kotlin routes answer 400
bad_request "Invalid request body" for an unknown member, so the contract must
not call such a member schema-valid.

The controls prove the pin can see closedness at all: DeviceList is already
closed, and RegisterRequest has exactly its six documented members.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_sync_api_closed_bodies_pin -v
"""
import os
import sys
import unittest

# Add tools directory to path for yaml_subset
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset

REGISTER_MEMBERS = {"username", "salt", "kdf_params", "kdf_version", "auth_verifier", "install_code"}

# Defaults to the repo spec; OPENAPI_SPEC_PATH lets a scratch copy be pinned
# (the mutant check uses it, the same hook as test_sync_api_status_gaps_pin).
SPEC_PATH = os.environ.get("OPENAPI_SPEC_PATH") or os.path.join(
    ROOT, "shared", "modules", "api-contracts", "openapi.yaml")


def _load_spec():
    """Load and parse the openapi.yaml file."""
    if not os.path.isfile(SPEC_PATH):
        raise AssertionError(f"openapi.yaml not found at {SPEC_PATH}")
    with open(SPEC_PATH, encoding="utf-8") as fh:
        text = fh.read()
    return yaml_subset.loads(text, SPEC_PATH)


class ClosedBodiesPinTest(unittest.TestCase):
    """SaltRequest and LoginRequest are closed; the controls keep the pin honest."""

    def setUp(self):
        self.schemas = _load_spec().get("components", {}).get("schemas", {})

    def test_salt_request_is_closed(self):
        """RED on base: SaltRequest carries no additionalProperties."""
        self.assertIn("SaltRequest", self.schemas, "SaltRequest schema missing")
        self.assertEqual(self.schemas["SaltRequest"].get("additionalProperties"), "false",
                         "SaltRequest additionalProperties")

    def test_login_request_is_closed(self):
        """RED on base: LoginRequest carries no additionalProperties."""
        self.assertIn("LoginRequest", self.schemas, "LoginRequest schema missing")
        self.assertEqual(self.schemas["LoginRequest"].get("additionalProperties"), "false",
                         "LoginRequest additionalProperties")

    def test_register_request_is_closed(self):
        """RED on base: RegisterRequest carries no additionalProperties.

        The register route refuses every unknown member except the optional
        install_code (AuthRegisterRoute.kt:82-90), so the request schema is
        closed over the five documented members plus install_code.
        """
        self.assertIn("RegisterRequest", self.schemas, "RegisterRequest schema missing")
        self.assertEqual(self.schemas["RegisterRequest"].get("additionalProperties"), "false",
                         "RegisterRequest additionalProperties")

    def test_control_device_list_is_closed(self):
        """GREEN on base: proves the pin can see a closed schema (not vacuous)."""
        self.assertIn("DeviceList", self.schemas, "DeviceList schema missing")
        self.assertEqual(self.schemas["DeviceList"].get("additionalProperties"), "false",
                         "DeviceList additionalProperties")

    def test_control_register_request_has_exactly_six_members(self):
        """GREEN on base: the six RegisterRequest members are exactly these."""
        self.assertIn("RegisterRequest", self.schemas, "RegisterRequest schema missing")
        props = self.schemas["RegisterRequest"].get("properties", {})
        self.assertEqual(set(props.keys()), REGISTER_MEMBERS,
                         "RegisterRequest must have exactly the six documented members")


if __name__ == "__main__":
    unittest.main()
