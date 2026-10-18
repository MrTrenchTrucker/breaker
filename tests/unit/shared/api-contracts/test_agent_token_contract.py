"""Unit tests for the agent-tokens contract group.

Tests the two admin operations (POST /v1/admin/agent-tokens,
GET /v1/admin/agent-tokens) and their schemas (AgentTokenScope,
AgentTokenCreateRequest, AgentToken, AgentTokenEntry) plus the three data-class
Kotlin types that mirror them. Uses the same stdlib + yaml_subset approach as
the sync contract tests (test_sync_contract.py) and the production Kotlin reader
(api_kotlin_reader / parse_all_kotlin).

Run: python3 -m unittest discover -s tests/unit/shared/api-contracts -p 'test_*.py'
"""
import os
import re
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset
from api_kotlin_reader import parse_all_kotlin


def _load_spec():
    """Load and parse the openapi.yaml file."""
    spec_path = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
    if not os.path.isfile(spec_path):
        raise AssertionError(f"openapi.yaml not found at {spec_path}")
    with open(spec_path, encoding="utf-8") as fh:
        text = fh.read()
    return yaml_subset.loads(text, "openapi.yaml")


def _schema(name):
    """Return the components/schemas entry for `name`."""
    return _load_spec()["components"]["schemas"][name]


def _kt():
    """Parse all Kotlin types via the production reader (raises on drift)."""
    return parse_all_kotlin()


_KOTLIN_DIR = os.path.join(
    ROOT, "shared", "modules", "api-contracts",
    "src", "main", "kotlin", "dev", "breaker", "shared", "api",
)


def _raw_kotlin_fields(class_name):
    """Return [(raw_name, jtype, nullable)] for the production-parsed data class.

    This reads the real .kt files directly (no snake-casing) so nullability can
    be asserted against the spec's nullable flags.
    """
    result = []
    text = open(os.path.join(_KOTLIN_DIR, f"{class_name}.kt"), encoding="utf-8").read()
    param_re = re.compile(r"^\s+val\s+(\w+)\s*:\s*([\w.]+)(\?)?(?:\s*=\s*[^,]+)?\s*,?\s*$")
    for line in text.splitlines():
        m = param_re.match(line)
        if m:
            result.append((m.group(1), m.group(2).strip(), bool(m.group(3))))
    return result


class AgentTokenPathsTest(unittest.TestCase):
    """The agent-tokens path exists with both verbs and the right operationIds."""

    def test_paths_exist(self):
        paths = _load_spec()["paths"]
        self.assertIn("/v1/admin/agent-tokens", paths)
        self.assertIn("post", paths["/v1/admin/agent-tokens"])
        self.assertIn("get", paths["/v1/admin/agent-tokens"])

    def test_create_operation_id(self):
        ops = _load_spec()["paths"]["/v1/admin/agent-tokens"]
        self.assertEqual(ops["post"]["operationId"], "adminCreateAgentToken")

    def test_list_operation_id(self):
        ops = _load_spec()["paths"]["/v1/admin/agent-tokens"]
        self.assertEqual(ops["get"]["operationId"], "adminListAgentTokens")


