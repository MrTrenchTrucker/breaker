# Api Contracts — README

OpenAPI 3 spec for the transcription-job contract (slice 1) — the single contract
both the Android client (`stt-server`) and the server (`whisper-server`) implement.

**Status:** slice 1 of the module. The transcription-job contract is complete:
- `POST /v1/audio/transcriptions` — enqueue a transcription job
- `GET /v1/jobs/{job_id}` — poll job status and result
- `GET /health` — service health check

Full module card: `AGENTS.md` in this folder.
