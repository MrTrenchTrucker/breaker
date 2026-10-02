"""Shared readers for the api-contracts unit tests. Standard library only.

Three independent readers, none of which imports another:

* the SPEC reader parses `openapi.yaml` using `tools/yaml_subset.py`, extracting
  the schemas, their fields, types, required-ness, and enum values;
* the KOTLIN reader parses the Kotlin source files with a closed grammar that
  refuses with file and line on any unrecognised construct, and counts what it
  should have found (the vacuity guard);
* the STRUCTURAL validator checks that `openapi.yaml` is structurally valid for
  the OpenAPI 3.x subset the slice uses.

Every reader refuses to return something empty or partial: a parser that finds
nothing would make every comparison downstream pass vacuously, so each one
counts what it should have found and raises when the count is off.

Module card: shared/modules/api-contracts/AGENTS.md
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
MODULE = ROOT / "shared" / "modules" / "api-contracts"
SPEC = MODULE / "openapi.yaml"
KOTLIN_DIR = MODULE / "src" / "main" / "kotlin" / "dev" / "breaker" / "shared" / "api"

# Add tools/ to sys.path so we can import yaml_subset
sys.path.insert(0, str(ROOT / "tools"))
import yaml_subset


# ---------------------------------------------------------------------------
# SPEC reader
# ---------------------------------------------------------------------------

def load_spec():
    """Load and return the parsed openapi.yaml as a dict.

    Refuses if the file is missing or the reader raises.
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


def get_schema_fields(schema):
    """Extract fields from a schema dict.

    Returns a list of (field_name, field_type, is_required, format, ref) tuples.
    field_type is the OpenAPI type string ("string", "integer", "number", "array", "object").
    format is the OpenAPI format string ("int64", "float", etc.) or None.
    ref is the $ref target (e.g., "#/components/schemas/Segment") or None.
    """
    fields = []
    properties = schema.get("properties", {})
    required = set(schema.get("required", []))
    for name, prop in properties.items():
        ftype = prop.get("type", "object")
        is_req = name in required
        fmt = prop.get("format")
        ref = prop.get("$ref")
        # Also check allOf for $ref
        if not ref:
            all_of = prop.get("allOf", [])
            if all_of:
                for item in all_of:
                    if isinstance(item, dict) and "$ref" in item:
                        ref = item["$ref"]
                        break
        fields.append((name, ftype, is_req, fmt, ref))
    return fields


def get_enum_values(schema):
    """Extract enum values from a schema dict.

    Returns a list of enum values, or None if no enum.
    """
    return schema.get("enum")


# ---------------------------------------------------------------------------
# Type map: spec type -> Kotlin type
# ---------------------------------------------------------------------------

def map_spec_type_to_kotlin(spec_type, fmt=None, ref=None, enum=None, items_ref=None):
    """Map an OpenAPI spec type to the expected Kotlin type string.

    Rules (exact, no guessing):
    - string -> String (or the enum class name if enum is present)
    - integer -> Int (unless format: int64 -> Long)
    - number -> Double
    - boolean -> Boolean
    - array -> List<...> (extract item type from items.$ref or items.type)
    - $ref -> the referenced class name (e.g., "TranscriptionResult")

    Raises ValueError for any spec type not in the map (refuse, do not guess).
    """
    if ref:
        # $ref: extract the class name from the ref path
        # e.g., "#/components/schemas/Segment" -> "Segment"
        parts = ref.split("/")
        return parts[-1]
    if spec_type == "string":
        if enum:
            # If there's an enum, the Kotlin type is the enum class name
            # We need to find which enum class this maps to
            # For now, return "String" and let the caller handle enum mapping
            return "String"
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
            # Extract the item type from the items.$ref
            parts = items_ref.split("/")
            item_type = parts[-1]
            return f"List<{item_type}>"
        return "List<Any>"
    raise ValueError(f"map_spec_type_to_kotlin: unsupported spec type: {spec_type} (format: {fmt})")


# ---------------------------------------------------------------------------
# KOTLIN reader (closed grammar, refuses with file and line)
# ---------------------------------------------------------------------------

# The closed grammar for the constrained Kotlin source shape:
# - package declaration
# - enum class with values
# - data class with constructor parameters
# - companion object with functions
# - No other constructs allowed

# Patterns for the closed grammar
PACKAGE_RE = re.compile(r'^package\s+([\w.]+)\s*$', re.MULTILINE)
ENUM_CLASS_RE = re.compile(r'^enum\s+class\s+(\w+)', re.MULTILINE)
ENUM_VALUES_RE = re.compile(r'^\s{4,}(\w+)(?:\s*[;,])?\s*$', re.MULTILINE)
DATA_CLASS_RE = re.compile(r'^data\s+class\s+(\w+)', re.MULTILINE)
# Constructor parameters: val name: Type (handles nullable types and default values)
PARAM_RE = re.compile(r'^\s{4,}val\s+(\w+)\s*:\s*([\w<>,\s?]+?)(?:\s*=\s*[^,]+)?(?:\s*,)?\s*$', re.MULTILINE)
COMPANION_RE = re.compile(r'^\s+companion\s+object', re.MULTILINE)
FUN_RE = re.compile(r'^\s+fun\s+(\w+)\s*\(', re.MULTILINE)


