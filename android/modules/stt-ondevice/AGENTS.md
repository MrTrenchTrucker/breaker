# AGENTS.md — android/modules/stt-ondevice/

## Purpose

sherpa-onnx local transcription (fallback engine), model lifecycle, checksum verify. Offline speech-to-text on the phone using the base repo's **sherpa-onnx**
engine (vendored from XIAOMI CORPORATION, upstream Apache-2.0, swept clean by Security Review).

**Build phase:** Phase 3 of `docs/04-build-order.md`. Needs first: `core` (on main) and `model-registry` (not built yet; this module names models only through its generated constants, ADR-016).

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
- Tamper test: corrupt model file → load refused (T1, T21).
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
