# ADR-013: Model hosting in the Breaker container + checksum verification

**Status:** accepted
**Date:** 2026-09-29

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
