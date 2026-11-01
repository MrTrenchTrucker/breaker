# whisper-server — README

**Status:** in progress by the maintainers' team (issue #9). This branch is the module's main PR; it stays open until the module is complete.

Breaker's own transcription service on the server. The phone sends audio to it, it queues each job in order, and it forwards the audio to whichever speech-to-text service the admin has set up (by default an existing Whisper X service). It never runs a speech model itself. Callers get a job id and poll for the result.

None of it is on main yet: the first three slices are on the module branch, and the runnable entry point (slice 4) is planned, not built. See the card.

Full module card, including how to run its tests: `AGENTS.md` in this folder.

## HTTP front

`POST /v1/audio/transcriptions` takes a multipart `file` (the audio), the only required part. Optional `model` and `language` parts are accepted and ignored for now. The upload is capped at 25 MiB; over that is refused. It answers `202 { job_id, status: "queued" }` and wakes the worker. `GET /v1/jobs/{job_id}` answers the job's status and, once done, its result. A done result is
idempotently re-fetchable for a short TTL after completion (default 10 minutes,
`BREAKER_WHISPER_RESULT_TTL_S`, an upper bound of 86400 s = 24 h): every GET inside that window returns the same `done` result. After
the window a store purge erases the result and a GET returns `404` reason `unknown or expired` — an
unknown id gives the same `404`. Audio is erased on completion (ADR-010), unchanged.

The `model` part is optional and ignored - the configured downstream model serves every job today; per-request model selection is a later schema change. There is no auth on these routes yet; every job is enqueued under one owner until the auth module lands.

**Token check (open).** Jobs are to be owned by the account of the caller's session token, and a missing, unknown, revoked or expired token refused with nothing enqueued; today the service checks no token. How it learns that a token is live is an open question (`ARCHITECTURE.md` R31).
