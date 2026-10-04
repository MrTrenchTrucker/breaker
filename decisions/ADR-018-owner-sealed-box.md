# ADR-018: Owner sealed box for agent-token job results; account reset

**Status:** accepted
**Date:** 2026-09-30

## Context
F27 requires agent-token job results to reach the owner's account and appear
in the web FE like any other transcription. ADR-006 (as amended) requires the
server to hold no usable key and store ciphertext only. An agent authenticates
with a bearer token (ADR-009), never the owner's password, so it cannot derive
the owner's KEK/DEK and cannot itself produce a ciphertext ADR-006's scheme
accepts.

Two constraints shape the design below:
- Sealing **every** job's result, including the requester's own, breaks the
  job contract ADR-015 already settled: the caller that submitted a job polls
  `GET /v1/jobs/{job_id}` and gets its result back once. An agent that gets
  back a blob it cannot open is functionally unable to transcribe at all —
  a scope cut on F27, not a security improvement, since the requester already
  holds the audio it submitted.
- A design that assumes admin- or database-level attackers can be locked out
  entirely overstates what a **sealed** box can do once someone with write
  access to the database is in the threat model. This ADR says plainly what
  the sealed box protects and what it does not.

## Decision
**The job contract is unchanged.** The requester — owner device or agent
token alike — always gets its own plaintext result back through the existing
`GET /v1/jobs/{job_id}` (ADR-015). Nothing about how an agent transcribes
changes.

**New retention rule for the job result itself, stated plainly because
nothing before this ADR said it:** the job's `result` row is deleted from
`whisper-server`'s SQLite job store as soon as its requester has fetched it
once, and if nobody ever fetches it, it is deleted **24 hours** after the job
finished regardless. Until one of those two things happens, the result sits
in that store in **plaintext** — the same store, and the same exposure, the
queued audio already has before its own job finishes (ADR-010). An
agent-token caller that never polls `GET /v1/jobs/{job_id}` therefore leaves
its result as plaintext-at-rest for up to 24 hours, not indefinitely; this is
the floor this ADR is willing to accept, not a claim that no plaintext ever
touches disk (see the full at-rest accounting in Consequences).

**Separately, only for an agent-token request:** once the FIFO worker
(`server/modules/whisper-server`) completes the job, it looks up the owning
account's public key (the token already carries an owner account, per
ADR-009 — the same lookup whisper-server needs to land results in the right
account) and seals a copy of the plaintext result to that key with **libsodium
`crypto_box_seal` only** — an anonymous sealed box: anyone holding the public
key can seal to it, only the private-key holder can open it. No Tink: Tink has
no `crypto_box_seal`-compatible construction, and this ADR needs one
interoperable wire format across a Kotlin/JVM sealer (whisper-server, via a
libsodium JVM binding) and Android + browser unsealers (libsodium.js in the
browser, a libsodium binding on Android) — cross-platform test vectors are
pinned once, for this one construction.

**If the owning account has no box public key yet** — it has not completed
the Phase 19 (or later) first-login bootstrap, whether because it predates
Phase 19 and hasn't logged in since, or because it is mid-reset (below) —
there is no key to seal to. No sealed copy is created and nothing is queued
for that owner; the requester still gets its own plaintext result exactly as
above, because the job contract never depended on sealing succeeding. The
owner simply has no agent-authored transcriptions waiting the next time they
do bootstrap; nothing is silently dropped from a store that never received
it.

The sealed copy is forwarded to `server/modules/sync-api` and stored in the
owner's **pending-sealed** store — not mixed into the ordinary transcription
table, so it never impersonates an authenticated transcription before the
owner's own client has actually processed it. **That "never impersonates"
claim is about ordering, not about content — see the honest limit in
Consequences: anyone who can write this store can plant a row the owner's
client will convert without question.** The owner's client — whichever device of
theirs logs in first — lists its own pending sealed records
(`GET /v1/sync/pending-sealed`), unseals each with the box private key (itself
DEK-wrapped and unwrapped as part of ordinary login, ADR-006), re-encrypts the
plaintext under the account's normal per-user AES-256-GCM DEK, and uploads it
through the existing `POST /v1/sync` with the transcription's **client id set
to the sealed record's id**, at the account's **current** `key_version`
(ADR-006) — a genuinely new record, not a replace. Sync already dedupes on
the (client id, key_version) pair (N12), so this upload is idempotent by
construction: if two devices race to convert the same pending record, the
second upload is a no-op, not a duplicate. The server
deletes the pending-sealed row once a synced transcription with that client id
has landed. The owner's own device requests need none of this — the phone
reads its own result and syncs it exactly as it always has.

