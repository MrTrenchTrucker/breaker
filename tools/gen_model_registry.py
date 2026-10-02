"""tools/gen_model_registry.py — the model-registry generator (ADR-016).

Reads shared/modules/model-registry/models.yaml through tools/yaml_subset.py
(the standard-library reader, no third-party YAML), checks EVERY entry before
it writes anything, and renders the committed Kotlin registry
(shared/modules/model-registry/src/main/kotlin/dev/breaker/shared/models/
ModelRegistry.kt) deterministically: the same models.yaml always produces the
same bytes. The generated file is never hand-edited; edit models.yaml, rerun
this tool, commit both (ADR-016; no runtime YAML on the phone).

Run:  python3 tools/gen_model_registry.py

Every refusal names the entry and the line, e.g.:
    gen_model_registry: entry 'small' line 12: field 'sha256' is not 64 lowercase hex characters

Entry checks (all of them, before any write):
* required fields: id, family, params, size_mb, url, sha256, upstream_commit,
  tamper_verified, hosted — plus either license, or both license_name and
  license_link (notes is optional; anything else is an unknown field);
* id: lowercase alphanumerics, unique across the registry;
* family: sherpa-onnx or whisper;
* params: a positive integer with an M suffix (the parameter count rounded to
  the nearest million, e.g. 70M);
* size_mb: a positive integer;
* sha256: 64 lowercase hex; upstream_commit: 40 lowercase hex;
* tamper_verified and hosted: exactly true or false;
* url: https only, host exactly github.com or api.github.com, and the path
  ends in /releases/assets/<digits> with nothing after it (no query, no
  fragment, no trailing segment) — OR a URL naming a full 40-character
  commit. The asset id is the immutable pin (module card, Known Gotchas: the
  downloader resolves it via the GitHub API asset route); tags and "latest"
  are refused;
* license: an SPDX id (a single token), or, when absent, both license_name
  and license_link (an https URL).
"""
import os
import re
import sys
from urllib.parse import urlsplit

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

import yaml_subset  # noqa: E402

REPO_ROOT = os.path.dirname(_HERE)
MODULE_DIR = os.path.join(REPO_ROOT, "shared", "modules", "model-registry")
MODELS_YAML = os.path.join(MODULE_DIR, "models.yaml")
OUTPUT_KOTLIN = os.path.join(
    MODULE_DIR, "src", "main", "kotlin", "dev", "breaker", "shared", "models",
    "ModelRegistry.kt",
)

REQUIRED_FIELDS = (
    "id", "family", "params", "size_mb", "url", "sha256",
    "upstream_commit", "tamper_verified", "hosted",
)
OPTIONAL_FIELDS = ("license", "license_name", "license_link", "notes")
KNOWN_FIELDS = set(REQUIRED_FIELDS) | set(OPTIONAL_FIELDS)

HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")
ID_SHAPE = re.compile(r"^[a-z0-9]+$")
PARAMS_SHAPE = re.compile(r"^[1-9][0-9]*M$")
DIGITS = re.compile(r"^[1-9][0-9]*$")
ASSET_PATH = re.compile(r"^/.+/releases/assets/[0-9]+$")
COMMIT_PATH = re.compile(r"^/.+/commit/[0-9a-f]{40}$")
SPDX_SHAPE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.+-]*$")
FAMILIES = ("sherpa-onnx", "whisper")


class RegistryError(ValueError):
    """A models.yaml entry the generator refuses to ship."""


def _entry_spans(text):
    """(start, end) line spans (1-based, end exclusive) of the sequence items
    under the top-level `models:` key, in file order. Spans exist so a
    refusal can point at the entry it refuses."""
    lines = text.splitlines()
    models_at = None
    for i, line in enumerate(lines, start=1):
        if line == "models:":
            models_at = i
            break
    if models_at is None:
        raise RegistryError("the document has no top-level `models:` key")
    starts = [
        i + 1 for i in range(models_at - 1, len(lines))
        if re.match(r"^\s*-\s+(\S|$)", lines[i])
    ]
    return [(s, e) for s, e in zip(starts, starts[1:] + [len(lines) + 1])]


def _field_line(lines, span, field):
    """Line of `field:` inside the entry span; the span start when absent."""
    start, end = span
    for i in range(start, end):
        if re.match(rf"^\s*{field}\s*:", lines[i - 1]):
            return i
    return start


