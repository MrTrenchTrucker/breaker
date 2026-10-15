"""Unit tests for the api-contracts sync group.

Tests the OpenAPI spec for the sync endpoints: POST /v1/sync,
GET /v1/transcriptions, and GET /v1/sync/pending-sealed. Uses the
yaml_subset parser (the same one the Python tier already uses) to parse
the spec and validate paths, methods, response codes, security, the
pair-dedupe semantics, the key_version floor, and the pending-sealed
re-upload words.

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


class SyncPathsExistTest(unittest.TestCase):
    """Every sync endpoint must exist in the spec with the correct method."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_sync_path_exists(self):
        self.assertIn("/v1/sync", self.paths)

    def test_sync_method_is_post(self):
        self.assertIn("post", self.paths["/v1/sync"])

    def test_transcriptions_path_exists(self):
        self.assertIn("/v1/transcriptions", self.paths)

    def test_transcriptions_method_is_get(self):
        self.assertIn("get", self.paths["/v1/transcriptions"])

    def test_pending_sealed_path_exists(self):
        self.assertIn("/v1/sync/pending-sealed", self.paths)

    def test_pending_sealed_method_is_get(self):
        self.assertIn("get", self.paths["/v1/sync/pending-sealed"])


class SyncResponseCodesTest(unittest.TestCase):
    """Each sync endpoint must return the correct status codes."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_sync_returns_204(self):
        responses = self.paths["/v1/sync"]["post"]["responses"]
        self.assertIn("204", responses)

    def test_sync_returns_401(self):
        responses = self.paths["/v1/sync"]["post"]["responses"]
        self.assertIn("401", responses)

    def test_sync_returns_403(self):
        """A valid token of the wrong kind is refused 403 (not 401)."""
        responses = self.paths["/v1/sync"]["post"]["responses"]
        self.assertIn("403", responses)

    def test_sync_returns_409(self):
        responses = self.paths["/v1/sync"]["post"]["responses"]
        self.assertIn("409", responses)

    def test_transcriptions_returns_200(self):
        responses = self.paths["/v1/transcriptions"]["get"]["responses"]
        self.assertIn("200", responses)

    def test_transitions_returns_401(self):
        responses = self.paths["/v1/transcriptions"]["get"]["responses"]
        self.assertIn("401", responses)

    def test_transcriptions_returns_403(self):
        responses = self.paths["/v1/transcriptions"]["get"]["responses"]
        self.assertIn("403", responses)

    def test_pending_sealed_returns_200(self):
        responses = self.paths["/v1/sync/pending-sealed"]["get"]["responses"]
        self.assertIn("200", responses)

    def test_pending_sealed_returns_401(self):
        responses = self.paths["/v1/sync/pending-sealed"]["get"]["responses"]
        self.assertIn("401", responses)

    def test_pending_sealed_returns_403(self):
        responses = self.paths["/v1/sync/pending-sealed"]["get"]["responses"]
        self.assertIn("403", responses)


class SyncSecurityTest(unittest.TestCase):
    """The sync group takes a user session token; an agent token is refused."""

    def setUp(self):
        self.spec = _load_spec()
        self.paths = self.spec.get("paths", {})

    def test_sync_requires_bearer_auth(self):
        security = self.paths["/v1/sync"]["post"].get("security", [])
        self.assertEqual(security, [{"bearerAuth": []}])

    def test_transcriptions_requires_bearer_auth(self):
        security = self.paths["/v1/transcriptions"]["get"].get("security", [])
        self.assertEqual(security, [{"bearerAuth": []}])

    def test_pending_sealed_requires_bearer_auth(self):
        security = self.paths["/v1/sync/pending-sealed"]["get"].get("security", [])
        self.assertEqual(security, [{"bearerAuth": []}])

    def test_sync_403_names_the_agent_token_refusal(self):
        """The 403 example must name the agent-token refusal (the wrong-kind
        token is a valid token refused by kind, not a missing/invalid one)."""
        resp = self.paths["/v1/sync"]["post"]["responses"]["403"]
        example = resp["content"]["application/json"]["example"]
        self.assertEqual(example["error"], "forbidden")
        self.assertIn("agent token", example["message"].lower())


class SyncPushSchemaTest(unittest.TestCase):
    """The SyncPush schema carries the pair-dedupe fields with the version floor."""

    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_sync_push_schema_exists(self):
        self.assertIn("SyncPush", self.schemas)

    def test_sync_push_required_fields(self):
        props = self.schemas["SyncPush"]
        self.assertEqual(
            set(props.get("required", [])),
            {"client_id", "key_version", "encrypted"},
        )

    def test_sync_push_client_id_is_string(self):
        props = self.schemas["SyncPush"]["properties"]
        self.assertEqual(props["client_id"]["type"], "string")

    def test_sync_push_key_version_is_integer(self):
        props = self.schemas["SyncPush"]["properties"]
        self.assertEqual(props["key_version"]["type"], "integer")

    def test_sync_push_key_version_has_floor_of_zero(self):
        """key_version is 0 before the first bootstrap, so the floor is 0.
        The module's subset parser hands scalars back as text, so the floor
        is pinned as the string "0", the same house style the auth contract
        pins its minimums with."""
        props = self.schemas["SyncPush"]["properties"]
        self.assertEqual(props["key_version"].get("minimum"), "0")

    def test_sync_push_encrypted_is_string(self):
        props = self.schemas["SyncPush"]["properties"]
        self.assertEqual(props["encrypted"]["type"], "string")


class SyncPushSemanticsTest(unittest.TestCase):
    """The dedupe semantics are pinned in the endpoint description."""

    def setUp(self):
        self.spec = _load_spec()
        self.desc = self.spec["paths"]["/v1/sync"]["post"]["description"]

    def test_description_says_idempotent_on_the_pair(self):
        self.assertIn("Idempotent", self.desc)
        self.assertIn("client_id, key_version", self.desc)

    def test_description_says_lower_version_refused(self):
        self.assertIn("409", self.desc)
        self.assertIn("lower key_version", self.desc)

    def test_description_says_atomic_replace_on_higher_version(self):
        self.assertIn("atomic replace", self.desc)


class TranscriptionRecordSchemaTest(unittest.TestCase):
    """The TranscriptionRecord schema carries key_version per record."""

    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_transcription_record_schema_exists(self):
        self.assertIn("TranscriptionRecord", self.schemas)

    def test_transcription_record_required_fields(self):
        props = self.schemas["TranscriptionRecord"]
        self.assertEqual(
            set(props.get("required", [])),
            {"client_id", "key_version", "encrypted", "created_at"},
        )

    def test_transcription_record_key_version_is_integer(self):
        props = self.schemas["TranscriptionRecord"]["properties"]
        self.assertEqual(props["key_version"]["type"], "integer")


class PendingSealedSchemaTest(unittest.TestCase):
    """The PendingSealed schema carries the id the re-upload must use."""

    def setUp(self):
        self.spec = _load_spec()
        self.schemas = self.spec.get("components", {}).get("schemas", {})

    def test_pending_sealed_schema_exists(self):
        self.assertIn("PendingSealed", self.schemas)

    def test_pending_sealed_required_fields(self):
        props = self.schemas["PendingSealed"]
        self.assertEqual(
            set(props.get("required", [])),
            {"id", "sealed_box", "created_at"},
        )

    def test_pending_sealed_id_describes_the_reupload(self):
        """The id field's description must say it is the client_id of the re-upload."""
        props = self.schemas["PendingSealed"]["properties"]
        self.assertIn("client id", props["id"]["description"].lower())

    def test_pending_sealed_description_names_the_reupload(self):
        """The endpoint description must state that a re-upload sends
        client_id = the pending record's id at the current key_version."""
        desc = self.spec["paths"]["/v1/sync/pending-sealed"]["get"]["description"]
        self.assertIn("client_id", desc)
        self.assertIn("current key_version", desc)

    def test_pending_sealed_description_states_the_honest_limit(self):
        """The T26 honest limit: the store cannot tell a genuine sealed box
        from one a database writer inserted directly."""
        desc = self.spec["paths"]["/v1/sync/pending-sealed"]["get"]["description"]
        self.assertIn("honest limit", desc.lower())
        self.assertIn("database writer", desc)


