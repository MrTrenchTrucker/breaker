# AGENTS.md — shared/modules/api-contracts/

## Purpose

The OpenAPI 3 spec for the endpoint groups that exist today (jobs, auth, sync, admin and the updates manifest body; training and streaming are planned, not built), with its Kotlin
request/response types (ADR-017): the single contract both the Android client (`stt-server`) and the server
(`whisper-server`) implement.

**Build phase:** in parts, each before the phase that needs it. First the transcription-job contract (`POST /v1/audio/transcriptions`, `GET /v1/jobs/{job_id}`, `GET /health`), needed by `whisper-server` (Phase 4) and `stt-server` (Phase 5); auth and sync before Phase 11 (`auth-client`, `sync`, `sync-api`); updates and training with their phases; the encryption fields with Phase 19, and the key_version 0 sync body codec with its test vectors now (ADR-006, amended 2026-10-21). **Shipped so far:** the job group (part 1), the auth group (part 2: register, salt, login, logout, keys, change-password, complete-reset and the sign-in hand-off), the sync group and the admin group; the remaining groups (updates and training) are later parts. Needs first: `shared`.

**Endpoints:**
- `POST /v1/audio/transcriptions` — multipart form: `file`, `model`, `language`
  → 202 `{ job_id, status: "queued" }` | 400 missing or empty upload | 401 unauth | 413 too large | 429 rate limited | 500 upload unreadable
- `GET /v1/jobs/{job_id}` → `{ status: queued|processing|done|failed, result:
  { text, segments[], language } | null, error: string | null }` — `error`
  is the failure reason, set exactly when status is `failed` (enforced both
  ways in the Kotlin type); `POST` 202 status is `queued` only. `result` is
  set exactly when status is `done` (enforced both ways in the Kotlin type,
  like `error`).
- `GET /health` — `{ status, forwarding_to, uptime }`
- (v1.1) `POST /v1/audio/transcriptions/stream` — SSE partial segments

There is no synchronous 200-with-body variant — every transcription is a job.

