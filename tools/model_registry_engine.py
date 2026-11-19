"""tools/model_registry_engine.py - the engine fields of a speech entry in the
model registry (ADR-050 J: models are data), split out of
tools/model_registry_fields.py.

Per speech entry, models.yaml now says everything the engine needs, so a new
model of a supported kind is a registry entry and its pin with no app code:

* `model_type`: the sherpa-onnx model-type string ('' for a streaming
  transducer, nemo_transducer for the offline NeMo transducer, whisper for the
  unsupported whisper entries);
* `files_encoder`, `files_decoder`, `files_joiner`, `files_tokens`: the four
  file names inside the archive;
* `limit_entry_bytes`, `limit_written_bytes`, `limit_stream_bytes`,
  `limit_entries`, `limit_depth`: the extraction bounds;
* `language`: the transcript language code ('' = the model decides);
* `default`: true on exactly one offered entry;
* `notice`: optional plain text the About screen shows.

The rules live in check_engine_fields (per entry) and check_default_count (the
whole speech list). The set of supported (kind, model_type) pairs is
tools/model_types.py. Built: these checks and the Kotlin lines
(render_engine_fields). Not built: an engine path for any pair outside the
supported set; the app code that reads these fields is another module's work.

Standard library only.
"""
import re

import model_types

FILE_FIELDS = ("files_encoder", "files_decoder", "files_joiner", "files_tokens")
LIMIT_FIELDS = ("limit_entry_bytes", "limit_written_bytes", "limit_stream_bytes",
                "limit_entries", "limit_depth")
ENGINE_FIELDS = (("model_type", "language", "default", "notice")
                 + FILE_FIELDS + LIMIT_FIELDS)

DIGITS = re.compile(r"^[1-9][0-9]*$")
TYPE_SHAPE = re.compile(r"^([a-z][a-z0-9_]*)?$")
LANGUAGE_SHAPE = re.compile(r"^([a-z]{2,3}(-[A-Z]{2})?)?$")


class EngineFieldError(ValueError):
    """An engine field the generator refuses; gen_model_registry rewraps it."""


def _text(raw, field, label, line_of):
    """The raw scalar of `field` (the empty string is a legal value here)."""
    v = raw[field]
    if not isinstance(v, str):
        raise EngineFieldError(
            f"{label}line {line_of(field)}: field '{field}' must be a scalar")
    return v


def _check_files(raw, label, line_of):
    out = {}
    for f in FILE_FIELDS:
        name = _text(raw, f, label, line_of)
        if not name.strip() or name != name.strip():
            raise EngineFieldError(
                f"{label}line {line_of(f)}: {f} must be a non-empty file name "
                f"without leading or trailing spaces")
        if "/" in name or "\\" in name or ".." in name:
            raise EngineFieldError(
                f"{label}line {line_of(f)}: {f} '{name}' must be a plain file "
                f"name inside the archive (no '/', '\\' or '..')")
        out[f] = name
    return out


def _check_limits(raw, label, line_of, size_bytes):
    out = {}
    for f in LIMIT_FIELDS:
        v = _text(raw, f, label, line_of)
        if not DIGITS.match(v):
            raise EngineFieldError(
                f"{label}line {line_of(f)}: {f} '{v}' must be a positive "
                f"integer")
        out[f] = int(v)
    stream = out["limit_stream_bytes"]
    if stream < size_bytes:
        raise EngineFieldError(
            f"{label}line {line_of('limit_stream_bytes')}: limit_stream_bytes "
            f"{stream} is below the pinned size_bytes {size_bytes}")
    # The decompressed stream carries every entry, written or skipped.
    for other in ("limit_entry_bytes", "limit_written_bytes"):
        if stream < out[other]:
            raise EngineFieldError(
                f"{label}line {line_of('limit_stream_bytes')}: "
                f"limit_stream_bytes {stream} is below {other} {out[other]}")
    return out


def _group(raw, names, label, line_of, what):
    """True when the whole group is present, False when none is; a part of it
    is refused (a half-written group would read as a smaller engine path)."""
    present = [n for n in names if n in raw]
    if present and len(present) != len(names):
        missing = [n for n in names if n not in raw]
        raise EngineFieldError(
            f"{label}line {line_of(present[0])}: the {what} are given in part; "
            f"missing: {', '.join(missing)}")
    return bool(present)


