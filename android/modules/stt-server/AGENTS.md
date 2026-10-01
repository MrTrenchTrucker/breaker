# AGENTS.md — android/modules/stt-server/

## Purpose

Client for Breaker's own `whisper-server` job queue (primary path). This
client never talks to the Whisper X container directly — it talks to
Breaker's server, which forwards to whichever transcription service the
admin has configured.

**Build phase:** Phase 5. Needs first: `core` (on main) and `api-contracts`.

## Owns
Enqueue + poll against Breaker's `whisper-server`; present the result as a
single `SttResult` once the job completes.

## Public Interface `core.SttEngine` (server path).

**In:** WAV bytes + model/language params.
**Out:** `SttResult(text, segments, language)` — raw text (formatting is the
`format` module's job). The job-queue mechanics (enqueue, poll, backoff) are
hidden behind this same port — `transport` and everything above it needs no
change for the async design underneath.

**API (async job contract, see `shared/api-contracts`):**
- `POST https://<zt-ip>:<port>/v1/audio/transcriptions` multipart: `file`
  (WAV), `model`, `language` → `202 { job_id, status: "queued" }`
- Poll `GET /v1/jobs/{job_id}` (short interval + cap, e.g. 500 ms up to N
  tries) → `{ status: queued|processing|done|failed, result }`; on `done`,
  extract `{ text, segments, language }` into `SttResult`; on `failed`,
  `SttResult.failure`.
- **This endpoint lives on Breaker's `whisper-server`, built in Phase 4** — not
  on the Whisper X container, which this client never addresses. Until Phase 4
  ships, server mode reports `SERVER_UNREACHABLE`/endpoint-missing.
- Auth: `Authorization: Bearer <apiKey>` (Android Keystore, never logged).

**Transport:** HTTPS over ZeroTier. TLS cert verification against Local
Server's cert infra (verify state first — R9). Short timeouts (connect 1.5 s
on the initial POST) so a dead server is reported fast; polling has its own
bounded budget so a stuck job degrades to a clear error, not a hang.

## Invariants
- Server transcription E2E from phone over ZeroTier (F2) — after Phase 4.
- Correct JSON parse for both the enqueue response and the poll response,
  incl. segments + timestamps on `done`.
- Timeout/error at either step surfaced as `SttResult.failure` — never a
  hang (R4).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_api_contracts (registered in modules.toml)

## Does Not Own
- Local transcription (stt-ondevice)
- The server queue (server/whisper-server)

## Test Locations
- Unit (Kotlin): `android/modules/stt-server/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:stt-server:test`
- Contract: `tests/contract/test_stt_server_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_stt_server_contract.py`
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
- OpenAI-compatible contract lives in shared/api-contracts — no drift.
- The Gradle boundary check does not require the
  `project(":shared:modules:api-contracts")` edge here while
  `shared_api_contracts` is base-only (it publishes no artifact); the
  moment it gains an artifact plugin the edge becomes required.
