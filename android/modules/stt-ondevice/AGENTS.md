# AGENTS.md — android/modules/stt-ondevice/

## Purpose

sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify. Offline speech-to-text on the phone using the upstream **sherpa-onnx**
library (Apache-2.0, upstream k2-fsa/sherpa-onnx). The module compiles against our own build of it:
a speech-recognition-only package at upstream commit 11afbd00, with text-to-speech and speaker
diarization switched off, published as a release file. The components of that package and their
licences are listed in `tools/sherpa-asr/NOTICE.md`, in the recipe folder that builds the package;
the same file is copied into the package. The app supplies that file at run time; nothing of the
library is copied into this repository.

**Build phase:** Phase 3 of `docs/04-build-order.md`. Needs first: `core` (on main) and `model-registry` (built; this module names models only through the registry's entries, ADR-016).

## Owns
sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify.

## Public Interface `core.SttEngine` (local path).

**In:** 16 kHz mono PCM float32.
**Out:** `SttResult(text, segments, language)` or a failure result: `SttError.LOCAL_MODEL_MISSING` for model problems, `SttError.OTHER` for audio, engine and store problems. The engine class is `OnDeviceSttEngine`; user sentences come from `ErrorMapping`. A decode that does not finish before its deadline is an `OTHER` failure too (see Engine notes).
The one public class for the native engine is `SherpaOnnxRecognizerFactory`, a `SherpaRecognizerFactory`.
The app passes `SherpaOnnxRecognizerFactory()` (optionally with a thread count) as the `factory`
argument of `ModelLoader`; the default there is still `UnavailableRecognizerFactory`, which refuses.

**Engine notes:**
- Local mode runs on the upstream sherpa-onnx library; none of its code is kept here. Do NOT reintroduce
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
  `transcribe`, `transcribeAsync` and `preload` return `ErrorMapping.decodeBusy()` (also `OTHER`) at
  once, and `preload` loads nothing; `diagnostics` is not refused. The constructor takes an injectable `DecodeDeadline`
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

**Native adapter side:**
- `SherpaOnnxRecognizerFactory` is the one public class here. `create(model)` refuses a model id
  with no streaming profile (`StreamingProfiles`: `small` and `tiny`, both English), asks
  `TransducerFileLocator` for the four files, and only then opens the engine, so a refusal leaves
  nothing to release. Every refusal and every failure to open is a `SherpaTranscriptionException`
  with a fixed message that carries no path, file name or model id; the engine's own error travels
  as the cause. A native library that fails to link (a `LinkageError`) is reported the same way, at
  open and at decode, so the loader and the engine see an engine failure and not an `Error`. Any
  other `Error` passes through the adapter (the engine has its own arms for out of memory and stack
  overflow). The thread count is a constructor argument, at least 1, default 2.
- `TransducerFileLocator` picks the files by which names are present, not from a fixed list: the
  encoder prefers the int8 file, the decoder and the joiner prefer the plain file, and each falls
  back to the other kind. A name belongs to a role when it starts with the role word followed by a
  dash or a dot and ends with `.onnx`; of several, the first by sort order is taken, and the token
  table must be named exactly `tokens.txt`. The files are returned only when each is a regular file
  of at least one byte.
- `SherpaOnnxRecognizer` (internal) is the `SherpaRecognizer` over a native streaming recognizer. It
  talks to the engine only through the small internal seam in `NativeStreaming.kt` (`NativeStream`,
  `NativeStreamingRecognizer`, `TransducerFiles`, `NativeStreamingOpener`), so it and its tests run
  without the library. One `decode` opens a stream, feeds the clip, then a block of 10,560 zero
  samples, marks the input finished, polls until the engine has nothing left to decode, reads the
  text and always releases the stream. Before any native call it refuses a recognizer that is
  already released, a sample rate other than 16 kHz and an empty clip. The number of polls is
  bounded (the frames in the clip and the silence, 100 per second, plus 16); past the bound the
  decode fails instead of looping. The transcript is one segment that spans the clip, or no segment
  when the text is blank. `release()` frees the native recognizer once, does nothing the second
  time and never throws.
- `SherpaOnnxBinding` (internal) is the only file that names the library's classes
  (`com.k2fsa.sherpa.onnx`). It sets 16 kHz, 80 mel bins, the CPU provider, greedy search and no
  endpoint detection, and leaves the model type empty so the library reads it from the model files.
  It never loads a native library itself; the library's own classes do that on first use.
- `RefusalMapping.kt` holds `mapRefusal` (a loader refusal to a failure result). It was moved out of
  `OnDeviceSttEngine.kt` with its text unchanged except for visibility (`internal`).

**Download side:**
- `HttpModelFetcher` is the real `ModelFetcher`. It is built on the platform's `HttpURLConnection`,
  so the fetcher needs no library of its own (the unpack side does, see Known Gotchas).
  `DownloadLimits` holds every bound as a plain parameter, so
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

**Unpack side:**
- Five files make it up. `ModelExtractor` unpacks a verified archive and is the only file in the
  module that imports the archive library. `ExtractionProfiles` is a static per-model table: for
  each model that has a profile (`small`, `tiny`) it names the four files the engine opens
  (encoder, decoder, joiner, tokens) and the bounds. `EntryRules` holds the checks that need only an
  entry's header (name, type flag, declared size). `BoundedStream` counts the decompressed bytes and
  throws past its limit instead of ending the stream early. `ExtractionOutcome` is the result:
  `Extracted`, or `Rejected` with a reason that says whether the archive, the phone or the free
  space was at fault.
- How it reads and writes: the archive is a tar inside bzip2, read through a 64 KiB buffer. Only the
  profile's flat file names are written, under the profile's own names (never a name taken from the
  archive), into a work directory in the staging directory. Each file is forced to storage before it
  is closed. Then ONE rename makes `<id>/files/`, so a partly unpacked model is never visible. Every
  other entry is read past and not written. After the last entry the stream is read to its end, so
  a missing end marker or extra bytes are noticed.
- What it refuses: an absolute path, a parent segment, a link, a non-regular or sparse entry, too
  many entries, an entry or the total over its bound (also the decompressed stream as a whole), a
  damaged stream or header, a truncated archive, a second top directory, duplicate names, a missing
  or empty needed file, and too little free space. A refusal deletes the work directory, creates no
  target and never touches the archive.
- The installer unpacks after the second check and the metadata writes, and only then reports
  `Installed`. On any refusal it deletes the whole model directory; a failed delete is reported
  through the could-not-delete sentence variant (`leftOnDisk`). A model with no profile is refused
  before anything is downloaded. An install over an existing one removes the earlier unpacked files
  before the archive is replaced; if that removal fails, the install is refused and the downloaded
  file is discarded. `ModelInstaller` takes the extractor as its new last constructor parameter,
  with the real one as the default.
- New installer refusal values: `UNSUPPORTED_MODEL`, `EXTRACT_REFUSED`, `EXTRACT_FAILED`, `NO_SPACE`.
  Three new user sentences: the archive could not be unpacked safely, the phone could not unpack it,
  and there is not enough free space to unpack it.
- The loader answers not-installed, and keeps the archive, when the archive verifies but `files/` does
  not hold all four profile files, each a regular file of at least one byte. That check comes after
  the archive check, so a tampered archive is still judged and deleted first. The loader hands the
  engine factory the `files/` directory.

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
of malware/backdoors/phone-home; the Kotlin bindings of the copy vendored in the base repo match upstream
signatures exactly. **Known bug:** issue #3983 — OOB write in the offline
transducer greedy-search decoder, reachable via a tampered .onnx (vocab_size
parsed with no upper bound). Mitigations adopted here: pinned immutable release-asset ids + upstream checksum.txt verification (closes the tampered-model delivery
vector); track #3983 and adopt the upstream decoder fix when it lands; revisit
(beam-search / non-transducer models) only if the fix doesn't land in a
reasonable window [2]. Whether our package (upstream commit 11afbd00) contains that fix has not been
checked. This audit covered the copy vendored in the base repo, which this module no longer uses (it
compiles against our speech-recognition-only package, see Known Gotchas); no audit of that package is
recorded here.

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
  worker thread and releases it on the slot thread, or on the worker when the decode was abandoned:
  one recognizer, two threads, one at a time. It keeps none between calls, so it does not matter
  which IO thread runs the next decode. What was read, at upstream release 1.13.8: the Kotlin files
  `OnlineRecognizer.kt` and `OnlineStream.kt` and the JNI files `online-recognizer.cc`,
  `online-stream.cc` and `common.h`. The Kotlin classes hold the native pointer in one plain field
  and use no thread-local value, lock, handler or thread identity. The JNI files keep no
  thread-local value, no lock, no stored environment pointer and no class, method or field id
  between calls; the only `static` in them is one helper function (a text search for the usual
  names, run with a control that shows the search finds JNI text). Limits: the C++ sources behind
  those JNI calls (recognizer, stream, feature and session code) and the ONNX Runtime inside the
  release file were NOT read here, so whether a recognizer created on one thread may be used from
  another is not confirmed; upstream gives no written guarantee; and the claim has not been tried
  on a device. If it ever needs one thread, the engine must switch to a single fixed thread for
  create, decode and release.

## Invariants
- Offline dictation E2E: tap → speak → send → text (F1).
- Tamper test: corrupt model file → load refused and the file deleted, so a retry
  downloads it fresh (T1, T21).
- Start latency < 1 s with preload (N1).
- **No model installed + local mode → clear "No model installed" error** (fix #1).
- The word stream never opens or holds the microphone; it only hears the audio
  the capture gives it. When the microphone yields to another app it gets no
  more audio, and the app stops it as at any capture end (at most one final
  update, then none) (F37, ADR-022) (not built yet).

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
- The unpack tests are `EntryRulesTest`, `BoundedStreamTest`, `ExtractionProfilesTest` (pins each
  profile against the listing of the real archive), `ModelExtractorTest`,
  `ModelExtractorRefusalTest`, `ModelExtractorBoundsTest`, `ModelExtractorCrashTest`,
  `ModelExtractorByIdTest`, `ModelExtractorSeamTest`, `ModelExtractorNamesTest`,
  `LocalModelStoreUnpackTest`, `ModelInstallerUnpackTest`, `ModelInstallerUnpackReinstallTest`,
  `ModelLoaderUnpackTest` and `ModelMessagesUnpackTextTest`. They use no network and no real
  archive.
- `TarFixtures` writes the test archives in memory (hand-written tar headers inside bzip2, damaged
  ones included); it is the only test file that imports the archive library.
  `ModelInstallerUnpackFixture`, in `ModelInstallerUnpackReinstallTest.kt`, is the shared fixture of
  the two installer unpack test classes.
- The adapter tests are `SherpaOnnxRecognizerTest` and `SherpaOnnxRecognizerFailureTest` (the adapter
  over `FakeNative.kt`, a scripted native engine), `SherpaOnnxRecognizerFactoryTest`,
  `TransducerFileLocatorTest`, `StreamingProfilesConsistencyTest` (the streaming ids equal the unpack
  ids, and the locator picks the files the unpack writes), `SherpaOnnxRecognizerWiringTest` (a real
  loader and engine over the real factory, with a fake opener) and `SherpaOnnxRealBindingTest` (the
  public factory on a plain JVM: a failure result, not an exception). The preload change has
  `OnDeviceSttEnginePreloadBusyTest`, and the four-names check has `LocalModelStoreFourNamesTest`
  and `ModelLoaderFourNamesTest`. None of them runs the native engine or a real model.
- Contract: `tests/contract/test_stt_ondevice_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_stt_ondevice_contract.py`
- Contract (decode bound pins): `tests/contract/stt_ondevice_bound_pins.py`, collected and run by the contract test above.
  It includes the pin that `preload` holds the busy guard.
- Contract (unpack pins): `tests/contract/stt_ondevice_extract_pins.py`, imported and run by the contract test above.
  It also pins the catalog version of the archive library to the release the unpack was written
  against, so a version change is a deliberate edit of that pin.
- Contract (release file pins): `tests/contract/stt_ondevice_aar_pins.py` holds the tests and
  `tests/contract/stt_ondevice_aar_checks.py` the rules they call; the contract test above imports
  and runs them. They pin the pin file, the settings and build wiring, the compile-only
  declaration, and that only the binding names the library and no main source loads a native library.
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
- Engine behaviour not changed yet: an `Error` (not an `Exception`) from the loader reaches the
  caller, except a native link failure, which the on-device adapter reports as an engine failure;
  the decode catch policy names two `Error` types and not others; a recognizer release
  that throws replaces the result; the engine and the loader keep two sets of user sentences that
  have drifted apart; a model the loader could not start is shown as "could not transcribe"; the
  wrong-family sentence names a model family the code does not check; `loadedModelId` in the
  diagnostics is not cleared after a refusal; diagnostics on a closed engine reads on the caller's
  thread; one salvaged test duplicates another; the `Outcome` enum is unused.
- The download side (`HttpModelFetcher`), the unpack side (`ModelExtractor`) and the native adapter
  (`SherpaOnnxRecognizerFactory`) exist. The default factory of `ModelLoader` is still
  `UnavailableRecognizerFactory`, which refuses to create a recognizer. No production code constructs
  `ModelInstaller` or `ModelLoader`. The engine takes the loader through `ModelLoaderPort`, and the
  app's wiring supplies it later, so the module cannot transcribe real audio until the app builds the
  loader with a `SherpaOnnxRecognizerFactory` and adds the release file (next bullet). The adapter, the
  release-file build route and their tests compile, and the tests run.
- The engine library is our own package of sherpa-onnx: the speech-recognition-only build at upstream
  commit 11afbd00, with text-to-speech and speaker diarization off, published as the release file
  `sherpa-onnx-v1.13.8-asr-only.aar` (an Android archive holding the classes and the native
  libraries). This module uses it to compile and nothing else: the build file declares it
  `compileOnly`, so it is not packaged, and the module's tests do not rely on it being on their
  classpath. The app must add the same release file at run time; that brings the classes and the
  native libraries, and the app chooses the ABIs (this package holds arm64-v8a and x86_64; the
  upstream k2-fsa file holds arm64-v8a, armeabi-v7a, x86 and x86_64; the app's base filter is
  arm64-v8a only). The upstream k2-fsa release file may be used only as a test control: it is not
  the pinned file and is never part of the shipped build.
- The build downloads the release file (about 23 MB) on a first build or an IDE sync, from the
  address in `sherpa-onnx-aar.properties`, through one exclusive repository in
  `settings.gradle.kts`. The task `verifySherpaAar` checks its SHA-256 against the pin and fails
  closed: a missing key, a digest that is not 64 lowercase hex digits, a version that differs from
  `gradle/libs.versions.toml`, anything but exactly one regular file named for the version, or a
  digest mismatch stops the build. It runs before this module's own build tasks only, and a
  command line can skip it with `-x`. When the app adds the release file it must add its own
  `dependsOn` on that task; that wiring is not in this module. The pin file is the only
  place the build of the library changes (its version must still equal the catalog
  version); which build the project ships is decided outside this module. At run time the module's
  network use is unchanged: it still reaches the network only to download a pinned model.
- What the build has shown: the adapter and the build route compile, the release file resolves and
  its SHA-256 is checked by the build, and the module tests pass (684 of 684) on a single core and
  in parallel. Not verified: the minimum SDK of the release file. None of this says the library
  loads or runs on a device (see the items below).
- `numThreads` defaults to 2 (`SherpaOnnxRecognizerFactory.DEFAULT_NUM_THREADS`) and the silence fed
  after the clip is 10,560 samples, 0.66 s (`SherpaOnnxRecognizer.TAIL_PADDING_SAMPLES`). Both are
  device placeholders and are NOT measured: whether that silence finishes the last word, or is too much,
  and which thread count suits a phone, are unknown. The 80 mel bins in the binding equal the
  library's own default and have not been checked against either model. The refusals of a rate
  other than 16 kHz and of an empty clip are this adapter's own rule, not the library's.
- Owed on a device before anyone says on-device transcription works: a first load and a decode of a
  known clip for BOTH models, `tiny` and `small` (the model metadata of neither was read here, so
  that each really is a streaming transducer is judged from its file names only); the memory one
  recognizer needs and the start latency, neither of which is measured; the size the native
  libraries add to the app (the file list of this package was not read here; the upstream k2-fsa
  file, a different build, has for arm64-v8a `libonnxruntime.so` at 22,249,560 bytes and
  `libsherpa-onnx-jni.so` at 4,771,760 bytes); the casing, punctuation and
  error rate of the text (English only); a check that the native libraries are aligned for 16 KB
  pages (not checked); and, if the app ever turns on code shrinking (no such setting was found in the
  app build file), keep rules for `com.k2fsa.sherpa.onnx`, because the JNI code looks fields up by
  name and the upstream k2-fsa file ships an empty `proguard.txt` (the one in this package was
  not read).
- The library is reported to end the whole process, not to throw, when a model file is missing, a
  line of the token table does not parse, or the model metadata is missing. That comes from the
  design notes: the C++ sources were not read here. The locator and the four-names check keep a
  missing or empty file, or a directory in its place, from reaching the library, but they cannot
  catch a damaged token table, and nothing in-process can recover from that exit.
- This package is built with text-to-speech and speaker diarization off. The upstream k2-fsa release
  file also contains text-to-speech classes (the `OfflineTts` family); this module uses none of
  them, and whether this package still holds any such class was not read here. The components of
  this package and their licences are listed in `tools/sherpa-asr/NOTICE.md`, which the recipe also
  copies into the package; this module does not change the repository NOTICE. As that file states
  them: sherpa-onnx Apache-2.0; onnxruntime (prebuilt, csukuangfj/onnxruntime-libs v1.28.2) MIT plus
  its ThirdPartyNotices; kaldi-native-fbank Apache-2.0; kissfft BSD-3-Clause; kaldi-decoder
  Apache-2.0; kaldifst v1.8.0 Apache-2.0; openfst Apache-2.0; simple-sentencepiece Apache-2.0;
  nlohmann/json MIT. The list was read from that file; the package itself was not opened here. This
  card makes no licence statement beyond the one in Purpose and this list.
- The extractor writes at most the profile's written-bytes bound and needs free space equal to that
  bound before it starts (80 MiB for `tiny`, 160 MiB for `small`). It compares the bound with the
  free space of the staging directory; that is a bound, not a measure of what the files need. A
  refusal for space deletes the model, so the next try downloads the archive again.
- A crash between the second check and the rename leaves the verified archive and no `files/`. The
  store still counts the model as installed (the archive is there), but the loader answers not
  installed and keeps the archive; installing again heals it. A work directory left in the staging
  directory by the crash stays until the next install of that model, which removes it first.
- `isExtracted` is true only when the model has an unpack profile, `files/` is a directory, and each
  of the profile's four names is a regular file of at least one byte in it; other files there do
  not matter. A model with no profile is never extracted. A removal of the earlier unpacked files
  that fails part-way is refused by the installer, and the old archive and a partly removed
  `files/` stay; when fewer than the four names remain, the loader now answers not-installed and
  keeps the archive. The check looks at names, file kind and size above zero only: it does not
  hash the four files (see the next bullet), and the locator checks the same four again before the
  engine opens them.
- The loader hashes only the archive, on every load, and never the four unpacked files. A change to
  an unpacked file after install is not detected, so the invariant "corrupt model file: load refused
  and the file deleted" holds for the archive only.
- The unpack side adds a library to the module: the archive library (`commons-compress`,
  Apache-2.0) at the version in `gradle/libs.versions.toml`, which carries the dated source line.
  Gradle also resolves three libraries it declares: `commons-codec`, `commons-io` and
  `commons-lang3` (Apache-2.0); the catalog names their versions in comment lines and has no
  entries for them. Main code imports the archive library in `ModelExtractor.kt` only.
- The profile bounds come from the entry listing of each real archive (names and sizes) plus
  headroom, and a test pins the listing figures. The extractor itself has not been run on a real
  archive: its tests use small archives they write themselves. Details a listing does not show
  (extended headers, for example) have not been seen.
- Existing tests that install a model were re-pointed to serve a real archive
  (`TarFixtures.tinyArchive()`), and the loader tests seed a `files/` directory
  (`Fixtures.seedExtracted`). The installer tests use the real extractor by default, so they need
  free disk space in the temp folder equal to the tiny model's written-bytes bound (80 MiB); on a
  full disk they fail with a no-space refusal, not a hang.
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
  `install()` now also unpacks inside the same blocking call, so the whole call must stay off the UI
  thread; how long the unpack takes on a phone has not been measured.
- Not exercised yet: the real TLS handshake, Android's own `HttpURLConnection` behaviour (the tests
  run on the JDK's) and a full-size download on mobile data. A device check is owed before anyone
  says downloads work.
- The decode deadline does not cover loading the model: the registry lookup, hashing the archive and
  creating the recognizer are not bounded, so a load that never returns still holds the slot and
  every call queued behind it waits.
- An abandoned decode (one that passed its deadline) can leak one worker thread and one recognizer,
  up to the model's memory, until the native call returns. At most one at a time: `transcribe`,
  `transcribeAsync` and `preload` return the busy failure while it runs, so no second recognizer is
  created next to the stuck one. If the native call never returns, the thread and the recognizer
  stay until the process ends. `close()` does not cancel it.
- A slow phone or a long clip can trigger a false timeout: the engine reports a failure while the
  native call keeps using CPU and battery until it ends.
- The limit (30 seconds plus three times the clip length) is a recommended value, not a
  measurement. It has not been tried on a device.
- Two source files are over the soft line cap of 300 lines and under the hard cap of 500:
  `OnDeviceSttEngine.kt` at 308 lines and `LocalModelStore.kt` at 307 lines. Neither is split further
  for now.
- The test `test_build_file_adds_exactly_one_external_dependency` (in the unpack pins) is false by
  its own name, because the build file now adds two coordinates. It is to be renamed in a later
  change.
- The thread-affinity reads above were made on upstream release 1.13.8. They were not repeated on
  the source at commit 11afbd00 that this package is built from.
