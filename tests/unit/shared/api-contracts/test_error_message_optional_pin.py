"""Contract pins: the Error schema has required exactly ["error"], message
is an optional string property, ApiError.kt declares val message: String?
= null with val error: String non-null, no production source constructs or
serialises ApiError, no production source contains "message":null, and the
whisper TranscriptionRoutes respondError body literal carries only the
error member (servers keep sending error only).

Run: python3 -m unittest tests.unit.shared.api-contracts.test_error_message_optional_pin
"""
import os
import re
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
API_ERROR_KT = os.path.join(
    ROOT,
    "shared",
    "modules",
    "api-contracts",
    "src",
    "main",
    "kotlin",
    "dev",
    "breaker",
    "shared",
    "api",
    "ApiError.kt",
)
TRANSCRIPTION_ROUTES_KT = os.path.join(
    ROOT,
    "server",
    "modules",
    "whisper-server",
    "src",
    "main",
    "kotlin",
    "dev",
    "breaker",
    "server",
    "whisper",
    "http",
    "TranscriptionRoutes.kt",
)
MAIN_SOURCE_DIRS = [
    os.path.join(ROOT, "server"),
    os.path.join(ROOT, "android"),
    os.path.join(ROOT, "shared"),
]


def _spec():
    with open(SPEC_PATH, encoding="utf-8") as fh:
        return yaml_subset.loads(fh.read(), "openapi.yaml")


def _error_schema():
    schemas = _spec().get("components", {}).get("schemas", {})
    if "Error" not in schemas:
        raise AssertionError("Error schema missing from components/schemas")
    return schemas["Error"]


def _read_text(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _find_respond_error(text):
    """Return the body of the respondError function, or None."""
    start = text.find("private suspend fun respondError")
    if start < 0:
        return None
    body_start = text.find("{", start)
    if body_start < 0:
        return None
    depth = 0
    for i in range(body_start, len(text)):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[body_start : i + 1]
    return None


class ErrorMessageOptionalPinTest(unittest.TestCase):
    """Pins the Error schema optionality of message and the ApiError default."""

    def test_error_schema_required_is_exactly_error(self):
        """(1) The Error schema required list equals exactly ["error"]."""
        schema = _error_schema()
        required = schema.get("required", [])
        self.assertEqual(
            required,
            ["error"],
            f"Error.schema required is {required!r}, expected ['error']",
        )

    def test_error_message_property_optional(self):
        """(2) message is still in properties with type string and an
        optional description containing the word "optional"."""
        schema = _error_schema()
        props = schema.get("properties", {})
        self.assertIn("message", props, "Error schema must still have message property")
        message_prop = props["message"]
        self.assertEqual(
            message_prop.get("type"),
            "string",
            "Error.message must remain type string",
        )
        desc = message_prop.get("description", "")
        self.assertIn(
            "optional",
            desc.lower(),
            f'Error.message description must contain "optional": {desc!r}',
        )

    def test_error_error_property(self):
        """(3) error is still in properties, type string."""
        schema = _error_schema()
        props = schema.get("properties", {})
        self.assertIn("error", props, "Error schema must have error property")
        error_prop = props["error"]
        self.assertEqual(
            error_prop.get("type"),
            "string",
            "Error.error must be type string",
        )

    def test_api_error_message_nullable_with_default(self):
        """(4) ApiError.kt declares `val message: String? = null`.

        Read by a plain regex on the file: the repo's Kotlin reader ignores
        defaults (reader at api_comparator.py:242), so a regex on the raw
        declaration is what pins `= null`. A regex on `val message` alone
        would also kill the dropped-default mutant: removing `= null` leaves
        `val message: String?` which no longer matches `String? = null`.
        """
        text = _read_text(API_ERROR_KT)
        self.assertIsNotNone(
            re.search(r"val message:\s*String\?\s*=\s*null", text),
            "ApiError.kt must declare val message: String? = null",
        )

    def test_api_error_error_non_null(self):
        """(5) ApiError.kt still declares `val error: String` non-null."""
        text = _read_text(API_ERROR_KT)
        self.assertIsNotNone(
            re.search(r"val error:\s*String\b(?!\s*\?)", text),
            "ApiError.kt must declare val error: String (non-null)",
        )

    def test_no_production_source_constructs_or_serialises_apierror(self):
        """(6) No production Kotlin or JS source under server/, android/ and
        shared/ (main trees, not tests) constructs or serialises ApiError,
        and none contains "message":null or "message": null.

        Command (written down):
        grep -rn 'ApiError(\\|ApiError.serializer\\|"message":null\\|"message": null'
          --include=*.kt --include=*.js server android shared
          | grep -v '/test/' | grep -v 'ApiError.kt:'
        """
        hits = []
        for directory in MAIN_SOURCE_DIRS:
            if not os.path.isdir(directory):
                continue
            for name in ("*.kt", "*.js"):
                for path in Path(directory).rglob(name):
                    rel = os.path.relpath(path, ROOT)
                    if "/test/" in rel or "/tests/" in rel:
                        continue
                    if rel.endswith("ApiError.kt"):
                        continue
                    text = _read_text(path)
                    for line_no, line in enumerate(text.split("\n"), 1):
                        if (
                            "ApiError(" in line
                            or "ApiError.serializer" in line
                            or '"message":null' in line
                            or '"message": null' in line
                            or '\\"message\\":null' in line
                            or '\\"message\\": null' in line
                        ):
                            hits.append(f"{rel}:{line_no}: {line.rstrip()}")
        self.assertEqual(
            hits,
            [],
            "production source must not construct/serialise ApiError or emit "
            f'"message":null: {hits}',
        )

    def test_whisper_respond_error_body_is_error_only(self):
        """(7) The whisper TranscriptionRoutes respondError body literal has
        only the `error` member (servers keep sending error only).

        Cut from TranscriptionRoutes.kt:199-201: the respondError function
        writes `{"error":...}` and nothing else.
        """
        text = _read_text(TRANSCRIPTION_ROUTES_KT)
        body = _find_respond_error(text)
        self.assertIsNotNone(body, "respondError function must exist in TranscriptionRoutes.kt")
        self.assertIn(
            '\\"error\\"',
            body,
            "respondError body literal must contain the error member",
        )
        self.assertNotIn(
            '\\"message\\"',
            body,
            "respondError body literal must not contain a message member",
        )


if __name__ == "__main__":
    unittest.main()
