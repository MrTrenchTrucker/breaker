# AGENTS.md — android/modules/stt-ondevice/

## Purpose

sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify. Offline speech-to-text on the phone using the base repo's **sherpa-onnx**
engine (vendored from XIAOMI CORPORATION, upstream Apache-2.0, swept clean by Security Review).

**Build phase:** Phase 3 of `docs/04-build-order.md`. Needs first: `core` (on main) and `model-registry` (built; this module names models only through the registry's entries, ADR-016).

## Owns
sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify.

## Public Interface `core.SttEngine` (local path).

**In:** 16 kHz mono PCM float32.
**Out:** `SttResult(text, segments, language)` or a failure result: `SttError.LOCAL_MODEL_MISSING` for model problems, `SttError.OTHER` for audio, engine and store problems. The engine class is `OnDeviceSttEngine`; user sentences come from `ErrorMapping`.

**Engine notes:**
- Keep the base's sherpa-onnx local-mode code (already swept). Do NOT reintroduce
  the base's cloud/Groq fallback path (Security Review fix #1).
- whisper.cpp AAR is a **fallback only** if sherpa-onnx lacks a needed feature.
- `OnDeviceSttEngine` implements `core.SttEngine` over the loader port. It checks the audio rate
  first (16 kHz only), asks the loader for the model, decodes, and releases the recognizer after the
  decode. Every problem comes back as a failure result and `transcribe` never throws, including a
  store failure while loading (reported as an OTHER failure).
- Model problems map to `LOCAL_MODEL_MISSING`; audio, engine and store problems map to `OTHER`. The
  engine never returns `SERVER_UNREACHABLE` or `TIMEOUT`: it has no server path.
- `transcribe` blocks (it bridges onto the dispatcher with `runBlocking`). `transcribeAsync` runs
  the decode on the dispatcher directly and must never call `transcribe`: the inner `runBlocking`
  would wait for the slot its caller holds and hang.
- A `transcribe` called from a thread already inside a decode is refused at once. The marker is a
  `ThreadLocal` reset in a `finally`; the reason a coroutine primitive does not fit is written at
  the site.
- If a decode throws a cancellation, the result of `transcribeAsync` ends cancelled (the cause is
  not swallowed); the blocking `transcribe` still returns a failure.
- `close()` marks the engine closed. It does not interrupt a running decode and does not cancel
  queued async calls: each of those returns the engine-closed failure when its turn comes. The
  engine holds no recognizer between calls.
- When a checksum failure left the file on disk (the delete failed), the user sentence says it could
  not be deleted instead of saying it was deleted.

**Model lifecycle:**
- Models from `shared/model-registry` (sizes + SHA-256 + per-model license terms).
- Download once → app data dir → **verify against upstream checksum.txt + our
  SHA-256 before load** (N3, T1, T21) [2].
- The check, as it is today: an archive is trusted only when the SHA-256 of
  the downloaded archive equals our pin AND that pin appears in the upstream
  checksum list. It does not yet check that the pin is the digest listed for
  this archive's own name (see Known Gotchas). The archive is checked when it
  is installed (in staging, and again after the move) and on every load.
- A file that fails the check is deleted at once, never loaded, never kept, so
  the next attempt downloads it fresh. If the delete itself fails, the refusal
  stays: the result carries `leftOnDisk` and its fixed sentence says the file
  could not be removed.
- Separate case: if the stored checksum list is missing or unreadable when a
  model is loaded, nothing was judged. The archive is kept and never loaded,
  the recognizer factory is never called, the user is told the model could not
  be checked right now, and it is checked again on the next load.
- What the user sees: one fixed plain sentence per kind of problem
  (`ModelMessages`). Paths, exception text and digests go to a debug sink the
  caller supplies (`ModelDebugSink`; the default drops them). A load or install
  refusal never carries a path or an exception class name.
- `preload(modelId)` loads the model once and releases the recognizer before it returns, so a
  settings screen can report a bad model early. It holds nothing, and each `transcribe` verifies and
  starts its own recognizer again, so preload does not shorten the next start. Load at startup (or
  lazy per setting) remains the app's wiring decision.

**Security Review's sherpa-onnx audit [2]:** safe to use with three mitigations — clean
of malware/backdoors/phone-home; the vendored Kotlin bindings match upstream
signatures exactly. **Known bug:** issue #3983 — OOB write in the offline
transducer greedy-search decoder, reachable via a tampered .onnx (vocab_size
parsed with no upper bound). Mitigations adopted here: pinned immutable release-asset ids + upstream checksum.txt verification (closes the tampered-model delivery
vector); track #3983 and adopt the upstream decoder fix when it lands; revisit
(beam-search / non-transducer models) only if the fix doesn't land in a
reasonable window [2].

**Threading:** inference blocks, so the engine runs one decode at a time on a single-slot coroutine
dispatcher, never the UI thread. The dispatcher is a single-slot view over the shared IO pool (built
by `singleSlot`). One slot is not one fixed thread: successive decodes may run on different IO
threads, so the reentrancy marker is reset after every decode.

- Thread affinity: the engine creates a recognizer, runs one decode and releases it inside a single
  call and keeps none between calls, so it does not matter which IO thread runs the next decode. As
  far as the upstream code read shows, the sherpa-onnx recognizer has no thread affinity: its native
  handle is a plain pointer, and the recognizer code read keeps no thread-local state and reads no
  thread identity (k2-fsa/sherpa-onnx, jni/ and csrc/ recognizer sources, read at the upstream
  version Breaker pins). ONNX Runtime documents that a session's Run may be called from multiple
  threads (onnxruntime core/session/inference_session.h). Limits: upstream gives no written
  guarantee, so the claim rests on reading the code; only the recognizer paths were searched; the
  vendored Kotlin in this repo is the offline recognizer, and the native adapter this module will
  use does not exist yet, so the claim must be re-checked when the adapter lands; if it ever needs
  one thread, the engine must switch to a single fixed thread.

## Invariants
- Offline dictation E2E: tap → speak → send → text (F1).
- Tamper test: corrupt model file → load refused and the file deleted, so a retry
  downloads it fresh (T1, T21).
- Start latency < 1 s with preload (N1).
- **No model installed + local mode → clear "No model installed" error** (fix #1).

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_model_registry (registered in modules.toml)

## Does Not Own
- Server transcription (stt-server)
- Model catalog (shared/model-registry)

## Test Locations
- Unit (Kotlin): `android/modules/stt-ondevice/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:stt-ondevice:test`
- Contract: `tests/contract/test_stt_ondevice_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_stt_ondevice_contract.py`
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
- Every .onnx is untrusted — verify checksum.txt before load [2]; track #3983 [2].
- The Gradle edge to `:shared:modules:model-registry` is required and present:
  the registry publishes an artifact (its build file applies `java-library`),
  this module's build file declares the edge, and its `modules.toml` entry
  lists `shared_model_registry`. The registry's types `ModelEntry` and
  `ModelFamily` appear in the public signatures of `ModelFetcher`,
  `ModelInstaller` and `ModelLoader` here, so a module that calls them needs
  the same edge.
- The per-archive check waits on a registry field. The registry's download
  URLs end in numeric release-asset ids, so the archive's upstream file name
  cannot be derived from the URL, and `ModelEntry` carries no file name.
  `ModelIntegrity.verify` already takes an optional archive name (unused) for
  the day the registry records it. Until then the check is the one under
  Model lifecycle.
- A crash between moving the archive into place and writing the stored
  checksum list and the verified marker can leave an archive without them. The
  window is the span in `ModelInstaller` from the move to those two writes,
  and it includes the second check of the archive. The loader then keeps the
  archive and refuses it with the "could not be checked right now" sentence on
  every load, because the stored checksum list is missing; installing the
  model again heals it.
- The start target (N1) is not met by the engine as it stands: every call hashes the whole archive
  and creates a recognizer, and preload keeps neither. Model sizes in the registry range from 122 MB
  to 1818 MB. This has not been measured on a device.
- Engine behaviour not changed yet: a failing `Error` (not an `Exception`) from the loader reaches
  the caller; the decode catch policy names two `Error` types and not others; a recognizer release
  that throws replaces the result; the engine and the loader keep two sets of user sentences that
  have drifted apart; a model the loader could not start is shown as "could not transcribe"; the
  wrong-family sentence names a model family the code does not check; `loadedModelId` in the
  diagnostics is not cleared after a refusal; diagnostics on a closed engine reads on the caller's
  thread; one salvaged test duplicates another; the `Outcome` enum is unused.
- Nothing in this module downloads or extracts yet: there is no real `ModelFetcher` and no real
  recognizer factory (the default, `UnavailableRecognizerFactory`, refuses to create one), and no
  production code constructs `ModelInstaller` or `ModelLoader`. The engine takes the loader through
  `ModelLoaderPort`, and the app's wiring supplies it later, so the engine cannot transcribe real
  audio until a recognizer factory exists.
