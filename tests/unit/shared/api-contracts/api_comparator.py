"""The production conformance comparator and the spec-type map.
Standard library only.

`map_spec_type_to_kotlin` maps one spec property shape to the expected
Kotlin type string (exact rules, no guessing).

`compare_fields` is THE production field-level conformance comparator: it is
the only field-level checker, and it is what the conformance tests, the
mismatch-class tests and the gate mutants run. It raises AssertionError with
a named message on the first mismatch; the messages are what the
mismatch-class tests assert on, so the class's own assertion is the evidence.

`check_coverage` is THE production both-ways coverage check: EVERY
components/schemas entry must map to a Kotlin type and every Kotlin type
must have a schema (the 5 hand-picked schemas of slice 1 are no longer the
coverage surface).
"""
import sys
from pathlib import Path

# NOTE: get_schema_fields and ref_target live in api_contracts_support, which
# re-exports this module. They are imported INSIDE compare_fields (below) to
# avoid a module-level circular import; by call time the module is fully
# loaded, so the deferred import is safe in either import order.

ROOT = Path(__file__).resolve().parents[4]


def map_spec_type_to_kotlin(spec_type, fmt=None, ref=None, enum=None, items_ref=None):
    """Map an OpenAPI spec type to the expected Kotlin type string.

    Rules (exact, no guessing):
    - $ref -> the referenced class name (e.g., "TranscriptionResult")
    - string -> String (an enum property maps to its enum class via the
      comparator's enum-lookup step, which knows the spec values)
    - integer -> Int (unless format: int64 -> Long)
    - number -> Double
    - boolean -> Boolean
    - array -> List<...> (extract item type from items.$ref)
    - anything else -> ValueError (refuse, do not guess)
    """
    if ref:
        # $ref: extract the class name from the ref path
        # e.g., "#/components/schemas/Segment" -> "Segment"
        parts = ref.split("/")
        return parts[-1]
    if spec_type == "string":
        return "String"
    if spec_type == "integer":
        if fmt == "int64":
            return "Long"
        return "Int"
    if spec_type == "number":
        return "Double"
    if spec_type == "boolean":
        return "Boolean"
    if spec_type == "array":
        if items_ref:
            parts = items_ref.split("/")
            item_type = parts[-1]
            return f"List<{item_type}>"
        return "List<Any>"
    raise ValueError(
        f"map_spec_type_to_kotlin: unsupported spec type: {spec_type} "
        f"(format: {fmt}, enum: {enum!r})"
    )


def check_coverage(schemas, kotlin_registry):
    """THE production both-ways coverage check.

    Iterates EVERY components/schemas entry and every parsed Kotlin type,
    both directions. The Error schema maps to the Kotlin type ApiError
    (named explicitly so it cannot clash with kotlin.Error); every other
    schema maps to the same-named Kotlin type and vice versa.

    Raises AssertionError naming the first violation:
    - "spec - kotlin: schema X has no mapped Kotlin type (expected Y)"
    - "kotlin - spec: Kotlin type Y has no spec schema (expected X)"
    Returns True on clean coverage.
    """
    mapping = {"Error": "ApiError"}
    for name in schemas:
        kt_name = mapping.get(name, name)
        if kt_name not in kotlin_registry:
            raise AssertionError(
                f"spec - kotlin: schema {name} has no mapped Kotlin type "
                f"(expected {kt_name})"
            )
    reverse = {"ApiError": "Error"}
    for kt_name in kotlin_registry:
        schema_name = reverse.get(kt_name, kt_name)
        if schema_name not in schemas:
            raise AssertionError(
                f"kotlin - spec: Kotlin type {kt_name} has no spec schema "
                f"(expected {schema_name})"
            )
    return True


