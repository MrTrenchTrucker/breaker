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
  tamper_verified, hosted, archive_name — plus either license, or both license_name and
  license_link (notes is optional; anything else is an unknown field);
* id: lowercase alphanumerics starting with a letter, unique across the
  registry (the generated Kotlin names a `val` after it, and a Kotlin
  identifier may not start with a digit);
* family: sherpa-onnx or whisper;
* params: a positive integer with an M suffix (the parameter count rounded to
  the nearest million, e.g. 70M);
* size_mb: a positive integer in MiB, rounded. For the registry's `small`
  entry, 365,748,162 is the byte size of release asset 191972150 from that
  asset's GitHub release metadata, and 191972150 is the asset id in that
  entry's own url (348.8 MiB = 349; in decimal MB it would be 366). Note the
  upstream checksum.txt that sha256 is checked against is itself 57,685 bytes -
  a different file from the model asset;
* sha256: 64 lowercase hex; upstream_commit: 40 lowercase hex;
* tamper_verified and hosted: exactly true or false;
* url: https only, no port and no userinfo (a port sends the download off
  the host every accepted route is served on, and userinfo is a credential
  that reaches the generated Kotlin, the logs and anything that prints the
  url), host exactly github.com or api.github.com, and either a
  path ending in /releases/assets/<digits> with nothing after it (no query,
  no fragment, no trailing segment) — and then the host must be
  api.github.com, because GitHub's web asset-id route returns 404 — or a URL
  naming a full 40-character commit. The asset id is the immutable pin
  (module card, Known Gotchas: the downloader resolves it via the GitHub API
  asset route); tags and "latest" are refused;
* license: an SPDX id (a single token), or, when absent, both license_name
  and license_link (an https URL);
* archive_name: present, a non-empty plain file name (no `/` or `\\` anywhere),
  and its (archive_name, sha256) pair a line of the committed fixture
  fixtures/upstream-checksum-excerpt.txt - the byte-exact upstream
  checksum.txt excerpt the module carries (checked offline, no network).

A second top-level list, `keyword_models:` (optional), holds keyword-spotting
models. Its entries obey every rule above, with three differences: the id may
hold single hyphens between alphanumerics (the generated constant turns them
into underscores), params may carry a decimal point (3.3M: the number as the
model's own name states it), and upstream_commit is optional (checked when
given). An id may not appear in both lists, and no id may name a constant the
generated object already declares (ALL, KEYWORD_MODELS). The generated
`ModelRegistry.ALL` and `byId` stay speech only; `KEYWORD_MODELS` and
`byKeywordId` answer for the keyword list only.
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
FIXTURE = os.path.join(
    MODULE_DIR, "fixtures", "upstream-checksum-excerpt.txt")

REQUIRED_FIELDS = (
    "id", "family", "params", "size_mb", "url", "sha256",
    "upstream_commit", "tamper_verified", "hosted", "archive_name",
)
OPTIONAL_FIELDS = ("license", "license_name", "license_link", "notes")
KNOWN_FIELDS = set(REQUIRED_FIELDS) | set(OPTIONAL_FIELDS)

HEX64 = re.compile(r"^[0-9a-f]{64}$")
HEX40 = re.compile(r"^[0-9a-f]{40}$")
# An id becomes a Kotlin val name (`val SMALL`), so it starts with a letter.
ID_SHAPE = re.compile(r"^[a-z][a-z0-9]*$")
PARAMS_SHAPE = re.compile(r"^[1-9][0-9]*M$")
# A keyword model's id may hold single hyphens; its params may carry a point.
KEYWORD_ID_SHAPE = re.compile(r"^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
KEYWORD_PARAMS_SHAPE = re.compile(r"^[1-9][0-9]*(\.[0-9]+)?M$")
KEYWORD_OPTIONAL = ("upstream_commit",)
# Constants the generated object declares itself: an id may not name them.
RESERVED_CONSTANTS = ("ALL", "KEYWORD_MODELS")
TOP_KEY = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*:")
DIGITS = re.compile(r"^[1-9][0-9]*$")
ASSET_PATH = re.compile(r"^/.+/releases/assets/[0-9]+$")
# The only host that serves an asset id's bytes (the web route 404s).
ASSET_HOST = "api.github.com"
COMMIT_PATH = re.compile(r"^/.+/commit/[0-9a-f]{40}$")
SPDX_SHAPE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.+-]*$")
FAMILIES = ("sherpa-onnx", "whisper")


class RegistryError(ValueError):
    """A models.yaml entry the generator refuses to ship."""


