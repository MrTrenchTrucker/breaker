"""Unit tests for the api-contracts sign-in hand-off group.

Tests the OpenAPI spec for the two hand-off routes, POST /v1/auth/handoff/start
and POST /v1/auth/handoff/exchange, and the four schemas they use
(HandoffStartRequest, HandoffStartResponse, HandoffExchangeRequest,
HandoffExchangeResponse). Uses the yaml_subset parser (the same one the Python
tier already uses) to parse the spec. The spec reader hands every plain scalar
back as text, so numbers are compared as text ("152") or converted with int().

What is pinned: paths and methods, operation ids, security per route, the exact
status sets, the exact property sets and required lists, the literal pattern
strings, the behaviour of those patterns (the 43-character URL-safe form and the
standard base64 of exactly 113 bytes), the device_name bounds, the one refusal of
the exchange route, the error bodies, key phrases of the descriptions, the
examples, the absence of any key field in the exchange answer, and the four
Kotlin data classes of the same names.

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
START_PATH = "/v1/auth/handoff/start"
EXCHANGE_PATH = "/v1/auth/handoff/exchange"
ERROR_REF = "#/components/schemas/Error"

PATTERN_URL43 = "^[A-Za-z0-9_-]{43}$"
PATTERN_SEALED = "^[A-Za-z0-9+/]{151}=$"

START_STATUSES = {"201", "400", "401", "403", "413"}
EXCHANGE_STATUSES = {"200", "400", "401", "413"}

BAD_BODY = ("bad_request", "Invalid request body")
TOO_BIG = ("payload_too_large", "Request body exceeds maximum allowed size")
START_ERRORS = {
    "400": BAD_BODY,
    "401": ("unauthorized", "Invalid or missing token"),
    "403": ("forbidden", "User session token required; an agent token is refused"),
    "413": TOO_BIG,
}
EXCHANGE_REFUSAL = ("unauthorized", "Invalid sign-in code or secret")
EXCHANGE_ERRORS = {
    "400": BAD_BODY,
    "401": EXCHANGE_REFUSAL,
    "413": TOO_BIG,
}

# schema name -> (properties, required, request-or-response label)
SCHEMA_SHAPES = {
    "HandoffStartRequest": ({"challenge", "sealed_package"}, {"challenge", "sealed_package"}),
    "HandoffStartResponse": ({"code"}, {"code"}),
    "HandoffExchangeRequest": ({"code", "secret", "device_name"}, {"code", "secret"}),
    "HandoffExchangeResponse": (
        {"token", "role", "key_version", "sealed_package", "username", "account_id"},
        {"token", "role", "key_version", "sealed_package", "username", "account_id"},
    ),
}

# (schema, field) -> (type, format)
FIELD_TYPES = {
    ("HandoffStartRequest", "challenge"): ("string", None),
    ("HandoffStartRequest", "sealed_package"): ("string", None),
    ("HandoffStartResponse", "code"): ("string", None),
    ("HandoffExchangeRequest", "code"): ("string", None),
    ("HandoffExchangeRequest", "secret"): ("string", None),
    ("HandoffExchangeRequest", "device_name"): ("string", None),
    ("HandoffExchangeResponse", "token"): ("string", None),
    ("HandoffExchangeResponse", "role"): ("string", None),
    ("HandoffExchangeResponse", "key_version"): ("integer", None),
    ("HandoffExchangeResponse", "sealed_package"): ("string", None),
    ("HandoffExchangeResponse", "username"): ("string", None),
    ("HandoffExchangeResponse", "account_id"): ("integer", "int64"),
}

URL43_FIELDS = [
    ("HandoffStartRequest", "challenge"),
    ("HandoffStartResponse", "code"),
    ("HandoffExchangeRequest", "code"),
    ("HandoffExchangeRequest", "secret"),
    ("HandoffExchangeResponse", "token"),
]
SEALED_FIELDS = [
    ("HandoffStartRequest", "sealed_package"),
    ("HandoffExchangeResponse", "sealed_package"),
]

# Names no key field of the exchange answer may carry (it holds no key material).
KEY_NAME_PARTS = ("dek", "privkey", "pubkey", "kek", "password", "verifier", "plaintext", "salt")

HARD_KEYWORDS = {
    "package", "in", "is", "as", "fun", "val", "var", "when", "object",
    "typealias", "typeof", "interface", "class", "return", "this", "super",
    "null", "true", "false", "throw", "try", "while", "do", "for", "if",
    "else", "break", "continue",
}

_SPEC_CACHE = []


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------

def _load_spec():
    """Load and parse the openapi.yaml file (parsed once, never mutated)."""
    if _SPEC_CACHE:
        return _SPEC_CACHE[0]
    if not os.path.isfile(SPEC_PATH):
        raise AssertionError(f"openapi.yaml not found at {SPEC_PATH}")
    with open(SPEC_PATH, encoding="utf-8") as fh:
        text = fh.read()
    spec = yaml_subset.loads(text, "openapi.yaml")
    _SPEC_CACHE.append(spec)
    return spec


def _paths():
    return _load_spec().get("paths", {})


def _op(path):
    """The POST operation of a path; fails with the path named."""
    paths = _paths()
    if path not in paths:
        raise AssertionError(f"{path} is missing from paths")
    if "post" not in paths[path]:
        raise AssertionError(f"{path} has no post operation")
    return paths[path]["post"]


def _responses(path):
    responses = _op(path).get("responses")
    if not isinstance(responses, dict) or not responses:
        raise AssertionError(f"{path} post has no responses")
    return responses


def _response(path, status):
    responses = _responses(path)
    if status not in responses:
        raise AssertionError(f"{path} post has no {status} response")
    return responses[status]


def _json_media(path, status):
    """The application/json media object of a response, with path and status named."""
    resp = _response(path, status)
    content = resp.get("content")
    if not isinstance(content, dict) or "application/json" not in content:
        raise AssertionError(f"{path} {status} has no application/json content")
    return content["application/json"]


def _request_media(path):
    body = _op(path).get("requestBody")
    if not isinstance(body, dict):
        raise AssertionError(f"{path} post has no requestBody")
    content = body.get("content")
    if not isinstance(content, dict) or "application/json" not in content:
        raise AssertionError(f"{path} requestBody has no application/json content")
    return body, content["application/json"]


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


def _has_phrase(text, phrase):
    """True when phrase is in text, bounded by non-alphanumerics where it starts or ends with one."""
    start = r"(?<![a-z0-9])" if phrase[0].isalnum() else ""
    end = r"(?![a-z0-9])" if phrase[-1].isalnum() else ""
    return re.search(start + re.escape(phrase) + end, text) is not None


def _ecma(pattern):
    """Read a pattern the way a JSON-schema validator does: searched, and $ means the very end."""
    if pattern.endswith("$"):
        return pattern[:-1] + r"\Z"
    return pattern


def _accepts(pattern, value, where):
    try:
        compiled = re.compile(_ecma(pattern))
    except re.error as err:
        raise AssertionError(f"{where}: pattern {pattern!r} is not a valid regex: {err}")
    return compiled.search(value) is not None


def _b64(raw):
    return base64.b64encode(raw).decode("ascii")


def _url_safe_digest():
    digest = hashlib.sha256(b"example secret text").digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def _good_url43():
    values = ["A" * 43, "-" * 43, "_" * 43, "a-Z_9" * 8 + "abc", _url_safe_digest()]
    for value in values:
        assert len(value) == 43, "internal: a good 43-character sample has the wrong length"
    return values


def _bad_url43():
    return [
        "",
        "A" * 42,
        "A" * 44,
        "A" * 42 + "+",
        "A" * 42 + "/",
        "A" * 42 + "=",
        "A" * 41 + "==",
        "A" * 43 + "\n",
        "A" * 42 + " ",
        "A" * 42 + chr(233),
        "A" * 21 + "+" + "A" * 21,
    ]


def _good_sealed():
    values = [
        _b64(bytes(113)),
        _b64(b"\xff" * 113),
        _b64(bytes(range(113))),
        _b64(b"\xfb\xef\xbe" * 37 + b"\xfb\xff"),
        _b64(bytes(65) + b"\x01" * 48),
    ]
    for value in values:
        assert len(value) == 152 and value.endswith("=") and not value.endswith("=="), (
            "internal: a good sealed sample is not 152 characters with one padding character")
    return values


def _bad_sealed():
    good = _b64(bytes(113))
    return [
        _b64(bytes(112)),
        _b64(bytes(114)),
        _b64(bytes(111)),
        _b64(bytes(115)),
        _b64(bytes(64)),
        good[:-1],
        good + "A",
        good[:-1] + "==",
        "-" + good[1:],
        "_" + good[1:],
        good[:76] + "\n" + good[76:],
        good[:10] + "=" + good[11:],
        good + "\n",
        "",
    ]


def _check_url43(tc, pattern, where):
    """Assert the pattern accepts good 43-character URL-safe text and refuses the bad samples."""
    good = _good_url43()
    bad = _bad_url43()
    tc.assertTrue(good and bad, f"{where}: internal: empty sample set")
    for value in good:
        tc.assertTrue(_accepts(pattern, value, where),
                      f"{where}: pattern {pattern!r} refuses a valid 43-character URL-safe value {value!r}")
    for value in bad:
        tc.assertFalse(_accepts(pattern, value, where),
                       f"{where}: pattern {pattern!r} accepts {value!r}, which is not exactly 43 URL-safe characters")


def _check_sealed(tc, pattern, where):
    """Assert the pattern accepts standard base64 of 113 bytes and refuses 112, 114 and the other bad samples."""
    good = _good_sealed()
    bad = _bad_sealed()
    tc.assertTrue(good and bad, f"{where}: internal: empty sample set")
    for value in good:
        tc.assertTrue(_accepts(pattern, value, where),
                      f"{where}: pattern {pattern!r} refuses the base64 of 113 bytes {value!r}")
    for value in bad:
        tc.assertFalse(_accepts(pattern, value, where),
                       f"{where}: pattern {pattern!r} accepts {value!r}, which is not the base64 of exactly 113 bytes")


def _camel(snake_name):
    return re.sub(r"_([a-z])", lambda m: m.group(1).upper(), snake_name)


def _read_kotlin(class_name):
    """Read one Kotlin data class file. Returns (text, [(field, type, nullable)])."""
    path = os.path.join(KOTLIN_DIR, class_name + ".kt")
    if not os.path.isfile(path):
        raise AssertionError(f"Kotlin file {class_name}.kt not found at {path}")
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    lines = text.split("\n")
    heads = [i for i, line in enumerate(lines) if line == f"data class {class_name}("]
    if len(heads) != 1:
        raise AssertionError(f"{class_name}.kt must hold exactly one line 'data class {class_name}(', found {len(heads)}")
    fields = []
    closed = False
    for number, line in enumerate(lines[heads[0] + 1:], heads[0] + 2):
        if line == ")":
            closed = True
            break
        m = re.match(r"^    val (\w+): ([A-Za-z]+)(\?)?,?$", line)
        if not m:
            raise AssertionError(
                f"{class_name}.kt:{number}: not a plain 'val name: Type' parameter line "
                f"(no default value, one per line): {line!r}")
        fields.append((m.group(1), m.group(2), bool(m.group(3))))
    if not closed:
        raise AssertionError(f"{class_name}.kt: the constructor never closes with a line holding only a close paren")
    return text, fields


# ---------------------------------------------------------------------------
# paths, methods, operation ids, security
# ---------------------------------------------------------------------------

class HandoffPathsTest(unittest.TestCase):
    """Both hand-off routes must exist in the spec with the correct method."""

    def test_start_path_exists(self):
        """Catches the start path being absent or renamed."""
        self.assertIn(START_PATH, _paths(), f"{START_PATH} is missing from paths")

    def test_exchange_path_exists(self):
        """Catches the exchange path being absent or renamed."""
        self.assertIn(EXCHANGE_PATH, _paths(), f"{EXCHANGE_PATH} is missing from paths")

    def test_start_method_is_post_only(self):
        """Catches the start route being declared with another method, or with extra ones."""
        entry = _paths().get(START_PATH, {})
        self.assertEqual(set(entry) & {"get", "put", "post", "delete", "patch", "head", "options"}, {"post"},
                         f"{START_PATH} must have exactly the post method, has {sorted(entry)}")

    def test_exchange_method_is_post_only(self):
        """Catches the exchange route being declared with another method, or with extra ones."""
        entry = _paths().get(EXCHANGE_PATH, {})
        self.assertEqual(set(entry) & {"get", "put", "post", "delete", "patch", "head", "options"}, {"post"},
                         f"{EXCHANGE_PATH} must have exactly the post method, has {sorted(entry)}")

    def test_no_other_handoff_path(self):
        """Catches a third route under /v1/auth/handoff/ (a route the contract does not define)."""
        found = {p for p in _paths() if p.startswith("/v1/auth/handoff")}
        self.assertEqual(found, {START_PATH, EXCHANGE_PATH},
                         f"paths under /v1/auth/handoff must be exactly the two agreed routes, found {sorted(found)}")

    def test_paths_sit_right_after_logout(self):
        """Catches the two paths being put somewhere other than after /v1/auth/logout, start first."""
        keys = list(_paths())
        self.assertIn(LOGOUT_PATH, keys, f"{LOGOUT_PATH} is missing from paths")
        at = keys.index(LOGOUT_PATH)
        self.assertEqual(keys[at + 1:at + 3], [START_PATH, EXCHANGE_PATH],
                         f"the two hand-off paths must follow {LOGOUT_PATH} in this order, found {keys[at + 1:at + 3]}")


class HandoffOperationIdsTest(unittest.TestCase):
    """Operation ids must be the agreed names and unique in the whole spec."""

    def test_start_operation_id(self):
        """Catches a wrong or missing operationId on the start route."""
        self.assertEqual(_op(START_PATH).get("operationId"), "handoffStart", f"{START_PATH} operationId")

    def test_exchange_operation_id(self):
        """Catches a wrong or missing operationId on the exchange route."""
        self.assertEqual(_op(EXCHANGE_PATH).get("operationId"), "handoffExchange", f"{EXCHANGE_PATH} operationId")

    def test_operation_ids_unique_in_spec(self):
        """Catches a copied operationId that collides with an existing operation."""
        seen = {}
        for path, entry in _paths().items():
            for method, op in entry.items():
                if isinstance(op, dict) and "operationId" in op:
                    seen.setdefault(op["operationId"], []).append(f"{method} {path}")
        self.assertTrue(seen, "no operationId found anywhere in the spec")
        self.assertIn("handoffStart", seen, "handoffStart is not an operationId anywhere in the spec")
        self.assertIn("handoffExchange", seen, "handoffExchange is not an operationId anywhere in the spec")
        for op_id, where in seen.items():
            self.assertEqual(len(where), 1, f"operationId {op_id} is used more than once: {where}")


class HandoffSecurityTest(unittest.TestCase):
    """Start takes the session bearer token; exchange takes no token."""

    def test_start_requires_bearer_auth(self):
        """Catches the start route losing its bearer requirement."""
        self.assertEqual(_op(START_PATH).get("security"), [{"bearerAuth": []}], f"{START_PATH} security")

    def test_exchange_has_explicit_empty_security(self):
        """Catches the exchange route inheriting or gaining a bearer requirement (security must be present and empty)."""
        op = _op(EXCHANGE_PATH)
        self.assertIn("security", op, f"{EXCHANGE_PATH} must say security: [] explicitly")
        self.assertEqual(op["security"], [], f"{EXCHANGE_PATH} security must be empty (the code and the secret are the credential)")

    def test_start_403_names_the_agent_token_refusal(self):
        """Catches a start 403 that does not say an agent token is refused (same body as /v1/sync)."""
        example = _json_media(START_PATH, "403").get("example")
        self.assertIsInstance(example, dict, f"{START_PATH} 403 has no example")
        self.assertEqual(example.get("error"), "forbidden", f"{START_PATH} 403 example error")
        self.assertIn("agent token", example.get("message", "").lower(), f"{START_PATH} 403 example message")


# ---------------------------------------------------------------------------
# statuses, request bodies, error bodies
# ---------------------------------------------------------------------------

class HandoffStatusSetsTest(unittest.TestCase):
    """Each route answers exactly the agreed status codes, with the agreed bodies."""

    def test_start_status_set_is_exact(self):
        """Catches an added or dropped status on the start route."""
        self.assertEqual(set(_responses(START_PATH)), START_STATUSES, f"{START_PATH} status codes")

    def test_exchange_status_set_is_exact(self):
        """Catches an added or dropped status on the exchange route."""
        self.assertEqual(set(_responses(EXCHANGE_PATH)), EXCHANGE_STATUSES, f"{EXCHANGE_PATH} status codes")

    def test_start_success_body_is_the_start_response(self):
        """Catches the 201 of start pointing at the wrong schema."""
        schema = _json_media(START_PATH, "201").get("schema", {})
        self.assertEqual(schema.get("$ref"), "#/components/schemas/HandoffStartResponse", f"{START_PATH} 201 schema")

    def test_exchange_success_body_is_the_exchange_response(self):
        """Catches the 200 of exchange pointing at the wrong schema."""
        schema = _json_media(EXCHANGE_PATH, "200").get("schema", {})
        self.assertEqual(schema.get("$ref"), "#/components/schemas/HandoffExchangeResponse", f"{EXCHANGE_PATH} 200 schema")

    def test_start_request_body_is_required_and_typed(self):
        """Catches a start request body that is optional or points at the wrong schema."""
        body, media = _request_media(START_PATH)
        self.assertEqual(str(body.get("required")).lower(), "true", f"{START_PATH} requestBody must be required")
        self.assertEqual(media.get("schema", {}).get("$ref"), "#/components/schemas/HandoffStartRequest",
                         f"{START_PATH} request schema")

    def test_exchange_request_body_is_required_and_typed(self):
        """Catches an exchange request body that is optional or points at the wrong schema."""
        body, media = _request_media(EXCHANGE_PATH)
        self.assertEqual(str(body.get("required")).lower(), "true", f"{EXCHANGE_PATH} requestBody must be required")
        self.assertEqual(media.get("schema", {}).get("$ref"), "#/components/schemas/HandoffExchangeRequest",
                         f"{EXCHANGE_PATH} request schema")

    def test_start_error_bodies_reference_error_and_carry_the_ruled_text(self):
        """Catches a start error body that is not the Error schema or has a different code or message."""
        self.assertTrue(START_ERRORS, "internal: empty error table")
        for status, (code, message) in START_ERRORS.items():
            media = _json_media(START_PATH, status)
            self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, f"{START_PATH} {status} schema")
            example = media.get("example")
            self.assertIsInstance(example, dict, f"{START_PATH} {status} has no example")
            self.assertEqual(example.get("error"), code, f"{START_PATH} {status} example error")
            self.assertEqual(example.get("message"), message, f"{START_PATH} {status} example message")

    def test_exchange_error_bodies_reference_error_and_carry_the_ruled_text(self):
        """Catches an exchange error body that is not the Error schema or has a different code or message."""
        self.assertTrue(EXCHANGE_ERRORS, "internal: empty error table")
        for status, (code, message) in EXCHANGE_ERRORS.items():
            media = _json_media(EXCHANGE_PATH, status)
            self.assertEqual(media.get("schema", {}).get("$ref"), ERROR_REF, f"{EXCHANGE_PATH} {status} schema")
            example = media.get("example")
            self.assertIsInstance(example, dict, f"{EXCHANGE_PATH} {status} has no example")
            self.assertEqual(example.get("error"), code, f"{EXCHANGE_PATH} {status} example error")
            self.assertEqual(example.get("message"), message, f"{EXCHANGE_PATH} {status} example message")


# ---------------------------------------------------------------------------
# the ONE refusal of the exchange route
# ---------------------------------------------------------------------------

class HandoffOneRefusalTest(unittest.TestCase):
    """The exchange route refuses every failed sign-in code with one status and one body."""

    def test_exchange_has_one_401_entry(self):
        """Catches the refusal being split or removed: exactly one 401 entry must exist."""
        responses = _responses(EXCHANGE_PATH)
        self.assertEqual([s for s in responses if s == "401"], ["401"], f"{EXCHANGE_PATH} must have one 401 entry")

    def test_exchange_has_no_status_that_tells_cases_apart(self):
        """Catches a 403, 404, 409, 410, 429 or 503 on the exchange route (each would name a case)."""
        responses = _responses(EXCHANGE_PATH)
        self.assertTrue(responses, f"{EXCHANGE_PATH} has no responses")
        for status in ("403", "404", "409", "410", "429", "503"):
            self.assertNotIn(status, responses, f"{EXCHANGE_PATH} must not have a {status} response")

    def test_exchange_error_statuses_are_only_400_401_413(self):
        """Catches any other 4xx or 5xx entry on the exchange route."""
        errors = {s for s in _responses(EXCHANGE_PATH) if s[:1] in ("4", "5")}
        self.assertEqual(errors, {"400", "401", "413"}, f"{EXCHANGE_PATH} error statuses")

    def test_401_has_one_fixed_message(self):
        """Catches a changed 401 message, or a 401 with several examples (more than one message)."""
        media = _json_media(EXCHANGE_PATH, "401")
        self.assertNotIn("examples", media, f"{EXCHANGE_PATH} 401 must have one example, not an examples map")
        example = media.get("example")
        self.assertIsInstance(example, dict, f"{EXCHANGE_PATH} 401 has no example")
        self.assertEqual((example.get("error"), example.get("message")), EXCHANGE_REFUSAL,
                         f"{EXCHANGE_PATH} 401 example")

    def test_401_message_differs_from_the_start_401(self):
        """Catches the exchange refusal copying the start route's token message."""
        start = _json_media(START_PATH, "401").get("example", {}).get("message")
        exchange = _json_media(EXCHANGE_PATH, "401").get("example", {}).get("message")
        self.assertTrue(start and exchange, "a 401 example message is missing")
        self.assertNotEqual(start, exchange, f"{EXCHANGE_PATH} 401 message must not be the token message of {START_PATH}")

    def test_400_message_says_nothing_about_the_code(self):
        """Catches a 400 message that names the code, the secret or a code state (it would tell cases apart)."""
        message = _json_media(EXCHANGE_PATH, "400").get("example", {}).get("message", "")
        self.assertTrue(message, f"{EXCHANGE_PATH} 400 example has no message")
        for word in ("code", "secret", "expired", "used", "unknown"):
            self.assertFalse(_has_phrase(message.lower(), word),
                             f"{EXCHANGE_PATH} 400 message {message!r} must not mention {word!r}")

    def test_401_description_says_no_other_status_tells_the_cases_apart(self):
        """Catches the text that promises the one answer being dropped from the exchange description."""
        resp = _response(EXCHANGE_PATH, "401")
        text = _flat(_op(EXCHANGE_PATH).get("description"), resp.get("description"))
        self.assertTrue(text, f"{EXCHANGE_PATH} has no description text")
        for phrase in ("401", "unknown", "expired"):
            self.assertTrue(_has_phrase(text, phrase),
                            f"{EXCHANGE_PATH} 401 or operation description must say {phrase!r}")
        op_text = _flat(_op(EXCHANGE_PATH).get("description"))
        self.assertTrue(_has_phrase(op_text, "no other status"),
                        f"{EXCHANGE_PATH} operation description alone must say 'no other status'")
        resp_text = _flat(resp.get("description"))
        self.assertTrue(_has_phrase(resp_text, "no other status"),
                        f"{EXCHANGE_PATH} 401 response description alone must say 'no other status'")

    def test_400_description_says_it_does_not_use_up_the_code(self):
        """Catches the rule that a 400 leaves the code usable being dropped from either place, each read alone."""
        resp = _response(EXCHANGE_PATH, "400")
        op_text = _flat(_op(EXCHANGE_PATH).get("description"))
        resp_text = _flat(resp.get("description"))
        self.assertTrue(_has_phrase(op_text, "neither does a 400"),
                        f"{EXCHANGE_PATH} operation description alone must say 'neither does a 400'")
        self.assertTrue(_has_phrase(resp_text, "not use up"),
                        f"{EXCHANGE_PATH} 400 response description alone must say 'not use up'")
        for phrase in ("device_name is present", "is not null", "1 to 64 characters"):
            self.assertTrue(_has_phrase(resp_text, phrase),
                            f"{EXCHANGE_PATH} 400 response description alone must say {phrase!r}")


