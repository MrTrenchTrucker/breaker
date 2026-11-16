"""Unit tests for the wrong-pw-limit contract.

Tests the OpenAPI spec for POST /v1/auth/login (423 locked answer with
Retry-After header) and the new admin path GET+PUT /v1/admin/wrong-pw-limit,
plus the WrongPwLimit schema. Uses the yaml_subset parser (the same one the
Python tier already uses) to validate paths, methods, operationIds, security,
response codes, headers, schema field/type/required-ness and error wording.

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

LOGIN_PATH = "/v1/auth/login"
WRONG_PW_LIMIT_PATH = "/v1/admin/wrong-pw-limit"
ERROR_REF = "#/components/schemas/Error"


def _load_spec():
    """Load and parse the openapi.yaml file."""
    spec_path = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
    if not os.path.isfile(spec_path):
        raise AssertionError(f"openapi.yaml not found at {spec_path}")
    with open(spec_path, encoding="utf-8") as fh:
        text = fh.read()
    return yaml_subset.loads(text, "openapi.yaml")


class Login423LockedAnswerTest(unittest.TestCase):
    """POST /v1/auth/login answers locked, 423 without a header, after the 5th wrong try."""

    def setUp(self):
        self.spec = _load_spec()
        self.login = self.spec.get("paths", {}).get(LOGIN_PATH, {}).get("post", {})

    def test_login_path_is_post(self):
        self.assertIn(LOGIN_PATH, self.spec.get("paths", {}))
        self.assertEqual(sorted(self.spec["paths"][LOGIN_PATH].keys()), ["post"])

    def test_login_423_response_exists(self):
        responses = self.login.get("responses", {})
        self.assertIn("423", responses, "login must carry a 423 locked answer")

    def test_login_423_has_no_retry_after_header(self):
        responses = self.login.get("responses", {})
        resp423 = responses.get("423", {})
        headers = resp423.get("headers", None)
        self.assertIsNone(headers, "423 response must not carry any header")
        self.assertNotIn("Retry-After", {},
                         "423 must not tell the caller how long to wait")

    def test_login_423_body_is_locked_error(self):
        responses = self.login.get("responses", {})
        resp423 = responses.get("423", {})
        content = resp423.get("content", {})
        app_json = content.get("application/json", {})
        self.assertEqual(app_json.get("schema", {}).get("$ref"), ERROR_REF,
                         "423 body must use the Error schema")
        example = app_json.get("example", {})
        self.assertEqual(example.get("error"), "locked",
                         "423 example error must be locked")
        self.assertEqual(example.get("message"), "Too many wrong tries. Try again later.",
                         "423 example message must be exactly the fixed sentence")

    def test_login_401_unchanged(self):
        responses = self.login.get("responses", {})
        self.assertIn("401", responses, "login must still carry plain 401")
        resp401 = responses["401"]
        example = resp401.get("content", {}).get("application/json", {}).get("example", {})
        self.assertEqual(example.get("error"), "unauthorized",
                         "401 error must stay unauthorized")
        self.assertEqual(example.get("message"), "Invalid username or verifier",
                         "401 message must stay byte for byte")

    def test_login_423_description_names_no_header(self):
        desc = self.login.get("description", "")
        self.assertIn("no Retry-After header: it carries no number or wait", desc,
                      "login 423 description must say the locked answer carries no header, number or wait")
        self.assertNotIn("Retry-After in seconds", desc,
                         "login 423 description must not name a Retry-After in seconds")

    def test_change_password_423_description_names_no_header(self):
        cp = self.spec.get("paths", {}).get("/v1/auth/change-password", {}).get("post", {})
        desc = cp.get("description", "")
        self.assertIn("A locked name gets the locked answer (423", desc,
                      "change-password description must name the locked 423 answer")
        self.assertIn("with no Retry-After header; the caller gets no number and no wait", desc,
                      "change-password 423 description must say no header, no number, no wait")
        self.assertNotIn("Retry-After in whole seconds", desc,
                         "change-password 423 description must not name a timed Retry-After")

    def test_423_example_message_is_fixed_sentence(self):
        for path, method in ((LOGIN_PATH, "post"), ("/v1/auth/change-password", "post")):
            resp = (self.spec.get("paths", {}).get(path, {})
                    .get(method, {}).get("responses", {}).get("423", {}))
            example = resp.get("content", {}).get("application/json", {}).get("example", {})
            self.assertEqual(example.get("error"), "locked",
                             path + " 423 example error must be locked")
            self.assertEqual(example.get("message"), "Too many wrong tries. Try again later.",
                             path + " 423 example message must be exactly the fixed sentence")
            self.assertNotIn("Try again in 15 minutes", example.get("message", ""),
                             path + " 423 example must not carry the old minutes sentence")

    def test_login_description_names_wrong_pw_limit_route(self):
        desc = self.login.get("description", "")
        self.assertIn("wrong-pw-limit", desc,
                      "login description must name the wrong-pw-limit route")
        self.assertIn("fifteen minute", desc,
                      "login description must name the fifteen minute wait")


class WrongPwLimitPathTest(unittest.TestCase):
    """GET+PUT /v1/admin/wrong-pw-limit, admin session only."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_wrong_pw_limit_path_exists(self):
        self.assertIn(WRONG_PW_LIMIT_PATH, self.paths)

    def test_wrong_pw_limit_methods_are_get_and_put(self):
        self.assertEqual(sorted(self.paths[WRONG_PW_LIMIT_PATH].keys()), ["get", "put"])

    def test_wrong_pw_limit_get_operation_id(self):
        op = self.paths[WRONG_PW_LIMIT_PATH]["get"]
        self.assertEqual(op.get("operationId"), "adminGetWrongPwLimit")

    def test_wrong_pw_limit_put_operation_id(self):
        op = self.paths[WRONG_PW_LIMIT_PATH]["put"]
        self.assertEqual(op.get("operationId"), "adminSetWrongPwLimit")

    def test_wrong_pw_limit_get_security_is_bearer(self):
        security = self.paths[WRONG_PW_LIMIT_PATH]["get"].get("security", [])
        self.assertEqual(security, [{"bearerAuth": []}],
                         "GET /v1/admin/wrong-pw-limit must require bearerAuth")

    def test_wrong_pw_limit_put_security_is_bearer(self):
        security = self.paths[WRONG_PW_LIMIT_PATH]["put"].get("security", [])
        self.assertEqual(security, [{"bearerAuth": []}],
                         "PUT /v1/admin/wrong-pw-limit must require bearerAuth")

    def test_wrong_pw_limit_get_statuses_200_401_403_and_503(self):
        responses = self.paths[WRONG_PW_LIMIT_PATH]["get"].get("responses", {})
        self.assertEqual(set(responses.keys()), {"200", "401", "403", "503"},
                         "GET must answer exactly 200/401/403/503 (503 is the fixed store-failure answer, no 400)")
        self.assertIn("401", responses)
        self.assertIn("403", responses)

    def test_wrong_pw_limit_put_statuses_200_400_401_403_and_503(self):
        responses = self.paths[WRONG_PW_LIMIT_PATH]["put"].get("responses", {})
        self.assertEqual(set(responses.keys()), {"200", "400", "401", "403", "503"},
                         "PUT must answer exactly 200/400/401/403/503 (503 is the fixed store-failure answer)")
        self.assertIn("400", responses)
        self.assertIn("401", responses)
        self.assertIn("403", responses)

    def test_wrong_pw_limit_put_request_body_is_wrong_pw_limit(self):
        op = self.paths[WRONG_PW_LIMIT_PATH]["put"]
        body = op.get("requestBody", {})
        self.assertTrue(body.get("required"), "PUT body must be required")
        content = body.get("content", {}).get("application/json", {})
        self.assertEqual(content.get("schema", {}).get("$ref"),
                         "#/components/schemas/WrongPwLimit",
                         "PUT body schema must be WrongPwLimit")
        example = content.get("example", {})
        self.assertEqual(set(example.keys()), {"enabled"},
                         "PUT example must hold exactly one member: enabled")

    def test_wrong_pw_limit_200_returns_wrong_pw_limit(self):
        for method in ("get", "put"):
            resp200 = self.paths[WRONG_PW_LIMIT_PATH][method]["responses"]["200"]
            schema = resp200.get("content", {}).get("application/json", {}).get("schema", {})
            self.assertEqual(schema.get("$ref"),
                             "#/components/schemas/WrongPwLimit",
                             f"{method.upper()} 200 must return WrongPwLimit")

    def test_wrong_pw_limit_400_body_is_bad_request(self):
        resp400 = self.paths[WRONG_PW_LIMIT_PATH]["put"]["responses"]["400"]
        example = resp400.get("content", {}).get("application/json", {}).get("example", {})
        self.assertEqual(example.get("error"), "bad_request",
                         "400 error must be bad_request")
        self.assertEqual(example.get("message"), "enabled must be true or false",
                         "400 message must be enabled must be true or false")


class WrongPwLimitSchemaTest(unittest.TestCase):
    """The WrongPwLimit schema: exactly enabled, boolean, required."""

    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_wrong_pw_limit_schema_exists(self):
        self.assertIn("WrongPwLimit", self.schemas)

    def test_wrong_pw_limit_properties_exactly_enabled(self):
        props = self.schemas["WrongPwLimit"]["properties"]
        self.assertEqual(set(props), {"enabled"},
                         "WrongPwLimit must have exactly one property: enabled")

    def test_wrong_pw_limit_enabled_is_boolean(self):
        self.assertEqual(self.schemas["WrongPwLimit"]["properties"]["enabled"]["type"],
                         "boolean",
                         "enabled must be boolean")

    def test_wrong_pw_limit_required_is_enabled(self):
        self.assertEqual(set(self.schemas["WrongPwLimit"].get("required", [])),
                         {"enabled"},
                         "enabled must be required")


if __name__ == "__main__":
    unittest.main()
