"""Unit tests for the api-contracts admin SCHEMAS and description-wording pins.

Tests the nine schemas appended to the OpenAPI spec across the admin
endpoint operations: the RoleUpdateRequest/Response, AdminHealth,
TranscriptionService, TranscriptionServiceUpdate, TestServiceResult,
ResetAccountRequest/Response and ClearStoreRequest schemas (field/type/
required-ness), plus the server-effects / validation wording pinned by the
set-account and transcription-service endpoint descriptions. Uses the
yaml_subset parser to validate schema shape and description wording.

Run: python3 -m unittest discover -s tests/unit/shared/api-contracts -t tests/unit/shared/api-contracts
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


def _load_spec():
    """Load and parse the openapi.yaml file."""
    spec_path = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
    if not os.path.isfile(spec_path):
        raise AssertionError(f"openapi.yaml not found at {spec_path}")
    with open(spec_path, encoding="utf-8") as fh:
        text = fh.read()
    return yaml_subset.loads(text, "openapi.yaml")


class AdminRoleUpdateRequestSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_role_update_request_exists(self):
        self.assertIn("RoleUpdateRequest", self.schemas)

    def test_role_update_request_fields(self):
        props = self.schemas["RoleUpdateRequest"]["properties"]
        self.assertEqual(set(props), {"username", "admin"})

    def test_role_update_request_required(self):
        self.assertEqual(
            set(self.schemas["RoleUpdateRequest"].get("required", [])),
            {"username", "admin"},
        )

    def test_admin_field_is_boolean(self):
        self.assertEqual(self.schemas["RoleUpdateRequest"]["properties"]["admin"]["type"], "boolean")


class AdminRoleUpdateResponseSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_role_update_response_exists(self):
        self.assertIn("RoleUpdateResponse", self.schemas)

    def test_role_field_description_reuses_role_wording(self):
        """role description reuses the existing LoginResponse role wording."""
        desc = self.schemas["RoleUpdateResponse"]["properties"]["role"]["description"]
        self.assertIn('The account role ("admin" or "user")', desc)


class AdminHealthSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_admin_health_exists(self):
        self.assertIn("AdminHealth", self.schemas)

    def test_admin_health_fields(self):
        self.assertEqual(
            set(self.schemas["AdminHealth"].get("required", [])),
            {"status", "git_tag"},
        )

    def test_admin_health_property_set_is_status_and_git_tag(self):
        self.assertEqual(set(self.schemas["AdminHealth"]["properties"]), {"status", "git_tag"})

    def test_admin_health_is_distinct_from_job_group_health(self):
        """The spec contains BOTH a Health and an AdminHealth schema, and they
        are not the same object (distinct field sets)."""
        self.assertIn("Health", self.schemas)
        self.assertIn("AdminHealth", self.schemas)
        health_props = set(self.schemas["Health"]["properties"])
        admin_health_props = set(self.schemas["AdminHealth"]["properties"])
        self.assertNotEqual(health_props, admin_health_props,
                            "AdminHealth must be a distinct schema from Health")


class TranscriptionServiceSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_transcription_service_exists(self):
        self.assertIn("TranscriptionService", self.schemas)

    def test_transcription_service_property_set_is_no_api_key(self):
        # The key is never echoed: property set is exactly {name, base_url,
        # api_key_set, enabled} -- NOT api_key.
        self.assertEqual(
            set(self.schemas["TranscriptionService"]["properties"]),
            {"name", "base_url", "api_key_set", "enabled"},
        )

    def test_transcription_service_required(self):
        self.assertEqual(
            set(self.schemas["TranscriptionService"].get("required", [])),
            {"name", "base_url", "api_key_set", "enabled"},
        )

    def test_admin_get_operation_refers_to_transcription_service(self):
        ref = self.paths["/v1/admin/transcription-service"]["get"]["responses"]["200"]["content"]["application/json"]["schema"].get("$ref")
        self.assertEqual(ref, "#/components/schemas/TranscriptionService")


class TranscriptionServiceUpdateSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_update_exists(self):
        self.assertIn("TranscriptionServiceUpdate", self.schemas)

    def test_update_has_api_key_property(self):
        props = self.schemas["TranscriptionServiceUpdate"]["properties"]
        self.assertIn("api_key", props)

    def test_api_key_is_nullable_and_not_required(self):
        api_key = self.schemas["TranscriptionServiceUpdate"]["properties"]["api_key"]
        self.assertEqual(api_key.get("type"), "string")
        self.assertTrue(api_key.get("nullable", False))
        # api_key is write-only/optional -- it must not appear in required.
        self.assertNotIn("api_key", self.schemas["TranscriptionServiceUpdate"].get("required", []))


class TestServiceResultSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_test_service_result_exists(self):
        self.assertIn("TestServiceResult", self.schemas)

    def test_test_service_result_field(self):
        self.assertEqual(
            set(self.schemas["TestServiceResult"].get("required", [])),
            {"reachable"},
        )
        props = self.schemas["TestServiceResult"]["properties"]
        self.assertEqual(props["reachable"]["type"], "boolean")


class ResetAccountRequestSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_reset_account_request_exists(self):
        self.assertIn("ResetAccountRequest", self.schemas)

    def test_reset_account_request_field_is_string_required(self):
        self.assertEqual(
            set(self.schemas["ResetAccountRequest"].get("required", [])),
            {"username"},
        )
        self.assertEqual(self.schemas["ResetAccountRequest"]["properties"]["username"]["type"], "string")


class ResetAccountResponseSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_reset_account_response_exists(self):
        self.assertIn("ResetAccountResponse", self.schemas)

    def test_reset_code_is_string_required(self):
        self.assertEqual(
            set(self.schemas["ResetAccountResponse"].get("required", [])),
            {"reset_code"},
        )
        self.assertEqual(self.schemas["ResetAccountResponse"]["properties"]["reset_code"]["type"], "string")


class ClearStoreRequestSchemaTest(unittest.TestCase):
    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_clear_store_request_exists(self):
        self.assertIn("ClearStoreRequest", self.schemas)

    def test_confirm_is_boolean_and_required(self):
        self.assertEqual(
            set(self.schemas["ClearStoreRequest"].get("required", [])),
            {"confirm"},
        )
        self.assertEqual(self.schemas["ClearStoreRequest"]["properties"]["confirm"]["type"], "boolean")

    def test_username_is_nullable_not_required(self):
        username = self.schemas["ClearStoreRequest"]["properties"]["username"]
        self.assertTrue(username.get("nullable", False))
        self.assertNotIn("username", self.schemas["ClearStoreRequest"].get("required", []))


class AdminDescriptionWordsTest(unittest.TestCase):
    """The endpoint descriptions pin the server-effects / validation wording."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_reset_account_description_words(self):
        desc = self.paths["/v1/admin/reset-account"]["post"]["description"]
        lowered = desc.lower()
        for phrase in ("keys", "transcriptions", "pending-sealed", "tokens"):
            self.assertIn(phrase, lowered)
        # one-time reset code with the 128-bit / single-use / 24-hour framing
        self.assertIn("reset code", lowered)
        self.assertIn("128", desc)
        self.assertIn("single use", lowered)
        self.assertIn("24-hour", lowered)

    def test_reset_account_token_kind_sentence(self):
        """The description names the bearer kinds: an admin session token OR an
        admin-scoped agent token."""
        desc = self.paths["/v1/admin/reset-account"]["post"]["description"]
        lowered = desc.lower()
        self.assertIn("admin session token", lowered)
        self.assertIn("admin-scoped agent token", lowered)

    def test_test_service_description_url_validation(self):
        """The connectivity-test description names the validation-before-probe
        behaviour: a valid URL is checked before any network attempt, so an
        unvalidated target is refused rather than probed."""
        desc = self.paths["/v1/admin/test-service"]["post"]["description"]
        lowered = desc.lower()
        self.assertIn("url", lowered)
        self.assertIn("reachable", lowered)
        self.assertIn("network attempt", lowered)

    def test_transcription_get_description_key_never_returned(self):
        desc = self.paths["/v1/admin/transcription-service"]["get"]["description"]
        lowered = desc.lower()
        self.assertIn("api key", lowered)
        self.assertIn("api_key_set", desc)

    def test_transcription_put_description_last_write_wins(self):
        desc = self.paths["/v1/admin/transcription-service"]["put"]["description"]
        lowered = desc.lower()
        self.assertIn("last-write-wins", lowered)
        self.assertIn("write-only", lowered)

    def test_clear_store_description_words(self):
        desc = self.paths["/v1/admin/clear-store"]["post"]["description"]
        lowered = desc.lower()
        self.assertIn("null or omitted", lowered)
        self.assertIn("for all users", lowered)
        self.assertIn("confirm", lowered)
        self.assertIn("400", lowered)


if __name__ == "__main__":
    unittest.main()