**Full endpoint groups (v1):**
- **auth** (ADR-006, ADR-018) — `POST /v1/auth/register` `{username, salt,
  kdf_params, kdf_version, auth_verifier}` (server rejects `kdf_params` below
  the 64 MiB/3/1 floor), first account = admin; `POST /v1/auth/salt`
  `{username} -> {salt, kdf_params, kdf_version}`, no decoy (honest "no such
  account" for an unknown name); `POST /v1/auth/login` `{username,
  auth_verifier} -> {token, role, wrapped_dek, wrapped_box_privkey,
  box_pubkey, key_version, prev_wrapped_dek, reset_at, rekeyed_at}`
  (`prev_wrapped_dek`: the previous DEK wrapped with the current one, present
  only while a re-key is unfinished, else `null`, ADR-006; `role` is `admin`|`user`,
  per auth-client's card; the four key fields (one of them, `box_pubkey`,
  plain rather than wrapped) are `null` together for a keyless account;
  `key_version` is `0` before the first bootstrap and never goes backwards;
  `reset_at`/`rekeyed_at` are the account's last-reset and last-rekey
  timestamps, or `null`, written as UTC with exactly three fractional digits
  (`2026-11-04T08:47:41.000Z` for a whole second), each shown when new to the device: "this account was
  reset on \<date\>" and "keys last changed on \<date\>", ADR-006/ADR-018) — the client refuses to
  proceed if the returned `kdf_params` are below the floor, whatever
  `kdf_version` claims (a server cannot talk a client down, ADR-006); `POST
  /v1/auth/keys` `{wrapped_dek, wrapped_box_privkey, box_pubkey}`, accepted
  only once, while the authenticated account has no keys yet (accepting it
  sets `key_version` to its previous value + 1) — **user
  session token only, an agent token is refused** (an admin can mint an
  agent token for any owner account, so key-bearing endpoints do not accept
  one); `POST /v1/auth/change-password` — user session token identifies the
  account (also refuses an agent token), body `{old_auth_verifier, new_salt,
  new_kdf_params, new_kdf_version, new_wrapped_dek, new_wrapped_box_privkey,
  new_box_pubkey, new_auth_verifier, new_key_version, prev_wrapped_dek}`
  (`prev_wrapped_dek` = the old DEK wrapped with the new DEK, ADR-006; server rejects
  `new_kdf_params` below the floor too, and any `new_key_version` that is not
  exactly the account's current one plus one; refused with 409 while the
  account still holds a `prev_wrapped_dek` from an unfinished re-key), `old_auth_verifier` re-proves
  the password so a bare token never suffices, revokes every other **user
  session** token (agent tokens are untouched — revoke those individually via
  `DELETE /v1/admin/agent-tokens/{id}`) — **every call re-keys:** there is no
  request shape left that merely re-wraps the existing DEK, and no special
  case for a change that happens to follow a reset (ADR-006/ADR-018); `POST
  /v1/auth/complete-reset` `{username, reset_code, salt, kdf_params,
  kdf_version, auth_verifier}` (server rejects `kdf_params` below the floor
  here too), authenticated by a one-time admin-issued code (≥128 random
  bits, single use, 24-hour expiry, no attempt-limit claim) **instead of a
  password OR a session token — the reset already revoked every token the
  account had, so this endpoint requires none, and refuses an agent token
  the same as the key-bearing endpoints above** — leaves the account keyless
  again, so `POST /v1/auth/keys` follows it exactly as it would a new
  account.
- **auth - key upload, password change, admin reset and reset completion (built) (ADR-006, ADR-018)** -
  `openapi.yaml` holds the paths of `POST /v1/auth/keys`, `POST /v1/auth/change-password`,
  `POST /v1/auth/complete-reset` and `POST /v1/admin/reset-account`; all four are built on the server.
  `POST /v1/auth/keys` takes a user session token only (an agent token is refused, 403, the same
  body as `/v1/sync`, decided before the body is read) and reads a body of at most 4096 bytes. The
  body is exactly `{wrapped_dek, wrapped_box_privkey, box_pubkey}`, each a string of standard
  base64 (RFC 4648, padded, no whitespace, no line wrap) that decodes to exactly 60, 60 and 32
  bytes (80, 80 and 44 characters). The 60 bytes are the 12-byte nonce, the 32 encrypted key bytes
  and the 16-byte tag (AES-256-GCM); the 32 bytes are the plain public key. The server checks only
  the lengths, stores the decoded bytes and re-encodes them, so a value whose last character
  carries spare bits is accepted, and login writes the values back as canonical padded base64.
  Statuses of keys: **200** `{"key_version": N}` (N is the new key_version; the success body
  is a choice of this contract, and change-password answers with the same shape) | **400**
  `bad_request` `Invalid request body` | **401** `unauthorized` `Invalid or missing user session
  token` | **403** `forbidden` `User session token required; an agent token is refused` | **409**
  `conflict` `The account already has keys`, or, first and for an account that waits for a reset,
  `The account is waiting for a reset to be completed` | **413** `payload_too_large` | **503**
  `unavailable`. A revoked or an expired session token gets the same 401 as an unknown one.
  `POST /v1/auth/change-password` (built) has the same token rule and the same 400, 401, 403, 413 and 503
  answers; it answers **200** `{"key_version": N}`, **401** `Invalid old verifier` for a wrong old
  verifier (which counts as a wrong try; one that is not base64 or not 32 bytes is a 400 and does not
  count), **409** `conflict` for an account without keys (`The account has no keys`), for a
  `new_key_version` that is not the current one plus one and for an unfinished re-key
  (`prev_wrapped_dek` held) (that is the order when more than one applies, except that an account
  waiting for a reset to be completed gets `The account is waiting for a reset to be completed`
  first; the mark is read right after the session check and before the body, so a bad body on a
  pending account is 409 and no wrong try is counted, and it is read again inside the transaction),
  and **423** `locked` with Retry-After. A session token that is revoked or expired before the
  transaction commits (for example by an admin reset running at the same time) gets the same 401,
  `unauthorized` `Invalid or missing user session token`. Its lengths, all checked before any
  write: old verifier 32, new salt 16, new verifier 32, new wrapped DEK 60, new wrapped box private
  key 60, new box public key 32 and `prev_wrapped_dek` 60 bytes. A success stores the new values,
  sets `rekeyed_at`, revokes every other user session token and the account's unredeemed sign-in
  codes, and clears the wrong-password row. The rule for `prev_wrapped_dek`: it is set to `null` again
  as soon as no stored transcription of the account that is not deleted has a `key_version` below the
  account's, checked in the same transaction after the change-password write, after a sync push that
  replaces a row and after a sync delete.
  `POST /v1/auth/complete-reset` takes no token (an agent token in the header is refused with 403
  before the body is read; any other header is ignored and its expiry does not move), answers
  **200** with the login body (keyless, `reset_at` set), **400**, **403**, **413**, **503**, and one
  fixed **401** `unauthorized` `Invalid username or reset code` for an unknown or invalid name, an
  account that is not waiting for a reset, or an unknown, malformed, wrong, used or expired code.
  The body is checked for its shape and types first (400); the 400 for the salt (16 bytes), the KDF
  bounds, `kdf_version` 1 and the verifier (32 bytes) comes only after a right name and code. There
  is no attempt counter: a wrong code leaves the wrong-password row alone and a locked name does
  not block a right code. The reset code and the request body are never logged.
  `POST /v1/admin/reset-account` `{username}` (admin session token or admin-scoped agent token; a
  user session is **403**, no token **401**, an unknown or invalid name **404** `not_found`
  `Account not found`, a body that is not exactly one string member **400**, a body over 4096
  bytes **413**, a storage failure **503**) answers **200** `{"reset_code": "..."}`. The code is 32
  random bytes as 43 URL-safe characters (256 random bits), single use, valid for 24 hours, stored
  only as a SHA-256 hash, one live code per account (a new reset replaces it), and shown only in
  this answer. The reset replaces the verifier with random bytes and marks the account
  reset-pending, clears the keys, revokes every token and unredeemed sign-in code, deletes the
  transcriptions, clears the wrong-password row, sets `reset_at` and keeps `key_version`. It also
  sets the purged floor of the change feed to the new change number, so every earlier cursor, also
  one at the old head, gets **410** from `GET /v1/transcriptions` and the client does a full
  resync. A pending account cannot log in (the 401 of a wrong verifier, not counted); `salt`
  answers as for any account; `keys` and `change-password` answer **409** (above). An admin who
  resets their own account revokes their own token. The pending-sealed rows of ADR-018 are planned,
  not built: that table does not exist yet.
- **auth — logout (ADR-018)** — `POST /v1/auth/logout`, bearer token only, no request body: revokes
  ONLY the presented token. → **204 No Content** | **401** `ApiError` for a missing, unknown or
  already-revoked token (nothing beyond "not a valid session" is disclosed). There is no standalone
  revoke-others route: other sessions are revoked only inside `change-password` (Phase 19).
- **auth - devices** - `GET /v1/auth/devices`, bearer user session token only: lists the caller's
  live sessions, oldest first, at most 50 rows. Each row: `id` (integer), `name` (string of 1 to
  64 code points, or null), `last_seen` (ISO-8601 UTC with milliseconds and a trailing Z),
  `current` (boolean, true only for the presented token). Answers **200** `{devices: [...]}`, **401**
  `ApiError` for a missing or dead token, **403** `ApiError` for an agent token, **503** `ApiError`
  for a store failure.
- **auth - devices - sign out one** - `DELETE /v1/auth/devices/{id}`, bearer user session token only: signs
  out one device of the caller's own account, by the id the device list returns. The id is decimal digits
  only. Answers **204 No Content**, or **404** `ApiError` `{error: "not_found", message: "No such device"}`
  for a bad id, an unknown id, another account's id, an agent token id, an already revoked id, an expired id
  and an id past the 180 day cap: one body, carrying no part of the id. A second DELETE of the same id
  answers 404. Signing out the calling device is allowed. The account's hand-off codes are left alone.
  **401** `ApiError` for a missing or dead token, **403** `ApiError` for an agent token, **503** `ApiError`
  for a store failure. Agent tokens are never revoked here.
- **auth - devices - sign out all** - `POST /v1/auth/devices/sign-out-all`, bearer user session token only:
  ends every device of the caller's own account, including the calling device, and deletes the account's
  unredeemed hand-off codes in the same transaction. Answers **204 No Content**, **401** `ApiError` for a
  missing or dead token, **403** `ApiError` for an agent token, **503** `ApiError` for a store failure.
  Agent tokens are never revoked here.
- **auth - sign-in hand-off (ADR-036)** - `POST /v1/auth/handoff/start`, user session token only (an
  agent token is refused, 403, the same body as `/v1/sync`): `{challenge, sealed_package}` -> **201**
  `{code}` | **400** | **401** | **403** | **413**. The code is valid for 5 minutes and usable once, it is
  43 URL-safe characters (so it goes into a `breaker://` link with no escaping), and an account has at most
  5 live codes (a sixth start ends the oldest and removes its package). `POST /v1/auth/handoff/exchange`,
  no token (the code and the secret are the credential): `{code, secret, device_name?}` -> **200** `{token,
  role, key_version, sealed_package, username, account_id}` | **400** (a 400 does not use up the code) |
  **401** | **413**. The exchange has ONE refusal: **401** `ApiError` `Invalid sign-in code or secret`, the
  same body for an unknown code, a code or secret of the wrong length or alphabet, a wrong secret, an
  expired code and a code already used; no other status tells these cases apart, and a wrong secret does
  not use up the code. The package is the sealed key package, exactly 113 bytes (65 + 48) written as
  standard base64 (152 characters); the server stores it without opening it, checks only its length, and
  deletes it when the code is used. The field is named `sealed_package` and NOT `package` because
  `package` is a hard keyword in Kotlin and the Kotlin type must carry the field (`sealedPackage`). The
  session from the exchange is an ordinary session that ends 30 days after its last use.
