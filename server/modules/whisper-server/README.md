# whisper-server — README

**Status:** in progress by the maintainers' team (issue #9). This branch is the module's main PR; it stays open until the module is complete.

Breaker's own transcription service on the server. The phone sends audio to it, it queues each job in order, and it forwards the audio to whichever speech-to-text service the admin has set up (by default an existing Whisper X service). It never runs a speech model itself. Callers get a job id and poll for the result.

Full module card, including how to run its tests: `AGENTS.md` in this folder.

## HTTP front

`POST /v1/audio/transcriptions` takes a multipart `file` (the audio) and a `model` part; both are required. An optional `language` part is accepted and ignored for now. The upload is capped at 25 MiB; over that is refused. It answers `202 { job_id, status: "queued" }` and wakes the worker. `GET /v1/jobs/{job_id}` answers the job's status and, once done, its result; an unknown id is 404.

The `model` part is required but NOT stored yet - the configured downstream model serves every job today; per-request model selection is a later schema change. There is no auth on these routes yet; every job is enqueued under one owner until the auth module lands.