# ---------------------------------------------------------------------------
# schema shapes
# ---------------------------------------------------------------------------

class HandoffSchemaShapeTest(unittest.TestCase):
    """The four schemas carry exactly the agreed properties, types and required lists."""

    def test_all_four_schemas_exist_as_objects(self):
        """Catches a missing schema or a schema that is not an object."""
        self.assertTrue(SCHEMA_SHAPES, "internal: empty schema table")
        for name in SCHEMA_SHAPES:
            self.assertEqual(_schema(name).get("type"), "object", f"schema {name} type")

    def test_property_sets_are_exact(self):
        """Catches an added, dropped or renamed property (including the wire name sealed_package)."""
        for name, (props, _required) in SCHEMA_SHAPES.items():
            self.assertTrue(props, f"internal: no expected properties for {name}")
            self.assertEqual(set(_schema(name).get("properties", {})), props, f"schema {name} properties")

    def test_required_lists_are_exact(self):
        """Catches a field that should be required being optional (or device_name becoming required)."""
        for name, (_props, required) in SCHEMA_SHAPES.items():
            self.assertTrue(required, f"internal: no expected required fields for {name}")
            listed = _schema(name).get("required", [])
            self.assertEqual(len(listed), len(set(listed)), f"schema {name} required list repeats a name: {listed}")
            self.assertEqual(set(listed), required, f"schema {name} required list")

    def test_device_name_is_the_only_optional_field(self):
        """Catches any other field becoming optional."""
        optional = {}
        for name, (props, required) in SCHEMA_SHAPES.items():
            optional[name] = set(_schema(name).get("properties", {})) - set(_schema(name).get("required", []))
            self.assertTrue(props, f"internal: no expected properties for {name}")
        self.assertEqual(optional["HandoffExchangeRequest"], {"device_name"}, "optional fields of HandoffExchangeRequest")
        for name in ("HandoffStartRequest", "HandoffStartResponse", "HandoffExchangeResponse"):
            self.assertEqual(optional[name], set(), f"schema {name} must have no optional field")

    def test_field_types_and_formats(self):
        """Catches a wrong type or format (for example key_version as a string, or account_id without int64)."""
        self.assertTrue(FIELD_TYPES, "internal: empty field table")
        for (schema, field), (ftype, fmt) in FIELD_TYPES.items():
            prop = _prop(schema, field)
            self.assertEqual(prop.get("type"), ftype, f"{schema}.{field} type")
            self.assertEqual(prop.get("format"), fmt, f"{schema}.{field} format")

    def test_only_device_name_is_nullable(self):
        """Catches device_name losing nullable true (null means no name), or any other field gaining a nullable flag."""
        checked = 0
        nullable_found = []
        for name, (props, _required) in SCHEMA_SHAPES.items():
            for field in props:
                value = str(_prop(name, field).get("nullable", "false")).strip().lower()
                if value == "true":
                    nullable_found.append(f"{name}.{field}")
                checked += 1
        self.assertEqual(checked, 12, "the four schemas must hold twelve fields in all")
        self.assertEqual(nullable_found, ["HandoffExchangeRequest.device_name"],
                         f"exactly HandoffExchangeRequest.device_name must be nullable, found {nullable_found}")

    def test_key_version_has_floor_of_zero_and_no_int64(self):
        """Catches a missing floor on key_version, or int64 on it (the Kotlin side is Int)."""
        prop = _prop("HandoffExchangeResponse", "key_version")
        self.assertEqual(prop.get("minimum"), "0", "HandoffExchangeResponse.key_version minimum")
        self.assertNotEqual(prop.get("format"), "int64", "HandoffExchangeResponse.key_version must not be int64")

    def test_role_allows_exactly_admin_and_user(self):
        """Catches a role that does not name admin and user (as enum values or in the description)."""
        prop = _prop("HandoffExchangeResponse", "role")
        enum = prop.get("enum")
        if enum is not None:
            self.assertEqual(set(enum), {"admin", "user"}, "HandoffExchangeResponse.role enum")
        else:
            text = _flat(prop.get("description"))
            self.assertTrue(_has_phrase(text, "admin") and _has_phrase(text, "user"),
                            "HandoffExchangeResponse.role must name admin and user in its description")

    def test_exchange_answer_carries_no_key_field(self):
        """Catches a key, password or verifier field creeping into the exchange answer."""
        props = set(_schema("HandoffExchangeResponse").get("properties", {}))
        self.assertTrue(props, "HandoffExchangeResponse has no properties")
        self.assertIn("sealed_package", props, "HandoffExchangeResponse must carry sealed_package")
        for field in props:
            for part in KEY_NAME_PARTS:
                self.assertNotIn(part, field.lower(),
                                 f"HandoffExchangeResponse.{field} looks like key material (contains {part!r})")
        for forbidden in ("wrapped_dek", "wrapped_box_privkey", "box_pubkey", "prev_wrapped_dek", "auth_verifier", "password"):
            self.assertNotIn(forbidden, props, f"HandoffExchangeResponse must not carry {forbidden}")

    def test_wire_name_is_sealed_package_never_package(self):
        """Catches the wire field being named package (a hard keyword in Kotlin)."""
        for name in ("HandoffStartRequest", "HandoffExchangeResponse"):
            props = _schema(name).get("properties", {})
            self.assertIn("sealed_package", props, f"schema {name} must name the field sealed_package")
            self.assertNotIn("package", props, f"schema {name} must not have a property named package")


