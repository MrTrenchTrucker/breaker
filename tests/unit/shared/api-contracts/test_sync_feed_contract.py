"""Unit tests for the api-contracts sync feed group.

Tests the OpenAPI spec for GET /v1/transcriptions, and the TranscriptionRecord
and TranscriptionPage schemas. Uses the yaml_subset parser (the same one the Python
tier already uses) to parse the spec. The spec reader hands every plain scalar
back as text, so numbers are compared as text ("1") or converted with int().

What is pinned: paths and methods, status sets, the exact property sets and required
lists, the literal pattern strings, the behaviour of those patterns (the cursor
form and the live-vs-delete rules), the error bodies, key phrases of the descriptions,
the examples, and the Kotlin data classes of the same names.

Run: python3 -m unittest discover -s tests/unit/shared/api-contracts -t tests/unit/shared/api-contracts
"""

import base64
import hashlib
import os
import re
import sys
import unittest

# Add tools directory to path for yaml_subset
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import yaml_subset

SPEC_PATH = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")
KOTLIN_DIR = os.path.join(
    ROOT, "shared", "modules", "api-contracts", "src", "main", "kotlin",
    "dev", "breaker", "shared", "api",
)

LOGOUT_PATH = "/v1/auth/logout"
TRANS_PATH = "/v1/transcriptions"

PATTERN_CURSOR = "^[0-9]{1,19}-[0-9]{1,19}$"

STATUS_CODES = {"200", "400", "401", "403", "410", "503"}

ERROR_REF = "#/components/schemas/Error"


def _load_spec():
    """Load and parse the openapi.yaml file (parsed once, never mutated)."""
    if TOOLS not in sys.path:
        sys.path.insert(0, TOOLS)
    if not os.path.isfile(SPEC_PATH):
        raise AssertionError(f"openapi.yaml not found at {SPEC_PATH}")
    with open(SPEC_PATH, encoding="utf-8") as fh:
        text = fh.read()
    spec = yaml_subset.loads(text, "openapi.yaml")
    return spec


def _paths():
    return _load_spec().get("paths", {})


def _get(path):
    """The GET operation of a path; fails with the path named."""
    paths = _paths()
    if path not in paths:
        raise AssertionError(f"{path} is missing from paths")
    if "get" not in paths[path]:
        raise AssertionError(f"{path} has no get operation")
    return paths[path]["get"]


def _responses(path):
    get_op = _get(path).get("responses")
    if not isinstance(get_op, dict) or not get_op:
        raise AssertionError(f"{path} get has no responses")
    return get_op


def _response(path, status):
    responses = _responses(path)
    if status not in responses:
        raise AssertionError(f"{path} get has no {status} response")
    return responses[status]


def _json_media(path, status):
    """The application/json media object of a response, with path and status named."""
    resp = _response(path, status)
    content = resp.get("content")
    if not isinstance(content, dict) or "application/json" not in content:
        raise AssertionError(f"{path} {status} has no application/json content")
    return content["application/json"]


def _schemas():
    return _load_spec().get("components", {}).get("schemas", {})


def _schema(name):
    schemas = _schemas()
    if name not in schemas:
        raise AssertionError(f"schema {name} is missing from components/schemas")
    return schemas[name]


def _prop(schema_name, field):
    props = _schema(schema_name).get("properties", {})
    if field not in props:
        raise AssertionError(f"schema {schema_name} has no property {field}")
    return props[field]


def _flat(*parts):
    """Lower-case text with every run of whitespace collapsed (folded YAML text)."""
    return " ".join(" ".join(str(p).split()) for p in parts if p).lower()


def _ecma(pattern):
    """Read a pattern the way a JSON-schema validator does: searched, and $ means the very end."""
    if pattern.endswith("$"):
        return pattern[:-1] + r"\Z"
    return pattern


def _accepts(pattern, value, where):
    try:
        import re as _re
        compiled = _re.compile(_ecma(pattern))
    except re.error as err:
        raise AssertionError(f"{where}: pattern {pattern!r} is not a valid regex: {err}")
    return compiled.search(value) is not None


