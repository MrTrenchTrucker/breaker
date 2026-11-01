# AGENTS.md — server/modules/whisper-server/

## Purpose

Breaker's OWN server container: `/v1/audio/transcriptions` (async enqueue) +
FIFO queue worker + `/v1/jobs/{job_id}` (poll), **forwarding** audio to the
admin-configured downstream transcription service (F26/ADR-008). Default
forwarding target: the existing Whisper X container over a VPN, unmodified.
Do NOT build a new transcription engine — this module never runs Whisper
itself; it queues, shapes the contract, and proxies.

**API (contract in `shared/api-contracts`):**
- `POST /v1/audio/transcriptions` — multipart `file` (WAV/MP3/OGG), optional `model` (ignored),
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
- Health: `GET /health` → `{ status: "ok", forwarding_to: "...", uptime }`, unauthenticated. `uptime` is the running time rendered as `HhMmSs` (for example `1h2m3s`), measured from a monotonic clock (`System.nanoTime`), so a wall-clock step cannot change it. `forwarding_to` is the configured downstream reduced to scheme + host + port only (no path, userinfo, query or fragment, any of which can carry a token); it is the literal `unparseable` when the URL is not http or https, has no host, or has a port that is not 1-65535. Both values are computed once at startup by `buildTranscriptionApi`.

**Build phase:** Phase 4; it can start right after Phase 0. The token check client is built (`HttpTokenCheck`, `http/HttpTokenCheck.kt`), and the two internal token-check routes it calls are built in `sync-api`; wiring the check into the enqueue and poll routes, and the owner sealing (Phase 19/20), are not built. Jobs are to be owned by the token's account and a missing, unknown, revoked or expired token refused; this module will ask sync-api over HTTP whether a token is live and fail closed when it cannot (ARCHITECTURE R31, ADR-015); the enqueue moves a session token's expiry, polls and the result fetch use a read-only check; not built. Needs first: `api-contracts`.

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
- `POST /v1/audio/transcriptions` → enqueue → `{ job_id, status: "queued" }` (`job_id` is a JSON string).
- A single worker processes one job at a time (strict FIFO, fair across users),
  forwarding audio (WAV 16 kHz mono PCM) to the **admin-configured
  transcription service** (F26) — Docker network, IP, or external URL. The
  worker never starts the next job until the active one returns.
- `GET /v1/jobs/{job_id}` → `{ status: queued|processing|done|failed, result }`.
- Jobs persist in SQLite → restart-safe (no queued audio lost).
- No priority in v1; per-user rate limits + job size caps (T18).
- **Result retention (ADR-018):** a completed job's `result` stays fetchable for `RESULT_WINDOW_MS` after the job finished (default 10 minutes, set by `BREAKER_WHISPER_RESULT_TTL_S`, at most 24 hours); every `GET /v1/jobs/{job_id}` inside that window returns the same result, and after the window the result is erased and the answer is `404` (unknown or expired). Until then the result sits here in **plaintext**, same as the queued audio does before the job finishes.

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
nothing is sent to sync-api (Phase 19/20, not built); the agent's own
`GET /v1/jobs/{job_id}` read is unaffected either way, so nothing about F27
breaks for that job. If the account does have a box public key, this module
seals a copy of the plaintext result to it with **libsodium
`crypto_box_seal`** (a libsodium JVM binding — no Tink; ADR-018), then
forwards the sealed copy to sync-api (Phase 19/20, not built). Before Phase
19/20 ship, no account has a box public key yet, so agent-token results are
simply not sealed — that lands with the rest of ADR-018, not before, and the
same no-key rule above is what covers it, not a separate phase check.

## Owns
/v1/audio/transcriptions + FIFO queue worker + job API, forwarding to the admin-configured service, and (for agent-token jobs whose owner has a box public key) sealing a copy of the completed result to that key for the sync-api pending-sealed store (Phase 19/20, not built) (ADR-018).

## Public Interface
TranscriptionApi, QueueWorker

## Depends On
- server (registered in modules.toml)
- shared_api_contracts (registered in modules.toml)

