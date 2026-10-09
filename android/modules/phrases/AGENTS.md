# AGENTS.md — android/modules/phrases/

## Purpose

Voice phrases: 'Breaker Breaker' wake + 'And I'm Gone' send detection, reporting where the send phrase began. Hands-free voice control — two CB-slang phrases:
- **"Breaker Breaker"** (idle): wake — tile appears AND **recording starts
  automatically** (F4).
- **"And I'm Gone"** (recording): send — stop recording, **report where the
  phrase began** (`PhraseEvent.Send(trimBeforeMs)`) so `DictateUseCase.stopCapture`
  trims the capture there and the phrase never enters the transcription (F9),
  dispatch send (server-primary → format → commit/clipboard) (F5).

**Build phase:** Phase 10; it can be spiked alongside Phase 6. Needs first: `core` (on main); it gets audio through core's ports, so `audio` (Phase 2) should exist before it is finished.

## Owns
Voice phrases: 'Breaker Breaker' wake + 'And I'm Gone' send detection, reporting where the send phrase began.
Core (`DictateUseCase.stopCapture`) trims the capture at that point; this
module never touches the dictation audio.

## Public Interface
Implements `core.PhraseTrigger`: `start(onPhrase: (PhraseEvent) -> Unit)`,
`stop()`, `isListening`. `onPhrase` delivers a `PhraseEvent` — `Wake` (no data:
the capture starts after the wake phrase, so there is nothing to trim) or
`Send(trimBeforeMs: Long?)`, the milliseconds from the start of the active
capture to where the send phrase began. This module computes that offset; it
does not buffer or cut the dictation audio itself (see Audio handling).

**Detector (built):**
- `PhraseDetector` — its constructor takes a core word stream (`WordStream`) and implements the core phrase trigger. It reports `PhraseEvent.Wake` (no data) when "breaker breaker" is heard, and `PhraseEvent.Send` with the start of the word "and" when "and I'm gone" is heard. Each phrase is reported at most once per utterance. It holds no dictation state, onUpdate is called one at a time, in order; each call happens-before the next, so everything one call wrote is seen by the next, even when calls run on different threads; the adapter's single-slot dispatcher gives that ordering; no update is delivered after stop() returns.; reports nothing after `stop`, and `stop` is safe to call from inside the callback.
- The matching itself is an internal class, PhraseMatcher, and is not part of this public interface.

**Built / Not built:**
- Built: the phrase matcher and the detector over the core word stream, with tests that run on scripted word streams.
- Not built: the recogniser adapter that feeds words into the word stream (another module); the app wiring that filters Wake and Send; the KWS upgrade path; training; any measurement on a device.

**v1 mechanism — streaming on-device ASR + phrase matching:**
- A recogniser adapter in another module runs the sherpa-onnx ASR in **streaming
  mode** and feeds its words to this module through the core word stream; each
  update is matched against "breaker breaker" and "and i'm gone" after
  normalising case, punctuation and the spellings of "i'm" ("i'm", "im", "i m").
  Filler words inside a phrase are not skipped.
- No training, no new dependency. Power is a non-issue (high-power mode, plugged in).

**Upgrade path — sherpa-onnx KWS (documented, not v1):**
- Dedicated keyword-spotting model (how "Hey Google" / "Alexa" work) for lower
  CPU/latency. Requires a small custom-trained model for our phrases; sherpa-onnx
  ships the KWS training toolkit (a few hundred clips per phrase).

**Audio handling:**
- This module never buffers or cuts the dictation audio. It runs its own
  streaming-ASR detection to find the phrases and, on the send phrase, works
  out `trimBeforeMs` — the offset from the start of the active capture to
  where the phrase began — reporting it as `PhraseEvent.Send(trimBeforeMs)`.
- `DictateUseCase.stopCapture(session, audioSource, trimBeforeMs)` is what
  keeps the audio before that offset and drops the phrase onward; a `null`
  offset keeps the whole capture. The dictation clip is core's, never this
  module's.

**State machine:** IDLE → (wake phrase) → RECORDING → (send phrase) → SENDING →
IDLE. Manual shake/tap can enter/exit the same states (shared with `gesture` +
`overlay`).

**Permissions (F11):** mic, sensor, overlay/foreground service, and notification
permissions are requested together at first startup.
**High-power mode (F12):** verify Android power-saving is off; prompt user to
enable high-performance mode if not.

## Invariants
- "Breaker Breaker" is reported as `PhraseEvent.Wake` reliably (tune the
  threshold on device); the app then starts recording (F4).
- "And I'm Gone" is reported as `PhraseEvent.Send` with `trimBeforeMs` at the
  phrase's onset, which is what lets core keep the phrase out of the
  transcription (F9).
- False positives < 1/day on device (N10).
- Manual shake/tap path unaffected (regression).
- Wake listening hears audio only while the app runs it: Breaker armed, wake
  listening switched on, and the microphone not given up to another app. The
  app decides when it comes back (F37, ADR-022) (not built yet).
- Startup permission prompt grants all required permissions (F11).
- Phrases are matched inside one recogniser update only: no words are kept from an earlier update, so a phrase cut by an endpoint is not reported.
  Tested by: `PhraseMatcherTest`, `PhraseDetectorTest`.
- The send offset is the start of the HEARD WORD that holds the send phrase (the owning HeardWord's startMs), which is earlier than the literal 'and' when 'and' is mid-HeardWord, never the start time of the other two words or of the utterance.
  Tested by: `PhraseMatcherTest`, `PhraseDetectorTest`.
- Each phrase is reported at most once per utterance; each listening run starts with a fresh reported set; it is cleared after a final update.
  Tested by: `PhraseMatcherTest`, `PhraseDetectorTest`.
- A character that is not a letter, a digit or an apostrophe ends a word; a piece with no letter or digit is skipped; each piece carries the start of the heard word it came from.
  Note (a): the curly apostrophe (U+2019) is treated like the ASCII apostrophe — mapped before the split, so it is content, not a boundary.
  Note (b): a hyphenated "i-m" splits to "i","m" and matches the existing i + m form.
  Tested by: `PhraseMatcherTest`.
- No report reaches the callback after `stop`, and a `stop` called from inside the callback returns safely.
  Tested by: `PhraseDetectorTest`.
- The module has no Android import, no thread, timer or clock, and it imports core only.
  Tested by: `ModuleHygieneTest`.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Gesture (gesture)
- Overlay (overlay)
- Training (training-client)

## Test Locations
- Unit (Kotlin): `android/modules/phrases/src/test/kotlin/`. Run: `./gradlew :android:modules:phrases:test`
- Contract: `tests/contract/test_phrases_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_phrases_contract.py`
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
- This module must report where the send phrase began
  (`PhraseEvent.Send(trimBeforeMs)`) accurately; `DictateUseCase.stopCapture`
  is what actually trims the audio at that offset (F9).
- Audio arrives through `core.AudioSource`, wired by `android/app` (ADR-001); this module never imports `audio` or `stt-ondevice`. Words arrive through core's `WordStream` port (its word stream); the recogniser adapter that feeds it is not built yet.
- A phrase cut by an endpoint (half in one utterance, half in the next) is not reported. Device check.
- Device-only and NOT verified: that the Send offset lands on the word "and" on a real capture; false positives (N10); start latency (N1).
- Matching is exact word adjacency: no filler words and no fuzzy matching yet.
- The word source (the recogniser adapter) is not built, so the detector cannot run on a device yet.
