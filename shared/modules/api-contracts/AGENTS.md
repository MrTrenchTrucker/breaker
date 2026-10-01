# AGENTS.md — shared/modules/api-contracts/

## Purpose

OpenAPI spec: auth, sync, jobs, updates, admin, training. OpenAPI 3 spec for the transcription endpoint — the single contract
both the Android client (`stt-server`) and the server (`whisper-server` add-on)
implement.

**Endpoints:**
- `POST /v1/audio/transcriptions` — multipart form: `file`, `model`, `language`
  → 202 `{ job_id, status: "queued" }` | 401 unauth | 413 too large
- `GET /v1/jobs/{job_id}` → `{ status: queued|processing|done|failed, result:
  { text, segments[], language } | null }`
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
- **sync** — **user session token only on every endpoint in this group, an
  agent token is refused.** `POST /v1/sync` (idempotent push, dedupe on the
  **(client id, `key_version`)** pair — not on client id alone, and not a
  content hash: two devices' AES-256-GCM ciphertexts for the same plaintext
  never match, because each uses a fresh nonce; the same pair is a no-op, a
  higher `key_version` at the same client id atomically replaces the stored
  record, and a lower one, at an existing or a brand-new client id, is
  refused), `GET /v1/transcriptions` (pull, each record's `key_version`
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
- Spec is machine-validatable (openapi-generator parses clean).
- Example request/response for each endpoint (golden WAV included).
- Client and server generated/stubbed from THIS spec — no drift.

## Owns
OpenAPI spec: auth, sync, jobs, updates, admin, training.

## Public Interface
OpenAPI spec (openapi.yaml); Kotlin request and response types
- `openapi.yaml`: the language-neutral contract (web FE, agents).
- Kotlin request and response types matching the spec, compiled against by the
  app and by the server services (ADR-017). A Python-tier conformance test keeps
  them identical to the spec.

## Depends On
- shared (registered in modules.toml)

## Does Not Own
- Server implementation (server/*)
- Client implementation (android/*)

## Test Locations
- Unit: `tests/unit/shared/api-contracts/`
- Contract: `tests/contract/test_api_contracts_contract.py`

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
- The Gradle boundary check does not require a consumer to name the
  `project(":shared:modules:api-contracts")` edge while this module
  publishes no artifact; the moment it gains code (applies an artifact
  plugin) the edge becomes required, and the bijection check will then
  demand it.
