# ADR-039: What a 409 on the sync push should do

**Status:** accepted by the manager
**Date:** 2026-11-07
**Approved by:** the manager decided option 4; the project owner has not seen it

## Context

ADR-038 parks a row the server refuses for good (a 400 or 413 about the row itself). It leaves the
409 answer of `POST /v1/sync` alone on purpose: today a 409 is `FAILED`, the drain stops at that
row, and every row behind it waits. ADR-038 names this as its own remaining gap and leaves it to
this ADR.

`POST /v1/sync` answers 409 in two ways. `SyncResponses.kt:44` (`respondAboveAccount`) sends
"key_version is above the account's current key_version" when the pushed key version is above the
account's current one. `SyncResponses.kt:48` (`respondLowerRefused`) sends "A lower key_version is
refused for this client_id" when the same client id is stored at a higher key version, or a
brand-new client id is below the account's current key version. Both are mapped at
`SyncRoutes.kt:47-48`. The store raises them at `TranscriptionStore.kt:29-35` and
`TranscriptionStore.kt:48-49` and `TranscriptionStore.kt:57-58`.

`POST /v1/auth/keys` IS built on the server (`AuthKeysRoute.kt`). It calls `AccountStore.storeKeys`
at `AuthKeysRoute.kt:50`, which raises `key_version` from 0 to 1 at `AccountStore.kt:316`. But no
client calls `/v1/auth/keys` today. The web FE's `signin-net.js` calls only `/v1/auth/salt`,
`/v1/auth/login`, `/v1/auth/handoff/start` and `/v1/auth/logout`. The Android auth-client's
`ServerAuthService` does only register, login and logout. The crypto module has no upload-keys
function. The docs describe the keys call as planned for Phase 19
(`android/modules/auth-client/AGENTS.md:8`). So an account can reach key_version 1 on the server,
but in practice none does today, and a 409 on `POST /v1/sync` cannot occur until a client that calls
`/v1/auth/keys` is built.

The phone pushes every row at `key_version` 0 (`SyncBodyCodec.plainPush` at `SyncBodyCodec.kt:66`,
called from `QueuedSyncService.kt:156`). If an account were at key_version 1 and the phone pushed a
brand-new client id at 0, the store would answer RefusedLower (409) at
`TranscriptionStore.kt:34-35`. If the phone pushed a stored client id at 0 while the account holds
it at 1, the store would also answer RefusedLower at `TranscriptionStore.kt:57-58`. Either way the
drain gets `UploadOutcome.FAILED` and stops at that row (`QueuedSyncService.kt:136`), and every row
behind it waits.

`SyncReport` (`android/modules/core/src/main/kotlin/dev/breaker/dictation/core/model/Sync.kt:10`)
has only `pushed` and `failed`. It has no parked field. ADR-038 added one additive `parked` count
field as approved once by the project leader; a second field needs the same approval.

The drain's KDoc and the sync card (Known Gotchas) say that a 409 is not covered by ADR-038 and
stops the drain at its row.

## Decision

Option 4. No code and no rule now. A 409 keeps today's behaviour: the drain gets `FAILED`, stops
at the row, and the row stays queued. The options and the open questions below stay open.

The trigger to revisit is the first of these: any client calls the keys route (`/v1/auth/keys`), or
the key_version 1 form is designed. A new ADR then chooses between park (option 1), skip (option 2)
and re-encrypt (option 3), on the evidence of that day.

Options 1 to 3 were written before ADR-038 step 2 landed. Where they say the parking code or its
migration is not built, it is built now (ADR-038, main).

## Reasons

A 409 cannot occur today. It needs an account above key_version 0, the keys route is the only code
that raises it, and no client calls that route (web FE, Android auth-client and crypto: no call). The
phone pushes every row at key_version 0. A rule for a case that cannot happen would be a guess, and
the best rule depends on how the key_version 1 form is built (what the phone can re-encrypt).

The resave flag does not help: it is about pushing an id the phone itself deleted (ADR-035 point 6),
not about key versions. An earlier frame of this question said the opposite; the evidence in the
context above shows it was wrong.

## Consequences

No code changes. ADR-038's remaining-gap text, the sync card and the sync README say that a 409
cannot occur today and point here. The queue behaviour for a 409 is unchanged and untested by
design. The day a client calls the keys route, this ADR is revisited before that client ships.

## Options

### Option 1: Park the 409 row like a permanent refusal

The row stays in the queue store, marked parked, never deleted, never retried. The drain skips
parked rows and moves on to the next row, exactly as ADR-038 does for a 400 or 413. `SyncReport`
shows the parked count (one additive field, approved once for ADR-038; this would be the same
field). The store keeps each parked reason behind a read method; nothing is lost silently. A delete
removes a parked row (ADR-038 rule 8). Nothing retries a parked row automatically; an explicit
retry-parked action is a later feature.

Changes in the drain: `QueuedSyncService.kt` treats a 409 as permanent (park) instead of `FAILED`
(stop). Changes in the store: a parked column and a reason text on the queue row (the ADR-038
migration). Changes in `SyncReport`: one additive `parked` count field with a default. This touches
ADR-038 rules 1, 3, 4, 7 and 8.

