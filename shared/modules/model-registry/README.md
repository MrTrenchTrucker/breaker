# model-registry — README

The one list of speech-recognition models that both the phone and the server use: sizes, download URLs pinned to immutable release assets, SHA-256 pins and each model's license. Every download is checked against the checksum the upstream project publishes, not only our own. Each entry also records the upstream revision the model came from and its exact license, and a CI check refuses any entry with a missing or malformed field. The phone and the server name models through constants generated from this list (ADR-016).

Full module card, including how to run its tests: `AGENTS.md` in this folder.
