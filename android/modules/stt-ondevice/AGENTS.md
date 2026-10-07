# AGENTS.md — android/modules/stt-ondevice/

## Purpose

sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify. Offline speech-to-text on the phone using the base repo's **sherpa-onnx**
engine (vendored from XIAOMI CORPORATION, upstream Apache-2.0, swept clean by Security Review).

**Build phase:** Phase 3 of `docs/04-build-order.md`. Needs first: `core` (on main) and `model-registry` (built; this module names models only through the registry's entries, ADR-016).

## Owns
sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify.

## Public Interface `core.SttEngine` (local path).

**In:** 16 kHz mono PCM float32.
**Out:** `SttResult(text, segments, language)` or `SttError.LOCAL_MODEL_MISSING`.

**Engine notes:**
- Keep the base's sherpa-onnx local-mode code (already swept). Do NOT reintroduce
  the base's cloud/Groq fallback path (Security Review fix #1).
- whisper.cpp AAR is a **fallback only** if sherpa-onnx lacks a needed feature.

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
- Load at startup (or lazy per setting); preload on idle to meet N1 (< 1 s start).

**Security Review's sherpa-onnx audit [2]:** safe to use with three mitigations — clean
of malware/backdoors/phone-home; the vendored Kotlin bindings match upstream
signatures exactly. **Known bug:** issue #3983 — OOB write in the offline
transducer greedy-search decoder, reachable via a tampered .onnx (vocab_size
parsed with no upper bound). Mitigations adopted here: pinned immutable release-asset ids + upstream checksum.txt verification (closes the tampered-model delivery
vector); track #3983 and adopt the upstream decoder fix when it lands; revisit
(beam-search / non-transducer models) only if the fix doesn't land in a
reasonable window [2].

**Threading:** inference blocks → dedicated single-thread executor, never UI thread.

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
- Nothing in this module downloads or extracts yet: there is no real
  `ModelFetcher`, no real recognizer factory (the default,
  `UnavailableRecognizerFactory`, refuses to create one), and no production
  code calls `ModelInstaller` or `ModelLoader`. Those arrive with later work.
