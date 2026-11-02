# AGENTS.md — shared/modules/api-contracts/

## Purpose

The OpenAPI 3 spec for every endpoint group (jobs, auth, sync, updates, admin, training), with its Kotlin
request/response types (ADR-017): the single contract both the Android client (`stt-server`) and the server
(`whisper-server`) implement.

**Build phase:** in parts, each before the phase that needs it. First the transcription-job contract (`POST /v1/audio/transcriptions`, `GET /v1/jobs/{job_id}`, `GET /health`), needed by `whisper-server` (Phase 4) and `stt-server` (Phase 5); auth and sync before Phase 11 (`auth-client`, `sync`, `sync-api`); updates, admin and training with their phases; the encryption fields with Phase 19, and the key_version 0 sync body codec with its test vectors now (ADR-006, amended 2026-10-21). **Shipped so far:** the job group (part 1) and the auth group (part 2: register, salt, login, logout, admin agent-token revoke); the remaining groups (sync, updates, the rest of admin, training, keys and change-password) are later parts. Needs first: `shared`.

**Endpoints:**
- `POST /v1/audio/transcriptions` — multipart form: `file`, `model`, `language`
  → 202 `{ job_id, status: "queued" }` | 401 unauth | 413 too large
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
  timestamps, or `null`, each shown when new to the device: "this account was
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
- **auth — logout (ADR-018)** — `POST /v1/auth/logout`, bearer token only, no request body: revokes
  ONLY the presented token. → **204 No Content** | **401** `ApiError` for a missing, unknown or
  already-revoked token (nothing beyond "not a valid session" is disclosed). There is no standalone
  revoke-others route: other sessions are revoked only inside `change-password` (Phase 19).
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
- **sync** — **user session token only on every endpoint in this group, an
  agent token is refused.** `POST /v1/sync` (idempotent push, dedupe on the
  **(client id, `key_version`)** pair — not on client id alone, and not a
  content hash: two devices' AES-256-GCM ciphertexts for the same plaintext
  never match, because each uses a fresh nonce; the same pair is a no-op, a
  higher `key_version` at the same client id atomically replaces the stored
  record, and a lower one, at an existing or a brand-new client id, is
  refused), `GET /v1/transcriptions` (pull as a page `{items, next, more}` of
  at most 100 records or 4 MiB, repeated with `since` = `next`; each record
  carries `change` and `deleted`, each live record its `key_version`
  included; also used by the first-login bootstrap to find and replace any
  pre-encryption plaintext rows, ADR-006), `GET /v1/sync/pending-sealed`
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
- **jobs** — `POST /v1/audio/transcriptions` (enqueue), `GET /v1/jobs/{id}`
  (status/result — unchanged by ADR-018: the caller that submitted the job
  always gets its own plaintext result back here, exactly as ADR-015 already
  specifies; sealing to the owner, when the caller was an agent token, is a
  separate, server-internal step between `whisper-server` and `sync-api`,
  not a second public endpoint). **Result retention (ADR-018):** a
  completed `result` is deleted from the job store once its requester has
  fetched it, or 24 hours after the job finished if nobody ever does —
  until then it sits in `whisper-server`'s SQLite store in plaintext, the
  same store the still-queued audio uses (ADR-010).
- **updates** — `GET /v1/updates/latest.json`
- **admin** — roles, health, transcription-service, test-service,
  agent-tokens, **reset-account** (F31, ADR-018 — replaces the earlier
  "reset-password" naming, which implied the admin sets a password; it
  cannot), **clear-store** (F29)
- **training** — `POST /v1/training/samples`, `GET /v1/training/model` (+ checksum)

**Auth:** `Authorization: Bearer <apiKey | userToken | agentToken>`.

**Note:** this endpoint lives on Breaker's own `whisper-server` container,
built in Phase 4 FROM this spec — never on the Whisper X container, which
Breaker does not modify and this client never addresses directly. Whisper
X's existing `/v1/chat/completions` (used separately by `format`) is out of
scope here.

## Invariants
- Spec is machine-validatable (openapi-generator parses clean). **NOT VERIFIED** — the tool is not in the toolchain; a stdlib structural validator covers the slice's subset.
- Example request/response for each endpoint (golden WAV included).
- Client and server generated/stubbed from THIS spec — no drift.
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
- Kotlin types (ADR-017): their tests run with `./gradlew :shared:modules:api-contracts:test` once the module applies the Kotlin plugin with its first types.
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
- Client and server generate from THIS spec — no drift.
- The Gradle boundary edge is LIVE: this module applies the Kotlin/JVM
  plugin and the artifact exists, and stt-server already names the edge —
  `implementation(project(":shared:modules:api-contracts"))` in
  android/modules/stt-server/build.gradle.kts, with `shared_api_contracts`
  in stt-server's `depends_on` in modules.toml. The bijection check
  demands the named edge; it is provided.
- The `openapi-generator parses clean` invariant is **NOT VERIFIED** — the
  tool is not in the toolchain; a stdlib structural validator covers the
  slice's subset.