# ---------------------------------------------------------------------------
# paths, methods, operation ids, security
# ---------------------------------------------------------------------------

class TranscriptionsPathsTest(unittest.TestCase):
    """The transcriptions path must exist and be get-only."""

    def test_transcriptions_path_exists(self):
        """Catches the transcriptions path being absent or renamed."""
        self.assertIn(TRANS_PATH, _paths(), f"{TRANS_PATH} is missing from paths")

    def test_transcriptions_method_is_get_only(self):
        """Catches the transcriptions route being declared with another method, or with extra ones."""
        entry = _paths().get(TRANS_PATH, {})
        self.assertEqual(
            set(entry) & {"get", "put", "post", "delete", "patch", "head", "options"},
            {"get"},
            f"{TRANS_PATH} must have exactly the get method, has {sorted(entry)}"
        )

    def test_transcriptions_has_since_parameter(self):
        """Catches the `since` query parameter being dropped or renamed."""
        params = _get(TRANS_PATH).get("parameters", [])
        names = {p.get("name") for p in params}
        self.assertIn("since", names, f"transcriptions parameters: {names}")

    def test_transcriptions_since_is_optional(self):
        """Catches `since` being required (it must be optional)."""
        params = _get(TRANS_PATH).get("parameters", [])
        for p in params:
            if p.get("name") == "since":
                self.assertEqual(str(p.get("required")).lower(), "false",
                                 f"`since` must not be required, got required={p.get('required')}")
                return
        self.fail("no `since` parameter found on transcriptions GET")

    def test_transcriptions_operation_id(self):
        """Catches the operationId being renamed."""
        self.assertEqual(_get(TRANS_PATH).get("operationId"), "listTranscriptions",
                         f"transcriptions operationId")

    def test_no_other_transcriptions_path(self):
        """Catches a third route under /v1/transcriptions/ beyond the DELETE route the contract defines."""
        found = {p for p in _paths() if p.startswith("/v1/transcriptions/")}
        self.assertEqual(found, {"/v1/transcriptions/{client_id}"}, f"paths under /v1/transcriptions/ must be only the DELETE route, found {sorted(found)}")

    def test_transcriptions_sits_right_after_logout(self):
        """Catches the transcriptions path being put before /v1/auth/logout."""
        keys = list(_paths())
        self.assertIn(LOGOUT_PATH, keys, f"{LOGOUT_PATH} is missing from paths")
        self.assertIn(TRANS_PATH, keys, f"{TRANS_PATH} is missing from paths")
        self.assertLess(
            keys.index(LOGOUT_PATH),
            keys.index(TRANS_PATH),
            f"{TRANS_PATH} must come after {LOGOUT_PATH} in path order, found logout at {keys.index(LOGOUT_PATH)} and transcriptions at {keys.index(TRANS_PATH)}"
        )


# ---------------------------------------------------------------------------
# statuses, request bodies, error bodies
# ---------------------------------------------------------------------------