class AgentTokenSecurityTest(unittest.TestCase):
    """Both operations carry bearerAuth; responses are exactly the admin refusal set."""

    def setUp(self):
        self.ops = _load_spec()["paths"]["/v1/admin/agent-tokens"]

    def test_create_carries_bearer_security(self):
        sec = self.ops["post"].get("security", [])
        self.assertEqual(sec, [{"bearerAuth": []}])

    def test_list_carries_bearer_security(self):
        sec = self.ops["get"].get("security", [])
        self.assertEqual(sec, [{"bearerAuth": []}])

    def test_create_responses_are_exactly_201_401_403(self):
        responses = self.ops["post"]["responses"]
        self.assertEqual(set(responses), {"201", "401", "403"})

    def test_create_401_names_unauthorized(self):
        ex = self.ops["post"]["responses"]["401"]["content"]["application/json"]["example"]
        self.assertEqual(ex["error"], "unauthorized")
        self.assertIn("Invalid or missing token", ex["message"])

    def test_create_403_names_forbidden(self):
        ex = self.ops["post"]["responses"]["403"]["content"]["application/json"]["example"]
        self.assertEqual(ex["error"], "forbidden")
        self.assertIn("Admin role required", ex["message"])

    def test_list_responses_are_exactly_200_401_403(self):
        responses = self.ops["get"]["responses"]
        self.assertEqual(set(responses), {"200", "401", "403"})

    def test_list_401_names_unauthorized(self):
        ex = self.ops["get"]["responses"]["401"]["content"]["application/json"]["example"]
        self.assertEqual(ex["error"], "unauthorized")
        self.assertIn("Invalid or missing token", ex["message"])

    def test_list_403_names_forbidden(self):
        ex = self.ops["get"]["responses"]["403"]["content"]["application/json"]["example"]
        self.assertEqual(ex["error"], "forbidden")
        self.assertIn("Admin role required", ex["message"])


class AgentTokenCreateContractTest(unittest.TestCase):
    """The create operation: schema refs, description sentences, 201 example."""

    def setUp(self):
        self.ops = _load_spec()["paths"]["/v1/admin/agent-tokens"]

    def test_create_request_body_is_required_ref(self):
        body = self.ops["post"].get("requestBody")
        self.assertIsNotNone(body, "create must carry a requestBody")
        self.assertTrue(body.get("required", False), "create requestBody must be required")
        content = body["content"]["application/json"]
        self.assertEqual(content.get("schema", {}).get("$ref"),
                         "#/components/schemas/AgentTokenCreateRequest")

    def test_201_refs_agent_token(self):
        responses = self.ops["post"]["responses"]
        self.assertIn("201", responses)
        schema = responses["201"]["content"]["application/json"]["schema"]
        self.assertEqual(schema.get("$ref"), "#/components/schemas/AgentToken")

    def test_201_example_has_nonempty_token(self):
        token = self.ops["post"]["responses"]["201"]["content"]["application/json"]["example"].get("token", "")
        self.assertIsInstance(token, str)
        self.assertTrue(len(token) > 0, "the synthetic example token must be non-empty")

    def test_description_callable_endpoints(self):
        desc = self.ops["post"]["description"].lower()
        self.assertIn("post /v1/audio/transcriptions", desc)
        self.assertIn("get /v1/jobs/{job_id}", desc)

    def test_description_token_returned_only_here(self):
        self.assertIn("never again", self.ops["post"]["description"].lower())

    def test_description_null_expiry_wording(self):
        self.assertIn("no expiry", self.ops["post"]["description"].lower())

    def test_create_default_scope(self):
        create = _schema("AgentTokenCreateRequest")
        scope_prop = create["properties"]["scope"]
        refs = [item.get("$ref") for item in scope_prop.get("allOf", [])]
        self.assertEqual(refs, ["#/components/schemas/AgentTokenScope"],
                         "scope must be the allOf $ref form to AgentTokenScope "
                         "(OpenAPI 3.0 ignores siblings of a $ref)")
        self.assertNotIn("scope", create.get("required", []),
                         "scope must NOT be in the CreateRequest required list (default applies)")
        self.assertEqual(scope_prop.get("default"), "transcribe")


