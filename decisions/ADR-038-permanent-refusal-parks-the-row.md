# ADR-038: A permanent refusal parks the row and the queue moves on

**Status:** accepted by the manager
**Date:** 2026-11-07
**Approved by:** the manager set the rule; the project owner has not seen it

## Context

Today a row the server refuses for good blocks the whole push queue. The uploader
answers FAILED, the drain stops at that row, counts one failure, and every row behind
it waits forever. `android/modules/sync/AGENTS.md` lines 84 and 85 say "until a later
permanent-refusal path exists", and line 93 says "Not Built Yet".
`QueuedSyncService.kt` KDoc "Known gap" lines 99-101 name the same gap. Nothing in the
phone code tells a permanent refusal from a transient failure.

`POST /v1/sync` answers 204, 400, 401, 403, 409, 413, 503 per `openapi.yaml` and
`SyncResponses.kt`. `SyncResponses.kt` sends 400 for a bad body, 401 for no token,
403 for an agent token, 409 for a key_version conflict, 413 for a body over 1 MiB, and
503 for a storage failure or failed session check. A grep of
`server/modules/sync-api/src/main` for 422 finds no matches: the server never answers
422 on this route.

## Decision

1. **Only a refusal about the row itself is permanent.** A 4xx on the row body (400
bad body, 413 body too large) is permanent. Never 401, 403, 429, any 5xx, or a network
error: those stay transient and keep the row queued.
2. **422 is not answered by this route.** A grep of `server/modules/sync-api/src/main`
finds no 422. If a later version adds it, the table below must be revisited.
3. **A permanent refusal parks the row.** It stays in the queue store, is marked parked,
is never deleted, is never retried, and the queue moves on to the next row.
4. **SyncReport shows how many rows are parked.** The reason of each stays in the store
and is readable through a read method (rule 7). Nothing is lost silently.
5. **No code is built by this ADR.** The permanent-refusal path is planned, not built.
A later RC writes the code.
6. **On disk: a new column plus a reason text on the queue row.** The code step carries
the store migration and a test on an old-format store.
7. **SyncReport gains one additive count field with a default.** No existing caller
breaks. Reasons are not in SyncReport; they stay in the store behind a read method. The
core card is amended in the same code PR. The manager approves this one additive change
to a core type, as project leader.
8. **A delete removes a parked row.** Nothing retries a parked row automatically. An
explicit retry-parked action is a later feature.
9. **No screen in this ADR.** User view: only the count in SyncReport, the reason
readable from the store.

Status table (status, row-level or not, permanent or transient):

| Status | Row-level | Permanent or transient |
| --- | --- | --- |
| 204 | no | no (success) |
| 400 | yes | permanent |
| 401 | no | transient |
| 403 | no | transient |
| 409 | not decided here: keeps today's behaviour (blocks the queue) | not decided here |
| 413 | yes | permanent |
| 422 | not answered | not answered |
| 503 | no | transient |

## Known remaining gap

409 keeps today's behaviour: a row pushed at a lower key version than the server holds
fails on every retry until it is re-saved (the ADR-035 resave), so treating it as
transient keeps the queue blocked - the very bug this ADR fixes. 409 gets its own ADR
with the resave flag.

## Decided

1. A permanent refusal parks the row: stays in the store, marked parked, never deleted,
never retried, queue moves on.
2. On disk: a new column plus a reason text on the queue row.
3. SyncReport gains one additive count field with a default; reasons stay in the store
behind a read method; the core card is amended in the code PR.
4. A delete removes a parked row.
5. Nothing retries a parked row automatically; an explicit retry-parked action is a
later feature.
6. No screen in this ADR: user view is only the count in SyncReport, the reason
readable from the store.

## Decided in the code step

1. **Resave:** the resave of the same id is one atomic step (one transaction or one statement) on both doubles and on the adapter. The check "is parked?" and the replace "clear parked reason and refresh values keeping seq" run inside one database transaction. The replaced row is a fresh pending row that goes through the normal drain and keeps its seq. A resave of a LIVE id is unchanged: first copy kept, as today (ADR-035 owns that), and there is a test proving it. (Resolves open point 1.)
2. **Classification:** only 400 and 413 are permanent. A refusal carrying any other status is treated as FAILED: nothing parked, the row stays queued, and the drain stops at that row as today. A thrown non-refusal exception keeps today's behaviour (FAILED, nothing parked).

## Open

1. The 409 ADR: its own ADR comes with the resave flag.

## Consequences

Rules in: the nine rules above; the status table; parked means stay, mark, never delete,
never retry; SyncReport shows the count of parked rows and the store holds each reason;
the code step carries the store migration, a test on an old-format store, and the same-id resave test.

Rules out: no code built now; 401, 403, 429, any 5xx, or network errors stay transient;
422 not answered; nothing beyond what parked means on disk or for the user.