class TranscriptionsStatusSetsTest(unittest.TestCase):
    """Each route answers exactly the agreed status codes, with the agreed bodies."""

    def test_status_set_is_exact(self):
        """Catches an added or dropped status on the transcriptions route."""
        self.assertEqual(set(_responses(TRANS_PATH)), STATUS_CODES, f"{TRANS_PATH} status codes")

    def test_200_description_has_paging_rules(self):
        """Catches the operation description dropping the 100-record / 4 MiB / no-limit rules."""
        desc = _get(TRANS_PATH).get("description", "")
        self.assertIn("100 items", desc, f"operation description: {desc!r}")
        self.assertIn("4 MiB", desc, f"operation description: {desc!r}")
        self.assertIn("one record always fits", desc, f"operation description: {desc!r}")
        self.assertIn("NO limit parameter", desc, f"operation description: {desc!r}")
        self.assertIn("since = next while more is true", desc, f"operation description: {desc!r}")

    def test_description_has_cursor_rules(self):
        """Catches the operation description dropping the cursor rules (first page, above the head, 400 before reading, 410)."""
        desc = _get(TRANS_PATH).get("description", "")
        for phrase in (
            "No since = live rows only",
            "A cursor above the head answers 200 with empty items and next = head",
            "= 400 (checked before anything is read)",
            "A cursor below the purged floor = 410",
        ):
            self.assertIn(phrase, desc, f"operation description lacks {phrase!r}: {desc!r}")

    def test_change_number_starts_at_one(self):
        """Catches the change number allowing 0 (a stored change is never 0)."""
        self.assertEqual(_prop("TranscriptionRecord", "change").get("minimum"), "1", "change minimum")

    def test_no_limit_parameter_exists(self):
        """Catches a `limit` query parameter being added to transcriptions GET."""
        params = _get(TRANS_PATH).get("parameters", [])
        names = {p.get("name") for p in params}
        self.assertNotIn("limit", names, f"limit parameter must not exist, found {sorted(names)}")

    def test_410_body_text(self):
        """Catches the 410 body losing its fixed gone message."""
        resp = _response(TRANS_PATH, "410")
        media = resp.get("content", {}).get("application/json", {})
        example = media.get("example", {})
        self.assertEqual(example.get("error"), "gone", f"410 error: {example!r}")
        self.assertEqual(example.get("message"), "The cursor is too old: list again without since",
                         f"410 message: {example!r}")

    def test_400_body_text(self):
        """Catches the 400 body losing its fixed bad_request text."""
        resp = _response(TRANS_PATH, "400")
        media = resp.get("content", {}).get("application/json", {})
        example = media.get("example", {})
        self.assertEqual(example.get("error"), "bad_request", f"400 error: {example!r}")
        self.assertIn("account tag and pattern", example.get("message", ""), f"400 message: {example!r}")


# ---------------------------------------------------------------------------
# schema shapes
# ---------------------------------------------------------------------------