# ---------------------------------------------------------------------------
# literal pattern strings and length bounds
# ---------------------------------------------------------------------------

class HandoffPatternLiteralTest(unittest.TestCase):
    """The pattern and length strings are pinned literally, as the auth tests pin theirs."""

    def _pattern(self, schema, field):
        return _prop(schema, field).get("pattern")

    def test_start_challenge_pattern(self):
        """Catches any change of the challenge pattern."""
        self.assertEqual(self._pattern("HandoffStartRequest", "challenge"), PATTERN_URL43, "HandoffStartRequest.challenge pattern")

    def test_start_sealed_package_pattern(self):
        """Catches any change of the sealed package pattern in the start request."""
        self.assertEqual(self._pattern("HandoffStartRequest", "sealed_package"), PATTERN_SEALED,
                         "HandoffStartRequest.sealed_package pattern")

    def test_start_response_code_pattern(self):
        """Catches any change of the code pattern in the start answer."""
        self.assertEqual(self._pattern("HandoffStartResponse", "code"), PATTERN_URL43, "HandoffStartResponse.code pattern")

    def test_exchange_request_code_pattern(self):
        """Catches any change of the code pattern in the exchange request."""
        self.assertEqual(self._pattern("HandoffExchangeRequest", "code"), PATTERN_URL43, "HandoffExchangeRequest.code pattern")

    def test_exchange_request_secret_pattern(self):
        """Catches any change of the secret pattern in the exchange request."""
        self.assertEqual(self._pattern("HandoffExchangeRequest", "secret"), PATTERN_URL43, "HandoffExchangeRequest.secret pattern")

    def test_exchange_response_token_pattern(self):
        """Catches any change of the session token pattern in the exchange answer."""
        self.assertEqual(self._pattern("HandoffExchangeResponse", "token"), PATTERN_URL43, "HandoffExchangeResponse.token pattern")

    def test_exchange_response_sealed_package_pattern(self):
        """Catches any change of the sealed package pattern in the exchange answer."""
        self.assertEqual(self._pattern("HandoffExchangeResponse", "sealed_package"), PATTERN_SEALED,
                         "HandoffExchangeResponse.sealed_package pattern")

    def test_sealed_package_length_bounds_in_both_schemas(self):
        """Catches a missing or different minLength or maxLength on either sealed_package (both must be 152)."""
        self.assertTrue(SEALED_FIELDS, "internal: empty field list")
        for schema, field in SEALED_FIELDS:
            prop = _prop(schema, field)
            self.assertEqual(prop.get("minLength"), "152", f"{schema}.{field} minLength")
            self.assertEqual(prop.get("maxLength"), "152", f"{schema}.{field} maxLength")

    def test_sealed_package_length_is_the_base64_of_113_bytes(self):
        """Catches the 152 bound drifting away from the length of 113 bytes in standard base64 (65 + 48)."""
        self.assertEqual(65 + 48, 113, "internal: the package is 65 + 48 bytes")
        self.assertEqual(len(_b64(bytes(113))), 152, "internal: base64 of 113 bytes is 152 characters")
        for schema, field in SEALED_FIELDS:
            prop = _prop(schema, field)
            self.assertEqual(int(prop["minLength"]), len(_b64(bytes(113))), f"{schema}.{field} minLength vs 113 bytes")
            self.assertEqual(int(prop["maxLength"]), len(_b64(bytes(113))), f"{schema}.{field} maxLength vs 113 bytes")

    def test_both_sealed_package_fields_agree(self):
        """Catches the start request and the exchange answer drifting apart on pattern or length."""
        a = _prop("HandoffStartRequest", "sealed_package")
        b = _prop("HandoffExchangeResponse", "sealed_package")
        for key in ("pattern", "minLength", "maxLength"):
            self.assertIn(key, a, f"HandoffStartRequest.sealed_package has no {key}")
            self.assertEqual(a.get(key), b.get(key), f"sealed_package {key} differs between the start request and the exchange answer")

    def test_other_43_character_fields_have_no_length_keywords(self):
        """Catches a stray minLength or maxLength that disagrees with the 43-character pattern."""
        self.assertTrue(URL43_FIELDS, "internal: empty field list")
        for schema, field in URL43_FIELDS:
            prop = _prop(schema, field)
            for key in ("minLength", "maxLength"):
                if key in prop:
                    self.assertEqual(prop[key], "43", f"{schema}.{field} {key} must be 43 if present")


