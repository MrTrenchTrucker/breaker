# MODULES.md — Breaker

> Module registry for the agent-driven build. Every module directory below has an
> `AGENTS.md` that tells a build agent exactly what the module does, its
> interfaces, dependencies, and acceptance criteria. This file is the index.

## Repo layout

```
breaker/
├── MODULES.md            <- you are here
├── ARCHITECTURE.md       <- master architecture (read this first)
├── agent-skills/         <- skills that set up AI agents to work here, by rank
├── docs/                 <- requirements, security, build order, sideload how-to
├── android/              <- Kotlin / native Android app (the phone client)
├── server/               <- Local Server services (STT, sync, web, training, deploy)
└── shared/               <- contracts, model registry, formatting prompts
```

## Base + security status

- Forked from the **swept OpenWhispr Android fork** (`com.edib.openwhispr`).
- Security Review (Bug Hunt) verdict: **clean bill of health** with 4 fixes, all applied
  in Phase 0: (1) no silent cloud fallthrough, (2) delete in-app updater,
  (3) change package id, (4) pin Gradle SHA-256. Plus NOTICE file (licensing).
- Caveat: base code was never built/run — Phase 0 includes a build + smoke-test gate.
- Second security review (k2-fsa/sherpa-onnx): **safe to use with three
  mitigations** — pin models to immutable release-asset ids + verify upstream
  checksum.txt; track transducer decoder bug #3983; keep sherpa-onnx off the
  server's network-exposed path [2].

## Priority model

**SERVER-PRIMARY:** Local Server via ZeroTier is the primary transcription +
formatting path. On-device sherpa-onnx is the automatic fallback for connectivity
issues. Default mode = `server-primary`.

## Updates & admin

- The app checks **once a day** for newer tagged releases (local GIT server) and
  notifies the user; admins have a **force-check button** (F20, F21).
- The **first account to register** on the web FE is **admin by default** and can
  assign other admins (F22).
- Updates are **signed APKs** verified by signature + SHA-256 before install
  (rebuild of the deleted in-app updater [1] — our server, secured signing key).

## Voice control (v1)

Two CB-slang phrases via **streaming on-device ASR + phrase matching**:
- **"Breaker Breaker"** — wake: tile appears + recording starts automatically.
- **"And I'm Gone"** — send: stop + trim audio at phrase onset + dispatch.
Upgrade path: per-user trained KWS model (server training container). Grammar
commands ("copy #27") deferred to v1.2.

## Module registry

| ID | Module | Where | Owner agent lane |
|----|--------|-------|------------------|
| `app` | Android entry point, dependency injection wiring, Gradle build | `android/app/` | Orchestration |
| `ui` | Screens: dictation, history, settings, auth, training; consumes ui-tokens | `android/ui/` | Coding |
| `core` | Domain core: models, ports, use cases (no Android deps) | `android/modules/core/` | Orchestration |
| `audio` | Mic capture, VAD, noise suppression, WAV encode | `android/modules/audio/` | Coding |
| `stt-ondevice` | sherpa-onnx local transcription (fallback engine) | `android/modules/stt-ondevice/` | Coding |
| `stt-server` | Client for Breaker's `whisper-server` job queue (primary; polls for the async result) | `android/modules/stt-server/` | Coding |
| `transport` | Connectivity probe: is the Local Server reachable (core routes on the answer); no cloud path | `android/modules/transport/` | Orchestration |
| `format` | Whisper Flow-style formatting (server LLM + rule-based local) | `android/modules/format/` | Coding |
| `phrases` | Streaming ASR + phrase matching: "Breaker Breaker" wake + "And I'm Gone" send | `android/modules/phrases/` | Coding |
| `gesture` | Shake-to-wake (accelerometer) | `android/modules/gesture/` | Coding |
| `overlay` | Floating tile = **CB mic glyph** + **LED bar meter** (WindowManager overlay, F36) | `android/modules/overlay/` | Coding |
| `commit` | CommitService: accessibility text insert + clipboard fallback | `android/modules/commit/` | Coding |
| `history` | SQLite transcription history + **3-month TTL cleanup + tombstones** | `android/modules/history/` | Coding |
| `settings` | Settings persistence + model registry access | `android/modules/settings/` | Coding |
| `sync` | Offline-first sync queue → push transcriptions to server | `android/modules/sync/` | Coding |
| `auth-client` | Login/register, token storage (Android Keystore) | `android/modules/auth-client/` | Coding |
| `training-client` | Record phrase samples, upload, download trained model | `android/modules/training-client/` | Coding |
| `updater` | Daily update check + admin force-check; verify + install signed APK | `android/modules/updater/` | Coding |
| `crypto` | Client-side key derivation (Argon2id + HKDF), DEK generation/wrap/unwrap, AES-256-GCM, X25519 box keypair, sealed-box unsealing (ADR-006, ADR-018) | `android/modules/crypto/` | Coding |
| `whisper-server` | Add `/v1/audio/transcriptions` + **FIFO queue worker + job API**, forwarding to the **admin-configured** transcription service | `server/modules/whisper-server/` | Coding |
| `sync-api` | Server API: auth, transcription sync, per-user isolation, **service config + agent tokens** | `server/modules/sync-api/` | Coding |
| `web-fe` | Debian container website (view/search/copy, client-side decrypt) + APK + cert + **model hosting** + **Trucking theme (light/dark, responsive)** + **admin panel (service config, agent tokens, store clear)** + **account management** | `server/modules/web-fe/` | Coding |
| `training` | Per-user voice phrase model training container | `server/modules/training/` | Coding |
| `deploy` | Git-pull deploy, docker-compose, ZeroTier bind, TLS, NOTICE | `server/modules/deploy/` | Bug Hunt + Orchestration |
| `api-contracts` | OpenAPI spec for transcription + sync + auth endpoints | `shared/modules/api-contracts/` | Orchestration |
| `model-registry` | Model sizes, URLs, SHA-256 pins, per-model licenses | `shared/modules/model-registry/` | Orchestration |
| `format-prompts` | Strict non-destructive formatting prompts for the server LLM | `shared/modules/format-prompts/` | Orchestration |
| `ui-tokens` | Trucking design tokens: colors (light/dark), typography, spacing, breakpoints — shared by web FE + Android app | `shared/modules/ui-tokens/` | Orchestration |
| `agent-skills` | Working rules and readback skills for AI agents, one pair per rank (ADR-019) | `agent-skills/` | Orchestration |

## Conventions

1. **Every module has `AGENTS.md`.** Purpose, interfaces (in/out), dependencies
   (module IDs), acceptance criteria, build notes.
2. **Ports live in `core`, adapters live in feature modules.** `core` never
   imports Android APIs.
3. **No cross-module imports.** Modules talk through `core` ports or the app's
   dependency wiring in `android/app/`.
4. **Terminology:** we say *text commit* / *text insertion* — never "injection."
5. **Security gate:** Security Review's 4 fixes + NOTICE must be verified in Phase 0.
6. **Multi-user:** all server data is user-scoped; isolation is server-enforced.
