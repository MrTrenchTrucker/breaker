# sync-api — README

The server's main API: accounts and logins (users, tokens, roles), syncing transcriptions, and the app's update check. It keeps every user's data separate from everyone else's (N13), and it only ever stores encrypted transcriptions once an account has its keys (ADR-006).

## What is in the module today

- A health check at `GET /health`. It answers `{"status":"ok"}` and touches nothing else.
- The account store (SQLite): register an account, look up its salt, and check a login verifier. The auth verifier is kept only as a salted hash, and registration refuses key-derivation settings outside the allowed bounds.
- A versioned database schema, migrated to the current version when the server starts.

The account HTTP routes (register, salt, login) and everything else the card lists are not built yet.

## Run it

Set `BREAKER_SYNC_API_HOST` (default `127.0.0.1`), `BREAKER_SYNC_API_PORT` (default `8080`) and `BREAKER_SYNC_API_DB` (required: the path of the SQLite file), then start `main`. The entry point `main` is not covered by unit tests, because it needs a real socket; everything it calls is.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
