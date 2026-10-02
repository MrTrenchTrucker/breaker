"""The api-contracts Python tier: spec reader, structural validator, and the
import surface for the Kotlin reader and the production comparator.
Standard library only.

Layout (line cap 500 per file -- this is why the tier is three modules):

* THIS file: the SPEC reader (openapi.yaml via tools/yaml_subset.py -- schemas,
  fields, types, required-ness, enums, refs, nullability, sibling enum
  narrowing) and the STRUCTURAL validator (every check its docstring claims,
  each backed by code here and a RED test in test_structural_validator.py).
* api_kotlin_reader: the KOTLIN reader -- a closed grammar that refuses with
  file and line on any unrecognised construct and counts what it should have
  found (the vacuity guard). The grammar is stated in that module's docstring
  and pinned by test_reader_closed_grammar.py.
* api_comparator: THE production conformance comparator (compare_fields) and
  the both-ways coverage check (check_coverage) plus the spec->Kotlin type
  map. compare_fields is the only field-level checker: the conformance tests,
  the mismatch-class tests and the gate mutants all run it, and its named
  assertion messages are what the class tests assert on.

The SPEC reader and the KOTLIN reader stay independent (neither imports the
other). compare_fields defers its imports of get_schema_fields/ref_target
from this module until call time, so the re-exports below and its own
import of this module never deadlock in either import order.

Every reader refuses to return something empty or partial: a parser that
finds nothing would make every comparison downstream pass vacuously, so each
one counts what it should have found and raises when the count is off.

Module card: shared/modules/api-contracts/AGENTS.md
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
MODULE = ROOT / "shared" / "modules" / "api-contracts"
SPEC = MODULE / "openapi.yaml"
KOTLIN_DIR = MODULE / "src" / "main" / "kotlin" / "dev" / "breaker" / "shared" / "api"

sys.path.insert(0, str(ROOT / "tools"))
import yaml_subset

# The closed Kotlin reader and the production comparator live in their own
# modules (line cap 500 per file); re-exported here so the test suite and
# the gate keep one import surface.
from api_kotlin_reader import (
    parse_kotlin_file, parse_all_kotlin, snake, KOTLIN_DIR,
)
from api_comparator import (
    map_spec_type_to_kotlin, compare_fields, check_coverage,
)


# ---------------------------------------------------------------------------
# SPEC reader
# ---------------------------------------------------------------------------

def load_spec():
    """Load and return the parsed openapi.yaml as a dict.

    Refuses if the file is missing or the reader raises, and refuses a
    parse that is missing any of the top-level keys this contract needs
    (openapi, info, paths, components) -- a partial spec would make the
    validator pass vacuously on the parts it has.
    """
    if not SPEC.exists():
        raise FileNotFoundError(f"Spec file not found: {SPEC}")
    spec = yaml_subset.load(str(SPEC))
    # Vacuity guard: the spec must have openapi, info, paths, and components
    for key in ("openapi", "info", "paths", "components"):
        if key not in spec:
            raise ValueError(f"Spec missing required top-level key: {key}")
    return spec


def get_schemas(spec):
    """Extract all schemas from the spec's components/schemas.

    Returns a dict mapping schema name -> schema dict.
    """
    schemas = spec.get("components", {}).get("schemas", {})
    if not schemas:
        raise ValueError("Spec has no components/schemas")
    return schemas


def effective_enum(prop):
    """Return the SIBLING enum of a property, or None.

    A property may narrow a $ref'd enum schema with a sibling `enum:` list
    (the JobAccepted.status shape: allOf -> JobStatus, enum: [queued]).
    The sibling list is the effective set of values the property allows;
    the $ref still names the Kotlin type.
    """
    if not isinstance(prop, dict):
        return None
    return prop.get("enum")


def get_schema_fields(schema):
    """Extract fields from a schema dict.

    Returns a list of (field_name, field_type, is_required, format, ref,
    nullable, enum) tuples. field_type is the OpenAPI type string
    ("string", "integer", "number", "array", "object"; a property whose only
    shape is an allOf/$ref keeps the literal "object" default -- the ref is
    what the comparator maps). format is the OpenAPI format string or None.
    ref is the $ref target (direct or first allOf member) or None.
    nullable is the property's `nullable` flag. enum is the property's
    sibling enum list (a narrowing on a $ref property) or None.
    """
    fields = []
    properties = schema.get("properties", {})
    required = set(schema.get("required", []))
    for name, prop in properties.items():
        if not isinstance(prop, dict):
            continue
        ftype = prop.get("type", "object")
        is_req = name in required
        fmt = prop.get("format")
        ref = prop.get("$ref")
        # Also check allOf for $ref (first $ref member)
        if not ref:
            all_of = prop.get("allOf", [])
            if isinstance(all_of, list):
                for item in all_of:
                    if isinstance(item, dict) and "$ref" in item:
                        ref = item["$ref"]
                        break
        fields.append((name, ftype, is_req, fmt, ref,
                       is_true(prop.get("nullable")), effective_enum(prop)))
    return fields


def get_enum_values(schema):
    """Extract enum values from a schema dict.

    Returns a list of enum values, or None if no enum.
    """
    return schema.get("enum")


def ref_target(ref):
    """The schema name at the end of a #/components/schemas/X ref, or None."""
    if not ref:
        return None
    parts = ref.split("/")
    return parts[-1]


