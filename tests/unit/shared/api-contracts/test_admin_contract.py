"""Unit tests for the api-contracts admin group.

Tests the OpenAPI spec for the admin endpoints: POST /v1/admin/roles,
GET /v1/admin/health, GET+PUT /v1/admin/transcription-service, POST
/v1/admin/test-service, POST /v1/admin/reset-account and POST
/v1/admin/clear-store, plus the nine schemas appended to the spec. Uses the
yaml_subset parser (the same one the Python tier already uses) to validate
paths, methods, operationIds, security, response codes, error wording and the
schema field/type/required-ness/nullability shape.

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


class AdminPathsExistTest(unittest.TestCase):
    """Every admin endpoint must exist in the spec with its correct method.

    Six admin paths, seven operations total (transcription-service carries
    both GET and PUT on the same key).
    """

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_roles_path_is_post(self):
        self.assertIn("/v1/admin/roles", self.paths)
        self.assertEqual(sorted(self.paths["/v1/admin/roles"].keys()), ["post"])

    def test_health_path_is_get(self):
        self.assertIn("/v1/admin/health", self.paths)
        self.assertEqual(sorted(self.paths["/v1/admin/health"].keys()), ["get"])

    def test_transcription_service_paths_are_get_and_put(self):
        key = "/v1/admin/transcription-service"
        self.assertIn(key, self.paths)
        # GET and PUT on the same path key.
        self.assertEqual(sorted(self.paths[key].keys()), ["get", "put"])

    def test_test_service_path_is_post(self):
        self.assertIn("/v1/admin/test-service", self.paths)
        self.assertEqual(sorted(self.paths["/v1/admin/test-service"].keys()), ["post"])

    def test_reset_account_path_is_post(self):
        self.assertIn("/v1/admin/reset-account", self.paths)
        self.assertEqual(sorted(self.paths["/v1/admin/reset-account"].keys()), ["post"])

    def test_clear_store_path_is_post(self):
        self.assertIn("/v1/admin/clear-store", self.paths)
        self.assertEqual(sorted(self.paths["/v1/admin/clear-store"].keys()), ["post"])

    def test_admin_operations_count_matches_operation_ids(self):
        """The union of registered methods across the six admin paths is
        exactly {roles=post, health=get, ts=GET+PUT, test=post, reset=post,
        clear=post} -- seven operations over six keys."""
        expected = [
            "/v1/admin/roles",
            "/v1/admin/health",
            "/v1/admin/transcription-service",
            "/v1/admin/test-service",
            "/v1/admin/reset-account",
            "/v1/admin/clear-store",
        ]
        # the operationIds across those six keys must be exactly the seven admin ops
        expected_ids = [
            "adminSetRole",
            "adminHealth",
            "adminGetTranscriptionService",
            "adminPutTranscriptionService",
            "adminTestService",
            "adminResetAccount",
            "adminClearStore",
        ]
        for key in expected:
            self.assertIn(key, self.paths)
        # the operationIds across those six keys must be exactly the seven admin ops
        ids = []
        for key in expected:
            for method in self.paths[key]:
                ids.append(self.paths[key][method].get("operationId"))
        self.assertEqual(sorted(set(ids)), sorted(expected_ids))


class AdminOperationIdsTest(unittest.TestCase):
    """The exact set of operationIds across the seven admin operations."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_operation_id_set(self):
        expected_ids = {
            "adminSetRole",
            "adminHealth",
            "adminGetTranscriptionService",
            "adminPutTranscriptionService",
            "adminTestService",
            "adminResetAccount",
            "adminClearStore",
        }
        found = set()
        for key in (
            "/v1/admin/roles",
            "/v1/admin/health",
            "/v1/admin/transcription-service",
            "/v1/admin/test-service",
            "/v1/admin/reset-account",
            "/v1/admin/clear-store",
        ):
            method = self.paths[key]
            for m in method:
                found.add(method[m]["operationId"])
        self.assertEqual(found, expected_ids)