- **auth — statuses of this slice** (every error body is the shared `ApiError`, never echoing a
  password, verifier, salt or token): register → **201** `{username, role}` | **400** naming the
  failing field (username charset or length, `kdf_version` other than 1, KDF values outside
  memory_kib 65536-262144 (64-256 MiB) / iterations 3-10 / parallelism 1-4, a salt whose decoded length is
  not 16 bytes or a verifier whose decoded length is not 32 bytes) | **409** username already taken (compared lowercase); salt → **200** | **404** for
  an unknown username (no decoy); login → **200** | **401** for bad credentials or an unknown
  account (the same body for both); `DELETE /v1/admin/agent-tokens/{id}` → **204** (also when
  already revoked) | **404** unknown id | **401** no or invalid token | **403** a valid
  non-admin token. The wire unit is KiB everywhere (memory_kib 65536-262144).
  Login also answers **503** `unavailable` (the fixed body, never a 500) for a storage failure in
  the credential check, the key read or the session issue.
  Built on the server: login also answers **423** (locked after five wrong
  passwords, with Retry-After in whole seconds), and `GET` and `PUT /v1/admin/wrong-pw-limit` read and set the switch.
- **sync** — **user session token only on every endpoint in this group, an
  agent token is refused.** `POST /v1/sync` (idempotent push, dedupe on the
  **(client id, `key_version`)** pair — not on client id alone, and not a
  content hash: two devices' AES-256-GCM ciphertexts for the same plaintext
  never match, because each uses a fresh nonce; the same pair is a no-op, a
  higher `key_version` at the same client id atomically replaces the stored
  record, and a lower one, at an existing or a brand-new client id, is
  refused; the optional boolean `resave` marks a re-save of an id the phone itself
  deleted), `GET /v1/transcriptions` (pull as a page `{items, next, more}` of
  at most 100 records or 4 MiB, repeated with `since` = `next`; each record
  carries `change` and `deleted`, each live record its `key_version`
  included; also used by the first-login bootstrap to find and replace any
  pre-encryption plaintext rows, ADR-006), `DELETE /v1/transcriptions/{client_id}`
  (204 for an applied, a repeat or an unknown delete; an applied delete takes a new
  change number and shows to other devices as a delete record), `GET /v1/sync/pending-sealed`
  (ADR-018: the authenticated owner's pending agent-token results, still
  sealed — `[{id, sealed_box, created_at}]`; the client unseals each,
  re-encrypts it under its own DEK, and uploads it via the same
  `POST /v1/sync` with `client_id` set to the pending record's `id` at the
  account's current `key_version` — a new record, not a replace; the server
  deletes that pending record once a synced transcription with that client
  id lands — **honest limit:** this endpoint cannot distinguish a row
  `whisper-server` sealed from one written directly by anyone with database
  write access, T26)
  The `encrypted` text of a push at key_version 0 is the standard base64 of an
  R1 body: one UTF-8 JSON object with the keys v, text, source, model,
  duration_ms and created_at, in that order (v is 1, source is local or
  server, created_at is the UTC time the transcription was made, to the
  millisecond). The Kotlin codec for that body is `SyncBodyCodec` in package
  `dev.breaker.shared.api.sync`; the full rules are in the /v1/sync operation
  description of `openapi.yaml`. A key_version 0 body carries no cryptography.