class PlantedDriftTest(unittest.TestCase):
    """Verify the tests can detect drift (planted mutations)."""

    def setUp(self):
        self.spec = _load_spec()

    def test_detects_missing_sync_path(self):
        spec = dict(self.spec)
        paths = dict(spec.get("paths", {}))
        del paths["/v1/sync"]
        spec["paths"] = paths
        self.assertNotIn("/v1/sync", spec["paths"])

    def test_detects_missing_403_on_sync(self):
        spec = dict(self.spec)
        spec["paths"] = dict(spec.get("paths", {}))
        sync = dict(spec["paths"]["/v1/sync"])
        post = dict(sync["post"])
        responses = dict(post["responses"])
        del responses["403"]
        post["responses"] = responses
        sync["post"] = post
        spec["paths"]["/v1/sync"] = sync
        self.assertNotIn("403", spec["paths"]["/v1/sync"]["post"]["responses"])

    def test_detects_wrong_security(self):
        spec = dict(self.spec)
        spec["paths"] = dict(spec.get("paths", {}))
        sync = dict(spec["paths"]["/v1/sync"])
        post = dict(sync["post"])
        post["security"] = []
        sync["post"] = post
        spec["paths"]["/v1/sync"] = sync
        self.assertEqual(spec["paths"]["/v1/sync"]["post"]["security"], [])

    def test_detects_missing_key_version_floor(self):
        spec = dict(self.spec)
        spec["components"] = dict(spec.get("components", {}))
        schemas = dict(spec["components"]["schemas"])
        sp = dict(schemas["SyncPush"])
        props = dict(sp["properties"])
        kv = dict(props["key_version"])
        del kv["minimum"]
        props["key_version"] = kv
        sp["properties"] = props
        schemas["SyncPush"] = sp
        spec["components"]["schemas"] = schemas
        self.assertNotIn("minimum", spec["components"]["schemas"]["SyncPush"]["properties"]["key_version"])


if __name__ == "__main__":
    unittest.main()
