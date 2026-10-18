# Api Contracts — README

OpenAPI 3 spec for the transcription-job contract (slice 1) — the single contract
both the Android client (`stt-server`) and the server (`whisper-server`) implement.

**Status:** the transcription-job and auth contracts, plus the sync group and the agent-token endpoints:
- `POST /v1/audio/transcriptions` — enqueue a transcription job
- `GET /v1/jobs/{job_id}` — poll job status and result
- `GET /health` — service health check
- `POST /v1/sync` — push a transcription (idempotent on the client_id /
  key_version pair)
- `GET /v1/transcriptions` — pull the account's transcriptions
- `GET /v1/sync/pending-sealed` — the owner's still-sealed agent-token results
- the four auth endpoints the Status line already names but never lists:
  - `POST /v1/auth/register` — register an account (never a password, never a DEK — the client derives the KEK and sends the verifier; first account = admin)
  - `POST /v1/auth/salt` — returns the salt and KDF parameters for an account (no decoy: an unknown username gets an honest "no such account")
  - `POST /v1/auth/login` — log in with username and auth_verifier; returns a session token and the account's wrapped keys (null together for a keyless account)
  - `POST /v1/auth/logout` — revoke the presented session token
- the two new agent-token endpoints:
  - `POST /v1/admin/agent-tokens` — create an agent token (the token value is returned only here)
  - `GET /v1/admin/agent-tokens` — list the account's agent tokens (ids, names, owners, scopes, expiries - never the token values)

Full module card: `AGENTS.md` in this folder.