**Every account holds an X25519 keypair alongside its DEK,** generated
client-side at the same first-login bootstrap ADR-006 defines for the DEK
(Phase 19): the private half is wrapped by the account's DEK (not the KEK
directly) and only the wrapped form is ever sent to or stored by the server;
the public half is plain data, stored server-side unwrapped, because sealing
requires no secret on the sealer's side.

**Admin / agent-scoped reset:** an admin, or an
admin-scoped agent token (F31), can trigger a reset on an account
(`POST /v1/admin/reset-account`). The server:
1. deletes that account's wrapped DEK, wrapped box private key, box public
   key, and verifier hash (all four go together — an orphaned public key
   with no recoverable private half is worse than no key at all);
2. deletes the account's transcriptions, because they can no longer be
   decrypted by anyone (R20) — keeping undecryptable ciphertext around serves
   no one — and deletes any still-unfetched pending-sealed rows for that
   account, which are sealed to a box public key that just got deleted and so
   can never be opened by whatever keypair comes next;
3. revokes every outstanding token for that account;
4. issues a one-time reset code — **at least 128 random bits, single use, and
   expiring 24 hours after issue** — which the admin passes to the user out of
   band (chat, in person — outside this system). There is no attempt-limit
   claim: this repo ships no brute-force protection on any auth endpoint
   (auth hardening is out of scope, docs/01/05 — see T25), so the code's
   strength has to stand on its own entropy and its short lifetime, not on a
   lockout;
5. records that the account **was reset**, and when — this is the one piece
   of server-side state this whole flow depends on being honest about (see
   below).

The user completes the reset **on their own device** (`POST
/v1/auth/complete-reset`, authenticated by the one-time code instead of a
password): they choose a new password there, and the client generates a new
salt, KDF params, verifier, and — since the reset left the account keyless —
runs through the exact same first-login bootstrap as a brand-new account to
generate a fresh DEK and box keypair.

**Be honest about what this still allows, rather than asserting the opposite
as an invariant.** The one-time code is a bearer secret: whoever presents it
first to `complete-reset` is the one who sets the new password and generates
the fresh keys, and the server cannot tell "the user, promptly" from "the
admin, before handing the code over" apart — they are the same API call.
**The admin does not set the user's password in the normal flow** (the normal
flow has the user run `complete-reset` themselves), but nothing enforces that
flow, and no line in this repo should claim the admin "never chooses, sees,
or learns" it as a guaranteed fact. If the admin completes the reset instead
of the user, the admin now holds a fresh KEK/DEK/keypair the user never
chose, and keeps the ability to read anything synced under it until a
password change is made by someone who does not pass the new password back
to the admin — in practice, the user's own change. Every password change
re-keys (ADR-006), but a change made by the admin leaves the admin holding
the new keys, so that change does not end the admin's access. What does is the
user's own change, and the "keys last changed on \<date\>" line is how a
user sees a change they did not make. The access is real until then, not
eliminated the instant the reset completes.

