# ADR-010: Retention policy — 3-month TTL, audio deletes on completion

**Status:** accepted
**Date:** 2026-09-29

## Context
Transcriptions accumulate; audio is sensitive. The user wants bounded storage
and immediate audio cleanup.

## Decision
Transcriptions auto-delete after **3 months** (server + phone, tombstone-
synced). Audio files delete immediately once the job finishes (success or
final failure) — **while a job is queued or processing, its audio does sit
in `whisper-server`'s SQLite job store** (that is what "restart-safe, no
queued audio lost" requires); audio never persists **past job completion**.
Failed jobs auto-retry 3× at 10 s. Admins can clear the store per-user or for
all users; each user can delete their own transcriptions one-by-one or
delete-all.

**Precise rule (phone and server identical):** a transcription expires when its `created_at` instant is more than 90 days (90 x 24 h) before the purge's current instant. A row exactly at the cutoff is kept. Both sides compute on UTC instants, with no time zone and no calendar months, so the phone and the server always agree on which rows are gone. "3 months" in this ADR and in F28 means this rule.

## Reasons
- Bounded storage, minimal audio exposure, user control over their own data.

## Consequences
Rules in: TTL purge job, tombstones, `/v1/admin/clear-store`, delete-all with
type-to-confirm; audio sitting in the job store for the duration of an
in-flight job. Rules out: indefinite retention, audio retained past job
completion.