## Does Not Own
- The configured transcription service itself
- Auth (sync-api)
- TLS and certificates: Breaker serves plain HTTP; an admin who wants https puts a reverse proxy in front (ADR-034)

## Test Locations
- Unit (Kotlin/Ktor, ADR-017): `server/modules/whisper-server/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :server:modules:whisper-server:test` once this module's build file applies the Kotlin plugin (today it applies `base` only, so there is no test task yet).
- Token check client: `server/modules/whisper-server/src/test/kotlin/dev/breaker/server/whisper/http/HttpTokenCheckTest.kt` runs `HttpTokenCheck` against a local fake server on 127.0.0.1: live, not live, unavailable (403, 500, no account number, refused connection, timeout), both paths and both headers, an empty or non-ASCII token never sent, a blank address or key refused, and nothing printed.
- Token check client, bounded answer: `server/modules/whisper-server/src/test/kotlin/dev/breaker/server/whisper/http/HttpTokenCheckBoundTest.kt` runs `HttpTokenCheck` against fake servers that send too many bytes, exactly 4096 bytes, headers and then a body held back past the total timeout, a partial answer then a held body, a normal answer and a 401, and a closed port. `server/modules/whisper-server/src/test/kotlin/dev/breaker/server/whisper/http/HttpTokenCheckSocketCloseTest.kt` runs `HttpTokenCheck` against a dribbling fake server and asserts that when the total deadline fires the body subscriber cancels itself and closes the socket so the exchange is not left open (BUILT).
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
- On main this module has source code: the database, the job store, the queue worker, the forwarder, the HTTP front and the runnable entry point (`WhisperServer.kt`, `WhisperConfig.kt`).
- On main: a runnable entry point, `main`, configured by `BREAKER_WHISPER_HOST`,
  `BREAKER_WHISPER_PORT`, `BREAKER_WHISPER_DB` and `BREAKER_WHISPER_FORWARDER_URL`; it binds 127.0.0.1 by default,
  refuses a bad configuration, and shuts down cleanly with the active job put back in the queue. An `IOException`
  while reading an upload is a failure of its own, never a 413.
- The link from whisper-server to sync-api is an HTTP contract, not an import. whisper-server asks sync-api over HTTP whether a token is live, through the two internal token-check routes and a service key. No code imports sync-api, so no sync-api entry is listed under Depends On, and modules.toml lists none.
  The sealing hand-off to sync-api (ADR-018) is unshipped (Phase 19/20). ADR-018's amendment of 2026-10-20 chooses no transport; a new ADR decides the transport when that phase is ordered.
