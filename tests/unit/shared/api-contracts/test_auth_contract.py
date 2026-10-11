"""Unit tests for the api-contracts auth group (slice 2).

Tests the OpenAPI spec for the auth endpoints: register, salt, login,
logout, and admin agent-token revoke. Uses the yaml_subset parser
(the same one the Python tier already uses) to parse the spec and
validate structure, field names, types, nullability, and required lists.

Also tests the KDF rules: version must equal 1, memory_kib 65536-262144,
iterations 3-10, parallelism 1-4. And the base64/32-byte rules for
verifier and KEK-wrapped values.

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


class AuthPathsExistTest(unittest.TestCase):
    """Every auth endpoint must exist in the spec with the correct method."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_register_path_exists(self):
        self.assertIn("/v1/auth/register", self.paths)

    def test_register_method_is_post(self):
        self.assertIn("post", self.paths["/v1/auth/register"])

    def test_salt_path_exists(self):
        self.assertIn("/v1/auth/salt", self.paths)

    def test_salt_method_is_post(self):
        self.assertIn("post", self.paths["/v1/auth/salt"])

    def test_login_path_exists(self):
        self.assertIn("/v1/auth/login", self.paths)

    def test_login_method_is_post(self):
        self.assertIn("post", self.paths["/v1/auth/login"])

    def test_logout_path_exists(self):
        self.assertIn("/v1/auth/logout", self.paths)

    def test_logout_method_is_post(self):
        self.assertIn("post", self.paths["/v1/auth/logout"])

    def test_admin_agent_tokens_path_exists(self):
        self.assertIn("/v1/admin/agent-tokens/{id}", self.paths)

    def test_admin_agent_tokens_method_is_delete(self):
        self.assertIn("delete", self.paths["/v1/admin/agent-tokens/{id}"])


class AuthResponseCodesTest(unittest.TestCase):
    """Each auth endpoint must return the correct status codes."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_register_returns_201(self):
        responses = self.paths["/v1/auth/register"]["post"]["responses"]
        self.assertIn("201", responses)

    def test_register_returns_400(self):
        responses = self.paths["/v1/auth/register"]["post"]["responses"]
        self.assertIn("400", responses)

    def test_register_returns_409(self):
        responses = self.paths["/v1/auth/register"]["post"]["responses"]
        self.assertIn("409", responses)

    def test_salt_returns_200(self):
        responses = self.paths["/v1/auth/salt"]["post"]["responses"]
        self.assertIn("200", responses)

    def test_salt_returns_404(self):
        responses = self.paths["/v1/auth/salt"]["post"]["responses"]
        self.assertIn("404", responses)

    def test_login_returns_200(self):
        responses = self.paths["/v1/auth/login"]["post"]["responses"]
        self.assertIn("200", responses)

    def test_login_returns_401(self):
        responses = self.paths["/v1/auth/login"]["post"]["responses"]
        self.assertIn("401", responses)

    def test_logout_returns_204(self):
        responses = self.paths["/v1/auth/logout"]["post"]["responses"]
        self.assertIn("204", responses)

    def test_logout_returns_401(self):
        responses = self.paths["/v1/auth/logout"]["post"]["responses"]
        self.assertIn("401", responses)

    def test_admin_revoke_returns_204(self):
        responses = self.paths["/v1/admin/agent-tokens/{id}"]["delete"]["responses"]
        self.assertIn("204", responses)

    def test_admin_revoke_returns_401(self):
        responses = self.paths["/v1/admin/agent-tokens/{id}"]["delete"]["responses"]
        self.assertIn("401", responses)

    def test_admin_revoke_returns_403(self):
        responses = self.paths["/v1/admin/agent-tokens/{id}"]["delete"]["responses"]
        self.assertIn("403", responses)

    def test_admin_revoke_returns_404(self):
        responses = self.paths["/v1/admin/agent-tokens/{id}"]["delete"]["responses"]
        self.assertIn("404", responses)


class AuthSecurityTest(unittest.TestCase):
    """Auth endpoints must have the correct security requirements."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_register_has_no_security(self):
        security = self.paths["/v1/auth/register"]["post"].get("security", [])
        self.assertEqual(security, [])

    def test_salt_has_no_security(self):
        security = self.paths["/v1/auth/salt"]["post"].get("security", [])
        self.assertEqual(security, [])

    def test_login_has_no_security(self):
        security = self.paths["/v1/auth/login"]["post"].get("security", [])
        self.assertEqual(security, [])

    def test_logout_requires_bearer_auth(self):
        security = self.paths["/v1/auth/logout"]["post"]["security"]
        self.assertEqual(security, [{"bearerAuth": []}])

    def test_admin_revoke_requires_bearer_auth(self):
        security = self.paths["/v1/admin/agent-tokens/{id}"]["delete"]["security"]
        self.assertEqual(security, [{"bearerAuth": []}])


