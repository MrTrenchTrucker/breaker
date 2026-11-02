"""Spec pins for the sync body format: the client id pattern, the key_version 0
form of the encrypted text, the body's keys and ranges, the stored_at rename,
and the module card, sync-api card, README and ADR text that describe them.

Standard library only. Each test reads text, or decodes a spec example, and
asserts the pinned value.
"""
import base64
import json
import os
import re
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset

PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
BODY_KEYS = {"v", "text", "source", "model", "duration_ms", "created_at"}
ORDER_TEXT = "v, text, source, model, duration_ms, created_at"
GOOD_ID = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"
KEY0_SENTENCE = (
    "At key_version 0 this is the standard base64 (RFC 4648, padded, no line wrap) "
    "of the R1 body described in the /v1/sync operation, and no cryptography is applied: "
    "a key_version 0 body is development-only until Phase 19."
)
NO_CRYPTO_SENTENCE = (
    "no cryptography is applied: a key_version 0 body is development-only until Phase 19."
)


def _read(*parts):
    """Return the text of a file under the repository root."""
    with open(os.path.join(ROOT, *parts), encoding="utf-8") as fh:
        return fh.read()


def decode_r1(text):
    """Decode a key_version 0 encrypted text to its R1 body (a dict)."""
    raw = base64.b64decode(text, validate=True)
    assert base64.b64encode(raw).decode("ascii") == text, "base64 is not in canonical form"
    return json.loads(raw.decode("utf-8"))


class SyncBodySpecTest(unittest.TestCase):
    """Pins the sync body text, the client id pattern and the stored_at rename."""

    @classmethod
    def setUpClass(cls):
        spec_text = _read("shared", "modules", "api-contracts", "openapi.yaml")
        cls.spec = yaml_subset.loads(spec_text, "openapi.yaml")
        cls.schemas = cls.spec["components"]["schemas"]
        cls.sync_desc = cls.spec["paths"]["/v1/sync"]["post"]["description"]

    def _check_r1_body(self, encrypted):
        body = decode_r1(encrypted)
        self.assertIsInstance(body, dict)
        self.assertEqual(set(body), BODY_KEYS)
        self.assertIs(type(body["v"]), int)
        self.assertEqual(body["v"], 1)
        self.assertIn(body["source"], ("local", "server"))

    def test_sync_push_client_id_pattern_value(self):
        pattern = self.schemas["SyncPush"]["properties"]["client_id"]["pattern"]
        self.assertEqual(pattern, PATTERN)

    def test_transcription_record_client_id_pattern_value(self):
        pattern = self.schemas["TranscriptionRecord"]["properties"]["client_id"]["pattern"]
        self.assertEqual(pattern, PATTERN)

    def test_client_id_pattern_refuses_upper_case_and_missing_hyphens(self):
        pattern = self.schemas["SyncPush"]["properties"]["client_id"]["pattern"]
        self.assertIsNotNone(re.fullmatch(pattern, GOOD_ID))
        self.assertIsNone(re.fullmatch(pattern, GOOD_ID.upper()))
        self.assertIsNone(re.fullmatch(pattern, GOOD_ID.replace("-", "")))

    def test_sync_push_encrypted_names_key_version_0_and_no_cryptography(self):
        desc = self.schemas["SyncPush"]["properties"]["encrypted"]["description"]
        self.assertIn(KEY0_SENTENCE, desc)
        self.assertIn(NO_CRYPTO_SENTENCE, desc)

    def test_transcription_record_encrypted_names_key_version_0_and_no_cryptography(self):
        desc = self.schemas["TranscriptionRecord"]["properties"]["encrypted"]["description"]
        self.assertIn(KEY0_SENTENCE, desc)
        self.assertIn(NO_CRYPTO_SENTENCE, desc)

    def test_sync_description_has_the_body_keys_in_fixed_order(self):
        self.assertIn(ORDER_TEXT, self.sync_desc)

    def test_sync_description_states_the_body_ranges(self):
        for bound in ("9007199254740991", "1970-01-01T00:00:00.000Z",
                      "9999-12-31T23:59:59.999Z"):
            self.assertIn(bound, self.sync_desc, bound)

    def test_sync_example_is_a_key_version_0_r1_body(self):
        content = self.spec["paths"]["/v1/sync"]["post"]["requestBody"]["content"]
        example = content["application/json"]["example"]
        self.assertEqual(example["key_version"], "0")
        self._check_r1_body(example["encrypted"])

    def test_transcription_example_is_a_key_version_0_r1_body(self):
        resp = self.spec["paths"]["/v1/transcriptions"]["get"]["responses"]["200"]
        row = resp["content"]["application/json"]["example"]["items"][0]
        self.assertEqual(row["key_version"], "0")
        self._check_r1_body(row["encrypted"])
        self.assertIn("stored_at", row)
        self.assertNotIn("created_at", row)

    def test_transcription_record_has_stored_at_and_no_created_at(self):
        rec = self.schemas["TranscriptionRecord"]
        self.assertIn("stored_at", rec["properties"])
        self.assertNotIn("created_at", rec["properties"])
        self.assertIn("stored_at", rec["required"])
        self.assertNotIn("created_at", rec["required"])

    def test_pending_sealed_keeps_created_at(self):
        ps = self.schemas["PendingSealed"]
        self.assertIn("created_at", ps["properties"])
        self.assertIn("created_at", ps["required"])

    def test_card_invariant_states_v0_no_cryptography(self):
        text = _read("shared", "modules", "api-contracts", "AGENTS.md")
        self.assertIn(
            "- A key_version 0 body carries no cryptography and is development-only until Phase 19.",
            text)

    def test_card_names_the_vectors_and_the_spec_test(self):
        text = _read("shared", "modules", "api-contracts", "AGENTS.md")
        for name in ("examples/sync_body_vectors.json",
                     "test_sync_body_vectors.py",
                     "test_sync_body_spec.py"):
            self.assertIn(name, text, name)

    def test_sync_api_card_names_the_key_version_refusal(self):
        text = _read("server", "modules", "sync-api", "AGENTS.md")
        self.assertIn(
            "A push whose key_version is above the account's current key_version is refused",
            text)

    def test_adr_amendment_has_binding_and_planned_parts(self):
        text = _read("decisions", "ADR-006-encryption.md")
        self.assertIn("amended 2026-10-21 (the sync body format)", text)
        self.assertIn("## Amendment 2026-10-21: the sync body format", text)
        self.assertIn("### Planned for Phase 19, not built", text)
        for n in range(1, 8):
            self.assertIsNotNone(re.search(r"(?m)^- P%d " % n, text), "P%d" % n)

    def test_readme_names_the_key_version_0_form(self):
        text = _read("shared", "modules", "api-contracts", "README.md")
        self.assertIn("key_version 0", text)


if __name__ == "__main__":
    unittest.main()
