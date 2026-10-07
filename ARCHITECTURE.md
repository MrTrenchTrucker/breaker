# Breaker — Master Architecture

**Status:** Draft v0.5 · **Date:** 2026-09-29 · **Base:** swept OpenWhispr Android fork (com.edib.openwhispr)

---

## Executive Summary

Breaker is a privacy-first, open-source Android dictation app that replaces
**Whisper Flow** — with Whisper Flow-style formatting, hands-free CB-slang voice
control, and a self-hosted web frontend.

**Server-first (primary):** transcription AND formatting run on **Local Server** — a
100% operational CPU Whisper + LLM pipeline — over **ZeroTier** VPN. The app points
at the existing **Whisper X** container. **On-device (fallback):** when the server
is unreachable, sherpa-onnx transcribes locally so dictation never stops.

**Hands-free voice control:** "Breaker Breaker" wakes + auto-records; "And I'm
Gone" sends. v1 uses streaming ASR + phrase matching; a per-user trained KWS
model (recorded in your own environment) is the upgrade path.

**Multi-user + sync + web:** every user on the VPN gets an isolated account.
Every transcription syncs to the server (even local-only mode) and is viewable,
searchable, and copyable from a **web frontend** on any device. The server also
hosts the Android APK, the CA certificate, and pulls the latest tagged release
from the user's local GIT server.

The codebase is a **modular monorepo** forked from the swept OpenWhispr Android
fork, rebuilt in **Kotlin / native Android**, with the Security Review's four security
fixes applied in Phase 0 [1]. Every module ships an `AGENTS.md` so the user's
agent lanes (Bug Hunt, Orchestration, Coding) can build each piece independently.

**Target device:** Samsung Galaxy S25 Ultra (16 GB RAM) — `small` model on-device,
`medium` possible. **Power:** high-power mode, phone plugged in (battery non-issue).

**Updates & admin:** the app checks **once a day** for newer tagged releases from
the local GIT server, with a **force-check button** for admins. The **first
account to register** on the web FE is **admin by default** and can assign other
admins.

---

## Table of Contents