**The mitigation is a rule on the very next password change, not a claim
that the takeover can't happen — and it needs no reset-specific case,
because ADR-006 makes EVERY password change re-key.** There is no lighter
"just re-wrap the existing DEK" path left for this ADR to carve an exception
out of: the account's very next password change after a completed reset —
whether that change is made by the legitimate user regaining control, or by
anyone else — runs the same flow ADR-006 defines for every password change.
The client generates a brand-new DEK and a brand-new X25519 box keypair,
re-encrypts and replaces every transcription the account has synced since the
reset completed (by construction there are few: step 2 above deleted
everything that existed before it) under the new DEK via the `key_version`
atomic-replace path (ADR-006), wraps the new DEK with the new KEK and the new
box private key with the new DEK — not the KEK, the same pairing as the
first-login bootstrap — and uploads all of it, advancing the account's
`key_version` and recording `rekeyed_at`. **The cost, stated plainly:**
re-encrypting whatever was stored since the reset; the account's data
footprint right after a reset is small precisely because the reset just
wiped it, which is what keeps that cost small. Whoever completed the reset
loses access from this point on only when this change is made by someone who
does not hand the new password back to them, in practice the user's own
change. If the admin makes the change, the admin generates the new DEK and
keeps access, and the "keys last changed on \<date\>" line shows the user a
change they did not make. Either way, what was written before the user's own
change is the honest, bounded exposure window this design leaves open, not a
claim of zero exposure. Step 0 of that same flow (ADR-006) also converts any pending sealed
record still sitting under the keys this change is about to replace, and the
server deletes any pending-sealed row still sealed to the box public key it
just replaced — so a reset-era sealed result cannot outlive the keys it was
sealed under.

**"The old password stops working" is not a warning sign a user can rely on
— it happens on every reset, including every legitimate one, at step 1
above, the instant the reset is triggered.** Telling users to treat it as a
signal would be false advertising: it fires identically whether the admin is
helping them or has just taken their account. The actual, honest signal is
the record kept at step 5: the Android app and the web FE both show **"this
account was reset on \<date\>"** when `reset_at` is new to that device, and
**"keys last changed on \<date\>"** when `rekeyed_at` is new (ADR-006), each
clause on its own,
so the user learns a reset happened from a source the admin cannot silently
suppress the way "your password still works" can be — the admin can decline
to hand over the code, but cannot make a completed reset not have happened.
**Admins are trusted with account control by this design (they can always
force a reset), not with data indefinitely** — a reset itself recovers no
plaintext for anyone, admin included, and the user's own next password change
is what bounds how long a completing admin's access lasts (a change the admin
makes does not, see above).

## Reasons
- Keeps ADR-006's guarantee for agent-authored content without breaking F27
  or the ADR-015 job contract.
- One mechanism (seal → pending store → unseal-on-next-login) answers "how
  does an agent's result become owner-only-readable" without inventing a
  second key hierarchy; it reuses the DEK-wrap pattern ADR-006 already
  defines for the box private key.
- `crypto_box_seal` is an audited, open-source, already-adjacent primitive —
  no home-grown crypto, no SRP/OPAQUE rewrite of the existing password auth.
