# AGENTS.md — android/app/

## Purpose
Android entry point, dependency injection wiring, Gradle build.

## Public Interface
app-level composition of all modules.

## Owns
app entry, DI container, Gradle build.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- android_ui (registered in modules.toml)

## Invariants
app launches to the settings screen; DI wiring composes all modules.

## Does Not Own
- Screen implementations (ui)
- Domain logic (core)

## Test Locations
- Unit: `tests/unit/android/app/`
- Contract: `tests/contract/test_app_contract.py`

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

## Known Gotchas
- Phase 1 deliverable — DI wiring must be the ONLY place modules are composed.
