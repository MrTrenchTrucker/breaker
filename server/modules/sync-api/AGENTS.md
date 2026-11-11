# AGENTS.md — server/modules/sync-api/

## Purpose

Auth (users, roles, agent tokens), sync, updates, retention, store clear, log policy. The server API surface for Breaker: auth (users, tokens, **roles**) and
transcription sync. The route that changes a role and the update endpoint are both built.
Multi-user isolation is enforced here (N13).
A route or job marked Planned, not built below is not installed on main yet.

**Endpoints (see shared/api-contracts):**
- `POST /v1/auth/register` — `{username, salt, kdf_params, kdf_version,
  auth_verifier}` (ADR-006 — never a password, never a DEK; registration
  creates no DEK and no box keypair). **First account ever registered =
  admin by default** (F22). Subsequent = `user`. Rejects `kdf_params` below
  the 64 MiB / 3 iterations / parallelism 1 floor (ADR-006) — a weaker
  request is refused, not silently stored. It refuses `kdf_params` outside
  the same bounds in both directions (memory 64 to 256 MiB, iterations 3 to
  10, parallelism 1 to 4), a `kdf_version` other than 1, and an
  `auth_verifier` that is not exactly 32 bytes, before it hashes it (the wire
  encoding, base64 or hex, is decided in api-contracts). **Once an account
  exists, registration is closed unless the `registration_open` admin switch
  is on**: the register route answers 403
  `{"error":"registration_closed","message":"Registration is closed."}`
  (the 403 comes before the username-taken 409, and no PBKDF2 hash runs on a
  closed server). Default after upgrade on a server that has accounts =
  CLOSED; the admin opens it with `PUT /v1/admin/registration-open`. The
  closed check runs in the same insert transaction as the account insert, so
  it cannot race with the first-admin rule; `AccountStore.register` itself is
  unchanged (store-level tests that register more than one account keep
  passing) and the rule lives in the route path through
  `AccountStore.registerIfOpen`.
- First-admin install code (ADR-043 slice C): while no account exists the server start makes a 43 character
  URL-safe code in the `install-code` file next to the database (`BREAKER_SYNC_API_INSTALL_CODE_FILE`
  changes the path; set but blank is refused), mode 0600 set at creation, and prints one notice line, never the
  code. A start that finds a file with a bad length, bad characters or group or other permissions refuses to
  run. The first registration must carry `install_code`: a missing code and a wrong code answer the same 400
  `invalid_field`, and a wrong code costs no password hash. The code is compared in the register transaction as
  well as before the hash. Once an account exists the field is ignored and the file is deleted after the first
  account commits (best effort).
- `GET/PUT /v1/admin/registration-open` - **admin-only**: read and flip the
  `registration_open` switch (server_settings key, integer 0/1). GET answers
  `{"enabled":true}` or `{"enabled":false}`; PUT accepts exactly one member,
  `enabled`, the literal `true` or `false`, anything else answers 400
  `bad_request` "enabled must be true or false"; both answer 200 with the
  read-back stored state. Mirror of `admin/AdminWrongPwLimitRoute.kt`:
  bearer 401 -> admin 403 -> body cap 413 -> body shape 400 -> fixed 503 on a
  store failure -> read-back state. Admin scope only: 401 no session, 403
  non-admin and agent tokens.
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
  `rekeyed_at` is the timestamp of its most recent re-key, or `null`. Each is
  written as UTC with exactly three fractional digits, so a whole second prints `.000`
  (`2026-11-04T08:47:41.000Z`). The
  client shows "this account was reset on \<date\>" when `reset_at` is new to
  that device and "keys last changed on \<date\>" when `rekeyed_at` is new,
  each on its own, ADR-006/ADR-018). Token required on all APIs. The raw `auth_verifier` this
  endpoint receives is a login credential in its own right — never logged
  (F32) — not merely guessing-bait; see the honest limit below.
  A store failure in the credential
  check (`AccountStore.verify`), in the key read (`AccountStore.keysFor`) or in the session issue
  (`TokenStore.issueSession`) answers 503 `unavailable` "The service is unavailable, try again
  later" (the same fixed body as the other routes), never a 500, and no token row is made.
  An account that waits for a reset to be completed (`reset_pending` = 1, set by the admin reset)
  cannot log in. `AccountStore.verify` takes the same path as for an active account with a wrong
  verifier: with the limit on, one full hash against the decoy, the try counted, and the lock after
  five wrong tries (423 with Retry-After); with the limit off, one hash against the decoy and no
  count and no lock. The login route answers the same 401 body as a wrong verifier, and no session,
  agent token or hand-off code is made for the account. The lock check still runs before the row
  read. The decoy is a fixed value that no verifier is derived from, and the stored random verifier
  is never compared, so a login with the old verifier never succeeds. `POST /v1/auth/salt` answers a
  pending account as it answers any account. A wrong try that lands after a reset has cleared the
  row is an ordinary count: `recordWrong` reads no account state, on purpose, because a no-op for a
  pending account would tell it apart from an active one again. Complete-reset clears that row, and
  a lock never blocks complete-reset.
  A pending account never gets a session, an agent token or a hand-off code: `TokenStore.issueSession` and `TokenStore.issueAgent` read `reset_pending` in the same transaction as the insert and make no row, and a login that meets it in that gap answers the same 401 and counts nothing.
