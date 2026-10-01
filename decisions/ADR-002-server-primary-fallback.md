# ADR-002: Server-primary transcription with automatic local fallback

**Status:** accepted, amended 2026-09-30 (audio queue: the whole clip is
recorded before routing; no 5 s buffer)
**Date:** 2026-09-29

## Context
Local Server's CPU Whisper + LLM pipeline is already operational over ZeroTier,
but mobile networks are flaky. The base repo silently fell through
to cloud when no local model existed — a behavior we must not inherit [1].

## Decision
Default mode is **server-primary**: probe Local Server (1.5 s timeout, TTL-cached
30 s); reachable → server transcription + LLM formatting; unreachable →
on-device sherpa-onnx + rule-based formatting. No silent cloud fallthrough,
ever [1]. Audio is never dropped across a mode change: the whole clip is
recorded before the probe's answer routes it, so no audio waits on a probe.
(Amended: an earlier version buffered at most 5 s while a probe was in
flight. The implemented `core.DictateUseCase` probes only after capture, so
that buffer had nothing to hold, and a 5 s cap could only have dropped
audio.)

## Reasons
- Server path is higher quality (LLM formatting); local path keeps dictation
  alive offline. The user runs plugged in, so battery is a non-issue.

## Consequences
Rules in: per-dictation probe, no audio dropped across a mode change, clear
`LOCAL_MODEL_MISSING` error
instead of a cloud attempt [1]. Rules out: cloud fallthrough, blocking on the
server.
