# AGENTS.md — server/modules/sync-api/

## Purpose

Auth (users, roles, agent tokens), sync, updates, retention, store clear, log policy. The server API surface for Breaker: auth (users, tokens, **roles**),
transcription sync, and the **update endpoint**. Multi-user isolation is enforced
here (N13).

**Endpoints (see shared/api-contracts):**
- `POST /v1/auth/register` — `{username, salt, kdf_params, kdf_version,
  auth_verifier}` (ADR-006 — never a password, never a DEK; registration
  creates no DEK and no box keypair). **First account ever registered =
  admin by default** (F22). Subsequent = `user`. Rejects `kdf_params` below
  the accepted range or above it (memory 64 to 256 MiB, iterations 3 to 10,
  parallelism 1 to 4, inclusive, each field checked on its own; the low ends
  are ADR-006's floor). A value outside it is refused, not silently stored.
  A `kdf_version` other than 1 is refused.
- `POST /v1/auth/salt` — `{username} -> {salt, kdf_params, kdf_version}`.
  **No decoy**: an unknown username gets an honest "no such account" (ADR-006
  — account discovery sits inside the VPN perimeter, docs/01/05).
- `POST /v1/auth/login` — `{username, auth_verifier} -> {token, role,
  wrapped_dek, wrapped_box_privkey, box_pubkey, key_version, prev_wrapped_dek,
  reset_at, rekeyed_at}` (the three key fields (one of them, `box_pubkey`,
  plain rather than wrapped) are `null` together for a keyless account;
  `key_version` is the account's current DEK generation, `0` before its first
  bootstrap and never lowered, a reset included; `prev_wrapped_dek` is the
  previous DEK wrapped with the current one, present only while a re-key is
  unfinished, else `null`; `reset_at` is the timestamp of the account's most
  recent completed reset, or `null` if it has never been reset, and
  `rekeyed_at` is the timestamp of its most recent re-key, or `null` — the
  client shows "this account was reset on \<date\>" when `reset_at` is new to
  that device and "keys last changed on \<date\>" when `rekeyed_at` is new,
  each on its own, ADR-006/ADR-018). Token required on all APIs. The raw `auth_verifier` this
  endpoint receives is a login credential in its own right — never logged
  (F32) — not merely guessing-bait; see the honest limit below.
- `POST /v1/auth/keys` — `{wrapped_dek, wrapped_box_privkey, box_pubkey}`,
  accepted only while the account has no keys yet; accepting it sets the
  account's `key_version` to its previous value + 1 (`0` → `1` on a first
  bootstrap, previous + 1 after a reset) (first-login bootstrap,
  ADR-006/ADR-018; covers both a pre-Phase-19 account's first post-Phase-19
  login and a freshly reset one). **Requires a user session token from
  `/v1/auth/login` or `/v1/auth/complete-reset` — an agent token is refused**,
  because an admin can mint an agent token for any owner account, and this
  endpoint is exactly where a key-bearing write would matter most; the same
  restriction applies to `change-password`, `POST /v1/sync`, and
  `GET /v1/sync/pending-sealed`. **`complete-reset` itself is the one
  exception, not a fifth member of that list:** it needs no user session
  token at all, because the reset that preceded it revoked every one — it is
  authenticated by the one-time reset code instead (see below), and it
  refuses an agent token exactly as the others do, for the same reason. The
  same first post-Phase-19 login also triggers the client to migrate any
  pre-existing plaintext transcription rows the account holds (ADR-006) —
  that migration lands through the ordinary `POST /v1/sync` path below (as a
  `key_version` replace), not a special endpoint.
- `POST /v1/auth/complete-reset` — `{username, reset_code, salt, kdf_params,
  kdf_version, auth_verifier}`, authenticated by the admin-issued one-time
  reset code (≥128 random bits, single use, 24-hour expiry) instead of a
  password or a session token — the reset already revoked every token this
  account had, so there is none to present, and this endpoint refuses an
  agent token the same as the key-bearing endpoints above. Rejects
  `kdf_params` outside the accepted range, same as registration. Leaves the account
  keyless again, so `POST /v1/auth/keys` follows it exactly as it would a
  brand-new account.
