# AGENTS.md — shared/modules/model-registry/

## Purpose

Model sizes, immutable release-asset URLs, upstream checksum.txt, licenses, hosted flag. The canonical list of ASR models for both on-device and server use:
sizes, download URLs, **SHA-256 pins** (integrity gate, T1), and **per-model
license terms** (Security Review: runtime ASR models carry their own upstream terms,
outside the audit).

**Integrity policy (Security Review mitigation 1) [2]:** treat every `.onnx` as
untrusted. Model URLs are **pinned to immutable release-asset ids** (never the moving
asr-models ref), and every download is verified against the **checksum.txt the
upstream already publishes** — not just our own recorded SHA-256 [2].

**On-device (sherpa-onnx compatible, S25 Ultra 16 GB):**
- `small` — **recommended default** for real-time dictation
- `medium` — possible on 16 GB; higher accuracy, slower
- (tiny/base available for weaker hardware)

**Server (Whisper X container, CPU):** whatever the existing pipeline already
serves — document it here for parity.

**Format:** `models.yaml` — id, family (sherpa-onnx|whisper), params, size_mb,
url, sha256, **license** (e.g., MIT for Whisper weights; check sherpa-onnx model
cards), notes. `models.yaml` is the hand-edited source of record; it is not
shipped to the phone and never parsed at runtime (ADR-016).

**Hosting (F30, D25):** if licensing permits, the model is also served from the
Breaker container (web-fe) with checksums, so the phone downloads from the
container instead of upstream [2]. Each entry records `hosted: true|false` and
the license check result (R26).

**Build phase:** Before Phase 2: `settings` (Phase 2) and `stt-ondevice` (Phase 3) both depend on it. Needs first: nothing beyond `shared`.

## Invariants
- Every entry has a sha256; download + verify path tested (tamper test).
- Every entry has a license field (R8).
- URLs pinned to specific releases (no floating "latest").
- Registry versioned; app refuses unknown model ids.
- **Every entry verifies against upstream's checksum.txt** (T21) [2].

## Owns
Model sizes, immutable release-asset URLs, upstream checksum.txt, licenses, hosted flag.

## Public Interface
Generated Kotlin model registry (ADR-016)
- Kotlin constants generated from `models.yaml` by `tools/gen_model_registry.py`;
  consumers name a model by these constants, never by reading `models.yaml`.

## Depends On
- shared (registered in modules.toml)

## Does Not Own
- Download/verify logic (android/stt-ondevice)

## Test Locations
- Unit (Python): `tests/unit/shared/model-registry/`, created with the module's first code. Run: `python3 -m unittest discover -s tests/unit/shared/model-registry`
- Contract: `tests/contract/test_model_registry_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_model_registry_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- Not in the tree yet: `models.yaml` and `tools/gen_model_registry.py` are created when this module is built (ADR-016). Until then, this card describes them; nothing can import them.
- URLs pin to immutable release-asset ids; verify upstream checksum.txt [2].
- The generated Kotlin file is never hand-edited: edit `models.yaml`, rerun the
  generator, commit both. A contract test fails if they drift (ADR-016).
- Do not add a runtime YAML or JSON reader here or in a consumer (ADR-016).
