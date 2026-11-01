# ADR-035: Several devices per account; the server is the source of truth

**Status:** accepted; the points under To be designed are open
**Date:** 2026-11-01
**Confirmed by:** the project owner, 2026-11-01, in his own words below

## Context

The project owner decided, 2026-11-01, that one account may use several devices (a phone and a tablet, for example), and how they stay in step:

> "For multi devise support this is what I'm thinking the container will be the source of truth so when I transcribe something on my phone it sends it to the server and then maybe I have a tablet and then the tablet updates that should be pretty easy to set up but will require a code change and that module the agents ah m d needs to be rewritten along with decisions adr."

He then approved three points: encryption stays as ADR-006 decides it; a delete on one device deletes everywhere; a new device does not need its own password prompt:

> "Yes to all 3 for multi device support. If you are logged in though you should not have to reinput your password."

Much of this is already decided. The sync-api card and the API contract list `GET /v1/transcriptions` to pull the account's transcriptions and `POST /v1/sync` to push; neither route is built in sync-api yet. The history module keeps tombstones and has `applyRemoteDelete` for a delete made elsewhere (history card). What is not decided:

- which module on the phone pulls, when, and how pulled rows merge into local history;
- how a new device gets the account's keys when the password is typed on the website (the sign-in hand-off, ADR-036) rather than on the device. Under ADR-006 the device derives the KEK from the password itself, so a device that never sees the password cannot unwrap the DEK.

On 2026-11-01 the project owner confirmed the design and ordered this update: "Breaker confirmed.  Update the repo when ready."

## Decision

1. **The server is the source of truth for an account's transcriptions.** Every device pushes what it records (`POST /v1/sync` in the API contract; the phone's push queue exists, the server route is not built yet) and pulls what other devices recorded. A device's local history is a copy of the server's, plus whatever is still queued to push.
2. **Offline stays first.** A device with no server keeps recording and reading its local copy. It pushes its queue and then pulls when the server is back. Nothing recorded offline is lost.
3. **The phone's sync module pulls.** `android_sync` gains a pull: after each drain of its push queue it asks the server for the rows changed since its last pull, and hands them to history. Rows are matched by their client id, so a row this device pushed is not stored twice. History stays the only module that writes the local store.
4. **Deletes go everywhere.** A delete on any device leaves a tombstone that is pushed. The server deletes the row and keeps a delete record until every device that held the row has pulled it. A device that pulls a delete applies it through `applyRemoteDelete`. A row deleted anywhere does not come back from another device.
5. **Encryption is unchanged (ADR-006).** Each device encrypts before it pushes. The server stores and returns sealed rows only and never holds the DEK, the KEK or the password.
6. **A new device gets its keys from the website sign-in, sealed for that device.** When the app starts the sign-in hand-off, it makes a fresh key pair and sends the public half with its sign-in challenge. The person types the password once, on the website. The website page derives the KEK in the browser (ADR-006's web path), unwraps the account's keys and seals them to the app's public key. The server passes that sealed package to the app with the session token at the code exchange. The app opens it with its private key and wraps the keys for its own storage. The server only ever carries a package it cannot open. The hand-off itself is decided in ADR-036. The device's own password sign-in stays as the fallback (ADR-036), and it still derives the KEK on the device.

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
- **Nothing of this is built.** Today the app has no pull, sync-api has no sync routes, and the sign-in is unchanged.
- **To be designed** (design note, before any of it is built): the changes-since cursor and its wire form; how long delete records are kept for a device that never comes back; the sealed package's format and key type; the merge when a pulled row and a local unsent edit share a client id; how the device list (ADR-036) shows each device's last pull.

Rules in: the server as the source of truth for an account's transcriptions; devices push, then pull; deletes propagate through the server; the sealed key hand-off to a new device as ADR-036 describes.

Rules out: a device as the source of truth; peer-to-peer device sync; the server ever holding a key that opens transcriptions.
