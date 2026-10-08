# stt-ondevice — README

**Status:** in progress by the maintainers' team (issue #7). This branch is the module's main PR; it stays open until the module is complete.

Offline speech-to-text on the phone. It runs the **sherpa-onnx** engine as the fallback when the server can't be reached. The engine is our ASR-only build of sherpa-onnx (Apache-2.0; text-to-speech and speaker diarization switched off), built from upstream sherpa-onnx at commit 11afbd00 and published as a release file. This module compiles against that release file, the app supplies it at run time, and nothing of it is copied into the repository. Building the module downloads it and checks it against the SHA-256 pinned, together with its address, in `sherpa-onnx-aar.properties`. It owns the model lifecycle: download, checksum verification against the pinned model registry, unpacking, and loading. A download that fails verification is deleted, so a retry starts clean. It never reaches the network except to download a pinned model.

Full module card, including how to run its tests: `AGENTS.md` in this folder.

The module also holds the on-device engine, OnDeviceSttEngine, which implements the core SttEngine port: it checks the model through the loader on every call, decodes one request at a time, and reports every problem as a failure result instead of throwing. The native adapter that creates the recognizer exists (`SherpaOnnxRecognizerFactory`), but the app still has to supply the sherpa-onnx release file and pass the factory to the model loader, so the module cannot transcribe until the app does that. How it behaves on a device has not been tested.
