# AGENTS.md — android/modules/training-client/

## Purpose

Record phrase samples, upload, download trained model + checksum verify. In-app voice phrase training (F16). Record the wake phrase
("Breaker Breaker") and end phrase ("And I'm Gone") in the user's environment;
the server trains a per-user model; the phone downloads and installs it.

**Build phase:** Phase 13, together with the server's `training`. Needs first: `core` (on main) and auth (Phase 11), because every training call carries the user's token.

## Owns
Record phrase samples, upload, download trained model + checksum verify.

## Public Interface `core.PhraseTraining` port.

**Flow:**
1. Guided recording: 10–20 samples per phrase (mic, ambient noise included).
2. Upload samples to the training container (user token, user-scoped).
3. Server trains a small per-user KWS model (sherpa-onnx KWS toolkit).
4. Phone downloads the model + **verifies checksum/SHA-256** (T21) → installs
   for the `phrases` module. Until trained, streaming-match is used.

**Privacy:** samples are user-scoped and deletable by the user (T15).

## Invariants
- A downloaded model that fails its checksum is never installed (T21); once installed, it is the model phrase detection uses (F16).
- Tampered model download refused (T21).
- Samples deletable; deletion removes them server-side.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Training itself (server/training)
- Phrase detection (phrases)

## Test Locations
- Unit (Kotlin): `android/modules/training-client/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:training-client:test`
- Contract: `tests/contract/test_training_client_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_training_client_contract.py`
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
- Trained models are .onnx too — checksum-verify before install (T21).
- The login token reaches this module as `core.AuthService`, wired by `android/app` (ADR-001); it never imports `auth-client`.