def is_true(value):
    """True for a YAML boolean that the stdlib reader hands back as the
    plain string 'true' (or a real True); false for 'false'/None/other."""
    if value is True:
        return True
    if isinstance(value, str):
        return value.strip().lower() == "true"
    return False


# NOTE on the type map: there is exactly one map_spec_type_to_kotlin, in
# api_comparator (re-exported above with compare_fields / check_coverage).
# A second copy here once shadowed the import, so the tests saw a different
# map from the comparator. Do not re-add it.


# ---------------------------------------------------------------------------
# Mutate helper (for mismatch-class tests and gate mutants)
# ---------------------------------------------------------------------------

def mutate(text, anchor, replacement):
    """Replace `anchor` with `replacement` in `text`.

    Raises ValueError if `anchor` is not found in `text`.
    This is the anchor rule: a missing anchor means the mutation is vacuous.
    """
    if anchor not in text:
        raise ValueError(f"mutate: anchor not found: {anchor!r}")
    return text.replace(anchor, replacement)


# ---------------------------------------------------------------------------
# Structural validator
# ---------------------------------------------------------------------------

def iter_refs(node):
    """Yield every $ref string anywhere in a spec structure (dicts, lists)."""
    if isinstance(node, dict):
        if "$ref" in node:
            yield node["$ref"]
        for v in node.values():
            yield from iter_refs(v)
    elif isinstance(node, list):
        for v in node:
            yield from iter_refs(v)


def validate_spec_structure(spec):
    """Validate that the spec is structurally valid for the OpenAPI 3.x subset.

    Checks (every claim here is backed by code below and by a RED test in
    test_structural_validator.py):
    - openapi version is 3.x
    - info has title and version
    - paths has at least one path
    - Every schema in components/schemas has a `type`
    - Every name in a schema's `required` list is a key in its `properties`
    - All $refs in the WHOLE spec resolve to components/schemas (response
      schemas, items.$ref, allOf members -- not just responses)

    Raises ValueError with a descriptive message on any violation.
    """
    # Check openapi version
    openapi_ver = spec.get("openapi", "")
    if not str(openapi_ver).startswith("3."):
        raise ValueError(f"openapi version must be 3.x, got: {openapi_ver}")

    # Check info
    info = spec.get("info", {})
    if not info.get("title"):
        raise ValueError("info.title is missing")
    if not info.get("version"):
        raise ValueError("info.version is missing")

    # Check paths
    paths = spec.get("paths", {})
    if not paths:
        raise ValueError("paths is empty")

    schemas = spec.get("components", {}).get("schemas", {})

    # [CHECK-TYPE] every schema has a type
    for name, schema in schemas.items():
        if not isinstance(schema, dict) or "type" not in schema:
            raise ValueError(f"schema {name} has no type")

    # [CHECK-REQUIRED-IN-PROPERTIES] required names are properties
    for name, schema in schemas.items():
        if not isinstance(schema, dict):
            continue
        props = schema.get("properties", {})
        for req in schema.get("required", []):
            if req not in props:
                raise ValueError(
                    f"schema {name}: required field '{req}' is not in properties"
                )

    # [CHECK-REFS] every $ref in the whole spec resolves
    for ref in iter_refs(spec):
        if not isinstance(ref, str) or not ref.startswith("#/components/schemas/"):
            raise ValueError(f"Unrecognised $ref target: {ref!r}")
        target = ref_target(ref)
        if target not in schemas:
            raise ValueError(f"Unresolved $ref: {ref}")

    return True