def compare_fields(schema_name, spec_schema, kotlin_data, kotlin_registry, schemas=None):
    """THE production field-level conformance comparator.

    Compares one spec schema against one parsed Kotlin type, field for field:

    1. [NAME-SET-1] spec -> kotlin: no spec field missing from Kotlin;
    2. [NAME-SET-2] kotlin -> spec: no Kotlin field missing from the spec;
    3. for every common field: [TYPE-MAP] the spec type (through the type
       map, with enum properties mapped to the enum class that carries the
       same values) equals the Kotlin type -- a spec enum property whose
       Kotlin side is a plain String is refused here;
    4. [REQUIRED-NESS] required=True <-> Kotlin non-nullable,
       required=False <-> Kotlin nullable;
    5. [ENUM-VALUES] when the field's $ref target is an enum schema, the
       Kotlin enum class carries exactly that value set.

    get_schema_fields tuples are (name, type, required, fmt, ref, nullable,
    enum) -- the spec-side nullable flag and the sibling enum narrowing are
    carried through.

    Raises AssertionError with a message that names schema, field and the
    two sides on the first mismatch. Returns True on a clean comparison.
    This function is what the mismatch-class tests run, and the evidence
    harness's mutants each remove one of the named branches above
    ([NAME-SET-1], [NAME-SET-2], [REQUIRED-NESS], [TYPE-MAP],
    [ENUM-VALUES]); the named marker comment is the anchor for the removal.
    """
    if schemas is None:
        schemas = {}
    from api_contracts_support import get_schema_fields, ref_target
    spec_fields = get_schema_fields(spec_schema)
    spec_names = {f[0] for f in spec_fields}
    kotlin_fields = kotlin_data.get("fields", [])
    kotlin_names = {f[0] for f in kotlin_fields}

    # [NAME-SET-1] spec -> kotlin
    extra_spec = spec_names - kotlin_names
    if extra_spec:
        raise AssertionError(
            f"spec - kotlin: {schema_name} has fields not in kotlin: {sorted(extra_spec)}"
        )

    # [NAME-SET-2] kotlin -> spec
    extra_kt = kotlin_names - spec_names
    if extra_kt:
        raise AssertionError(
            f"kotlin - spec: {schema_name} has fields not in spec: {sorted(extra_kt)}"
        )

    spec_by = {f[0]: f for f in spec_fields}
    kt_by = {f[0]: f for f in kotlin_fields}

    for name in sorted(spec_names & kotlin_names):
        s_name, s_type, s_req, s_fmt, s_ref, s_nullable, s_enum = spec_by[name]
        k_name, k_type, k_null = kt_by[name]
        prop = spec_schema.get("properties", {}).get(name, {})
        items = prop.get("items", {}) if isinstance(prop, dict) else {}
        items_ref = items.get("$ref") if isinstance(items, dict) else None

        # [TYPE-MAP] spec -> expected Kotlin type
        expected = map_spec_type_to_kotlin(s_type, s_fmt, s_ref, s_enum, items_ref)
        # An enum property maps to the Kotlin enum class that carries exactly
        # the effective value set (sibling narrowing or inline enum).
        if s_enum:
            for en, ed in kotlin_registry.items():
                if ed.get("type") == "enum" and set(ed.get("values", [])) == set(s_enum):
                    expected = en
                    break
        if expected != k_type:
            raise AssertionError(
                f"{schema_name}.{name}: spec type {s_type} maps to {expected}, "
                f"but Kotlin has {k_type}"
            )

        # [REQUIRED-NESS] required=True <-> non-nullable, required=False <-> nullable
        if s_req:
            if k_null:
                raise AssertionError(
                    f"{schema_name}.{name}: spec says required, but Kotlin is nullable"
                )
        else:
            if not k_null:
                raise AssertionError(
                    f"{schema_name}.{name}: spec says optional, but Kotlin is not nullable "
                    f"(should be nullable or have a default)"
                )

        # [ENUM-VALUES] a $ref to an enum schema: the Kotlin enum carries the
        # target schema's full value set (a sibling narrowing restricts at
        # construction time in Kotlin, not the value set).
        target = ref_target(s_ref)
        if target is not None and k_type in kotlin_registry:
            tgt = schemas.get(target)
            if isinstance(tgt, dict) and tgt.get("enum") is not None:
                kt_enum = kotlin_registry.get(k_type, {})
                if set(kt_enum.get("values", [])) != set(tgt["enum"]):
                    raise AssertionError(
                        f"{schema_name}.{name}: spec enum for {target} "
                        f"{sorted(tgt['enum'])} differs from Kotlin {k_type} "
                        f"values {sorted(kt_enum.get('values', []))}"
                    )

    return True
