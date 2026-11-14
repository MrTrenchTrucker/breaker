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

**Precise rule:** a transcription expires when its clock instant (phone: `created_at`; server: `stored_at_ms`, the time of the row's last write) is more than 90 days (90 x 24 h) before the purge's current instant. A row exactly at the cutoff is kept. Both sides compute on UTC instants, with no time zone and no calendar months. The two sides use the same window but not the same rows: the server expires a row at the same time as the phone or later, never earlier. "3 months" in this ADR and in F28 means this rule.

## Reasons
- Bounded storage, minimal audio exposure, user control over their own data.

## Consequences
Rules in: TTL purge job, tombstones, `/v1/admin/clear-store`, delete-all with
type-to-confirm; audio sitting in the job store for the duration of an
in-flight job. Rules out: indefinite retention, audio retained past job
completion.

## Amendment 2026-11-12 (the phone keeps 30 days, ADR-047)

The project owner ruled that the phone keeps 30 days of transcriptions and 30 days of its own delete notes (tombstones), and that the server keeps 3 months; a Load more button on the history screen asks the server for older rows, which are shown and not saved (ADR-047). One 30 day window covers both phone values, so the phone's window is a single constant.

The Decision above is unchanged. From now on it describes the server's retention (90 x 24 hours on the server's own clock) and the shape of the rule on both sides (UTC instants, no time zone, no calendar months, a row exactly at the cutoff kept). The phone applies the same rule with a window of 30 x 24 hours, so the server always keeps a row longer than the phone does.

Built: on main the phone keeps 30 days (the history module's `RetentionPolicy` sets its window to 30 days, ADR-047).
