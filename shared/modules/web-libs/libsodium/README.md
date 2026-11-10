# libsodium

libsodium 0.8.4 vendored pair into shared/modules/web-libs/libsodium.
VENDORED AND PINNED ONLY — NEVER COPIED TO A SERVED PATH (CSP no
wasm-unsafe-eval; ADR-040). Serving needs a new ADR.

## The pair

| File | Size | sha256 | Role |
|------|------|--------|------|
| libsodium-sumo.mjs | 413353 | 4c94708f7e78eac7a32b29e2ce0ff96f4bd599d78c129f27bf8a061e20776c8c | Core: raw WASM bindings (_crypto_pwhash, _crypto_box_seal, _crypto_box_open, ...). Self-contained, inlined wasm. |
| libsodium-wrappers.mjs | 122029 | 40de1ef7cb8f2caae02c2a8f04809151904fa28cb9364cadedc57af89a6c9284 | Friendly API: crypto_pwhash, crypto_box_seal, crypto_box_open, ... built on the sumo core. Line 1: `import e from "libsodium-sumo"` — NOT self-contained (bare specifier needs a resolver). Accepted: never served. |
| LICENSE | 839 | ce7b8ba14db085aadb72359226ccf7273225db31dad653242a7726a10dabbbd4 | ISC licence text |

## Source

- Tarball libsodium-sumo-0.8.4.tgz, sha256
  c51e7573c7b048acd07ec7b8677640208e9c00e0ab11416d3895bf6e299e5f7e → libsodium-sumo.mjs + LICENSE
- Tarball libsodium-wrappers-sumo-0.8.4.tgz, sha256
  858ad2910255d9b6c3527d6b7e8bcfcd98917a495ad2c97775bf2bfede13dfaf → libsodium-wrappers.mjs + LICENSE

The VERSION file records BOTH source tarballs (name, version, tarball sha256)
and the licence id (ISC).

No RC ids, agent names or host names in this text.