BLOCK_SCALAR = re.compile(r"^(\s*)\S[^:]*:\s*[|>][0-9+-]*\s*$")
ITEM = re.compile(r"^\s*-\s+(\S|$)")


def _entry_spans(text, key="models"):
    """(start, end) line spans (1-based, end exclusive) of the sequence items
    under the top-level `<key>:` key, in file order, up to the next top-level
    key. Spans exist so a refusal can point at the entry it refuses.

    Body lines of a block scalar (`notes: >-`) are free text, not sequence
    items: a note that starts with "- " must not be counted as an entry, or a
    valid file is refused for carrying one item too many."""
    lines = text.splitlines()
    models_at = None
    for i, line in enumerate(lines, start=1):
        if line == f"{key}:":
            models_at = i
            break
    if models_at is None:
        raise RegistryError(f"the document has no top-level `{key}:` key")
    stop = len(lines)
    for i in range(models_at, len(lines)):
        if TOP_KEY.match(lines[i]):
            stop = i
            break
    starts = []
    block_indent = None
    for i in range(models_at - 1, stop):
        line = lines[i]
        if block_indent is not None:
            if not line.strip() or len(line) - len(line.lstrip()) > block_indent:
                continue  # a body line of the scalar open above
            block_indent = None
        scalar = BLOCK_SCALAR.match(line)
        if scalar:
            block_indent = len(scalar.group(1))
            continue
        if ITEM.match(line):
            starts.append(i + 1)
    return [(s, e) for s, e in zip(starts, starts[1:] + [stop + 1])]

def _fixture_pairs():
    """The (archive_name, sha256) data lines of the committed fixture, in file
    order: the offline, byte-exact copy of the upstream checksum.txt lines
    (the module's provenance record, module card Invariants). The generator
    reads the fixture file itself, strictly; a fixture it cannot hold is the
    same as a missing one."""
    try:
        with open(FIXTURE, "r", encoding="utf-8") as fh:
            text = fh.read()
    except OSError as err:
        raise RegistryError(
            f"the fixture cannot be read: {err}") from None
    if not text.strip():
        raise RegistryError("the fixture is empty")
    pairs = []
    for raw in text.splitlines():
        if raw.startswith("#") or not raw.strip():
            continue
        parts = raw.split("\t")
        if len(parts) != 2 or not HEX64.match(parts[1]):
            raise RegistryError(
                "the fixture carries a malformed data line (must be "
                f"filename<TAB>64-lowercase-hex): {raw!r}")
        pairs.append((parts[0], parts[1]))
    if not pairs:
        raise RegistryError("the fixture carries no data line")
    return pairs


def _field_line(lines, span, field):
    """Line of `field:` inside the entry span; the span start when absent.
    A body line of a block scalar (a `notes: >-` block) is prose: a wrapped
    line that happens to read like `field:` is not the field."""
    start, end = span
    block_indent = None
    for i in range(start, end):
        line = lines[i - 1]
        if block_indent is not None:
            if not line.strip() or len(line) - len(line.lstrip()) > block_indent:
                continue  # a body line of the scalar open above
            block_indent = None
        scalar = BLOCK_SCALAR.match(line)
        if scalar:
            block_indent = len(scalar.group(1))
            continue
        if re.match(rf"^\s*{field}\s*:", line):
            return i
    return start


def _where(raw, span, lines, field=None):
    """The `entry '<id>' line <n>` prefix a refusal carries: the entry named by
    its id when the id is a usable non-empty scalar, and by a line otherwise
    (an absent or empty id is itself the thing being refused, so there is no
    id to name it by). When the refusal is about a named field and the id is
    unusable, the line is that field's line - the entry's sequence marker when
    the field has none of its own (it is absent); otherwise the entry's
    sequence marker."""
    eid = raw.get("id")
    if isinstance(eid, str) and eid:
        line = _field_line(lines, span, field) if field else span[0]
        return f"entry '{eid}' line {line}"
    if field:
        return f"entry at line {_field_line(lines, span, field)}"
    return f"entry at line {span[0]}"


