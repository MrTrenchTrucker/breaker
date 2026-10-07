# 04 — Build Order for Agents

Phases are sequential; each ends with a testable increment. What may be
released to users is set by the gates below ("Gates and releases"), not by the
phase number. Agents build from `AGENTS.md` files in each module.

| Phase | Deliverable | Lane(s) | Exit criteria |
|-------|-------------|---------|---------------|
| 0 | **Fork + Security Review fixes + gate** — fork swept base, apply 4 fixes (no cloud fallthrough, delete updater, new package id, pin Gradle SHA-256), add NOTICE file, **build + smoke-test** (Security Review caveat: code never built) | Bug Hunt | Fork builds; fixes verified; NOTICE accurate; smoke test passes |
| 1 | **Skeleton + core** — Gradle project, app shell, `core` module (models, ports, use cases), DI wiring | Orchestration | `core` unit tests green; app launches to settings screen |
| 2 | **audio + settings + history** — mic capture w/ VAD, WAV encode; settings store; SQLite history | Coding | Record → WAV file; settings persist; history CRUD green |
| 3 | **stt-ondevice** — sherpa-onnx local mode, model download **pinned to immutable release-asset ids + verified against upstream checksum.txt** [2], transcribe; clear error when no model (fix #1) | Coding | Offline dictation E2E on device (F3, N1, N3, N8); tamper test (T21) |
| 4 | **whisper-server (async queue) + VPN wiring** — build Breaker's own `whisper-server` container: `POST /v1/audio/transcriptions` (enqueue) + `GET /v1/jobs/{job_id}` (poll), single FIFO worker forwarding audio to the admin-configured transcription service (default target: the existing Whisper X container, unmodified); TLS + API key; ZT bind check. **Absorbs former Phase 18** (see that row). | Coding + Bug Hunt | `curl` enqueue + poll works from phone over ZT; two concurrent jobs serialize FIFO; restart resumes the queue; port scan shows ZT-only bind |
| 5 | **stt-server + transport** — OpenAI-compatible client; **server-primary** probe + fallback | Coding | Server-primary: server when reachable, local when not (F1, F2, F3) |
| 6 | **overlay + gesture** — floating tile (WindowManager), shake-to-wake (accelerometer) | Coding | Shake → tile → tap → dictation UI (F4, F5) |
| 7 | **commit** — CommitService: IME commit + clipboard fallback + toast | Coding | Send → text in focused field; no field → clipboard (F5, F6) |
| 8 | **format** — server LLM formatter (via existing chat completions) + rule-based local formatter; non-destructive tests | Coding | Numbered-list example formats correctly (F8, N9); offline formatting works |
| 9 | **E2E + bench + security review** — full flow test, on-device model bench, threat-model checklist | Bug Hunt | All F/N requirements in scope for Phases 0–8 met; docs/03 checklist items tagged for that scope signed off (items tagged with a later phase sign off at their own phase — see docs/03 phase tags) |
| 10 | **phrases** — streaming ASR + phrase matching: "Breaker Breaker" wake + auto-record; "And I'm Gone" send + audio trim | Coding | Wake phrase starts recording; send phrase sends; phrase excluded from text (F4, F5, F9, N10) |
| 11 | **auth + sync** — server sync-api (register/login/tokens, sync) + android sync + auth-client **+ `android/modules/crypto`'s derivation slice** (`deriveKeys` only — Argon2id + HKDF split into KEK + auth verifier, ADR-006). The password never leaves the device from this phase on, even though the DEK/AES-256-GCM and the ADR-018 box keypair don't exist until Phase 19 — `change-password` and any other key-bearing endpoint a builder wires before Phase 19 has nothing to re-wrap yet, so they wait for it too | Coding | First account = admin; login works; sync pushes + idempotent (F13, F14, N12, N13) |
| 12 | **web-fe** — Debian container website: registration card, login, dashboard, admin panel | Coding | Register → first account admin; dashboard shows own transcriptions (F15, F22) |
| 13 | **training** — training container + training-client: record samples, upload, train, download per-user model | Coding | Trained model downloads + verifies; phrase detection improves (F16) |
| 14 | **APK hosting + cert serving + sideload docs** — APK + CA cert on web-fe; docs/06 | Coding + Bug Hunt | Download + install path works; cert install → HTTPS trusted (F17, F18) |
| 15 | **Git-pull deploy pipeline** — pull latest tagged release, build, sign, serve | Coding | Deploy from tag; rollback works (F19) |
| 16 | **updater + updates API + APK signing** — daily check + admin force-check; `/v1/updates/latest.json`; signed APK + SHA-256 verify; secure signing key | Coding + Bug Hunt | Daily check notifies; force-check works; tampered APK refused (T11); signing key not in git |
| 17 | **FE registration + roles polish** — registration card UX, first-account-is-admin, admin assignment | Coding + Bug Hunt | First account = admin; admins assign admins; no self-promotion (F22, T16) |
| 18 | **(absorbed into Phase 4 — see that row.)** Kept as a numbered slot so Phase 19-22 references elsewhere don't shift; no separate deliverable here. | — | — |
| 19 | **Encryption by default, part 2** — per-user DEK generation/wrap/unwrap (AES-256-GCM) + the ADR-018 X25519 box keypair and sealed-box unseal, client crypto (Android + web FE); the `key_version` field and `POST /v1/sync`'s atomic-replace path it drives (ADR-006). Any account still keyless (registered in Phases 11–18, or just reset) gets its DEK + keypair generated and uploaded at its next login (ADR-006 first-login bootstrap). **`change-password` ships here too, and re-keys on every call** — new DEK, new box keypair, new `key_version`, re-encrypt-and-replace of every record still below it (ADR-006) — there is no separate, lighter re-wrap path and no special case for a password change that happens to follow a reset | Coding + Bug Hunt | Ciphertext at rest; server can't decrypt without the password; re-login unwraps; keyless accounts bootstrap on next login; a password change always replaces the DEK and box keypair and advances `key_version`, re-encrypting the account's own records (F24, T17) |
| 20 | **Admin config** — transcription service endpoint + connectivity test (web FE + sync-api) + agent tokens + the owner's sealed copy of agent-token results (ADR-018: `whisper-server` seals, `sync-api` stores it and serves `GET /v1/sync/pending-sealed`, Android `sync` and the web FE convert it at login) + admin account reset (F31, ADR-018: `POST /v1/admin/reset-account`, `POST /v1/auth/complete-reset`) | Coding + Bug Hunt | Admin configures service; test reports reachable; agent token transcribes via queue; the owner's next login turns a sealed result into an ordinary transcription; a reset account logs in with its one-time code and gets fresh keys at that login, same as any keyless account (F26, F27, F31) |
| 21 | **Data lifecycle** — retention TTL (3 months), store clear (per-user/all), log policy; audio erased when a job finishes and the 3× retry @10 s are built with whisper-server's queue (Phase 4) and checked here | Coding + Bug Hunt | Old transcriptions purge; audio never outlives its job; retries succeed; logs have no plaintext (F28, F29, F32) |
| 22 | **UI/UX** — Trucking theme (white/black/green, light/dark), responsive FE (landscape/portrait), CB mic motif + LED bar state colors, account management + user transcription delete screens | Coding | FE + app share tokens; light/dark toggle; mobile layout on phones; state colors green/orange/red; delete-account rules hold (F25, F33–F36) |

## Gates and releases

Building is open before the gates: modules are built, reviewed and merged in
phase order. The gates decide what reaches users. When a gate's status
changes, update its row here and link the pull request or issue that holds the
evidence.

| Gate | What it requires | What it blocks | Status |
|------|------------------|----------------|--------|
| Phase 0 | The forked base app on `main` with Security Review's 4 fixes; NOTICE accurate; the base app built and smoke-tested | Any release (published APK or server image) | **Not passed.** NOTICE is in place; the forked base app is not on `main` yet. |
| Phase 9 | Every `docs/03-security-threat-model.md` checklist item tagged Phase 0–8 signed off; E2E, bench and security review done | Any release | **Not started.** |
| Phase 19 | Encryption by default (the Phase 19 row's exit criteria) | Releasing sync | **Not started.** |

**Sync is development-only until Phase 19.** Phases 11–18 build sync, but no
release build turns it on and no real user's data is synced before encryption
by default ships. The Phase 11–18 plaintext migration (ADR-006) therefore only
ever meets development data.

## Parallelization notes
- Phase 4 (whisper-server queue) is independent of the app — start immediately
  after Phase 0 by the Coding lane while Orchestration does Phase 1.
- Phases 2 and 3 can be developed in parallel after Phase 1.
- Phase 8 (format) can start as soon as Phase 4 lands (server LLM path) — the
  rule-based local formatter can start even earlier, after Phase 1.
- Phase 10 (phrases) can be spiked in parallel with Phase 6 (gesture) — same
  audio pipeline, complementary triggers.
- Phase 16 (updater) depends on Phase 15 (git-pull deploy) — the update endpoint
  reads GIT tags. Phase 17 (roles) depends on Phase 11 (auth).
- Phase 18 is absorbed into Phase 4 (no separate queue-wrapping step — Phase 4
  ships the queue directly; see the Phase 4 row).
- Phase 19 (encryption, part 2) touches sync (11), web-fe (12), and the
  Android client — build after 12; shared spec + test vectors in
  api-contracts (R21).
- **Phase 11 ships ADR-006's key derivation directly, not a placeholder for
  it:** register/login carry the real Argon2id + HKDF fields (`salt`,
  `kdf_params`, `kdf_version`, `auth_verifier`) from day one — there is no
  Phase 11–18 window where a client sends the password, or a hash of it, in
  place of a real verifier, and so no *auth* migration and no auth lockout to
  design for. There IS a **data** migration, and it is real: Phase 11 also
  ships sync (F13), four phases before the DEK exists (Phase 19), so every
  transcription a Phase 11–18 account pushes sits on the server as
  **plaintext** the whole time it waits for that account's first Phase-19
  login. What Phase 19 adds is the DEK (AES-256-GCM) and the ADR-018 box
  keypair — both bootstrapped, per account, at that account's next login
  after Phase 19 ships (one mechanism for the keys themselves, whether the
  account is old or brand new) — **and** the one-time migration of that
  account's own plaintext rows to ciphertext as part of the same login, with
  the server deleting each plaintext row once its encrypted replacement
  lands (see ADR-006). Because sync is development-only until Phase 19
  ("Gates and releases"), that migration only ever meets development data.
- Phase 20 (agent tokens) depends on Phase 19 delivering the ADR-018
  owner-keypair / sealed-box primitive, not only the DEK/AES-256-GCM
  primitive — an agent-token result cannot be sealed to an owner who has no
  box keypair yet. The account reset (F31) depends on Phase 19 the same way:
  it leaves the account keyless, so the next login goes through Phase 19's
  first-login bootstrap.
- Phase 20 (admin config) builds on Phase 12 (web-fe) and Phase 4 (whisper-server
  queue, which already forwards to a configurable target) — the connectivity
  test and agent tokens extend the existing admin panel; the forwarding target
  itself has been swappable since Phase 4.
- Phase 21 (data lifecycle) touches the queue (Phase 4), sync (11), history, and
  the admin panel (12) — build after 20.
- Phase 22 (UI/UX) consumes `shared/ui-tokens` — build the tokens first, then
  apply to the FE (12) and the Android app shell (1).
- Phase 7 (IME) has the most platform risk — start a spike in Phase 5 to validate
  `InputMethodService` on the S25 Ultra.

## Definition of Done (repo-wide)
- Every module has an `AGENTS.md` that matches reality.
- `MODULES.md` registry is accurate.
- No cross-module imports outside `core` ports.
- Terminology: "text commit" / "text insertion" (never "injection").
- Security Review's 4 fixes present and verified.