- `POST /v1/auth/change-password` — Bearer token identifies the account;
  body `{old_auth_verifier, new_salt, new_kdf_params, new_kdf_version,
  new_wrapped_dek, new_wrapped_box_privkey, new_box_pubkey, new_auth_verifier,
  new_key_version, prev_wrapped_dek}` — refused (409) while the account still
  holds a `prev_wrapped_dek` from an earlier, unfinished re-key (a second
  change would overwrite the only copy of the older DEK); this module keeps
  `prev_wrapped_dek` until none of the account's records sits below the
  current `key_version`, whether the last one was replaced or deleted (F28,
  F33), then deletes it (ADR-006)
  — `old_auth_verifier` re-proves the current password before any key
  material is replaced, so a live token alone is never sufficient (ADR-006).
  Rejects `new_kdf_params` outside the accepted range, same as registration. Revokes
  every other outstanding **user session token**; agent tokens are not
  password-derived and are unaffected — they are revoked only through
  `DELETE /v1/admin/agent-tokens/{id}`. **This endpoint always re-keys — there
  is no lighter "just re-wrap the DEK" call and no special case for a change
  that happens to follow a reset:** every call replaces the wrapped DEK, the
  wrapped box private key and the box public key, advances the account's
  `key_version` by one, and records `rekeyed_at`; this module does not need
  to tell "an ordinary change" and "the change after a reset" apart, it just
  accepts the new wrapped/plain values for the fields it owns and deletes any
  pending-sealed row still sealed to the `box_pubkey` it just replaced.
- `POST /v1/sync` — push transcriptions. Idempotent (N12) on the **pair**
  (`client_id`, `key_version`), not on `client_id` alone: the same pair is a
  no-op (two devices' AES-256-GCM ciphertexts for the same plaintext never
  match on their own, because each uses a fresh nonce, so this is a real
  retry-dedupe, not a content hash); the same `client_id` at a **higher**
  `key_version` is an atomic replace — this module swaps the stored row for
  the new one and deletes the old one in the same transaction, only once the
  new one has landed; the same `client_id` at a **lower** `key_version`, or a
  brand-new `client_id` below the account's current `key_version`, is
  refused (409). This one path carries the Phase 11–18 plaintext migration
  (`key_version` 0 → 1) and every re-key (`n` → `n+1`) alike — neither is a
  second, same-version push of a row already on file.
- `GET /v1/transcriptions` — pull user's transcriptions (web FE + app; also
  what the first-login bootstrap reads to find any plaintext rows to
  migrate, ADR-006), each with its own `key_version` so a client can tell
  which of its own rows still need replacing after an interrupted migration
  or re-key.
- `GET /v1/sync/pending-sealed` — the authenticated owner's still-sealed
  agent-token job results (ADR-018): `[{id, sealed_box, created_at}]`. The
  owner's client unseals each and re-uploads it via `POST /v1/sync` with
  `client_id` = that record's `id` at the account's **current**
  `key_version` (a new record, not a replace); this module deletes the
  pending record once a synced transcription with that client id lands.
  **Honest limit (T26):** this endpoint returns whatever this module's
  pending-sealed store holds; it has no way to tell a sealed box
  whisper-server actually produced from one a database writer inserted
  directly — both are equally well-formed `crypto_box_seal` output, and the
  owner's client converts either kind the same way.
- `GET /v1/updates/latest.json` — latest tagged release from the local GIT
  server: `{ version, apk_url, sha256, signature, notes }` (F20/F21).
- `POST /v1/admin/roles` — **admin-only**: assign/revoke admin on another account.
- `GET /v1/admin/health` — admin-only: server health + latest GIT tag.
- `GET/PUT /v1/admin/transcription-service` — **admin-only**: read/update the
  configured transcription endpoint (name, base URL, optional API key, enabled) (F26).
