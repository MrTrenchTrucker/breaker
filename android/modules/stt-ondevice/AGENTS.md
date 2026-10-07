# AGENTS.md — android/modules/stt-ondevice/

## Purpose

sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify. Offline speech-to-text on the phone using the base repo's **sherpa-onnx**
engine (vendored from XIAOMI CORPORATION, upstream Apache-2.0, swept clean by Security Review).

**Build phase:** Phase 3 of `docs/04-build-order.md`. Needs first: `core` (on main) and `model-registry` (built; this module names models only through the registry's entries, ADR-016).

## Owns
sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify.

## Public Interface `core.SttEngine` (local path).

**In:** 16 kHz mono PCM float32.
**Out:** `SttResult(text, segments, language)` or a failure result: `SttError.LOCAL_MODEL_MISSING` for model problems, `SttError.OTHER` for audio, engine and store problems. The engine class is `OnDeviceSttEngine`; user sentences come from `ErrorMapping`. A decode that does not finish before its deadline is an `OTHER` failure too (see Engine notes).

**Engine notes:**
- Keep the base's sherpa-onnx local-mode code (already swept). Do NOT reintroduce
  the base's cloud/Groq fallback path (Security Review fix #1).
- whisper.cpp AAR is a **fallback only** if sherpa-onnx lacks a needed feature.
- `OnDeviceSttEngine` implements `core.SttEngine` over the loader port. It checks the audio rate
  first (16 kHz only), asks the loader for the model, decodes, and releases the recognizer after the
  decode (after an abandoned decode, the worker releases it when the native call returns). Every
  problem comes back as a failure result and `transcribe` never throws, including a store failure
  while loading (reported as an OTHER failure).
- Model problems map to `LOCAL_MODEL_MISSING`; audio, engine and store problems map to `OTHER`. The
  engine never returns `SERVER_UNREACHABLE` or `TIMEOUT`: it has no server path.
- A decode that does not finish before its deadline returns a failure, category `OTHER`
  (`ErrorMapping.decodeTimedOut()`), not `TIMEOUT`. While an abandoned decode is still running,
  `transcribe` and `transcribeAsync` return `ErrorMapping.decodeBusy()` (also `OTHER`) at once;
  `preload` and `diagnostics` are not refused. The constructor takes an injectable `DecodeDeadline`
  and a worker dispatcher for the native call after the loader and the slot dispatcher; both have
  defaults, so a call with one or two arguments compiles unchanged. The default limit is 30 seconds
  plus three times the clip length. It is a recommended value and is NOT measured on a device.
- `transcribe` blocks (it bridges onto the dispatcher with `runBlocking`). `transcribeAsync` runs
  the decode on the dispatcher directly and must never call `transcribe`: the inner `runBlocking`
  would wait for the slot its caller holds and hang.
- A `transcribe` called from a thread already inside a decode is refused at once. The marker is a
  `ThreadLocal` reset in a `finally`; the reason a coroutine primitive does not fit is written at
  the site. The worker that runs the native call carries the same marker, so a call made from inside
  a decode is still refused at once.
- If a decode throws a cancellation, the result of `transcribeAsync` ends cancelled (the cause is
  not swallowed); the blocking `transcribe` still returns a failure.
- `close()` marks the engine closed. It does not interrupt a running decode, does not cancel an
  abandoned decode and does not cancel queued async calls: each queued call returns the engine-closed
  failure when its turn comes. The engine holds no recognizer between calls; an abandoned decode's
  worker still holds its recognizer until the native call returns (see Known Gotchas).
- When a checksum failure left the file on disk (the delete failed), the user sentence says it could
  not be deleted instead of saying it was deleted.

**Download side:**
- `HttpModelFetcher` is the real `ModelFetcher`. It is built on the platform's `HttpURLConnection`,
  so the module takes no new dependency. `DownloadLimits` holds every bound as a plain parameter, so
  a test can tighten any of them. Both constructor arguments (`limits`, `cancelled`) have defaults.
- Where it may connect: https only, on every request. The first request goes to a host in the
  first-hop set (`api.github.com`); a redirect goes to a host in the redirect set
  (`release-assets.githubusercontent.com`) and nowhere else. An address with user-info or a port
  other than 443 is refused. Redirects (301, 302, 303, 307, 308) are followed by hand, at most 5. No
  credentials and no cookies are sent: the request carries only fixed `Accept`, `Accept-Encoding`
  and `User-Agent` headers.
- How much and how long: the body is capped at the registry size plus one MiB (the registry gives
  the size to the nearest whole MiB); the checksum list is capped at one MiB. A declared length above
  the cap fails before one byte is read, and a body that grows past the cap is cut off. Connect
  timeout 15 s, read timeout 30 s, and one hour for the whole fetch.
- The caller may stop a fetch with a polled cancel lambda: it is asked before each request and once
  per chunk of a body. A read already blocked in the socket ends at the read timeout.
- A network problem is a failure result with a short reason that never carries an address, path or
  query. The installer sends that reason to the debug sink only; the user sees one fixed sentence.
  Only a problem writing the staging file (a full disk included) is thrown, as an `IOException`,
  and the partial file is deleted first.
- The fetcher never hashes. The installer's digest check is the judge of the bytes.

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

The native call itself runs on an IO worker (the worker dispatcher, `Dispatchers.IO` by default)
while the slot thread parks on a first-wins `CompletableFuture`. The worker's result or the deadline
completes the future, whichever comes first. The deadline ends the wait, not the native call: when
it wins, the slot thread returns the timeout failure and the slot is free again, while the abandoned
worker stays inside the native call and releases its recognizer when that call returns. This is an
argued exception to the rule to use coroutines for every concurrent thing (root
`AGENTS.md` section 6). A suspension would hand the single slot to the next queued decode, so two
native decodes could overlap. A nested coroutine bridge on a caller's own loop could run the next
queued body inside the wait. A native call cannot be cancelled, so the only lever is to stop waiting
for it. A JDK future parks the thread and pumps nothing. The reason is also written at the park and
at the worker dispatch in `DecodeBound.kt`.

An interrupt of the thread that waits ends the wait like a deadline: the call returns the timeout
failure, the decode counts as abandoned, and the thread's interrupt flag is put back. A test covers
it.

- Thread affinity: the engine creates a recognizer on the slot thread, decodes with it on a bound
  worker thread (one recognizer, two threads, one at a time) and releases it inside a single call
  (on the slot thread, or on the worker when the decode was abandoned). It keeps none between
  calls, so it does not matter which IO thread runs the next decode. As far as the upstream code
  read shows, the sherpa-onnx recognizer has no thread affinity: its native handle is a plain
  pointer, and the recognizer code read keeps no thread-local state and reads no thread identity
  (k2-fsa/sherpa-onnx, jni/ and csrc/ recognizer sources, read at the upstream version Breaker
  pins). ONNX Runtime documents that a session's Run may be called from multiple threads
  (onnxruntime core/session/inference_session.h). Limits: upstream gives no written guarantee, so
  the claim rests on reading the code. That reading covered the absence of thread-bound state in the
  recognizer paths read; it did NOT cover creating a recognizer on one thread and decoding with it
  on another, and that is re-checked against the real binding when the adapter lands. Only the
  recognizer paths were searched; the vendored Kotlin in this repo is the offline recognizer, and
  the native adapter this module will use does not exist yet, so the claim must be re-checked when
  the adapter lands; if it ever needs one thread, the engine must switch to a single fixed thread.

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
- The fetcher tests use a scripted opener and no network. One class, `JdkHttpOpenerLoopbackTest`,
  tests the real opener against a plain server on the loopback address only. Exactly one of its
  tests (the read timeout) waits on a real timer, by design: the timer is the behaviour under test.
- `DownloadPolicyContractTest` reads the real model registry against the default `DownloadLimits`:
  every registry address must be https on a first-hop host, and every entry must fit under the
  default size cap.
- Contract: `tests/contract/test_stt_ondevice_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_stt_ondevice_contract.py`
- Contract (decode bound pins): `tests/contract/stt_ondevice_bound_pins.py`, collected and run by the contract test above.
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
- The download side now exists (`HttpModelFetcher`, see Download side). Extraction does not, and
  neither does a real recognizer factory (the default, `UnavailableRecognizerFactory`, refuses to
  create one). No production code constructs `ModelInstaller` or `ModelLoader`. The engine takes the
  loader through `ModelLoaderPort`, and the app's wiring supplies it later, so the engine cannot
  transcribe real audio until a recognizer factory exists.
- There is no resume: an interrupted download starts again from zero. A partial file named
  `<id>.download` can stay in the staging directory after a process death, and the installer never
  sweeps it; the fetcher deletes its own same-name file at the start of the next attempt, so a
  partial for a model that is never tried again stays. The staged `checksums.txt` is never deleted
  by the installer (the next fetch replaces it).
- The download limits and the two host sets are recommended values and are NOT measured on a
  device. The redirect host was read from the live release when the fetcher was written. If the
  release host changes, a download fails closed (the "could not be downloaded" sentence) until the
  redirect set is updated.
- The channel is the platform's trust store, with no certificate pinning. A certificate authority
  the user installed on the phone can intercept the download, but it cannot defeat the digest
  check, which is the integrity control.
- The host rate limits unauthenticated API access (the limit itself was not measured). A 403 or 429
  shows as "could not be downloaded". The checksum list is fetched first, so on a clean install a
  network failure shows the checksum-list sentence, not the model sentence.
- The metered-data and Wi-Fi-only policy, the `INTERNET` permission, running the download off the UI
  thread and allowing one install at a time belong to the app wiring, not to this module.
- Not exercised yet: the real TLS handshake, Android's own `HttpURLConnection` behaviour (the tests
  run on the JDK's) and a full-size download on mobile data. A device check is owed before anyone
  says downloads work.
- The decode deadline does not cover loading the model: the registry lookup, hashing the archive and
  creating the recognizer are not bounded, so a load that never returns still holds the slot and
  every call queued behind it waits.
- An abandoned decode (one that passed its deadline) can leak one worker thread and one recognizer,
  up to the model's memory, until the native call returns. At most one at a time: `transcribe` and
  `transcribeAsync` return the busy failure while it runs. `preload` is not refused, so for a moment
  a second recognizer can exist next to the stuck one. If the native call never returns, the thread
  and the recognizer stay until the process ends. `close()` does not cancel it.
- A slow phone or a long clip can trigger a false timeout: the engine reports a failure while the
  native call keeps using CPU and battery until it ends.
- The limit (30 seconds plus three times the clip length) is a recommended value, not a
  measurement. It has not been tried on a device.
