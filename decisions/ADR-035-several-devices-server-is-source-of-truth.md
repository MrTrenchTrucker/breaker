# ADR-035: Several devices per account; the server is the source of truth

**Status:** accepted; the sync routes and the change feed are built on the server; the phone's pull and
merge pieces are built and not wired: PullRun (#441), PullSink (#442), applyRemoteRow (#444), retainOnly
(#445), the pull cursor store (#446), isQueued/queuedIds (#447), PullApplier (#448) and PullCycle (#450);
the clear-store write is built too (#449). The wiring of the pull is decided, not built: no shipped code
builds a PullCycle or a PullApplier or implements the pull transport, so the app does not start a pull. The
phone's side of the sign-in hand-off is built; the purge is built on the server; the points still open are
under To be designed
**Date:** 2026-11-01
**Confirmed by:** the project owner, 2026-11-01, in his own words below

## Context

The project owner decided, 2026-11-01, that one account may use several devices (a phone and a tablet, for example), and how they stay in step:

> "For multi devise support this is what I'm thinking the container will be the source of truth so when I transcribe something on my phone it sends it to the server and then maybe I have a tablet and then the tablet updates that should be pretty easy to set up but will require a code change and that module the agents ah m d needs to be rewritten along with decisions adr."

He then approved three points: encryption stays as ADR-006 decides it; a delete on one device deletes everywhere; a new device does not need its own password prompt:

> "Yes to all 3 for multi device support. If you are logged in though you should not have to reinput your password."

Much of this is already decided. The sync-api card and the API contract list `GET /v1/transcriptions` to pull the account's transcriptions and `POST /v1/sync` to push; both routes are built in sync-api. The history module keeps tombstones and has `applyRemoteDelete` for a delete made elsewhere (history card). What is not decided:

- which module on the phone pulls, when, and how pulled rows merge into local history;
- how a new device gets the account's keys when the password is typed on the website (the sign-in hand-off, ADR-036) rather than on the device. Under ADR-006 the device derives the KEK from the password itself, so a device that never sees the password cannot unwrap the DEK.

On 2026-11-01 the project owner confirmed the design and ordered this update: "Breaker confirmed.  Update the repo when ready."

## Decision

1. **The server is the source of truth for an account's transcriptions.** Every device pushes what it records (`POST /v1/sync` in the API contract; the phone's push queue exists and the server route is built) and pulls what other devices recorded. A device's local history is a copy of the server's, plus whatever is still queued to push.
2. **Offline stays first.** A device with no server keeps recording and reading its local copy. It pushes its queue and then pulls when the server is back. Nothing recorded offline is lost.
3. **The phone's sync module pulls.** `android_sync` gains a pull: after each drain of its push queue it asks the server for the rows changed since its last pull, and hands them to history. Rows are matched by their client id, so a row this device pushed is not stored twice. History stays the only module that writes the local store.
4. **Deletes go everywhere.** A delete on any device leaves a tombstone that is pushed. Built on the server:
   the row is marked deleted, keeps its id, client id and change number, loses its body, and is carried in
   the same change feed as the rows. A delete record is purged once every live user session of the account
   has pulled past it. On the phone (decided, not built): a device that pulls a delete applies it through
   `applyRemoteDelete`; a row deleted anywhere does not come back from another device.
5. **Encryption is unchanged (ADR-006).** Each device encrypts before it pushes. The server stores and returns sealed rows only and never holds the DEK, the KEK or the password.
6. **A new device gets its keys from the website sign-in, sealed for that device.** Built on the phone: the
   key pair, made in memory for one sign-in by `SignInStarterImpl.begin` and held by `SignInHolder`; the
   return flow (`HandoffReturnFlow.handle`, called from `HandoffReturnActivity`), which runs the finish
   through a background so the exchange never blocks the main thread; the exchange call, which carries no
   bearer (`HandoffFinish`); `CryptoService.openSignInPackage`; the account keys kept in a sealed file of
   their own (`CredentialFileVault`, under an Android Keystore key); and the session, saved after the keys.
   A failed store or a logout clears both, each in its own try; an unreadable answer or a package
   that does not open leaves the stores as they were and at most logs out. Built on the server:
   the start and exchange routes
   (`AuthHandoffRoutes.kt`), the code store and the sweep. Not built: the website page (its crypto is a
   stub, `web-fe` `js/signin-crypto.js`), and the phone's pull. The app's device name is built (the platform's manufacturer and model, cleaned to 1 to 64 code points). When the app starts the sign-in hand-off, it makes a fresh X25519 key pair in memory for
   that one sign-in and sends the public half with its sign-in challenge. The pair and the app's one-time
   secret are dropped at the code exchange, or when the sign-in is abandoned. The person types the password
   once, on the website. The website page derives the KEK in the browser (ADR-006's web path), unwraps the
   account's keys and seals them to the app's public key as an anonymous sealed box, the construction ADR-018
   already fixes. The server keeps that sealed package until the exchange deletes it, and never past the
   code's 5-minute expiry. The server passes the sealed package to the app with the session token at the code
   exchange. The app opens it with its private key. It then keeps the account's DEK and box private key at
   rest only as blobs sealed with an Android Keystore AES key, never the server-wrapped forms, and the
   session in its own sealed file with the server address. This is built. The server only ever carries a
   package it cannot open. The hand-off itself is decided in ADR-036. The device's own password sign-in stays
   as the fallback (ADR-036), and it still derives the KEK on the device.

## Reasons

- The owner's words: the container is the source of truth, and the tablet updates.
- The phone's push queue, history's tombstones and `applyRemoteDelete`, and the API contract for both routes already exist; this builds the missing server routes and the phone's pull instead of adding a second sync design.
- Sealing to a key the app made for this one sign-in keeps ADR-006's guarantee for a passive reader of the server: the server stores and forwards nothing it can open.
- The device never asks for the password again once it is signed in, as the owner asked.

## Consequences

- **Trust boundary, named:** the hand-off rests on the website page doing what it should, which ADR-006 already names for the web path: anyone who can change the page the browser loads can change what it does with the password. On plain http (ADR-034) that includes anyone on the network path who can tamper with the page in transit. This is the risk ADR-034 already accepts for plain http, and the admin closes it with https.
- **Modules whose cards and READMEs change:** `android_sync` (pull, cursor, merge hand-off), `android_history` (receiving pulled rows; `applyRemoteDelete` wired), `server_sync_api` (changes-since pull, delete records kept until pulled, sealed key package at the code exchange, the wrong-password limit), `server_web_fe` (sealing the keys in the page), `android_auth_client` (key pair, opening the package). `ARCHITECTURE.md` section 12 and the sync section are updated to match. Each card marks these items as planned, not built.
- **Sync stays development-only.** As ADR-006 (amended 2026-10-01) says, sync is development-only until Phase 19. Nothing in this decision changes that: no release build turns multi-device sync on before encryption by default ships.
- **ADR-006 is amended** by point 6: a device may receive its keys sealed from the website instead of deriving the KEK itself (see the amendment at the end of ADR-006).
- **Built so far, and what is not.** Of the sign-in hand-off, `CryptoService.openSignInPackage` (with its
  parser and the sealed-box open) is built on the phone side, and on the server the start route `POST
  /v1/auth/handoff/start`, the exchange route `POST /v1/auth/handoff/exchange`, migration 4 (the sign-in code
  table, its index, the device name column and the triggers), the code store and sweep, and the revoke rule
  are built. Built on the phone: making the key pair, calling the exchange route, opening the package, and
  storing the keys and then the session (both cleared together on a failure or a logout). Built on the server:
  the device list and the two sign-out routes for the person's own devices. Built on the phone: the app's device name (the platform's manufacturer and model, cleaned to 1 to 64 code points). Not built: the website page. The wrong-password limit is built on the server
  (ADR-036). Of the sync work on the server, the push route `POST /v1/sync`, the plain pull route `GET
  /v1/transcriptions` and the transcriptions table are built, and so is the store layer of the changes-since
  feed: migration 5 (the change counter on the account row, the change number on every transcription row, the
  delete flag, the purged-floor column, the pulled-change column on the session row and the unique index), a
  change number on every insert and replace, and a push of a deleted row that is a no-op. The contract text of
  the feed and the Kotlin types for it are built too. The DELETE route `DELETE
  /v1/transcriptions/{client_id}`, the store delete, the `resave` mark on a push, and the plain list that
  leaves deleted rows out are built too. The `since` parameter, the page object, the 400 answer and the 410
  answer of `GET /v1/transcriptions` are built. The write of the pulled number and the purge of delete records
  are built on the server. Not built: the wiring of the pull. Built and not wired: PullRun (#441), PullSink
  (#442), applyRemoteRow (#444), retainOnly (#445), the pull cursor store (#446), isQueued/queuedIds (#447),
  PullApplier (#448) and PullCycle (#450). The clear-store write (#449) is built and mounted on the server. No shipped code builds a PullCycle
  or a PullApplier or implements the pull transport, so the app does not start a pull. The device name
  column on the session row is built (migration 4); the app now sends the platform's manufacturer and model as the name and the device list is
  built on the server (`GET /v1/auth/devices`) and in the phone's auth service, with no screen yet. Today the app has no pull, and the password sign-in is
  unchanged.
- **Changes-since feed.** Built on the server: the cursor is a server-assigned change number, unique to the
  account and strictly increasing on every row write (insert, replace and delete). It is sent as an opaque
  string and is exclusive: the client asks for the changes after N. It is not the stored time, and stored_at
  stays a field of the row. The reply carries the next cursor. A delete is a flag on the row: the row keeps
  its id, client id and change number, loses its body and is marked deleted, and rows and deletes come in
  one feed in change order. A delete record is purged once every live session of the account has pulled past
  it (built on the server).
  **Decided, not built (phone):** the client stores the cursor and sends it back as a `since` query parameter
  on `GET /v1/transcriptions`. Client-id dedupe stays as a second guard; it is not built on the phone, which
  pulls nothing yet, and the local history keeps one row per id with `INSERT OR REPLACE`.
  **Decided, not built (phone):** A remote delete is applied even if this device saved the same id again.
  **Built on the phone:** A local delete followed
  by a local save of the same id clears the local tombstone, so the row is live again.
  **Device name:** the session row has a nullable device name, and that column is built (migration 4). The
  app sending a name at sign-in is built; the person renaming it is decided, not built. The device list is built
  on the server and, on the phone, as a call of the auth service (`ServerAuthService.devices()`); no screen shows
  it yet. There is no devices table: the device list has one row per session, with a last-seen date derived from the session's
  expiry.
  A device whose session expired signs in again and does a full resync, so it never needs old delete records;
  the full resync is decided, not built (phone). The sync routes and the transcriptions table were built
  first, as their own piece of work, before the cursor.
- **Still to be designed** (before any of it is built): the merge when a pulled row and a local unsent edit share a client id; the encoding of the keys inside the sealed package.

## Decided details of the changes-since feed (sync 1B)

The Decision above says what the feed is for. This section records the details that were decided afterwards,
each with its reason. They are decided, not built, except where it says built. Built on the server: the change
number, the cursor, the answer, the delete record, and the push of a deleted id with its resave mark. Not
built: clear-store, the phone side. The write of the pulled number and the purge of delete records are built
on the server. The word "feed" means the answer of `GET /v1/transcriptions` when the client sends `since`.

- **The change number.** Each account has one counter, kept on the account row. Every write of a transcription row (insert, replace, delete) takes the next number of that account. Reason: one counter that only goes up means a purge can never lower it, so a number is never handed out twice. The migration numbers the rows that already exist 1 to N in id order, per account. This part is built (migration 5).
- **The cursor.** The cursor text is `<account_id>-<change>`. The client treats it as opaque: it stores it and sends it back, and never builds one. Reason: the account tag lets the server refuse a cursor from another account. A cursor of another account, or one that does not match the pattern, is a 400 before any number is read. A cursor above the head (the highest number of the account) is a 200 with no records and `next` set to the head. A cursor below the purged floor is a 410. Built on server.
- **The answer.** The answer is an object `{items, next, more}`. A page holds at most 100 records and 4 MiB, and one record always fits. There is no limit parameter. The client asks again with `since` set to `next` for as long as `more` is true. With no `since` the answer holds live rows only, and the head is read in the same transaction as the rows. Reason: fixed limits keep one answer small, and a client that loops on `next` always ends at the head. Built on server.
- **The delete record.** A delete record holds the client id, its change number, `deleted` true and the stored time of the delete. It has no key version and no body. A delete is sent by `DELETE /v1/transcriptions/{client_id}`. That route answers 204 for an applied delete, a repeat and an unknown id, so a queued delete is never refused for good. An applied delete takes a NEW change number, so every other device sees it after its last pull. The tombstone in point 4 is the phone's local mark of a delete, and the call that carries it to the server is this route. Built on server.
- **The purge.** The purge runs inside the pull and nowhere else. A delete record leaves only by the floor rule: the floor is the lowest pulled number over the account's live session rows, and a record at or below the floor goes, because every live device has seen it. A session that never sent `since` has no pulled number and holds the purge until it ends (30 days idle, sign-out or revoke). The website page must send `since` set to `next` on every call after its first list, so it stops holding the purge. A pull stores the smaller of `since` and the head as the session's number, never a number above the head. Reason: a bad cursor can then never let the purge pass records a device did not receive.
- **Who makes delete records.** Two writes make delete records: a clear-store write, and the server's scheduled TTL expiry job. The job turns each live row older than the TTL (by `stored_at_ms`, the time of the row's last write) into a delete record: a new change number, body cleared, deleted set. It runs on a daily schedule and not inside a pull, selects live rows only, and never raises `purged_through`: only the floor purge does. Reason for delete records and not a silent drop: a device that pulls with `since` would keep a silently dropped row and could push it back with a fresh `stored_at`, which would renew the TTL, while a delete record rides the rule that a push of an already deleted id is answered 204 with no write. A reset of the account drops the rows outright and revokes every session, so no cursor survives it and no delete record is needed. After a full list (no `since`), the phone removes the local rows that are not in the answer and are not waiting in its own push queue. Reason: a phone that was revoked and signed in again would otherwise keep rows the server dropped. Decided, not built.
- **A push of a deleted id.** A push of a client id that is already deleted is answered 204 with no write and no change number. The one exception is the optional boolean `resave` on the push body. The phone sets it only on the first push of an id that the phone itself deleted and then saved again, and never on a retry. Reason: the real stale case is a lost 204 that is retried after another device deleted the row, and bringing the row back would break point 4, which says a row deleted anywhere does not come back from another device. The mark keeps the other rule, that a local save after a local delete makes the row live again. Built on server.

## Decided details of the phone pull (sync 1C)

The Decision above says what the phone's pull does. This section records the details decided afterwards,
all of them Decided. PullRun (#441), PullSink (#442), applyRemoteRow (#444), retainOnly (#445), the pull
cursor store (#446), isQueued/queuedIds (#447), PullApplier (#448) and PullCycle (#450) are built. The
wiring of the pull is decided, not built: no shipped code builds a PullCycle or a PullApplier or implements
the pull transport, so the app does not start a pull.

- **The port.** A new core port in core/port, one interface that history implements and sync calls,
  wired by the app; history stays the ONLY writer of the local store; sync never gets the whole history
  store. Three calls: apply a pulled live row, apply a remote delete (client id, deleted at), and later
  retain only the ids for the prune after a full list (sync passes the ids still waiting in its push
  queue). The names are decided by the manager: the interface PullSink; the calls applyRemoteRow,
  applyRemoteDelete (it exists in history and keeps its name) and retainOnly.

- **Where the cursor lives.** In the sync module's own SQLite file next to the push queue; one opaque
  string, never parsed or built; advanced only when a whole pull ended (more is false), per the sync card
  line "last complete pull"; an interrupted pull starts again from the stored cursor (one row per id, so
  repeats are harmless); cleared with the queue on sign-out or account change; dropped on a 400 or a 410.

- **When the pull runs.** After each drain of the push queue. The first pull sends no since; the phone
  never writes the "account-0" form (that is only what the server accepts). Later pulls send the stored
  next unchanged, page after page while more is true. A 410 or a 400 drops the stored cursor and lists
  again without since, ONCE per pull; a second refusal stops the pull and counts one failure, no loop.
  After a full list the prune runs.

- **The merge of a pulled live row with the same client id.** (a) a local tombstone of any reason: the
  row is NOT stored and the tombstone stays (ADR-035 point 4); the case of reviving a row from another
  device is left open and logged, nothing is lost because the server keeps the row. (b) the id is waiting
  unsent in the push queue: the local edit wins, the pulled row is skipped, the push goes out and the next
  feed carries the server's answer. (c) otherwise: store it, one row per id. Decided by the manager inside
  this architecture; the project owner has not seen it and may overrule it.

- **A pulled delete record.** Always goes to the remote delete call, even if this device saved the id
  again; the REMOTE tombstone is held across a local re-save. This is not the local rule: a local delete
  then a local save clears the LOCAL tombstone.

- **A pulled delete record for an id waiting unsent in the push queue.** The delete wins on the phone, as in the
  rule above: the row goes and the REMOTE tombstone is written. The pull never touches the push queue, so the
  queue entry stays. The server decides what that entry does: a push of an id that is already deleted is answered
  204 with no write and no change number, the queue counts 204 as confirmed and drops the entry, so the waiting
  edit does not bring the row back. The one exception is an entry that carries the `resave` mark (the phone
  deleted that id itself and then saved it again): its push does revive the row on the server, which is the local
  rule at work, and the next pull gives the row back to the phone. Decided by the manager inside this
  architecture; the project owner has not seen it and may overrule it.

- **Not decided here.** New-device keys (question 3) and the key_version 1 pulled row (question 4) stay
  later work.

## Decided details of the list cursor (sync 1D)

The manager decided on 2026-11-07 that the changes-since feed has TWO cursor
kinds. Both are server-assigned and opaque; the client stores one and sends it
back as `since`, never builds one. Decided, not built (server).
Decided by the manager inside the opaque-cursor rule; the project owner has not seen it and may overrule it.

- **The defect.** A first list (no `since`) returns the oldest 100 live rows
  and `next = the change number of the last live row emitted`. The next call
  sends `since = next`, and line 22 of TranscriptionPageReader.kt answers 410
  when `since < purged_through`. World, recounted from repro.py: push 101 rows
  (changes 1 to 101), push a 102nd and delete it (the delete record is change
  103); a first device pulls to the head and its `since=103` call purges it, so
  `purged_through = 103`. A NEW device lists from scratch: page 1 = 100 rows,
  `next = 100`, `more = true`; its next call `since=100` is below 103 = 410;
  "list again without since" returns the same page and the same `next`, and the
  410 repeats for ever. A second phone or a reinstall can never finish its
  first list. Server only: the phone treats the cursor as opaque and is
  unaffected. Account id in repro: 1.

- **Two cursor kinds.** (1) Incremental `<account_id>-<change>` - unchanged,
  pattern `^[0-9]{1,19}-[0-9]{1,19}$`. (2) LIST cursor, issued only as `next`
  of a NON-final page of a no-since list: `<account_id>-<position>-<head>`
  where `position` is the change of the last live row emitted on that page and
  `head` is the head read on the FIRST page of the list, pattern
  `^[0-9]{1,19}-[0-9]{1,19}-[0-9]{1,19}$`. The widened pattern that accepts
  both is `^[0-9]{1,19}-[0-9]{1,19}(-[0-9]{1,19})?$`; any other text is a 400
  before any number is read. The account part keeps its no-leading-zero rule: a
  leading zero other than a single 0 is refused.

- **What the server does on a list cursor.** It skips the 410 check (a list
  reads live rows only, no delete record is needed, so `purged_through` is
  irrelevant). It reads live rows with `position < change_no <= head` (the head
  carried in the cursor), ordered by `change_no`, one more than a page
  (`LIMIT 101`): a list is a SNAPSHOT at the first-page head. Rows written after
  that head (new rows, re-saves, deletes) are not emitted by the list; they reach
  the phone through the incremental pull `since=head` that the final `next`
  returns. This keeps every earlier reason (no 410 on a list, a delete between
  pages is reported, the purge is held at the first-page head) and the position
  never exceeds its head. It stores as the session's pulled number
  `min(head, head_now)`, never above the first-page head, so the purge cannot
  pass records the phone has not yet seen.

- **What `next` is.** While `more`, `next = <account_id>-<position>-<head>` (the
  LIST cursor, same first-page head). On the FINAL page, `next =
  <account_id>-<head>` (the incremental cursor at the first-page head, NOT the
  head now) - so deletes and changes after that head reach the phone by its next
  normal pull. Single-page lists (100 live rows or fewer) are unchanged: `next
  = head`.

- **Why not the other two options.** Clamping `next` up to `purged_through`
  skips live rows. Putting the head only at the end loses a delete made between
  two pages: the final next would jump past its delete record.

- **The phone is unchanged.** The prune after a full list uses the union of ALL
  pages of that list and only runs when the list finished (noted for the prune
  piece).

- **When a list cursor is valid (Q1, Q6).** Only when `1 <= position < head`: the
  server never issues position 0 or a position equal to the head (a non-final page
  means a live row exists above the position and at or below the head). Anything
  else is a 400, answered before any number is read. The refusal is in the openapi
  400 text and a contract test pins that sentence.

- **A reset while a list is in flight (Q2).** Nothing special. A reset revokes every
  session, so a list in flight is refused by authentication before its cursor is
  read. A stale list cursor used with a NEW session just continues on the new feed,
  and its final incremental cursor at the old head meets the existing 410 and
  list-again-once if `purged_through` has passed it.

- **A session revoked or expired between two pages (Q3).** Authentication runs first,
  as on any route: 401 or 403. The list cursor carries no session state.

- **A list cursor of another account (Q4).** 400 before any number is read, as for
  an incremental cursor of another account.

- **Two lists of one session in flight (Q5).** Independent: the cursor is
  self-contained (account, position, head). The session's stored pulled number is
  the smaller of the two lists' values; it may move DOWN, which is safe, because it
  only holds the purge longer.

- **No question is left open.** Q1 to Q6 are decided above.

What is built and what is not is listed in Built so far, and what is not, above.

Rules in: the server as the source of truth for an account's transcriptions; devices push, then pull; deletes propagate through the server; the sealed key hand-off to a new device as ADR-036 describes.

Rules out: a device as the source of truth; peer-to-peer device sync; the server ever holding a key that opens transcriptions.
