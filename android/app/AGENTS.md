# AGENTS.md — android/app/

## Purpose
Android entry point, dependency injection wiring, Gradle build.

**Build phase:** Phase 1. Needs first: `core` (on `main`); it wires each other module in as that module lands. This card covers the app scaffold: it wires `settings` and `ui`, and its launcher activity hosts the settings screen.

## Public Interface
app-level composition of all modules. Concretely, the app's own types are:
- `BreakerApp` — the `Application`; builds and holds the composition root.
- `BreakerCompositionRoot` — the single place modules are composed; takes a files directory (plain JVM, testable without Android) and exposes the `SettingsStore` and the credential-reference `Keystore`.
- `SettingsLauncherActivity` — the launcher activity; hosts the settings view the `ui` module builds.
- `FileCredentialRefHolder` — a `Keystore` implementation backed by a file, holding the credential *reference* (never a secret).
- `SystemClockAdapter` — the app's `Clock` adapter (the real system clock; plain JVM).
- `AppStartPurge` — schedules the one start-up purge off the main thread, reporting a failure to a callback instead of letting it escape.
- The composition root exposes the `HistoryStore` it is handed (the concrete `SqliteHistoryStore` the app builds).

## Owns
app entry, DI container (composition root), Gradle build, and the file-backed credential-reference holder (a `Keystore` impl that holds the reference, not the secret).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- android_ui (registered in modules.toml)
- android_settings (registered in modules.toml)
- android_history (registered in modules.toml)

## Invariants
app launches to the settings screen; DI wiring composes all modules.

## Does Not Own
- Screen implementations (ui)
- Domain logic (core)
- The secret-resolving device Keystore (not built and not verified; the app's `FileCredentialRefHolder` holds only the reference)

## Test Locations
- Unit (Kotlin): `android/app/src/test/kotlin/` — `CompositionRootTest` (JVM, no Android): proves the composition root is wired file-to-file (a `save` on one instance is visible to a second over the same directory; the exposed `Keystore` carries the reference a `save` wrote; the reference survives a second `FileCredentialRefHolder` over the same file). Run: `./gradlew :android:app:test`
- Contract: `tests/contract/test_app_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_app_contract.py`
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
- Phase 1 deliverable — DI wiring must be the ONLY place modules are composed.
- **NOT VERIFIED on a device or emulator.** What is checked for this scaffold is
  compile (`assembleDebug` plus the unit test) and the JVM tests of the
  composition root. The launcher activity and the real settings view are **not
  exercised** — the app has not been launched on a device or an emulator.
- **Start-up purge is one-shot and only logged.** `BreakerApp` schedules
  `SqliteHistoryStore.purgeExpired()` once on an app-lifetime coroutine scope
  off the main thread. A failure is passed to a callback that logs it
  (`Log.w`); it is not retried and does not crash the app. Purge scheduling
  beyond app start is not built.
- `FileCredentialRefHolder` holds a credential *reference*, never a secret. The
  platform `Keystore` that would resolve the reference against a secret is **not
  built and not verified**; nothing in this module is a claim that a key is kept
  safe on the device.
