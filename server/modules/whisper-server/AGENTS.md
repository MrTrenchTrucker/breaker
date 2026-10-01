# AGENTS.md — server/modules/whisper-server/

## Purpose

Breaker's OWN server container: `/v1/audio/transcriptions` (async enqueue) +
FIFO queue worker + `/v1/jobs/{job_id}` (poll), **forwarding** audio to the
admin-configured downstream transcription service (F26/ADR-008). Default
forwarding target: the existing Whisper X container over ZeroTier, unmodified.
Do NOT build a new transcription engine — this module never runs Whisper
itself; it queues, shapes the contract, and proxies.

**API (contract in `shared/api-contracts`):**
- `POST /v1/audio/transcriptions` — multipart `file` (WAV/MP3/OGG), `model`,
  `language` → `202 { job_id, status: "queued" }`
- `GET /v1/jobs/{job_id}` → `{ status: queued|processing|done|failed, result:
  { text, segments[], language } | null }`
- Optional SSE streaming of partial segments (v1.1).
- Output formats (once done): JSON / SRT / VTT; word-level timestamps.

**Implementation notes:**
- This module does not load a Whisper model. It forwards the WAV to whatever
  the admin has configured (F26); the shape of that downstream call is
  whatever that service natively speaks — Whisper X's existing interface by
  default. This module's job is to make the FRONT of that forwarding
  OpenAI-shaped, queued, and admin-swappable — never to patch the downstream
  container.
- Diarization exists downstream but is NOT needed for this single-speaker
  dictation endpoint (leave it alone; don't wire it in here).
- **Formatting is NOT this module's job** — the app calls the existing
  `/v1/chat/completions` endpoint separately (see `android/modules/format`).
- Concurrency: this module's own queue + single worker is what prevents
  concurrent callers (multiple users, plus any agent tokens per F27) from
  hammering the downstream engine at once — that is the reason this module
  exists as its own container instead of as a patch to Whisper X (F23).
- Health: `GET /health` → `{ status: "ok", forwarding_to: "...", uptime }`.

**Build phase:** Phase 4; it can start right after Phase 0. The token checks come with `sync-api` (Phase 11) and sealing results to the owner in Phase 20. Needs first: `api-contracts`.

## Invariants
- Correct JSON for a known test WAV (golden transcript match).
- Word timestamps present; SRT/VTT output valid.
- Two concurrent requests don't corrupt state (queue works).
- Existing `/v1/chat/completions` behavior unchanged (regression).

- two concurrent requests serialize
- order preserved
- worker restart resumes the queue
- failures surface as `failed` with a clear error
- retry 3× at 10 s verified
- audio deleted on completion (F28).

**FIFO transcription queue (F23):** the transcription service is CPU-bound —
concurrent requests would contend. A queue serializes jobs:
- `POST /v1/audio/transcriptions` → enqueue → `{ job_id, status: "queued" }`.
- A single worker processes one job at a time (strict FIFO, fair across users),
  forwarding audio (WAV 16 kHz mono PCM) to the **admin-configured
  transcription service** (F26) — Docker network, IP, or external URL. The
  worker never starts the next job until the active one returns.
- `GET /v1/jobs/{job_id}` → `{ status: queued|processing|done|failed, result }`.
- Jobs persist in SQLite → restart-safe (no queued audio lost).
- No priority in v1; per-user rate limits + job size caps (T18).
- **Result retention (ADR-018):** a completed job's `result` is deleted from
  this store as soon as its requester fetches it once, and — if nobody ever
  fetches it — 24 hours after the job finished, whichever comes first. Until
  then the result sits here in **plaintext**, same as the queued audio does
  before the job finishes.

**Retry + audio lifecycle (F28):** failed jobs auto-retry **3× at 10 s
intervals** before failing. The audio file is deleted once the job finishes
(success or final failure) — audio does not persist past that point; while a
job is queued or processing, its audio sits in this module's SQLite store
(ADR-010), which is the honest at-rest statement, not "server memory only"
(ADR-018). The completed **result** (above) persists longer than the audio
does — fetched-or-24h, not delete-on-finish — because the requester has to
have a chance to actually poll for it.

**Owner sealed-box step (ADR-018, ships with Phase 19/20):** the requester —
owner device or agent token — always gets its own plaintext result back from
`GET /v1/jobs/{job_id}`, unchanged, on the retention schedule above.
Separately, when the requester was an agent token, this module looks up the
owning account's box public key (the token already carries an owner
account, ADR-009). **If the account has no box public key** — pre-Phase-19
and never yet logged in, or mid-reset (ADR-018) — no sealed copy is made and
nothing is sent to `sync-api`'s pending-sealed store; the agent's own
`GET /v1/jobs/{job_id}` read is unaffected either way, so nothing about F27
breaks for that job. If the account does have a box public key, this module
seals a copy of the plaintext result to it with **libsodium
`crypto_box_seal`** (a libsodium JVM binding — no Tink; ADR-018), then
forwards the sealed copy to `sync-api`'s pending-sealed store. This is a
server-internal call, not part of the public API surface. Before Phase
19/20 ship, no account has a box public key yet, so agent-token results are
simply not sealed — that lands with the rest of ADR-018, not before, and the
same no-key rule above is what covers it, not a separate phase check.

## Owns
/v1/audio/transcriptions + FIFO queue worker + job API, forwarding to the admin-configured service, and (for agent-token jobs whose owner has a box public key) sealing a copy of the completed result to that key and forwarding it to sync-api's pending-sealed store (ADR-018).

## Public Interface
TranscriptionApi, QueueWorker

## Depends On
- server (registered in modules.toml)
- shared_api_contracts (registered in modules.toml)
- server_sync_api (registered in modules.toml)

## Does Not Own
- The configured transcription service itself
- Auth (sync-api)

## Test Locations
- Unit (Kotlin/Ktor, ADR-017): `server/modules/whisper-server/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :server:modules:whisper-server:test` once this module's build file applies the Kotlin plugin (today it applies `base` only, so there is no test task yet).
- Contract: `tests/contract/test_whisper_server_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_whisper_server_contract.py`
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
- FIFO: never start the next job until the active one returns; retry 3× at 10 s.
- Written in Kotlin on the JVM with Ktor, compiled against api-contracts' Kotlin types (ADR-017).
- The seal step only ever runs for an agent-token job, and only seals a copy
  — it never replaces the plaintext result the requester itself polls for
  (ADR-015's job contract is unchanged, ADR-018).
- Sealing is unauthenticated by construction (`crypto_box_seal`): this module
  trusts whatever `box_pubkey` `sync-api` hands back. It is not a defense
  against an attacker who can already write that row, and the pending-sealed
  store it forwards to is only as trustworthy as whoever can write to it —
  a forged row there converts into an ordinary-looking transcription on the
  owner's next login, with no review (T26, ADR-018).
- An owner account with no box public key yet gets no sealed copy, not a
  dropped job and not a plaintext one — see the Owner sealed-box step above.
