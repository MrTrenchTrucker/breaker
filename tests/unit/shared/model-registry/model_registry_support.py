"""Shared readers for the model-registry unit tests. Standard library only.

Two independent readers, plus one entry factory:

* the GENERATOR is imported from tools/ (the same way tools/yaml_subset.py is
  served to its own tests): the refusal tests exercise its checks, never a
  copy of them;
* the FIXTURE reader parses shared/modules/model-registry/fixtures/
  upstream-checksum-excerpt.txt strictly: the header pins its provenance
  (source, the full upstream file's size and sha256, the asset-id -> filename
  mapping, the fetch date) and the data lines are tab-separated
  filename <TAB> sha256, byte-exact copies of upstream;
* assert_sha256_matches_fixture is the entry-vs-fixture comparison, shared by
  the shipped fixture test and by the planted REDs so the two cannot drift;
* a_planted_entry builds a temporary models.yaml from the committed one with
  exactly one mutation, so a refusal test cannot pass because the edit it
  meant to make was never there (the edit is asserted against the real file).

Run: python3 -m unittest discover -s tests/unit/shared/model-registry
"""
import os
import re
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))))  # tests/unit/shared/model-registry -> repo root
TOOLS = os.path.join(ROOT, "tools")
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

import gen_model_registry  # noqa: E402

RegistryError = gen_model_registry.RegistryError
check_models = gen_model_registry.check_models

MODULE = os.path.join(ROOT, "shared", "modules", "model-registry")
MODELS_YAML = os.path.join(MODULE, "models.yaml")
FIXTURE = os.path.join(MODULE, "fixtures", "upstream-checksum-excerpt.txt")
GENERATED_KOTLIN = os.path.join(
    MODULE, "src", "main", "kotlin", "dev", "breaker", "shared", "models",
    "ModelRegistry.kt",
)

ASSET_ID_MAP = re.compile(
    r"^#\s+(\d+)\s+->\s+(\S+)\s*$",
)


def models_text():
    """The committed models.yaml, as text (the source of record)."""
    with open(MODELS_YAML, "r", encoding="utf-8") as fh:
        text = fh.read()
    if not text.strip():
        raise AssertionError("model_registry tests: models.yaml is empty")
    return text


def entry():
    """The single converted entry, as the generator would ship it."""
    entries = check_models(models_text())
    if len(entries) != 1:
        raise AssertionError(
            f"model_registry tests: models.yaml carries {len(entries)} "
            f"entries, slice 1 has exactly one")
    return entries[0]


def asset_id_of(url):
    """The url's last path segment — the release-asset id, which is the pin."""
    segs = url.rstrip("/").split("/")
    if not segs or not segs[-1].isdigit():
        raise AssertionError(f"model_registry tests: url ends without an asset id: {url}")
    return segs[-1]


def fixture_data():
    """The fixture, parsed strictly.

    Returns (asset_map, lines): asset_map maps asset id -> filename exactly as
    the header records it; lines is the list of (filename, sha256) data lines,
    in file order. The parse refuses an unreadable header or a malformed data
    line: a fixture the reader cannot hold is the same as a missing one.
    """
    with open(FIXTURE, "r", encoding="utf-8") as fh:
        text = fh.read()
    if not text.strip():
        raise AssertionError("model_registry tests: the fixture is empty")
    asset_map = {}
    lines = []
    for raw in text.splitlines():
        if raw.startswith("#"):
            m = ASSET_ID_MAP.match(raw)
            if m:
                asset_map[m.group(1)] = m.group(2)
            continue
        if not raw.strip():
            continue
        parts = raw.split("\t")
        if len(parts) != 2 or not re.match(r"^[0-9a-f]{64}$", parts[1]):
            raise AssertionError(
                "model_registry tests: malformed fixture data line (must be "
                f"filename<TAB>64-lowercase-hex): {raw!r}")
        lines.append((parts[0], parts[1]))
    if not asset_map:
        raise AssertionError(
            "model_registry tests: the fixture header names no asset id -> "
            "filename mapping")
    if not lines:
        raise AssertionError("model_registry tests: the fixture carries no data line")
    return asset_map, lines


def assert_sha256_matches_fixture(entries, asset_map, lines):
    """The one comparison between a registry entry and the upstream fixture:
    the entry's sha256 equals the single fixture line its asset id's filename
    names.

    Raises AssertionError naming the entry and the filename. The exactly-one
    guard is part of the comparison, not a separate test of it: a fixture
    carrying zero lines for the filename would make the check pass on nothing,
    and two would make it ambiguous which upstream line is meant. The shipped
    fixture test and the planted REDs both call this, so neither can drift
    from the check the other proves.
    """
    for e in entries:
        aid = asset_id_of(e["url"])
        filename = asset_map[aid]
        matches = [s for (f, s) in lines if f == filename]
        if len(matches) != 1:
            raise AssertionError(
                f"entry {e['id']!r}: expected exactly one fixture line for "
                f"{filename}, got {len(matches)}")
        if e["sha256"] != matches[0]:
            raise AssertionError(
                f"entry {e['id']!r} sha256 {e['sha256']} drifts from "
                f"upstream's published line for {filename}: {matches[0]}")


def planted_file(mutate, name="planted_models.yaml"):
    """Write `mutate(models_text())` to a temporary file and return its path.

    `mutate` takes the real text and returns the planted text. The caller is
    responsible for the mutation being what it claims (the tests assert the
    planted file differs from the committed one in exactly the way they say).
    """
    text = mutate(models_text())
    fd, path = tempfile.mkstemp(suffix=name)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path


def refuses(text, name="models.yaml"):
    """Run the generator's checks on `text`; return the refusal message, or
    raise if the text was ACCEPTED (the test then knows its plant did not
    stick)."""
    try:
        check_models(text, name=name)
    except RegistryError as err:
        return str(err)
    raise AssertionError(
        f"model_registry tests: the planted text was ACCEPTED; expected a "
        f"refusal\n{text}")
