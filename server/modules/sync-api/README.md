# sync-api - README

The server's main API. It keeps accounts and logins, the agent-token revoke route, and the transcription sync routes: a phone pushes one transcription, and the account pulls its own transcriptions back. Every user's data is kept apart from every other user's (ADR-014). The sync routes accept only key_version 0, the development-only plaintext form. Encrypted storage (ADR-006) is not built yet.

## What the module does today

- Health: `GET /health` answers that the service is up. It touches no data.
- Accounts: register a username with its salt, KDF settings and auth verifier; look up the salt and KDF settings for a username; log in; log out. The first account ever registered is the admin, and every later account is a user. The auth verifier is kept only as a salted hash.
- Tokens: a login issues a session token. Only a SHA-256 hash of each token text is stored, never the token. Agent tokens are created by the token store, which has no HTTP route yet. An admin can revoke an agent token with `DELETE /v1/admin/agent-tokens/{id}`.
- Transcription sync: `POST /v1/sync` stores one transcription, and `GET /v1/transcriptions` returns the account's own rows. Both are written for key_version 0 only (see the rules below).
- Storage is SQLite. The schema is versioned, and the server brings the file to the current version when it starts.

## Routes

| Method and path | Who may call it | Success | Other answers |
|---|---|---|---|
| GET /health | anyone | 200 `{"status":"ok"}` | none |
| POST /v1/auth/register | anyone | 201 `{"username","role"}` | 400 invalid_field; 409 username_taken |
| POST /v1/auth/salt | anyone | 200 salt, kdf_params, kdf_version | 400 bad_request; 404 no_such_account |
| POST /v1/auth/login | anyone | 200 token and role, with the key fields | 400 bad_request; 401 unauthorized |
| POST /v1/auth/logout | a session token | 204, no body | 401 unauthorized, also for an agent token |
| DELETE /v1/admin/agent-tokens/{id} | an admin session token, or an agent token of admin scope | 204, also when already revoked | 401 unauthorized; 403 forbidden (not an admin); 404 not_found |
| POST /v1/sync | a session token only | 204, no body | 400 bad_request; 401; 403 forbidden (agent token); 409 conflict; 413 payload_too_large |
| GET /v1/transcriptions | a session token only | 200, a JSON array | 401; 403 forbidden (agent token) |

Notes on the routes that exist now:

- Register body: `username` (1 to 64 characters from A-Z, a-z, 0-9, `.`, `_`, `-`; compared in lower case), `salt` (base64, 16 bytes), `kdf_params` (`memory_kib` 65536 to 262144, `iterations` 3 to 10, `parallelism` 1 to 4), `kdf_version` (1), `auth_verifier` (base64, 32 bytes). A value out of range is refused with 400, and nothing is stored.
- Login returns `role`, a `token`, and the key fields `wrapped_dek`, `wrapped_box_privkey`, `box_pubkey`, `prev_wrapped_dek`, `reset_at` and `rekeyed_at`, which are null, with `key_version` 0. Key storage is not built, so every account answers this way today.
- Logout revokes only the session token that was presented. The account's other sessions and its agent tokens are not touched.
- The admin revoke route answers 404 when the id is not an agent token row (an unknown id, or the id of a session token).

## The sync routes

### POST /v1/sync (a phone pushes one transcription)

1. The token is checked first. A missing, unknown, expired or revoked token gets 401. A live agent token gets 403. The body of a request that has no good session token is never read.
2. A `Content-Length` above 131072 bytes gets 413 at once. Otherwise the body is read up to 131072 bytes. A body that is larger gets 413, and the rest of it is not read.
3. The body must be one UTF-8 JSON object with exactly three keys: `client_id` (a string), `key_version` (a JSON integer from 0 to 2147483647: no sign, no fraction, no exponent, no leading zero except 0 itself) and `encrypted` (a non-empty string). Any other body gets 400.
4. `client_id` must be a lower-case canonical UUID: 8-4-4-4-12 lower-case hex digits with hyphens. Otherwise 400.
5. `encrypted` must be strict base64 at every key_version: the standard alphabet, padded, a length that is a multiple of 4, `=` only at the end (at most two), and zero unused bits in the last group. Otherwise 400. The text is never put into a message.
6. The decoded size of `encrypted` is worked out from its length and its padding, which step 5 has already checked, so the text does not need decoding for it. More than 65536 bytes gets 413. Exactly 65536 bytes passes.
7. At key_version 0 only, the decoded text must be a sync body: one JSON object with the keys `v`, `text`, `source`, `model`, `duration_ms` and `created_at` and no other key. The reader accepts the keys in any order. Its format is defined in the sync operation of the API contract (`shared/modules/api-contracts/openapi.yaml`), which holds the full rules. The decoded fields are dropped at once. Otherwise 400.
8. A `key_version` above the account's current key_version gets 409 with nothing stored. The text is not read as a sync body in that case. Every account is at key_version 0 today, because no code can raise it.
9. The store writes the row. A new pair (`client_id`, `key_version`) gets 204. A pair that is already on file also gets 204, and nothing changes.

