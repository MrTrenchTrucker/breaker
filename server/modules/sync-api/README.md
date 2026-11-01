# sync-api — README

The server's main API: accounts and logins (users, tokens, roles), syncing transcriptions, and the app's update check. It keeps every user's data separate from everyone else's (N13), and it only ever stores encrypted transcriptions once an account has its keys (ADR-006).

## What is in the module today

- A health check at `GET /health`. It answers `{"status":"ok"}` and touches nothing else.
- The account store (SQLite): register an account, look up its salt, and check a login verifier. The auth verifier is kept only as a salted hash, and registration refuses key-derivation settings outside the allowed bounds.
- The token store (SQLite): user session tokens, issued at login, and agent tokens, minted for an owner account with a scope (`transcribe` or `admin`) and an optional expiry. A session token expires 30 days after its last use, and each use moves that date out (ADR-031). Only a SHA-256 hash of each token is stored, never the token itself. A token never carries more than its owner's role. Revoking works three ways: one agent token, every other session token of an account, or every token of an account. The login, logout, agent-token revoke and token-check routes use it.
- A versioned database schema, migrated to the current version when the server starts.

The account HTTP routes (register, salt, login, logout), the admin route that revokes an agent token and the two internal token-check routes are installed. The sync, key and reset routes and the rest of what the card lists are not built yet.

Planned, not built (ADR-035, ADR-036): the changes-since pull, the routes for sign-in from the website, the wrong-password limit, an admin route to end a session, and the device list.

## Run it

Set `BREAKER_SYNC_API_HOST` (default `127.0.0.1`), `BREAKER_SYNC_API_PORT` (default `8080`) and `BREAKER_SYNC_API_DB` (required: the path of the SQLite file), then start `main`. The entry point `main` is not covered by unit tests, because it needs a real socket; everything it calls is.

The internal listener is a second listener, set by BREAKER_SYNC_API_INTERNAL_HOST (default 127.0.0.1) and BREAKER_SYNC_API_INTERNAL_PORT (default 8081). It carries only the two token-check routes that whisper-server calls, never the public listener or the VPN interface. Its service key is made on the first start in the file named by BREAKER_SYNC_API_SERVICE_KEY_FILE (default: service-key next to the database file); delete that file and start again to rotate the key. Transport: Breaker serves plain HTTP on both listeners; TLS is the admin's choice through a reverse proxy in front of it (ADR-034). A refused start names the variable or the path and never prints a key or a value. The card of this module has the full rules.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