# ---------------------------------------------------------------------------
# behaviour of the patterns
# ---------------------------------------------------------------------------

class HandoffPatternBehaviourTest(unittest.TestCase):
    """The patterns read from the spec behave as the rules say, on built samples."""

    def test_challenge_pattern_behaviour(self):
        """Catches a challenge pattern that accepts 42 or 44 characters, plus, slash, padding or a newline."""
        _check_url43(self, _prop("HandoffStartRequest", "challenge").get("pattern", ""), "HandoffStartRequest.challenge")

    def test_start_code_pattern_behaviour(self):
        """Catches a start-answer code pattern with the wrong length or alphabet."""
        _check_url43(self, _prop("HandoffStartResponse", "code").get("pattern", ""), "HandoffStartResponse.code")

    def test_exchange_code_pattern_behaviour(self):
        """Catches an exchange-request code pattern with the wrong length or alphabet."""
        _check_url43(self, _prop("HandoffExchangeRequest", "code").get("pattern", ""), "HandoffExchangeRequest.code")

    def test_exchange_secret_pattern_behaviour(self):
        """Catches an exchange-request secret pattern with the wrong length or alphabet."""
        _check_url43(self, _prop("HandoffExchangeRequest", "secret").get("pattern", ""), "HandoffExchangeRequest.secret")

    def test_exchange_token_pattern_behaviour(self):
        """Catches a session token pattern with the wrong length or alphabet."""
        _check_url43(self, _prop("HandoffExchangeResponse", "token").get("pattern", ""), "HandoffExchangeResponse.token")

    def test_start_sealed_package_pattern_behaviour(self):
        """Catches a start-request package pattern that accepts 112 or 114 bytes, URL-safe text or a misplaced pad."""
        _check_sealed(self, _prop("HandoffStartRequest", "sealed_package").get("pattern", ""), "HandoffStartRequest.sealed_package")

    def test_exchange_sealed_package_pattern_behaviour(self):
        """Catches an exchange-answer package pattern that accepts 112 or 114 bytes, URL-safe text or a misplaced pad."""
        _check_sealed(self, _prop("HandoffExchangeResponse", "sealed_package").get("pattern", ""),
                      "HandoffExchangeResponse.sealed_package")

    def test_samples_of_112_and_114_bytes_have_the_same_length(self):
        """Proves the length bound alone cannot tell 112, 113 and 114 bytes apart, so the pattern must."""
        self.assertEqual(len(_b64(bytes(112))), 152, "base64 of 112 bytes must be 152 characters")
        self.assertEqual(len(_b64(bytes(114))), 152, "base64 of 114 bytes must be 152 characters")
        self.assertEqual(len(_b64(bytes(113))), 152, "base64 of 113 bytes must be 152 characters")
        self.assertTrue(_b64(bytes(112)).endswith("=="), "base64 of 112 bytes ends in two pad characters")
        self.assertFalse(_b64(bytes(114)).endswith("="), "base64 of 114 bytes has no pad character")


