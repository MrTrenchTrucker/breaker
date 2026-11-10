# whisper-server — README

**Status:** on main. Session-token auth is wired into the transcription routes (enqueue uses `use`, poll uses `peek`, a refused token is one plain 401, an unreachable token check a plain 503); the card lists the module's surface.

Breaker's own transcription service on the server. The phone sends audio to it, it queues each job in order, and it forwards the audio to whichever speech-to-text service the admin has set up (by default an existing Whisper X service). It never runs a speech model itself. Callers get a job id and poll for the result.

The job store, the queue worker, the forwarder, the HTTP front and the runnable entry point (`WhisperServer.kt`, `WhisperConfig.kt`) are all on main. See the card.

Full module card, including how to run its tests: `AGENTS.md` in this folder.

## HTTP front

`POST /v1/audio/transcriptions` takes a multipart `file` (the audio), the only required part. Optional `model` and `language` parts are accepted and ignored for now. The upload is capped at 25 MiB; over that is refused. It answers `202 { job_id, status: "queued" }` and wakes the worker. `GET /v1/jobs/{job_id}` answers the job's status and, once done, its result. A done result is
idempotently re-fetchable for a short TTL after completion (default 10 minutes,
`BREAKER_WHISPER_RESULT_TTL_S`, an upper bound of 86400 s = 24 h): every GET inside that window returns the same `done` result. After
the window a store purge erases the result and a GET returns `404` reason `unknown or expired` — an
unknown id gives the same `404`. Audio is erased on completion (ADR-010), unchanged.

The `model` part is optional and ignored - the configured downstream model serves every job today; per-request model selection is a later schema change. These routes require a caller session token in the `Authorization: Bearer` header; a missing, not-live, unavailable, or foreign-scheme token is refused with one plain 401, and each job is owned by the account the token resolves to.

**Token check (built).** Jobs are owned by the account of the caller's session token, and a missing, unknown, revoked or expired token is refused with one plain 401 and nothing enqueued. The module asks sync-api over HTTP whether the presented token is live (`ARCHITECTURE.md` R31, ADR-015): the enqueue moves the token's expiry via `use`, polls and the result fetch use the read-only `peek` check, and when sync-api cannot be reached the request is refused with a plain 503 (fail closed) and nothing is enqueued.

**Agent-token request limit (built, optional).** Off unless
`BREAKER_WHISPER_AGENT_RATE_LIMIT_PER_MIN` is set to a whole number from 1 to 10000. An agent token
that sends more requests than that inside a fixed 60 second window gets 429 with a `Retry-After`
header; both routes share one counter per token, kept in memory. A session token is never limited.

Breaker serves plain HTTP; TLS is the admin's choice through a reverse proxy in front of it (ADR-034).