def _check_url(url, line, label=""):
    parts = urlsplit(url)
    if parts.scheme != "https":
        raise RegistryError(
            f"{label}line {line}: url must be https (got scheme "
            f"'{parts.scheme or 'none'}')")
    if parts.hostname not in ("github.com", "api.github.com"):
        raise RegistryError(
            f"{label}line {line}: url host must be exactly github.com or "
            f"api.github.com (got '{parts.hostname or 'none'}')")
    if parts.query or parts.fragment:
        raise RegistryError(
            f"{label}line {line}: url must not carry a query or fragment")
    if ASSET_PATH.match(parts.path) or COMMIT_PATH.match(parts.path):
        return
    raise RegistryError(
        f"{label}line {line}: url path must end in /releases/assets/<asset id> or "
        f"name a full 40-character commit — tags and 'latest' are not "
        f"pinned (got '{parts.path}')")


def _check_entry(raw, span, lines):
    """Validate one parsed entry (a string->string map) and return the
    converted entry (ints and booleans materialised)."""
    start, _ = span
    keys = set(raw)

    for f in REQUIRED_FIELDS:
        if f not in keys:
            raise RegistryError(f"line {start}: required field '{f}' is missing")
    for k in sorted(keys - KNOWN_FIELDS):
        raise RegistryError(
            f"line {_field_line(lines, span, k)}: unknown field '{k}' "
            f"(known: {', '.join(sorted(KNOWN_FIELDS))})")

    def value(field):
        v = raw[field]
        if not isinstance(v, str) or not v:
            raise RegistryError(
                f"line {_field_line(lines, span, field)}: field '{field}' "
                f"must be a non-empty scalar")
        return v

    eid = value("id")
    if not ID_SHAPE.match(eid):
        raise RegistryError(
            f"line {start}: id '{eid}' must be "
            f"lowercase alphanumerics")
    label = f"entry '{eid}' "
    family = value("family")
    if family not in FAMILIES:
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'family')}: family '{family}' "
            f"is not one of {', '.join(FAMILIES)}")
    params = value("params")
    if not PARAMS_SHAPE.match(params):
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'params')}: params '{params}' "
            f"must be a positive integer with an M suffix (e.g. 70M)")
    size_mb = value("size_mb")
    if not DIGITS.match(size_mb):
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'size_mb')}: size_mb '{size_mb}' "
            f"must be a positive integer")
    url = value("url")
    _check_url(url, _field_line(lines, span, "url"), label)
    sha = value("sha256")
    if not HEX64.match(sha):
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'sha256')}: sha256 is not 64 "
            f"lowercase hex characters")
    commit = value("upstream_commit")
    if not HEX40.match(commit):
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'upstream_commit')}: "
            f"upstream_commit is not 40 lowercase hex characters")
    for field in ("tamper_verified", "hosted"):
        v = value(field)
        if v not in ("true", "false"):
            raise RegistryError(
                f"{label}line {_field_line(lines, span, field)}: field '{field}' "
                f"must be exactly true or false (got '{v}')")
    if "license" in keys:
        lic = value("license")
        if not SPDX_SHAPE.match(lic):
            raise RegistryError(
                f"{label}line {_field_line(lines, span, 'license')}: license "
                f"'{lic}' is not a single-token SPDX id")
    else:
        for field in ("license_name", "license_link"):
            if field not in keys:
                raise RegistryError(
                    f"{label}line {start}: the entry has no 'license' SPDX id, so "
                    f"'{field}' is required")
        if not value("license_name").strip():
            raise RegistryError(
                f"{label}line {_field_line(lines, span, 'license_name')}: "
                f"license_name must not be empty")
        link = value("license_link")
        if not link.startswith("https://"):
            raise RegistryError(
                f"{label}line {_field_line(lines, span, 'license_link')}: "
                f"license_link must be an https URL")
    if "notes" in keys:
        value("notes")

    return {
        "id": eid,
        "family": family,
        "params": params,
        "size_mb": int(size_mb),
        "url": url,
        "sha256": sha,
        "upstream_commit": commit,
        "tamper_verified": raw["tamper_verified"] == "true",
        "hosted": raw["hosted"] == "true",
        "licence": raw.get("license") or raw.get("license_name", ""),
    }