def _check_url(url, line, label=""):
    parts = urlsplit(url)
    if parts.scheme != "https":
        raise RegistryError(
            f"{label}line {line}: url must be https (got scheme "
            f"'{parts.scheme or 'none'}')")
    if parts.username is not None or parts.password is not None:
        # A credential in a url reaches the generated Kotlin, the logs and
        # anything that prints the url. Naming WHICH part was present is
        # diagnostic; naming its VALUE would copy the secret into every log
        # that ever prints this refusal -- more exposure than the url itself.
        which = "a username" if parts.password is None else "a username and password"
        raise RegistryError(
            f"{label}line {line}: url must not carry userinfo "
            f"(got {which})")
    try:
        port = parts.port
    except ValueError:
        # a non-numeric port is a malformed authority, not a host we can serve
        port = "not a number"
    if port is not None:
        # a port sends the download off the https default, away from the host
        # every accepted route is served on
        raise RegistryError(
            f"{label}line {line}: url must not carry a port "
            f"(got ':{port}')")
    if parts.hostname not in ("github.com", "api.github.com"):
        raise RegistryError(
            f"{label}line {line}: url host must be exactly github.com or "
            f"api.github.com (got '{parts.hostname or 'none'}')")
    if parts.query or parts.fragment:
        raise RegistryError(
            f"{label}line {line}: url must not carry a query or fragment")
    if ASSET_PATH.match(parts.path):
        # Host and path shape are one rule, not two: the web asset route
        # 404s, so an asset id is only downloadable from the API host.
        if parts.hostname != ASSET_HOST:
            raise RegistryError(
                f"{label}line {line}: an /releases/assets/<asset id> url is "
                f"served only by {ASSET_HOST} (GitHub's web asset route "
                f"returns 404; got host '{parts.hostname}')")
        return
    if COMMIT_PATH.match(parts.path):
        return
    raise RegistryError(
        f"{label}line {line}: url path must end in /releases/assets/<asset id> or "
        f"name a full 40-character commit — tags and 'latest' are not "
        f"pinned (got '{parts.path}')")


def _check_entry(raw, span, lines, keyword=False):
    """Validate one parsed entry (a string->string map) and return the
    converted entry (ints and booleans materialised). `keyword` selects the
    keyword-model variant of the id, params and upstream_commit rules."""
    start, _ = span
    keys = set(raw)

    for f in REQUIRED_FIELDS:
        if f not in keys and not (keyword and f in KEYWORD_OPTIONAL):
            raise RegistryError(
                f"{_where(raw, span, lines)}: required field '{f}' is missing")
    for k in sorted(keys - KNOWN_FIELDS):
        raise RegistryError(
            f"{_where(raw, span, lines, k)}: unknown field '{k}' "
            f"(known: {', '.join(sorted(KNOWN_FIELDS))})")

    def value(field):
        v = raw[field]
        if not isinstance(v, str) or not v:
            raise RegistryError(
                f"{_where(raw, span, lines, field)}: field '{field}' "
                f"must be a non-empty scalar")
        return v

    eid = value("id")
    label = f"entry '{eid}' "
    if not (KEYWORD_ID_SHAPE if keyword else ID_SHAPE).match(eid):
        hyphens = (", with single hyphens allowed between them"
                   if keyword else "")
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'id')}: id '{eid}' must be "
            f"lowercase alphanumerics starting with a letter{hyphens} (the "
            f"generated Kotlin names a val after it)")
    if _kotlin_constant(eid) in RESERVED_CONSTANTS:
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'id')}: id '{eid}' would "
            f"name the constant {_kotlin_constant(eid)}, which the generated "
            f"object already declares")
    family = value("family")
    if family not in FAMILIES:
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'family')}: family '{family}' "
            f"is not one of {', '.join(FAMILIES)}")
    params = value("params")
    if keyword:
        if not KEYWORD_PARAMS_SHAPE.match(params):
            raise RegistryError(
                f"{label}line {_field_line(lines, span, 'params')}: params "
                f"'{params}' must be a positive number with an M suffix "
                f"(e.g. 3.3M)")
    elif not PARAMS_SHAPE.match(params):
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
    commit = value("upstream_commit") if "upstream_commit" in keys else ""
    if (commit or not keyword) and not HEX40.match(commit):
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
    # The archive-name checks run after the entry's own field checks, so an
    # entry that already fails one of them keeps that first refusal.
    archive = value("archive_name")
    if "/" in archive or "\\" in archive:
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'archive_name')}: "
            f"archive_name must be a plain file name without a path separator "
            f"(got '{archive}')")
    if (archive, sha) not in _fixture_pairs():
        raise RegistryError(
            f"{label}line {_field_line(lines, span, 'archive_name')}: "
            f"archive_name '{archive}' does not pair with the entry's sha256 "
            f"in the upstream fixture")

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
        "archive_name": archive,
    }


