# ADR-003: On-device engine = sherpa-onnx (pinned + verified)

**Status:** accepted
**Date:** 2026-09-29

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
