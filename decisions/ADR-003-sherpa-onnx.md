# ADR-003: On-device engine = sherpa-onnx (pinned + verified)

**Status:** accepted, amended 2026-11-18 (the pin is our own sha256 per model; the upstream checksum.txt is advisory, not required)
**Date:** 2026-09-29

**Amendment 2026-11-18 (ADR-050, built in the speech module and the registry, not compiled or run).** Mitigation (1) below says to verify against upstream checksum.txt. That was the rule when this ADR was written. The amended rule, now in the code: every model is pinned by our own sha256, byte size and release-asset id, and the download is verified against that pin. Upstream checksum.txt is advisory. The reason is that the second model, Parakeet TDT 0.6B v3, has no checksum.txt line, and an upstream re-upload broke installs on 2026-11-18. The word "immutable" in the text below is not true: upstream can re-point an asset id, and our own size and sha256 pin catches that (hand-traced only, not run). The original text below is kept as written.

## Context
The swept base vendors sherpa-onnx (XIAOMI CORPORATION, Apache-2.0). Security Review's
second audit found it safe to use with three mitigations, but not a clean bill
of health: issue #3983 is an OOB write in the offline transducer greedy-search
decoder, reachable via a tampered .onnx [2].

## Decision
Keep sherpa-onnx as the on-device engine with all three mitigations: (1) treat
every .onnx as untrusted — pin model URLs to immutable release-asset ids and verify
against upstream checksum.txt [2]; (2) retain the transducer path but track
#3983 and adopt the upstream decoder fix when it lands — revisit only if the
fix doesn't land in a reasonable window [2]; (3) keep sherpa-onnx off Local Server's
network-exposed path (server transcription uses the existing Whisper X
container) and network-isolate containers [2].

## Reasons
- Zero new supply-chain surface vs adding whisper.cpp; the vendored Kotlin
  bindings match upstream signatures exactly [2].

## Consequences
Rules in: model-registry pins, checksum verification before load, Phase 0 build
gate (nothing has been compiled or run [1][2]). Rules out: floating model
URLs, sherpa-onnx on the server's exposed path.
