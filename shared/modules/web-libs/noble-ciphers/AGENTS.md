# AGENTS.md — shared/modules/web-libs/noble-ciphers/

## Purpose

Vendored copies of @noble/ciphers 2.4.0 for the web client crypto layer:
AES-GCM (aes.js), XSalsa20/XChaCha20-Poly1305 (salsa.js), and the supporting
primitives (_arx.js, _poly1305.js, _polyval.js, utils.js). Unmodified copies,
pinned by hash. Used by web-fe's aesgcm.js for key derivation and the sealed box
(ADR-040).

## Files

aes.js, salsa.js, _arx.js, _poly1305.js, _polyval.js, utils.js, LICENSE
(exactly these seven; no more, no fewer)

## Owns

The seven vendored files above. Does not edit them; replaces them only by an
exact copy of a newer release with pin hashes updated in the same change.

## Does Not Own

- The Gradle copy task (lives in server/modules/web-fe).
- Any usage of these files (aesgcm.js in web-fe imports them after the copy).

## Public Interface

None — data-only vendored files.

## Depends On

- shared_web_libs (parent, registered in modules.toml)

## Invariants

- Every file matches its tarball sha256 byte-for-byte.
- No file outside the listed set; no edits to vendored files.

## Test Locations

- Pin table: tests/contract/web_libs_pins.py (one row per file of this
  sub-module; collected by its own contract test, tests/contract/test_noble_ciphers_contract.py)

## Test Requirement

sha256 of every file equals the value recorded from the tarball; byte-equal
against the tarball; LICENSE present and named; VERSION matches card and README;
no file outside the listed set.

## Known Gotchas

- Upstream files carry trailing whitespace; do not edit them. .gitattributes
  handles strict-apply (git apply --check --whitespace=error).
- chacha.js is NOT vendored — aesgcm.js imports only aes.js and utils.js; aes.js
  itself needs only _polyval.js and utils.js, and the other three files (salsa.js, _arx.js, _poly1305.js)
  ship for the sealed box's XSalsa20-Poly1305 half (ADR-040).

