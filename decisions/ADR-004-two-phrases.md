# ADR-004: Voice control = two phrases ("Breaker Breaker" / "And I'm Gone")

**Status:** accepted
**Date:** 2026-09-29

## Context
Hands-free dictation needs a wake and a send trigger. "Hey Breaker" was
rejected: "hey" is a common speech word (false positives). The product voice is
CB slang.

## Decision
v1 uses streaming on-device ASR + phrase matching for **"Breaker Breaker"**
(wake + auto-record) and **"And I'm Gone"** (send + audio trim at phrase
onset). Upgrade path: a per-user trained KWS model (recorded in the user's
environment, trained server-side, downloaded to the phone). Shake + tap remain
manual fallbacks. Grammar commands ("copy #27") deferred to v1.2.

## Reasons
- Two identical distinctive tokens = low false-positive risk; the wake phrase
  IS the product name, said the CB way.

## Consequences
Rules in: audio trimmed at the send-phrase onset (the phrase never appears in
the text). Rules out: "Hey Breaker", grammar commands in v1.