- **jobs** - `POST /v1/audio/transcriptions` (enqueue), `GET /v1/jobs/{job_id}`
  (status/result — unchanged by ADR-018: the caller that submitted the job
  always gets its own plaintext result back here, exactly as ADR-015 already
  specifies; sealing to the owner, when the caller was an agent token, is a
  separate, server-internal step between `whisper-server` and `sync-api`,
  not a second public endpoint). **Result retention (ADR-018):** a
  completed `result` stays fetchable for a window after the job finished
  (default 10 minutes, set by `BREAKER_WHISPER_RESULT_TTL_S`, at most 24 hours);
  every `GET` inside the window returns the same result and after it the
  result is erased - until then it sits in `whisper-server`'s SQLite store in plaintext, the
  same store the still-queued audio uses (ADR-010).
- **updates** - `GET /v1/updates/latest.json`
  In the spec (body only): the path is in `openapi.yaml` with the six-field `UpdateManifest` body (`version`, `version_code`, `apk_url`, `sha256`, `signature`, `notes`); the server does not serve it yet; auth and statuses are open (ADR-011).
- **admin** — roles, health, transcription-service, test-service,
  agent-tokens, **end-session** (`DELETE /v1/admin/sessions/{id}`, ADR-036),
  **reset-account** (F31, ADR-018 — replaces the earlier
  "reset-password" naming, which implied the admin sets a password; it
  cannot), **clear-store** (F29) — BUILT: `POST /v1/admin/clear-store` clears
  transcriptions per-user or for all users with confirmation; every live row gets a delete
  record so other devices remove the rows through the feed.