def check_engine_fields(raw, out, label, line_of):
    """Validate the engine fields of one speech entry and add the converted
    values to `out` (which already holds kind, offered and size_bytes).
    Raises EngineFieldError."""
    kind, offered = out["kind"], out["offered"]
    mtype = _text(raw, "model_type", label, line_of)
    if not TYPE_SHAPE.match(mtype):
        raise EngineFieldError(
            f"{label}line {line_of('model_type')}: model_type '{mtype}' must be "
            f"empty or lowercase letters, digits and underscores")
    out["model_type"] = mtype
    supported = model_types.is_supported(kind, mtype)
    if offered and not supported:
        raise EngineFieldError(
            f"{label}line {line_of('model_type')}: kind '{kind}' with "
            f"model_type '{mtype}' is outside the supported set "
            f"({model_types.describe()}); only an entry with offered: false "
            f"may carry it")
    has_files = _group(raw, FILE_FIELDS, label, line_of, "files_* fields")
    has_limits = _group(raw, LIMIT_FIELDS, label, line_of, "limit_* fields")
    for present, group, names in ((has_files, "files_*", FILE_FIELDS),
                                  (has_limits, "limit_*", LIMIT_FIELDS),
                                  ("language" in raw, "language", ("language",))):
        if supported and not present:
            raise EngineFieldError(
                f"{label}line {line_of('model_type')}: a supported entry "
                f"needs {group} ({', '.join(names)}) and it has none")
    out["files"] = _check_files(raw, label, line_of) if has_files else None
    out["limits"] = (_check_limits(raw, label, line_of, out["size_bytes"])
                     if has_limits else None)
    out["language"] = None
    if "language" in raw:
        lang = _text(raw, "language", label, line_of)
        if not LANGUAGE_SHAPE.match(lang):
            raise EngineFieldError(
                f"{label}line {line_of('language')}: language '{lang}' must be "
                f"empty (the model decides) or a code such as en")
        out["language"] = lang
    out["default"] = False
    if "default" in raw:
        flag = _text(raw, "default", label, line_of)
        if flag not in ("true", "false"):
            raise EngineFieldError(
                f"{label}line {line_of('default')}: field 'default' must be "
                f"exactly true or false (got '{flag}')")
        out["default"] = flag == "true"
    if out["default"] and not supported:
        raise EngineFieldError(
            f"{label}line {line_of('default')}: an entry outside the supported "
            f"set ({model_types.describe()}) cannot be the default")
    if out["default"] and not offered:
        raise EngineFieldError(
            f"{label}line {line_of('default')}: the default entry must be "
            f"offered")
    out["notice"] = None
    if "notice" in raw:
        text = ("" if raw["notice"] is None
                else _text(raw, "notice", label, line_of))
        if not text.strip() or text != text.strip():
            raise EngineFieldError(
                f"{label}line {line_of('notice')}: notice must not be blank or "
                f"carry leading or trailing white space (a `>-` block keeps "
                f"the text exact)")
        out["notice"] = text


def check_default_count(entries):
    """Exactly one speech entry carries default: true; raises EngineFieldError
    naming the ids found otherwise."""
    ids = [e["id"] for e in entries if e.get("default")]
    if len(ids) != 1:
        raise EngineFieldError(
            f"exactly one speech entry must carry default: true (found "
            f"{len(ids)}: {', '.join(ids) or 'none'})")


def render_engine_fields(e, kotlin_str):
    """The Kotlin argument lines (8 spaces of indent) for the engine fields of
    one converted speech entry; a field the entry does not carry is omitted and
    takes its default. ModelFiles and ModelLimits are one line each, so no entry
    argument line is a bare `),` and no extra bare `    )` line appears."""
    lines = []
    if e.get("model_type") is not None:
        lines.append(f"        modelType = {kotlin_str(e['model_type'])},")
    if e.get("files"):
        f = e["files"]
        args = ", ".join(f"{k} = {kotlin_str(f[name])}" for k, name in (
            ("encoder", "files_encoder"), ("decoder", "files_decoder"),
            ("joiner", "files_joiner"), ("tokens", "files_tokens")))
        lines.append(f"        files = ModelFiles({args}),")
    if e.get("limits"):
        lim = e["limits"]
        args = ", ".join(f"{k} = {lim[name]}{suffix}" for k, name, suffix in (
            ("maxEntryBytes", "limit_entry_bytes", "L"),
            ("maxWrittenBytes", "limit_written_bytes", "L"),
            ("maxStreamBytes", "limit_stream_bytes", "L"),
            ("maxEntries", "limit_entries", ""),
            ("maxDepth", "limit_depth", "")))
        lines.append(f"        limits = ModelLimits({args}),")
    if e.get("language") is not None:
        lines.append(f"        language = {kotlin_str(e['language'])},")
    if e.get("default"):
        lines.append("        isDefault = true,")
    if e.get("notice") is not None:
        lines.append(f"        notice = {kotlin_str(e['notice'])},")
    return lines
