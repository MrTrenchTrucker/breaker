# AGENTS.md — shared/modules/web-libs/

## Purpose

Parent module for the web-client cryptographic libraries vendored into Breaker's
website (server/modules/web-fe). Owns no files of its own — each library lives
in its own sub-module folder beneath this one. The parent exists to register the
group, to name its sub-modules, and to be the single depends_on target for
web-fe so the Gradle copy task has one registry edge to wire.

## Sub-modules

noble-ciphers, noble-hashes, noble-curves, libsodium — each with its own
AGENTS.md + README.md + VERSION record file, each vendored and pinned by hash,
each unmodified from its upstream tarball.

## Owns

No files. The parent module is a grouping module only; every vendored file is
owned by one of its sub-modules.

## Does Not Own

- Any implementation file — those live in the sub-modules.
- The Gradle copy task — that lives in server/modules/web-fe.
- The libsodium serving decision — libsodium is vendored and pinned but never
  copied to a served path (CSP script-src 'self' has no wasm-unsafe-eval;
  ADR-040). Serving it needs a new ADR.

## Public Interface

None (data-only module). The Gradle build sees it as a base-plugin project
that publishes no artifact.

## Depends On

- shared (registered in modules.toml)

## Invariants

- Every file in every sub-module matches its tarball sha256 byte-for-byte.
- The Gradle copy task includes exactly the three noble sub-modules and excludes libsodium.
- libsodium is vendored and pinned but never served (no served path).

## Test Locations

- Contract: `tests/contract/test_web_libs_contract.py`. Run:
  `python3 -m unittest discover -s tests/contract -t tests/contract -p test_web_libs_contract.py`
  Collected pins live in `tests/contract/web_libs_pins.py`.
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement

Every file in every sub-module must match its tarball sha256 byte-for-byte;
the copy task must include exactly the three noble sub-modules and exclude
libsodium; the five red checks must each fail by name and restore.

## Known Gotchas

- Vendored files carry trailing whitespace (upstream, not edited). Do not edit
  them; .gitattributes marks shared/modules/web-libs/** -whitespace so
  git apply --check --whitespace=error passes.
- The parent publishes no artifact (base plugin only), so no project() edge
  is required of its dependents — the registry edge is comment-only for the
  build, like web-fe today.