class AgentTokenListContractTest(unittest.TestCase):
    """The list operation: no request body, array 200 with $ref items and a synthetic example."""

    def setUp(self):
        self.ops = _load_spec()["paths"]["/v1/admin/agent-tokens"]

    def test_list_has_no_request_body(self):
        self.assertIsNone(
            self.ops["get"].get("requestBody"),
            "list must not carry a requestBody",
        )

    def test_200_is_array_of_agent_token_entry(self):
        responses = self.ops["get"]["responses"]
        self.assertIn("200", responses)
        schema = responses["200"]["content"]["application/json"]["schema"]
        self.assertEqual(schema.get("type"), "array")
        items = schema.get("items", {})
        self.assertEqual(items.get("$ref"), "#/components/schemas/AgentTokenEntry")

    def test_list_description_token_value_never_returned(self):
        desc = self.ops["get"]["description"]
        self.assertIn("token value", desc)
        self.assertIn("never here", desc.lower())

    def test_200_example_has_at_least_one_entry(self):
        example = self.ops["get"]["responses"]["200"]["content"]["application/json"]["example"]
        self.assertIsInstance(example, list)
        self.assertGreaterEqual(len(example), 1, "the synthetic 200 example must hold an array entry")


class AgentTokenScopeTest(unittest.TestCase):
    """The scope schema + required-list placement across the three schemas + Kotlin enum."""

    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec["components"]["schemas"]
        self.kotlin = parse_all_kotlin()

    def test_scope_schema_is_string_enum_exactly_two(self):
        scope = self.schemas["AgentTokenScope"]
        self.assertEqual(scope.get("type"), "string")
        self.assertEqual(set(scope.get("enum", [])), {"transcribe", "admin"})

    def test_create_request_scope_not_required_with_default(self):
        create = self.schemas["AgentTokenCreateRequest"]
        scope_prop = create["properties"]["scope"]
        refs = [item.get("$ref") for item in scope_prop.get("allOf", [])]
        self.assertEqual(refs, ["#/components/schemas/AgentTokenScope"],
                         "scope must be the allOf $ref form to AgentTokenScope "
                         "(OpenAPI 3.0 ignores siblings of a $ref)")
        self.assertNotIn("scope", create.get("required", []),
                         "scope must not be required on the request (default applies)")
        self.assertEqual(scope_prop.get("default"), "transcribe")

    def test_agent_token_scope_required(self):
        agent = self.schemas["AgentToken"]
        scope_prop = agent["properties"]["scope"]
        self.assertEqual(scope_prop.get("$ref"), "#/components/schemas/AgentTokenScope")
        self.assertIn("scope", agent.get("required", []),
                      "scope must be required on the AgentToken schema")

    def test_agent_token_entry_scope_required(self):
        entry = self.schemas["AgentTokenEntry"]
        scope_prop = entry["properties"]["scope"]
        self.assertEqual(scope_prop.get("$ref"), "#/components/schemas/AgentTokenScope")
        self.assertIn("scope", entry.get("required", []),
                      "scope must be required on the AgentTokenEntry schema")

    def test_kotlin_enum_members(self):
        """The Kotlin AgentTokenScope enum declares members TRANSCRIBE / ADMIN,
        exactly as the house JobStatus pattern does."""
        kt = self.kotlin.get("AgentTokenScope", {})
        self.assertEqual(kt.get("type"), "enum")
        self.assertEqual(set(kt.get("values", [])), {"transcribe", "admin"})

    def test_kotlin_enum_fromString_companion(self):
        text = open(os.path.join(_KOTLIN_DIR, "AgentTokenScope.kt"), encoding="utf-8").read()
        self.assertIn("enum class AgentTokenScope", text)
        self.assertIn("fromString", text)


