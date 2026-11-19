# ADR-013: Model hosting in the Breaker container + checksum verification

**Status:** accepted, amended 2026-11-18 (the pin is our own sha256 per model; the upstream checksum.txt is advisory, not required)
**Date:** 2026-09-29

**Amendment 2026-11-18 (ADR-050; the replacement rule is built in the speech module and the registry, not compiled or run).** The Decision below says every download is verified against upstream's checksum.txt before load. That rule is replaced: each model is pinned by our own sha256, byte size and release-asset id, and the download is verified against that pin. Upstream checksum.txt is advisory. The second model, Parakeet TDT 0.6B v3, has no checksum.txt line. The original text below is kept as written.

## Context
On-device models download over the network and are parsed by sherpa-onnx —
Security Review's #3983 finding makes a tampered model a memory-corruption primitive [2].
The user wants the model available from the Breaker container for first-run
download.

## Decision
The web-fe serves the on-device model + checksums (licensing permitting; each
model records `hosted: true|false` and its license check in the model-registry).
Every download is verified against upstream's published checksum.txt before
load [2]. Fallback: direct upstream download with pinned checksums [2].
First-run onboarding includes the model download so fallback works immediately.

## Reasons
- Consistent with "treat every .onnx as untrusted" [2]; container hosting
  speeds first-run on the VPN and keeps one distribution point.

## Consequences
Rules in: `hosted` flag, license check per model, checksum.txt verification.
Rules out: floating model URLs, unverified downloads.
