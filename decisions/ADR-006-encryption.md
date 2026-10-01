# ADR-006: Encryption by default — per-user DEK, password-wrapped

**Status:** accepted, amended 2026-09-30 (client-side key derivation, the
guarantee it actually gives, login, password change and account reset)
**Date:** 2026-09-29

See ADR-018 for agent-token and queued-job results, which cannot go through
this password-gated path at all (an agent token carries no password).

## Context
Multi-user + web FE means transcriptions live on the server. The owner wants
nobody — not even admins or agents with server access — able to read another
user's notes in plaintext.

The first version of this ADR had the *server* generate the DEK and derive
the KEK from the password. That framing was backwards: whatever computes the KEK from a password has to see the password to do it,
so a server that derives the KEK is a server the password reaches. This
amendment moves derivation to the client and states the resulting guarantee
in the honest, weaker form it actually has.

## Decision
**Client-side derivation, split by domain.** At registration the **client**,
from the user's password and a random 16-byte salt, runs **one** Argon2id pass (parameters below), then HKDF-splits that
single output into two independent subkeys using distinct labels:
- `breaker-kek-v1` → the **KEK**, which wraps the DEK and never leaves the
  client.
- `breaker-auth-verifier-v1` → the **auth verifier**, a fixed-length
  pseudorandom value with no path back to the KEK, sent to the server instead
  of the password.

Domain separation is what makes this safe to split: the two labels come out
of the same Argon2id output, but there is no function that recovers the KEK
from the verifier or vice versa. The client uploads {salt, KDF params, KDF
version, auth verifier} at registration — never the password, never the DEK,
never the KEK. The server never generates a DEK, never derives a KEK, and
never receives the password.

**Argon2id parameters (feasible in a browser):** memory 64 MiB, 3
iterations, parallelism 1, 32-byte output, 16-byte random salt. Stored **per
account** together with a KDF version number, so a later account can move to
higher costs at its next password change without breaking accounts still on
v1. **Floor, enforced on both sides:** the server refuses to register an
account, accept a password change, or complete a reset, at `kdf_params` below
64 MiB / 3 iterations / parallelism 1 — a weaker set is simply rejected, not
stored. The
client independently refuses to derive against parameters below that same
floor when a login response hands them back, whatever `kdf_version` they
claim — a client does not trust a server to talk it down to a cheaper KDF.
**Password rule:** the client refuses a password under 12 characters and
warns further on common/weak ones; this is a client-side nudge, not a
guarantee (see Reasons).

**Login is two steps, with no decoy:**
1. The client asks for the account's `{salt, kdf_params, kdf_version}`
   (`POST /v1/auth/salt`). An unknown username gets an honest "no such
   account" response — the same thing registration already tells someone
   choosing a name. This repo's scope decision (docs/01 "Out of scope", docs/05
   "Deferred / out of scope") puts auth hardening out of scope for v1; neither
   list names account enumeration by that term, but this repo reads
   "auth hardening" as covering it too — the VPN/ZeroTier perimeter is the
   auth layer for now, so account existence is not worth hiding behind it. A
   decoy salt would only pretend otherwise, so none is used.
2. The client re-derives the same Argon2id output locally and HKDF-splits it
   again, sends only the auth verifier (`POST /v1/auth/login`), and the
   server checks it against a server-side hash of the verifier (any standard
   password hash — Argon2id again is fine; this is a second, server-chosen
   salt/hash layered on top of the verifier, not a substitute for the
   client-side KDF). On success the server returns a token plus the account's
   wrapped keys: `{wrapped_dek, wrapped_box_privkey, box_pubkey}` (ADR-018) —
   `null` for an account that has none yet. The client unwraps the DEK
   locally with the KEK and encrypts/decrypts transcriptions with
   AES-256-GCM. Search is client-side as a consequence.

