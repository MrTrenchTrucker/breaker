# AGENTS.md — android/modules/settings/

## Purpose

Settings persistence + model registry access. Typed settings persistence + access to the shared model registry.

**Build phase:** Phase 2. Needs first: `core` (on main) and `model-registry` (not built yet; model choices come from its generated constants, ADR-016).

## Owns
Settings persistence + model registry access.

## Public Interface `core.SettingsStore`.

**Keys:** mode, model_size, server_url, api_key (Android Keystore, encrypted at
rest — T2), wake_gesture_enabled, tile_position, language, preload_model,
formatting_enabled, theme_mode.

**Model registry:** reads `shared/model-registry` (model list, sizes, URLs,
SHA-256 pins) — used by `stt-ondevice` for download + verify.

**UI:** settings screen (F7).

## Invariants
- Settings persist across restarts.
- API key stored via Keystore, never in plaintext/logs.
- Model registry parsed; invalid SHA-256 blocks model load.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_model_registry (registered in modules.toml)

## Does Not Own
- Server URL auth (auth-client)
- Model catalog (shared/model-registry)

## Test Locations
- Unit (Kotlin): `android/modules/settings/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:settings:test`
- Contract: `tests/contract/test_settings_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_settings_contract.py`
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
- Server URL is a first-run setting — never hardcode.