1. [Requirements](#1-requirements)
2. [System Overview](#2-system-overview)
3. [Repository Layout](#3-repository-layout)
4. [Android App Architecture](#4-android-app-architecture)
5. [On-Device STT Engine](#5-on-device-stt-engine)
6. [Server Transcription Service](#6-server-transcription-service)
7. [Transport & Fallback](#7-transport--fallback)
8. [Gesture, Voice Phrases & Overlay](#8-gesture-voice-phrases--overlay)
9. [Formatting](#9-formatting)
10. [Text Commit (Accessibility Service + Clipboard)](#10-text-commit-accessibility-service--clipboard)
11. [History, Sync & Web Access](#11-history-sync--web-access)
12. [Multi-User & Auth](#12-multi-user--auth)
13. [Voice Phrase Training](#13-voice-phrase-training)
14. [Distribution: APK, Certificates & Sideload](#14-distribution-apk-certificates--sideload)
15. [Deployment & Git](#15-deployment--git)
16. [Security & Threat Model](#16-security--threat-model)
17. [Build Order for Agents](#17-build-order-for-agents)
18. [Decisions & Risks](#18-decisions--risks)

---

## 1. Requirements

**Functional**
- F1. Dictate speech → **formatted** text. Server path is primary; on-device is fallback.
- F2. Server-first: when Local Server is reachable over ZeroTier, transcribe + format there.
- F3. On-device fallback: when the server is unreachable, transcribe locally (with
      rule-based formatting) so dictation never stops.
- F4. **"Breaker Breaker"** (voice phrase) OR shake → floating tile appears AND
      **recording starts automatically**.
- F5. **"And I'm Gone"** (voice phrase) OR tap tile → stop + send → commit text to
      focused field.
- F6. If no text field is focused, or the accessibility insert is refused →
      copy to clipboard, with a confirmation: our toast on Android 12 and
      below, the system's own copy confirmation on Android 13+.
- F7. In-app transcription history with one-tap copy.
- F8. Whisper Flow-style formatting: numbered lists, punctuation, filler removal.
- F9. The send phrase "And I'm Gone" is excluded from the transcription (audio
      trimmed at phrase onset).
- F10. Settings: mode, model size, server URL/API key, voice phrases on/off,
      shake on/off, formatting on/off, tile position, language.
- F11. On startup, the app prompts for ALL permissions it needs at once (mic,
      sensor, overlay/foreground service, notifications) with clear explanations.
- F12. High-power mode: the app detects Android power-saving state and prompts
      the user to enable high-performance mode.
- F13. **Sync:** every transcription syncs to the server — even local-only mode —
      pushed when connectivity returns. No data loss on offline dictation.
- F14. **Multi-user:** username/password accounts; each user's data (transcriptions,
      trained phrases, settings) is isolated.
- F15. **Web FE:** a website on Local Server where a user logs in and views, searches,
      and copies their transcriptions from any device on the VPN.
- F16. **Voice phrase training:** in-app flow to record the wake/end phrases in the
      user's environment; the server trains a per-user model; the phone downloads it.
- F17. **APK hosting:** the server serves the Android APK so users can download and
      sideload it easily.
- F18. **Certificates:** self-hosted CA cert served for one-time install so HTTPS
      to the server is trusted (no cert warnings).
- F19. **Deploy:** the Breaker server stack pulls the latest tagged version from the
      user's local GIT server.
- F20. **Daily update check:** the app checks once a day for a newer tagged release
      (from the local GIT server) and notifies the user when one is available.
- F21. **Force check (admin):** the first registered user is admin and has a
      force-check button that triggers an immediate update check.
- F22. **Registration + roles:** the web FE shows a registration card on first
      login; the **first account registered is admin by default**; admins can
      assign other admins.
- F23. **FIFO transcription queue:** the server queues transcription jobs
      first-in-first-out — concurrent requests never hammer the CPU Whisper
      pipeline; one job at a time, order preserved, restart-safe.
- F24. **Encryption by default:** transcriptions are encrypted at rest with a
      per-user key; the server stores ciphertext only and cannot read any
      user's notes in plaintext.
- F25. **Theming:** the web FE and app share a **Trucking** design language —
      white/black/green palette, **light + dark modes**, shared theme tokens
      (supersedes the earlier CB Radio amber palette).
- F26. **Admin-configurable transcription service:** admins configure the
      transcription service endpoint (Docker network, IP, or external URL) from
      the web FE, with a connectivity test. The server proxies audio to it, so
      service API keys never reach phones.
- F27. **Agent tokens:** admins generate scoped API tokens for AI agents; agents
      transcribe through the same FIFO queue and read their own result via
      `GET /v1/jobs/{job_id}`, same as any caller (ADR-015). Separately, when
      the owner already has a box keypair, the server seals a copy of the
      result to the owner's public key (ADR-018) and queues it in the owner's
      account, so it syncs in as an ordinary transcription the next time the
      owner **logs in** on any device (not merely "online" — conversion
      happens at login, and only the device that logs in first converts it).
      No Android app needed.
- F28. **Data retention:** transcriptions auto-delete after **3 months**; audio
      files delete immediately once transcription returns (success or final
      failure); failed transcriptions auto-retry **3× at 10 s intervals**.
- F29. **Admin store management:** the admin panel can clear the transcription
      store per-user or for all users.
- F30. **Model hosting:** the Breaker container hosts the on-device model
      (licensing permitting) for download by the phone app.
- F31. **Agent admin capability:** agents holding admin-scoped tokens can reset
      user passwords (admin-only endpoint).
- F32. **Log policy:** logs record events per-user only — never plaintext
      transcriptions.
- F33. **Account management:** every user can log out or delete their own
      account from the FE; the **last remaining admin cannot delete their
      account or be demoted** (prevents admin lockout).
- F34. **User transcription management:** each user can delete their own
      transcriptions one-by-one or via a delete-all button, on the FE.
- F35. **Responsive web FE:** the container website renders landscape and
      portrait layouts based on device size/resolution (mobile-first
      breakpoints), matching the Android app's look.
- F36. **State indicators:** the CB mic glyph + LED bar meter show transmission
      state — **green** = sent (copy confirmed), **orange** = server failed →
      local fallback, **red** = complete failure; the LED bar (digital
      Cobra-style segment display) fills above the floating mic while recording.

**Non-functional**
- N1. Dictation starts < 1 s after wake phrase (models preloaded where possible).
- N2. No telemetry; the only network calls are to Local Server.
- N3. Model files **pinned to immutable release-asset ids** and verified against
      upstream's published checksum.txt (SHA-256) before use [2].
- N4. Battery-friendly: sensor + overlay listeners idle when not dictating.
- N5. Android 11+ (min SDK 30), arm64-v8a primary target.
- N6. Audio never leaves the device in local/fallback mode (except user-initiated sync).
- N7. Server API is OpenAI-shaped but **asynchronous**: `POST /v1/audio/transcriptions`
      enqueues and returns `{ job_id, status: "queued" }`; the client polls
      `GET /v1/jobs/{job_id}` for the result. The endpoint lives on **Breaker's own
      `whisper-server` container**, which forwards audio to the admin-configured
      transcription service (default: the existing Whisper X container, which is
      not modified).
- N8. No silent cloud fallthrough: local mode without a model errors clearly
      (Security Review fix #1).
- N9. Formatting must be **non-destructive**: same meaning, only reformatted.
- N10. Voice-phrase false positives measured on device (target < 1/day).
- N11. High-power mode expected: the app verifies power-saving is off and warns if not.
- N12. Sync is idempotent (retries safe; no duplicate transcriptions).
- N13. Multi-user isolation enforced server-side (no cross-user reads).

## 2. System Overview

```
┌─────────────────────────────── PHONE (Android) ───────────────────────────────┐
│  phrases ──"Breaker Breaker"──▶ tile + auto-record                            │
│  phrases ──"And I'm Gone"──▶ core: stop, trim, send                           │
│  gesture ──shake──▶ tile · overlay ──tap──▶ record / tap──▶ send              │
│                                                                               │
│  audio ──pcm16/16k──▶ core DictateUseCase ──asks──▶ transport (probe)         │
│     ├─ server reachable ──▶ stt-server (Breaker whisper-server, via ZT) ──▶   │
│     │                        format (server LLM) ──formatted text──▶ commit   │
│     └─ server down ──▶ stt-ondevice (sherpa-onnx) ──raw text──▶               │
│                          format (rule-based) ──formatted text──▶ commit       │
│                                                                               │
│  commit ──accessibility insert──▶ focused field                               │
│       └── no field / refused ──▶ clipboard                                    │
│  history ◀── every transcription (text, source, timestamp)                    │
│  sync ──queue──▶ push to server when reachable (even local-only mode)         │
│  training-client ──record samples──▶ upload──▶ download trained model         │
│  auth-client ──login/register──▶ token for sync + training + STT              │
└────────────────────────────────────────────────────────────────────────────────┘
                              │  ZeroTier VPN (TLS via self-hosted CA)
                              ▼
┌─────────────────────────────── Local Server ──────────────────────────────────┐
│  whisper-server ── /v1/audio/transcriptions                                   │
│  sync-api ── /v1/sync (push/pull transcriptions) + /v1/auth (users, tokens)   │
│  web-fe ── Debian container: website (view/search/copy) + APK + cert hosting  │
│  training ── per-user voice phrase model training container                   │
│  deploy ── git pull (latest tagged from local GIT) · docker-compose · certs   │
└────────────────────────────────────────────────────────────────────────────────┘
```

## 3. Repository Layout

Fork the swept OpenWhispr Android fork (package `com.edib.openwhispr` → **new
package id**, Security Review fix #3) → rename `breaker/` [1]. Apply Security Review fixes in
Phase 0: delete the in-app updater (#2), change package id (#3), pin Gradle
SHA-256 (#4), add NOTICE file (licensing) [1].

```
breaker/
├── MODULES.md
├── ARCHITECTURE.md
├── docs/  (00 exec summary · 01 requirements · 02 system overview
│          03 security · 04 build order · 05 decisions & risks
│          06 sideload & install how-to)
├── android/
│   ├── AGENTS.md
│   ├── app/                    # Android entry, DI wiring, Gradle
│   ├── ui/                     # screens: dictation, history, settings, auth, training
│   └── modules/  (core, audio, stt-ondevice, stt-server, transport,
│                  format, phrases, gesture, overlay, commit, history,
│                  settings, sync, auth-client, training-client, updater,
│                  crypto)
├── server/
│   ├── AGENTS.md
│   └── modules/  (whisper-server · sync-api · web-fe · training · deploy)
└── shared/
    ├── AGENTS.md
    └── modules/  (api-contracts · model-registry · format-prompts · ui-tokens)
```

## 4. Android App Architecture

**Stack:** Kotlin + native Android (base repo is Kotlin/Android already). Gradle +
Android SDK. **Gradle distribution SHA-256 pinned** (Security Review fix #4) [1].

**Layering (hexagonal):** `core` (pure Kotlin ports + use cases, no Android
imports) · `ui` (views) · `modules/*` (adapters).

**Threading:** ASR inference is blocking → it runs on a single-threaded coroutine
dispatcher, never the UI thread (`AGENTS.md` section 6). Audio capture on a separate high-priority thread.

## 5. On-Device STT Engine (fallback)

**Engine:** the base repo's **sherpa-onnx** (vendored from XIAOMI CORPORATION,
upstream Apache-2.0 — verified by Security Review; credited in NOTICE) [1].
- Already in the swept codebase → zero new supply-chain surface.
- whisper.cpp AAR remains a fallback only if we need features sherpa-onnx lacks.

**Models:** on-device ASR models carry **their own upstream terms** (outside the
Security Review audit) — record per-model license in the registry [1]. S25 Ultra (16 GB):
`small` default, `medium` possible. Download once → verify against upstream
checksum.txt + our SHA-256 → preload [2].

**Security Review's sherpa-onnx audit (2026):** safe to use with **three mitigations**
[2]. Clean of malware/backdoors/phone-home; the vendored Kotlin bindings match
upstream signatures exactly [2]. **Known bug:** GitHub issue #3983 — an OOB
write in the offline transducer greedy-search decoder, reachable via a tampered
.onnx (model metadata parses vocab_size with no upper bound) [2]. Mitigations,
all adopted: (1) treat every .onnx as untrusted — pin model URLs to immutable release-asset ids + verify against upstream checksum.txt [2]; (2) retain the
transducer path but track #3983 and adopt the upstream decoder fix when it lands
— revisit (beam-search / non-transducer models) only if the fix doesn't land in
a reasonable window [2]; (3) sherpa-onnx stays OFF Local Server's network-exposed
path (server transcription uses the existing Whisper X container) +
network-isolated containers [2]. Honest limits: nothing compiled/run; ONNX
Runtime + ONNX not individually audited [2].

## 6. Server Transcription Service (primary)

**Reality:** Local Server's pipeline is **already 100% operational** — CPU Whisper,
diarization, and an **LLM endpoint** (`/v1/chat/completions`). The Whisper X
container is not modified by Breaker; the app never talks to it directly.

**The one gap (Security Review):** there is no OpenAI-shaped, queued,
multi-user-safe transcription endpoint anywhere yet. Phase 4 closes it by
building **Breaker's own `whisper-server` container**, which exposes
`POST /v1/audio/transcriptions` (async job enqueue) + `GET /v1/jobs/{job_id}`
and forwards the audio to the admin-configured transcription service (default
target: the existing Whisper X container). This also serializes concurrent
callers, which is the whole reason F23 specifies a queue rather than a direct
pass-through: a single CPU-bound inference backend serving multiple user
accounts, plus any agent tokens (F27), needs one caller at a time, not a race.

**Formatting:** after transcription, the server path calls the **existing**
`/v1/chat/completions` LLM with a strict formatting prompt (temperature 0,
plain-text output, structured/JSON mode off) → formatted text.

**Transport:** HTTPS over ZeroTier, TLS + API key + user token. Short timeouts
(connect 1.5 s) so an unreachable server is found fast.

**FIFO transcription queue (F23):** the transcription service is CPU-bound, so
concurrent requests would contend. A queue serializes jobs:
1. `POST /v1/audio/transcriptions` → enqueue → `{ job_id, status: "queued" }`.
2. A single worker processes one job at a time (strict FIFO, fair across users),
   calling the existing CPU Whisper pipeline.
3. `GET /v1/jobs/{job_id}` → `{ status: queued|processing|done|failed, result }`.
4. The Android client polls until `done`, then commits the text.
Jobs are persisted (SQLite) so a server restart doesn't lose queued audio. No
priority in v1 — strict FIFO. Timeouts + failure states surface in the app
("queued…", "transcribing…").

**Retry + audio lifecycle (F28):** a failed transcription job auto-retries
**3× at 10 s intervals** before failing; the queued audio is held in the job
store only while the job is queued or running and is erased when the job
finishes (success or final failure) — audio never outlives its job on the
server (ADR-010).

**Configurable transcription service (F26):** the transcription endpoint is
**not hardcoded** — admins configure it in the web FE (name, base URL, optional
API key, enabled). It can be a container on the same Docker network, a service
reachable by IP, or an external URL. The admin panel includes a **connectivity
test** that probes the endpoint and reports reachable/unreachable. The queue
forwards jobs to the configured service; for transcription the Android app
always talks to the Breaker server, so transcription-service API keys never
reach phones and the FIFO queue works
regardless of which service is configured. Audio format: **WAV 16 kHz mono PCM**
— native to Whisper/WhisperX (MP3 would only add a lossy decode step).

## 7. Transport & Fallback

**Default mode: SERVER-PRIMARY.** Probe Local Server (TCP connect, 1.5 s timeout,
TTL-cached 30 s) → reachable = server; unreachable = on-device fallback.
Re-probe on demand.

**Modes:** `server-primary` (default) / `local` / `server`. Local without a model
→ clear `LOCAL_MODEL_MISSING` error, no server attempt (Security Review fix #1) [1].

**Audio queue:** the app records the whole clip before the probe's answer
routes it, so no audio waits on a probe and none is dropped (ADR-002).

## 8. Gesture, Voice Phrases & Overlay

**Gesture (shake-to-wake):** `SensorManager` accelerometer; shake = high-pass
filtered magnitude threshold crossings in a 500 ms window.

**Voice phrases (CB slang):**
- **v1 mechanism — streaming on-device ASR + phrase matching.** sherpa-onnx ASR in
  streaming mode matches "Breaker Breaker" / "And I'm Gone" in the text stream.
  No training, no new dependency.
- **Upgrade path — per-user trained KWS model** (see section 13): the training
  container fine-tunes a small KWS model from the user's own recordings.
- "Breaker Breaker" (idle) → tile + auto-record (F4). "And I'm Gone" (recording)
  → stop + **trim audio at phrase onset** (F9) + dispatch (F5).
- **Permissions (F11):** mic, sensor, overlay, notifications requested
  together at first startup. The accessibility service isn't part of that
  runtime prompt — it has no system dialog to request; onboarding leads the
  user to its Settings toggle instead (ADR-022). **High-power mode (F12):**
  verify power-saving off.

**Overlay (floating tile):** `WindowManager` + `TYPE_APPLICATION_OVERLAY` with `FLAG_NOT_FOCUSABLE` (Android 11+). Tile is **tap-only**; tapping it starts dictation in place, on the tile — no Activity opens, so the app the user is typing in keeps focus and the user's own keyboard stays up (ADR-022). The tile IS the **CB mic glyph** — tap to talk. While recording, a digital Cobra-style **LED bar meter** fills directly above it, with a small cancel control; tap again (or the send phrase) to send (F36). Permission set: `SYSTEM_ALERT_WINDOW` (tile), mic, internet, foreground service, notifications (F11), plus the accessibility service — a Settings toggle onboarding leads the user to, not a runtime prompt (ADR-022) [1].

## 9. Formatting

**Whisper Flow-style formatting (F8):** raw dictation → clean structured text.
- **Server path:** existing LLM (`/v1/chat/completions`) with a strict
  non-destructive prompt (temperature 0, plain-text output — structured/JSON
  mode off, per shared/format-prompts). Prompt in
  `shared/format-prompts`.
- **Local path:** deterministic rule-based formatter (regex grammar for
  enumerations, casing, filler removal).
- Golden test: "I have 3 things... one is file a, two is file b, three is file c"
  → numbered list. Diff-check test enforces N9.

## 10. Text Commit (Accessibility Service + Clipboard)

**CommitService** — *text commit*, not "injection."
1. Focused editable field found → the accessibility service (`AccessibilityService`)
   inserts the text (`AccessibilityNodeInfo.ACTION_SET_TEXT` merging with the
   existing text at the cursor, or `ACTION_PASTE` from the clipboard).
   `ACTION_SET_TEXT` replaces the node's whole text, so the merge is the
   service's own job: read the current text + selection, build the new text,
   set it, then place the cursor after the inserted text.
2. No focused field, or the insert is refused → clipboard copy, with a
   confirmation: our toast on Android 12 and below, the system's own copy
   confirmation on Android 13+.
3. Optional preview before send.

## 11. History, Sync & Web Access

**History (Android):** SQLite through the history module's own adapter on the platform API (no Room). Rows: id, text, raw_text, source
(`local`|`server`), model, duration_ms, created_at, audio_path (optional). What
is still to push lives in the sync module's own queue, not on history's rows.
Tap-to-copy, search, delete.

**Sync (F13):** a sync queue pushes pending transcriptions to the server whenever
it is reachable — **even if dictation ran in local-only mode**. Idempotent
(N12): the server dedupes on the pair (client id, `key_version`) — a repeat of
the same pair is a no-op, and a re-key's higher `key_version` at the same
client id atomically replaces the stored record rather than being treated as
a duplicate push (ADR-006). No data loss on offline dictation. Auth via user
token (section 12).

**Web FE (F15):** a Debian container website where the user logs in and views /
searches / copies their transcriptions from any device on the VPN. Data is
pulled from the same sync store. This makes "copy what I said earlier" easy from
a computer — no phone needed. Decryption happens **in the browser** (client-side
JS) with a key derived from the user's password — the server stores no
plaintext and never receives the password or the KEK over the wire (F24,
ADR-006). That is not the same as "cannot decrypt under any circumstance",
and for the web FE specifically it is not the same as "a compromised server
can't get the password either" — that JS is **served by this same server on
every login**, so a compromised server can serve a version that captures the
password directly; see §12 and ADR-006 for the honest guarantee, which draws
that line explicitly. Queued audio, and a completed job's result until it is
fetched or 24 hours old, persist in `whisper-server`'s SQLite store (ADR-010
for the audio; ADR-018 for the result) — neither is "server memory only";
see ADR-018 for the full at-rest statement.

**Theming (F25):** the web FE and app share a **Trucking** design language —
white/black/green, modern but not futuristic, inspired by 18-wheeler door
logos. Light + dark modes. Shared theme tokens (CSS variables / plain Kotlin
values) live in `shared/ui-tokens`:
- **Light:** `bg #FFFFFF · surface #F4F6F4 · text #111417 · primary #1E7A46 ·
  danger #C0392B · trim black`
- **Dark:** `bg #0E1113 · surface #161B1E · text #F2F5F2 · primary #2E9E5B ·
  danger #E74C3C`
- **Type:** Anton/Oswald display (condensed, truck-lettering) · Inter body ·
  JetBrains Mono for IDs/timestamps
- **Responsive (F35):** mobile-first breakpoints; bottom nav on phones, sidebar
  on desktop; the Android app consumes the same tokens so both look identical.
- **CB mic motif + state colors (F36):** the CB mic glyph is the favicon, the
  floating tile, and the web FE's hero card (ComfyUI art: flat vector, white/green
  + black-outline variant). A digital Cobra-style **LED bar meter** (segments
  filling with audio level, directly above the floating mic on Android) shows
  state: **green** = sent (copy confirmed), **orange** = server failed → local
  fallback succeeded, **red** = complete failure. Full spec in `docs/07`.

**Retention (F28):** transcriptions auto-delete after **3 months** (server and
phone). The admin panel can clear the store per-user or for **all users** (F29).
Deletes propagate via tombstones so the phone and web FE stay consistent.

## 12. Multi-User & Auth

**Accounts (F14):** username/password registration (one-time). Any user on the
VPN can use Breaker. Each user gets an isolated namespace:
- Transcriptions (DB rows scoped by user_id)
- Trained phrase models (per-user files)
- Settings / API tokens

**Auth:** server issues a token (session/JWT) on login; the Android app stores it
in the Android Keystore (never plaintext). The web FE uses the same auth. All
server APIs (sync, training, STT) require a valid user token. **Isolation is
enforced server-side** (N13) — never trust client-side scoping.

**Onboarding:** register → download APK + install cert → log in → train phrases
→ grant permissions → enable high-power mode → GTG.

**Roles (F22):** two roles — `admin` and `user`. The **first account to register**
is **admin by default**. Admins can: assign/revoke admin on other accounts,
trigger an immediate update check on their own device (Settings → About → Check for updates — the same daily-poll endpoint, called on demand; this is not a push to other users' devices), and see server health. Users have
isolated data and no admin powers. Roles are stored server-side and enforced by
the API (N13).

**Encryption by default (F24):** every transcription is encrypted at rest with a
per-user key — the server stores **ciphertext only**, so a passive reader of
the server (an admin browsing rows, an agent with server access, a database
snapshot with no cracking attempt behind it) cannot read another user's notes
in plaintext.
- **Registration:** the **client** derives one Argon2id output from the
  user's password and a random salt, and HKDF-splits it (distinct labels)
  into a KEK — which never leaves the client — and an auth verifier, the
  only password-derived value the server ever receives. **Registration
  itself creates no DEK and no box keypair**; the server never generates the
  DEK, never derives the KEK, and never sees the password. The DEK and the
  ADR-018 box keypair are generated only at the first-login bootstrap below,
  which is a separate step because it ships in a later phase (Phase 19 vs.
  Phase 11 for registration itself — docs/04) — see ADR-006.
- **First login after registration (bootstrap, ADR-006):** the client
  generates a random 256-bit DEK, wraps it with the KEK it just derived,
  generates an X25519 box keypair, wraps the box private key with the
  (unwrapped) DEK — not the KEK — and uploads all three wrapped/plain values
  once. The same step also migrates any transcription the account synced
  before this bootstrap ran (see the Sync note below) from server-side
  plaintext to ciphertext.
- **Login (phone or web FE), once keys exist:** the client asks for the
  account's salt + KDF parameters, re-derives the same KEK + auth verifier
  locally, and sends only the auth verifier. On success the server returns a
  token plus the account's wrapped keys (`wrapped_dek`, and the ADR-018
  `wrapped_box_privkey` + `box_pubkey`); the client unwraps the DEK locally
  with the KEK and encrypts/decrypts transcriptions with AES-256-GCM.
- **The honest guarantee (ADR-006) — different for Android and the web
  FE:** the server stores no plaintext and never receives the password or
  the KEK over the wire. For **Android**, whose key-handling code ships
  inside a signed APK, that means whoever obtains the stored verifier hash
  or a wrapped DEK (a DB/backup breach, a log line) can only test password
  guesses **offline**, one Argon2id run per guess, at the account's stored
  KDF cost. For the **web FE**, the browser downloads that same key-handling
  JavaScript from the server on every login, so a compromised server (or
  anyone with write access to the web-fe container) can serve a version that
  sends the password, the KEK or the unwrapped DEK home directly — no
  guessing required. Protection scales with the password's strength times
  Argon2id cost only against a passive reader of the server; it says nothing
  against a server that can change what it serves. This repo ships no rate
  limiting or account-enumeration defense to make guessing expensive either
  way (auth hardening is explicitly out of scope; docs/01, docs/05).
- **Tradeoff:** a password change re-keys every time — new DEK, new box
  keypair, new `key_version` — and the client re-encrypts and replaces every
  record still below that version (ADR-006). That bounds, but does not
  erase, exposure from an old database/backup copy that still holds the
  previous wrapped DEK and salt: cracking the old password from that copy
  still yields whatever it protected as of that copy, but it stops yielding
  anything synced after the account's next password change, because that
  change generates keys the old copy never saw. The cost is real too: every
  password change re-encrypts all of the account's stored transcriptions
  (bounded by the 3-month retention). An admin/agent-scoped reset without the
  old password deletes the account's existing transcriptions outright rather
  than pretending to recover them, and — because password change always
  re-keys — the account's very next password change afterward rotates the
  DEK and box keypair the same way any other password change does, rather
  than needing a special case to avoid leaving whoever completed the reset
  with permanent access — see Admin capabilities below and ADR-018.

**Agent tokens (F27):** admins generate scoped API tokens (name, optional
expiry, owner account) in the web FE. Agents use the token to call the same
transcription API — `POST /v1/audio/transcriptions` + `GET /v1/jobs/{id}` —
through the same FIFO queue, and read their own result exactly like any other
caller (ADR-015's job contract is unchanged). Tokens are revocable; leakage is
a documented risk (R23). **Token scopes (D29):** `transcribe` (default) or
`admin`.
Separately, because an agent token holds no password and so cannot derive the
owner's KEK/DEK, `whisper-server` also seals a copy of the completed result to
the owner's box public key (ADR-018) and hands it to `sync-api`'s
pending-sealed store — **only once the owner has one**; an owner who has
never bootstrapped (or is mid-reset, ADR-018) simply gets no sealed copy,
and the agent still reads its own plaintext result normally. The owner's own
client unseals it on next login, re-encrypts it under the normal per-user
DEK, and uploads it through the ordinary sync path — that is how it "appears
in the web FE like any transcription." This protects a **stored** copy from
**read-only** exposure; it is not a defense against whoever controls the
running server or has database write access, who can also forge a sealed
row the owner's client will convert without review (ADR-018, T26).

**Admin capabilities (F31):** admins — including agents holding admin-scoped
tokens — can trigger an account reset (`POST /v1/admin/reset-account`). The
server deletes that account's keys, deletes its transcriptions outright (R20
— they cannot be decrypted by anyone once the keys are gone, so there is
nothing left to "recover"), deletes any still-unfetched pending-sealed rows
for it, revokes its tokens, and issues a one-time reset code (≥128 random
bits, single use, 24-hour expiry) for the admin to pass to the user out of
band. Whoever presents that code first to `POST /v1/auth/complete-reset`
chooses the new password and generates the fresh keys **on their own
device** — in the normal flow that is the user, and the admin does not set
the password, but the code itself cannot distinguish "the user, promptly"
from "the admin, before handing it over," so this repo does not claim the
admin can never be the one who completes it. What bounds that risk is not a
claim it can't happen: password change always re-keys (ADR-006) — there is
no lighter re-wrap path to carve a reset-specific exception out of — so the
account's very next password change, whichever one that is, generates a
**new** DEK and box keypair and re-encrypts whatever the account has synced
since the reset. Whoever held the reset-era keys loses access from that point
on only if the change is made by someone who does not pass the new password
back to them, in practice the user's own change; a change made by the admin
keeps the admin's access, and shows the user a "keys last changed" date they
don't recognise. "The old password stops working" fires on
every reset, legitimate or not, so it is not a signal a user can act on;
instead, the app and the web FE show "this account was reset on \<date\>" when
the account's `reset_at` is new to that device, and "keys last changed on
\<date\>" when its `rekeyed_at` is new, each on its own, because the server-side reset record is
something an admin can't quietly suppress the way a working old password
could imply "nothing happened." See ADR-018 for the full flow.

**Account management (F33):** any user can log out or delete their own account
from the FE (destructive: purges transcriptions, samples, tokens, and the
wrapped DEK — confirmed + type-to-confirm). The **last remaining admin cannot
delete their account or be demoted**; with multiple admins, an admin can be
demoted by another admin first, then delete their own account. Each user can
delete their own transcriptions one-by-one or with a delete-all button (F34);
deletes propagate via tombstones.

## 13. Voice Phrase Training

**Why server-side:** sherpa-onnx is inference-only — the phone can record but not
train a KWS model. The **training container** does the heavy lifting.

**Flow (F16):**
1. In-app "Train my phrases" flow: user records the wake phrase and end phrase
   (e.g., 10–20 samples each) in their current environment (car, home, noisy cafe).
2. Samples upload to the training container (with user token).
3. Training container fine-tunes a small per-user KWS model (sherpa-onnx KWS
   toolkit / k2-fsa training recipes).
4. Phone downloads the trained model, SHA-256 verified, and uses it for phrase
   detection (upgrade path in section 8). Until trained, streaming-match is used.

**Privacy:** voice samples are user-scoped and stored in the user's namespace;
deletable by the user.

**Compute (D26):** KWS fine-tuning is small — **CPU is the default path**;
confirm the model size in Phase 13. If GPU training is required, the training
container registers with the local inference engine and uses **Local Inference**
(FIFO multi-agent, Fast Lane priority) for GPU job management.

## 14. Distribution: APK, Certificates & Sideload

**APK hosting (F17):** the server serves the signed Android APK (built from the
latest tagged GIT release) so friends/family download it straight from the web FE.

**Certificates (F18):** the server serves the self-hosted CA certificate for
one-time install. Once installed on the phone, HTTPS to Local Server is trusted —
no "connection not private" warnings on the local IP or VPN.

**Sideload (Android):** the user enables "install unknown apps" once (Samsung:
Settings → Security & privacy → Install unknown apps → allow from this source),
then installs the APK. Honest note: installing the CA cert makes the *download*
trusted; Android still shows a standard "unknown app" prompt on first install of
a sideloaded APK — that's normal and expected for self-hosted apps.

**Full how-to:** `docs/06-sideload-and-install.md`.

**Update mechanism (F20, F21):** the app checks `GET /v1/updates/latest.json`
**once a day** (and on demand via the admin force-check button). The endpoint
reads the latest **tagged release from the user's local GIT server**. When a
newer version exists:
1. The app notifies the user ("Breaker 1.2.0 is available — update?").
2. On confirm, it downloads the **signed APK** over HTTPS (trusted via our CA).
3. It verifies the APK **signature + SHA-256** (published on the endpoint).
4. Android installs over the existing app — same package ID + signature →
   treated as an update, **data/settings preserved**.

This is Security Review fix #2 done right: the broken updater (pointed at the original
author's releases, plaintext signing key) is deleted [1], and the new one points
at OUR server with the signing key stored securely (never in git) [1].

**Model hosting (F30):** the web-fe also serves the on-device model + checksums
(licensing permitting) so the phone downloads it from the Breaker container
instead of upstream — consistent with "treat every .onnx as untrusted" [2].
**Update rollback (D28):** the updater keeps the previous APK; a rollback button
restores it if a release misbehaves.

## 15. Deployment & Git

**Deploy (F19):** the Breaker server stack (docker-compose: whisper-server,
sync-api, web-fe, training) **pulls the latest tagged version from the user's
local GIT server** on deploy. Tagged releases → build → serve. Rollback = deploy
previous tag.

**Containers:** each service is a container; the web-fe is a **Debian-based**
container. All bind to the ZeroTier interface only. TLS via the self-hosted CA.
API keys + user tokens never in git.

**APK signing:** the deploy pipeline signs each tagged release's APK with a
dedicated **APK signing key** (generated once, stored in a secrets store — never
in git, unlike the base repo's plaintext cert [1]). The public key + SHA-256 are
served on `/v1/updates/latest.json` for client verification.

**Update endpoint:** `sync-api` exposes `GET /v1/updates/latest.json` (sole registered owner per modules.toml/its own AGENTS.md — both `updater` and `web-fe`'s cards explicitly disclaim owning it), which resolves the latest GIT tag and returns version, APK URL, SHA-256, and signature.

## 16. Security & Threat Model

**Security Review verdict (swept 2026):** base is safe to build on — no malware,
obfuscation, backdoor, hidden exfiltration, or prompt-injection; permissions
minimal; no hardcoded secrets; recordings app-private; provenance two real
developers over 6.5 months [1].

**Four fixes (Phase 0):** (1) no silent cloud fallthrough [1], (2) delete the
in-app updater [1], (3) change package id [1], (4) pin Gradle SHA-256 [1].

**Licensing:** NOTICE file crediting XIAOMI CORPORATION (vendored sherpa-onnx,
upstream Apache-2.0) + upstream OpenWhispr authors; state modifications; keep
Apache-2.0 — cannot relicense a derivative closed [1]. Runtime ASR models carry
their own upstream terms [1].

**Caveat (Security Review):** code was never built or run — Phase 0 includes a build +
smoke-test gate [1].

**New attack surface (multi-user + web + sync + training):**
- Cross-user data access → server-side isolation (N13), token auth.
- Training samples exfiltration → user-scoped storage, TLS, token auth.
- Sync replay/duplication → idempotent sync (N12).
- Web FE XSS → standard web hardening (CSP, escaping).
- T17. **Key management / DEK compromise** — DEK wrapped by a client-derived
      KEK (Argon2id, ADR-006); server never generates or stores a usable key.
      A stolen DB/backup still lets an attacker guess the password offline at
      Argon2id cost — that is the honest limit of ADR-006, not a gap unique to this
      threat (F24).
- T18. **Queue abuse / DoS** — FIFO worker + per-user rate limits + job size caps.
- T19. **Agent token leakage** — scoped, revocable, rate-limited tokens (F27).
- T20. **SSRF via transcription service config** — admin-only config; URL
      validation on the connectivity test (F26).
- T21. **Tampered model / transducer OOB write (#3983)** — models pinned to
      immutable release-asset ids + upstream checksum.txt verified; track the
      upstream decoder fix [2].
- T22. **Plaintext leakage via logs** — log policy: events only, per-user, never
      plaintext transcriptions (F32).
- T23. **Store-clear abuse** — admin-only endpoints + confirmation; audited (F29).
- T25. **Offline password guessing (Android)** — the auth verifier hash and the wrapped
      DEK are both offline-guessable at one Argon2id run per guess (ADR-006
      parameters), for **Android**, whose key-handling code is in a signed
      APK the server cannot change. No rate limiting or account-enumeration
      defense is implemented: brute-force protection and session expiry are
      explicitly out of scope (docs/01, docs/05 — read as covering
      enumeration too, though neither list names it) and T18's per-user rate
      limits do not apply to unauthenticated auth endpoints. Mitigations: an
      enforced Argon2id floor (server- and client-side, ADR-006) and a
      12-character minimum password. A raw (unhashed) leaked verifier is a
      stronger threat than guessing — see T27.
- T26. **Sealed-box owner-key substitution** — anyone with database write
      access (an admin, or an agent given server access) could replace an
      account's `box_pubkey` and silently redirect future agent-token
      results, or insert a forged row directly into the pending-sealed store
      that the owner's own client converts into an ordinary-looking
      transcription with no review (ADR-018). No integrity binding exists on
      the stored key in v1, so the swap cannot be prevented, only detected:
      at the account's next login the client recomputes the box public key
      from the unwrapped private key and treats a mismatch with the stored
      `box_pubkey` as tampering (ADR-018) — results sealed to the swapped key
      before that login are already exposed, and a forged row is not detected
      at all. This is the same trust the server already holds,
      since it processes every server-routed transcription in plaintext
      during the job itself (on-device transcriptions never reach it) —
      ADR-018.
- T27. **Served-JS key capture (web FE only)** — the web FE's Argon2id/
      HKDF/AES-GCM JavaScript is served by this same server on every login,
      with no signed-artifact pinning the way the Android APK has. A
      compromised server, or anyone with write access to the web-fe
      container's files, can serve a version that sends the password, the
      KEK, or the unwrapped DEK home at the user's next browser login — no
      guessing needed. This does not apply to Android. No mitigation exists
      in v1 beyond the general "don't let the server get compromised" (ADR-006).
      A raw, unhashed leaked auth verifier is also a direct login credential
      (immediate session + wrapped keys, and the ability to overwrite them
      via change-password) — not merely offline-guessing bait (ADR-006).
- APK tampering in transit → HTTPS + SHA-256 of APK published on the FE.
- See `docs/03-security-threat-model.md` for the full table (T1–T16, T24–T27).

## 17. Build Order for Agents

See `docs/04-build-order.md`. Summary:

| Phase | Deliverable | Lane |
|-------|-------------|------|
| 0 | Fork + **4 Security Review fixes** + NOTICE + build/smoke-test gate | Bug Hunt |
| 1 | Repo skeleton, Gradle, `core` ports + use cases | Orchestration |
| 2 | `audio` + `settings` + `history` | Coding |
| 3 | `stt-ondevice` (sherpa-onnx local mode, model registry) | Coding |
| 4 | **whisper-server (async queue) + VPN wiring** — Breaker's own container adds `/v1/audio/transcriptions` (async enqueue) + `/v1/jobs/{id}` (poll), forwarding to the admin-configured service (default: existing Whisper X container, unmodified). Absorbs former Phase 18. | Coding + Bug Hunt |
| 5 | `stt-server` client + `transport` (connectivity probe; core routes) | Coding |
| 6 | `overlay` + `gesture` | Coding |
| 7 | `commit` (accessibility + clipboard) | Coding |
| 8 | `format` (server LLM + rule-based) | Coding |
| 9 | E2E test, bench, security review | Bug Hunt |
| 10 | `phrases` — streaming ASR + phrase matching | Coding |
| 11 | `auth` + `sync` (server sync-api + android sync/auth-client) — ships ADR-006's client-side key derivation (Argon2id + HKDF split) so the password never leaves the device from the first login | Coding |
| 12 | `web-fe` (Debian container website) | Coding |
| 13 | `training` container + `training-client` | Coding |
| 14 | APK hosting + cert serving + sideload docs | Coding + Bug Hunt |
| 15 | Git-pull deploy pipeline | Coding |
| 16 | `updater` (daily check + admin force-check) + `/v1/updates` + APK signing | Coding + Bug Hunt |
| 17 | FE registration + roles (registration card, first-account-is-admin, admin assignment) | Coding + Bug Hunt |
| 18 | *(absorbed into Phase 4 — see that row; kept as a numbered slot so Phase 19-22 references elsewhere don't shift.)* | — |
| 19 | `crypto` — DEK generation/wrap/unwrap, AES-256-GCM, and the ADR-018 X25519 box keypair + sealed-box unseal (Android + web FE); the `key_version` field and the atomic-replace path it drives on `POST /v1/sync` (ADR-006). Accounts with no keys yet (created in Phases 11–18, or just reset) get theirs at their next login, which also migrates any transcription they synced before this phase from server-side plaintext to ciphertext. `change-password` ships here too and re-keys on every call — new DEK, new box keypair, new `key_version` — with no lighter re-wrap path and no special case for a reset (ADR-006) | Coding + Bug Hunt |
| 20 | Admin config: transcription service (endpoint + connectivity test) + agent tokens + the owner's sealed copy of agent-token results (ADR-018) + admin account reset (`reset-account`, `complete-reset`) | Coding + Bug Hunt |
| 21 | Data lifecycle: retention TTL (3 months), audio delete-on-success, 3× retry @10 s, store clear (per-user/all), log policy | Coding + Bug Hunt |
| 22 | UI/UX: Trucking theme (white/black/green, light/dark), responsive FE (landscape/portrait), account management + user transcription delete screens | Coding |

## 18. Decisions & Risks

**Decisions:**
- D1. Kotlin / native Android (base is already Kotlin; accessibility service + overlay need native).
- D2. Base = swept OpenWhispr Android fork (com.edib.openwhispr) [1].
- D3. On-device engine = sherpa-onnx (in base, swept); whisper.cpp fallback only.
- D4. Server = reuse existing Local Server pipeline via a new **Breaker-owned
      `whisper-server` container** that adds the async `/v1/audio/transcriptions`
      job queue and forwards to it — Whisper X itself is not patched [1].
- D5. Accessibility-service text insert with clipboard fallback (supersedes IME-first, ADR-022).
- D6. Server-primary with automatic local fallback; no silent cloud fallthrough.
- D7. Formatting = server LLM + rule-based local, one `format` interface.
- D8. Voice control = two phrases in v1 (streaming ASR + phrase matching);
      per-user trained KWS model as upgrade path.
- D9. Wake phrase = "Breaker Breaker" (CB pairing; low false-positive risk).
- D10. Permissions + power explicit: all permissions at startup (F11); high-power
      mode verified (F12).
- D11. **Sync = offline-first queue, idempotent push** — no data loss, even in
      local-only mode.
- D12. **Training = server-side container** (phone records, server trains).
- D13. **Multi-user = isolated namespaces, server-enforced auth.**
- D14. **Web FE = Debian container** on Local Server; also hosts APK + CA cert.
- D15. **Deploy = git-pull of latest tagged release** from local GIT server.
- D16. **Updates = daily check + admin force-check** against `/v1/updates`, signed
      APK + SHA-256 verification, install-over preserves data. Rebuild of the
      deleted in-app updater [1], pointed at OUR server with a secured signing key.
- D17. **Roles = first-account-is-admin**; admins can assign other admins;
      server-enforced (N13).
- D18. **FIFO transcription queue** — single worker, job API, persisted jobs;
      strict FIFO, no priority in v1 (F23).
- D19. **Encryption by default, client-derived** — at registration the
      client (not the server) derives one Argon2id output from the password
      and HKDF-splits it into a KEK and an auth verifier (the only
      password-derived value the server ever sees); the DEK itself and the
      ADR-018 box keypair are generated only at the account's first-login
      key bootstrap (a later phase, ADR-006), not at registration.
      AES-256-GCM; server stores ciphertext only. Honest limit: offline
      password guessing at Argon2id cost is the guarantee for **Android**
      (signed APK); the web FE's key-handling JS is served by the server on
      every login, so a compromised server can capture the password or the
      DEK there directly (ADR-006). See ADR-018 for agent-token and
      queued-job results, which use a separate owner-keypair sealed-box step
      since agents never hold the owner's password (F24).
- D20. **Trucking theme** — white/black/green, light + dark modes, shared
      tokens in `shared/ui-tokens`; supersedes the CB Radio palette (F25).
- D30. **Account deletion rules** — any user can delete their own account; the
      **last admin cannot delete their account or be demoted** (F33).
- D31. **User transcription management** — delete single + delete-all (own
      transcriptions only), tombstone-synced (F34).
- D32. **Responsive web FE** — mobile-first breakpoints; landscape/portrait
      layouts; same tokens as the Android app (F35).
- D33. **CB mic motif + LED bar state indicators** — CB mic glyph as
      favicon/tile/hero (ComfyUI art); digital Cobra-style LED bar fills above
      the floating mic while recording; green = sent, orange = server-fail →
      local fallback, red = complete failure (F36).
- D21. **Configurable transcription service** — admin-configured endpoint
      (Docker/IP/external), server-side proxy, connectivity test (F26).
- D22. **Agent tokens** — admin-generated scoped API tokens; agents use the same
      FIFO queue; results to owner account (F27).
- D23. **Retain the sherpa-onnx transducer path (pinned + verified)** — models
      pinned to immutable release-asset ids + verified against upstream checksum.txt;
      adopt the #3983 fix when it lands; revisit only if it doesn't land in a
      reasonable window [2].
- D24. **Retention policy** — 3-month TTL on transcriptions; audio deleted on
      job completion; 3× retry at 10 s (F28).
- D25. **Model hosted in the Breaker container** — licensing permitting; checksums
      served alongside (F30).
- D26. **Training compute** — CPU default (confirm KWS model size); GPU via the
      local inference engine (Local Inference) if required.
- D27. **Log policy** — events only, per-user, never plaintext (F32).
- D28. **Update rollback** — previous APK retained; one-tap rollback.
- D29. **Agent token scopes** — `transcribe` (default) | `admin` (F31).

**Risks:**
- R1. Overlay focus limits on newer Android (tile is tap-only by design).
- R2. Sensor permission denied (fallback: always-visible tile).
- R3. On-device model accuracy vs speed (S25 Ultra 16 GB → small/medium fine).
- R4. ZeroTier flakiness on mobile networks (probe TTL + auto-fallback).
- R5. Accessibility-service permission UX friction, including the Android 13+
      "restricted setting" step for sideloaded apps (onboarding screen).
- R6. **Missing server endpoint** — server path blocked until `/v1/audio/transcriptions` [1].
- R7. **Base code never built/run** — Phase 0 gate [1].
- R8. ASR model upstream terms (outside Security Review audit) [1].
- R9. Local Server cert infra state UNKNOWN — verify before TLS changes.
- R10. **LLM formatting could hallucinate** — temperature 0, diff-check tests.
- R11. **High-power mode dependency** — verify + prompt (F12).
- R12. **Phrase-detection false positives** — target < 1/day; shake fallback.
- R13. **Multi-user isolation bugs** — server-enforced scoping + tests (N13).
- R14. **Sync conflicts/duplicates** — idempotent sync (N12).
- R15. **Training sample privacy** — user-scoped, deletable, TLS.
- R16. **Sideload friction for friends/family** — clear how-to (docs/06) + cert
      install + APK SHA-256 published.
- R17. **Update tampering / signing key compromise** — signing key in secrets
      store (never git [1]); APK signature + SHA-256 verified before install;
      key rotation procedure.
- R18. **Admin escalation / role abuse** — first-account-is-admin is a bootstrap
      rule; role changes audited server-side; users cannot self-promote.
- R19. **Queue backpressure / long waits under load** — job status UX
      ("queued…"), timeouts, per-user rate limits (T18).
- R20. **Admin/agent-scoped reset destroys old data by design** — the reset
      deletes the account's keys and transcriptions outright (ADR-018); the
      admin who completes it can take over the account until a password
      change is made by someone who does not pass the new password back to
      the admin, in practice the user's own change (every change re-keys,
      ADR-006, but one made by the admin keeps the admin's access). "The old password stops working" is NOT a signal the legitimate
      user can rely on to notice this — it fires on every reset, admin
      takeover or not; the honest signals are the "this account was reset on
      \<date\>" and "keys last changed on \<date\>" notices the app and web
      FE show instead, each when its date is new (ADR-006, ADR-018). No
      recovery-key option in v1 (F24).
- R21. **Client-side crypto complexity (Android + web FE)** — one shared spec
      (ADR-006, "Exact bytes") + test vectors; the vectors live in the crypto
      module's tests today and move to a shared file in api-contracts when the
      web FE's crypto is built (Phase 19).
- R22. **Misconfigured transcription endpoint** — connectivity test + clear
      errors; queue fails jobs with a visible status (F26).
- R23. **Agent token leakage** — scoped + revocable tokens, rate-limited (T19).
- R24. **sherpa-onnx transducer OOB write (issue #3983)** — pinned models +
      upstream checksum.txt close the delivery vector for a private deployment;
      adopt the upstream fix; revisit the transducer path if it doesn't land [2].
- R25. **Retention deletion surprises users** — 3-month TTL warned in UI; admin
      override via store management (F28/F29).
- R26. **Model licensing blocks hosting** — fallback: direct upstream download
      with pinned checksums [2].
- R27. **CPU training infeasible** — GPU via Local Inference (D26).
- R28. **Admin-scoped agent token leaked** — can trigger an account reset (wipes
      the account's keys + transcriptions, never sets the password itself);
      scoped tokens, revocation, audit (F31, T19, ADR-018).
- R29. **Account deletion data loss** — destructive + irreversible; confirmation
      + type-to-confirm + clear warning (F33).
- R30. **Last-admin lockout** — prevented by rule: last admin cannot delete or
      be demoted (F33).

**Deferred / out of scope (user decisions):**
- **Auth hardening** (brute-force protection, session expiry, refresh) — the
  VPN/ZeroTier perimeter is the auth layer for now; an auth server may come later.
- **Certificate lifecycle** — manual re-issue by the admin; out of scope for the
  Breaker app (the app uses certs, it doesn't manage them).
- **Formal backups** — multi-drive redundancy + device copies of transcriptions;
  no dedicated backup subsystem.
