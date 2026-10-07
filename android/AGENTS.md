# AGENTS.md — android/ (Kotlin / native Android app)

## Purpose

The phone client: Kotlin/native Android app shell, DI wiring, screens. The phone client for Breaker. Kotlin + native Android, Android 11+
target, arm64-v8a primary. Hexagonal layout: `core` (pure Kotlin ports + use
cases) + `ui` (views) + `modules/*` (adapters).

**Base:** swept OpenWhispr Android fork. Phase 0 applied: package id changed off
`com.edib.openwhispr`, in-app updater deleted, Gradle SHA-256 pinned, no cloud
fallthrough, NOTICE added.

**Priority:** **server-primary** — Local Server via ZeroTier is the primary
transcription + formatting path; on-device sherpa-onnx is the fallback.

**Voice control (v1):** "Breaker Breaker" (wake + auto-record) and "And I'm Gone"
(send) via streaming ASR + phrase matching; a per-user trained KWS model is the
upgrade path (ARCHITECTURE section 8). Shake + tap remain the manual fallbacks.

**Build:** Gradle (pinned distribution SHA-256). Android SDK API 30+ (Android 11+; matches gradle/libs.versions.toml minSdk). SQLite through the history module's own adapter on the platform API (no Room).

**Lanes:** Orchestration owns `core`; Coding owns `modules/*` and `ui`.

**Terminology:** "text commit" / "text insertion" — never "injection."

**Module map (details in each module's AGENTS.md):**
- `core` — domain models, ports, use cases (no Android imports)
- `audio` — mic capture, VAD, noise suppression, WAV encode
- `stt-ondevice` — sherpa-onnx local transcription (fallback; pinned models + checksum.txt [2])
- `stt-server` — client for Breaker's own `whisper-server` job queue (primary)
- `transport` — connectivity probe: is the Local Server reachable (core routes on the answer); no cloud path
- `format` — Whisper Flow-style formatting (server LLM + rule-based local)
- `phrases` — streaming ASR + phrase matching: "Breaker Breaker" wake + "And I'm Gone" send
- `gesture` — shake-to-wake (accelerometer)
- `overlay` — floating tile (WindowManager)
- `commit` — CommitService: IME commit + clipboard fallback
- `history` — SQLite transcription history + 3-month TTL + tombstones
- `settings` — settings persistence
- `sync` — offline-first sync queue (idempotent push)
- `auth-client` — login/register, token storage (Android Keystore), roles/scopes
- `training-client` — record phrase samples, upload, download trained model
- `updater` — daily check + admin force-check, signed APK, rollback
- `crypto` — client-side key derivation (Argon2id + HKDF), DEK generation/wrap/unwrap,
  AES-256-GCM, X25519 box keypair, sealed-box unsealing

**UI (F25, F35):** the app consumes `shared/ui-tokens` (Trucking theme —
white/black/green, light/dark, hard edges, condensed display type) so it looks
identical to the web FE. `ui-tokens` ships plain Kotlin values that mirror the CSS
variables (no UI-framework dependency); `android/ui` applies them.

**Build phase:** Container. It groups the Android modules and was built with the skeleton in Phase 1 (on main).

## Invariants
- F1–F36 and N1–N13 in `docs/01-requirements.md`.

## Owns
The phone client: Kotlin/native Android app shell, DI wiring, screens.

## Public Interface
App entry, Gradle build, app-level wiring

## Depends On
- shared (registered in modules.toml)

## Does Not Own
- Server-side logic (server/*)
- Shared contracts (shared/*)

## Test Locations
- No unit tests: this folder only groups the modules under it and holds no code.
- Contract: `tests/contract/test_android_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_android_contract.py`
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
- Kotlin + native Android — IME and overlay need native APIs, not Flutter.