### GET /v1/transcriptions (the account pulls its own rows)

- Session token only: 401 for no good token, 403 for an agent token.
- 200 with a JSON array. Each item has exactly four fields: `client_id`, `key_version`, `encrypted` (exactly as stored) and `stored_at`.
- `stored_at` is the time the server stored the row, in RFC 3339 UTC, to whole seconds. The store cuts the time to the whole second when it stores the row, so the stored value and the shown value are the same second. The cut is never a rounding, for example `2026-10-11T12:00:05Z`.
- Order: `stored_at` ascending, then `client_id` ascending. The order uses the same whole-second time that is shown, so two rows that show the same `stored_at` are ordered by `client_id`.
- An account with nothing stored gets `[]`.

## Rules a reader needs

- One row per account and client id. The row keeps `client_id`, `key_version`, the `encrypted` text exactly as received, and `stored_at`, which is held as whole seconds.
- A pair pushed again is a no-op. The first text and the first `stored_at` are kept.
- Replacing a row with a higher key_version is not built. A push above the current key_version is refused with 409 whatever the row holds.
- Every query is scoped to the account that made the request. An account never sees another account's rows, and its pushes are stored under its own account.
- An agent token gets 403 on both sync routes. Logout refuses an agent token with 401, a different answer on purpose.
- Size limits: the raw request body is at most 131072 bytes, and the decoded `encrypted` text is at most 65536 bytes. Both refusals come before anything is written. At every key_version the strict base64 check runs before the size check, so a text that is not strict base64 is refused with 400 even when it is longer than the limit. A 400 or 413 body is a fixed sentence that never repeats the input.
- Only key_version 0 is stored today. The check that `encrypted` is a valid sync body exists only for this development-only plaintext form, and it goes away when encryption ships.
- No log line, error message or response carries the `encrypted` text or the decoded text.

## Storage

- `accounts` (schema version 1): one row per account, with the username, the client salt, the KDF settings, the hashed verifier and the role.
- `tokens` (schema version 2): one row per token. Only the SHA-256 hash of the token text is stored. A revoked row is kept, so a revoked token id is never reused.
- `transcriptions` (added with the sync routes, schema version 3): one row per account and client id, with `key_version`, the `encrypted` text and the stored time in milliseconds. The stored time is always a whole second: the store cuts it when the row is stored. A UNIQUE constraint on the account and client id is the second guard. Deleting an account deletes its rows.

## Not built yet

- Key versions 1 and above: the encrypted form (planned for Phase 19), the replace of a row by a higher key_version, and the 409 for a lower key_version. Only the 409 above the current key_version is built.
- Account keys and changes, planned for Phase 19: `POST /v1/auth/keys`, `POST /v1/auth/change-password` and `POST /v1/auth/complete-reset`.
- Pending-sealed agent-token results, planned for Phase 20: `GET /v1/sync/pending-sealed`.
- Other admin routes: creating and listing agent tokens over HTTP, roles, health, the transcription service, the test service, account reset and clear-store. Also `GET /v1/updates/latest.json`.
- The retention purge (the 90-day rule of ADR-010) and tombstones.
- Session expiry: a session token never expires today.

## Configuration

- `BREAKER_SYNC_API_DB` (required): the path of the SQLite file.
- `BREAKER_SYNC_API_HOST` (optional): the host to listen on. The default is the loopback address.
- `BREAKER_SYNC_API_PORT` (optional): the port to listen on. The default is 8080.

## Tests

The unit tests are Kotlin, under `src/test/kotlin`, and run with the module's Gradle test task (`:server:modules:sync-api:test`). The structural contract test is `tests/contract/test_sync_api_contract.py`. The module card, `AGENTS.md` in this folder, is the full reference.

- The route tests cannot prove that a chunked request carried no Content-Length: a body of 131072 bytes or 131073 bytes gets the same answer whichever branch reads it.
