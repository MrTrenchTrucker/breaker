# AGENTS.md — Repo-Level Instructions

This file tells any agent — at any level, in any later session — how to work in
this repo without being told by a person first.

## The three lanes

| Lane | Owner | Entry points |
|------|-------|--------------|
| **Bug Hunt** | Security/QA | `docs/03-security-threat-model.md`, Phase 0 + Phase 9 gates, `tools/check_repo.py` |
| **Orchestration** | Structure/contracts | `ARCHITECTURE.md`, `modules.toml`, `MODULE_MAP.md`, `shared/modules/*`, `docs/04-build-order.md` |
| **Coding** | Implementation | The module's own `AGENTS.md` + `docs/01-requirements.md` (F-numbers) |

## The rules that always hold

1. **Read first.** Before drafting or changing anything, read the module's
   `AGENTS.md` and the registry entry in `modules.toml`. New work must not
   silently contradict existing docs.
2. **Terminology:** we say *text commit* / *text insertion* — never
   "injection."
3. **No cross-module imports.** Modules talk through `core` ports or the app's
   dependency wiring in `android/app/`. `depends_on` in `modules.toml` must stay
   acyclic.
4. **Every module card carries its Test Requirement.** Anything built against a
   module must be proven to fail loudly: break the protected behavior on
   purpose, confirm the test fails and says why, then restore. A test that only
   ever passes proves nothing.
5. **Registry and tree must agree.** Every entry in `modules.toml` has a real
   folder with `AGENTS.md` + `README.md`; every parent README names its
   sub-modules. Run `tools/check_repo.py` after any structural change.
6. **Security gates are load-bearing.** The forked base app is not on `main`
   yet and has not passed its build and smoke test [1][2]. Phase 0 (build +
   smoke test) and Phase 9 (E2E + bench + security review) are mandatory
   gates — no phase after them starts without their sign-off.
7. **Models are untrusted.** Every `.onnx` is pinned to immutable release-asset ids and verified against upstream checksum.txt before use [2].
8. **No plaintext transcriptions in logs, ever** (F32). Events only, per-user.

## Definition of done (repo-wide)

- `modules.toml` matches the folder tree; `tools/check_repo.py` passes.
- Every touched module's card is accurate (Owns / Does Not Own / Public
  Interface / Depends On / Invariants / Test Locations / Test Requirement /
  Known Gotchas).
- Terminology clean; no cross-module imports outside `core` ports.
- Security Review's fixes [1] and the sherpa-onnx mitigations [2] are preserved.

## Build order

See `docs/04-build-order.md` — 22 phases, 0 (build gate) through 22 (UI/UX).
