# AGENTS.md — android/modules/settings/

## Purpose

Settings persistence + model registry access. Typed settings persistence + read-only access to the shared model registry.

**Build phase:** Phase 2. Needs first: `core` (on main) and `model-registry`; model choices come from the registry's generated constants (ADR-016).

## Owns
Settings persistence + model registry access.

## Public Interface

The caller constructs `SettingsFileStore` and supplies a `Keystore`
implementation:

- `SettingsFileStore` — a concrete class; the caller constructs it with its
  settings file and a `Keystore`. It implements `core.SettingsStore`.
- `Keystore` — an interface, not a class: the caller supplies an implementation
  of it and does not construct the type itself. It is the credential port
  `SettingsFileStore` routes the `api_key` reference through. The module
  supplies no implementation of it for real use — no device implementation
  exists yet (see Known Gotchas) — so a caller who treats it as a constructible
  type will not compile.
- `ModelCatalog` — a read-only object over the model registry: the full model
  list (id, size, URL, SHA-256) and a lookup by id. No download or verify logic
  lives here; that is `stt-ondevice`'s.

**Keys:** mode, model_size, server_url, api_key (Android Keystore, encrypted at
rest — T2) *(the device Keystore is not built yet — see Known Gotchas)*,
wake_gesture_enabled, tile_position, language, preload_model,
formatting_enabled, theme_mode.

**Model registry:** `ModelCatalog` reads `shared/model-registry` (model list,
sizes, URLs, SHA-256 pins); `stt-ondevice` reads the same registry for download
+ verify, which is its work, not this module's.

**UI:** settings screen (F7). *(Not built yet.)*

## Invariants
- Settings persist across restarts.
- A save that fails leaves the previous settings file as it was, or no file when there was none. If the failure is in the file steps, the keystore is not told the new reference. If it is in the keystore step, the file is put back and the previous reference is restored, subject to the limit in the Known Gotchas.
- API key stored via Keystore, never in plaintext/logs. Both halves of that are
  stated apart on purpose: routing the reference through the `Keystore` port, and
  the settings file never carrying the key, are built and tested; what is not
  built is any device Keystore behind the port.
  *(the device Keystore is not built yet — see Known Gotchas)*
- Model registry parsed; invalid SHA-256 blocks model load. *(Not built yet.)*

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
- The Gradle edge to `:shared:modules:model-registry` is required and present:
  the registry publishes an artifact, and the boundary check makes the edge
  mandatory the moment it does. `ModelCatalog` reads the registry through it,
  and the edge stays `implementation(...)` — the registry's types never appear
  in this module's public signatures, so nothing outside needs the edge.
- The device Keystore is **not implemented and not verified** yet. Nothing in
  this module has been run against Android Keystore; the port carries the credential
  *reference* only and has no method that can receive or return a secret. The
  instrumented test that will verify a hardware-backed key is a later change — it
  belongs in `androidTest`, not `src/test`, and it is the only place that claim can
  honestly be made.
- A reference's durability **across a real process restart is NOT VERIFIED**. What the
  unit tests prove is narrower: that the store routes the reference through the
  `Keystore` port and never writes it to the settings file. A fake port lives in the
  same process, so it cannot prove survival across process death — that is the platform
  Keystore's job, and it arrives with the device implementation.
- The settings file holds **nine** of the card's ten keys. `api_key` is deliberately
  absent: the credential reference travels through the `Keystore` port, never through
  `java.util.Properties`. A reader counting keys will find one short and that is
  correct.
- A saved `model_size` is a registry id: a save is refused, before the file is
  touched, when it is not an id the registry names, and a read falls the key back
  to the default for exactly that case. The match is EXACT-CASE — the registry's
  own lookup is exact, and its ids are lowercase alphanumerics — so a case-folded
  reader would honour a file the writer refuses. The file holds plain strings;
  the registry holds the ids; no id list is copied into either.
- **Why a monitor, and the one lock in this module.** `save()` is a blocking call, not a suspend function. The settings file is small, and `save()` may be called from the main thread through the `SettingsStore` port. A coroutines `Mutex` can only be taken by suspending, and its non-suspending `tryLock` cannot wait, so the only other choice would be a spin, which the standing orders forbid. A monitor is therefore the one place this module uses `synchronized`, and the reason is written next to the site in `SettingsFileStore.save`.
- **One lock for the whole process.** Every save takes the same monitor, `AtomicFileReplace.lock`, and holds it for the whole save, including the keystore call and the put-back. Two store instances over the same file never write at once, whichever instance is used.
- **The write and the move.** `save()` writes the whole document to `<file name>.tmp` in the same folder, then moves it over the live file in one atomic move (`ATOMIC_MOVE`). The keystore is told the new reference only after that move, and not at all when the file steps failed. If the keystore call throws, the file is put back through its own temp file, `<file name>.restore`, which is moved over the live file by the same move step; the previous reference is restored when the port changed it; and the original failure is thrown. There is no fsync, so a power cut or a crash in the middle of the move or the restore is not covered: the live file may then be the old one, and what a power cut leaves on disk has not been verified.
- **The limit of a failed restore.** If the reference cannot be put back, or cannot be read back to be checked, the file is still put back, the original failure is thrown with the second one attached, and the reference stays as the port left it. The screen notice that says Nothing was changed can then be false for the reference only. The app's real credential reference holder (`FileCredentialRefHolder`) writes through a temp file and one atomic move, so a failed write there leaves its previous reference in place; the limit applies only to a `Keystore` that changes the reference and then throws.
- **The temp name is fixed.** It is never random, so a stale `<file name>.tmp` left by a crash is overwritten by the next save, and `load()` never reads it. The restore temp name, `<file name>.restore`, is fixed too and is never read by `load()`.
- **The test seam.** The in-module test seam is an internal constructor that takes the write and the move as functions. The public constructor `SettingsFileStore(file, keystore)` is unchanged and supplies the real steps.
