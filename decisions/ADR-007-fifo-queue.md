# ADR-007: FIFO transcription queue

**Status:** accepted
**Date:** 2026-09-29

## Context
CPU Whisper is single-request-per-core; concurrent requests would thrash it.
Multiple users (and agent tokens) share the server.

## Decision
`POST /v1/audio/transcriptions` enqueues and returns `{ job_id, status }`; a
single worker processes one job at a time (strict FIFO, fair across users),
forwarding to the admin-configured transcription service; `GET /v1/jobs/{id}`
returns status. Jobs persist in SQLite (restart-safe). No priority in v1.
Failed jobs auto-retry 3× at 10 s intervals; audio deletes on completion.

## Reasons
- FIFO is fair, simple, and restart-safe; the queue wraps whatever service is
  configured.

## Consequences
Rules in: job API, retry policy, per-user rate limits. Rules out: priority
queue in v1 (Local Inference Fast Lane is a possible future extension).
