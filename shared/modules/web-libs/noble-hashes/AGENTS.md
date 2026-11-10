# AGENTS.md — shared/modules/web-libs/noble-hashes/

## Purpose

Vendored copies of @noble/hashes 2.4.0 for the web client crypto layer:
Argon2id (argon2.js), BLAKE2b (blake2.js), HKDF (hkdf.js), HMAC (hmac.js),
and the supporting primitives (_u64.js, utils.js, _blake.js, _md.js, sha2.js).
Unmodified copies, pinned by hash. Used by web-fe's key derivation (ADR-040).

## Files

argon2.js, blake2.js, hkdf.js, hmac.js, _u64.js, utils.js, _blake.js, _md.js,
sha2.js, LICENSE (exactly these ten; no more, no fewer)

## Owns

The ten vendored files above. Does not edit them; replaces them only by an
exact copy of a newer release with pin hashes updated in the same change.

## Does Not Own

- The Gradle copy task (lives in server/modules/web-fe).
- Any usage of these files.

## Public Interface

None — data-only vendored files.

## Depends On

- shared_web_libs (parent, registered in modules.toml)

## Invariants

- Every file matches its tarball sha256 byte-for-byte.
- No file outside the listed set; no edits to vendored files.

## Test Locations

- Pin table: tests/contract/web_libs_pins.py (one row per file of this
  sub-module; collected by its own contract test, tests/contract/test_noble_hashes_contract.py)

- Contract: tests/contract/web_libs_pins.py (one table row for this sub-module)

## Test Requirement

sha256 of every file equals the value recorded from the tarball; byte-equal
against the tarball; LICENSE present and named; VERSION matches card and README;
no file outside the listed set.

## Known Gotchas

- Upstream files carry trailing whitespace; do not edit them. .gitattributes
  handles strict-apply (git apply --check --whitespace=error).
- sha2.js IS vendored — needed by ed25519.js in noble-curves, which imports
  '@noble/hashes/sha2.js' and is served the copy in this module (noble-curves does not vendor its own).

