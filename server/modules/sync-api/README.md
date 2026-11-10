# sync-api — README

The server's main API: accounts and logins (users, tokens, roles), and syncing transcriptions. The route that changes a role and the app's update check are planned, not built. It keeps every user's data separate from everyone else's (N13). It stores each transcription as an opaque string with its key_version and never opens it; before any key exists the stored text is the development-only form (ADR-006).

## What is in the module today

- A health check at `GET /health`. It answers `{"status":"ok"}` and touches nothing else.
- The account store (SQLite): register an account, look up its salt, and check a login verifier. The auth verifier is kept only as a salted hash, and registration refuses key-derivation settings outside the allowed bounds.
- The token store (SQLite): user session tokens, issued at login, and agent tokens, minted for an owner account with a scope (`transcribe` or `admin`) and an optional expiry. The two agent routes are served by whisper-server, where an optional per-token request limit is built: off unless the admin sets it; over the cap that module answers 429 with a Retry-After header, and user sessions are not limited. A session token expires 30 days after its last use, and each use moves that date out, but never past 180 days from its creation (the slide is clamped at the cap; a session past the cap is refused; agent tokens are not capped) (ADR-031). Only a SHA-256 hash of each token is stored, never the token itself. A token never carries more than its owner's role. Revoking works three ways: one agent token, every other session token of an account, or every token of an account. The login, logout, change-password, agent-token create, list and revoke, and token-check routes use it.
- The transcription store (SQLite): one row per account and client id, holding the stored
  text and its key_version as pushed. A push is idempotent on the pair (client_id,
  key_version), and a push body is at most 1 MiB.
- A versioned database schema, migrated to the current version when the server starts.

The account HTTP routes (register, salt, login, logout), the admin routes that create, list and revoke an agent token, that end a session and that
read and flip the wrong-password limit, the two internal token-check routes and the three transcription routes
(POST /v1/sync, GET /v1/transcriptions, DELETE /v1/transcriptions/{client_id}) are installed. The rest of what
the card lists is not built yet. Built: `POST /v1/admin/reset-account` (an admin, with a session or an
admin-scoped agent token, resets one account in one transaction: the verifier is replaced with random bytes
and the account is marked reset-pending, the keys, tokens, sign-in codes and transcriptions are deleted,
`reset_at` is set, `key_version` is kept, every earlier incremental feed cursor gets 410 (a list cursor is never 410), and one one-time reset code is
returned; the code is 43 characters, valid for 24 hours, stored only as a hash, and a new reset replaces it),
`POST /v1/auth/complete-reset` (the person sets a new password with the code, no session needed; every failed
lookup is one fixed 401, and the answer is the keyless login body), the reset-pending account (login answers
the 401 of a wrong verifier and counts it like any wrong try; `POST /v1/auth/keys` and `POST
/v1/auth/change-password` answer 409), migration 9 (the `reset_pending` column and the `reset_codes` table),
the delete of expired reset codes by the same sweep as the sign-in codes, and `POST /v1/auth/keys` (first key
upload, status 200 with the new key_version), `POST /v1/auth/change-password` (the signed-in person changes
the password and re-keys the account in one transaction; it re-proves the old verifier, moves `key_version` up
by one, sets `rekeyed_at`, ends the other sessions and the account's unredeemed sign-in codes and clears the
wrong-password row), the previous-key rule (`prev_wrapped_dek` is set to NULL once no stored transcription
that is not deleted sits below the account's `key_version`; it runs in the password change, in a sync push
that replaces a row and in a sync delete), migration 8 (six nullable key columns on the accounts table) and a
login that answers the stored key columns, with `reset_at` and `rekeyed_at` always printed with three
fractional digits and a fixed 503 for a store failure.

Built on the server: the device list, `GET /v1/auth/devices`, and the two sign-out routes for the person's own
devices, `DELETE /v1/auth/devices/{id}` and `POST /v1/auth/devices/sign-out-all` (204 no body; an agent token
refused 403; one fixed 404 body for any id that is not the caller's live session; agent tokens never revoked).
The website page that calls them, and the app's sign-out call, are not built. Built on the server: the
admin end-session route, `DELETE /v1/admin/sessions/{id}` (204 no body; admin only, an admin session or an
admin-scoped agent token; one fixed 404 body for any id that is not a live session of any account; agent
tokens never revoked; the account's hand-off codes left alone). The
wrong-password limit is built (its failure counting, the locked answer, 423 with Retry-After, the on/off
switch (migration 7, `server_settings`, `ServerSettingsStore`, read live on every attempt, default on, only a
stored integer 0 is off, off = no counting, no refusing, stored attempts kept), the admin route that flips it,
the drop of the row at registration, the hook `AccountStore.clearAttempts` and the sweep every five minutes).
The sign-in hand-off (migration 4, the code store, the sweep, the revoke rule and the start and exchange
routes) is built; the app wiring is built; the website page is built, but its derivation, box key and
sealing steps are stubs, so it cannot complete a hand-off yet. The change number and the delete flag are built
in the store (migration 5); the DELETE route is built; the read side of the feed (the since parameter, the
page object, the 400 and the 410) is built; the pulled number and the purge of delete records are built. The
table of pending-sealed results, which the reset will also have to clear, does not exist yet.

## Run it

Set `BREAKER_SYNC_API_HOST` (default `127.0.0.1`), `BREAKER_SYNC_API_PORT` (default `8080`) and `BREAKER_SYNC_API_DB` (required: the path of the SQLite file), then start `main`. The entry point `main` is not covered by unit tests, because it needs a real socket; everything it calls is.

The internal listener is a second listener, set by BREAKER_SYNC_API_INTERNAL_HOST (default 127.0.0.1) and BREAKER_SYNC_API_INTERNAL_PORT (default 8081). It carries only the two token-check routes that whisper-server calls, never the public listener or the VPN interface. Its service key is made on the first start in the file named by BREAKER_SYNC_API_SERVICE_KEY_FILE (default: service-key next to the database file); delete that file and start again to rotate the key. Transport: Breaker serves plain HTTP on both listeners; TLS is the admin's choice through a reverse proxy in front of it (ADR-034). A refused start names the variable or the path and never prints a key or a value. The card of this module has the full rules.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
