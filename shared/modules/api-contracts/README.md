# Api Contracts — README

OpenAPI 3 spec for the transcription-job contract (slice 1) — the single contract
both the Android client (`stt-server`) and the server (`whisper-server`) implement.

**Status:** the transcription-job and auth contracts, plus the sync group, the agent-token endpoints, and the admin group:
- `POST /v1/audio/transcriptions` — enqueue a transcription job
- `GET /v1/jobs/{job_id}` — poll job status and result
- `GET /health` — service health check
- `POST /v1/sync` — push a transcription (idempotent on the client_id /
  key_version pair; at key_version 0 the encrypted text is the base64 of the R1 body, see AGENTS.md)
- `GET /v1/transcriptions` — pull the account's transcriptions in pages (`since` cursor, at most 100 records or 4 MiB a page, delete records included)
- `GET /v1/sync/pending-sealed` — the owner's still-sealed agent-token results
- the four auth endpoints the Status line already names but never lists:
  - `POST /v1/auth/register` — register an account (never a password, never a DEK — the client derives the KEK and sends the verifier; first account = admin)
  - `POST /v1/auth/salt` — returns the salt and KDF parameters for an account (no decoy: an unknown username gets an honest "no such account")
  - `POST /v1/auth/login` — log in with username and auth_verifier; returns a session token and the account's wrapped keys (null together for a keyless account)
  - `POST /v1/auth/logout` — revoke the presented session token
- the sign-in hand-off, from the website to the app:
  - `POST /v1/auth/handoff/start` - the website, with a user session token, creates a one-time sign-in code (valid 5 minutes, usable once) and stores the sealed key package; an agent token is refused
  - `POST /v1/auth/handoff/exchange` - the app trades the code and its secret for its own session token and the sealed package (no token sent; one 401 refusal for every failure)
- the two new agent-token endpoints:
  - `POST /v1/admin/agent-tokens` — create an agent token (the token value is returned only here)
  - `GET /v1/admin/agent-tokens` — list the account's agent tokens (ids, names, owners, scopes, expiries - never the token values)
- the admin group:
  - `POST /v1/admin/roles` — grant or revoke an account's admin role
  - `GET /v1/admin/health` — the server's health and the released GIT tag (admin only)
  - `GET /v1/admin/transcription-service` / `PUT /v1/admin/transcription-service` — read or reconfigure the transcription service (the API key is never echoed; PUT is last-write-wins and the key is optional/write-only)
  - `POST /v1/admin/test-service` — probe the configured transcription service's connectivity (admin only)
  - `POST /v1/admin/reset-account` — reset an account: the server deletes its keys, transcriptions, unfetched pending-sealed rows and revokes its tokens, and issues a one-time reset code
  - `POST /v1/admin/clear-store` — clear the transcription store for one user or all users (confirm required)

Full module card: `AGENTS.md` in this folder.
