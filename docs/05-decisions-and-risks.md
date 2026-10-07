# 05 — Decisions & Risks

## Decisions (ADR-style)

**D1 — Kotlin / native Android.**
The base repo is already Kotlin/Android; IME (`InputMethodService`), overlay
(`WindowManager`), and sensor access need native APIs.

**D2 — Base = swept OpenWhispr Android fork (`com.edib.openwhispr`).**
Security Review gave a clean bill of health; Apache-2.0 permits the fork. Package id
changes in Phase 0 (fix #3).

**D3 — On-device engine = sherpa-onnx (already in the base).**
Vendored from XIAOMI CORPORATION, upstream verified Apache-2.0, swept clean.
Zero new supply-chain surface vs adding whisper.cpp. whisper.cpp AAR remains a
fallback if sherpa-onnx lacks a needed feature.

**D4 — Server = reuse the existing Local Server pipeline via Breaker's own
`whisper-server` container.**
The existing Whisper X pipeline (CPU Whisper, diarization) is reused as-is and
is NOT modified. The app never points at Whisper X directly — it talks to
Breaker's new `whisper-server` container, which async-queues jobs and forwards
them to the admin-configured downstream service (default: Whisper X). **The
only gap:** build that queue/forward container (Phase 4).

**D5 — IME-first text commit with clipboard fallback.**
Matches the user's UX spec exactly ("click to send; if it can't paste, copy to
clipboard"). Text commit, not "injection."

**D6 — Server-primary with automatic local fallback.**
Local Server is the primary transcription + formatting path; on-device is the
fallback for connectivity issues. Per-dictation probe (TTL-cached); the whole
clip is recorded before routing, so no audio is dropped (ADR-002).
No silent cloud fallthrough (fix #1).

**D7 — Formatting = server LLM + rule-based local, one `format` interface.**
Server path reuses the existing `/v1/chat/completions` LLM with a strict
non-destructive prompt (temperature 0, plain-text output — structured/JSON mode off). Local path uses a
deterministic regex grammar (enumerations, casing, filler). Swappable + testable.

**D8 — Voice control = two phrases in v1, streaming ASR + phrase matching.**
**"Breaker Breaker"** (wake + auto-record) and **"And I'm Gone"** (send + audio
trim at phrase onset). v1 mechanism: streaming on-device sherpa-onnx ASR with
phrase matching in the text stream — no training, no new dependency. **Upgrade
path:** per-user trained KWS model (server training container). Grammar commands
("copy #27") deferred to v1.2. Shake + tap remain the manual fallbacks.

**D9 — Wake phrase = "Breaker Breaker" (not "Hey Breaker").**
CB-slang pairing with "And I'm Gone"; two identical distinctive tokens = lower
false-positive risk than "Hey" (a common speech word). The wake phrase IS the
product name, said the CB way.

**D10 — Permissions + power are explicit.**
The app requests ALL permissions at startup (F11) and verifies high-power mode
(F12). Battery is a non-issue by design — the user runs plugged in.

**D11 — Sync = offline-first queue, idempotent push.**
Every transcription syncs to the server when reachable — even local-only mode.
Idempotent (N12): retries safe, no duplicates. No data loss on offline dictation.

**D12 — Training = server-side container.**
sherpa-onnx is inference-only; the phone records samples, the server trains a
per-user KWS model, the phone downloads it (F16).

**D13 — Multi-user = isolated namespaces, server-enforced auth.**
Each user's transcriptions, trained models, and settings are scoped by user_id
server-side (N13). Tokens in the Android Keystore.

**D14 — Web FE = Debian container** on Local Server; also hosts APK + CA cert.

**D15 — Deploy = git-pull of latest tagged release** from local GIT server.

**D16 — Updates = daily check + admin force-check, signed APK.**
The app polls `/v1/updates/latest.json` once a day (and on admin force-check),
verifies APK signature + SHA-256, and installs over the existing app (data
preserved). This is a proper rebuild of the deleted in-app updater [1] — pointed
at OUR server, signing key secured (never git) [1].

**D17 — Roles = first-account-is-admin.**
The first account to register on the web FE is admin by default and can assign
other admins. Server-enforced; no self-promotion (F22, T16).

**D18 — FIFO transcription queue.**
The server queues transcription jobs first-in-first-out: `POST
/v1/audio/transcriptions` enqueues and returns a job_id; a single worker
processes one job at a time; `GET /v1/jobs/{id}` returns status. Jobs persist in
SQLite (restart-safe). No priority in v1 — strict FIFO is fair across users (F23).

**D19 — Encryption by default (per-user DEK, client-derived, split
auth/KEK).**
At registration the **client** — not the server — derives one Argon2id
output from the password and a random salt, and HKDF-splits it (distinct
labels) into a KEK (never leaves the client) and an auth verifier — the only
password-derived value the server ever receives, at both register and login.
The DEK itself, and the ADR-018 box keypair, are generated only at the
account's first-login key bootstrap, a later phase (ADR-006) — not at
registration. Argon2id parameters (64 MiB, 3 iterations, parallelism 1) are
stored per account with a KDF version, enforced as a floor on both server
and client, so they can only be raised later at a password change, never
lowered. The server never generates the DEK, never derives the KEK, and
stores only the wrapped DEK + salt + a hash of the auth verifier. On login
the client re-derives both values locally and unwraps the DEK; transcriptions
encrypt with AES-256-GCM. The server stores ciphertext only (F24).

**The honest limit, stated once, and it differs by client:** for
**Android**, whose key-handling code ships in a signed APK, this protects
the server from ever holding a usable key, but whoever obtains the stored
verifier hash or a wrapped DEK — a DB/backup breach, a log line — can test
password guesses offline at Argon2id cost. For the **web FE**, the same
key-handling code is downloaded from the server on every login, so a
compromised server can serve a version that captures the password or the DEK
directly — no guessing needed (ADR-006). Protection (where it applies) is
the password's strength times Argon2id cost, not an absolute guarantee, and
this repo ships no rate limiting or account-enumeration defense (auth
hardening is explicitly out of scope, above). Password change re-keys every
time — a new DEK, a new box keypair, and a new `key_version` that the client
re-encrypts and replaces the account's own records up to — so it bounds, but
does not erase, exposure from an old database/backup copy that still holds a
previous wrapped DEK: that copy still yields whatever it protected as of
itself, but nothing synced after the account's next password change. The
cost is symmetric: every password change re-encrypts all of the account's
stored transcriptions (bounded by the 3-month retention). An admin/agent-scoped
reset without the old password deletes the account's keys and its existing
transcriptions outright — old data is lost either way, and whoever completes
the reset holds the fresh keys until the account's *next* password change,
which rotates the DEK the same way any other password change does, to bound
that exposure (R20). See ADR-006
(amended) and ADR-018 for the full login/change-password/reset flows, and
for agent-token and queued-job results, which use a separate owner-keypair
sealed-box step since agents never hold the owner's password.

**D20 — Trucking theme (supersedes the CB Radio palette).**
White/black/green, modern but not futuristic, inspired by 18-wheeler door
logos. Light + dark modes. Shared theme tokens in `shared/ui-tokens` (CSS
variables / plain Kotlin values) — one-file swap (F25).

**D21 — Configurable transcription service.**
The transcription endpoint is not hardcoded: admins configure it in the web FE
(Docker network, IP, or external URL) with a connectivity test. The server
proxies audio to it, so service API keys never reach phones and the FIFO queue
works regardless of which service is configured (F26).

**D22 — Agent tokens.**
Admins generate scoped API tokens (name, optional expiry, owner account) for AI
agents. Agents call the same transcription API through the same FIFO queue;
results sync to the owner's account and appear in the web FE. No Android app
needed. Tokens are revocable (F27, T19).

**D23 — Retain the sherpa-onnx transducer path (pinned + verified).**
Security Review's audit: safe with three mitigations [2]. Models pin to immutable release-asset ids and verify against upstream checksum.txt (closes the tampered-model
delivery vector); track decoder bug #3983 and adopt the upstream fix when it
lands; revisit (beam-search / non-transducer models) only if the fix doesn't
land in a reasonable window. sherpa-onnx stays off Local Server's network-exposed
path [2].

**D24 — Retention policy.**
Transcriptions auto-delete after 3 months (server + phone, tombstone-synced).
Audio files delete immediately once the job finishes (success or final
failure). Failed jobs auto-retry 3× at 10 s intervals (F28).

**D25 — Model hosted in the Breaker container.**
The web-fe serves the on-device model + checksums (licensing permitting) so the
phone downloads from the container instead of upstream — consistent with
treating every .onnx as untrusted [2]. Fallback: direct upstream download with
pinned checksums (R26).

**D26 — Training compute: CPU default, GPU via Local Inference if required.**
KWS fine-tuning is small — CPU is the default path (confirm model size in Phase
13). If GPU training is required, the training container registers with the
local inference engine and uses Local Inference (FIFO multi-agent, Fast Lane priority).

**D27 — Log policy.**
Logs record events per-user only — never plaintext transcriptions (F32, T22).

**D28 — Update rollback.**
The updater keeps the previous APK; a rollback button restores it if a release
misbehaves.

**D29 — Agent token scopes.**
`transcribe` (default) | `admin`. Admin-scoped tokens can trigger an account reset
(they never set or see the new password; ADR-018) (F31).

**D30 — Account deletion rules.**
Any user can delete their own account (destructive: purges transcriptions,
samples, tokens, wrapped DEK — confirmed + type-to-confirm). The last remaining
admin cannot delete their account or be demoted; with multiple admins, an admin
can be demoted by another admin first (F33).

**D31 — User transcription management.**
Each user can delete their own transcriptions one-by-one or with a delete-all
button on the FE; deletes propagate via tombstones (F34).

**D32 — Responsive web FE.**
Mobile-first breakpoints; landscape/portrait layouts by viewport; the Android
app consumes the same `shared/ui-tokens` so both look identical (F35).

**D33 — CB mic motif + LED bar state indicators.**
The CB mic glyph is the favicon, floating tile, and dictation hero (ComfyUI
art). A digital Cobra-style LED bar meter fills above the floating mic while
recording; state colors: **green** = sent (copy confirmed), **orange** = server
failed → local fallback, **red** = complete failure (F36).

## Risks

| # | Risk | Impact | Mitigation |
|---|------|--------|------------|
| R1 | Overlay focus limits on newer Android | Med | Tile is tap-only; dictation UI in a normal window |
| R2 | Sensor permission denied on some devices | Med | Fallback: always-visible tile toggle |
| R3 | On-device model accuracy vs speed | Low | S25 Ultra 16 GB → small/medium fine; size is a setting |
| R4 | ZeroTier flakiness on mobile networks | Med | Probe TTL + auto-fallback to local; never block on server |
| R5 | IME registration UX friction | Low | Onboarding screen linking to IME settings |
| R6 | **Missing server endpoint** — server path blocked until `/v1/audio/transcriptions` is added | High | Phase 4 first priority after Phase 0 |
| R7 | **Base code never built/run** — compilation + runtime unverified | High | Phase 0 build + smoke-test gate |
| R8 | ASR model upstream terms (outside Security Review audit) | Med | Per-model license recorded in model-registry |
| R9 | Local Server cert infra state UNKNOWN | High | Phase 4 starts with verification script before TLS changes |
| R10 | **LLM formatting could hallucinate** | Med | Temperature 0, plain-text output (structured/JSON mode off), non-destructive prompt, diff-check tests (N9) |
| R11 | **High-power mode dependency** — app must verify power-saving is off | Med | Detect + prompt at startup (F12); battery itself is a non-issue (plugged in) |
| R12 | **Phrase-detection false positives** ("Breaker Breaker" heard in normal speech) | Med | Target < 1/day on device; tune threshold/matching; shake remains reliable fallback |
| R13 | **Multi-user isolation bugs** | High | Server-enforced scoping (N13) + cross-user tests; never trust client claims |
| R14 | **Sync conflicts/duplicates** | Med | Idempotent sync (N12): (client id, key_version) pair dedupe |
| R15 | **Training sample privacy** | Med | User-scoped storage, TLS, token auth, user-deletable |
| R16 | **Sideload friction for friends/family** | Med | Clear how-to (docs/06) + cert install + APK SHA-256 published |
| R17 | **Update tampering / signing key compromise** | High | Signed APK + SHA-256 verify; key in secrets store (never git [1]); rotation procedure |
| R18 | **Admin escalation / role abuse** | Med | First-account-is-admin bootstrap; audited role changes; no self-promotion (T16) |
| R19 | **Queue backpressure / long waits under load** | Med | Job status UX ("queued…"), timeouts, per-user rate limits (T18) |
| R20 | **Admin/agent-scoped reset destroys old data by design** | Med | Reset deletes the account's keys, transcriptions and any pending-sealed rows outright (ADR-018); whoever completes it holds the fresh keys until a password change is made by someone who does not pass the new password back to them, in practice the user's own change (every change re-keys, ADR-006, but one made by the admin keeps the admin's access), which bounds the exposure rather than leaving it permanent; the account records the reset and each re-key, and shows "reset on \<date\>" and "keys last changed on \<date\>", each when its date is new to the device, since the old password no longer working happens on every reset and cannot be relied on as a signal; no recovery-key option in v1 (F24) |
| R21 | **Client-side crypto complexity (Android + web FE)** | Med | One shared spec (ADR-006, "Exact bytes") + test vectors; the vectors live in the crypto module's tests today and move to a shared file in api-contracts when the web FE's crypto is built (Phase 19) |
| R22 | **Misconfigured transcription endpoint** | Med | Connectivity test + clear errors; queue fails jobs with visible status (F26) |
| R23 | **Agent token leakage** | Med | Scoped + revocable tokens, rate-limited, optional expiry (T19) |
| R24 | **sherpa-onnx transducer OOB write (issue #3983)** | Med | Pinned models + upstream checksum.txt; adopt upstream fix; revisit transducer path if fix doesn't land [2] |
| R25 | **Retention deletion surprises users** | Low | 3-month TTL warned in UI; admin override via store management (F28/F29) |
| R26 | **Model licensing blocks hosting** | Med | Fallback: direct upstream download with pinned checksums [2] |
| R27 | **CPU training infeasible** | Low | GPU via Local Inference (D26) |
| R28 | **Admin-scoped agent token leaked** | Med | Scoped tokens, revocation, audit (F31, T19) |
| R29 | **Account deletion data loss** | Med | Destructive + irreversible; confirmation + type-to-confirm + clear warning (F33) |
| R30 | **Last-admin lockout** | Low | Prevented by rule: last admin cannot delete or be demoted (F33) |

## Deferred / out of scope (user decisions)
- **Auth hardening** (brute-force protection, session expiry, refresh) — the
  VPN/ZeroTier perimeter is the auth layer for now; an auth server may come later.
- **Certificate lifecycle** — manual re-issue by the admin; out of scope for the
  Breaker app (the app uses certs, it doesn't manage them).
- **Formal backups** — multi-drive redundancy + device copies of transcriptions;
  no dedicated backup subsystem.