class HandoffSampleControlsTest(unittest.TestCase):
    """The sample sets must be able to fail: each wrong pattern below is refused by the checks."""

    def test_literal_patterns_pass_the_checks(self):
        """Control: the agreed literal patterns pass both checks."""
        _check_url43(self, PATTERN_URL43, "control URL-safe 43")
        _check_sealed(self, PATTERN_SEALED, "control sealed 113 bytes")

    def test_wrong_43_patterns_fail_the_check(self):
        """Control: patterns with the wrong length, alphabet or anchors are caught by the 43-character check."""
        wrong = [
            "^[A-Za-z0-9_-]{42}$",
            "^[A-Za-z0-9_-]{44}$",
            "^[A-Za-z0-9_-]{43,44}$",
            "^[A-Za-z0-9+/]{43}$",
            "^[A-Za-z0-9_-]{43}=$",
            "[A-Za-z0-9_-]{43}",
            "^[A-Za-z0-9_-]{43}",
            "^[A-Za-z0-9_+/-]{43}$",
            "^.{43}$",
        ]
        self.assertTrue(wrong, "internal: empty control list")
        for pattern in wrong:
            with self.assertRaises(AssertionError, msg=f"the 43-character check passed the wrong pattern {pattern!r}"):
                _check_url43(self, pattern, "control")

    def test_wrong_sealed_patterns_fail_the_check(self):
        """Control: patterns for 112 or 114 bytes, the URL-safe alphabet or loose padding are caught by the sealed check."""
        wrong = [
            "^[A-Za-z0-9+/]{150}==$",
            "^[A-Za-z0-9+/]{152}$",
            "^[A-Za-z0-9+/]{151,152}=?$",
            "^[A-Za-z0-9_-]{151}=$",
            "^[A-Za-z0-9+/=]{152}$",
            "[A-Za-z0-9+/]{151}=",
            "^[A-Za-z0-9+/]{151}=",
            "^[A-Za-z0-9+/]{150}[=A]=$",
        ]
        self.assertTrue(wrong, "internal: empty control list")
        for pattern in wrong:
            with self.assertRaises(AssertionError, msg=f"the sealed check passed the wrong pattern {pattern!r}"):
                _check_sealed(self, pattern, "control")