- `POST /v1/admin/test-service` — **admin-only**: probe the configured endpoint,
  return reachable/unreachable. URL validation (T20).
- `POST /v1/admin/agent-tokens` — **admin-only**: create scoped token (name,
  optional expiry, owner account). `DELETE /v1/admin/agent-tokens/{id}` — revoke (F27).
- Agent auth: `Authorization: Bearer <agent-token>` on `POST
  /v1/audio/transcriptions` + `GET /v1/jobs/{id}` — same FIFO queue, results to
  the owner's namespace. Rate-limited (T19).
- `POST /v1/admin/reset-account` — **admin-only** (incl. admin-scoped agent
  tokens, F31): deletes the account's wrapped DEK, box keypair, and verifier
  hash; deletes its transcriptions outright (R20 — undecryptable once the
  keys are gone) and any still-unfetched pending-sealed rows for it (they are
  sealed to a box key that no longer exists); revokes all its tokens;
  deletes any `prev_wrapped_dek`; keeps the account's `key_version` counter
  (it never goes backwards, so the next bootstrap sets previous + 1 and a
  stale pre-reset device's pushes are refused); records `reset_at = now()` on
  the account; and returns a one-time reset
  code (**≥128 random bits, single use, expires 24 hours after issue — no
  attempt-limit claim, this repo ships no brute-force protection on any auth
  endpoint**) for the admin to pass to the user out of band (ADR-018).
  Whoever calls `POST /v1/auth/complete-reset` first with that code chooses
  the new password on their own device — in the normal flow that is the
  user, but this endpoint cannot itself tell that apart from the admin
  completing it before handing the code over; that risk and its mitigation
  (DEK/keypair rotation at the account's next password change) are ADR-018's
  to state, not this module's to paper over. Logged as an event (F32).
- `POST /v1/admin/clear-store` — **admin-only**: clear transcriptions per-user
  or for all users, with confirmation (F29, T23). Tombstones propagate to phones.
- Retention: transcriptions TTL = **3 months** (ADR-010 precise rule); purge job enforces it (F28).
- **Log policy (F32):** events only, per-user — auth, admin actions, queue
  health. **Never plaintext transcriptions** (T22).
- **Multi-admin config:** concurrent edits to the transcription service config
  are **last-write-wins** (documented default; no locking in v1).

**Roles (F22, T16):**
- `admin` — first registered account; can assign/revoke admin, force update
  checks, view health.
- `user` — isolated data, no admin powers.
- Role checks are **server-side only**; the client never trusts its own role claim.

**Isolation (N13):** every query is scoped by the authenticated user_id. No
cross-user reads, ever. Training samples and transcriptions live in per-user
namespaces.

**Encryption at rest (F24, ADR-006):** the **client**, not this module,
generates the DEK and derives the KEK (Argon2id + HKDF split), and only at
the first-login bootstrap, not at registration. Registration receives and
stores, as-is, `{salt, kdf_params, kdf_version, hash(auth_verifier)}` (params
checked against the accepted range first) — never a password, never a DEK, never a
KEK. Login receives the auth verifier, checks it against the stored hash,
and returns the account's wrapped keys, its current `key_version`, and
`reset_at`/`rekeyed_at` unchanged (this module never unwraps the keys or
computes a version itself — it advances `key_version` only when it accepts a
bootstrap (`/v1/auth/keys`) or a `change-password`, by exactly one, and
never lowers it). Transcriptions from an account that has
completed its bootstrap are stored as **ciphertext only** (T17); a Phase
11–18 account that has not yet had its first Phase-19-or-later login still
has its transcriptions stored as **plaintext** (`key_version` 0) here until
that login's migration runs, and any account's own records can sit at more
than one `key_version` for as long as an interrupted migration or re-key
takes to finish replacing them (ADR-006) — this module does not distinguish
the cases specially, it just stores whatever `key_version` the client
uploads with each row and applies the replace/refuse rules on
`POST /v1/sync` above. Also stores each account's box public key
(ADR-018, plain) and wrapped box private key (DEK-wrapped, same treatment as
the DEK itself), used by `whisper-server` to seal agent-token job results
before they reach this module's pending-sealed store, when the account has
one yet.

**Honest limit (ADR-006/ADR-018):** this module never holds a usable key,
but for **Android** clients, whoever obtains its stored verifier hash or a
wrapped DEK — a DB/backup breach, a log line — can test password guesses
offline at Argon2id cost. That is not a gap in this module; it is the actual
strength of the scheme, and this repo ships no rate limiting on the auth
endpoints to raise that cost further (auth hardening is explicitly out of
scope; docs/01, docs/05; see T25 — not T18, which is the queue's per-user
limiter and does not apply to these unauthenticated endpoints). **This does
not hold for the web FE:** that client's key-handling JS is served by
`web-fe`, a sibling of this module on the same server, so a compromised
server captures the password or DEK directly at the next browser login — no
guessing (T27). Separately, a **raw** (unhashed) leaked `auth_verifier` is
not an offline-guessing case at all: it logs the holder in immediately and
lets them call `change-password`, though `change-password` now also requires
`old_auth_verifier` in the body, so a token alone (without the raw verifier)
is not enough to rotate keys. An admin/agent-scoped reset destroys the
account's old transcriptions and pending-sealed rows outright rather than
pretending to recover them, and bounds — rather than eliminates — whoever
completes it by rotating the DEK at the account's next password change
(R20, ADR-018).

**Build phase:** Phase 11 (keys and re-key in Phase 19; agent tokens, reset and pending-sealed in Phase 20). Needs first: `api-contracts`.

## Invariants
- First registered account is admin; second is user (F22).
- Admin can promote/demote; users cannot self-promote (T16).
- User A cannot read user B's transcriptions (N13 test).
- Sync retries don't duplicate (N12 test).
- `/v1/updates/latest.json` reflects the latest GIT tag.
- `POST /v1/auth/keys` rejects a second call once an account has keys — a
  keyless account can only get keys once per reset (ADR-006, ADR-018).
- A reset leaves an account fully keyless and token-less until
  `/v1/auth/complete-reset` runs; login fails cleanly in between, never
  partially.
- A reset also clears the account's pending-sealed rows, so
  `GET /v1/sync/pending-sealed` never returns a row sealed to a box key the
  reset just deleted; an ordinary re-key (`change-password`) clears the same
  way for whatever `box_pubkey` it just replaced.
- `register`, `change-password` and `complete-reset` never accept
  `kdf_params` outside the accepted range, 64 to 256 MiB memory, 3 to 10
  iterations, parallelism 1 to 4, inclusive, each field checked on its own;
  `register` also refuses any `kdf_version` other than 1
  (test: submit a set below and a set above it, confirm the 4xx each time).
- `change-password` always advances `key_version` by exactly one and never
  accepts a same-or-lower `new_key_version` (test: replay an old value,
  confirm the 4xx) — there is no call shape that re-wraps without advancing
  it.
- `POST /v1/sync` never treats a higher-`key_version` push at an existing
  `client_id` as a duplicate, and never treats a lower one as an update — the
  first replaces, the second is refused (test: push both orders, confirm the
  replace and the 409).
- A username is 1 to 64 characters from `A-Z a-z 0-9 . _ -` only. It is
  stored as typed; uniqueness and every lookup use its lower-case form, so
  "Bob" and "bob" cannot both exist and a lookup under another case finds the
  account (test: register both, expect the second refused; look up under a
  different case).
- The auth verifier is exactly 32 bytes; any other length is refused before
  any hashing. The server keeps only a salted hash of it (PBKDF2-HMAC-SHA256,
  600,000 iterations by default, a fresh 16-byte salt per account, the
  algorithm id and iteration count stored in the row so the cost can be
  raised later) and compares hashes in constant time.

## Owns
Auth (users, roles, agent tokens), sync, updates, retention, store clear, log policy.

## Public Interface
AuthApi, SyncApi, AdminApi

## Depends On
- server (registered in modules.toml)
- shared_api_contracts (registered in modules.toml)

## Does Not Own
- Transcription (whisper-server)
- UI (web-fe)

## Test Locations
- Unit (Kotlin/Ktor, ADR-017): `server/modules/sync-api/src/test/kotlin/`. Run: `./gradlew :server:modules:sync-api:test`. The entry point `main` is not covered by unit tests, because it needs a real socket; every function it calls is.
- Contract: `tests/contract/test_sync_api_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_sync_api_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- First account = admin; last admin cannot be deleted/demoted; logs never plaintext (F32).
- Written in Kotlin on the JVM with Ktor, compiled against api-contracts' Kotlin types (ADR-017).
- This module never wraps or unwraps a key — it only stores and returns
  whatever the client already wrapped. Do not "helpfully" add a server-side
  KDF call here; that is exactly the design ADR-006's amendment moved away
  from.
- `box_pubkey` has no integrity binding in v1 (T26) — this module is the
  only place that can overwrite it, so a write path here is a write path an
  attacker with DB/admin access could also use; treat it with the same care
  as the wrapped-key columns even though it is stored plain. The same is true
  of the pending-sealed store: a write path into it is a forged-transcription
  path, not only a redirect path (T26).
- `change-password`'s `old_auth_verifier` field is the only thing standing
  between "holds a live session token" and "can replace this account's
  keys" — do not let the Bearer token alone satisfy this endpoint.
- The server-side hash adds no guessing resistance beyond the client's key
  derivation: the verifier is 32 pseudorandom bytes, and the hash is there so
  that a stolen row is not a usable login.
- An unknown username is refused without running the hash, so it costs less
  time than a wrong verifier. That is by design: there is no decoy, and
  account existence is already public at the salt step.
- No logging binding is on the classpath yet, so Ktor prints a notice about a
  no-op logger in tests.
- `sqlite-jdbc` unpacks a native library into the temporary directory at
  first use, so the container needs a writable one.
- Hashing at 600,000 iterations takes a noticeable fraction of a second per
  register or login. It runs off the database lane.
- The database holds one connection, and every transaction runs on one
  single-lane coroutine dispatcher.

## Third-Party Dependencies

This is a licence check and an advisory-database lookup, not a source audit.
Licences were read from each artifact's POM on 2026-10-07. Each version is the
newest stable release on Maven Central on 2026-10-07; the read dates and URLs
for the Ktor and coroutines versions are in `gradle/libs.versions.toml`.
Password hashing uses the JDK's `javax.crypto.Mac`, so it adds no
dependency (Bouncy Castle was considered for Argon2id and not used).

| Dependency | Version | Scope | Licence | Security note |
|---|---|---|---|---|
| `io.ktor:ktor-server-core` | 3.6.0 | main | Apache-2.0 | 0 advisories in the OSV database on 2026-10-07. |
| `io.ktor:ktor-server-cio` | 3.6.0 | main | Apache-2.0 | 0 advisories. Pure Kotlin, no native code. |
| `io.ktor:ktor-server-test-host` | 3.6.0 | test | Apache-2.0 | 0 advisories. |
| `org.xerial:sqlite-jdbc` | 3.53.4.0 | main here | Apache-2.0 | 0 advisories. Ships native code. The catalog entry is test-only in the android modules and the runtime driver here. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | main | Apache-2.0 | 0 advisories. |
| `junit:junit` | 4.13.2 | test | EPL-1.0 | 0 advisories. |
| `org.slf4j:slf4j-api` | 2.0.19, transitive via Ktor | main | MIT (the project's licence; its POM has no licence element) | No logging binding is added. |

A control query on the same date for a known-vulnerable library (log4j-core
2.14.1) returned 7 advisories, so the lookup works.
