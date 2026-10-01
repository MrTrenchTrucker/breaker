# AGENTS.md — server/ (Local Server wiring)

## Purpose

Local Server services: transcription, sync, web, training, deploy. Connect Breaker to the **already-operational** Local Server pipeline — we are NOT
building a new STT engine; `whisper-server` adds an async job-queue front end
and wires the app to it.

**Stack (existing):** CPU Whisper with minimal lag; single/multi-speaker
diarization available; **LLM endpoint `/v1/chat/completions` already exists**
(used for formatting). The existing pipeline runs in the **Whisper X
container**, reachable via ZeroTier — Breaker does not modify it.

**The gap:** nothing today exposes an OpenAI-shaped, queued,
multi-user-safe `/v1/audio/transcriptions`. Phase 4 closes it by building
Breaker's own `whisper-server` container, which forwards to the
admin-configured service (default: the Whisper X container above).

**Formatting:** the app's `format` module reuses the existing
`/v1/chat/completions` endpoint with the strict prompt from
`shared/format-prompts` — no new LLM service needed.

**Lanes:** Coding builds the endpoint add-on; Bug Hunt verifies (ZT bind, auth, TLS).

**Module map:**
- `whisper-server` — add `POST /v1/audio/transcriptions` + **FIFO queue worker +
  job API**, forwarding to the admin-configured transcription service
- `sync-api` — auth (users, roles, agent tokens), sync, updates, retention,
  store clear, log policy
- `web-fe` — Debian container website (client-side decrypt, Trucking UI),
  APK + cert + model hosting, admin panel
- `training` — per-user voice phrase model training (CPU; GPU via Local Inference)
- `deploy` — VPN wiring, TLS, API key, APK signing, git-pull, NOTICE, volumes

**Build phase:** Container. It groups the server modules and holds no code.

## Invariants
- `curl -X POST .../v1/audio/transcriptions -F file=@test.wav` returns `202
  { job_id, status: "queued" }`; `curl .../v1/jobs/{job_id}` (polled) returns the
  finished JSON text once `status: "done"` (via the FIFO queue → configured
  service).
- Service bound to ZeroTier interface only (not 0.0.0.0).
- API key required; TLS verified; no plaintext key in logs.
- Ciphertext only at rest (F24); no plaintext transcriptions in logs (F32).

## Owns
Local Server services: transcription, sync, web, training, deploy.

## Public Interface
Server entry, container wiring

## Depends On
- shared (registered in modules.toml)

## Does Not Own
- Phone client (android/*)
- Shared contracts (shared/*)

## Test Locations
- No unit tests: this folder only groups the modules under it and holds no code.
- Contract: `tests/contract/test_server_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_server_contract.py`
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
- Local Server cert infra state was UNKNOWN — verify before TLS changes (R9).
