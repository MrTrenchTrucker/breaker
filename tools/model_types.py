"""tools/model_types.py - the ONE place that says which speech models the app can
run (ADR-050 J: models are data).

A speech entry is a (kind, model_type) pair: `kind` is streaming or offline, and
`model_type` is the exact sherpa-onnx model-type string its engine needs (the
empty string for a streaming transducer). An entry whose pair is in SUPPORTED
can be offered and run by the engine as data alone. An entry whose pair is
outside it (the whisper entries) may exist only with `offered: false`.

tools/gen_model_registry.py reads this module to check models.yaml and to write
`ModelRegistry.SUPPORTED_TYPES` into the generated Kotlin; nothing else keeps a
second list. Built: the generator and its tests. Not built: a recogniser for a
pair that is not listed here (offline whisper has no engine path yet).

Standard library only.
"""

# (kind, model_type) pairs the engine can run, in the order the generated
# Kotlin lists them. Adding a pair here is a decision that the engine can run it.
SUPPORTED = (
    ("streaming", ""),
    ("offline", "nemo_transducer"),
)


def is_supported(kind, model_type):
    """True when the (kind, model_type) pair is in SUPPORTED."""
    return (kind, model_type) in SUPPORTED


def describe():
    """The supported set as text for a refusal message."""
    return ", ".join(f"({k}, '{t}')" for k, t in SUPPORTED)