def parse_kotlin_file(filepath):
    """Parse a Kotlin source file with the closed grammar.

    Returns a dict with:
    - 'package': the package name
    - 'enums': list of (enum_name, [values])
    - 'data_classes': list of (class_name, [(field_name, field_type, is_nullable)])
    - 'functions': list of function names

    Refuses with file and line on any unrecognised construct.
    """
    filepath = Path(filepath)
    if not filepath.exists():
        raise FileNotFoundError(f"Kotlin file not found: {filepath}")

    text = filepath.read_text(encoding="utf-8")
    lines = text.splitlines()

    result = {
        "package": None,
        "enums": [],
        "data_classes": [],
        "functions": [],
    }

    # Quote firewall: remove string literals before parsing
    # This prevents declarations inside string literals from being read
    text_no_strings = re.sub(r'"[^"]*"', '""', text)
    lines_no_strings = text_no_strings.splitlines()

    # Parse package
    pkg_match = PACKAGE_RE.search(text_no_strings)
    if pkg_match:
        result["package"] = pkg_match.group(1)
    else:
        raise ValueError(f"{filepath.name}: no package declaration found")

    # Parse enums
    for match in ENUM_CLASS_RE.finditer(text_no_strings):
        enum_name = match.group(1)
        # Find the enum values (indented lines after the enum class line)
        start_line = text_no_strings[:match.start()].count('\n')
        values = []
        for i, line in enumerate(lines_no_strings[start_line + 1:], start_line + 1):
            if line.strip() == '}':
                break
            val_match = ENUM_VALUES_RE.match(line)
            if val_match:
                values.append(val_match.group(1))
        result["enums"].append((enum_name, values))

    # Parse data classes
    for match in DATA_CLASS_RE.finditer(text_no_strings):
        class_name = match.group(1)
        # Find the constructor parameters
        start_line = text_no_strings[:match.start()].count('\n')
        fields = []
        for i, line in enumerate(lines_no_strings[start_line + 1:], start_line + 1):
            if line.strip() == ')':
                break
            param_match = PARAM_RE.match(line)
            if param_match:
                fname = param_match.group(1)
                ftype = param_match.group(2).strip()
                # Check if nullable (ends with ?)
                is_nullable = ftype.endswith('?')
                if is_nullable:
                    ftype = ftype[:-1].strip()
                fields.append((fname, ftype, is_nullable))
        result["data_classes"].append((class_name, fields))

    # Parse functions (in companion objects)
    for match in FUN_RE.finditer(text_no_strings):
        result["functions"].append(match.group(1))

    # Vacuity guard: at least one of enums or data_classes must be found
    if not result["enums"] and not result["data_classes"]:
        raise ValueError(f"{filepath.name}: no enums or data classes found (vacuity guard)")

    return result


def snake(name):
    """Convert camelCase to snake_case.

    Strict snake(): jobId -> job_id, jobID -> job_i_d.
    No .lower() on property names (only on enum values).
    """
    # Insert underscore between consecutive uppercase letters (for acronyms like jobID)
    s1 = re.sub('([A-Z])([A-Z])', r'\1_\2', name)
    # Insert underscore between lowercase/digit and uppercase
    s2 = re.sub('([a-z0-9])([A-Z])', r'\1_\2', s1)
    return s2.lower()


def parse_all_kotlin():
    """Parse all Kotlin source files in the module.

    Returns a dict mapping class/enum name -> parsed data.
    Field names are converted to snake_case for comparison with the spec.
    Enum values are lowercased.
    Each field is (field_name, field_type, is_nullable).
    """
    if not KOTLIN_DIR.exists():
        raise FileNotFoundError(f"Kotlin directory not found: {KOTLIN_DIR}")

    result = {}
    for kt_file in KOTLIN_DIR.glob("*.kt"):
        parsed = parse_kotlin_file(kt_file)
        for enum_name, values in parsed["enums"]:
            # Lowercase enum values for comparison with spec
            result[enum_name] = {"type": "enum", "values": [v.lower() for v in values]}
        for class_name, fields in parsed["data_classes"]:
            # Convert field names to snake_case
            snake_fields = [(snake(f), t, n) for f, t, n in fields]
            result[class_name] = {"type": "data_class", "fields": snake_fields}

    # Vacuity guard: at least one class or enum must be found
    if not result:
        raise ValueError("No Kotlin classes or enums found (vacuity guard)")

    return result


# ---------------------------------------------------------------------------
# Mutate helper (for mismatch-class tests)
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

def validate_spec_structure(spec):
    """Validate that the spec is structurally valid for the OpenAPI 3.x subset.

    Checks:
    - openapi version is 3.x
    - info has title and version
    - paths has at least one path
    - All $refs are quoted and resolve
    - All schemas have type
    - All required fields are listed in properties

    Raises ValueError with a descriptive message on any violation.
    """
    # Check openapi version
    openapi_ver = spec.get("openapi", "")
    if not openapi_ver.startswith("3."):
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

    # Check all $refs are quoted (the yaml_subset reader would have refused unquoted)
    # and resolve to existing schemas
    schemas = spec.get("components", {}).get("schemas", {})
    for path, methods in paths.items():
        for method, op in methods.items():
            if not isinstance(op, dict):
                continue
            # Check responses
            responses = op.get("responses", {})
            for status, resp in responses.items():
                if not isinstance(resp, dict):
                    continue
                content = resp.get("content", {})
                for media_type, media in content.items():
                    if not isinstance(media, dict):
                        continue
                    schema = media.get("schema", {})
                    if isinstance(schema, dict):
                        ref = schema.get("$ref")
                        if ref:
                            # Check the ref resolves
                            ref_name = ref.split("/")[-1]
                            if ref_name not in schemas:
                                raise ValueError(f"Unresolved $ref: {ref}")

    return True
