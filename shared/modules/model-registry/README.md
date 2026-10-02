# model-registry — README

The one list of speech-recognition models that both the phone and the server use: sizes, download URLs pinned to immutable release assets, SHA-256 pins and each model's license. Every download is checked against the checksum the upstream project publishes, not only our own. Each entry also records the upstream revision the model came from and its exact license, and a CI check refuses any entry with a missing or malformed field. The phone and the server name models through constants generated from this list (ADR-016).

Status: slice 1 — `models.yaml` (one entry: `small`), the generator `tools/gen_model_registry.py`, the committed generated Kotlin and the tests. The other sizes and the hosted copies (F30, D25) are later slices.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