- The three routes that need no login (`register`, `salt` and `login`) read a request body of at most 4096 bytes: a longer body is refused with `413` and `payload_too_large` before it is parsed, whether its length is declared or not, and nothing is stored. The keys route (below) reads at most 4096 bytes too, with the same refusal.
- Internal token-check routes (mounted on the internal listener only; written and tested): `POST /internal/v1/token/use` and `POST /internal/v1/token/peek`, called only by whisper-server. Both take no body; the headers are `X-Service-Key` (checked first, in constant time; the key cannot be blank) and `X-Presented-Token`. A wrong or missing key is a 403 `bad_service_key`; with a good key every not-live cause (missing, malformed, oversized, unknown, revoked, expired) is the same 401 `not_live`, and a live token is a 200 with `account_id`, `kind` and `role`. `use` is `TokenStore.resolve` (a live session token's expiry moves to now + 30 days); `peek` is the read-only `TokenStore.peek` (the same live rule, nothing written). They are mounted on the internal listener and on no other listener (see the section Internal listener and service key).
- `POST /v1/auth/keys` - BUILT. `{wrapped_dek, wrapped_box_privkey, box_pubkey}`,
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
  The rules of this route, in order (`auth/AuthKeysRoute.kt`). (1) The token is resolved first: a
  missing, malformed, unknown, revoked or expired token is 401, and a failed token check is 503. (2) A token that is not a user session token (an
  agent token) is 403, the same body as `POST /v1/sync`, decided right after the token resolves
  and before the body is read. (3) The body is read with a cap of 4096 bytes: a longer body is 413
  before it is parsed. (4) The body is one JSON object with exactly the three members, each a
  string of standard base64 (RFC 4648, padded, no whitespace, no line wrap) that decodes to exactly
  60 bytes (`wrapped_dek`), 60 bytes (`wrapped_box_privkey`) and 32 bytes (`box_pubkey`); the 60
  bytes are the 12-byte nonce, the 32 encrypted key bytes and the 16-byte tag (AES-256-GCM). A
  malformed body, a missing, extra or non-string member, bad base64 or a wrong decoded length is
  400 and nothing is written. The module checks only the lengths and never opens the values. (5)
  In one transaction the call is accepted only while the account has no keys (a keyed account is
  409). Before that, in the same transaction, the `reset_pending` mark is read: a pending account is
  409 `conflict` "The account is waiting for a reset to be completed" and nothing is written; this
  refusal comes before the has-keys refusal, because a reset account is keyless too. The body is read
  first, so a bad body on a pending account is 400. It stores the decoded bytes
  and re-encodes them on the way out, so a value whose last character carries spare bits is accepted and login writes
  it back as the canonical padded base64 of the stored bytes. It sets `key_version` to its
  previous value + 1. A failure inside the transaction leaves every column and `key_version` as
  they were, and the answer is 503. If the account is gone by then, the answer is the 401 above. Answers: 200
  `{"key_version": N}` (N is the new counter; the success body is a choice of the contract, the
  same shape `change-password` uses), 400 `bad_request` "Invalid request body", 401
  `unauthorized` "Invalid or missing user session token", 403 `forbidden` "User session token
  required; an agent token is refused", 409 `conflict` "The account already has keys" (for an
  account that waits for a reset to be completed, 409 `conflict` "The account is waiting for a reset
  to be completed", first), 413
  `payload_too_large` "Request body exceeds maximum allowed size", 503 `unavailable` "The service
  is unavailable, try again later". No part of the request is echoed.
- `POST /v1/auth/complete-reset` - BUILT. `{username, reset_code, salt, kdf_params,
  kdf_version, auth_verifier}`, authenticated by the admin-issued one-time
  reset code (32 random bytes, 256 bits, 43 URL-safe characters, single use, 24-hour expiry)
  instead of a password or a session token - the reset already revoked every token this
  account had, so there is none to present, and this endpoint refuses an
  agent token the same as the key-bearing endpoints above. Rejects
  `kdf_params` outside the bounds above and a `kdf_version` other than 1, same
  as registration. Leaves the account
  keyless again, so `POST /v1/auth/keys` follows it exactly as it would a
  brand-new account. The rules of this route, in order (`auth/AuthCompleteResetRoute.kt`,
  `auth/CompleteResetBody.kt`, `reset/ResetStore.kt`). (1) The `Authorization` header is read only
  to refuse an agent token: a non-empty bearer text is looked up with `TokenStore.peek` (which
  writes nothing and moves no expiry); a token of kind agent is 403 (the same body as `POST
  /v1/sync`) before the body is read, a failed check is 503, and any other header, a session token
  included, is ignored. (2) A body over 4096 bytes is 413. (3) The body is read by a strict reader:
  exactly the six members, each once, `kdf_params` with exactly its three whole-number members,
  every text member a JSON string, nothing after the object; any shape fault is 400. Only the shape
  and the types are checked here. (4) A user name that is not valid, an unknown name, an account that
  is not waiting for a reset, no live code (a code is dead AT its expiry instant), a malformed code
  and a code whose SHA-256 does not match are all the one fixed 401 `unauthorized` "Invalid
  username or reset code", with the same status and the same bytes. The lookup and the compare are
  one function in `reset/ResetStore.kt`, and `MessageDigest.isEqual` runs on every path (filler
  values stand in for a malformed code and for a missing row), so none of these cases is faster than
  another. (5) Only after a right name and code: the salt must be 24 characters of standard padded
  base64 that decode to 16 bytes, the KDF values inside the bounds, `kdf_version` 1, and the
  verifier 44 characters of standard padded base64 that decode to 32 bytes; a fault is 400 and
  nothing is written. (6) The new verifier is hashed before the transaction opens. (7) One
  transaction: the lookup and compare again, the code row deleted by its account and hash (a count
  other than 1 is the same 401, so of two completions with one code only one succeeds), the new
  salt, KDF fields and verifier written with `reset_pending` set to 0 (only for an account that is
  pending), the wrong-password row cleared, and a session made with the call the sign-in exchange
  uses. A failure inside rolls the spend of the code back and answers 503. The answer is 200 with
  the login body (keyless, `key_version` unchanged, `reset_at` set). There is no attempt counter,
  because the code is 256 random bits: a wrong code leaves the wrong-password row alone, and a
  locked name does not block a right code (the lock is cleared on success). Known limit: whoever
  presents the code first chooses the password (ADR-018). The reset code and the request body are
  never logged.
  Unlimited tries are safe here: the reset code is 256 random bits and works only once, so a cap per
  name would protect nothing against guessing and would only let anyone who knows a username lock the
  owner out during the 24 hour window in which the code is valid. Limiting the request rate is left to
  the reverse proxy of the deployment (backlog).
  A 400 for the salt, the KDF values or the verifier comes only after a correct code, so only the
  holder of a valid code sees it and it reveals nothing new; one fixed answer there would cost that
  person a useful error.
- `POST /v1/auth/change-password` - BUILT. Bearer token identifies the account;
  body `{old_auth_verifier, new_salt, new_kdf_params, new_kdf_version,
  new_wrapped_dek, new_wrapped_box_privkey, new_box_pubkey, new_auth_verifier,
  new_key_version, prev_wrapped_dek}` — refused (409) while the account still
  holds a `prev_wrapped_dek` from an earlier, unfinished re-key (a second
  change would overwrite the only copy of the older DEK); this module keeps
  `prev_wrapped_dek` until none of the account's stored records that is not
  deleted sits below the current `key_version`, then sets it to
  NULL (ADR-006; the rule is described below)
  — `old_auth_verifier` re-proves the current password before any key
  material is replaced, so a live token alone is never sufficient (ADR-006).
  Rejects `new_kdf_params` outside the bounds above and a `new_kdf_version`
  other than 1, same as registration. Revokes
  every other outstanding **user session token**; agent tokens are not
  password-derived and are unaffected — they are revoked only through
  `DELETE /v1/admin/agent-tokens/{id}`. **This endpoint always re-keys — there
  is no lighter "just re-wrap the DEK" call and no special case for a change
  that happens to follow a reset:** every call replaces the wrapped DEK, the
  wrapped box private key and the box public key, advances the account's
  `key_version` by one, and records `rekeyed_at` (the time of the change, in epoch milliseconds
  from the injected clock); this module does not need
  to tell "an ordinary change" and "the change after a reset" apart, it just
  accepts the new wrapped/plain values for the fields it owns. It also deletes, in the same
  transaction, any pending-sealed row of the account that was sealed to the `box_pubkey` it just replaced.
  The rules of this route, in order (`auth/AuthChangePasswordRoute.kt`,
  `auth/ChangePasswordBody.kt`, `passwordchange/PasswordChangeStore.kt`). (1) The token is resolved
  first: a missing, malformed, unknown, revoked or expired token is 401, and a failed token check is
  503. (2) An agent token is 403 (the same body as `POST /v1/sync`) before the body is read. (3) A body
  over 4096 bytes is 413. (4) The body is read by a strict reader (`parseChangePasswordBody`): one JSON
  object with exactly the ten members, each once; a malformed body, a missing, extra, duplicate or
  wrongly typed member, a bad base64 text, a wrong decoded length (old verifier 32, new salt 16, new
  verifier 32, new wrapped DEK 60, new wrapped box private key 60, new box public key 32 and
  `prev_wrapped_dek` 60 bytes, required), `new_kdf_params` outside the bounds, a `new_kdf_version`
  other than 1 or a `new_key_version` below 1 is 400 before any write, and nothing is counted. (5) The
  old verifier is re-proved through the same path as login (`AccountStore.verify` on the account's
  folded name): a wrong one counts as a wrong try and is 401
  `unauthorized` "Invalid old verifier"; one that is not base64 or not 32 bytes is the 400 above and
  does not count; a locked name gets the locked 423 with Retry-After in whole seconds (the same
  answer as login). A right old verifier clears the wrong-password row inside `verify`, as in login
  (when the limit is on), and the change transaction clears it again. A signed-in session keeps
  working (sync, sign-out) while the name is locked; changing the password needs the name unlocked.
  (6) The new verifier is hashed, with the hashing of registration, before the transaction opens. (7)
  Then one transaction makes all the effects or none. It first reads that the presented token is
  still a live session and that the account exists (otherwise the same 401 as below). It is then 409
  `conflict`, in this order when more than one applies, when the account has no keys ("The account
  has no keys"), when `new_key_version` is not the current one + 1 ("key_version is not the
  account's current key_version plus one"), or while `prev_wrapped_dek` is held ("A previous re-key
  is unfinished"). It is 409 first, before all of these, when the account waits for a reset to be
  completed ("The account is waiting for a reset to be completed"): the mark is read once more
  inside this transaction, and also right after the session check and before the body is read
  (`AccountStore.resetPendingOf`), so a bad or oversized body on a pending account is 409 and no
  wrong try is counted. A session token that is revoked
  or expired before the transaction commits (for example by an admin reset running at the same
  time) gets the same 401, `unauthorized` "Invalid or missing user session token". Success writes
  every changed column in one statement as sent (the salt, the KDF values and version, the
  verifier hash, the three keys, `prev_wrapped_dek`, `key_version`, `rekeyed_at`; the server never
  moves the old wrapped DEK itself), runs the previous-key rule, revokes every
  other user session token and ends the account's unredeemed sign-in codes (the presented session
  and the agent tokens are kept), clears the wrong-password row and answers
  200 `{"key_version": N}`. A failure of the store anywhere in steps (1) and (5) to (7) is 503
  `unavailable` and the transaction leaves every column, token and the wrong-password row as it was.
  The previous-key rule (`accounts/PrevKeyRule.kt`, one statement with one bind, the one place it is
  written): `prev_wrapped_dek` is set to NULL when no stored transcription of the account that is not
  deleted has a `key_version` below the account's `key_version`. It runs inside the transaction of
  each write that can change the answer, right after that write: the change-password write (an
  account with no old rows is cleared at once), the `POST /v1/sync` push that replaces a row at a
  higher `key_version`, and an applied `DELETE /v1/transcriptions/{client_id}`. A delete record
  carries no body and never holds the column.
- `POST /v1/sync`: push one transcription. BUILT. Idempotent on the **pair** (`client_id`,
  `key_version`), not on `client_id` alone. The body is one JSON object with the
  keys `client_id` (a lowercase canonical UUID), `key_version` (a whole number from 0 up to
  the largest signed 32-bit integer) and `encrypted` (non-empty standard base64 with correct
  padding and no whitespace), plus one optional key `resave`, which must be the literal true or false
  and counts as false when absent. The server stores `encrypted` as an opaque string and never
  decodes it. The body is at most 1 MiB (1,048,576 bytes): one byte more is refused with 413
  `payload_too_large` before it is parsed. Any other shape or field is refused with 400
  `bad_request` and a fixed message that never echoes the input. For a valid body: the same
  pair is a no-op (204) and never overwrites the stored text; the same `client_id` at a
  **higher** `key_version` replaces its one row in place (204); a `key_version` above the
  account's current one is refused with 409 `conflict` and the message "key_version is above
  the account's current key_version"; the same `client_id` at a **lower** `key_version`, or
  a brand-new `client_id` below the account's current `key_version`, is refused with 409
  `conflict` and the message "A lower key_version is refused for this client_id". The route
  needs a user session token: no token, a malformed one or an unknown one is 401, and an
  agent token is 403. A storage failure or a failed session check is 503 `unavailable`.
  An account is at `key_version` 0 until its first key upload sets it to 1; a push is checked
  against the stored `key_version`, so a keyless account accepts only key_version 0 and an
  account at key_version 1 accepts 1 (a push below the account's key_version is refused as above).
- `GET /v1/transcriptions`: the caller's feed of transcriptions, one page per answer. BUILT.
  The answer is `{"items":[...],"next":"<account>-<change>","more":true or false}`. Records come
  in change order (the account's change number, oldest change first). A live record is
  `{"client_id","change","deleted":false,"key_version","encrypted","stored_at"}`; a delete record is
  `{"client_id","change","deleted":true,"stored_at"}` with no key version and no body. `stored_at`
  is the UTC time of the row's last write (for a delete record, of the delete), written as
  `YYYY-MM-DDTHH:MM:SS.mmmZ` (three millisecond digits, the form of `created_at`). Without `since`
  the answer holds live rows only. With `since=<account>-<change>` it holds every row (live and
  deleted) whose change number is above
  `<change>`. A page holds at most 100 records and a running size of 4 MiB (a record counts
  its body length plus 200; a record that alone passes the size is still returned alone). `next`
  is the change of the last record when `more` is true, and the account's head otherwise; the
  client repeats with `since=next` while `more` is true. A malformed `since`, or one whose
  account part is not the caller's account (written in decimal with no leading zero), is 400
  `bad_request`, answered before the store is asked; a `since` below the purged floor is 410
  `gone`. BUILT (server, ADR-035 section 1D): a list longer than one page returns a LIST
  cursor `<account>-<position>-<head>` as `next` while `more` is true; a list cursor is never 410, one
  whose position is 0 or not below its head is 400, a list reads only live rows up to the first-page head (a
  snapshot), and the final page returns the incremental cursor at the first-page head. A pull with a since sets the session's number to the smaller of the since and the head; every
  successful pull then deletes the delete records at or below the lowest number of the account's live user
  sessions, and nothing when a live session has no number. There is no limit parameter. It needs a user
  session token, with the same 401 and 403
  answers as the push, and a storage failure, a stored time that cannot be formatted, or a
  failed session check is 503 `unavailable`. The intended callers are the web FE and the app; the first-login bootstrap
  that reads the list to migrate plaintext rows (ADR-006) is not built.
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
  Built (the read side): migration 10 (`db/SchemaV10.kt`) adds the table `pending_sealed` (`id` text key,
  `account_id` with ON DELETE CASCADE, `sealed_box` as the base64 text this route returns, `created_at`) and
  an index on `account_id`; the route answers a user session only (no token or a dead one 401, any agent
  token, an admin scoped one too, 403) with the rows of the token's own account, never one named in the
  request, ordered by `created_at` then `id`, as `[]` when there are none, and the fixed 503 on a failure.
  No paging and no cap yet: cap or page before the write path is built. **Nothing writes the table yet:**
  how whisper-server hands a sealed copy over is not decided (see below). The server deletes no pending
  row when a synced transcription with its client id lands: that part is not built either.
- `GET /v1/updates/latest.json` — the update manifest FILE read from the path in
  `BREAKER_SYNC_API_MANIFEST_FILE`: `{ version, version_code, apk_url, sha256,
  signature, notes }` (F20/F21, ADR-011). **Built**, public, no session.
  The variable unset, or no file at the path: 404 not_found, "No release yet". A
  file that is there but unusable (a folder, over 65,536 bytes, not UTF-8, not one
  JSON object, a missing, blank or mistyped field, a version_code below 1): 503
  unavailable, a fixed body that echoes nothing of the file. A valid file: 200, the
  body rebuilt from the six fields in that order. The release tool that writes the
  file is not built.
- `POST /v1/admin/roles` — **admin-only**: assign/revoke admin on another account.
  Built (adminSetRole): the body is exactly `username` (a string) and `admin` (true or false), nothing
  else; the answer is 200 with the stored username and the new role (asking for the role the account
  already has is a 200 that changes nothing); an unknown or invalid name is 404; a demotion that would
  leave no admin is 409, decided in the same statement that writes the role; a pending account may be
  promoted or demoted. The role is read from the account on every call, so a demoted admin's session
  and admin agent tokens stop being admin at once.
- `GET /v1/admin/health` — admin-only: server health + latest GIT tag.
  Built (adminHealthRoute): 200 with `{"status":"ok","git_tag":...}` for an admin token (401 without a live token, 403 for
  a non-admin); status is always `ok` (the token check needed the database); `git_tag` is the value of the
  environment variable `BREAKER_SYNC_API_GIT_TAG` (1 to 64 printable ASCII characters, no spaces), or `unknown`
  when it is unset. Nothing sets it today: the deploy module is not built. A failing token check is the fixed 503.
  The unauthenticated `GET /health` answers `{"status":"ok"}` and is unchanged. The admin health route checks the
  process and the database only, no other service.
- `GET /v1/status` - **any signed-in user** (a session or an agent token; no role check, no scope check): which services
  are up, names and booleans only. Built (`status/StatusRoute.kt`, `status/StatusProbe.kt`): 200 with
  `{"services":[{"name":"sync-api","up":true},{"name":"whisper-server","up":false}]}`; 401 without a live token; the
  fixed 503 when the token check fails. sync-api is listed up because it answered. whisper-server is listed only when
  the environment variable `BREAKER_WHISPER_HEALTH_URL` is set (a plain http or https URL, never taken from a request;
  a blank or malformed value stops the start with the usual configuration error); unset means it is not listed, so a
  stack without it never shows "down". The probe is one GET of that URL, 1 second for the connection and 1 second for
  the answer, no redirect followed, the body never read (it names an address) and only HTTP 200 is up; a refusal, a
  timeout, any other status or any exception is `"up": false`, never a 500. The answer is cached for about 10 seconds,
  failures too, and callers who arrive together share one probe, so a user cannot make sync-api hammer whisper-server.
  Clock and probe are injected ports (`StatusProbe`), so the tests need no socket and no sleep; one wiring test reaches
  the route through `syncApiModule` and one runs the real probe against a closed local port. NOT built: the rate limit
  on this route (ADR-041 step c is not on main; the route takes it when that step lands), and the deploy setting (the
  compose service address of whisper-server, and the internal network, are owed by the deploy slice).
- `GET/PUT /v1/admin/transcription-service` — **admin-only**: read/update the
  configured transcription endpoint (name, base URL, optional API key, enabled) (F26).
  Planned, not built: no such route is installed.
- `POST /v1/admin/test-service` — **admin-only**: probe the configured endpoint,
  return reachable/unreachable. URL validation (T20).
  Planned, not built: no such route is installed.
- `POST /v1/admin/agent-tokens` — **admin-only**: create a scoped token (name,
  optional expiry, owner account). `GET /v1/admin/agent-tokens` — **admin-only**: list
  the live agent tokens. `DELETE /v1/admin/agent-tokens/{id}` — revoke (F27).
  **Built**: POST and GET (`AdminCreateAgentTokenRoute.kt`, `AdminListAgentTokensRoute.kt`;
  the store calls are `issueAgent`, `listAgentTokens` and `usernameOf`). Admin means a
  session token of an admin or an admin-scoped agent token. The order of POST: bearer
  token (401 when missing or dead), admin role (403), body size cap (413), body shape
  (400), the owner account (404 when unknown), then the store; GET stops after the admin
  role and reads the store. A store failure on either is the fixed 503, with no
  exception text. POST takes `name`, `owner_username` and an optional `scope` (default
  `transcribe`; `admin` only for an admin owner, else 400) and an optional `expires_at`
  (an instant in the future, else 400). An owner waiting on a reset answers 409. It
  answers 201 with the token value, once. GET answers every live agent token of every
  account, oldest first, each with `id`, `name`, `owner_username`, `scope` and `expires_at`:
  a revoked token never appears, an expired token that was not revoked does, and no
  token value or hash is ever sent.
- `DELETE /v1/admin/sessions/{id}` — **admin-only**: end one session of any account by its id (ADR-036). BUILT.
- Agent auth: `Authorization: Bearer <agent-token>` on `POST
  /v1/audio/transcriptions` + `GET /v1/jobs/{id}` — same FIFO queue, results to
  the owner's namespace. These two routes are served by whisper-server, not by this module: it
  asks this module only whether the token is live. The agent-token request limit (T19) is built
  there, optional and off unless the admin sets it; its rules are on the whisper-server card.
- `POST /v1/admin/reset-account` - BUILT. `{username}`, **admin-only**: an admin session token or
  an admin-scoped agent token, the one exception to the rule that key-bearing routes refuse
  agent tokens. The rules of this route, in order (`admin/AdminResetAccountRoute.kt`,
  `reset/ResetStore.kt`). (1) The caller: no, malformed, unknown, revoked or expired token is 401, a
  failed token check is 503. (2) Not an admin (a user session, or an agent token whose effective
  scope is not admin) is 403 "Admin role required". (3) A body over 4096 bytes is 413. (4) The body
  is exactly one member, `username`, a string (a null, a number, an extra or a repeated member is
  400). (5) A name that is not valid, or that no account has (the lookup uses the lower-case form),
  is 404 `not_found` "Account not found". (6) Then ONE transaction: the verifier hash is replaced
  with 32 fresh `SecureRandom` bytes (so no password can match even if a branch is missed) and
  `reset_pending` is set to 1; `wrapped_dek`, `wrapped_box_privkey`, `box_pubkey` and
  `prev_wrapped_dek` are cleared; `reset_at` is set from the injected clock; `key_version` is kept
  (it never goes backwards, so the next bootstrap sets previous + 1 and a stale pre-reset device's
  pushes are refused); `change_seq` goes up by one and `purged_through` is set to that new value in
  the same statement, so after a reset every earlier cursor of `GET /v1/transcriptions`, also one at
  the old head, is 410 and the client is to do a full resync (decided, not built on the phone), while a
  first page after the reset and its
  `next` are not; every token of the account (sessions and agent tokens) is revoked and its
  unredeemed sign-in codes are deleted; its transcriptions are deleted outright (undecryptable once the
  keys are gone), tombstones too; the wrong-password row is cleared; and any
  older reset code is replaced by the new one. A failure anywhere rolls all of it back and answers
  the fixed 503. The answer is 200 `{"reset_code": "..."}`. An admin may reset their own account;
  that revokes the token they called with, so their next call needs a new sign-in. Pending-sealed
  rows are planned, not built: that table does not exist yet, so the reset deletes nothing from it
  (when it is built, the reset must delete the unfetched rows sealed to the box key that was
  cleared).
  The reset code: 32 random bytes from `SecureRandom`, shown as 43 URL-safe characters (base64url,
  no padding, 256 bits), single use, 24 hours from the injected clock (dead AT the instant), kept
  only as a SHA-256 hash in `reset_codes`. There is ONE live code per account (the table has one row
  per account, and a new reset replaces the old row, so the old code stops working). The row is
  deleted when the code is used, when it expires (`ResetStore.deleteExpired`, called by
  `runHandoffSweep` in the same loop as the hand-off codes, every 60 seconds and at boot), and with
  the account. There is no session cap in this module, so a reset does not touch one. The code
  appears in the 200 answer only and in no log. Whoever calls `POST /v1/auth/complete-reset` first
  with that code chooses the new password on their own device - in the normal flow that is the
  user, but this endpoint cannot itself tell that apart from the admin completing it before handing
  the code over; that risk and its mitigation (DEK/keypair rotation at the account's next password
  change) are ADR-018's to state, not this module's to paper over.
- `POST /v1/admin/clear-store` — **admin-only**: clear transcriptions per-user (username given)
  or for all users (username null or omitted); confirm must be true. Every live row gets a delete
  record (a new change number each, body cleared), so other devices remove the rows through the
  feed; unknown or invalid username 404; answers 200 with the cleared count. For all users one
  transaction per account, so a failure on one account answers 503 and leaves the accounts already
  cleared cleared. The reset path (`deleteAllOn`) is separate and leaves no delete records. BUILT.
- Retention: transcriptions TTL = **3 months** (ADR-010 precise rule); purge job enforces it (F28).
  Ruled in ADR-035; the logic and the schedule are built. `TranscriptionPurge.expireDueOn`
  turns the live rows of one account older than the TTL (`RETENTION_TTL_MS`, 90 x 24 h, by `stored_at_ms`,
  a row exactly at the cutoff is kept) into delete records, oldest first, at most a batch cap per call,
  on the caller's connection inside the caller's transaction, and returns how many it did. Each takes a
  new change number through `markDeleted` (body cleared, deleted set, `stored_at_ms` = now), and
  `PrevKeyRule` runs once for the batch. It never raises `purged_through` (only the floor purge does)
  and never touches a delete record or another account. A TTL or a batch cap below one is refused.
  **Built (server, this module):** `ExpiryScheduler` runs once a day (`START_DELAY` after start, then every
  `INTERVAL` = 24 h, injectable clock so tests run instantly), walks every account from `accountIds()`,
  calls `expireDueOn` with **only** `RETENTION_TTL_MS` as ttl, a named `BATCH_CAP` (500 rows per batch,
  one batch = one transaction), repeats an account while a batch comes back full up to
  `MAX_BATCHES_PER_ACCOUNT_PER_RUN` (10), catches one account's failure and logs only its class name without
  killing the schedule or echoing data. `nowMs` is read from the clock at each batch. It starts inside the existing
  sweep job in `SyncApiMain.kt` beside the token sweep and stops inside `stopSweep`, before the database
  closes, exactly once. Live rows only, by `stored_at_ms`, never raises `purged_through`, never touches a
  delete record or another account. An account with no live session keeps its delete records until a
  session exists and pulls; growth is bounded by that account's own expired rows, one delete record per
  expired row. The job starts one hour after each server start and then every 24 hours; a server
  restarted more often than hourly never runs it; deploys are rare.
  Phone retention tombstones reaching the server stay deferred with the tombstone port.
- **Log policy (F32):** events only, per-user — auth, admin actions, queue
  health. **Never plaintext transcriptions** (T22).
- **Multi-admin config:** concurrent edits to the transcription service config
  are **last-write-wins** (documented default; no locking in v1).

**Planned (ADR-035, ADR-036), partly built:** where an item below says BUILT, that part is in main; every other part is planned, not built.
- The changes-since pull of `GET /v1/transcriptions` is decided; it is BUILT, with a
  cursor. The cursor is a server-assigned change number, unique to the account and strictly
  increasing on every row write (insert, replace and delete). It is sent as an opaque
  string, and it is exclusive: the client asks for the changes after it. The client stores
  it and sends it back as a `since` query parameter on `GET /v1/transcriptions`, and the
  reply carries the next cursor. A delete is a flag on the row in the same feed: the row
  keeps its id, client id and change number, loses its body and is marked deleted. A delete
  record is purged once every live session of the account has pulled past it. A device whose
  session has expired signs in again and does a full resync, so it needs no old delete
  records (the phone's full resync is decided, not built). BUILT (server, ADR-035 section 1D): a list
  longer than one page carries a LIST cursor, so a new device can finish its first list even when
  `purged_through` is above the change of the 100th live row. The routes and the transcriptions table are
  BUILT (the two route bullets above).
  The change number and the delete flag are BUILT in the store (migration 5: every insert and replace takes
  the next number of the account inside the write, and a push of a deleted row is a no-op); the DELETE route,
  the store delete and the resave mark are BUILT; the `since` parameter, the page object, the 400 and the 410
  are BUILT; the write of the pulled number and the purge of delete records are BUILT (a pull with a since
  sets the number; every successful pull purges).
- Sign-in from the website: a start route, where the website creates a one-time code after the person signs in
  (single use, 5 minutes, a new kind in the token store), and an exchange route, where the app trades the code
  and its one-time secret for a session token and the sealed key package. The website page makes the sealed
  package, sealed to the app's public key; the server keeps it until the exchange, which hands it to the app
  and deletes it, never past the code's 5-minute expiry, and never opens it. The store layer under both routes
  is BUILT: migration 4 (the device name column and the sign-in code table), the code store, the 60-second
  sweep that deletes dead codes, and the rule that ending a person's sessions also deletes that person's
  codes. The two routes (`POST /v1/auth/handoff/start`, user session token only, and `POST
  /v1/auth/handoff/exchange`, no token) are BUILT; the app calls the exchange route and stores the received
  package (BUILT on the phone); the website page exists but its derivation, box key and sealing steps are stubs, so it cannot complete a hand-off yet. A hand-off code is never made for a pending account (the start
  answers as for a missing account), and a code found on a pending account at the exchange is spent and
  refused with the one answer.
- Wrong-password limit, on the sign-in path (website and device): after 5 wrong passwords for an account, sign-in for that account waits 15 minutes. It is on by default and the admin can turn it off. The failure counting is BUILT (migration 6, the table signin_attempts, `accounts/SigninAttemptStore.kt`, called by `AccountStore.verify`) and the locked answer is BUILT: `verify` reads the lock first, for a known and an unknown name alike, before the account row and the hash, and the login route answers 423 with Retry-After in whole seconds (counted up to the next second, at least 1) and the body `Too many wrong tries. Try again in N minutes.`; a right password inside the wait is refused the same way and touches nothing; a counting write that fails (the lock read, the count, the clear) never changes the answer. The 5th wrong try itself still answers the plain 401. The admin switch is BUILT on the server (migration 7, the table server_settings, `settings/ServerSettingsStore.kt`, read by `AccountStore.verify` on every attempt, default on; off = no counting, no refusing, no lock read, and the stored attempts are kept; a failed read of the switch counts as on; only a stored integer 0 reads off, any other stored value, even a real number or text, reads on). The admin route that flips it is BUILT: `admin/AdminWrongPwLimitRoute.kt`, GET and PUT /v1/admin/wrong-pw-limit, admin only; a missing or unknown token answers 401, a caller that is not an admin answers 403, and both come before the body is read; the PUT body is exactly one member, enabled, the literal true or false, and anything else answers 400 bad_request with the contract text; the answer is 200 with the stored state read back; a store failure (the resolve, the set, or the read-back) is caught and answered 503 Service Unavailable with the plain body `{"error":"unavailable","message":"The service is unavailable, try again later"}`; the exception text never reaches a body, a log or a message, and a cancellation is rethrown (it is a shutdown, not a store error). The revoke route (`DELETE /v1/admin/agent-tokens/{id}`) answers the same 503 when its resolve, the existence check or the revoke throws. The drop at registration is BUILT: `AccountStore.register` deletes the row left for the folded name inside the same transaction as the insert, after a successful insert only (a refused registration drops nothing). The clear hook is BUILT: `AccountStore.clearAttempts` deletes the row of a name for the admin reset to call (a malformed name touches nothing, a database error is not swallowed, no token is touched, so a signed-in session is not affected). The password change clears the row inside its own transaction through `SigninAttemptStore.clearOn`, which runs the same delete on the caller's connection. The sweep is BUILT: `SigninAttemptStore.deleteStale` deletes expired locks and partial counts with no wrong try for more than 15 minutes, and `accounts/SigninAttemptSweep.kt` runs it at boot and then every 5 minutes inside the one sweep job that `SyncApiMain` starts with the application and stops before the database closes; a failed sweep is swallowed and never touches a sign-in, and the attempt-time rule still decides. The password change is BUILT (the route above). The admin reset is BUILT: it clears the row of the reset name inside its own transaction through `SigninAttemptStore.clearOn`, as the completion of a reset does. It limits online guesses only and does not slow an attacker who holds a stolen verifier hash (see the honest limit below).
- Rate limits on register and salt (ADR-041 step c, BUILT): `POST /v1/auth/register` allows 10 requests per source address and 120 from all addresses together in a fixed 60 second window, and `POST /v1/auth/salt` allows 60 per address and 1200 together. The address is the socket peer; `X-Forwarded-For` is ignored until the trusted-proxy setting exists (a later step). The counters are rows of `rate_limit_counters` (migration 11, key route + address, the global row is the address `*`), kept by `accounts/RateLimitCounterStore.kt` in one transaction per request: a refused request counts nothing, a full table (10,000 rows) answers a new address without counting it and never refuses it, there is no oldest-first eviction (the sweep, `RateLimitCounterSweep.kt`, deletes expired windows every minute, so a full table of live windows is a flood), and an address already in the table still counts. The routes take the counter store as an optional parameter (the module passes the real one; a test of one route alone may leave it out). The check runs first in the route, before the body is read and before any hash or lookup, the same for a known and an unknown name. A refusal is 429 with `Retry-After` (whole seconds, 1 to 3600) and a fixed body that holds no number, key or address. If the counter store fails, or the switch cannot be read, the request goes on as if there were no limit and nothing is counted (a store error never blocks a user, ADR-041); a cancellation is never swallowed. The rule is `enforceLimits` over three small ports (the switch read, the source address, the counter check) so a test can play every failure. A source pin (`SyncApiMainWiringTest`) keeps `main` launching the counter sweep; the sweep itself is tested on its own. One admin switch for all of them, `GET` and `PUT /v1/admin/rate-limits` (setting `rate_limits_enabled`, default on, read live), mirrors the wrong-password switch. The numbers are constants, not settings. Complete-reset and `POST /v1/sync` are NOT limited yet (later steps); T18 stays planned until they are.
- Key upload and the stored key columns are BUILT: migration 8 (`db/SchemaV8.kt`) adds six nullable columns to `accounts` by ADD COLUMN, with no rebuild of the table: `wrapped_dek`, `wrapped_box_privkey`, `box_pubkey`, `prev_wrapped_dek`, `rekeyed_at` and `reset_at`. NULL means keyless. CHECK constraints pin the lengths (`wrapped_dek` 60, `wrapped_box_privkey` 60, `box_pubkey` 32 and `prev_wrapped_dek` 60 bytes), and one more CHECK requires the three key columns to be all set or all NULL. `rekeyed_at` and `reset_at` are stored as epoch milliseconds. With it come the route `POST /v1/auth/keys` (above; `AccountStore.storeKeys` writes the three values and raises `key_version` by one in one transaction) and a change to login: login answers the stored values read by `AccountStore.keysFor` (the key fields as the canonical padded base64 of the stored bytes, `key_version` as the stored counter, `reset_at` and `rekeyed_at` as ISO-8601 UTC strings made from the stored epoch milliseconds, always with exactly three fractional digits by `formatInstantMs` in `transcriptions/SyncTimeFormat.kt`, so a whole second prints `.000`); a keyless account still gets the key fields null and `key_version` 0. The password change (`POST /v1/auth/change-password`, above) writes `prev_wrapped_dek` (as sent) and `rekeyed_at`, and the previous-key rule clears `prev_wrapped_dek` again; `reset_at` is written by the admin reset. Migration 9 (`db/SchemaV9.kt`) is BUILT: it adds `accounts.reset_pending` (INTEGER NOT NULL DEFAULT 0, CHECK in 0 or 1) by ADD COLUMN, with no rebuild of the table (other tables hold foreign keys to accounts that would cascade), and the table `reset_codes` (one row per account: `account_id` primary key referencing accounts with ON DELETE CASCADE, `code_hash` a blob of exactly 32 bytes, `issued_at_ms`, `expires_at_ms` above it) with an index on `expires_at_ms`. The database `user_version` is 9. The admin reset, the reset codes and the reset-pending mark of an account are BUILT (the two routes above).
- An admin route to end a session at once is BUILT on the server (ADR-036): `DELETE /v1/admin/sessions/{id}`,
  admin only, 204 with no body. An admin session and an agent token whose effective scope is admin are both
  accepted; a non-admin session or a non-admin-scope agent token answers 403, and a missing or dead token
  answers 401 - the auth is resolved and tested before the id is read, so a non-admin with a garbage id gets
  403, not 404. The id is decimal digits only; a bad id and any id that is not a live session of any account
  (unknown, an agent token row, already revoked, expired, past the 180-day cap) all answer one fixed 404
  carrying no part of the id. The store work is one transaction (`TokenStore.revokeSessionById` reads the
  row's account_id with an id-only SELECT, then runs the existing `REVOKE_DEVICE_SQL` bound to that account
  and the id; the UPDATE carries the whole liveness rule), so a check and a revoke cannot be split; the
  account's hand-off codes are left alone and agent
  tokens are never revoked by this route. A store failure answers the fixed 503 and a cancellation is
  rethrown, never answered.
- A device list for an account, BUILT on the server: `GET /v1/auth/devices` lists the caller's live sessions, oldest first, at most 50 rows; each row holds `id`, a nullable `name`, `last_seen` in the feed time form and `current` (true only for the presented token). There is no devices table. Sign-out for each device and "sign out all my devices" are BUILT on the server as the two user routes `DELETE /v1/auth/devices/{id}` and `POST /v1/auth/devices/sign-out-all`, both 204 with no body, bearer user session token only, an agent token refused 403, a missing or dead token 401 and a store failure the fixed 503. The one-device route proves the id in one UPDATE that carries the account, the kind and the liveness rule, so another account's id, an agent token id, an already revoked, expired or past-the-cap id all answer one fixed 404 with no part of the id in the body; the account's hand-off codes are left alone. The sign-out-all route uses `revokeOtherSessionsOn` with a keep id that cannot be a live session, so it revokes every live session of the account including the caller's and deletes the account's hand-off codes; agent tokens are never touched by either route. The website page that calls them, and the app's sign-out call, are not built.

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
checked against the floor and the ceiling, and the version against 1, first, and
the verifier checked to be exactly 32 bytes before it is hashed) — never a
password, never a DEK, never a KEK. Login receives the auth verifier, checks it against the stored hash,
and returns the account's wrapped keys, its current `key_version`, and
`reset_at`/`rekeyed_at` unchanged (this module never unwraps the keys or
computes a version itself — it advances `key_version` only when it accepts a
bootstrap (`/v1/auth/keys`) or a `change-password`, by exactly one, and
never lowers it). Transcriptions from an account that has
completed its bootstrap are meant to be stored as **ciphertext only** (T17: this module stores
any standard-base64 string the client sends, and no client completes the bootstrap yet); a Phase
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
strength of the scheme, and the wrong-password wait on sign-in limits online
guesses only and cannot raise that cost (auth hardening is explicitly out of
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
account's old transcriptions outright (and its pending-sealed rows) rather than
pretending to recover them, and bounds — rather than eliminates — whoever
completes it by rotating the DEK at the account's next password change
(R20, ADR-018).

**Build phase:** Phase 11 (keys and re-key in Phase 19; agent tokens, reset and pending-sealed in Phase 20). Needs first: `api-contracts`.

## Invariants
- First registered account is admin; second is user (F22).
- Admin can promote/demote; users cannot self-promote (T16).
  Built: `POST /v1/admin/roles` (see its entry above).
- User A cannot read user B's transcriptions (N13 test).
- Sync retries don't duplicate (N12 test).
- `/v1/updates/latest.json` answers from the manifest file only: no path or no
  file is 404, an unusable file is 503, a valid file is 200 with the six fields
  rebuilt (test: UpdatesRouteTest).
- `POST /v1/auth/keys` rejects a second call once an account has keys — a
  keyless account can only get keys once per reset (ADR-006, ADR-018).
- A reset leaves an account fully keyless and token-less until
  `/v1/auth/complete-reset` runs; login fails cleanly in between (the 401 of a wrong
  verifier, counted like any wrong try), never partially, and `keys` and `change-password` answer 409.
- After a reset every earlier feed cursor, also one at the old head, is 410; the next push of the
  account takes a change number above `purged_through`.
- A reset also clears the account's pending-sealed rows in its own transaction, so
  `GET /v1/sync/pending-sealed` never returns a row sealed to a box key the reset just deleted; an ordinary
  re-key (`change-password`) clears the same way for whatever `box_pubkey` it just replaced, and the first
  key store after a reset (`POST /v1/auth/keys`, only when it stores keys) clears too. A refused call clears nothing.
- An account has at most one live reset code; the code is stored only as a SHA-256 hash, is single
  use, and is dead at its expiry instant (24 hours after it was made).
- `register`, `change-password` and `complete-reset` never accept
  `kdf_params` below the ADR-006 floor or above its ceiling, or a
  `kdf_version` other than 1 (test: submit a weaker set, a heavier one and
  version 2, confirm the 4xx), and no endpoint hashes an
  auth verifier that is not exactly 32 bytes (test: submit 31 and 33 bytes,
  confirm the 4xx).
- `change-password` always advances `key_version` by exactly one and never
  accepts a same-or-lower `new_key_version` (test: replay an old value,
  confirm the 4xx) — there is no call shape that re-wraps without advancing
  it.
- `POST /v1/sync` never treats a higher-`key_version` push at an existing
  `client_id` as a duplicate, and never treats a lower one as an update — the
  first replaces, the second is refused (test: push both orders, confirm the
  replace and the 409).
- A push whose key_version is above the account's current key_version is refused with 409
  `conflict` (ADR-006: only the server sets it). BUILT: the check runs before the row
  lookup, so such a push never replaces a stored row.
- A push body over 1 MiB (1,048,576 bytes) is refused with 413 `payload_too_large` before it
  is parsed, and the exact cap is accepted. BUILT.
- A repeat of the same pair is a no-op that never overwrites the stored text, and a higher
  key_version replaces the one row of its client id in place, so the list keeps its place.
  BUILT.
- `GET /v1/transcriptions` returns only the rows of the caller's own account, in change order.
  BUILT.
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
- The server stores only the SHA-256 of a token's 43-character text, never the
  token itself, and a token never appears in any log. Looking a token up is an
  indexed equality on that hash (test: issue a token, search the database file
  for its text and confirm it is not there; resolve the token and confirm the
  row is found through its hash).
- A session token expires 30 days after its last use, and no session outlives 180 days from its
  creation: login stores an expiry of now + 30 days, and every resolve of a live session token
  moves its expiry to the lesser of now + 30 days and creation + 180 days (the slide is clamped
  at the cap, so a session that keeps being used still dies at 180 days). The live-check is
  scoped to sessions: an agent token past 180 days stays live (its expiry is set at issue and
  is never slid), and a session whose expiry is past its creation + 180 days is refused. A
  session row with no expiry gets one at its next use. A token past its
  expiry is refused with the normal 401 and stays refused, also after a later
  try. An agent token has an optional expiry of its own, is dead at its expiry
  instant, not after it, and is never moved by a use (test: issue an agent token
  that expires in the future, resolve it just before and exactly at that instant,
  expect found and then no; resolve a session token on day 29 and expect found
  with the expiry moved, on day 30 unused and expect refused).
- A token never carries more than its owner's role (ADR-009). Minting an
  admin-scoped agent token for an owner who is not an admin is refused. When a
  token is resolved, the effective scope is `transcribe` whenever the owner is
  no longer an admin, even if the stored scope is `admin`; routes must use the
  effective scope, not the stored one (test: mint an admin-scoped token for an
  admin, demote the owner, resolve, expect stored `admin` and effective
  `transcribe`; mint one for a plain user, expect the refusal).
- Revocation: `change-password` revokes every OTHER live session token of that
  account, keeping the one that presented it, and never touches agent tokens.
  A reset revokes every live token of the account, of both kinds. Deleting an
  agent token revokes only that agent token. Deleting an account removes its
  tokens (test: give an account two sessions and an agent token, keep one
  session and expect the other session revoked and the agent token still
  live; revoke all and expect none live; revoke one agent token and expect
  the rest live; delete the account and expect its tokens gone).
- An unknown, malformed, expired or revoked token all resolve to the same
  plain "no": nothing tells the caller which of the four it was (test: resolve
  one of each, expect the same empty answer every time).

### Internal listener and service key

Where it listens. sync-api runs two listeners. The public listener is set by BREAKER_SYNC_API_HOST and BREAKER_SYNC_API_PORT and carries the routes listed above. The internal listener is set by BREAKER_SYNC_API_INTERNAL_HOST and BREAKER_SYNC_API_INTERNAL_PORT and carries only the two internal token-check routes, POST /internal/v1/token/use and POST /internal/v1/token/peek: no health route, no account route and no admin route. whisper-server is its only caller. On shutdown the public engine stops first, then the internal engine, then the database closes exactly once; any token-store failure on the internal routes answers 503 {"error":"unavailable"} without exposing the exception text, and the service key is still checked first (wrong key stays 403).

Settings. The three new settings are read like the public ones: a set but blank value is refused, and the refusal names the variable and never a value. BREAKER_SYNC_API_INTERNAL_HOST defaults to 127.0.0.1, and the code does not check that it is a loopback or stack-internal address. Every wildcard spelling is refused: after trimming and lower-casing, a text made only of the characters 0 : . [ ] is a wildcard (for example 0.0.0.0, ::, [::], ::0 or 0). The internal host is not checked against any VPN address, because the server cannot know it: the operator gives a loopback or stack-internal address. BREAKER_SYNC_API_INTERNAL_PORT defaults to 8081 and is a whole number from 1 to 65535, ASCII digits only. BREAKER_SYNC_API_SERVICE_KEY_FILE defaults to a file named service-key next to the database file; a bare database file name puts it in the current folder. An explicit value of BREAKER_SYNC_API_SERVICE_KEY_FILE or of BREAKER_SYNC_API_DB that the file system rejects as a path text (for example one with a NUL character) is refused; the refusal names that variable only and copies neither the text nor the system message.

Collision rule. The start is refused when the internal port equals the public port and either the two hosts are equal after trimming and lower-casing, or the public host is a wildcard (the same test as for the internal host), or both hosts are loopback spellings (127.0.0.1, localhost, ::1 or [::1]). Different ports never collide. The refusal names BREAKER_SYNC_API_INTERNAL_PORT and BREAKER_SYNC_API_PORT and no values.

Key file. On the first start, if the key file does not exist, sync-api makes a key from 32 random bytes, written as 43 characters of base64url without padding. It writes the key to a temporary file in the same folder, created owner-only, then makes the key file a hard link to that temporary file, so the key file appears complete with owner-only mode in one step; the temporary file is then removed. If two starts race, the start whose link fails reads the other start's file. An existing key file is read only if it is at most 44 bytes, readable and writable by its owner only, and its text is exactly 43 characters of the URL-safe alphabet with at most one trailing newline. A key file that does not meet these rules, a file system with no POSIX permissions, a missing folder and a folder that does not allow links are refusals. Every refusal names the path, never the key text, and the folder is not created. The key text never appears in an exception message, a toString or a log line.

Rotation. Delete the key file, delete any file named service-key-*.tmp in the same folder, and start again; the next start makes a new key. After a crash during the very first start, such a temporary file (owner-only) can remain and hold the key text, which is why rotation removes it too. A leftover temporary file never blocks a start, and a start does not remove it.

Transport. Breaker serves plain HTTP on both listeners. TLS is the admin's choice through a reverse proxy in front of it (ADR-034).

Start failures. If the internal listener cannot start (for example, its port is taken), the start prints one line, sync-api: the internal listener could not start, closes the database and exits with status 1 before the public listener starts. This has not been run against a taken port: if the engine reports a failed bind only after it has started, the process keeps running without the internal listener, so check that the internal port answers. A key file refusal prints its message, closes the database and exits with status 2. When the public listener stops, the internal listener stops too.

Still open. How the key reaches whisper-server is the deploy step's job. whisper-server takes the key from its own setting BREAKER_WHISPER_SYNC_API_SERVICE_KEY.

## Owns
Auth (users, roles, agent tokens), sync, updates, retention, store clear, log policy.

## Public Interface
The HTTP surface. `syncApiModule` installs the public routes: `/v1/auth/*`, `/v1/sync`, `/v1/transcriptions` and `/v1/admin/*`. `internalModule` installs the service-key routes `/internal/v1/token/*` on the internal listener.

## Depends On
- server (registered in modules.toml)
- shared_api_contracts (registered in modules.toml)

## Does Not Own
- Transcription (whisper-server)
- UI (web-fe)
- TLS and certificates: Breaker serves plain HTTP; an admin who wants https puts a reverse proxy in front (ADR-034)

## Test Locations
- Unit (Kotlin/Ktor, ADR-017): `server/modules/sync-api/src/test/kotlin/`. Run: `./gradlew :server:modules:sync-api:test`. The entry point `main` is not covered by unit tests, because it needs a real socket; every function it calls is. New test class `servicecheck/ServiceCheckCancellationTest.kt` calls `serve` directly (it was moved to an internal top-level function in `ServiceCheckRoutes.kt`) and proves cancellation is not answered 503.
- Username vectors: `UsernameVectorData.kt` is the literal copy of `shared/modules/api-contracts/examples/username_vectors.json`; `UsernameVectorsTest.kt` runs every row through `UsernameRules.isValid`, and the python test `tests/unit/shared/api-contracts/test_username_vectors.py` checks that the copy and the json agree.
- Body cap: `AuthBodyLimitTest.kt` sends each of the three open routes a body one byte over 4096 (refused with `413` and the fixed error, nothing stored) and a body of exactly 4096 (not refused), a chunked body with no declared length over the cap (refused), and pins the cap at 4096.
- Session expiry: `TokenStoreSessionExpiryTest.kt` (a new session stores now + 30 days; a session used on day 29 stays valid and moves forward; unused for 30 days is refused, one second earlier is not; an expired session stays refused after a later try; each use pushes the date out; a session with no expiry gets one at its next use; an agent token is not moved by a use), with `TokenStoreResolveTest.kt` (a session used every day still resolves after ten years) and `TokenStoreIssueTest.kt` (the stored expiry).
- Internal token check: `tokens/TokenStorePeekTest.kt` (peek gives resolve's answer for a live session and writes nothing; it refuses expired, revoked, unknown and malformed tokens) `servicecheck/ServiceKeyTest.kt` (a blank, empty or whitespace-only key cannot be built; a missing or blank header, a longer, shorter or different key never matches) and `servicecheck/ServiceCheckRoutesTest.kt` (the key is checked first; every not-live cause gives the same 401; the 200 body; use moves a session expiry and peek does not; an agent token is never moved; neither the key nor the token reaches the output or a body; an oversized token is refused). New test class `servicecheck/ServiceCheckCancellationTest.kt` calls internal top-level `serve` directly and proves cancellation propagates instead of becoming 503.
- Internal listener and key file: `SyncApiConfigInternalTest.kt` (the internal settings and their defaults; the wildcard family refused for the internal host, with and without surrounding spaces; the public wildcard family and the loopback spellings colliding with the internal listener on one port; equal host names colliding; a named public host and a loopback internal host accepted; a NUL character in the database path or the key file path refused with a fresh message), `servicecheck/ServiceKeyFileTest.kt` (a new key with owner-only mode and no newline; the second start returns the same text without rewriting; malformed, too large, group or other readable, and missing-folder refusals name the path and never the key text; keys with underscores or a capital Z accepted; characters just outside the alphabet refused), `servicecheck/ServiceKeyFileRaceTest.kt` (a start that loses the race returns the winner's text, and a winner readable by its group is refused and its text is never returned; no temporary file is left; a leftover temporary file does not block a start; a failed or unsupported link is refused; the key text is in no refusal message), `servicecheck/ServiceCheckSourceScanTest.kt` (no servicecheck source file prints, writes to a system stream, logs or prints a stack trace; the scan reports each token in a text that holds it), `InternalModuleTest.kt` (the two routes with the right key; 403 for a wrong or missing key; 401 for no token; a GET is not 200; the health, auth and admin paths are 404 on the internal listener, and the public listener answers them, as the controls show) and `PublicModuleHasNoInternalRoutesTest.kt` (the two internal routes are 404 on the public listener, with the internal listener as the control). `main` is not covered by unit tests, because it needs sockets.
- Transcriptions (BUILT): under `db/`, `SchemaV3MigrationTest.kt` (a fresh database ends at
  version 3 with the transcriptions table and key_version 0 for a new account; an upgrade
  from version 2 keeps the account at key_version 0). Under `transcriptions/`:
  `TranscriptionStoreTest.kt` (a new client id; the same pair as a no-op; the same pair with
  other text as a no-op; a higher key_version as one replaced row; a lower key_version
  refused; an above-account push refused before the row lookup; a new client id below the
  account refused; two accounts with one client id do not touch each other; a replace keeps
  the list place; an account with no rows lists nothing; a push for a missing account
  throws); `TranscriptionConcurrencyTest.kt` (two concurrent pushes of one pair leave one
  row); `SyncRoutesPushTest.kt` (204 for a first and for a repeat push; 413 one byte over
  the cap, with a declared length or chunked, and 204 at the cap; a text holding a slash and
  a text with two padding characters are stored and listed; 400 for each validation failure
  with its fixed message; 409 above the account; 409 for a lower version of a stored client
  id; the largest key_version reaches the account check); `SyncRoutesListTest.kt` (an empty
  list; the row shape with the injected clock time; oldest first; one account never sees
  another account's rows; 503 for a stored time that cannot be formatted;
  renderedPageEqualsPullPageFixture renders the route page and asserts body equals
  PullPageFixture.PAGE exactly);
  `SyncRoutesAuthTest.kt` (401 with no token, an unknown, malformed, revoked or expired
  session token, or a Basic scheme with a live token; 403 for an agent token on both routes;
  401 for a revoked agent token; a session token as the control);
  `SyncRoutesClosedDatabaseTest.kt` (503 with the fixed body on a closed database, on both
  routes); `SyncRoutesTokenStoreClosedTest.kt` (503 with the fixed body when the token store
  is closed, on both routes); `SyncApiModuleRoutesTest.kt` (the public module mounts the
  push and the list); `SyncTimeFormatTest.kt` (the stored time format, the leap day, dropped
  milliseconds). Test-only helpers: `SyncRouteTestSupport.kt` and `TranscriptionFixtures.kt`
  (the fixtures set accounts.key_version by SQL, because the store never does).
- Sign-in hand-off (BUILT): under `db/`, `SchemaV4MigrationTest.kt` (a fresh database ends at the latest version with the sign-in code table, its index, the device name column and both triggers; an upgrade from version 3 keeps every row; the length and key checks of the table; a device name only on a session row). Under `handoff/`: `HandoffStoreStartTest.kt` (a started code and its stored hash; the created and expiry times; the cap of five live codes and which one is displaced; expired rows gone at the next start; an unknown account), `HandoffStoreRedeemTest.kt` (the session and package returned; a second redeem, a wrong secret, an expired code and an unknown code all give the one refusal; a code is dead at its expiry instant), `HandoffStoreRedeemGuardTest.kt` (a device name of 0 or 65 characters is refused before any change), `HandoffConcurrencyTest.kt` (two redeems of one code at once give one session), `HandoffSweepTest.kt` (the boot sweep, the 60-second interval, a failing sweep is swallowed),
`HandoffSweepLogTest.kt` (a failing sweep is logged at WARN with the exception class name only,
never the message, which can carry a path or a key; a cancellation is never swallowed). Under `tokens/`: `HandoffRevokeTest.kt` (ending an account's sessions deletes its codes and no other account's). `ShutdownOrderTest.kt` pins that the sweep stops after the internal engine and before the database closes. Under `auth/`: `AuthHandoffStartRouteTest.kt` (201 with a code; the fixed 401 and 403 bodies; a 400 for each fault of the body; a non-canonical but pattern-valid package is accepted; 413 over 4096 bytes), `AuthHandoffExchangeRouteTest.kt` (the 200 answer with the account's key_version; the device name rules; the one 401 for every refusal; a wrong secret leaves the code live), `AuthHandoffExchangeMoreTest.kt` (two exchanges at once give one session; the device name is stored; a package comes back as the canonical text of the stored bytes; a second account gets the role user; a code used as a Bearer token gets the 401; the issued session token works), `AuthHandoffExchangeCapTest.kt` (the 4096-byte cap, declared and chunked, on both routes), `AuthHandoffBodyShapeTest.kt` (malformed bodies are a 400 on both routes). Test-only helpers: `HandoffFixtures.kt`, `HandoffRouteTestBase.kt`, `HandoffReply.kt`.
- Change numbers (BUILT): under `db/`, `SchemaV5MigrationTest.kt` (a fresh database ends at the latest version with the change number columns, the delete flag, the pulled-change column and the unique index, and refuses a negative counter or a delete flag of 2; the unique index refuses a repeated number of one account and accepts it on another; an upgrade from version 4 keeps every row and numbers each account's rows 1 to N in id order). Under `transcriptions/`, `TranscriptionStoreChangeTest.kt` (new client ids take 1, 2, 3 per account, a replace takes the next number, a no-op or a refusal takes none, a push of a deleted row is a no-op), `TranscriptionStoreChangeOrderTest.kt` (the order of refusals; a failed insert after the counter bump rolls the counter back), `TranscriptionStoreChangeConcurrencyTest.kt` (two pushes at once get two different consecutive numbers), `TranscriptionStoreDeleteTest.kt` (delete takes the next number, repeat/unknown takes none, the mark reanimates), `SyncRoutesDeleteRouteTest.kt` (DELETE answers 204/400/401/403/503), `SyncPushBodyResaveTest.kt` (resave parsing), `SyncRoutesPushResaveTest.kt` (resave on the route), `TranscriptionStorePageTest.kt` (the page of the feed: live rows only without since, delete records, the 100-record and size limits, next and more, Gone), `SyncRoutesFeedTest.kt` (the feed routes with the store), `SyncRoutesFeedRouteTest.kt` (the exact page text, the 400, 401, 403, 410 and 503 answers) and `SyncRoutesListTest.kt` (the list answers in the page shape).
- Pulled number and purge of delete records (BUILT): under `tokens/`, `TokenStorePullNumberTest.kt` (a pull
  stores the smaller of since and head, and only for a session of its own account),
  `TokenStorePullFloorTest.kt` (the floor is the lowest stored number of the live user sessions, and none when
  no session is live or one has no number), `TokenStorePullFloorPinTest.kt` (the floor's live rule agrees with
  the login rule for the fixture sessions); under `transcriptions/`, `TranscriptionPurgeRecordsTest.kt` (the
  purge removes delete records at or below the floor, raises purged_through to the highest one removed and
  touches no live row), `TranscriptionStorePullTest.kt` (a pull sets the number and purges in one transaction,
  a Gone pull writes nothing, and a failing purge rolls the number back), `SyncRoutesPullPurgeTest.kt`
  (through the route, a delete record goes once every live session has passed it, and a cursor below the new
  floor answers 410), `SyncRoutesPullGuardTest.kt` (a bad or foreign cursor, a 410, an agent token and a
  failing purge write nothing, and a failing purge answers 503 and leaves the number and the rows unchanged),
  and `SyncApiPullEndToEndTest.kt` (the whole module with real sign-ins: a delete record goes once every
  session has passed it, and an old cursor answers 410 after a lagging session signs out).
- Wrong-password counting and the locked answer (BUILT): under `db/`, `SchemaV6MigrationTest.kt` (a fresh database ends at the latest version with the signin_attempts table and refuses a negative count, a negative lock, a negative last attempt and a name that is upper case, empty or 65 characters long; an upgrade from version 5 keeps every row and adds an empty table). Under `accounts/`, `SigninAttemptStoreTest.kt` (the count, the lock at the fifth wrong try, a try inside a lock, an expired lock, the 15-minute expiry of a partial count and its exact edge, the row cap with its eviction order, and the counting through verify) `SigninAttemptStoreLockTest.kt` (the lock read, and the seconds left rounded up, at least 1), `AccountStoreLockedTest.kt` (the 6th try is Locked, a right password inside the wait is Locked and touches nothing, a locked unknown name gets the same answer, the count and the clear fail open when the table is gone), `AuthLoginRouteLockedTest.kt` (423 with the header and the body, equal for a known and an unknown name, 200 after the wait, 899 seconds say 15 minutes) and `AccountStoreCountingTest.kt` (an unknown name and a wrong verifier count, a right password clears the row, a malformed name touches no row). The switch (BUILT): `db/SchemaV7MigrationTest.kt` (a fresh database ends at the latest version with an empty server_settings table and refuses an empty key and a 65-character key; an upgrade from version 6 keeps every row), `settings/ServerSettingsStoreTest.kt` (an absent row reads on, only a stored integer 0 reads off, a wide value, a real number, text and empty text read on, the read is live) and `accounts/AccountStoreSwitchTest.kt` (off: no counting, no lock read, a locked name is let in at once and its row is kept; back on: locked again; a failed read of the switch counts as on). The switch route (BUILT): under `admin/`, `AdminWrongPwLimitRouteTest.kt` (401 without a token, with a bad header or with an unknown token, 403 for a plain user and for a non-admin agent token, GET answers the stored state, PUT false and true are read back, the content type is JSON), `AdminWrongPwLimitBodyTest.kt` (every bad body answers 400 with the exact Error body and leaves the switch alone, whitespace is accepted, a repeated member and trailing text are refused) and `AdminWrongPwLimitFlowTest.kt` (through the whole application: the route is wired, five wrong tries lock, turning the limit off lets the name in and keeps its row, turning it on locks it again, with the limit off no row is written). The drop, the hook and the sweep (BUILT): under `accounts/`, `AccountStoreRegisterDropTest.kt` (a locked or partly counted name is clean after its registration and signs in at once, capital letters fold, a taken name and every refused registration keep the row, other names are untouched), `AccountStoreClearAttemptsTest.kt` (a locked account signs in at once after the clear, the name is folded, a malformed name and a name without a row touch nothing, a session issued before the clear still resolves), `SigninAttemptStoreSweepTest.kt` (deleteStale at the edges: exactly 15 minutes kept and one millisecond more deleted, a lock ending exactly now deleted and one millisecond later kept, a live lock kept, the count returned) and `SigninAttemptSweepTest.kt` (a pass at once, a pass after every pause, a failed pass swallowed, a cancellation ends the loop, the interval is five minutes).
- Key upload and stored keys (BUILT): under `db/`, `SchemaV8MigrationTest.kt` (a fresh database ends at version 8 with the six key columns, all NULL and key_version 0 on a new account; an upgrade from version 7 keeps the account and a token row and gives NULL key columns; each length check refuses one byte less, one byte more and an empty blob and accepts the exact size; a text value of the right length is refused; the three key columns refuse each partial combination and accept all set and all NULL; rekeyed_at and reset_at refuse minus 1). Under `accounts/`, `AccountStoreKeysTest.kt` (storeKeys returns the new key_version and writes the bytes, a second call is AlreadyKeyed and changes nothing, an unknown id is NoSuchAccount, a preset key_version is raised by one, a second account is untouched, a forced failure with a test-only trigger leaves everything as it was and the call works after the trigger is dropped; keysFor for a keyless, a keyed and an unknown account, with copies of the bytes; KeyBundle refuses a wrong size and prints no value). Under `auth/`, `AuthKeysRouteTestBase.kt` (test-only helper: the byte patterns, the request and the column reader), `AuthKeysRouteTest.kt` (200 with the exact body and the stored bytes; a second call 409 with the exact body; an agent token 403 with the body of POST /v1/sync, also for a malformed or an oversized body; 401 for no token, an unknown, a revoked or a malformed header; a preset key_version raised by one; a second account untouched; JSON content type), `AuthKeysRouteBodyTest.kt` (400 for a wrong decoded length of each member, text that is not base64, url-safe characters, whitespace, a wrong padding, malformed JSON, a missing, extra, duplicate or non-string member; 413 over 4096 bytes and 200 at exactly 4096, declared and chunked; 400 or 413 before 409 for a keyed account), `AuthKeysRouteSafetyTest.kt` (503 with the fixed body on a forced store failure and 200 after the trigger is dropped; no output or answer holds a key; a source scan of the route and the key types, proven on a dirty text) and `AuthLoginRouteKeysTest.kt` (login answers the stored values as standard padded base64, key_version and the instants; a keyless account answers the nulls and 0; two accounts get their own keys; a wrong verifier gets 401 without key text; the nine member names in the documented order). The tests that pin the latest schema version now expect 8: `MigrationsTest.kt`, `SchemaV2MigrationTest.kt`, `SchemaV2Test.kt` and `SchemaV3MigrationTest.kt` to `SchemaV7MigrationTest.kt` (the digit 7 became 8 on 14 lines in 8 files, and two test names were reworded: the test about a fresh file database in `SchemaV2Test.kt` and the first test in `SchemaV7MigrationTest.kt`).
- Password change and the previous-key rule (BUILT): under `accounts/`, `PrevKeyRuleTest.kt` (the rule clears `prev_wrapped_dek` when the account has no stored rows; keeps it while a not-deleted row below the `key_version` remains; clears it after a push that replaces the last old row and after a delete of it; keeps it when one of two old rows is replaced; a deleted old row does not hold it; a keyless account and a second account are untouched). Under `passwordchange/`, `PasswordChangeStoreTestBase.kt` (test-only helper), `PasswordChangeStoreTest.kt` (a change writes every column as sent with `rekeyed_at`; other sessions are revoked while this one and the agent token stay; the account's hand-off codes are deleted and a second account keeps its code; the wrong-password row is cleared; the old verifier stops verifying and the new one verifies), `PasswordChangeStoreRefusalTest.kt` (a revoked, expired, foreign or agent session, or a missing account, is not live; no keys; a wrong `key_version`; a held `prev_wrapped_dek`; the order when two causes apply; nothing is written), `PasswordChangeStoreAtomicTest.kt` (a forced error after the account update and one at the last write leave every table as it was; two changes with one `key_version` leave one request in the columns) and `PasswordChangeRequestTest.kt` (each wrong size is refused with a message that names the field and holds no value; the copies; the fixed printed text). Under `tokens/`, `TokenStoreSessionLiveTest.kt` (the live-session check is true for a live session and false for a revoked, expired, agent or foreign one, and does not move the expiry). Under `transcriptions/`, `SyncTimeFormatInstantTest.kt` (a whole second prints `.000`, milliseconds print three digits, a small remainder is padded, a negative value is refused, `formatStoredAt` is unchanged). Under `auth/`, `AuthChangePasswordRouteTestBase.kt` (test-only helper), `AuthChangePasswordRouteTest.kt` (200 with the exact body and every column; the old verifier no longer logs in and the new one does; sessions, agent token and hand-off codes; the wrong-password row; the body cap, declared and chunked), `AuthChangePasswordRouteBodyTest.kt` (400 for each byte member wrong by one byte with a valid text length, url-safe or unpadded text, KDF values outside the bounds, and each fault of the strict reader), `AuthChangePasswordRouteAuthTest.kt` (403 for an agent token before the body is read; 401 for no, unknown, revoked or expired tokens; 401 "Invalid old verifier" counted; the sixth request locked with 423 even with the right verifier; the 409 causes and their order), `AuthChangePasswordRouteSafetyTest.kt` (503 with the fixed body when the transaction fails, with the old keys, verifier and tokens kept; no output or answer holds a key or a verifier; a source scan of the new files, proven on a dirty text), `AuthKeysRouteExpiryTest.kt` (an expired session gets 401 on `POST /v1/auth/keys`) and `AuthLoginRouteFailureTest.kt` (a forced failure in verify, in keysFor and in issueSession each gets the exact 503 body and no token row, and login works again after the fault is removed; whole-second and millisecond instants print three digits). One existing line changed in `AuthLoginRouteKeysTest.kt` (line 116): the expected `rekeyed_at` text is now `2023-11-14T22:13:20.000Z`, so a whole second prints `.000`.
- Admin reset and reset completion (BUILT): under `db/`, `SchemaV9MigrationTest.kt` (a fresh database has
  `reset_pending`, `reset_codes` and its index; an upgrade from version 8 keeps the
  account and gives `reset_pending` 0; the CHECK constraints on the mark, the hash length and type, and the
  times; one code row per account; the row goes with the account). Under `reset/`, `ResetStoreTestBase.kt`
  (test-only helper), `ResetStoreResetTest.kt` (every effect of a reset, `key_version` kept, `change_seq` plus
  one with `purged_through` equal to it, the hand-off codes deleted, a second reset replaces the code, the
  admin's own tokens die, two accounts, capital letters in the name), `ResetStoreAtomicTest.kt` (a forced
  error on the code insert or on the clear of the wrong-password row leaves every table as it was),
  `ResetStoreCompleteTest.kt` (the effects of a completion, pending only, a used code, the exact expiry
  instant, a locked name, the filler path, two coroutines on one code give one winner),
  `ResetStoreLateTryTest.kt` (a late wrong try after a reset is a plain count that complete clears; a locked
  pending name completes through the store), `ResetSweepTest.kt` (`deleteExpired` at its edges and
  `runHandoffSweep` with a reset store) and `ResetFeedRuleTest.kt` (after a reset the cursor at the old head
  and the cursor 0 are 410, a first page and its `next` are not, the next push is above the floor). Under
  `tokens/`, `TokenStoreRevokeAllOnTest.kt`; under `transcriptions/`, `TranscriptionPurgeTest.kt`; under
  `accounts/`, `AccountStorePendingTest.kt` (verify on a pending account is Rejected and runs one hash; with
  the limit on the try is counted and the lock applies, with the limit off there is no count and no lock;
  `resetPendingOf`; `storeKeys` answers `ResetPending`), `AccountStorePendingCountsTest.kt` (the count, the
  hash cost and the lock of a pending name at store level); under `passwordchange/`,
  `PasswordChangeStorePendingTest.kt` (the pending refusal comes before the no-keys one). Under `admin/`,
  `AdminResetAccountRouteTest.kt` and `AdminResetAccountRouteBodyTest.kt` (401, 403 for a user session and a
  transcribe agent, 200 for an admin session and an admin agent token, 404, 413, 400 shapes, self-reset).
  Under `auth/`, `ResetRouteTestBase.kt` (test-only helper), `AuthCompleteResetRouteTest.kt`,
  `AuthCompleteResetRouteBodyTest.kt`, `AuthCompleteResetRouteIdentityTest.kt` (every failed lookup gives the
  same status and bytes; an agent token is 403 before the body; a session header is ignored and its expiry
  does not move), `AuthCompleteResetRouteSafetyTest.kt` (two completions at once, 503 on a closed database, no
  log line holds the code), `AuthLoginPendingTest.kt` (the limit on and off), `AuthLoginPendingLockTest.kt`
  (the lock of pending names at route level), `AuthKeysPendingTest.kt` (a pending account with a valid body gets
  409 and is not keyed; with a bad body it gets 400, not 409) and `AuthChangePasswordPendingTest.kt` (409 before
  the body and before the old verifier is checked).
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
- How `whisper-server` hands a sealed copy to the pending-sealed store is NOT decided, and neither the
  store nor any write path into it is built. ADR-018's amendment of 2026-10-20 drops the planned
  whisper-server -> sync-api code edge and chooses no transport; a new ADR decides it when Phase 19/20 is
  ordered.
- `change-password`'s `old_auth_verifier` field is the only thing standing
  between "holds a live session token" and "can replace this account's
  keys" — do not let the Bearer token alone satisfy this endpoint.
- The server-side hash adds no guessing resistance beyond the client's key
  derivation: the verifier is 32 pseudorandom bytes, and the hash is there so
  that a stolen row is not a usable login.
- An unknown username, and a pending account, at login costs one full hash against a fixed decoy
  verifier, the same as a wrong verifier, so login timing does not tell them
  apart. The salt step has no decoy: account existence is already public there.
- No logging binding is on the classpath yet, so Ktor prints a notice about a
  no-op logger in tests.
- `sqlite-jdbc` unpacks a native library into the temporary directory at
  first use, so the container needs a writable one.
- Hashing at 600,000 iterations takes a noticeable fraction of a second per
  register or login. It runs off the database lane.
- The database holds one connection, and every transaction runs on one
  single-lane coroutine dispatcher.
- The token store knows no caller: a route must check the token's `kind` and
  its effective scope itself. Which endpoint takes which token:
  - `POST /v1/auth/keys`, `POST /v1/auth/change-password`, `POST /v1/sync`,
    `GET /v1/transcriptions`, `GET /v1/sync/pending-sealed` need a session token; an agent token is
    refused.
  - `POST /v1/audio/transcriptions` and `GET /v1/jobs/{id}` accept an agent
    token or a session token.
  - The admin endpoints need role admin for a session token and effective
    scope admin for an agent token.
- A session row ends by expiry (30 days after its last use, ADR-031), logout,
  `change-password`, a reset or the deletion of the account. Rows that are
  revoked or expired are never purged yet.
- A revoked row is kept (a soft revoke), so a token id is never reused and
  revoking twice changes nothing.
- An agent token's label is 1 to 64 characters with no control characters. It
  may repeat for one owner and is stored as typed.
- A token's text carries 256 random bits, so a fast unsalted hash is enough: no
  password-style stretching is needed. No timing-safe comparison is needed
  either, because the database compares one hash to another and the caller
  controls only the text that is hashed.

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
