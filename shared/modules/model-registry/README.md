# model-registry — README

The one list of speech-recognition models that both the phone and the server use: sizes, download URLs pinned to immutable release assets, SHA-256 pins and each model's license. Every download is checked against the checksum the upstream project publishes, not only our own. Each entry also records the upstream revision the model came from and its exact license, and a CI check refuses any entry with a missing or malformed field. The phone and the server name models through constants generated from this list (ADR-016).

Status: `models.yaml` holds four entries: `small` and `tiny` (streaming Zipformer, English) and `base` and `medium` (non-streaming Whisper, English; the app cannot run a non-streaming model yet). The generator `tools/gen_model_registry.py`, the committed generated Kotlin and the tests are in the tree. The hosted copies (F30, D25) are not built: every entry has `hosted: false`.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