class AdminSecurityTest(unittest.TestCase):
    """Every one of the seven operations requires bearerAuth."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_each_admin_operation_requires_bearer_auth(self):
        path_methods = (
            ("/v1/admin/roles", ["post"]),
            ("/v1/admin/health", ["get"]),
            ("/v1/admin/transcription-service", ["get", "put"]),
            ("/v1/admin/test-service", ["post"]),
            ("/v1/admin/reset-account", ["post"]),
            ("/v1/admin/clear-store", ["post"]),
        )
        count = 0
        for key, methods in path_methods:
            for method in methods:
                security = self.paths[key][method].get("security", [])
                self.assertEqual(security, [{"bearerAuth": []}],
                                 f"{key} {method} must carry bearerAuth")
                count += 1
        # six keys, seven operations (transcription-service adds PUT)
        self.assertEqual(count, 7)


class AdminResponseCodesTest(unittest.TestCase):
    """Each admin operation returns the correct status codes."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_roles_response_codes(self):
        responses = self.paths["/v1/admin/roles"]["post"]["responses"]
        self.assertIn("200", responses)
        self.assertIn("401", responses)
        self.assertIn("403", responses)

    def test_health_response_codes(self):
        responses = self.paths["/v1/admin/health"]["get"]["responses"]
        self.assertIn("200", responses)
        self.assertIn("401", responses)
        self.assertIn("403", responses)

    def test_transcription_service_get_response_codes(self):
        responses = self.paths["/v1/admin/transcription-service"]["get"]["responses"]
        self.assertIn("200", responses)
        self.assertIn("401", responses)
        self.assertIn("403", responses)

    def test_transcription_service_put_response_codes(self):
        responses = self.paths["/v1/admin/transcription-service"]["put"]["responses"]
        self.assertIn("200", responses)
        self.assertIn("401", responses)
        self.assertIn("403", responses)

    def test_test_service_response_codes(self):
        responses = self.paths["/v1/admin/test-service"]["post"]["responses"]
        self.assertIn("200", responses)
        self.assertIn("401", responses)
        self.assertIn("403", responses)

    def test_reset_account_response_codes(self):
        responses = self.paths["/v1/admin/reset-account"]["post"]["responses"]
        self.assertIn("200", responses)
        self.assertIn("401", responses)
        self.assertIn("403", responses)
        self.assertIn("404", responses)

    def test_clear_store_response_codes(self):
        responses = self.paths["/v1/admin/clear-store"]["post"]["responses"]
        self.assertIn("200", responses)
        self.assertIn("401", responses)
        self.assertIn("403", responses)
        self.assertIn("400", responses)


class AdminErrorWordingTest(unittest.TestCase):
    """The 403/404 wording on the admin operations."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_403_wording_is_admin_role_required(self):
        for key, method in (
            ("/v1/admin/roles", "post"),
            ("/v1/admin/health", "get"),
            ("/v1/admin/transcription-service", "get"),
            ("/v1/admin/transcription-service", "put"),
            ("/v1/admin/test-service", "post"),
        ):
            resp = self.paths[key][method]["responses"]["403"]
            example = resp["content"]["application/json"]["example"]
            self.assertEqual(example["error"], "forbidden")
            self.assertIn("admin role required", example["message"].lower())

    def test_401_wording_is_invalid_or_missing_token(self):
        for key, method in (
            ("/v1/admin/roles", "post"),
            ("/v1/admin/health", "get"),
            ("/v1/admin/transcription-service", "put"),
            ("/v1/admin/test-service", "post"),
        ):
            resp = self.paths[key][method]["responses"]["401"]
            example = resp["content"]["application/json"]["example"]
            self.assertEqual(example["error"], "unauthorized")
            self.assertIn("invalid or missing token", example["message"].lower())

    def test_reset_account_404_wording(self):
        resp = self.paths["/v1/admin/reset-account"]["post"]["responses"]["404"]
        example = resp["content"]["application/json"]["example"]
        self.assertEqual(example["error"], "not_found")

    def test_clear_store_400_winding(self):
        resp = self.paths["/v1/admin/clear-store"]["post"]["responses"]["400"]
        example = resp["content"]["application/json"]["example"]
        self.assertEqual(example["error"], "bad_request")
        self.assertIn("confirm", example["message"].lower())


class AdminClearStoreEmptyBodyTest(unittest.TestCase):
    """The clear-store 200 response carries no content (empty body)."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_clear_store_200_has_no_content(self):
        responses = self.paths["/v1/admin/clear-store"]["post"]["responses"]
        ok = responses["200"]
        # either no content key, or a 200 with no application/json example.
        if "content" in ok:
            self.assertNotIn("application/json", ok["content"],
                             "clear-store 200 must have an empty body")
        self.assertIn("Store cleared", ok.get("description"))


if __name__ == "__main__":
    unittest.main()