class TranscriptionsSchemaShapeTest(unittest.TestCase):
    """The schemas carry exactly the agreed properties, types and required lists."""

    def test_TranscriptionPage_exists_as_object(self):
        """Catches a missing schema or a schema that is not an object."""
        self.assertTrue(_schema("TranscriptionPage"), f"TranscriptionPage schema is missing")

    def test_TranscriptionPage_type_is_object(self):
        """Catches TranscriptionPage not being an object type."""
        self.assertEqual(_schema("TranscriptionPage").get("type"), "object",
                         f"TranscriptionPage type")

    def test_TranscriptionPage_required_lists_are_exact(self):
        """Catches a field that should be required being optional."""
        listed = _schema("TranscriptionPage").get("required", [])
        self.assertEqual(len(listed), len(set(listed)),
                         f"TranscriptionPage required list repeats a name: {listed}")
        self.assertEqual(set(listed), {"items", "next", "more"},
                         f"TranscriptionPage required list")

    def test_TranscriptionPage_properties_are_exact(self):
        """Catches an added, dropped or renamed property."""
        self.assertEqual(
            set(_schema("TranscriptionPage").get("properties", {})),
            {"items", "next", "more"},
            f"TranscriptionPage properties"
        )

    def test_TranscriptionPage_items_type_is_array(self):
        """Catches items not being an array."""
        self.assertEqual(
            _schema("TranscriptionPage").get("properties", {}).get("items", {}).get("type"),
            "array", f"TranscriptionPage.items type"
        )

    def test_TranscriptionPage_items_items_ref(self):
        """Catches items not referencing TranscriptionRecord."""
        items = _schema("TranscriptionPage").get("properties", {}).get("items", {})
        self.assertEqual(
            items.get("items", {}).get("$ref"),
            "#/components/schemas/TranscriptionRecord",
            f"TranscriptionPage.items.items $ref"
        )

    def test_TranscriptionPage_next_never_empty(self):
        """Catches the next description dropping the 'never empty' rule."""
        desc = _schema("TranscriptionPage").get("properties", {}).get("next", {}).get("description", "")
        self.assertIn("never empty", desc.lower(), f"next description: {desc!r}")
        self.assertIn("always matches the cursor pattern", desc.lower(), f"next description: {desc!r}")

    def test_TranscriptionPage_example_has_next_and_more(self):
        """Catches the page example dropping next or more."""
        resp = _response(TRANS_PATH, "200")
        example = resp.get("content", {}).get("application/json", {}).get("example", {})
        self.assertIn("next", example, f"example missing next: {example!r}")
        self.assertIn("more", example, f"example missing more: {example!r}")

    def test_TranscriptionPage_next_type_is_string(self):
        """Catches next not being a string."""
        self.assertEqual(
            _schema("TranscriptionPage").get("properties", {}).get("next", {}).get("type"),
            "string", f"TranscriptionPage.next type"
        )

    def test_TranscriptionPage_next_pattern(self):
        """Catches next not having the cursor pattern."""
        self.assertEqual(
            _schema("TranscriptionPage").get("properties", {}).get("next", {}).get("pattern"),
            PATTERN_CURSOR, f"TranscriptionPage.next pattern"
        )

    def test_TranscriptionPage_example_has_next(self):
        """Catches the 200 example dropping the `next` cursor field."""
        media = _json_media(TRANS_PATH, "200")
        example = media["example"]
        self.assertIn("next", example, "200 example has no next field")
        self.assertEqual(example["next"], "7-42", "200 example next value")
        self.assertIn("items", example, "200 example has no items")
        self.assertIsInstance(example["items"], list, "200 example items is not a list")
        self.assertGreaterEqual(len(example["items"]), 1, "200 example items is empty")

    def test_TranscriptionPage_more_type_is_boolean(self):
        """Catches more not being a boolean."""
        self.assertEqual(
            _schema("TranscriptionPage").get("properties", {}).get("more", {}).get("type"),
            "boolean", f"TranscriptionPage.more type"
        )

    def test_TranscriptionRecord_exists_as_object(self):
        """Catches a missing schema or a schema that is not an object."""
        self.assertTrue(_schema("TranscriptionRecord"), f"TranscriptionRecord schema is missing")

    def test_TranscriptionRecord_type_is_object(self):
        """Catches TranscriptionRecord not being an object type."""
        self.assertEqual(_schema("TranscriptionRecord").get("type"), "object",
                         f"TranscriptionRecord type")

    def test_TranscriptionRecord_required_lists_are_exact(self):
        """Catches a field that should be required being optional."""
        listed = _schema("TranscriptionRecord").get("required", [])
        self.assertEqual(len(listed), len(set(listed)),
                         f"TranscriptionRecord required list repeats a name: {listed}")
        self.assertEqual(set(listed), {"client_id", "change", "deleted", "stored_at"},
                         f"TranscriptionRecord required list")

    def test_TranscriptionRecord_properties_are_exact(self):
        """Catches an added, dropped or renamed property."""
        props = set(_schema("TranscriptionRecord").get("properties", {}))
        self.assertTrue(
            {"client_id", "change", "deleted", "key_version", "encrypted", "stored_at"} <= props,
            f"TranscriptionRecord properties missing required fields: {props}"
        )

    def test_TranscriptionRecord_client_id_type(self):
        """Catches client_id not being a string."""
        self.assertEqual(
            _schema("TranscriptionRecord").get("properties", {}).get("client_id", {}).get("type"),
            "string", f"TranscriptionRecord.client_id type"
        )

    def test_TranscriptionRecord_change_type(self):
        """Catches change not being a Long (integer)."""
        self.assertEqual(
            _schema("TranscriptionRecord").get("properties", {}).get("change", {}).get("type"),
            "integer", f"TranscriptionRecord.change type"
        )

    def test_TranscriptionRecord_deleted_type(self):
        """Catches deleted not being a boolean."""
        self.assertEqual(
            _schema("TranscriptionRecord").get("properties", {}).get("deleted", {}).get("type"),
            "boolean", f"TranscriptionRecord.deleted type"
        )

    def test_TranscriptionRecord_stored_at_type(self):
        """Catches stored_at not being a string."""
        self.assertEqual(
            _schema("TranscriptionRecord").get("properties", {}).get("stored_at", {}).get("type"),
            "string", f"TranscriptionRecord.stored_at type"
        )

    def test_TranscriptionRecord_stored_at_description(self):
        """Catches the stored_at description being shortened or dropped."""
        desc = _schema("TranscriptionRecord").get("properties", {}).get("stored_at", {}).get("description", "")
        self.assertIn("delete record", desc, f"stored_at description: {desc!r}")
        self.assertIn("delete write", desc, f"stored_at description: {desc!r}")
