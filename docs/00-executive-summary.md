# 00 — Executive Summary

**Breaker** is an open-source, privacy-first dictation system that replaces the
paid Whisper Flow — a **phone app + self-hosted server** that turns speech into
Whisper Flow-style formatted text, with CB-slang voice control.

**Server-first (primary):** transcription AND formatting run on Local Server — the
user's already-operational CPU Whisper + LLM pipeline — over ZeroTier VPN. The
app talks to Breaker's own `whisper-server` container (async job queue), which
forwards audio to the admin-configured transcription service — by default the
existing Whisper X container, which Breaker does not modify.

**On-device (fallback):** when the server is unreachable, sherpa-onnx
transcribes locally with a rule-based formatter — dictation never stops, and
everything syncs to the server when connectivity returns.

**Voice control:** "Breaker Breaker" wakes + auto-records; "And I'm Gone" sends.
Per-user trained KWS models (recorded in your own environment) are the upgrade
path. Shake and tap remain the manual fallbacks.

**Multi-user + web:** every user on the VPN gets an isolated account (first
account registered = **admin**). Every transcription syncs to the server —
encrypted at rest, so a passive admin browsing the database sees ciphertext,
not another user's notes — and is viewable/searchable/copyable from the web
front end on any device. That is the encryption-at-rest guarantee, not a
claim that no admin action or server compromise could ever expose a note;
see ADR-006 and ADR-018 for the limits that come with an admin's actual
capabilities (a forced account reset, control of the web FE's served code).

**Distribution:** the server hosts the Android APK, the CA certificate, and the
on-device model. Updates check **once a day** for tagged releases from the
user's local GIT server, with an admin force-check button and signed-APK
verification.

**The look:** a **Trucking** design language — white/black/green, light + dark
modes, CB mic motif with a digital Cobra-style LED bar meter for state. The web
FE and Android app share one token set and look identical.

**Security (Security Review, two audits):**
- Base repo: **clean bill of health** with 4 fixes (no silent cloud fallthrough,
  delete the in-app updater, change package ID, pin Gradle SHA-256) + NOTICE
  file (licensing) [1].
- sherpa-onnx: **safe with 3 mitigations** — pin models to immutable release-asset ids + verify upstream checksum.txt; track decoder bug #3983; keep sherpa-onnx
  off the server's network-exposed path [2].
- Honest caveat: the forked base app has not passed its build and smoke test
  yet — Phase 0 is that gate [1][2].

**Key decisions (see 05):** Kotlin/native Android · swept OpenWhispr fork ·
sherpa-onnx on-device · reuse the existing server pipeline · server-primary with
local fallback · accessibility-service text insert with clipboard fallback · LLM + rule-based
formatting · FIFO transcription queue · encryption by default (per-user DEK,
password-wrapped) · admin-configurable transcription service · agent tokens ·
3-month retention · Trucking UI.

**Read next:** `01-requirements.md` → `02-system-overview.md` →
`ARCHITECTURE.md` → `07-ui-ux-look-and-feel.md`.
