# AGENTS.md — shared/modules/web-libs/noble-curves/

## Purpose

Vendored copies of @noble/curves 2.4.0 for the web client crypto layer:
X25519 (ed25519.js + the abstract closure: curve, edwards, fft, frost,
hash-to-curve, modular, montgomery, oprf, utils) plus LICENSE. Unmodified
copies, pinned by hash. Used by web-fe's sealed box (ADR-040).

## Files

ed25519.js, utils.js, LICENSE, and abstract/{curve,edwards,fft,frost,
hash-to-curve,modular,montgomery,oprf}.{js} — 11 files total (exactly these;
no more, no fewer)

## Owns

The eleven vendored files above. Does not edit them; replaces them only by an
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
  sub-module; collected by its own contract test, tests/contract/test_noble_curves_contract.py)

- Contract: tests/contract/web_libs_pins.py (one table row for this sub-module)

## Test Requirement

sha256 of every file equals the value recorded from the tarball; byte-equal
against the tarball; LICENSE present and named; VERSION matches card and README;
no file outside the listed set.

## Known Gotchas

- Upstream files carry trailing whitespace; do not edit them. .gitattributes
  handles strict-apply (git apply --check --whitespace=error).
- The abstract closure is 8 files (238,631 bytes) plus ed25519.js and utils.js;
  all are needed: ed25519.js statically imports seven of the eight abstract
  files and utils.js directly, and fft.js comes in through frost.js.

