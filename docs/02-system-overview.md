# 02 — System Overview

## Server-primary with automatic local fallback + sync + web + training

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
│  commit ──IME active?──▶ InputMethodService commit ──▶ focused field          │
│       └── no field ──▶ clipboard + toast                                      │
│  history ◀── every transcription (text, source, timestamp)                    │
│  sync ──queue──▶ push to server when reachable (even local-only mode)         │
│  crypto ──keys+DEK (Argon2id+HKDF, AES-GCM) ──▶ encrypt before upload         │
│  training-client ──record samples──▶ upload──▶ download trained model         │
│  auth-client ──login/register──▶ token + role for sync/training/STT           │
│  updater ──daily check + admin force-check──▶ signed APK install              │
└────────────────────────────────────────────────────────────────────────────────┘
                              │  ZeroTier VPN (TLS via self-hosted CA)
                              ▼
┌─── Local Server ───────────────────────────────────────────────────────────────────┐
│  whisper-server ── /v1/audio/transcriptions + FIFO queue                     │
│                   ──▶ forwards to admin-configured service (Docker/IP/ext)    │
│  sync-api ── /v1/sync + /v1/auth (users, roles, agent tokens) + updates      │
│  web-fe ── Debian container: website (client-side decrypt) + APK + cert +    │
│            model hosting + admin panel (service config, store clear)         │
│  training ── per-user voice phrase model training (CPU; GPU via Local Inference)   │
│  deploy ── git pull (latest tagged) · docker-compose · certs · APK signing   │
└────────────────────────────────────────────────────────────────────────────────────┘
```

## Data flow (happy path — voice, server primary)
1. Idle: streaming ASR listens for "Breaker Breaker".
2. Wake phrase → tile appears + recording starts automatically.
3. User dictates. "And I'm Gone" detected → stop + trim at phrase onset.
4. Transport probes Local Server → audio → POST /v1/audio/transcriptions (enqueue) → poll GET /v1/jobs/{id} → raw text →
   formatter (existing LLM) → formatted text.
5. CommitService: IME commit inline, else clipboard.
6. Transcription row written to history, and queued for sync.
7. Sync queue pushes to `/v1/sync` → server stores under the user's namespace.

## Data flow (fallback — server down)
Same, but local sherpa-onnx + rule-based formatter. Transcription saved locally
and queued for sync; **pushed to the server when connectivity returns**
(F13). No packets leave the phone until the user's sync runs.

## Multi-user
Each user registers once (username/password). All data — transcriptions, trained
phrase models, settings — is scoped by user_id server-side (N13). The web FE
shows only the logged-in user's transcriptions.

## Key invariants
- Audio is captured once, in one format (16 kHz mono PCM), and either transcribed
  locally or uploaded — never both.
- Every transcription is stored in history regardless of source, and syncs when possible.
- Mode is decided per-dictation (probe result TTL-cached, never assumed).
- No silent cloud fallthrough, ever.
- Formatting is non-destructive (N9). The send phrase never appears in the text (F9).
- Sync is idempotent (N12). Isolation is server-enforced (N13).
- **Encryption:** transcriptions are ciphertext at rest; the server cannot decrypt
  without the user's password (F24). Search is client-side.
- **Retention:** transcriptions auto-delete after 3 months; audio deletes on job
  completion (F28).
- **Queue:** transcription jobs are strict FIFO — one at a time, restart-safe (F23).
- **Roles:** first account = admin; the last admin cannot delete or be demoted (F33).
