# ADR-015: Async transcription job queue, hosted in Breaker's own whisper-server (not the Whisper X container)

**Status:** accepted
**Date:** 2026-09-30

## Context
Two spec-audit findings surfaced unresolved contradictions inside the docs themselves:
- **Shape:** N7 (ARCHITECTURE.md:157-158) and the api-contracts card (shared/modules/api-contracts/AGENTS.md:9-13) described a **synchronous** `POST /v1/audio/transcriptions` returning `{ text, segments, language }` directly in a 200. ADR-007, MODULES.md:75, and that same api-contracts card's own 'jobs' endpoint group (line 18) described an **asynchronous** job queue: enqueue → `{ job_id, status }` → poll `GET /v1/jobs/{job_id}`.
- **Location:** docs/04-build-order.md:12 and ARCHITECTURE.md:270-271 said the endpoint is added to the **existing Whisper X container**. MODULES.md:75, ADR-007, ADR-008, and ARCHITECTURE.md:300-301 said it lives in **Breaker's own `whisper-server` container**, forwarding to an admin-configured service. whisper-server's own AGENTS.md carried both readings in the same paragraph, and the same split recurred in docs/00, docs/02, server/AGENTS.md, MODULE_MAP.md, and several module cards.
- docs/01-requirements.md:106 rules out "building a new server STT service" — read narrowly, as ruling out a new transcription ENGINE (see Decision 4), not a queue/proxy in front of the existing one.

A queue design was already ratified on F23 (queue) and F26/F27 (admin-configurable service, agent tokens) grounds: a single CPU-bound inference backend cannot safely serve concurrent callers — multiple user accounts and any agent tokens (F27) alike — without one.

## Decision
1. **Shape:** the transcription contract is **async only**. There is no synchronous 200-with-body variant. `POST /v1/audio/transcriptions` always enqueues and returns `202 { job_id, status: "queued" }`; the caller always polls `GET /v1/jobs/{job_id}` for the result.
2. **Location:** the endpoint, the FIFO worker, and the job store live in **Breaker's own `server/modules/whisper-server` container** — a new container in Breaker's docker-compose, alongside sync-api/web-fe/training/deploy. It is not a patch to the Whisper X container's source, and the Whisper X container is not modified by this project.
3. **Forwarding:** whisper-server forwards each job's audio to the admin-configured downstream transcription service (F26/ADR-008). The default configured target is the existing Whisper X container over ZeroTier — unchanged, exactly as it runs today, and swappable per F26.
4. **Why a queue in front, not a patch inside:** F23 already specifies this queue for exactly this reason — serializing every caller (multiple Breaker users, plus any agent tokens) without Whisper X's own code needing to know Breaker exists. docs/01:106's "no new server STT service" is read narrowly, as "no new transcription engine" — whisper-server is glue and contention control, not a competing Whisper implementation.

## Reasons
- Matches the already-ratified F23/ADR-007/ADR-008 design.
- Keeps Whisper X's blast radius at zero — Breaker ships a new container it fully owns and tests, instead of a change against infrastructure Breaker doesn't own and that other, non-Breaker consumers also use.
- Already the majority reading in the docs: ADR-007, ADR-008, MODULES.md:75, and half of api-contracts' own card already described this design — N7, D4, docs/04 Phase 4/18, docs/01:106, ARCHITECTURE.md's own §17 phase table, docs/00's executive summary, server/AGENTS.md, MODULE_MAP.md, and several module AGENTS.md/README pairs needed to catch up.
- Resolves the two findings without touching F26/ADR-008 (admin-configurable service), which already assumed this shape.

## Consequences
- **Phase 4** is redefined to build the full whisper-server module (queue + job API + forwarding), not a sync patch to Whisper X. **Phase 18** ('FIFO worker + job API on whisper-server') is absorbed into Phase 4 and becomes an empty slot with a pointer, in BOTH copies of the phase table (docs/04 and ARCHITECTURE.md §17), so later phase numbers (19-22) don't shift.
- `android/modules/stt-server` polls instead of reading a body from the POST; `core.SttEngine`'s public shape (`SttResult`) is unchanged, so `transport` and everything above it needs no change.
- `shared/modules/api-contracts` keeps exactly one transcription contract (async); the sync variant is deleted from the spec, not just deprioritized.
- Out of scope for this ADR: how queued job results are protected at rest (agent tokens vs. encryption by default, F24/F27) — a separate decision.