- FIFO: never start the next job until the active one returns; retry 3× at 10 s.
- Written in Kotlin on the JVM with Ktor, compiled against api-contracts' Kotlin types (ADR-017).
- The seal step only ever runs for an agent-token job, and only seals a copy
  — it never replaces the plaintext result the requester itself polls for
  (ADR-015's job contract is unchanged, ADR-018).
- Sealing is unauthenticated by construction (`crypto_box_seal`): this module
  trusts whatever `box_pubkey` sync-api (Phase 19/20, not built) hands back. It is not a defense
  against an attacker who can already write that row, and the pending-sealed
  store it forwards to is only as trustworthy as whoever can write to it —
  a forged row there converts into an ordinary-looking transcription on the
  owner's next login, with no review (T26, ADR-018).
- An owner account with no box public key yet gets no sealed copy, not a
  dropped job and not a plaintext one — see the Owner sealed-box step above.
- A job-store failure never ends the worker: each store call goes through one retry helper that
  pauses and tries again, a failed complete or fail is held and re-sent, and a cancelled worker still
  stops at once. The one place this module logs is a single `java.util.logging` line in
  `worker/QueueWorker.kt` for such a failure, carrying only the operation, the job id and the
  exception class name — never the exception message, the audio or the result. `SourceScanTest`
  allows `Logger` in that one file only (it is still reported in every other main file) and
  `QueueWorkerLoggingTest` pins what the line may contain. Reason: a store failure that stalls the
  queue without a trace is worse than one minimal line.

## Forwarder contract
- A forwarder must bound its own call: it must not make an unbounded HTTP request.
  The worker's retry logic assumes each forward completes in a reasonable time.
- The stored error text is bounded at 500 characters (JobPolicy.ERROR_TEXT_LIMIT).
  The worker applies .take(ERROR_TEXT_LIMIT) to the full formatted failure string.
- The forwarder is `HttpTranscriptionForwarder`. It makes ONE attempt per call and never retries
  itself; the queue worker owns the retry schedule (3 attempts, 10 s apart).
- It asks the downstream for `verbose_json` (`response_format=verbose_json`).
- `TranscriptionResponse` reads a plain-text body as the text, with no segments and an empty language.
- The request timeout defaults to 60 s and is admin-set (`DownstreamConfig.requestTimeout`); it
  bounds how long a long audio upload may take, so a stuck downstream cannot hold the worker.
- Failure messages are fixed strings: they never contain the downstream URL or the API key.
- A 2xx answer whose content type is not JSON is refused before parsing, so the plain-text branch
  of `TranscriptionResponse` is reached only by direct callers, never through the forwarder.

## Token check client
- `TokenCheck` is the port that asks sync-api over HTTP whether a presented token is live: `use` (moves a session token's expiry) and `peek` (read only). `HttpTokenCheck` is the adapter: it POSTs to `/internal/v1/token/use` or `/internal/v1/token/peek`, with the service key in `X-Service-Key` and the presented token in `X-Presented-Token`.
- The answer is `Live(accountId)` for a 200 whose body parses as `"account_id":N` with N a whole number of 1 to 18 digits followed by optional white space and then a comma or closing brace; `NotLive` for a 401; and `Unavailable` for anything else (403, 5xx, a 200 without a clean account number, 19+ digits, `12abc`, `7.5`, `-3`, a refused connection, a timeout). The caller refuses the request on `Unavailable`.
- A token that is empty, or holds a character outside visible ASCII, is answered `NotLive` without a call.
- The call is bounded: 3 s to connect and 5 s in total, and the 5 s cover the answer body as well as the headers; the answer body is read up to 4096 bytes, more is `Unavailable`. Every failure of the call except a cancel of the caller is `Unavailable`. When the total deadline fires, the body subscriber cancels itself and closes the connection so the exchange is not left open (BUILT). The token and the key are never logged or put in an error text.

## HTTP front

- The two public routes live in `http/TranscriptionRoutes.kt` and are installed by
  `http/TranscriptionApi.kt` (`TranscriptionApi(store, wakeWorker).install(app)`), the module's
  public entry point. The runnable entry point ships here; the engine is CIO, and tests use
  `embeddedServer`.
- `POST /v1/audio/transcriptions` takes a multipart `file` part (required); optional `model` and
  `language` parts are accepted and ignored. It enqueues a job and answers
  `202 { job_id, status: "queued" }` (`job_id` a JSON string), then wakes the worker.
- The upload cap is 25 MiB (`MAX_AUDIO_BYTES`), the OpenAI audio-transcriptions limit this
  OpenAI-shaped front follows. It is enforced while the part is read (a counted read plus Ktor's
  form-field limit), never by reading the whole body first; over the cap is `413`.
- A missing or empty `file` part is `400`; a missing or blank `model` part is accepted and ignored.
- `GET /v1/jobs/{job_id}` answers `{ status, result }` for a known id and `404` for an unknown one;
  a `failed` job also carries the stored `error`. Results stay fetchable for the result window
  (see Result retention above).
- **Honest limits of this slice:** the `model` part is optional and ignored (the forwarder sends the
  configured downstream model for every job); the `language` part is not stored; there is no
  auth yet, so every job is enqueued under one owner constant (`OWNER_ACCOUNT_ID`) until `sync-api`
  lands; no audio-format check is done (the downstream service decides).
- `json/Json.kt` holds the one shared `escapeJsonString`, used by both the forwarder and the HTTP
  layer so the two cannot disagree about escaping.
