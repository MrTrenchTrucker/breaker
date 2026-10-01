# 03 — Security & Threat Model

## Security Review (Bug Hunt) verdict — 2026-09

The base repo (OpenWhispr Android fork, `com.edib.openwhispr`) was swept and
received a **clean bill of health**:
- No malware, no obfuscation, no backdoor, no hidden exfiltration, no prompt-injection.
- Every network destination maps to a requested feature.
- Permissions are minimal (mic, internet, foreground service — no contacts, SMS,
  camera, location).
- No hardcoded secrets. Recordings stay app-private.
- Provenance: two real developers over 6.5 months, complete license text.
- Apache-2.0 explicitly permits the fork.

**Caveat:** the code was never built or run — compilation and runtime behavior
are **unverified**. Phase 0 includes a build + smoke-test gate.

## The four fixes (Phase 0, in order of importance)

| # | Fix | Why |
|---|-----|-----|
| 1 | **No silent cloud fallthrough** — local mode with no downloaded model currently falls through to a cloud path (WhisperAccessibilityService.kt:601-608). Audio only uploads if a Groq key is pasted, and the key is never prefilled, so nothing transmits — but the error says "Set API key," which is misleading when you never wanted cloud. Our server-first design removes the Groq path entirely and errors clearly. | Correctness + privacy |
| 2 | **Delete the in-app updater** — it downloads an APK over the network, its signing key is a custom cert committed in plaintext, and in a fork it still points at the ORIGINAL AUTHOR's releases. | Critical supply-chain |
| 3 | **Change the app package id** off `com.edib.openwhispr` — don't ship a fork under someone else's identity. | Identity |
| 4 | **Pin the Gradle distribution SHA-256.** | Supply-chain |

## Licensing

- **Private use: zero obligations.**
- **Public release (the repo is public):** (a) the root **NOTICE file** credits
  **XIAOMI CORPORATION** for the vendored sherpa-onnx code (that package has no
  license file of its own; upstream verified Apache-2.0) and the upstream
  OpenWhispr authors; (b) it states that the files were modified; (c) keep the
  Apache-2.0 license — **you CANNOT relicense an Apache-2.0 derivative closed.**
- Keep `NOTICE` current: code copied from any other project adds its entry in the
  same PR.
- **ASR models:** runtime ASR models you download carry their own upstream terms,
  outside this audit — record per-model licenses in `shared/model-registry`.

## Assets
- A1. Audio recordings (privacy-critical). · A2. Transcription history. ·
  A3. API key for Local Server. · A4. Model files (integrity-critical). ·
  A5. Phone mic + overlay privileges.

## Threats & mitigations

