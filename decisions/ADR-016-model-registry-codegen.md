# ADR-016: Model registry compiled to Kotlin; no YAML parsing on the phone

**Status:** accepted
**Date:** 2026-09-30

## Context
`shared/modules/model-registry/models.yaml` is the source of record for every
speech model the app may download: URL, SHA-256 pin, size and licence
(ADR-003's integrity policy). Early implementation work read it with
hand-written YAML readers that ran on the phone, in two modules (stt-ondevice
and settings), and a third, test-only reader parsed `openapi.yaml` for the
api-contracts checks. A parser on the phone sits in the path that decides which
model file is trusted, and three copies of one grammar are three places to fix.
The Python tier cannot simply take over: its standard library has no YAML
parser.

## Decision
1. `models.yaml` stays the hand-edited source of record. It is not shipped to
   the phone and nothing parses it at runtime.
2. `tools/gen_model_registry.py` generates Kotlin source (one constant per
   model: id, family, url, sha256, size, licence, hosted) into
   `shared/modules/model-registry`. The generated file is committed, is marked
   as generated, and is never edited by hand.
3. A Python contract test regenerates the file and fails if the committed copy
   differs, so `models.yaml` and the app cannot drift apart.
4. The Python tier reads YAML through one small standard-library-only reader in
   `tools/`. It supports block and flow mappings and sequences (a flow
   collection may nest inside another), plain and quoted scalars, block scalars
   and whole-line comments. Every plain scalar is read as a string, with no
   implicit numbers, booleans or dates; the only null is an empty value, and
   callers convert types explicitly. Duplicate keys, anchors, aliases, tags and
   anything else outside that subset are refused with the line number. The same
   reader serves the api-contracts spec checks. No third-party YAML library is
   added.
5. App consumers (stt-ondevice, settings) use the generated constants; their
   YAML readers are deleted. A server-side consumer reads `models.yaml` through
   the same `tools/` reader or a generated equivalent, decided when that module
   is built.

## Reasons
- Nothing on the phone parses the registry, so nothing shipped can mis-parse it.
- One reader instead of three, and it runs only at build and test time.
- The Python tier stays standard-library-only: it runs anywhere with Python
  3.11+ and needs no install step.
- A malformed registry fails the build tooling in front of a developer, not the
  app on a phone.

## Consequences
Rules in: generated registry source plus a drift test; the `tools/` YAML reader
with its own tests (each watched failing on malformed input); Python 3.11+ in
the build container. Adding a model means editing `models.yaml`, running the
generator and committing both files.
Rules out: a runtime YAML or JSON parser for the registry in any module; a
third-party YAML library in the build or test tooling.