# ---------------------------------------------------------------------------
# device_name
# ---------------------------------------------------------------------------

class HandoffDeviceNameTest(unittest.TestCase):
    """device_name is optional and nullable (omitted and null both mean no name); a string is 1 to 64 characters, not trimmed."""

    def test_device_name_is_a_string_with_bounds(self):
        """Catches a missing or different minLength or maxLength on device_name."""
        prop = _prop("HandoffExchangeRequest", "device_name")
        self.assertEqual(prop.get("type"), "string", "HandoffExchangeRequest.device_name type")
        self.assertEqual(prop.get("minLength"), "1", "HandoffExchangeRequest.device_name minLength")
        self.assertEqual(prop.get("maxLength"), "64", "HandoffExchangeRequest.device_name maxLength")

    def test_device_name_is_optional(self):
        """Catches device_name being put in the required list."""
        required = _schema("HandoffExchangeRequest").get("required", [])
        self.assertTrue(required, "HandoffExchangeRequest has no required list")
        self.assertNotIn("device_name", required, "HandoffExchangeRequest.device_name must be optional")
        self.assertIn("code", required, "HandoffExchangeRequest.code must be required")
        self.assertIn("secret", required, "HandoffExchangeRequest.secret must be required")

    def test_device_name_is_nullable(self):
        """Catches device_name losing nullable true (JSON null means no name and is not a 400)."""
        prop = _prop("HandoffExchangeRequest", "device_name")
        self.assertEqual(str(prop.get("nullable", "false")).strip().lower(), "true",
                         "HandoffExchangeRequest.device_name must be nullable: true")

    def test_device_name_bounds_cover_one_to_sixty_four(self):
        """Catches bounds that would let 0 or 65 characters through (built from the spec values)."""
        prop = _prop("HandoffExchangeRequest", "device_name")
        low, high = int(prop["minLength"]), int(prop["maxLength"])
        for length, ok in ((0, False), (1, True), (64, True), (65, False)):
            self.assertEqual(low <= length <= high, ok, f"device_name of {length} characters: bounds {low}..{high}")

    def test_device_name_description_states_the_rule(self):
        """Catches the field description losing a part of the rule: may be left out, may be null, both mean no name, no trimming."""
        prop = _prop("HandoffExchangeRequest", "device_name")
        text = _flat(prop.get("description"))
        self.assertTrue(text, "HandoffExchangeRequest.device_name has no description")
        for phrase in ("left out", "null", "no name", "1 to 64 characters", "no trimming"):
            self.assertTrue(_has_phrase(text, phrase),
                            f"HandoffExchangeRequest.device_name description alone must say {phrase!r}")


