# AGENTS.md - android/modules/stt-server/

## Purpose

Client for Breaker's own `whisper-server` job queue (primary path). This
client never talks to the Whisper X container directly; it talks to
Breaker's server, which forwards to whichever transcription service the
admin has configured.

**Build phase:** Phase 5. Needs first: `core` (on main) and `api-contracts`.

## Owns
Enqueue + poll against Breaker's `whisper-server`; present the result as a
single `SttResult` once the job completes.

## Public Interface `core.SttEngine` (server path).

`class SttServerEngine(serverUrl: () -> String, apiKey: () -> String?, limits: ServerLimits = ServerLimits())`
implements `core.SttEngine`. The public constructor takes two providers, read
once per call: the base address and the API key. The app supplies both; this
module reads no settings and no storage.

`ServerLimits` is a public data class holding every time and size limit. Its
defaults are listed under Known Gotchas.

**In:** `SttRequest` (WAV bytes, duration, `model`, `language`).
**Out:** `SttResult.Success(text, segments, language)` or
`SttResult.Failure(error, detail)`. `detail` is one fixed sentence from
`FailureSentences`, or, for a failed job, the server's failure text after
cleaning.

`transcribe` blocks, never throws, and never runs past its budget. The budget
is `baseBudgetMs + clipFactor * clip length`, capped at `maxBudgetMs`, measured
from the start of the call. The engine keeps no state between calls.

The job-queue mechanics (enqueue, poll, backoff) stay behind this port, so
`transport` and everything above it needs no change.

**API (async job contract, see `shared/api-contracts`):**
- `POST {base}/v1/audio/transcriptions`, multipart: part `file` (the WAV bytes,
  `audio.wav`), part `model`, part `language`. Expects `202` and a job id. The
  POST is never repeated.
- Poll `GET {base}/v1/jobs/{job_id}`. The first poll waits 500 ms; each later
  pause is 1.5 times the one before (at least 1 ms more), capped at 2 s; at
  most 600 polls. `queued` and `processing` keep polling; `done` gives the
  success; `failed` gives a failure with the server's text, cleaned: the key is
  removed, long token-like runs are redacted, and the text is cut to 160 units.
- **This endpoint lives on Breaker's `whisper-server`, built in Phase 4**, not
  on the Whisper X container, which this client never addresses.
- Auth: `Authorization: Bearer <key>` on every call (the POST and each poll),
  with `Accept: application/json`. The key is never logged or stored, and never
  appears in a failure text. A key with a CR, LF or NUL is refused before
  anything is sent.

**Answers and results (fixed):**

| Answer | Result |
|---|---|
| POST 202 | go on and poll |
| POST 401, 403 | `OTHER`, key refused |
| POST 413 | `OTHER`, too large |
| POST 404 | `SERVER_UNREACHABLE`, no endpoint (inferred, not seen on a real server) |
| POST 429, 5xx | `SERVER_UNREACHABLE`, not taking work (inferred, not seen on a real server) |
| POST any other status | `OTHER`, unexpected status |
| poll 200, `done` | success with text, segments and language |
| poll 200, `failed` | `OTHER`, the job's failure text, cleaned |
| poll 200, `queued` or `processing` | poll again |
| poll 200, unreadable or wrong fields | `OTHER`, bad answer |
| poll 401, 403 | `OTHER`, key refused |
| poll 404 | `OTHER`, job gone |
| poll 429, 5xx, or a lost connection | counted as transient; up to 3 in a row are tolerated, and the next one ends the call with that failure |
| read timeout | counted as transient; up to 3 in a row are tolerated, and the next one ends the call as `TIMEOUT` |
| budget used up, or 600 polls without `done` | `TIMEOUT` |
| TLS failure | `SERVER_UNREACHABLE`, at once, never retried |
| connection refused or unknown host | `SERVER_UNREACHABLE` on the POST; counted as transient on a poll |

**Transport:** https addresses only. Any other scheme, a missing host, user
information, or a space or control character is refused before anything is
sent. Platform trust only: no certificate pinning, no custom CA, no
TrustManager. Redirects are not followed and caches are off. Each call has a
connect timeout (1.5 s by default), so a dead server is reported fast. Every
exchange runs under a watchdog, so a stuck call ends at its deadline rather
than in a hang.

## Invariants
- End-to-end transcription from a phone over ZeroTier (F2) is not verified
  yet; it waits for the server module (Phase 4).
- Correct JSON parse for both the enqueue response and the poll response,
  incl. segments + timestamps on `done`.
- Timeout/error at either step surfaced as `SttResult.failure`, never a
  hang (R4).
- The upload is sent at most once. No retry of the POST.
- Only https addresses are called. The check runs before any request.
- The key never reaches a log line (the module logs nothing), an exception
  message, or a failure text.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_api_contracts (registered in modules.toml)

## Does Not Own
- Local transcription (stt-ondevice)
- The server queue (server/whisper-server)

## Test Locations
- Unit (Kotlin): `android/modules/stt-server/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:stt-server:test`
- Contract: `tests/contract/test_stt_server_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_stt_server_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- OpenAI-compatible contract lives in shared/api-contracts - no drift.
- The build wires both code edges: `project(":android:modules:core")` and
  `project(":shared:modules:api-contracts")`. The contract helper requires the
  api-contracts edge, because that module applies `kotlin.jvm` and
  `java-library` and so publishes an artifact; the contract test asserts that
  the edge is declared.
- Default limits (`ServerLimits.kt`): connect 1.5 s; POST reply 10 s, POST cap
  30 s; poll read 5 s, poll cap 10 s; first poll after 500 ms, pauses capped at
  2 s; budget 60 s plus 3 times the clip, at most 300 s; 600 polls; 3 transient
  poll failures in a row. None of these is measured on a device or against a
  real server.
- Not verified yet, and waiting for the server module (Phase 4):
  - the 404 answer to the upload and the 429 and 5xx answers. They are
    inferred. `openapi.yaml` in shared/api-contracts lists 202, 401 and 413
    for the upload, and 200, 401 and 404 for a job;
  - the retry timings and the default time limits, on a real server;
  - the certificate state of the real server. The module trusts the platform
    only, with no pinning and no custom CA, so the real certificate has not
    been checked;
  - an end-to-end dictation on a phone. The app uses this engine through its composition root (in automatic mode only when the probe answers, in server mode always); an end-to-end run on a phone is not verified yet.