| # | Threat | Mitigation |
|---|--------|------------|
| T1 | **Model swap** — malicious/poisoned model file | SHA-256 pins in `shared/model-registry`; verify before load |
| T2 | **API key exfiltration** | Key via Android Keystore; never logged |
| T3 | **ZeroTier exposure** — server reachable by other ZT members | Bind to ZT interface; API key; TLS |
| T4 | **Overlay spoofing** — another app fakes our tile | Verify window ownership; tap-only tile |
| T5 | **Mic abuse** — another app records while we hold mic | Explicit per-session mic permission; recording indicator |
| T6 | **Audio interception on wire** | TLS to server (verify Local Server cert infra state first — UNKNOWN) |
| T7 | **History theft** | App-private storage; optional encryption at rest |
| T8 | **IME abuse** — commit to wrong field | Commit only on explicit send tap; preview toggle |
| T9 | **In-app updater abuse** (inherited) | **Deleted in Phase 0** (Security Review fix #2) |
| T10 | **Silent cloud fallthrough** (inherited) | **Fixed in Phase 0** (Security Review fix #1) |
| T11 | **Update tampering** — malicious APK served as an update | Signed APK + SHA-256 verified before install; signing key in secrets store (never git) [1] |
| T12 | **Signing key compromise** | Key in secrets store; rotation procedure; no plaintext certs (base repo's flaw [1]) |
| T13 | **Cross-user data access** (multi-user) | Server-enforced user scoping (N13); token auth on every API |
| T14 | **Sync replay / duplicate transcriptions** | Idempotent sync (N12): (client id, key_version) pair dedupe |
| T15 | **Training sample exfiltration** | User-scoped storage, TLS, token auth, user-deletable |
| T16 | **Admin escalation / role abuse** | First-account-is-admin bootstrap; role changes audited; no self-promotion |
| T17 | **Key management / DEK compromise** — a stolen DB leaks only wrapped keys, never plaintext or a usable key derived server-side | DEK generated + wrapped **client-side**; wrapped by a client-derived KEK (Argon2id + HKDF split, ADR-006); server never generates the DEK or derives the KEK; ciphertext only at rest (F24). Does **not** defend against offline password guessing against the leaked wrapped keys (Android — see T25) or against a compromised server serving password-capturing JS to a web-FE login (see T27) |
| T18 | **Queue abuse / DoS** — flooding the transcription queue | FIFO worker + per-user rate limits + job size caps. Does not apply to the unauthenticated auth endpoints (salt lookup, register, login) — see T25 |
| T19 | **Agent token leakage** — a leaked token transcribes as the owner | Scoped + revocable tokens, rate-limited, optional expiry (F27) |
| T20 | **SSRF via transcription service config** — admin-configured URL probed by the server | Admin-only config; URL validation; no arbitrary localhost probing (F26) |
| T21 | **Tampered model / transducer OOB write** — issue #3983, reachable via a malicious .onnx | Models pinned to immutable release-asset ids + upstream checksum.txt verified; adopt upstream decoder fix when it lands; sherpa-onnx off the server network path [2] |
| T22 | **Plaintext leakage via logs** — a log line containing a transcription defeats encryption-at-rest | Log policy: events only, per-user, never plaintext transcriptions (F32) |
| T23 | **Store-clear abuse** — wiping another user's (or all) transcriptions | Admin-only endpoints + confirmation; audited (F29) |
| T24 | **Destructive account/delete-all abuse** — accidental or malicious self-wipe | Type-to-confirm + clear irreversible warning; audit event (F33, F34) |
| T25 | **Offline password guessing (Android)** — anyone holding the stored auth-verifier hash or a wrapped DEK (DB dump, backup, log line) can test password guesses offline, one Argon2id run per guess, against the Android key-handling path (signed APK, not server-served) | Argon2id cost floor, **enforced** server-side (register, change-password, complete-reset) and client-side (login) — ADR-006: 64 MiB, 3 iterations, parallelism 1, per-account KDF version; 12-character minimum password + client-side weak-password warning (ADR-006). **No brute-force protection or account-enumeration defense is implemented** — brute-force protection is explicitly out of scope for v1 (docs/01, docs/05: "auth hardening"), and this repo reads that as covering account enumeration too, though neither list names it by that term; the VPN/ZeroTier perimeter is the auth layer for now. Protection scales with password strength × KDF cost, never stronger. A **raw, unhashed** leaked verifier is not this threat — it is a direct login credential; see T27 |
| T26 | **Sealed-box owner-key substitution / forged sealed rows** — a party with database write access (an admin, or an agent given server access) replaces an account's `box_pubkey`, silently redirecting future agent-token results to a key of their choosing, **or inserts a forged sealed row directly**, which the owner's own client unseals and converts into an ordinary-looking transcription automatically, with no review | Detection only, for the key swap: there is no MAC over the public key under the DEK, so nothing prevents the swap, but at the account's next login the client recomputes the box public key from the unwrapped private half and treats a mismatch with the stored `box_pubkey` as tampering (ADR-018; `android/modules/crypto`, `server/modules/web-fe`) — results sealed to the swapped key before that login are already exposed. None for forged rows: the owner's client has no way to tell a forged sealed row from a real one — `crypto_box_seal` is anonymous by design. This is the same trust level the server already holds, since it processes every server-routed transcription in plaintext during the job itself (on-device transcriptions never reach it) (ADR-018) — the sealed box defends read-only exposure of a stored copy, not a party who can act as the server or write its database |
| T27 | **Served-JS key capture (web FE only)** — the web FE's Argon2id/HKDF/AES-GCM JavaScript is downloaded from the server on every login, with no signed-artifact pinning; a compromised server, or anyone with write access to the web-fe container's files, can serve a version that sends the password, the KEK, or the unwrapped DEK home directly at the user's next browser login | None in v1 beyond not letting the server get compromised — this is a structural property of running key-handling code in a browser fetched from the same server it is meant to keep honest, and it does not apply to Android (signed APK). **A raw, unhashed leaked auth verifier** is a related but distinct threat: it is a direct login credential (session + wrapped keys), and it authorizes `change-password` (now requiring the old verifier explicitly in the body, not a bearer token alone — ADR-006), so a holder can lock the real user out of their keys, though not decrypt anything without also breaking Argon2id |

## Verification checklist (Bug Hunt lane — Phase 9 gate; each item tagged with the phase(s) that deliver and hold it)

Items tagged **[Phase 0–8]** are what the Phase 9 gate blocks on — they must all be signed off before anything is released to users, per root `AGENTS.md` rule 6 (gate status: `docs/04-build-order.md`, "Gates and releases"). A single-phase tag (e.g. **[Phase 3]**) means the item is delivered once and does not change again before Phase 9. A phase-RANGE tag (e.g. **[Phase 4–8]**) means the item first becomes checkable at the range's start and must still hold, unregressed, when Phase 9 signs off — it is not a box ticked early and forgotten. Items tagged with a phase after 9 are listed here as the cumulative Bug Hunt record; they sign off when that phase's own exit criteria (docs/04-build-order.md) are met, not at Phase 9.

- [ ] Security Review's 4 fixes verified applied (updater gone, package id changed,
      Gradle pinned, no cloud fallthrough) **[Phase 0]**
- [ ] NOTICE file present + accurate (XIAOMI sherpa-onnx, OpenWhispr authors) **[Phase 0]**
- [ ] Gradle dependency audit clean **[Phase 0–8]**
- [ ] Model SHA-256 verification path tested (tamper test) **[Phase 3]**
- [ ] No unexpected network calls (packet capture during local-mode dictation) **[Phase 0–8]**
- [ ] API key not present in logs/backups **[Phase 4–8]**
- [ ] Overlay window ownership verified **[Phase 6]**
- [ ] Mic permission flow reviewed **[Phase 2]**
- [ ] Base code compiled + smoke-tested (Security Review caveat closed) **[Phase 0]**
- [ ] Update path verified: signed APK + SHA-256 check; signing key NOT in git **[Phase 16]**
- [ ] Admin bootstrap verified: first account is admin; role assignment works; no self-promotion **[Phase 17]**
- [ ] Multi-user isolation tested (user A cannot read user B's data) **[Phase 11]**
- [ ] Sync stays development-only until encryption ships: no release build turns sync on, and no real user's data is synced, before Phase 19 **[Phase 11–19]**
- [ ] Encryption verified: ciphertext at rest; server cannot decrypt without user password (F24) **[Phase 19]**
- [ ] Queue verified: concurrent requests serialize FIFO; restart-safe (F23) **[Phase 4]**
- [ ] Service config verified: connectivity test works; misconfigured URL fails clearly (F26) **[Phase 20]**
- [ ] Agent tokens verified: scoped, revocable, rate-limited; results to owner (F27, T19) **[Phase 20]**
- [ ] Model tamper test: corrupt/pinned-mismatch model refused (T21); checksum.txt verified [2] **[Phase 3]**
- [ ] Retention verified: 3-month TTL; audio deleted on job completion; 3× retry @10 s (F28) **[Phase 21]**
- [ ] Log audit: no plaintext transcription content in any log (F32, T22) **[Phase 21]**
- [ ] Sync idempotency tested (retries don't duplicate) **[Phase 11]**
