# Api Contracts — README

OpenAPI 3 spec for the transcription-job contract (slice 1) — the single contract
both the Android client (`stt-server`) and the server (`whisper-server`) implement.

**Status:** the transcription-job and auth contracts, plus the sync group:
- `POST /v1/audio/transcriptions` — enqueue a transcription job
- `GET /v1/jobs/{job_id}` — poll job status and result
- `GET /health` — service health check
- `POST /v1/sync` — push a transcription (idempotent on the client_id /
  key_version pair)
- `GET /v1/transcriptions` — pull the account's transcriptions
- `GET /v1/sync/pending-sealed` — the owner's still-sealed agent-token results

Full module card: `AGENTS.md` in this folder.