# ---------------------------------------------------------------------------
# descriptions
# ---------------------------------------------------------------------------

class HandoffDescriptionPhraseTest(unittest.TestCase):
    """Key phrases of the plain-words rules are pinned (on folded, lower-cased text)."""

    def setUp(self):
        self.start = _flat(_op(START_PATH).get("description"))
        self.exchange = _flat(_op(EXCHANGE_PATH).get("description"))

    def test_start_description_exists(self):
        """Catches a start route with no description."""
        self.assertTrue(self.start, f"{START_PATH} has no description")

    def test_exchange_description_exists(self):
        """Catches an exchange route with no description."""
        self.assertTrue(self.exchange, f"{EXCHANGE_PATH} has no description")

    def test_start_says_five_minutes(self):
        """Catches the 5-minute life of a code being dropped."""
        self.assertTrue(_has_phrase(self.start, "5 minutes"), f"{START_PATH} description must say '5 minutes'")

    def test_start_says_once(self):
        """Catches the single-use rule being dropped from the start description."""
        self.assertTrue(_has_phrase(self.start, "once"), f"{START_PATH} description must say the code is usable once")

    def test_start_says_at_most_five_live_codes(self):
        """Catches the cap of live codes per account being dropped."""
        self.assertTrue(_has_phrase(self.start, "at most 5 live codes"), f"{START_PATH} description must say 'at most 5 live codes'")

    def test_start_names_the_oldest_code_displacement(self):
        """Catches the rule that a sixth start ends the oldest live code being dropped."""
        self.assertTrue(_has_phrase(self.start, "oldest"), f"{START_PATH} description must name the oldest live code")

    def test_start_says_the_code_is_43_characters_and_link_safe(self):
        """Catches the code length and the breaker:// link remark being dropped."""
        for phrase in ("43 characters", "breaker://"):
            self.assertTrue(_has_phrase(self.start, phrase), f"{START_PATH} description must say {phrase!r}")

    def test_exchange_says_deleted(self):
        """Catches the rule that the stored package is deleted on success being dropped."""
        self.assertTrue(_has_phrase(self.exchange, "deleted"), f"{EXCHANGE_PATH} description must say the package is deleted")

    def test_exchange_says_once(self):
        """Catches the rule that the package comes back once, in this answer only, being dropped."""
        self.assertTrue(_has_phrase(self.exchange, "once"), f"{EXCHANGE_PATH} description must say the package comes back once")

    def test_exchange_says_the_code_is_used_up(self):
        """Catches the rule that a successful exchange uses up the code being dropped from the operation description."""
        self.assertTrue(_has_phrase(self.exchange, "used up"), f"{EXCHANGE_PATH} operation description alone must say 'used up'")

    def test_exchange_says_ordinary_session(self):
        """Catches the remark that the session is an ordinary session being dropped."""
        self.assertTrue(_has_phrase(self.exchange, "ordinary session"), f"{EXCHANGE_PATH} description must say 'ordinary session'")

    def test_exchange_says_thirty_days(self):
        """Catches the 30-day end of the session being dropped."""
        self.assertTrue(_has_phrase(self.exchange, "30 days"), f"{EXCHANGE_PATH} description must say '30 days'")

    def test_exchange_says_hash_and_no_key_material(self):
        """Catches the hash rule, or the no-password and no-plaintext-key promise, being dropped."""
        for phrase in ("hashes the secret", "password", "plaintext key"):
            self.assertTrue(_has_phrase(self.exchange, phrase), f"{EXCHANGE_PATH} description must say {phrase!r}")

    def test_spec_does_not_document_a_503_for_these_routes(self):
        """Catches a 503 being documented for the hand-off routes (the agreed status list has none)."""
        for path in (START_PATH, EXCHANGE_PATH):
            self.assertNotIn("503", _responses(path), f"{path} must not document a 503")
            self.assertFalse(_has_phrase(_flat(_op(path).get("description")), "503"), f"{path} description must not mention 503")


# ---------------------------------------------------------------------------
# examples
# ---------------------------------------------------------------------------

def _examples():
    """(label, schema name, example mapping) for the four bodies that carry a schema example."""
    out = []
    _body, start_req = _request_media(START_PATH)
    _body, exch_req = _request_media(EXCHANGE_PATH)
    out.append((f"{START_PATH} request", "HandoffStartRequest", start_req.get("example")))
    out.append((f"{START_PATH} 201", "HandoffStartResponse", _json_media(START_PATH, "201").get("example")))
    out.append((f"{EXCHANGE_PATH} request", "HandoffExchangeRequest", exch_req.get("example")))
    out.append((f"{EXCHANGE_PATH} 200", "HandoffExchangeResponse", _json_media(EXCHANGE_PATH, "200").get("example")))
    return out