- **training** — `POST /v1/training/samples`, `GET /v1/training/model` (+ checksum)
  Planned, not built: no training path is in `openapi.yaml` yet and no module serves it.

**Auth:** `Authorization: Bearer <apiKey | userToken | agentToken>`.

**Note:** this endpoint lives on Breaker's own `whisper-server` container,
built in Phase 4 FROM this spec — never on the Whisper X container, which
Breaker does not modify and this client never addresses directly. Whisper
X's existing `/v1/chat/completions` (used separately by `format`) is out of
scope here.

## Invariants
- Spec is machine-validatable (openapi-generator parses clean). Checked in CI by the pinned validator, job `openapi-validate` in `.github/workflows/checks.yml` (openapi-generator-cli 7.26.0, jar sha256 pinned); a pin test guards the pin. A stdlib structural validator also covers the slice's subset.
- Example request/response for each endpoint (golden WAV included).
- Client and server use the Kotlin types of this module, written by hand from the spec; `tests/unit/shared/api-contracts/test_conformance.py` compares every schema with its Kotlin type field for field, so drift fails a test.
- A key_version 0 body carries no cryptography and is development-only until Phase 19.

## Owns
OpenAPI spec: auth, sync, jobs, updates, admin, training.

## Public Interface
OpenAPI spec (openapi.yaml); Kotlin request and response types
- `openapi.yaml`: the language-neutral contract (web FE, agents).
- Kotlin request and response types matching the spec, compiled against by the
  app and by the server services (ADR-017). A Python-tier conformance test keeps
  them identical to the spec.
