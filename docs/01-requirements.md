# 01 — Requirements

## Functional
- **F1** Dictate speech → **formatted** text (server path primary).
- **F2** Server-first: when Local Server is reachable over ZeroTier, transcribe + format there.
- **F3** On-device fallback: server unreachable → local transcription + rule-based
  formatting. Dictation never stops.
- **F4** **"Breaker Breaker"** (voice phrase) OR shake → floating tile appears AND
  **recording starts automatically**.
- **F5** **"And I'm Gone"** (voice phrase) OR tap tile → stop + send → commit text
  to focused field.
- **F6** If no text field is focused → copy to clipboard + toast.
- **F7** In-app transcription history with one-tap copy.
- **F8** Whisper Flow-style formatting: numbered lists, punctuation, filler removal.
- **F9** The send phrase "And I'm Gone" is **excluded** from the transcription
  (audio trimmed at phrase onset).
- **F10** Settings: mode, model size, server URL/API key, voice phrases on/off,
  shake on/off, formatting on/off, tile position, language.
- **F11** On startup, the app prompts for ALL permissions it needs at once (mic,
  sensor, overlay/foreground service, notifications).
- **F12** High-power mode: the app detects Android power-saving state and prompts
  the user to enable high-performance mode.
- **F13** **Sync:** every transcription syncs to the server — even local-only
  mode — pushed when connectivity returns. No data loss on offline dictation.
- **F14** **Multi-user:** username/password accounts; each user's data
  (transcriptions, trained phrases, settings) is isolated.
- **F15** **Web FE:** website on Local Server to view, search, and copy transcriptions
  from any device on the VPN.
- **F16** **Voice phrase training:** in-app flow to record wake/end phrases in the
  user's environment; the server trains a per-user model; the phone downloads it.
- **F17** **APK hosting:** the server serves the Android APK for download.
- **F18** **Certificates:** self-hosted CA cert served for one-time install so
  HTTPS to the server is trusted.
- **F19** **Deploy:** the Breaker server stack pulls the latest tagged version from
  the user's local GIT server.
- **F20** **Daily update check:** the app checks once a day for a newer tagged
  release (from the local GIT server) and notifies the user when one is available.
- **F21** **Force check (admin):** the first registered user is admin and has a
  force-check button that triggers an immediate update check.
- **F22** **Registration + roles:** the web FE shows a registration card on first
  login; the **first account registered is admin by default**; admins can assign
  other admins.
- **F23** **FIFO transcription queue:** the server queues transcription jobs
  first-in-first-out — one job at a time, order preserved, restart-safe.
- **F24** **Encryption by default:** transcriptions are encrypted at rest with a
  per-user key; the server stores ciphertext only and cannot read any user's
  notes in plaintext.
- **F25** **Theming:** the web FE and app share a **Trucking** design language —
  white/black/green palette, **light + dark modes**, shared theme tokens
  (supersedes the earlier CB Radio amber palette).
- **F26** **Admin-configurable transcription service:** admins configure the
  transcription service endpoint (Docker network, IP, or external URL) from the
  web FE, with a connectivity test. The server proxies audio to it, so service
  API keys never reach phones.
- **F27** **Agent tokens:** admins generate scoped API tokens for AI agents;
  agents transcribe through the same FIFO queue and read their own result via
  `GET /v1/jobs/{job_id}`, same as any caller (ADR-015). Separately, when the
  owner already has a box keypair, the server seals a copy of the result to
  the owner's public key (ADR-018) and queues it in the owner's account, so
  it syncs in as an ordinary transcription the next time the owner **logs
  in** on any device (not merely "online" — conversion happens at login, and
  only the device that logs in first converts it). No Android app needed.
- **F28** **Data retention:** transcriptions auto-delete after **3 months**;
  audio files delete immediately once transcription returns (success or final
  failure); failed transcriptions auto-retry **3× at 10 s intervals**.
- **F29** **Admin store management:** the admin panel can clear the transcription
  store per-user or for all users.
- **F30** **Model hosting:** the Breaker container hosts the on-device model
  (licensing permitting) for download by the phone app.
- **F31** **Agent admin capability:** agents holding admin-scoped tokens can
  trigger an account reset (admin-only endpoint). In the normal flow the user
  chooses their own new password on their own device; the admin does not set
  it — but whoever presents the one-time reset code first is the one who
  completes it, and ADR-018 states that risk and its mitigation honestly
  rather than treating "the user does it" as guaranteed.
- **F32** **Log policy:** logs record events per-user only — never plaintext
  transcriptions.
- **F33** **Account management:** every user can log out or delete their own
  account from the FE; the **last remaining admin cannot delete their account
  or be demoted** (prevents admin lockout).
- **F34** **User transcription management:** each user can delete their own
  transcriptions one-by-one or via a delete-all button, on the FE.
- **F35** **Responsive web FE:** the container website renders landscape and
  portrait layouts based on device size/resolution (mobile-first breakpoints),
  matching the Android app's look.
- **F36** **State indicators:** the CB mic glyph + LED bar meter show
  transmission state — **green** = sent (copy confirmed), **orange** = server
  failed → local fallback, **red** = complete failure; the LED bar (digital
  Cobra-style segment display) fills above the floating mic while recording.

## Non-functional
- **N1** Dictation starts < 1 s after wake phrase (models preloaded where possible).
- **N2** No telemetry; the only network calls are to Local Server.
- **N3** Model files **pinned to immutable release-asset ids** and verified against
  upstream's published checksum.txt (SHA-256) before use [2].
- **N4** Battery-friendly: sensor + overlay listeners idle when not dictating.
- **N5** Android 11+ (`setFloating`), arm64-v8a primary target.
- **N6** Audio never leaves the device in local/fallback mode (except user-initiated sync).
- **N7** Server API is OpenAI-shaped but **asynchronous**: `POST /v1/audio/transcriptions`
  enqueues and returns `{ job_id, status: "queued" }`; the client polls
  `GET /v1/jobs/{job_id}` for the result. The endpoint lives on **Breaker's own
  `whisper-server` container**, which forwards audio to the admin-configured
  transcription service (default: the existing Whisper X container, which is not
  modified).
- **N8** No silent cloud fallthrough: local mode without a model errors clearly
  (Security Review fix #1).
- **N9** Formatting is **non-destructive**: same meaning, only reformatted.
- **N10** Voice-phrase false positives measured on device (target < 1/day).
- **N11** High-power mode expected: the app verifies power-saving is off and warns if not.
- **N12** Sync is **idempotent** (retries safe; no duplicate transcriptions).
- **N13** Multi-user isolation enforced **server-side** (no cross-user reads).

## Target device
- Samsung Galaxy S25 Ultra, 16 GB RAM. On-device model: `small` default, `medium`
  possible. Power: high-power mode, phone plugged in (battery non-issue).

## Out of scope (v1)
- iOS port. · Speaker diarization on device. · Real-time streaming dictation.
- Building a new **transcription engine** (Whisper) — the Local Server pipeline
  already exists and is reused as-is. (Breaker DOES add its own `whisper-server`
  **queue/proxy** container in front of it — see ARCHITECTURE.md §6 — that is
  glue and contention control, not a new engine.)
- Grammar voice commands ("copy #27") — deferred to v1.2.
- Public internet exposure (VPN/ZeroTier only).
- Text-to-speech (TTS) — deferred to a later revision.
- Auth hardening (brute-force protection, session expiry) — the VPN/ZeroTier
  perimeter is the auth layer for now; an auth server may come later.
- Certificate lifecycle — manual re-issue by the admin; out of scope for the app.
- Formal backups — multi-drive redundancy + device copies of transcriptions.
