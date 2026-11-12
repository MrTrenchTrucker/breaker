# ADR-040: The website uses noble for key derivation and the sealed box

**Status:** accepted, amended 2026-11-10 (the measured phone timing replaces the 'a few seconds' expectation)
**Date:** 2026-11-10
**Approved by:** the project owner, typed 2026-11-10

## Context

The sign-in page of the website (ADR-036) needs two things its crypto stub does not do yet: stretch the
password into a key with Argon2id at the phone's setting (64 MiB, 3 passes, 1 lane), and open
the sealed box that carries the key package from the phone (ADR-018: `crypto_box_seal`, X25519, the
package `0x01 || DEK || boxPriv`). AES-256-GCM is already there: `aesgcm.js` uses the vendored
`@noble/ciphers` 2.4.0 (`shared/modules/web-libs/noble-ciphers/`).

Two libraries could supply the rest. `@noble/hashes`, `@noble/curves` and `@noble/ciphers` are plain
JavaScript. libsodium.js 0.8.4 is the libsodium C library built to WebAssembly. The noble parts were
checked against the sealed-box test vector of the phone side. The page's content security policy is
`script-src 'self'` with no WebAssembly.

## Decision

The website uses the noble libraries:

- Argon2id (and BLAKE2b) from `@noble/hashes`; X25519 from `@noble/curves`; XSalsa20-Poly1305 from
  `@noble/ciphers`. The sealed box is opened by composing them as libsodium does (X25519 shared key,
  HSalsa20, a BLAKE2b nonce from the two public keys, XSalsa20-Poly1305).
- The calls sit behind one small wrapper module of the website ("stretch the password", "open the sealed
  box", "seal"), so a library can be swapped in one place and the same test vectors check any of them.
- Only the files the page uses are vendored, unmodified and pinned by a source test, as `@noble/ciphers`
  is today. `NOTICE` names each one.

## Reasons

- No change to the content security policy. WebAssembly would need `wasm-unsafe-eval` for the whole page.
- Plain JavaScript that a person can read. `@noble/ciphers` and `@noble/curves` have an independent audit.
  The authors of `@noble/hashes` list Argon2 as not covered by it.
- The cost is time, once per sign-in. Argon2id at our setting measured about 0.8 seconds in Node on the
  project server's CPU; a phone browser took 486 ms (median; noble-hashes 2.4.0, Argon2id at 64 MiB, 3
  passes, 1 lane: three runs on the project owner's phone, 540, 486 and 483 ms; at 32 MiB, 558, 524 and
  522 ms; one phone and one browser, no more devices were measured). Opening items afterwards uses
  AES-GCM and is fast. The heavy step does not run on a click.
- libsodium.js stays a known, checked fallback: the project's own review found it safe to adopt on 2026-11-05.

## Consequences

- The first web task is done: Argon2id was timed on a real phone browser (median 486
  ms at 64 MiB, one phone, one browser). At this speed the decision stands; a slower
  device would be a reason to reopen it - then a new ADR supersedes this one and
  libsodium.js goes in behind the same wrapper, with the policy line it needs.
- The website has no WebAssembly and the policy stays as it is.
- The sealed box is composed from parts, so its code is pinned to the shared test vector of the phone
  side (`SignInPackageTest`), and a mismatch fails the build.
  Update 2026-11-11: the SOURCES of noble-curves stay unmodified and pinned by sha256; the SERVED copy has four specifier rewrites (bare `@noble/hashes/...` paths become relative paths), and the served files are pinned by sha256 too.