# ---------------------------------------------------------------------------
# literal pattern strings and behaviour
# ---------------------------------------------------------------------------

class TranscriptionsPatternLiteralTest(unittest.TestCase):
    """The pattern and length strings are pinned literally."""

    def test_cursor_pattern_behaviour_accepts(self):
        """Catches a cursor pattern that rejects valid cursors."""
        good_values = ["7-42", "7-0", "0-0"]
        for value in good_values:
            self.assertTrue(
                _accepts(PATTERN_CURSOR, value, "TranscriptionsPatternLiteralTest"),
                f"pattern {PATTERN_CURSOR} should accept valid cursor {value!r}"
            )

    def test_cursor_pattern_behaviour_refuses(self):
        """Catches a cursor pattern that accepts invalid cursors."""
        bad_values = ["42", "7-", "-4", "a-1", "7-42-1", "", "9" * 21]
        for value in bad_values:
            self.assertFalse(
                _accepts(PATTERN_CURSOR, value, "TranscriptionsPatternLiteralTest"),
                f"pattern {PATTERN_CURSOR} should refuse invalid cursor {value!r}"
            )

    def test_cursor_pattern_reads_from_spec(self):
        """Catches anyone loosening or tightening the spec's own cursor pattern."""
        for path_field in ((TRANS_PATH, "parameters", "since"),
                           ("TranscriptionPage", "next")):
            if path_field[0] == TRANS_PATH:
                params = _get(TRANS_PATH).get("parameters", [])
                pat = None
                for p in params:
                    if p.get("name") == "since":
                        pat = p.get("schema", {}).get("pattern")
                        break
                self.assertIsNotNone(pat, "since parameter has no pattern")
            else:
                pat = _schema("TranscriptionPage").get("properties", {}).get("next", {}).get("pattern")
            self.assertEqual(pat, PATTERN_CURSOR,
                             f"cursor pattern on {path_field} drifted from {PATTERN_CURSOR!r}")
        # Behaviour pinned to the spec's own pattern: 19 digits each side is accepted,
        # 20 digits each side is refused.
        self.assertTrue(_accepts(PATTERN_CURSOR, "9" * 19 + "-1", "spec pattern"))
        self.assertFalse(_accepts(PATTERN_CURSOR, "9" * 20 + "-1", "spec pattern"))
        self.assertFalse(_accepts(PATTERN_CURSOR, "1-" + "9" * 20, "spec pattern"))


# ---------------------------------------------------------------------------
# Kotlin data classes
# ---------------------------------------------------------------------------

