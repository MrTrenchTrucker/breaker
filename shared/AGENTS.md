# AGENTS.md — shared/ (contracts + registry)

## Purpose

Contracts + registry shared by app and server. Single source of truth shared by the Android app and the server:
the API contract and the model registry. Both sides read from here; neither side
defines these independently.

**Module map:**
- `api-contracts` — OpenAPI spec: auth, sync, jobs, updates, admin, training
- `model-registry` — model sizes, immutable release-asset URLs, upstream checksum.txt,
  per-model licenses, hosted flag
- `format-prompts` — strict non-destructive formatting prompts for the server LLM
- `ui-tokens` — Trucking design tokens (colors light/dark, type, spacing,
  breakpoints) shared by the web FE + Android app

**Convention:** changes here require updating BOTH consumers (app + server).

**Build phase:** Container. It groups the shared modules and holds no code.

## Owns
Contracts + registry shared by app and server.

## Public Interface
Contracts, registries

## Depends On
- none

## Does Not Own
- Implementation of either side

## Test Locations
- No unit tests: this folder only groups the modules under it and holds no code.
- Contract: `tests/contract/test_shared_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_shared_contract.py`
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
- Changes here require updating BOTH consumers (app + server).

## Invariants
- One registry of record: `modules.toml` is the only module registry; a module folder with an AGENTS.md that is not in it, and a registry entry with no folder, are both repo errors (check_repo).
- Contract and registry files live only here: an OpenAPI spec outside shared/modules/api-contracts, or a model registry data file outside shared/modules/model-registry, is a repo error.
- Cards are the contract: every module's AGENTS.md carries the nine required sections exactly once; a repeated or renamed section is a failure, not a first-wins read.
- The build follows the registry: a code module's build file names exactly the code modules its `depends_on` lists — nothing more (a boundary breach) and nothing less (a missing edge) — and a `base`-only container is exempt from the "less" half because it publishes no artifact to link against.