**First-login key bootstrap (phase order):** the Argon2id/HKDF split
above is what ships with auth in **Phase 11**, so the password never leaves
the device from the very first login this app supports. Phase 11's register
and login bodies carry exactly the fields listed above — nothing that doesn't
exist yet. **DEK generation, AES-256-GCM, and the ADR-018 box keypair ship in
Phase 19.** Whenever a client logs into an account that has no wrapped keys —
whether that account predates Phase 19 or was just created — it generates the
DEK, wraps it with the KEK it just derived, generates the box keypair, wraps
the box private key with the (now-unwrapped) DEK — not the KEK — and uploads
all of it once (`POST /v1/auth/keys`, accepted only while the account has no
keys yet). One mechanism covers both "an old account's first login after
Phase 19 ships" and "a brand-new account's very first login" — there is no
separate key-bootstrap path to get wrong.

That covers the account's *keys*. Its *data* needs a separate, real
migration: sync ships in Phase 11, four phases before the DEK exists in
Phase 19, so every transcription a Phase-11–18 account pushed sat on the
server as **plaintext** the whole time — "the server stores no plaintext"
(below) was not true for those accounts until this bootstrap runs. That
migration, and every later re-key (below), both need a record replaced, not
merely re-sent, which is why every synced transcription now carries a
**`key_version`**: `0` means plaintext (a row pushed in Phases 11–18, before
the account had a DEK at all); otherwise it is the account's DEK generation —
set or checked only by the server: accepting a first-login bootstrap
(`POST /v1/auth/keys`) sets it to the account's previous value + 1 (`0` → `1`
for an account's first bootstrap), and every accepted re-key advances it by
exactly one. It never goes backwards: a reset (ADR-018) deletes the account's
keys, records and pending-sealed rows but keeps this counter, so the bootstrap
after a reset sets previous + 1, and a stale pre-reset device's pushes at an
old version are refused as below. The server stores the account's current
`key_version` alongside its wrapped keys and returns it at login. `POST /v1/sync` dedupes on the **pair**
(`client_id`, `key_version`), not on `client_id` alone: the same pair is a
no-op (retries stay idempotent, N12); the same `client_id` at a **higher**
`key_version` is an atomic replace — the server swaps the stored copy for the
new one and deletes the old copy in the same transaction, only once the
replacement has actually landed; the same `client_id` at a **lower**
`key_version` is refused (409), so a stale client can't roll a record back;
and a **new** `client_id` below the account's current `key_version` is also
refused. `GET /v1/transcriptions` returns each record's `key_version`, so a
client can tell which of its own rows still need replacing — an interrupted
migration or re-key simply picks up where it left off at the next login,
until none remain. The same first-login bootstrap that uploads the new keys
pulls that account's own existing rows this way: it encrypts each one under
the freshly generated DEK and replaces it at `key_version` 1 through this
same atomic-replace path (not a second, unrelated push of the same id — see
`server/modules/sync-api/AGENTS.md`). A Phase-19-or-later client that skips
this step would otherwise pull rows it has no way to AES-GCM-decrypt.

**Password change re-keys, every time (the user knows the old password):**
there is no lighter-weight "just re-wrap the same DEK" path any more, and no
special case for "the first change after a reset" — every password change,
including that one, runs the same flow:
0. if an earlier re-key is still unfinished (login returned `prev_wrapped_dek`),
   the client finishes it first (step 3 below). `change-password` is refused
   (409) while `prev_wrapped_dek` is held, because a second change would
   overwrite the only copy of the older DEK and strand the records still
   under it. Then the client converts every pending sealed record it can see under the
   **current** keys (`GET /v1/sync/pending-sealed`, ADR-018), so nothing is
   left sealed to a box public key that is about to be replaced;
1. it generates a **new** DEK and a **new** X25519 box keypair, wraps the new
   DEK with the newly derived KEK, and wraps the new box private key with the
   new DEK (the same pairing the first-login bootstrap uses);
