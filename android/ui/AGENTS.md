# AGENTS.md — android/ui/

## Purpose
Screens: history, settings, auth, training, and the onboarding for the
permissions (accessibility service, overlay).

**Build phase:** Phase 1 (the app shell's first screens). Each screen arrives with its feature's phase, and the Trucking theme with Phase 22. Needs first: `core` and `shared/ui-tokens` (both on `main`).

## Owns
screen components; consumes `shared/ui-tokens` (Trucking theme).

## Public Interface
Screen components

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_ui_tokens (registered in modules.toml)

## Invariants
screens render with ui-tokens; light/dark toggle persists (F25).

## Does Not Own
- Business logic (core)
- Platform adapters (modules/*)

## Test Locations
- Unit (Kotlin): `android/ui/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:ui:test`
- Contract: `tests/contract/test_ui_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_ui_contract.py`
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

## Known Gotchas
- Consumes shared/ui-tokens — do not hardcode colors in screens.