Cost and what can go wrong: a 409 that is really transient (a brief account-state glitch) becomes
permanent and the row never retries. A parked row stays parked unless an explicit retry-parked
action is built later. The row needs the key_version 1 form to exist before a 409 can occur; on
today's tree no account is above key_version 0.

What it needs that does not exist yet: a client that calls `/v1/auth/keys` so an account reaches
key_version 1 (not built; the card line is `android/modules/auth-client/AGENTS.md:8`, key bootstrap
in Phase 19). The ADR-038 migration (new column plus reason text) is not built (ADR-038 rule 5: "No
code is built by this ADR").

### Option 2: Leave it queued but let the drain walk past it (skip it)

This is the manager's lean.

The row stays queued at `key_version` 0. The drain skips the 409 row (does not send it, does not
remove it) and continues to the next row. Each drain that reaches the row counts it again in
`SyncReport.failed`, as a skipped row does today (`QueuedSyncService.kt:97` and `AGENTS.md:84`). The
row retries on every drain. The push is refused again on every drain while the account's key
version is above the version the row is pushed at (0). It is accepted only after something re-encrypts
the row at the current key version; no mechanism exists yet to do that.

Changes in the drain: `QueuedSyncService.kt` treats a 409 as a skip (like a skipped row) instead of
`FAILED`. Changes in the store: none. Changes in `SyncReport`: none (the row keeps counting as
failed on each drain). This touches ADR-038 rules 1 and 3 only by contrast (it does not park; it
keeps the row queued).

Cost and what can go wrong: the row retries forever and never succeeds until something re-encrypts it.
Each drain that reaches it counts one failed, so `SyncReport.failed` grows without bound for that
row. A row skipped forever is stuck and visible, never sent (the row stays queued but never leaves).
An unbounded queue is not a risk (the row takes one slot) but a permanently failing row is.

What it needs that does not exist yet: a way to re-encrypt a queued row at the current
key version. Nothing built today. `android/modules/sync/AGENTS.md:97` says "Everything at
key_version 1 or higher ... is not built." The resave flag itself is built on the server
(`SyncRoutes.kt:37`, `TranscriptionStore.kt:21`) but it reanimates a deleted row; it is not about
key versions (`ADR-035` point 6 and `SyncRoutesPushResaveTest.kt:53-231`).

### Option 3: Re-encrypt and retry automatically

When the drain gets a 409 on a row, it re-encrypts the row at the account's current key version and
retries the push at that key version. If the retry answers 204, the row is removed. If it answers
409 again, the row stays queued and the drain moves on (like option 2).

Changes in the drain: `QueuedSyncService.kt` catches a 409, re-encrypts the stored text under the
current key version, rebuilds the push, and retries once. Changes in the store: none (the row's
stored text is unchanged; only the push body changes). Changes in `SyncReport`: none extra. This
touches no ADR-038 rule directly (it does not park).

Cost and what can go wrong: re-encryption needs the account's DEK of the current key version, which
the phone may not hold (the phone derives keys at login; ADR-006). A retry storm is not a risk (one
retry per row per drain) but a row that always fails at the new key version stays queued forever.
The re-encrypt step is not built (`android/modules/crypto/AGENTS.md:225-228` says joining the nonce,
ciphertext and tag into one base64 string for key_version 1 is "not built in this module").

What it needs that does not exist yet: the key_version 1 encryption form (not built;
`android/modules/sync/AGENTS.md:97`), the phone's DEK for the current key version (not built; Phase
19), and a way to know the account's current key_version on the phone (login returns it;
`ResponseDecoder.kt:61` parses it, but using it for a push is not built).

### Option 4: Decide now, not later

Treat the 409 as permanent (option 1) now, because the key_version 1 form is not built and no
account is above key_version 0 today, so no 409 can occur today. The ADR is written so the code step
is ready when the Phase 19 client lands. This is not a separate technical option; it is option 1
with a timing decision attached.

The evidence in step 2 of the brief shows a 409 cannot happen yet (no client calls `/v1/auth/keys`,
so no account is above key_version 0). That means the decision can wait until the key_version 1 form
is designed, or be made now as option 1 so the code step is ready. This ADR lists both; the manager
picks.

## My pick (a pick, not a decision)

Option 4. A 409 cannot happen today because no client calls `/v1/auth/keys`, so no account is above
key_version 0. Writing the code now means the drain change is ready when the Phase 19 client lands;
waiting means re-working the same drain later. The manager decides.

What I would want the manager to check first: whether the key_version 1 form is on the Phase 19
roadmap, and whether a parked row needs an explicit retry-parked action later.

## Open questions

1. Which option does the manager pick? 2. Does the parked count field in `SyncReport` need the
project leader's approval again (it was approved once for ADR-038; this is the same field)? 3. If
option 1 or 4: what re-encryption path (if any) unparks a row when the account's key version
advances? 4. If option 2: how does the user learn that one row is stuck and never retries? 5. If
option 3: does the phone hold the DEK for the account's current key version, and how does it get it?
6. Should the 409 ADR be written now or when the key_version 1 form is designed (option 4 timing)?