- Sync body codec, package `dev.breaker.shared.api.sync`: `SyncBodyCodec` encodes
  and decodes the R1 body and the key_version 0 `encrypted` text. It sits in a
  subpackage, outside the Python reader's scan of this folder.

## Depends On
- shared (registered in modules.toml)

## Does Not Own
- Server implementation (server/*)
- Client implementation (android/*)

## Test Locations
- Unit (Python, the spec and its conformance checks): `tests/unit/shared/api-contracts/`, created with the module's first slice. Run: `python3 -m unittest discover -s tests/unit/shared/api-contracts`
- Kotlin types (ADR-017): their tests run with `./gradlew :shared:modules:api-contracts:test` (the module applies the Kotlin plugin).
- Sync body vectors: `examples/sync_body_vectors.json`. The Kotlin codec test under
  `src/test/kotlin/dev/breaker/shared/api/sync/` checks them, and the stdlib oracle
  `tests/unit/shared/api-contracts/test_sync_body_vectors.py` re-derives them. The
  spec pins are in `tests/unit/shared/api-contracts/test_sync_body_spec.py`.
- Username vectors: `examples/username_vectors.json` holds the one table of names
  and answers for the username pattern. The stdlib test
  `tests/unit/shared/api-contracts/test_username_vectors.py` checks the table
  against the pattern in `openapi.yaml` and against the Kotlin literal copy in the
  sync-api tests; each side of the system checks the same table.
- Contract: `tests/contract/test_api_contracts_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_api_contracts_contract.py`
- Sign-in hand-off: the stdlib test `tests/unit/shared/api-contracts/test_signin_handoff_contract.py`
  pins the two hand-off paths, their statuses, the field sets and patterns of the four hand-off schemas,
  the one refusal of the exchange, and the matching Kotlin types.
- Wrong-password limit (built on the server): the stdlib test `tests/unit/shared/api-contracts/test_wrongpw_contract.py`
  pins the 423 locked answer of login with its Retry-After header (whole seconds, minimum 1), the
  `GET` and `PUT /v1/admin/wrong-pw-limit` path with its statuses and operation ids, and the
  `WrongPwLimit` schema (its field-for-field match with the Kotlin type is checked by `test_conformance.py`).
- Updates manifest: the stdlib test `tests/unit/shared/api-contracts/test_updates_contract.py` pins the path, the operation id, the six required names, the field types and `version_code` as int64 with minimum 1, and runs the conformance comparator on `UpdateManifest`; the Kotlin test `UpdateManifestTest` pins the minimum of the version code in the type.
- Validator pin: `tests/unit/shared/api-contracts/test_openapi_validation_pin.py` reads `.github/workflows/checks.yml` as text and pins the job, the version, the jar sha256, the checksum-before-run order, the spec path and the version-built URL.
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

## Known Gotchas
- Client and server use hand-written Kotlin types, not generated ones; `test_conformance.py` keeps them in line with this spec.
- The Gradle boundary edge is LIVE: this module applies the Kotlin/JVM
  plugin and the artifact exists, and stt-server already names the edge —
  `implementation(project(":shared:modules:api-contracts"))` in
  android/modules/stt-server/build.gradle.kts, with `shared_api_contracts`
  in stt-server's `depends_on` in modules.toml. The bijection check
  demands the named edge; it is provided.
- The `openapi-generator parses clean` invariant is checked in CI by the pinned
  validator, job `openapi-validate`; `test_openapi_validation_pin.py` guards the pin.
  A stdlib structural validator also covers the slice's subset.
