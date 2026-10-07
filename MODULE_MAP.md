# MODULE_MAP.md — find the right module

> One line per module. Regenerate by hand from `modules.toml` whenever the
> registry changes.

## android/ — the phone app (Kotlin, native Android)
| Module | Path | Job |
|--------|------|-----|
| `app` | `android/app/` | Android entry, DI wiring, Gradle build |
| `ui` | `android/ui/` | Screens: history, settings, auth, training; permission onboarding |
| `core` | `android/modules/core/` | Domain models, ports, use cases (no Android deps) |
| `audio` | `android/modules/audio/` | Mic capture, VAD, noise suppression, WAV encode |
| `stt-ondevice` | `android/modules/stt-ondevice/` | sherpa-onnx local transcription (fallback) |
| `stt-server` | `android/modules/stt-server/` | Client for Breaker's own whisper-server job queue (primary) |
| `transport` | `android/modules/transport/` | Connectivity probe: is the Local Server reachable (core routes) |
| `format` | `android/modules/format/` | Whisper Flow-style formatting (server LLM + rule-based) |
| `phrases` | `android/modules/phrases/` | "Breaker Breaker" wake + "And I'm Gone" send |
| `gesture` | `android/modules/gesture/` | Shake-to-wake (accelerometer) |
| `overlay` | `android/modules/overlay/` | Floating tile = CB mic glyph + LED bar meter |
| `commit` | `android/modules/commit/` | CommitService: accessibility text insert + clipboard fallback |
| `history` | `android/modules/history/` | SQLite history + 3-month TTL + tombstones |
| `settings` | `android/modules/settings/` | Settings persistence + model registry access |
| `sync` | `android/modules/sync/` | Offline-first sync queue (idempotent push) |
| `auth-client` | `android/modules/auth-client/` | Login/register, token storage, roles/scopes |
| `training-client` | `android/modules/training-client/` | Record phrase samples, upload, download trained model |
| `updater` | `android/modules/updater/` | Daily check + admin force-check, signed APK, rollback |
| `crypto` | `android/modules/crypto/` | Client-side key derivation (Argon2id + HKDF), DEK generation/wrap/unwrap, AES-256-GCM, X25519 box keypair, sealed-box unsealing |

## server/ — Local Server services
| Module | Path | Job |
|--------|------|-----|
| `whisper-server` | `server/modules/whisper-server/` | `/v1/audio/transcriptions` + FIFO queue → configured service |
| `sync-api` | `server/modules/sync-api/` | Auth (users, roles, agent tokens), sync, updates, retention, store clear |
| `web-fe` | `server/modules/web-fe/` | Debian container website (client-side decrypt, Trucking UI) + APK/cert/model hosting + admin panel |
| `training` | `server/modules/training/` | Per-user voice phrase model training (CPU; GPU via Local Inference) |
| `deploy` | `server/modules/deploy/` | Git-pull deploy, docker-compose, certs, APK signing, ZT bind |

## shared/ — contracts + registry
| Module | Path | Job |
|--------|------|-----|
| `api-contracts` | `shared/modules/api-contracts/` | OpenAPI spec: auth, sync, jobs, updates, admin, training |
| `model-registry` | `shared/modules/model-registry/` | Model sizes, immutable release-asset URLs, checksum.txt, licenses |
| `format-prompts` | `shared/modules/format-prompts/` | Strict non-destructive formatting prompts for the server LLM |
| `ui-tokens` | `shared/modules/ui-tokens/` | Trucking design tokens (colors, type, spacing, breakpoints) |

## agent-skills/ — set up AI agents to work here
| Module | Path | Job |
|--------|------|-----|
| `agent-skills` | `agent-skills/` | Working rules and readback skills for AI agents, one pair per rank (project leader, IT manager, worker) |
