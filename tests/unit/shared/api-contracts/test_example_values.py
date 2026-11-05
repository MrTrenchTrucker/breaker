"""Every openapi.yaml example value fits its own schema, and every base64
example decodes to the length the schema promises."""

import base64
import os
import re
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)
HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

import yaml_subset
from api_contracts_support import get_schemas, ref_target

SPEC_PATH = os.path.join(ROOT, "shared", "modules", "api-contracts", "openapi.yaml")

B64_LENGTHS = {"salt": 16, "new_salt": 16, "auth_verifier": 32, "old_auth_verifier": 32, "new_auth_verifier": 32, "box_pubkey": 32}
HTTP_METHODS = ("get", "post", "put", "patch", "delete", "head", "options", "trace")


def load_spec_text(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def parse_spec(text):
    return yaml_subset.loads(text, "openapi.yaml")


def resolve_schema(schema, schemas):
    while isinstance(schema, dict) and "$ref" in schema:
        name = ref_target(schema["$ref"])
        schema = schemas.get(name, schema)
    return schema


def check_examples(spec_text, lengths=B64_LENGTHS):
    spec = parse_spec(spec_text)
    schemas = get_schemas(spec)
    paths = spec.get("paths", {})
    problems = []
    pattern_count = [0]

    def walk(example_val, schema, where):
        if not isinstance(example_val, dict) or not isinstance(schema, dict):
            return
        resolved = resolve_schema(schema, schemas)
        properties = resolved.get("properties", {})
        for key, val in example_val.items():
            if key not in properties:
                continue
            prop_schema = properties[key]
            loc = f"{where}.{key}"
            if isinstance(prop_schema, dict) and "$ref" in prop_schema:
                ref_name = ref_target(prop_schema["$ref"])
                prop_schema = schemas.get(ref_name, prop_schema)
            if isinstance(prop_schema, dict):
                pattern = prop_schema.get("pattern")
                if pattern and isinstance(val, str):
                    pattern_count[0] += 1
                    if not re.fullmatch(pattern, val):
                        problems.append(f"{loc}: example {val!r} does not match pattern {pattern!r}")
                if key in lengths and isinstance(val, str):
                    if val == "null":
                        continue
                    expected_len = lengths[key]
                    try:
                        decoded = base64.b64decode(val, validate=True)
                    except Exception as exc:
                        problems.append(f"{loc}: {key}={val!r} is not valid base64: {exc}")
                        continue
                    if len(decoded) != expected_len:
                        problems.append(f"{loc}: {key} decodes to {len(decoded)} bytes, want {expected_len}")
                    reencoded = base64.b64encode(decoded).decode("ascii")
                    if reencoded != val:
                        problems.append(f"{loc}: {key}={val!r} is not canonical (re-encodes to {reencoded!r})")
            if isinstance(val, dict) and isinstance(prop_schema, dict):
                nested = resolve_schema(prop_schema, schemas)
                if nested.get("type") == "object" and "properties" in nested:
                    walk(val, nested, loc)

    for path, path_item in paths.items():
        if not isinstance(path_item, dict):
            continue
        for method, op in path_item.items():
            if method not in HTTP_METHODS or not isinstance(op, dict):
                continue
            media_types = []
            rb = op.get("requestBody")
            if isinstance(rb, dict):
                for mt, mt_obj in rb.get("content", {}).items():
                    if isinstance(mt_obj, dict) and "schema" in mt_obj and "example" in mt_obj:
                        media_types.append((f"request {method}", mt, mt_obj))
            for status, resp in op.get("responses", {}).items():
                if not isinstance(resp, dict):
                    continue
                for mt, mt_obj in resp.get("content", {}).items():
                    if isinstance(mt_obj, dict) and "schema" in mt_obj and "example" in mt_obj:
                        media_types.append((f"response {status}", mt, mt_obj))
            for label, mt, mt_obj in media_types:
                where = f"{path} {label} {mt}"
                walk(mt_obj["example"], mt_obj["schema"], where)

    if pattern_count[0] < 30:
        problems.append(f"vacuity guard: {pattern_count[0]} pattern-bearing example values, need at least 30")

    return problems


FIXED_SPEC_TEXT = load_spec_text(SPEC_PATH)


def swap_once(text, anchor, new):
    assert text.count(anchor) == 1, f"anchor count={text.count(anchor)}"
    assert text.count(new) == 0, f"new text already present count={text.count(new)}"
    return text.replace(anchor, new, 1)


def build_unfixed_text():
    text = FIXED_SPEC_TEXT
    text = swap_once(text,
        ('              username: "alice"\n'
         '              salt: "AAAAAAAAAAAAAAAAAAAAAA=="\n'
         '              kdf_params:\n'
         '                memory_kib: 65536\n'
         '                iterations: 3\n'
         '                parallelism: 1\n'
         '              kdf_version: 1\n'
         '              auth_verifier: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="'),
        ('              username: "alice"\n'
         '              salt: "AAAAAAAAAAAAAAAAAAAAAA=="\n'
         '              kdf_params:\n'
         '                memory_kib: 65536\n'
         '                iterations: 3\n'
         '                parallelism: 1\n'
         '              kdf_version: 1\n'
         '              auth_verifier: "dmVyaWZpZXI="'))
    text = swap_once(text,
        ('              username: "alice"\n'
         '              salt: "AAAAAAAAAAAAAAAAAAAAAA=="'),
        ('              username: "alice"\n'
         '              salt: "c2FsdA=="'))
    text = swap_once(text,
        ('                $ref: "#/components/schemas/SaltResponse"\n'
         '              example:\n'
         '                salt: "AAAAAAAAAAAAAAAAAAAAAA=="'),
        ('                $ref: "#/components/schemas/SaltResponse"\n'
         '              example:\n'
         '                salt: "c2FsdA=="'))
    text = swap_once(text,
        ('            example:\n'
         '              username: "alice"\n'
         '              auth_verifier: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="'),
        ('            example:\n'
         '              username: "alice"\n'
         '              auth_verifier: "dmVyaWZpZXI="'))
    return text


UNFIXED_SPEC_TEXT = build_unfixed_text()


class ExampleValuesTest(unittest.TestCase):
    def test_fixed_spec_green(self):
        problems = check_examples(FIXED_SPEC_TEXT)
        self.assertEqual(problems, [])

    def test_red_on_unfixed(self):
        problems = check_examples(UNFIXED_SPEC_TEXT)
        self.assertTrue(problems, "expected problems on unfixed spec")
        sites = set()
        for p in problems:
            loc = p.split(": ", 1)[0]
            field = loc.rsplit(".", 1)[-1] if "." in loc else ""
            if field in ("salt", "auth_verifier", "box_pubkey", "new_salt", "old_auth_verifier", "new_auth_verifier"):
                sites.add(loc)
        self.assertEqual(len(sites), 4, f"expected 4 bad sites, got {len(sites)}: {sorted(sites)}")

    def test_mutant_old_salt_register(self):
        text = FIXED_SPEC_TEXT
        anchor = '              username: "alice"\n              salt: "AAAAAAAAAAAAAAAAAAAAAA=="'
        mutant = '              username: "alice"\n              salt: "c2FsdA=="'
        mutated = swap_once(text, anchor, mutant)
        problems = check_examples(mutated)
        self.assertTrue(any("register" in p and "salt" in p for p in problems), problems)

    def test_mutant_old_verifier_register(self):
        text = FIXED_SPEC_TEXT
        anchor = ('              username: "alice"\n'
                  '              salt: "AAAAAAAAAAAAAAAAAAAAAA=="\n'
                  '              kdf_params:\n'
                  '                memory_kib: 65536\n'
                  '                iterations: 3\n'
                  '                parallelism: 1\n'
                  '              kdf_version: 1\n'
                  '              auth_verifier: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="')
        mutant = ('              username: "alice"\n'
                  '              salt: "AAAAAAAAAAAAAAAAAAAAAA=="\n'
                  '              kdf_params:\n'
                  '                memory_kib: 65536\n'
                  '                iterations: 3\n'
                  '                parallelism: 1\n'
                  '              kdf_version: 1\n'
                  '              auth_verifier: "dmVyaWZpZXI="')
        mutated = swap_once(text, anchor, mutant)
        problems = check_examples(mutated)
        self.assertTrue(any("register" in p and "auth_verifier" in p for p in problems), problems)

    def test_mutant_old_salt_salt_response(self):
        text = FIXED_SPEC_TEXT
        anchor = '                $ref: "#/components/schemas/SaltResponse"\n              example:\n                salt: "AAAAAAAAAAAAAAAAAAAAAA=="'
        mutant = '                $ref: "#/components/schemas/SaltResponse"\n              example:\n                salt: "c2FsdA=="'
        mutated = swap_once(text, anchor, mutant)
        problems = check_examples(mutated)
        self.assertTrue(any("salt response" in p and "salt" in p for p in problems), problems)

    def test_mutant_old_verifier_login(self):
        text = FIXED_SPEC_TEXT
        anchor = '            example:\n              username: "alice"\n              auth_verifier: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="'
        mutant = '            example:\n              username: "alice"\n              auth_verifier: "dmVyaWZpZXI="'
        mutated = swap_once(text, anchor, mutant)
        problems = check_examples(mutated)
        self.assertTrue(any("login" in p and "auth_verifier" in p for p in problems), problems)

    def test_mutant_non_canonical_salt(self):
        text = FIXED_SPEC_TEXT
        anchor = '              username: "alice"\n              salt: "AAAAAAAAAAAAAAAAAAAAAA=="'
        mutant = '              username: "alice"\n              salt: "AAAAAAAAAAAAAAAAAAAAAB=="'
        mutated = swap_once(text, anchor, mutant)
        problems = check_examples(mutated)
        self.assertTrue(any("salt" in p and "canonical" in p for p in problems), problems)

    def test_mutant_pattern_only_username(self):
        text = FIXED_SPEC_TEXT
        anchor = '            example:\n              username: "alice"\n              reset_code: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"'
        mutant = '            example:\n              username: "al ice"\n              reset_code: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"'
        mutated = swap_once(text, anchor, mutant)
        problems = check_examples(mutated)
        self.assertTrue(any("does not match pattern" in p for p in problems), problems)

    def test_mutant_vacuity_guard_twenty_nine(self):
        text = FIXED_SPEC_TEXT
        anchor = '                $ref: "#/components/schemas/SaltResponse"\n              example:\n                salt: "AAAAAAAAAAAAAAAAAAAAAA=="'
        mutant = '                $ref: "#/components/schemas/SaltResponse"\n              example:\n                salt:\n'
        mutated = swap_once(text, anchor, mutant)
        problems = check_examples(mutated)
        self.assertTrue(any(p.startswith("vacuity guard: 29") for p in problems), problems)

    def test_mutant_salt_length_table_fifteen(self):
        problems = check_examples(FIXED_SPEC_TEXT, lengths={**B64_LENGTHS, "salt": 15})
        self.assertTrue(any("salt" in p and "want 15" in p for p in problems), problems)