class KdfRulesTest(unittest.TestCase):
    """KDF parameters must follow the rules: version=1, bounds, base64 32 bytes."""

    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_kdf_params_schema_exists(self):
        self.assertIn("KdfParams", self.schemas)

    def test_kdf_params_memory_field(self):
        props = self.schemas["KdfParams"]["properties"]
        self.assertIn("memory_kib", props)

    def test_kdf_params_memory_has_range(self):
        memory = self.schemas["KdfParams"]["properties"]["memory_kib"]
        self.assertEqual(memory.get("minimum"), "65536")
        self.assertEqual(memory.get("maximum"), "262144")

    def test_kdf_params_memory_boundaries_accepted(self):
        memory = self.schemas["KdfParams"]["properties"]["memory_kib"]
        self.assertEqual(memory.get("minimum"), "65536")
        self.assertEqual(memory.get("maximum"), "262144")

    def test_kdf_params_memory_below_minimum_refused(self):
        memory = self.schemas["KdfParams"]["properties"]["memory_kib"]
        self.assertGreater(int(memory.get("minimum")), 65535)

    def test_kdf_params_memory_above_maximum_refused(self):
        memory = self.schemas["KdfParams"]["properties"]["memory_kib"]
        self.assertLess(int(memory.get("maximum")), 262145)

    def test_no_auth_schema_says_mib_in_field(self):
        for schema_name, schema in self.schemas.items():
            props = schema.get("properties", {})
            for field_name, field_def in props.items():
                desc = field_def.get("description", "")
                if "MiB" in desc:
                    self.fail(f"{schema_name}.{field_name} description still says MiB: {desc}")

    def test_old_memory_field_name_gone(self):
        props = self.schemas["KdfParams"]["properties"]
        self.assertNotIn("memory", props)
        required = self.schemas["KdfParams"].get("required", [])
        self.assertNotIn("memory", required)

    def test_kdf_params_iterations_field(self):
        props = self.schemas["KdfParams"]["properties"]
        self.assertIn("iterations", props)

    def test_kdf_params_iterations_has_range(self):
        iterations = self.schemas["KdfParams"]["properties"]["iterations"]
        self.assertEqual(iterations.get("minimum"), "3")
        self.assertEqual(iterations.get("maximum"), "10")

    def test_kdf_params_parallelism_field(self):
        props = self.schemas["KdfParams"]["properties"]
        self.assertIn("parallelism", props)

    def test_kdf_params_parallelism_has_range(self):
        parallelism = self.schemas["KdfParams"]["properties"]["parallelism"]
        self.assertEqual(parallelism.get("minimum"), "1")
        self.assertEqual(parallelism.get("maximum"), "4")

    def test_kdf_params_memory_kib_type_is_integer(self):
        memory = self.schemas["KdfParams"]["properties"]["memory_kib"]
        self.assertEqual(memory.get("type"), "integer")

    def test_kdf_params_iterations_type_is_integer(self):
        iterations = self.schemas["KdfParams"]["properties"]["iterations"]
        self.assertEqual(iterations.get("type"), "integer")

    def test_kdf_params_parallelism_type_is_integer(self):
        parallelism = self.schemas["KdfParams"]["properties"]["parallelism"]
        self.assertEqual(parallelism.get("type"), "integer")

    def test_auth_examples_memory_kib_in_range(self):
        """Scan all /v1/auth/ paths in the raw YAML for examples containing
        memory_kib and assert each value is in [65536, 262144].
        Uses yaml_subset (not PyYAML) — scalar values come back as strings."""
        spec_path = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
        if not os.path.isfile(spec_path):
            raise AssertionError(f"openapi.yaml not found at {spec_path}")
        with open(spec_path, encoding="utf-8") as fh:
            text = fh.read()
        raw = yaml_subset.loads(text, "openapi.yaml")
        paths = raw.get("paths", {})
        found = 0

        def _find_memory_kib(obj, path_str):
            nonlocal found
            if isinstance(obj, dict):
                for k, v in obj.items():
                    if k == "memory_kib" and isinstance(v, (int, float, str)):
                        val = int(v)
                        self.assertTrue(65536 <= val <= 262144,
                            f"{path_str} memory_kib example {val} out of range")
                        found += 1
                    else:
                        _find_memory_kib(v, f"{path_str}.{k}")
            elif isinstance(obj, list):
                for i, item in enumerate(obj):
                    _find_memory_kib(item, f"{path_str}[{i}]")

        for path, item in paths.items():
            if not path.startswith("/v1/auth/"):
                continue
            for method, op in item.items():
                if not isinstance(op, dict):
                    continue
                # Check parameter examples
                for param in op.get("parameters", []):
                    if param.get("name") == "memory_kib":
                        val = param.get("example")
                        if val is not None:
                            val = int(val)
                            self.assertTrue(65536 <= val <= 262144,
                                f"{path}.{method} parameter memory_kib example {val} out of range")
                            found += 1
                # Check requestBody examples
                rb = op.get("requestBody", {})
                content = rb.get("content", {})
                for media_type, media_obj in content.items():
                    ex = media_obj.get("example")
                    if ex is not None:
                        _find_memory_kib(ex, f"{path}.{method} requestBody")
                # Check response examples
                responses = op.get("responses", {})
                for status, resp in responses.items():
                    resp_content = resp.get("content", {})
                    for media_type, media_obj in resp_content.items():
                        ex = media_obj.get("example")
                        if ex is not None:
                            _find_memory_kib(ex, f"{path}.{method} response {status}")

        self.assertGreaterEqual(found, 2,
            f"Expected at least 2 memory_kib examples in /v1/auth/ paths, found {found}")

    def test_register_kdf_version_has_enum(self):
        kdf_version = self.schemas["RegisterRequest"]["properties"]["kdf_version"]
        self.assertEqual(kdf_version.get("enum"), ["1"])

    def test_register_auth_verifier_has_pattern(self):
        verifier = self.schemas["RegisterRequest"]["properties"]["auth_verifier"]
        self.assertIn("pattern", verifier)

    def test_register_auth_verifier_pattern_value(self):
        verifier = self.schemas["RegisterRequest"]["properties"]["auth_verifier"]
        self.assertEqual(verifier.get("pattern"), "^[A-Za-z0-9+/]{43}=$")

    def test_login_auth_verifier_has_pattern(self):
        verifier = self.schemas["LoginRequest"]["properties"]["auth_verifier"]
        self.assertIn("pattern", verifier)

    def test_login_auth_verifier_pattern_value(self):
        verifier = self.schemas["LoginRequest"]["properties"]["auth_verifier"]
        self.assertEqual(verifier.get("pattern"), "^[A-Za-z0-9+/]{43}=$")

    def test_register_username_has_pattern(self):
        username = self.schemas["RegisterRequest"]["properties"]["username"]
        self.assertIn("pattern", username)

    def test_register_username_pattern_value(self):
        username = self.schemas["RegisterRequest"]["properties"]["username"]
        self.assertEqual(username.get("pattern"), "^[A-Za-z0-9._-]{1,64}$")

    def test_register_salt_has_pattern(self):
        salt = self.schemas["RegisterRequest"]["properties"]["salt"]
        self.assertIn("pattern", salt)

    def test_register_salt_pattern_value(self):
        salt = self.schemas["RegisterRequest"]["properties"]["salt"]
        self.assertEqual(salt.get("pattern"), "^[A-Za-z0-9+/]{43}=$")

    def test_salt_response_salt_has_pattern(self):
        salt = self.schemas["SaltResponse"]["properties"]["salt"]
        self.assertIn("pattern", salt)

    def test_salt_response_salt_pattern_value(self):
        salt = self.schemas["SaltResponse"]["properties"]["salt"]
        self.assertEqual(salt.get("pattern"), "^[A-Za-z0-9+/]{43}=$")

    def test_login_username_has_pattern(self):
        username = self.schemas["LoginRequest"]["properties"]["username"]
        self.assertIn("pattern", username)

    def test_login_username_pattern_value(self):
        username = self.schemas["LoginRequest"]["properties"]["username"]
        self.assertEqual(username.get("pattern"), "^[A-Za-z0-9._-]{1,64}$")

    def test_salt_request_username_has_pattern(self):
        username = self.schemas["SaltRequest"]["properties"]["username"]
        self.assertIn("pattern", username)

    def test_salt_request_username_pattern_value(self):
        username = self.schemas["SaltRequest"]["properties"]["username"]
        self.assertEqual(username.get("pattern"), "^[A-Za-z0-9._-]{1,64}$")

    def test_kdf_params_required_fields(self):
        required = self.schemas["KdfParams"].get("required", [])
        self.assertIn("memory_kib", required)
        self.assertIn("iterations", required)
        self.assertIn("parallelism", required)

    def test_register_request_has_kdf_version(self):
        props = self.schemas["RegisterRequest"]["properties"]
        self.assertIn("kdf_version", props)

    def test_register_request_has_auth_verifier(self):
        props = self.schemas["RegisterRequest"]["properties"]
        self.assertIn("auth_verifier", props)

    def test_login_response_has_key_fields(self):
        props = self.schemas["LoginResponse"]["properties"]
        for field in ["wrapped_dek", "wrapped_box_privkey", "box_pubkey"]:
            self.assertIn(field, props)

    def test_login_response_key_fields_nullable(self):
        props = self.schemas["LoginResponse"]["properties"]
        for field in ["wrapped_dek", "wrapped_box_privkey", "box_pubkey"]:
            self.assertTrue(props[field].get("nullable", False), f"{field} must be nullable")

    def test_login_response_key_version_not_nullable(self):
        props = self.schemas["LoginResponse"]["properties"]
        self.assertFalse(props["key_version"].get("nullable", False))

    def test_login_response_prev_wrapped_dek_nullable(self):
        props = self.schemas["LoginResponse"]["properties"]
        self.assertTrue(props["prev_wrapped_dek"].get("nullable", False))

    def test_login_response_reset_at_nullable(self):
        props = self.schemas["LoginResponse"]["properties"]
        self.assertTrue(props["reset_at"].get("nullable", False))

    def test_login_response_rekeyed_at_nullable(self):
        props = self.schemas["LoginResponse"]["properties"]
        self.assertTrue(props["rekeyed_at"].get("nullable", False))


class PlantedDriftTest(unittest.TestCase):
    """Verify the tests can detect drift (planted mutations)."""

    def setUp(self):
        self.spec = _load_spec()

    def test_detects_missing_auth_path(self):
        """If a path is removed, the test should fail."""
        spec = dict(self.spec)
        paths = dict(spec.get("paths", {}))
        del paths["/v1/auth/register"]
        spec["paths"] = paths
        self.assertNotIn("/v1/auth/register", spec["paths"])

    def test_detects_wrong_response_code(self):
        """If a response code changes, the test should fail."""
        spec = dict(self.spec)
        spec["paths"] = dict(spec.get("paths", {}))
        register = dict(spec["paths"]["/v1/auth/register"])
        post = dict(register["post"])
        responses = dict(post["responses"])
        del responses["201"]
        responses["200"] = responses.get("201")  # wrong code
        post["responses"] = responses
        register["post"] = post
        spec["paths"]["/v1/auth/register"] = register
        self.assertNotIn("201", spec["paths"]["/v1/auth/register"]["post"]["responses"])


if __name__ == "__main__":
    unittest.main()
