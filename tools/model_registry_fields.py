"""tools/model_registry_fields.py - the pin and presentation fields of the model
registry, split out of tools/gen_model_registry.py (ADR-016, ADR-050).

Standard library only. Three jobs:

* check the fields every entry now carries: `size_bytes` and `asset_id` (our
  own pin: the byte size and the release-asset id of the archive), and, for the
  speech list only, `kind` (streaming | offline), `offered`, and the optional
  `label` and `rating` the screens show, and the engine fields of
  tools/model_registry_engine.py;
* check that an asset url names the same release asset as `asset_id`, so the
  entry cannot hold two answers for one fact (a commit url has no asset id to
  compare); the MiB rounding of `size_bytes` against `size_mb` is a unit test,
  not a generator rule;
* render those fields as Kotlin lines, and decide whether an archive may have
  no line in the upstream checksum fixture.

Not built here: nothing reads `label`, `rating`, `kind` or `offered` yet outside
the registry and its tests; the first-run and settings screens that show them,
and the recogniser that `kind` selects, are other modules' work.
"""
import re
from urllib.parse import urlsplit

import model_registry_engine as engine

DIGITS = re.compile(r"^[1-9][0-9]*$")
ASSET_PATH = re.compile(r"^/.+/releases/assets/([0-9]+)$")
RATING_SHAPE = re.compile(r"^[0-3]$")
KINDS = ("streaming", "offline")

# Fields only a speech entry may carry; a keyword entry holding one is refused
# as an unknown field (a keyword spotter has no kind, label, rating or offer).
SPEECH_ONLY_FIELDS = (("kind", "label", "rating", "offered")
                      + engine.ENGINE_FIELDS)
# Present in every entry of both lists.
PIN_FIELDS = ("size_bytes", "asset_id")
# Required in a speech entry on top of PIN_FIELDS; label and rating are optional,
# and the other engine fields are checked in tools/model_registry_engine.py.
SPEECH_REQUIRED = ("kind", "offered", "model_type")

# Archives that upstream's checksum.txt does not list. Such an archive may have
# no fixture line; our own pin is the only check on it. This is an allow-list of
# names, and it stops applying the moment the fixture does carry a line for the
# name: then the line must pair like any other.
NO_UPSTREAM_LINE = frozenset({
    "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
})


class RegistryError(ValueError):
    """A models.yaml entry the generator refuses to ship."""


def archive_pair_ok(archive, sha, pairs):
    """True when (archive, sha) may ship against the fixture `pairs`: it is a
    fixture line, or the archive is on NO_UPSTREAM_LINE and the fixture holds
    no line at all for that name."""
    if (archive, sha) in pairs:
        return True
    return archive in NO_UPSTREAM_LINE and not any(a == archive for a, _ in pairs)


def check_pin_fields(raw, keyword, label, line_of, value, url):
    """Validate the new fields of one entry; return their converted values.

    `raw` is the parsed entry, `label` the `entry '<id>' ` refusal prefix,
    `line_of(field)` the line a field sits on, `value(field)` the generator's
    non-empty-scalar reader, and `url` the already-checked url.
    Raises RegistryError."""
    out = {}
    if keyword:
        for f in SPEECH_ONLY_FIELDS:
            if f in raw:
                raise RegistryError(
                    f"{label}line {line_of(f)}: field '{f}' belongs to a "
                    f"speech entry only (a keyword entry has no {f})")
    size_bytes = value("size_bytes")
    if not DIGITS.match(size_bytes):
        raise RegistryError(
            f"{label}line {line_of('size_bytes')}: size_bytes '{size_bytes}' "
            f"must be a positive integer (the archive's byte count)")
    out["size_bytes"] = int(size_bytes)
    asset_id = value("asset_id")
    if not DIGITS.match(asset_id):
        raise RegistryError(
            f"{label}line {line_of('asset_id')}: asset_id '{asset_id}' must "
            f"be a positive integer (the release-asset id)")
    out["asset_id"] = int(asset_id)
    m = ASSET_PATH.match(urlsplit(url).path)
    # A commit url names no release asset, so there is no asset id in it to
    # compare; only an asset url is held to asset_id.
    if m is not None and m.group(1) != asset_id:
        raise RegistryError(
            f"{label}line {line_of('asset_id')}: asset_id {asset_id} differs "
            f"from the asset the url names ({m.group(1)})")
    if keyword:
        return out
    kind = value("kind")
    if kind not in KINDS:
        raise RegistryError(
            f"{label}line {line_of('kind')}: kind '{kind}' is not one of "
            f"{', '.join(KINDS)}")
    out["kind"] = kind
    offered = value("offered")
    if offered not in ("true", "false"):
        raise RegistryError(
            f"{label}line {line_of('offered')}: field 'offered' must be "
            f"exactly true or false (got '{offered}')")
    out["offered"] = offered == "true"
    out["label"] = None
    if "label" in raw:
        text = value("label")
        if not text.strip() or text != text.strip():
            raise RegistryError(
                f"{label}line {line_of('label')}: label must not be empty or "
                f"carry leading or trailing spaces")
        out["label"] = text
    out["rating"] = None
    if "rating" in raw:
        rating = value("rating")
        if not RATING_SHAPE.match(rating):
            raise RegistryError(
                f"{label}line {line_of('rating')}: rating '{rating}' must be "
                f"a whole number from 0 to 3")
        out["rating"] = int(rating)
    if out["offered"] and out["label"] is None:
        raise RegistryError(
            f"{label}line {line_of('offered')}: an offered entry needs a label")
    if out["offered"] and out["rating"] is None:
        raise RegistryError(
            f"{label}line {line_of('offered')}: an offered entry needs a rating")
    try:
        engine.check_engine_fields(raw, out, label, line_of)
    except engine.EngineFieldError as err:
        raise RegistryError(str(err)) from None
    return out


def render_fields(e, kotlin_str):
    """The Kotlin argument lines for the new fields of one converted entry
    (8 spaces of indent, trailing comma). Keyword entries carry only the pin."""
    lines = []
    if "kind" in e:
        lines.append(f"        kind = ModelKind.{e['kind'].upper()},")
    lines.append(f"        sizeBytes = {e['size_bytes']}L,")
    lines.append(f"        assetId = {e['asset_id']}L,")
    if e.get("label") is not None:
        lines.append(f"        label = {kotlin_str(e['label'])},")
    if e.get("rating") is not None:
        lines.append(f"        rating = {e['rating']},")
    if "offered" in e:
        lines.append(f"        offered = {str(e['offered']).lower()},")
    lines += engine.render_engine_fields(e, kotlin_str)
    return lines
