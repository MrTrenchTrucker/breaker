# AGENTS.md — server/modules/training/

## Purpose

Per-user voice phrase model training (CPU; GPU via Local Inference). Per-user voice phrase model training container (F16). The phone
records, the server trains.

**In:** sample audio + user token (`POST /v1/training/samples`).
**Out:** a small per-user KWS model + checksum for the phone to download.

**Pipeline:**
- Samples stored in the user's namespace (isolated, N13; deletable, T15).
- Fine-tune a small KWS model (sherpa-onnx KWS toolkit / k2-fsa recipes) for
  the user's wake/end phrases in their environment.
- Publish model + **SHA-256/checksum** — the phone verifies before install (T21).
- These models are loaded by the phone's sherpa-onnx, so Security Review's mitigation 1
  (treat every .onnx as untrusted, verify checksums) applies to them too [2].

**Compute (D26):** KWS fine-tuning is small — **CPU is the default path**;
confirm the model size in Phase 13. If GPU training is required, register with
the local inference engine and use **Local Inference** (FIFO multi-agent, Fast Lane
priority) for GPU job management.

**Build phase:** Phase 13, together with `training-client`. Needs first: auth (Phase 11), because every training call carries the user's token.

## Invariants
- Training completes; model + checksum downloadable (F16).
- Cross-user access refused (N13); samples deletable (T15).
- Tampered model refused on the phone (T21).
- For the model size confirmed in Phase 13, a training run completes on the CPU path; if it cannot, training uses the GPU through Local Inference (D26, R27).

## Owns
Per-user voice phrase model training (CPU; GPU via Local Inference).

## Public Interface
TrainingApi

## Depends On
- server (registered in modules.toml)

## Does Not Own
- Sample recording (android/training-client)
- Model hosting (web-fe)

## Test Locations
- Unit (Python): `tests/unit/server/training/`, created with the module's first code. Run: `python3 -m unittest discover -s tests/unit/server/training`
- Contract: `tests/contract/test_training_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_training_contract.py`
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
- CPU default; GPU via Local Inference only if the model size requires it (confirm in Phase 13).
