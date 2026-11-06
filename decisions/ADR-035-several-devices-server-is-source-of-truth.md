# ADR-035: Several devices per account; the server is the source of truth

**Status:** accepted; the sync routes and the change feed are built on the server; the clear-store write and
the phone side are decided, not built; the purge is built on the server; the points still open are under To be
designed
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
   key pair, made in memory for one sign-in by `SignInStarterImpl.begin` (`crypto/SignInStarterImpl.kt`, lines
   40-60) and held by `SignInHolder.begin` (`core/usecase/SignInHolder.kt`, line 41, wired at
   `BreakerCompositionRoot.kt`, line 126); the return flow (`HandoffReturnFlow.handle`,
   `wiring/HandoffReturnFlow.kt`, line 11, called from `host/HandoffReturnActivity.kt`, line 21); and
   `CryptoService.openSignInPackage` (`crypto/CryptoServiceImpl.kt`, lines 86-98). Built on the server: the
   start and exchange routes (`auth/AuthHandoffRoutes.kt`, lines 31 and 96), the code store and the sweep.
   Decided, not built: the app calls to the start and exchange routes (no caller; `DefaultHandoffReceiver`
   only wipes the attempt and returns NOT_FINISHED, `core/port/HandoffReceiver.kt`, lines 26-31), the website
   page (its crypto is a stub, `web-fe` `js/signin-crypto.js`), the Keystore wrapping and the wiring of the
   received package. When the app starts the sign-in hand-off, it makes a fresh X25519 key pair in memory for
   that one sign-in and sends the public half with its sign-in challenge. The pair and the app's one-time
   secret are dropped at the code exchange, or when the sign-in is abandoned. The person types the password
   once, on the website. The website page derives the KEK in the browser (ADR-006's web path), unwraps the
   account's keys and seals them to the app's public key as an anonymous sealed box, the construction ADR-018
   already fixes. The server keeps that sealed package until the exchange deletes it, and never past the
   code's 5-minute expiry. The server passes the sealed package to the app with the session token at the code
   exchange. The app opens it with its private key. It then wraps the account's DEK and the session token at
   rest with an Android Keystore AES key, which is decided and not built. The server only ever carries a
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
  are built. Not built: the website page, the app side of the hand-off (calling the routes, the wiring of
  the received package), and the device list with sign-out. The wrong-password limit is built on the server
  (ADR-036). Of the sync work on the server, the push route `POST /v1/sync`, the plain pull route `GET
  /v1/transcriptions` and the transcriptions table are built, and so is the store layer of the changes-since
  feed: migration 5 (the change counter on the account row, the change number on every transcription row, the
  delete flag, the purged-floor column, the pulled-change column on the session row and the unique index), a
  change number on every insert and replace, and a push of a deleted row that is a no-op. The contract text of
  the feed and the Kotlin types for it are built too. The DELETE route `DELETE
  /v1/transcriptions/{client_id}`, the store delete, the `resave` mark on a push, and the plain list that
  leaves deleted rows out are built too. The `since` parameter, the page object, the 400 answer and the 410
  answer of `GET /v1/transcriptions` are built. The write of the pulled number and the purge of delete records
  are built on the server. Not built: the clear-store write and the whole phone side. The device name column
  on the session row is built (migration 4); the app does not send a name yet and the device list is not
  built. Today the app has no pull, and the sign-in is unchanged.
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
  **Decided, not built (phone, in flight in android/modules/history, not landed):** A local delete followed
  by a local save of the same id clears the local tombstone, so the row is live again.
  **Device name:** the session row has a nullable device name, and that column is built (migration 4). The
  app sending a name at sign-in, the person renaming it and the device list are decided, not built. There is
  no devices table: the device list has one row per session, with a last-seen date derived from the session's
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
- **Who makes delete records.** Only a clear-store write makes delete records. A reset of the account drops the rows outright and revokes every session, so no cursor survives it and no delete record is needed. After a full list (no `since`), the phone removes the local rows that are not in the answer and are not waiting in its own push queue. Reason: a phone that was revoked and signed in again would otherwise keep rows the server dropped.
- **A push of a deleted id.** A push of a client id that is already deleted is answered 204 with no write and no change number. The one exception is the optional boolean `resave` on the push body. The phone sets it only on the first push of an id that the phone itself deleted and then saved again, and never on a retry. Reason: the real stale case is a lost 204 that is retried after another device deleted the row, and bringing the row back would break point 4, which says a row deleted anywhere does not come back from another device. The mark keeps the other rule, that a local save after a local delete makes the row live again. Built on server.

What is built and what is not is listed in Built so far, and what is not, above.

Rules in: the server as the source of truth for an account's transcriptions; devices push, then pull; deletes propagate through the server; the sealed key hand-off to a new device as ADR-036 describes.

Rules out: a device as the source of truth; peer-to-peer device sync; the server ever holding a key that opens transcriptions.