2. it calls `change-password`, authenticated as now by the **old**
   `auth_verifier` in the body (a live session token is never enough by
   itself — see below) — the body now always carries the new salt/KDF
   params/version, the new auth verifier, the new wrapped DEK, the new
   wrapped box private key, the new box public key, `new_key_version =`
   the account's current `key_version` **+ 1**, and **`prev_wrapped_dek`**:
   the OLD DEK wrapped with the NEW DEK, so the re-key in step 3 can still
   read the records it has not reached yet after the old keys are gone. The
   server swaps the stored
   keys, advances the account's current `key_version`, records
   **`rekeyed_at`**, revokes every other outstanding **user session token**
   (agent tokens are a separate credential, ADR-009, and are not touched —
   revoking one is a distinct admin action), and deletes any pending-sealed
   rows still sealed to the box public key this call just replaced;
3. the client re-encrypts every one of the account's own records still below
   the new `key_version` and replaces each one through the same atomic-replace
   path above, and re-encrypts its own not-yet-synced local queue under the
   new DEK before pushing it (a queued item still under the old DEK would be
   refused as a new record below the current version). This resumes at the next login if it is interrupted: login
   returns `prev_wrapped_dek` for as long as any record sits below the current
   `key_version`, the client unwraps it with the new DEK and finishes the job,
   and the server deletes `prev_wrapped_dek` once no such record is left, whether
   the last one was replaced or deleted (a user's delete, F33, or the
   retention purge, F28). Only
   the holder of the new password can unwrap it, so it gives no one else the
   old DEK.

The server keeps `reset_at` (below) and adds `rekeyed_at`; the app and the web
FE show "this account was reset on \<date\>" when `reset_at` is new to that device, and
"keys last changed on \<date\>" when `rekeyed_at` is new — each clause on its own, so a routine
password change never re-announces an old reset.
**The honest guarantee, stated plainly:** after a password change, whoever
knew the previous password or held the previous DEK can no longer read
anything stored after the change, or any record the re-key has already
replaced — copies they already took stay readable to them, and a record not
yet replaced stays under the old DEK until the re-key reaches it (normally at
once; at the next login if interrupted). **The cost, stated plainly:** every
password change re-encrypts all of the account's stored transcriptions; they
are text, and the 3-month retention bounds how many of them there ever are.

**Admin / agent-scoped reset (the user does not know the old
password):** see ADR-018's Decision for the full flow and its honesty
requirements; in short, the server can only wipe the account's keys and data
and issue a one-time code — whoever completes the reset with that code
chooses the new password and generates a fresh DEK and box keypair on their
own device, the same first-login bootstrap a brand-new account runs. The
admin does not set the password in the normal flow, but nothing about the
code stops the admin from being the one who completes the reset instead of
the user; ADR-018 states that risk honestly rather than claiming it away.
Because password change now re-keys unconditionally (above), the mitigation
needs no reset-specific case to detect: the account's very next password
change — by the legitimate user regaining control, or by anyone else — rotates
the DEK and box keypair the same way every other password change does.

## Reasons
- **The honest guarantee, stated once, in full — Android:** the server
  stores no plaintext and never receives the password or the KEK. But
  whoever obtains the stored verifier hash or a wrapped DEK — a database or
  backup breach, a log line — can test password guesses **offline**, at one
  Argon2id run per guess, exactly as fast as the account's stored KDF
  parameters allow. So the real protection is **the password's strength
  times the Argon2id cost**, nothing stronger. This replaces every earlier
  claim that the server "cannot decrypt under any account", that a fact
  reaching the server is "literally" insufficient to derive the KEK, or that
  nothing in a request body or log can ever yield a key — all of those were
  false the moment a real password is guessable, and this repo does not ship
  rate limiting or account-enumeration defenses to make guessing costly
  (auth hardening is explicitly out of scope; docs/01, docs/05). Domain
  separation (the two HKDF labels) is still real and still worth having: it
  is what stops the *auth verifier itself*, sent on every login, from being
  usable to derive the KEK directly — but it does not defend against an
  attacker who can just try the password against Argon2id and re-derive both
  values, because that is exactly what a legitimate login does too. This
  guarantee holds for the Android app because its key-handling code ships
  inside a **signed APK the user already installed** — the server has no way
  to change what that code does on a given device without the user fetching
  a new signed build.