class AgentTokenParityTest(unittest.TestCase):
    """Every data-class Kotlin type mirrors its schema (camelCase names, nullability)."""

    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec["components"]["schemas"]
        self.kotlin = parse_all_kotlin()

    def test_files_exist_and_declare_data_class(self):
        for name in ("AgentTokenCreateRequest", "AgentToken", "AgentTokenEntry"):
            path = os.path.join(_KOTLIN_DIR, f"{name}.kt")
            self.assertTrue(os.path.isfile(path), f"{name}.kt must exist in the Kotlin dir")
            text = open(path, encoding="utf-8").read()
            self.assertIn("package dev.breaker.shared.api", text)
            self.assertIn("data class", text)

    def test_fields_match_spec_names_and_nullability(self):
        """The DTO's raw property names (camelCase) match the schema's properties;
        a nullable spec field maps to an X? type, non-null otherwise."""
        pairs = [
            "AgentTokenCreateRequest",
            "AgentToken",
            "AgentTokenEntry",
        ]
        for schema_name in pairs:
            spec_schema = self.schemas[schema_name]
            spec_props = spec_schema.get("properties", {})

            raw_fields = _raw_kotlin_fields(schema_name)
            raw_names = {snake(f[0]) for f in raw_fields}
            self.assertEqual(raw_names, set(spec_props.keys()),
                             f"{schema_name}: Kotlin field names must match spec property names")

            for spec_name, prop in spec_props.items():
                nullable = bool(prop.get("nullable", False))
                matching = [f for f in raw_fields if snake(f[0]) == spec_name]
                self.assertEqual(len(matching), 1,
                                 f"{schema_name}.{spec_name}: expected one Kotlin field named {snake(spec_name)}")
                _, jtype, jnullable = matching[0]
                self.assertEqual(jnullable, nullable,
                                 f"{schema_name}.{spec_name}: nullability (X?) must follow the spec's nullable flag "
                                 f"(nullable={bool(prop.get('nullable', False))})")

    def test_entry_id_is_long(self):
        """AgentTokenEntry.id is Long in Kotlin against integer/int64 in the spec."""
        entry_spec = self.schemas["AgentTokenEntry"]
        id_prop = entry_spec["properties"]["id"]
        self.assertEqual(id_prop.get("type"), "integer")
        self.assertEqual(id_prop.get("format"), "int64")

        # the DELETE path {id} also takes integer/int64 -> both sides pinned identically
        del_id = self.spec["paths"]["/v1/admin/agent-tokens/{id}"]["delete"]["parameters"][0]
        del_schema = del_id.get("schema", {})
        self.assertEqual(del_schema.get("type"), "integer")
        self.assertEqual(del_schema.get("format"), "int64")

    def test_entry_id_long_in_kotlin(self):
        fields = dict((f[0], f) for f in _raw_kotlin_fields("AgentTokenEntry"))
        self.assertIn("id", fields, "AgentTokenEntry must expose an id field")
        name, jtype, nullable = fields["id"]
        self.assertEqual(jtype, "Long", "AgentTokenEntry.id must be Long in Kotlin")
        self.assertFalse(nullable, "AgentTokenEntry.id must be non-null")

    def test_create_scope_kotlin_default_is_null(self):
        """B1 pin: the Kotlin scope default is `null` (never a literal scope value).

        A default of `AgentTokenScope.ADMIN` would be a privilege escalation;
        null/omitted is read as `transcribe` by the server (the spec's
        `default: transcribe` is the wire default, the Kotlin default is null).
        Reads the Kotlin source line for scope (house reader style).
        """
        text = open(os.path.join(_KOTLIN_DIR, "AgentTokenCreateRequest.kt"),
                    encoding="utf-8").read()
        match = re.search(r"^\s+val scope:\s*AgentTokenScope\??\s*(=[^\n]+)?\s*,?\s*$",
                          text, re.M)
        self.assertIsNotNone(match, "AgentTokenCreateRequest.kt must declare a scope field")
        default = (match.group(1) or "").strip().rstrip(",").strip()
        self.assertEqual(default, "= null",
                         "the Kotlin scope default must be `= null` "
                         "(null/omitted is read as transcribe by the server; "
                         "a non-null default such as AgentTokenScope.ADMIN "
                         "would be a privilege escalation)")


# ---------------------------------------------------------------------------
# snake_case helper (mirrors api_kotlin_reader.snake for nullability mapping).
# ---------------------------------------------------------------------------

from api_kotlin_reader import snake


if __name__ == "__main__":
    unittest.main()
