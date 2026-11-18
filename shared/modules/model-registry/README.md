# model-registry — README

The one list of speech-recognition models for the phone and, as planned (ADR-016), the server; no server code reads it today: sizes, download URLs pinned to immutable release assets, SHA-256 pins and each model's license. Every download is checked against our own SHA-256 pin. Decided, not built (ADR-050): the upstream checksum.txt becomes advisory, not required; today's check still requires the pin to appear in the upstream file. Each entry also records the upstream revision the model came from, its exact
license, and the upstream file name it downloads as (checked against the checksum
the upstream publishes), and a CI check refuses any entry with a missing or malformed field. The phone names models through constants generated from this list (ADR-016).

Status: `models.yaml` holds four entries: `small` and `tiny` (streaming Zipformer, English) and `base` and `medium` (non-streaming Whisper, English; the app cannot run a non-streaming model yet). The generator `tools/gen_model_registry.py`, the committed generated Kotlin and the tests are in the tree. The hosted copies (F30, D25) are not built: every entry has `hosted: false`.

Decided, not built (ADR-050): a `parakeet` entry (Parakeet TDT 0.6B v3, CC BY 4.0) with the label "Better" and a rating of 2 of 3 truck emoji; `small` is labelled "Good", 1 of 3. The registry stays at four entries until that build lands.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
