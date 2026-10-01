# ADR-008: Admin-configurable transcription service

**Status:** accepted
**Date:** 2026-09-29

## Context
Every self-hoster has a different setup (Docker network, IP, external URL).
The endpoint must not be hardcoded.

## Decision
Admins configure the transcription service endpoint in the web FE (name, base
URL, optional API key, enabled) with a **Test connection** button. The server
proxies audio to it; for transcription the Android app always talks to the
Breaker server, so transcription-service API keys never reach phones. Admin-only with URL validation (SSRF
guard).

## Reasons
- Keeps keys server-side, keeps the FIFO queue service-agnostic, and matches
  "each user's server is different."

## Consequences
Rules in: `/v1/admin/transcription-service`, `/v1/admin/test-service`,
last-write-wins multi-admin edits. Rules out: hardcoded endpoints, per-user
service config.
