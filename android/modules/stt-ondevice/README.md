# stt-ondevice — README

Offline speech-to-text on the phone. It runs the **sherpa-onnx** engine (vendored from XIAOMI CORPORATION, upstream Apache-2.0, reviewed clean) as the fallback when the server can't be reached. It owns the model lifecycle: download, checksum verification against the pinned model registry, and loading. It never reaches the network except to download a pinned model.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