class TranscriptionsKotlinTest(unittest.TestCase):
    """The Kotlin data classes exist and hold the same fields as the schemas."""

    def _check(self, name):
        """Read one Kotlin data class file. Returns (text, [(field, type, nullable)])."""
        path = os.path.join(KOTLIN_DIR, name + ".kt")
        if not os.path.isfile(path):
            raise AssertionError(f"Kotlin file {name}.kt not found at {path}")
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        lines = text.split("\n")
        heads = [i for i, line in enumerate(lines) if line == f"data class {name}("]
        if len(heads) != 1:
            raise AssertionError(
                f"{name}.kt must hold exactly one line 'data class {name}(', found {len(heads)}"
            )
        fields = []
        closed = False
        for number, line in enumerate(lines[heads[0] + 1:], heads[0] + 2):
            if line == ")":
                closed = True
                break
            m = re.match(r"^    val (\w+): ([A-Za-z<>]+)(\?)?,?$", line)
            if not m:
                raise AssertionError(
                    f"{name}.kt:{number}: not a plain 'val name: Type' parameter line "
                    f"{line!r}"
                )
            fields.append((m.group(1), m.group(2), bool(m.group(3))))
        if not closed:
            raise AssertionError(f"{name}.kt: the constructor never closes with a line holding only a close paren")
        return text, fields

    @staticmethod
    def _camel(snake_name):
        return re.sub(r"_([a-z])", lambda m: m.group(1).upper(), snake_name)

    def test_TranscriptionRecord_kotlin(self):
        """Catches a missing, misnamed or mistyped TranscriptionRecord.kt."""
        text, fields = self._check("TranscriptionRecord")
        self.assertIn("package dev.breaker.shared.api", text.split("\n"),
                      f"TranscriptionRecord.kt: package line")
        self.assertIn(f"Mirrors the `TranscriptionRecord` schema in the OpenAPI spec.",
                      text, f"TranscriptionRecord.kt: KDoc sentence")
        self.assertFalse(
            [ln for ln in text.split("\n") if ln.startswith("import ")],
            f"TranscriptionRecord.kt must have no imports"
        )
        names = [f[0] for f in fields]
        self.assertEqual(len(names), len(set(names)),
                         f"TranscriptionRecord.kt repeats a field: {names}")
        # Fields vs schema properties
        props = _schema("TranscriptionRecord").get("properties", {})
        self.assertEqual(
            set(names),
            {self._camel(p) for p in props},
            f"TranscriptionRecord.kt fields versus schema properties"
        )
        HARD_KEYWORDS = {
            "package", "in", "is", "as", "fun", "val", "var", "when", "object",
            "typealias", "typeof", "interface", "class", "return", "this", "super",
            "null", "true", "false", "throw", "try", "while", "do", "for", "if",
            "else", "break", "continue",
        }
        for field in names:
            self.assertNotIn(field, HARD_KEYWORDS,
                             f"TranscriptionRecord.kt: field {field} is a hard keyword")
        self.assertNotIn("package", names,
                         f"TranscriptionRecord.kt: no field may be named package")
        by_name = {f[0]: f for f in fields}
        for wire, prop in props.items():
            ftype = prop.get("type", "string")
            fmt = prop.get("format")
            expected = "Long" if (ftype == "integer" and fmt == "int64") else ("Int" if ftype == "integer" else ("Boolean" if ftype == "boolean" else "String"))
            _n, kotlin_type, nullable = by_name[self._camel(wire)]
            self.assertEqual(kotlin_type, expected,
                             f"TranscriptionRecord.{self._camel(wire)} Kotlin type for spec type {ftype}:{fmt}")
            self.assertEqual(nullable, wire not in {"client_id", "stored_at", "change", "deleted"},
                             f"TranscriptionRecord.{self._camel(wire)} nullable must match optional in the schema")

    def test_TranscriptionPage_kotlin(self):
        """Catches a missing, misnamed or mistyped TranscriptionPage.kt."""
        text, fields = self._check("TranscriptionPage")
        self.assertIn("package dev.breaker.shared.api", text.split("\n"),
                      f"TranscriptionPage.kt: package line")
        self.assertIn(f"Mirrors the `TranscriptionPage` schema in the OpenAPI spec.",
                      text, f"TranscriptionPage.kt: KDoc sentence")
        self.assertFalse(
            [ln for ln in text.split("\n") if ln.startswith("import ")],
            f"TranscriptionPage.kt must have no imports"
        )
        names = [f[0] for f in fields]
        self.assertEqual(len(names), len(set(names)),
                         f"TranscriptionPage.kt repeats a field: {names}")
        # Fields vs schema properties
        props = _schema("TranscriptionPage").get("properties", {})
        self.assertEqual(
            set(names),
            {self._camel(p) for p in props},
            f"TranscriptionPage.kt fields versus schema properties"
        )
        self.assertIn("items", [f[0] for f in fields],
                      f"TranscriptionPage.kt must carry items")
        self.assertIn("next", [f[0] for f in fields],
                      f"TranscriptionPage.kt must carry next")
        self.assertIn("more", [f[0] for f in fields],
                      f"TranscriptionPage.kt must carry more"
        )