class HandoffExamplesTest(unittest.TestCase):
    """Every example satisfies its own schema and looks obviously fake."""

    def test_four_examples_exist_and_are_mappings(self):
        """Catches a missing example on the request or success bodies."""
        found = _examples()
        self.assertEqual(len(found), 4, "internal: four examples expected")
        for label, schema, example in found:
            self.assertIsInstance(example, dict, f"{label}: no example mapping for schema {schema}")

    def test_example_keys_fit_the_schema(self):
        """Catches an example that misses a required key or carries a key the schema does not have."""
        for label, schema, example in _examples():
            props = set(_schema(schema).get("properties", {}))
            required = set(_schema(schema).get("required", []))
            self.assertTrue(props and required, f"{schema}: empty properties or required list")
            self.assertTrue(required <= set(example), f"{label}: example misses required keys {sorted(required - set(example))}")
            self.assertTrue(set(example) <= props, f"{label}: example has keys not in {schema}: {sorted(set(example) - props)}")

    def test_example_strings_satisfy_patterns_and_bounds(self):
        """Catches an example string that breaks its own pattern, minLength or maxLength."""
        checked = 0
        for label, schema, example in _examples():
            for field, value in example.items():
                prop = _prop(schema, field)
                if prop.get("type") != "string":
                    continue
                self.assertIsInstance(value, str, f"{label}.{field}: example is not text")
                if "pattern" in prop:
                    self.assertTrue(_accepts(prop["pattern"], value, f"{label}.{field}"),
                                    f"{label}.{field}: example {value!r} breaks its pattern {prop['pattern']!r}")
                    checked += 1
                if "minLength" in prop:
                    self.assertGreaterEqual(len(value), int(prop["minLength"]), f"{label}.{field}: example is too short")
                if "maxLength" in prop:
                    self.assertLessEqual(len(value), int(prop["maxLength"]), f"{label}.{field}: example is too long")
        self.assertGreaterEqual(checked, 7, "expected at least seven pattern-checked example strings")

    def test_example_numbers_and_role(self):
        """Catches a negative or non-numeric key_version or account_id, or a role that is not admin or user."""
        answer = dict((label, ex) for label, _schema_name, ex in _examples())[f"{EXCHANGE_PATH} 200"]
        self.assertRegex(answer.get("key_version", ""), r"^[0-9]+$", "exchange 200 example key_version must be a whole number from 0")
        self.assertRegex(answer.get("account_id", ""), r"^[0-9]+$", "exchange 200 example account_id must be a whole number")
        self.assertIn(answer.get("role"), ("admin", "user"), "exchange 200 example role")
        self.assertTrue(answer.get("username"), "exchange 200 example username must not be empty")

    def test_exchange_request_example_device_name_in_bounds(self):
        """Catches an exchange example whose device_name is empty or longer than 64 characters."""
        example = dict((label, ex) for label, _schema_name, ex in _examples())[f"{EXCHANGE_PATH} request"]
        if "device_name" in example:
            self.assertTrue(1 <= len(example["device_name"]) <= 64, "exchange request example device_name must be 1 to 64 characters")

    def test_43_character_examples_are_one_repeated_character(self):
        """Catches a challenge, code, secret or token example that is not exactly 43 times one single character (sealed_package is not covered)."""
        by_key = {}
        for _label, schema, example in _examples():
            for field, value in example.items():
                if (schema, field) in URL43_FIELDS:
                    by_key[(schema, field)] = value
        self.assertEqual(sorted(by_key), sorted(URL43_FIELDS), "an example is missing for one of challenge, code, secret, token")
        names = sorted(field for _schema_name, field in by_key)
        self.assertEqual(names, ["challenge", "code", "code", "secret", "token"], "the five 43-character example fields")
        for (schema, field), value in by_key.items():
            self.assertEqual(len(value), 43, f"{schema}.{field} example must be 43 characters, got {len(value)}")
            self.assertEqual(len(set(value)), 1, f"{schema}.{field} example {value!r} must be one single character repeated")

    def test_sealed_package_examples_decode_to_113_bytes(self):
        """Catches a sealed_package example that is not standard base64 of exactly 113 bytes."""
        seen = 0
        for label, schema, example in _examples():
            if "sealed_package" not in example:
                continue
            try:
                raw = base64.b64decode(example["sealed_package"], validate=True)
            except ValueError as err:
                self.fail(f"{label}: sealed_package example is not standard base64: {err}")
            self.assertEqual(len(raw), 113, f"{label}: sealed_package example decodes to {len(raw)} bytes, not 113")
            self.assertEqual(len(example["sealed_package"]), 152, f"{label}: sealed_package example length")
            seen += 1
        self.assertEqual(seen, 2, "both the start request and the exchange answer need a sealed_package example")

    def test_examples_look_obviously_fake(self):
        """Catches a real-looking random example: the most common character must be a fifth or more of the text."""
        seen = 0
        for label, schema, example in _examples():
            for field, value in example.items():
                if (schema, field) in URL43_FIELDS or (schema, field) in SEALED_FIELDS:
                    top = max(value.count(ch) for ch in set(value))
                    self.assertGreaterEqual(top * 5, len(value),
                                            f"{label}.{field}: example {value!r} looks random, not obviously fake")
                    seen += 1
        self.assertEqual(seen, 7, "seven 43-or-152-character examples are expected")


# ---------------------------------------------------------------------------
# Kotlin data classes
# ---------------------------------------------------------------------------

class HandoffKotlinTest(unittest.TestCase):
    """The four Kotlin data classes exist and hold the same fields as the schemas."""

    def _check(self, name):
        text, fields = _read_kotlin(name)
        props = _schema(name).get("properties", {})
        required = set(_schema(name).get("required", []))
        self.assertTrue(props and fields, f"{name}: empty field set")
        self.assertIn("package dev.breaker.shared.api", text.split("\n"), f"{name}.kt: package line")
        self.assertIn(f"Mirrors the `{name}` schema in the OpenAPI spec.", text, f"{name}.kt: KDoc sentence")
        self.assertFalse([ln for ln in text.split("\n") if ln.startswith("import ")], f"{name}.kt must have no imports")
        names = [f[0] for f in fields]
        self.assertEqual(len(names), len(set(names)), f"{name}.kt repeats a field: {names}")
        self.assertEqual(set(names), {_camel(p) for p in props}, f"{name}.kt fields versus schema properties")
        for field in names:
            self.assertNotIn(field, HARD_KEYWORDS, f"{name}.kt: field {field} is a hard keyword")
        self.assertNotIn("package", names, f"{name}.kt: no field may be named package")
        by_name = {f[0]: f for f in fields}
        for wire, prop in props.items():
            ftype, fmt = FIELD_TYPES[(name, wire)]
            expected = "String" if ftype == "string" else ("Long" if fmt == "int64" else "Int")
            _n, kotlin_type, nullable = by_name[_camel(wire)]
            self.assertEqual(kotlin_type, expected, f"{name}.{_camel(wire)} Kotlin type for spec type {ftype}")
            self.assertEqual(nullable, wire not in required, f"{name}.{_camel(wire)} nullable must match optional in the schema")

    def test_handoff_start_request_kotlin(self):
        """Catches a missing, misnamed or mistyped HandoffStartRequest.kt."""
        self._check("HandoffStartRequest")

    def test_handoff_start_response_kotlin(self):
        """Catches a missing, misnamed or mistyped HandoffStartResponse.kt."""
        self._check("HandoffStartResponse")

    def test_handoff_exchange_request_kotlin(self):
        """Catches a missing, misnamed or mistyped HandoffExchangeRequest.kt (deviceName must be nullable)."""
        self._check("HandoffExchangeRequest")

    def test_handoff_exchange_response_kotlin(self):
        """Catches a missing, misnamed or mistyped HandoffExchangeResponse.kt (accountId Long, keyVersion Int)."""
        self._check("HandoffExchangeResponse")

    def test_sealed_package_is_camel_cased_in_both_classes(self):
        """Catches the Kotlin field being named package, or sealed_package being kept in snake case."""
        for name in ("HandoffStartRequest", "HandoffExchangeResponse"):
            _text, fields = _read_kotlin(name)
            self.assertIn("sealedPackage", [f[0] for f in fields], f"{name}.kt must carry sealedPackage")

    def test_device_name_is_nullable_in_kotlin(self):
        """Catches deviceName losing its question mark."""
        _text, fields = _read_kotlin("HandoffExchangeRequest")
        by_name = {f[0]: f for f in fields}
        self.assertIn("deviceName", by_name, "HandoffExchangeRequest.kt must carry deviceName")
        self.assertTrue(by_name["deviceName"][2], "HandoffExchangeRequest.deviceName must be nullable")


if __name__ == "__main__":
    unittest.main()