- Pinning the construction to libsodium specifically (not "libsodium or an
  equivalent") is what makes the promised cross-platform test vectors
  possible at all; a choice of construction per platform is a promise no
  single test vector file can keep.

## Consequences
Rules in:
- New `sync-api`-owned server columns: `box_pubkey` (plain), alongside the
  existing wrapped-DEK columns and a new `wrapped_box_privkey` (DEK-wrapped).
- A new pending-sealed store (`sync-api`) and its read endpoint,
  `GET /v1/sync/pending-sealed`; reuse of the existing `POST /v1/sync` for the
  owner's re-encrypt-and-upload step (client id = sealed record id); the
  store's rows are deleted outright as part of an account reset (above).
- `POST /v1/auth/keys` (ADR-006) is the same bootstrap endpoint whether the
  account is new or was just reset — reset does not need its own key-upload
  endpoint.
- `POST /v1/admin/reset-account` (replaces the earlier "reset-password"
  naming, which implied the admin sets a password — it cannot) and
  `POST /v1/auth/complete-reset`; the one-time code's entropy/lifetime floor
  (≥128 bits, single use, 24-hour expiry), and the account's `reset_at` and
  `rekeyed_at` markers (ADR-006), shown together as "this account was reset
  on \<date\>; keys last changed on \<date\>".
- The deletion, on every
  re-key including the one that follows a reset, of any pending-sealed row
  still sealed to the box public key just replaced — a real behavioral
  requirement, not a documentation-only distinction, so it is called out here
  as something the code must actually implement, not infer.
- The job-result retention rule (fetched-or-24h) on `whisper-server`'s job
  store, alongside the existing queued-audio retention (ADR-010).
- Two new operations with the same cross-platform test-vector requirement
  ADR-006 places on the DEK path: `sealToOwner(pubkey, plaintext) ->
  sealedBox` lives in `whisper-server` (server-side, JVM), and
  `unsealAndReencrypt(sealedBox, boxPrivkey, dek) -> ciphertext` lives on the
  client (Android `core.CryptoService`, added when Phase 19 builds it, and the
  web FE). This documentation change set does not itself touch `core`.
- `whisper-server` gains a libsodium JVM binding and the seal-to-owner step,
  used only when a completed job's requester was an agent token and the
  owner already has a box public key (see the no-key-yet case above).

Rules out:
- Agent results left as plaintext-at-rest **indefinitely** with no bound at
  all — that defeats the one thing ADR-006 exists to prevent, for accounts
  whose owner relies on agent tokens as a routine, everyday path, not a rare
  edge case. The fetched-or-24h retention rule above is a **bound**, not an
  elimination: an unfetched result is still plaintext in SQLite for up to a
  day, and this ADR says so rather than calling that "server memory only."
- Agents unable to retrieve their own result at all — rejected in this
  revision specifically: it silently breaks the ADR-015 job contract for
  every agent-token caller, for no security gain, since the requester already
  holds the plaintext audio it submitted.
- Treating the sealed box as a defense against a server operator or anyone
  with database write access. **The honest limit:** `box_pubkey` is
  stored plain with nothing binding it to the account beyond the row it sits
  in, and `crypto_box_seal` is unauthenticated by design (that is what lets
  whisper-server seal without holding a secret). Anyone who can write the
  database — an admin, or an agent the operator has given server access to —
  could replace `box_pubkey` and silently redirect every future agent-token
  result to a key of their own choosing; there is no MAC over the public key
  under the DEK, so nothing on the wire proves the stored key is the one the
  account's own client generated. **A cheap, real check the client CAN make,
  and must:** at every login the client already unwraps `boxPrivkey` and
  receives `box_pubkey` in the same response — it computes the public key
  that `boxPrivkey` actually corresponds to (`crypto_scalarmult_base`) and
  compares it to the `box_pubkey` the server just handed back. A mismatch
  means the stored key was swapped since this client last generated one, and
  the client treats that as tampering (surface it, do not silently
  overwrite or re-bootstrap) — this catches the swap at the swapped
  account's very next login, for the cost of one scalar multiplication. It
  does not stop the swap from happening, and a swap that happens **and is
  never logged into again from a device that holds the real key** goes
  undetected, so this is a detection improvement, not a MAC. **The
  same write access forges content, not only redirects it:** because
  `crypto_box_seal` is anonymous and `box_pubkey` is plain, readable data,
  anyone who can write a row into the pending-sealed store directly — the
  same database writer, or anything that can reach whisper-server's
  server-internal seal/forward path — can insert a sealed box containing
  content they chose, sealed to the real `box_pubkey`. The owner's own client
  then unseals and converts it automatically, on login, with no review step,
  into ciphertext "indistinguishable from any other transcription" (see
  `android/modules/crypto/AGENTS.md`). The pending-sealed store's isolation
  from the transcription table (above) prevents an *unprocessed* forged row
  from posing as a real transcription; it does not prevent a forged row from
  *becoming* one once the owner's own client has done its automatic job.
  Sealed rows, in other words, are only ever as trustworthy as the server
  that produced them — this is **the same trust the server already holds**,
  since it processes every transcription submitted through it in plaintext
  during the job itself (on-device, local-only transcriptions never reach
  the server at all, so this does not apply to them) — the sealed box
  protects a **stored copy** from **read-only** exposure (a database dump, a
  backup, an admin or agent browsing rows after the fact), not from an actor
  who can act as the server, or write its database, while a job runs or a
  row lands.
- **The at-rest story for the queue itself, stated honestly:** queued
  audio sits in `whisper-server`'s SQLite job store for as long as the job
  waits in the queue, then is deleted once the job finishes (ADR-010) — it is
  not "server memory only". The completed **result** sits in the same store,
  in plaintext, until fetched or 24 hours old (above) — that is new ground
  this ADR covers, not a restatement of ADR-010, which is about audio, not
  results. The transcript also exists in server memory during transcription
  and formatting, and the downstream transcription service the admin has
  configured also sees the plaintext audio (F26). None of that changes here;
  this ADR seals the **completed, stored** result for an agent-token job,
  nothing upstream of that.
