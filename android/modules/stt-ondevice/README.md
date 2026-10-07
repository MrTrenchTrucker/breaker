# stt-ondevice — README

**Status:** in progress by the maintainers' team (issue #7). This branch is the module's main PR; it stays open until the module is complete.

Offline speech-to-text on the phone. It runs the **sherpa-onnx** engine (vendored from XIAOMI CORPORATION, upstream Apache-2.0, reviewed clean) as the fallback when the server can't be reached. It owns the model lifecycle: download, checksum verification against the pinned model registry, and loading. A download that fails verification is deleted, so a retry starts clean. It never reaches the network except to download a pinned model.

Full module card, including how to run its tests: `AGENTS.md` in this folder.

The module also holds the on-device engine, OnDeviceSttEngine, which implements the core SttEngine port: it checks the model through the loader on every call, decodes one request at a time, and reports every problem as a failure result instead of throwing. The native sherpa-onnx adapter that creates the recognizer is not wired in yet, so the module cannot transcribe real audio until it lands.
