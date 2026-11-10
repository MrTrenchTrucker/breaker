# AGENTS.md — shared/modules/web-libs/libsodium/

## Purpose

Vendored libsodium 0.8.4 pair for the web client crypto layer:
libsodium-sumo.mjs (the core, self-contained, inlined wasm) and
libsodium-wrappers.mjs (the friendly API: crypto_pwhash, crypto_box_seal,
crypto_box_open). Unmodified copies, pinned by hash. VENDORED AND PINNED ONLY
— NEVER COPIED TO A SERVED PATH. The website CSP is script-src 'self' with no
wasm-unsafe-eval (ADR-040), so libsodium is not served. Serving it needs a new
ADR.

## Pair (the two files that give crypto_pwhash + crypto_box_seal/_open)

1. libsodium-sumo.mjs — the core. Exports raw WASM bindings
   (_crypto_pwhash, _crypto_box_seal, _crypto_box_open, ...). Self-contained;
   inlines its wasm. Size 413353 bytes, sha256
   4c94708f7e78eac7a32b29e2ce0ff96f4bd599d78c129f27bf8a061e20776c8c.

2. libsodium-wrappers.mjs — the friendly API built on top of the sumo core.
   Line 1: `import e from"libsodium-sumo";` — imports the sumo core by BARE
   specifier. NOT self-contained: it cannot run without a module resolver that
   maps "libsodium-sumo" to the sumo file. Since it is never served, that is
   accepted; serving needs a new decision. Size 122029 bytes, sha256
   40de1ef7cb8f2caae02c2a8f04809151904fa28cb9364cadedc57af89a6c9284.

The upstream d.ts (libsodium-wrappers.d.ts, not vendored here) lists crypto_pwhash,
crypto_box_seal and crypto_box_seal_open.

## Files

libsodium-sumo.mjs, libsodium-wrappers.mjs, LICENSE (exactly these three;
no more, no fewer)

## Owns

The three vendored files above. Does not edit them.

## Does Not Own

- Any module resolver for the bare "libsodium-sumo" specifier — not needed
  because the pair is never served.
- The Gradle copy task — libsodium is NOT copied by web-fe's copyWebLibs task;
  the pin test checks this exclusion.

## Public Interface

None — data-only vendored files, never served.

## Depends On

- shared_web_libs (parent, registered in modules.toml)

## Invariants

- Every file matches its tarball sha256 byte-for-byte.
- No file outside the listed set; no edits to vendored files.
- libsodium is vendored and pinned but never served (no served path).

## Test Locations

- Pin table: tests/contract/web_libs_pins.py (one row per file of this
  sub-module; collected by its own contract test, tests/contract/test_libsodium_contract.py)

- Contract: tests/contract/web_libs_pins.py (one table row for this sub-module;
  the copy-task pin checks libsodium is EXCLUDED from web-fe's copy task)

## Test Requirement

sha256 of every file equals the value recorded from the tarball; byte-equal
against the tarball; LICENSE present and named; VERSION records BOTH source
tarballs (name, version, tarball sha256) and the licence id (ISC); no file
outside the listed set; web-fe's copy task does not include libsodium.

## Known Gotchas

- The wrappers file imports "libsodium-sumo" by bare name — it cannot run
  without a resolver. That is accepted because the pair is never served.
- libsodium-sumo.mjs carries trailing whitespace (upstream); do not edit it.
  .gitattributes handles strict-apply.

