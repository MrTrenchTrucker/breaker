"""Contract pins for GET /v1/updates/latest.json: the path and method, the
operationId, the UpdateManifest schema with exactly six required properties,
the field types, version_code as int64 with minimum 1, and the Kotlin type
UpdateManifest with matching fields.

Run: python3 -m unittest tests.unit.shared.api-contracts.test_updates_contract
"""
import os
import sys
import unittest
from pathlib import Path

HERE = os.path.abspath(__file__)
ROOT = HERE
for _ in range(5):
    ROOT = os.path.dirname(ROOT)
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)
TEST_DIR = str(Path(__file__).resolve().parent)
if TEST_DIR not in sys.path:
    sys.path.insert(0, TEST_DIR)

import yaml_subset

SPEC_PATH = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
UPDATES_PATH = "/v1/updates/latest.json"
MANIFEST_REF = "#/components/schemas/UpdateManifest"
REQUIRED = ["version", "version_code", "apk_url", "sha256", "signature", "notes"]
STRING_FIELDS = ["apk_url", "sha256", "signature", "notes"]
_SPEC_CACHE = []


def _spec():
    if not _SPEC_CACHE:
        with open(SPEC_PATH, encoding="utf-8") as fh:
            _SPEC_CACHE.append(yaml_subset.loads(fh.read(), "openapi.yaml"))
    return _SPEC_CACHE[0]


def _operation():
    paths = _spec().get("paths", {})
    if UPDATES_PATH not in paths:
        raise AssertionError(f"{UPDATES_PATH} is missing from the spec paths")
    op = paths[UPDATES_PATH].get("get")
    if not isinstance(op, dict):
        raise AssertionError(f"{UPDATES_PATH} has no get operation")
    return op


def _schemas():
    return _spec().get("components", {}).get("schemas", {})


def _manifest():
    schemas = _schemas()
    if "UpdateManifest" not in schemas:
        raise AssertionError("UpdateManifest schema missing")
    return schemas["UpdateManifest"]


class UpdatesContractTest(unittest.TestCase):
    def test_updates_path_is_get_with_operation_id_get_update_manifest(self):
        """Catches a missing path, a wrong method, or a renamed operationId."""
        op = _operation()
        self.assertIsInstance(op, dict, f"{UPDATES_PATH} must expose a get operation")
        self.assertEqual(op.get("operationId"), "getUpdateManifest", "operationId")

    def test_answer_names_exactly_six_required_properties(self):
        """Catches a missing, extra, or renamed required property."""
        responses = _operation().get("responses", {})
        self.assertIn("200", responses, "200 response missing")
        media = responses["200"].get("content", {}).get("application/json", {})
        self.assertEqual(media.get("schema", {}).get("$ref"), MANIFEST_REF, "200 schema")
        manifest = _manifest()
        self.assertEqual(manifest.get("required"), REQUIRED, "UpdateManifest required")
        props = manifest.get("properties", {})
        self.assertEqual(set(props.keys()), set(REQUIRED), "UpdateManifest properties")

    def test_version_code_is_integer_int64_minimum_1(self):
        """Catches version_code with a wrong type, format, or minimum."""
        props = _manifest().get("properties", {})
        self.assertEqual(props["version"].get("type"), "string", "version type")
        version_code = props["version_code"]
        self.assertEqual(version_code.get("type"), "integer", "version_code type")
        self.assertEqual(version_code.get("format"), "int64", "version_code format")
        self.assertEqual(str(version_code.get("minimum")), "1", "version_code minimum")

    def test_the_other_four_are_plain_strings(self):
        """Catches apk_url, sha256, signature, or notes not being strings.

        Asserts only `type: string` for these four; absence of format or
        length rules is not pinned, so a later tightening stays legal.
        """
        props = _manifest().get("properties", {})
        for name in STRING_FIELDS:
            self.assertEqual(props[name].get("type"), "string", f"{name} type")

    def test_kotlin_type_update_manifest_exists_with_same_fields(self):
        """Catches a missing Kotlin type or fields that differ from the spec."""
        from api_contracts_support import (
            check_coverage, compare_fields, get_schemas, load_spec,
            map_spec_type_to_kotlin, parse_all_kotlin,
        )
        spec = load_spec()
        schemas = get_schemas(spec)
        self.assertIn("UpdateManifest", schemas, "UpdateManifest schema missing")
        kotlin = parse_all_kotlin()
        self.assertIn("UpdateManifest", kotlin, "UpdateManifest Kotlin type missing")
        kt = kotlin["UpdateManifest"]
        self.assertEqual(kt.get("type"), "data_class", "UpdateManifest Kotlin type")
        self.assertTrue(
            compare_fields(
                "UpdateManifest", schemas["UpdateManifest"], kt, kotlin, schemas
            ),
            "UpdateManifest fields must compare clean both ways",
        )
        self.assertTrue(
            check_coverage(schemas, kotlin),
            "schema and Kotlin coverage must hold both ways",
        )
        self.assertEqual(
            map_spec_type_to_kotlin("integer", "int64"), "Long", "version_code map"
        )


if __name__ == "__main__":
    unittest.main()