def _check_list(doc, text, lines, key, keyword, speech_lines=None):
    """Validate every entry of the top-level list `key`; returns the
    converted entries in file order and a map of id -> id line. For the
    keyword list `speech_lines` is the speech list's id -> line map, and an
    id found there is refused (the lists never share an id)."""
    entries = doc[key]
    if not isinstance(entries, list) or not entries:
        raise RegistryError(f"'{key}' must be a non-empty sequence")
    spans = _entry_spans(text, key)
    if len(spans) != len(entries):
        raise RegistryError(
            f"the reader found {len(entries)} entries but the file carries "
            f"{len(spans)} sequence items under `{key}:`")
    seen = {}
    out = []
    for raw, span in zip(entries, spans):
        entry = _check_entry(raw, span, lines, keyword)
        id_line = _field_line(lines, span, "id")
        if speech_lines and entry["id"] in speech_lines:
            raise RegistryError(
                f"entry '{entry['id']}' line {id_line}: id '{entry['id']}' is "
                f"also the id of a speech model (speech entry on line "
                f"{speech_lines[entry['id']]}); the two lists must not share "
                f"an id")
        if entry["id"] in seen:
            # both halves name the line the id is ON: an entry need not open
            # with `id:`, so the sequence marker is not the id line
            raise RegistryError(
                f"entry '{entry['id']}' line {id_line}: duplicate id "
                f"'{entry['id']}' (first on line {seen[entry['id']]})")
        seen[entry["id"]] = id_line
        out.append(entry)
    return out, seen


def check_registry(text, name="models.yaml"):
    """Parse `text` with the stdlib reader and validate EVERY entry of both
    lists before anything is returned. Returns (speech entries, keyword
    entries), each in file order; the keyword list is empty when the optional
    `keyword_models:` key is absent. Raises RegistryError (message: `entry
    '<id>' line <n>: <reason>`)."""
    try:
        doc = yaml_subset.loads(text, name=name)
    except yaml_subset.YamlSubsetError as err:
        raise RegistryError(str(err)) from None
    if not isinstance(doc, dict) or "models" not in doc:
        raise RegistryError("the document must be a mapping with a 'models' key")
    lines = text.splitlines()
    speech, speech_lines = _check_list(doc, text, lines, "models", False)
    if "keyword_models" not in doc:
        return speech, []
    keyword, _ = _check_list(
        doc, text, lines, "keyword_models", True, speech_lines)
    return speech, keyword


def check_models(text, name="models.yaml"):
    """The speech entries of `check_registry` (both lists are validated)."""
    return check_registry(text, name)[0]


def _kotlin_constant(eid):
    return re.sub(r"(?<!^)([a-z])([A-Z])", r"\1_\2", eid).upper().replace("-", "_")


def _kotlin_str(s):
    # Kotlin reads $ as a template start: a bare $ in a licence or a url owner
    # segment must reach the compiler as an escaped literal dollar.
    return ('"' + s.replace("\\", "\\\\").replace('"', '\\"')
            .replace("$", "\\$") + '"')


def render_kotlin(entries, keyword_entries=()):
    """The committed Kotlin, rendered from converted entries (speech first,
    then keyword models). Deterministic: same entries -> same bytes. `ALL` and
    `byId` name the speech entries only; `KEYWORD_MODELS` and `byKeywordId`
    the keyword entries only."""
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
        '    val archiveName: String = "",',
        ")",
        "",
        "object ModelRegistry {",
    ]
    for e in list(entries) + list(keyword_entries):
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
            f"        archiveName = {_kotlin_str(e['archive_name'])},",
            "    )",
            "",
        ]
    names = ", ".join(_kotlin_constant(e["id"]) for e in entries)
    kw_names = ", ".join(_kotlin_constant(e["id"]) for e in keyword_entries)
    kw_list = f"listOf({kw_names})" if kw_names else "emptyList()"
    lines += [
        "    val ALL: List<ModelEntry> = listOf(" + names + ")",
        "",
        "    val KEYWORD_MODELS: List<ModelEntry> = " + kw_list,
        "",
        "    fun byId(id: String): ModelEntry? = ALL.firstOrNull { it.id == id }",
        "",
        "    fun byKeywordId(id: String): ModelEntry? = "
        "KEYWORD_MODELS.firstOrNull { it.id == id }",
        "}",
        "",
    ]
    return "\n".join(lines)


def generate(models_yaml_path=MODELS_YAML, output_path=OUTPUT_KOTLIN):
    """Check every entry, then write the Kotlin. Returns (speech entries,
    output_path). Nothing is written until every entry passes."""
    with open(models_yaml_path, "r", encoding="utf-8") as fh:
        text = fh.read()
    entries, keyword = check_registry(
        text, name=os.path.basename(models_yaml_path))
    kotlin = render_kotlin(entries, keyword)
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