def check_models(text, name="models.yaml"):
    """Parse `text` with the stdlib reader and validate EVERY entry before
    anything is returned. Returns the converted entries in file order; raises
    RegistryError (message: `entry '<id>' line <n>: <reason>`)."""
    try:
        doc = yaml_subset.loads(text, name=name)
    except yaml_subset.YamlSubsetError as err:
        raise RegistryError(str(err)) from None
    if not isinstance(doc, dict) or "models" not in doc:
        raise RegistryError("the document must be a mapping with a 'models' key")
    entries = doc["models"]
    if not isinstance(entries, list) or not entries:
        raise RegistryError("'models' must be a non-empty sequence")
    lines = text.splitlines()
    spans = _entry_spans(text)
    if len(spans) != len(entries):
        raise RegistryError(
            f"the reader found {len(entries)} entries but the file carries "
            f"{len(spans)} sequence items under `models:`")
    seen = {}
    out = []
    for raw, span in zip(entries, spans):
        entry = _check_entry(raw, span, lines)
        if entry["id"] in seen:
            raise RegistryError(
                f"entry '{entry['id']}' line {span[0]}: duplicate id '{entry['id']}' (first on line {seen[entry['id']]})")
        seen[entry["id"]] = span[0]
        out.append(entry)
    return out


def _kotlin_constant(eid):
    return re.sub(r"(?<!^)([a-z])([A-Z])", r"\1_\2", eid).upper()


def _kotlin_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render_kotlin(entries):
    """The committed Kotlin, rendered from converted entries. Deterministic:
    same entries -> same bytes."""
    lines = [
        "// Generated by tools/gen_model_registry.py from models.yaml —",
        "// do not edit by hand. Edit models.yaml and rerun the generator.",
        "// (ADR-016: the app reads these constants; nothing parses YAML at runtime.)",
        "package dev.breaker.shared.models",
        "",
        "enum class ModelFamily {",
        "    SHERPA_ONNX,",
        "    WHISPER,",
        "}",
        "",
        "data class ModelEntry(",
        "    val id: String,",
        "    val family: ModelFamily,",
        "    val url: String,",
        "    val sha256: String,",
        "    val sizeMb: Int,",
        "    val licence: String,",
        "    val hosted: Boolean,",
        ")",
        "",
        "object ModelRegistry {",
    ]
    for i, e in enumerate(entries):
        comma = "," if i < len(entries) - 1 else ""
        fam = "SHERPA_ONNX" if e["family"] == "sherpa-onnx" else "WHISPER"
        lines += [
            f"    val {_kotlin_constant(e['id'])}: ModelEntry = ModelEntry(",
            f"        id = {_kotlin_str(e['id'])},",
            f"        family = ModelFamily.{fam},",
            f"        url = {_kotlin_str(e['url'])},",
            f"        sha256 = {_kotlin_str(e['sha256'])},",
            f"        sizeMb = {e['size_mb']},",
            f"        licence = {_kotlin_str(e['licence'])},",
            f"        hosted = {str(e['hosted']).lower()},",
            f"    ){comma}",
            "",
        ]
    names = ", ".join(_kotlin_constant(e["id"]) for e in entries)
    lines += [
        "    val ALL: List<ModelEntry> = listOf(" + names + ")",
        "",
        "    fun byId(id: String): ModelEntry? = ALL.firstOrNull { it.id == id }",
        "}",
        "",
    ]
    return "\n".join(lines)


def generate(models_yaml_path=MODELS_YAML, output_path=OUTPUT_KOTLIN):
    """Check every entry, then write the Kotlin. Returns (entries, output_path).
    Nothing is written until every entry passes."""
    with open(models_yaml_path, "r", encoding="utf-8") as fh:
        text = fh.read()
    entries = check_models(text, name=os.path.basename(models_yaml_path))
    kotlin = render_kotlin(entries)
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(kotlin)
    return entries, output_path


def main():
    try:
        entries, path = generate()
    except RegistryError as err:
        print(f"gen_model_registry: {err}", file=sys.stderr)
        return 1
    print(f"gen_model_registry: {len(entries)} entr"
          f"{'y' if len(entries) == 1 else 'ies'} -> {os.path.relpath(path, REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
