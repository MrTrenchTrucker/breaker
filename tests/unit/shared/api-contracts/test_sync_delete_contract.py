"""Unit tests for the api-contracts sync delete group.

Tests the OpenAPI spec for DELETE /v1/transcriptions/{client_id} and the
resave mark on POST /v1/sync. Uses the yaml_subset parser (the same one the
Python tier already uses) to parse the spec and validate paths, methods,
response codes, security, the path-id pattern, and the resave mark.

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


class DeleteTranscriptionPathTest(unittest.TestCase):
    """DELETE /v1/transcriptions/{client_id} must exist with delete method."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_delete_transcriptions_path_exists(self):
        self.assertIn("/v1/transcriptions/{client_id}", self.paths)

    def test_delete_transcriptions_method_is_delete(self):
        self.assertIn("delete", self.paths["/v1/transcriptions/{client_id}"])

    def test_delete_operation_id_is_delete_transcription(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        self.assertEqual(op.get("operationId"), "deleteTranscription")

    def test_delete_has_path_param_client_id(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        params = op.get("parameters", [])
        names = [p.get("name") for p in params]
        self.assertIn("client_id", names)

    def test_delete_path_param_pattern_is_lowercase_uuid(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        params = op.get("parameters", [])
        for p in params:
            if p.get("name") == "client_id":
                schema = p.get("schema", {})
                pattern = schema.get("pattern")
                self.assertIsNotNone(pattern)
                import re
                good = "0f9c" + "4d1e-6a2b-4c3d-9e8f-" + "1a2b3c" + "4d5e6f"
                bad_upper = good.upper()
                bad_no_hyphen = good.replace("-", "")
                bad_empty = ""
                bad_37 = good + "X"
                self.assertIsNotNone(re.fullmatch(pattern, good))
                self.assertIsNone(re.fullmatch(pattern, bad_upper))
                self.assertIsNone(re.fullmatch(pattern, bad_no_hyphen))
                self.assertIsNone(re.fullmatch(pattern, bad_empty))
                self.assertIsNone(re.fullmatch(pattern, bad_37))

    def test_delete_has_no_request_body(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        self.assertNotIn("requestBody", op)

    def test_delete_status_204_has_no_content(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        responses = op.get("responses", [])
        if isinstance(responses, dict):
            resp = responses.get("204", {})
        else:
            resp = {}
        content = resp.get("content")
        self.assertIsNone(content)

    def test_delete_statuses_no_404_no_410(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        responses = op.get("responses", {})
        self.assertNotIn("404", responses)
        self.assertNotIn("410", responses)

    def test_delete_declares_200_400_401_403_503(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        responses = op.get("responses", {})
        for code in ("204", "400", "401", "403", "503"):
            self.assertIn(code, responses, code)

    def test_delete_400_body_is_fixed_error(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        responses = op.get("responses", {})
        resp400 = responses.get("400", {})
        content = resp400.get("content", {})
        appjson = content.get("application/json", {})
        schema = appjson.get("schema", {})
        self.assertEqual(schema.get("$ref"), "#/components/schemas/Error")
        example = appjson.get("example", {})
        self.assertEqual(example.get("error"), "bad_request")
        self.assertEqual(example.get("message"), "client id is not a lowercase canonical UUID")

    def test_delete_has_bearer_auth_security(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        security = op.get("security", [])
        self.assertIn({"bearerAuth": []}, security)

    def test_delete_summary_contains_delete(self):
        op = self.paths["/v1/transcriptions/{client_id}"]["delete"]
        self.assertIn("Delete", op.get("summary", ""))


class DeletePlainWordRulesTest(unittest.TestCase):
    """Plain-word rules in the DELETE operation description."""

    def setUp(self):
        self.spec = _load_spec()
        self.desc = self.spec["paths"]["/v1/transcriptions/{client_id}"]["delete"].get("description", "")

    def test_delete_description_says_new_change_number(self):
        self.assertIn("new change number", self.desc)

    def test_delete_description_says_clears_body_and_sets_deleted(self):
        self.assertIn("clears the body", self.desc)
        self.assertIn("sets deleted", self.desc)

    def test_delete_description_says_row_keeps_id_and_client_id(self):
        self.assertIn("row keeps its id", self.desc)
        self.assertIn("client id", self.desc)

    def test_delete_description_says_repeat_takes_no_change_number(self):
        self.assertIn("repeat or unknown delete writes nothing", self.desc)

    def test_delete_description_says_no_key_version(self):
        self.assertIn("carries no key_version", self.desc)

    def test_delete_description_no_404_no_410(self):
        self.assertNotIn("404", self.desc)
        self.assertNotIn("410", self.desc)


class SyncResaveMarkTest(unittest.TestCase):
    """The resave mark on POST /v1/sync."""

    def setUp(self):
        self.spec = _load_spec()
        self.desc = self.spec["paths"]["/v1/sync"]["post"].get("description", "")

    def test_sync_description_mentions_resave_mark(self):
        self.assertIn("resave", self.desc)

    def test_sync_description_says_resave_is_boolean(self):
        self.assertIn("boolean", self.desc)

    def test_sync_description_says_unknown_member_is_400(self):
        self.assertIn("any member other than", self.desc)
        self.assertIn("refused with 400", self.desc)

    def test_sync_schema_has_resave_property(self):
        props = self.spec["components"]["schemas"]["SyncPush"]["properties"]
        self.assertIn("resave", props)
        self.assertEqual(props["resave"]["type"], "boolean")

    def test_sync_schema_resave_not_required(self):
        required = set(self.spec["components"]["schemas"]["SyncPush"].get("required", []))
        self.assertNotIn("resave", required)

    def test_sync_description_says_non_boolean_resave_is_400(self):
        self.assertIn("not a boolean (string, number, null) is a 400", self.desc)

    def test_sync_description_resave_mark_full_text(self):
        self.assertIn("(first push of an id the phone itself deleted, never a retry)", self.desc)

    def test_sync_schema_resave_description_says_server_not_built(self):
        desc = self.spec["components"]["schemas"]["SyncPush"]["properties"]["resave"]["description"]
        self.assertIn("that change is not built", desc)

    def test_sync_description_absent_or_false_means_no_mark(self):
        self.assertIn("absent or false means no mark", " ".join(self.desc.split()))

    def test_sync_description_other_member_list_includes_resave(self):
        self.assertIn("any member other than client_id, key_version, encrypted and resave is refused with 400", " ".join(self.desc.split()))

    def test_sync_description_deleted_id_without_mark_writes_nothing(self):
        flat = " ".join(self.desc.split())
        self.assertIn("deleted on the server answers 204 and writes nothing, unless it carries the mark", flat)

    def test_sync_description_deleted_id_with_mark_is_a_fresh_save(self):
        flat = " ".join(self.desc.split())
        self.assertIn("fresh save (the new body, deleted becomes false, a new change number is taken, 204)", flat)

    def test_sync_description_mark_on_a_live_id_changes_nothing(self):
        flat = " ".join(self.desc.split())
        self.assertIn("The mark on an id that is not deleted changes nothing", flat)


if __name__ == "__main__":
    unittest.main()
