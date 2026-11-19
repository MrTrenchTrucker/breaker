# AGENTS.md — android/modules/training-client/

## Purpose

Record phrase samples, upload, download trained model + checksum verify. In-app voice phrase training (F16). Record the wake phrase
("Breaker Breaker") and end phrase ("And I'm Gone") in the user's environment;
the server trains a per-user model; the phone downloads and installs it.

**Status on main:** nothing in this module is built. The folder holds this card, the README and a build file, and no Kotlin file; `PhraseTraining` is a port declared in `core` (`core/port/PhraseTraining.kt`), not a type of this module. Everything below (the Flow, the Privacy rule, the Invariants and the Known Gotchas) is the target design, planned, not built. Decided, not built (ADR-051): the phone side is accepted as recording, uploading, installing and deleting per ADR-051 B, E and F, and it is built first, before the shared contract and the server (ADR-051 I).

**Build phase:** Phase 13, together with the server's `training`. Needs first: `core` (on main) and auth (Phase 11), because every training call carries the user's token.

## Owns
Record phrase samples, upload, download trained model + checksum verify.

## Public Interface `core.PhraseTraining` port.

**Flow:**
1. Guided recording: 10 to 20 samples per phrase (mic, ambient noise included). Decided, not built (ADR-051 B): the count is fixed by the build; each sample is 16 kHz mono and stays in app-private storage until uploaded.
2. Upload samples to the training container (user token, user-scoped). Decided, not built (ADR-051 B and C): nothing is uploaded unless the user starts training and is signed in; the phone deletes each sample once the server confirms it.
3. Server trains a small per-user KWS model (sherpa-onnx KWS toolkit). Decided, not built (ADR-051 D): the server trains on the CPU by default.
4. Phone downloads the model + **verifies checksum/SHA-256** (T21) then installs
   for the `phrases` module. Decided, not built (ADR-051 E): the verified model is installed as its own keyword model, outside the shipped registry, and the default keyword model of option C is used until one is installed and verified, and again if anything fails.

**Privacy:** samples are user-scoped and deletable by the user (T15). Decided, not built (ADR-051 F): one user action deletes the samples and the model on the server and the model on the phone.

## Invariants
- A downloaded model that fails its checksum is never installed (T21); once installed, it is the model phrase detection uses (F16). Decided, not built (ADR-051 E): a model that fails its checksum, or is missing, leaves the default keyword model in use.
- Tampered model download refused (T21).
- Samples deletable; deletion removes them server-side. Decided, not built (ADR-051 F): the model is deleted too, on the server and on the phone.
- Decided, not built (ADR-051 G): with no server, or while signed out, training is not offered and option C keeps working.

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
