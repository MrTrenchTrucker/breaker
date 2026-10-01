# ADR-014: Multi-user isolation, server-enforced

**Status:** accepted
**Date:** 2026-09-29

## Context
Any user on the VPN can use Breaker; each has an isolated account. Data
isolation must not depend on client behavior.

## Decision
Every server query is scoped by the authenticated user_id; roles and tokens are
enforced server-side; the client never trusts its own claims. Transcriptions,
trained models, and settings live in per-user namespaces. Logs record events
per-user only — never plaintext transcriptions (F32).

## Reasons
- The user's stated requirement: agents browsing the server must not
  accidentally read another user's notes; encryption (ADR-006) backs this at
  the data layer.

## Consequences
Rules in: user-scoped queries, server-side role checks, event-only logging.
Rules out: client-trusted scoping, plaintext in logs.