- **The same guarantee does NOT hold for the web FE, and this ADR is not
  honest if it implies otherwise.** A web-FE user's browser downloads its
  Argon2id/HKDF/AES-GCM JavaScript from the same server every time it loads
  the login page (`server/modules/web-fe`) — there is no signed artifact
  pinning that code the way there is on Android. Whoever controls the
  server, or anything with write access to the web-fe container's files, can
  serve JavaScript that reads the password (or the KEK, or the unwrapped DEK)
  out of the page and sends it home, at the user's very next browser login.
  That is not offline guessing against a stored hash — it is direct capture,
  with no Argon2id cost paid and no guessing involved, because the served
  code IS the thing computing the KEK from the password. The three
  properties above (ciphertext-only storage, no usable key reaching the
  server over the wire, offline-guessing-only for a passive database reader)
  are real and worth having, but for a web-FE login they hold only as long as
  the server is not, itself, the attacker. This is the same trust boundary
  ADR-018 draws for the sealed box ("not a defense against whoever controls
  the running server") — ADR-006 draws it here for the password and the KEK.
- Stronger than "server sends the key" regardless, and unconditionally true
  for a **passive** reader of the server (an admin browsing rows, an agent
  with read-only server access, a snapshot of the database with no cracking
  attempt and no ability to change what the web FE serves): ciphertext only,
  no password guessing and no served-code capture involved.
- One Argon2id run derives both subkeys; there is no separate "derive the KEK
  alone" function, so nothing that computes the auth verifier without the
  password can also produce the KEK.
- **The raw auth verifier is itself a login credential, not just
  guessing-bait.** Anyone who obtains it in the clear — not hashed, the value
  actually sent on the wire — can log in as the user outright: no guessing,
  no Argon2id cost, immediate access to a session token and the account's
  wrapped keys and ciphertext. Holding it also lets them call
  `change-password` (the current design authenticates that endpoint with the
  live verifier), so they can install a verifier and a DEK wrapping of their
  own choosing and lock the real user out. None of this decrypts a single
  transcription without also breaking Argon2id — the verifier's domain
  separation from the KEK holds — but "can only guess offline" is not the
  right honesty statement for a **raw, unhashed** verifier exposure; it is
  the right statement only for the **stored hash of it**.

## Consequences
Rules in: client-side crypto on Android + web FE (`android/modules/crypto`),
shared Argon2id/HKDF parameters + test vectors across both, server- and
client-side enforcement of the 64 MiB / 3 / 1 KDF floor on register, change-
password AND complete-reset, `POST /v1/auth/salt` (no decoy), `POST
/v1/auth/keys` (first-login bootstrap, plus the plaintext → ciphertext data
migration for any account that synced transcriptions in Phases 11–18, both
landing through `POST /v1/sync`'s `key_version`-aware replace), `POST
/v1/auth/change-password` (re-proven by `old_auth_verifier` in the body,
re-keys — replaces the DEK and box keypair and advances `key_version` — on
every call, revokes other user session tokens), the `key_version` field on
every synced record and the atomic-replace path it drives, type-to-confirm on
destructive actions. `core.CryptoService` gains the
Argon2id/HKDF derivation operations when Phase 11 builds it, and the DEK
generation/wrap + box-keypair operations when Phase 19 builds them — the
port is not changed by this documentation change set itself (see
`android/modules/crypto/AGENTS.md`).

Rules out: server-side search; a decoy salt response; citing queue rate
limits (T18) as a defense for auth endpoints, which are unauthenticated and
which T18 was never about; any claim that this scheme resists an attacker who
already has the stored verifier hash or wrapped DEK and can spend compute
guessing passwords against it; any claim that the served-JS trust model for
the web FE matches the signed-APK trust model for Android; a server or
client accepting KDF parameters below the stated floor; a live session token
alone authorizing a password change.
