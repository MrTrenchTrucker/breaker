# AGENTS.md - android/modules/phrases/

## Purpose

Voice phrases: 'Breaker Breaker' wake + 'And I'm Gone' send detection, reporting where the send phrase began. Hands-free voice control: two CB-slang phrases:
- **"Breaker Breaker"** (idle): wake, then the tile appears AND **recording starts
  automatically** (F4).
- **"And I'm Gone"** (recording): send: stop recording, **report where the
  phrase began** (`PhraseEvent.Send(trimBeforeMs)`) so `DictateUseCase.stopCapture`
  trims the capture there and the phrase never enters the transcription (F9),
  dispatch send (server-primary, then format, then commit or clipboard) (F5).

**Build phase:** Phase 10; it can be spiked alongside Phase 6. Needs first: `core` (on main, with the `WordStream` port); it gets words through core's `WordStream` port and needs no audio module.

## Owns
Voice phrases: 'Breaker Breaker' wake + 'And I'm Gone' send detection, reporting where the send phrase began.
Core (`DictateUseCase.stopCapture`) trims the capture at that point; this
module never touches the dictation audio.

## Public Interface
Implements `core.PhraseTrigger`: `start(onPhrase: (PhraseEvent) -> Unit)`,
`stop()`, `isListening`. `onPhrase` delivers a `PhraseEvent`: `Wake` (no data:
the capture starts after the wake phrase, so there is nothing to trim) or
`Send(trimBeforeMs: Long?)`, the milliseconds from the start of the active
capture to where the send phrase began. This module takes that offset from the start time of the heard word; it
does not buffer or cut the dictation audio itself (see Audio handling).

**Detector (built):**
- `PhraseDetector`: its constructor takes a core word stream (`WordStream`) and implements the core phrase trigger. It reports `PhraseEvent.Wake` (no data) when "breaker breaker" is heard, and `PhraseEvent.Send` with the start of the heard word that holds "and" when "and I'm gone" is heard. Each phrase is reported at most once per utterance. It holds no dictation state. The order and happens-before rules belong to the word stream (core `WordStream`), not to this module. No report reaches the callback after `stop`, and `stop` is safe to call from inside the callback.
- The matching itself is an internal class, PhraseMatcher, and is not part of this public interface.

**Built / Not built:**
- Built: the phrase matcher and the detector over the core word stream, with tests that run on scripted word streams.
- Built elsewhere, in `stt-ondevice`: `OnDeviceWordStream` (internal there), which implements core's `WordStream` over the on-device engine, and the public `SherpaOnnxWordStreamFactory`, whose `create(model)` gives a `FedWordStream`. Not built: the app wiring that feeds that stream and filters Wake and Send; the KWS upgrade path; training; any measurement on a device.
- PLANNED (Spoken Send, approved design, no code yet; lands after the Home mic work): a new app wiring class, `PhraseSendHandler`, is the ONE owner of the detector's start and stop, the audio tee and the Send hand-off. It calls `PhraseDetector.start`/`stop` only from the take worker, and stops idempotently on Cancel, a device failure or a microphone yield. The detector's callback runs on the word-stream thread; the handler turns a Send into `finish(trimBeforeMs)` submitted to the take worker, at most one Send per take, for Home and the tile alike. The tee: the capture thread calls `FedWordStream.feed`, which never blocks or throws into capture; a stream failure or a no-final path never stops or loses the take. The app -> `android_phrases` registry edge is added with that code, not before; this module's interface does not change.

**v1 mechanism: streaming on-device ASR + phrase matching:**
- A recogniser adapter in another module runs the sherpa-onnx ASR in **streaming
  mode** and feeds its words to this module through the core word stream; each
  update is matched against "breaker breaker" and "and i'm gone" after
  normalising case, punctuation and the spellings of "i'm" ("i'm", "im", "i m").
  Filler words inside a phrase are not skipped.
- No training, no new dependency. Power is a non-issue (high-power mode, plugged in).

**Upgrade path: sherpa-onnx KWS (documented, not v1):**
- Dedicated keyword-spotting model (how "Hey Google" / "Alexa" work) for lower
  CPU/latency. Requires a small custom-trained model for our phrases; sherpa-onnx
  ships the KWS training toolkit (a few hundred clips per phrase).

**Audio handling:**
- This module never buffers or cuts the dictation audio. It runs no recogniser of its own:
  it matches the phrases in the words the word stream reports and, on the send phrase, works
  out `trimBeforeMs`: the offset from the start of the active capture to
  where the phrase began, reported as `PhraseEvent.Send(trimBeforeMs)`.
- `DictateUseCase.stopCapture(session, audioSource, trimBeforeMs)` is what
  keeps the audio before that offset and drops the phrase onward; a `null`
  offset keeps the whole capture. The dictation clip is core's, never this
  module's.

**Listening:** `isListening` is true from `start` until `stop`. The dictation states that the
phrases drive (core `DictationState`: IDLE, ARMED, RECORDING, SENDING) are kept by core and the app,
not by this module.

**Permissions (F11):** the app asks for them at first start, one screen per permission (microphone, display over other apps, notifications and the accessibility service); this module requests none.
**High-power mode (F12):** the app checks power-saving; this module does nothing with it.

## Invariants
- "Breaker Breaker" is reported as `PhraseEvent.Wake` when the two words are heard next to each other; how reliably that holds on a device is not measured yet (see Known Gotchas).
- "And I'm Gone" is reported as `PhraseEvent.Send` with `trimBeforeMs` at the
  phrase's onset, which is what lets core keep the phrase out of the
  transcription (F9).
- False positives below 1 per day on device are the target (N10); no test here measures them (see Known Gotchas).
- The manual shake and tap paths are not part of this module (they belong to `gesture` and `overlay`).
- This module never opens the microphone. Wake listening (F37, ADR-022) is the app's rule: it hears audio only while Breaker is armed, wake listening is switched on and the microphone has not been given up to another app, and the app decides when it comes back. It is not built on main yet.
- The first-start permission screens are not part of this module; the app asks for each permission on its own screen (F11).
- Phrases are matched inside one recogniser update only: no words are kept from an earlier update, so a phrase cut by an endpoint is not reported.
  Tested by: `PhraseMatcherTest`.
- The send offset is the start of the HEARD WORD that holds the send phrase (the owning HeardWord's startMs), which is earlier than the literal 'and' when 'and' is mid-HeardWord, never the start time of the other two words or of the utterance.
  Tested by: `PhraseMatcherTest`, `PhraseDetectorTest`.
- Each phrase is reported at most once per utterance; each listening run starts with a fresh reported set; it is cleared after a final update.
  Tested by: `PhraseMatcherTest`, `PhraseDetectorTest`.
- A character that is not a letter, a digit or an apostrophe ends a word; a piece with no letter or digit is skipped; each piece carries the start of the heard word it came from.
  Note (a): the curly apostrophe (U+2019) is treated like the ASCII apostrophe: it is mapped before the split, so it is content, not a boundary.
  Note (b): a hyphenated "i-m" splits to "i","m" and matches the existing i + m form.
  Tested by: `PhraseMatcherTest`, `PhraseMatcherFixTest`.
- No report reaches the callback after `stop`, and a `stop` called from inside the callback returns safely.
  Tested by: `PhraseDetectorTest`.
- A PhraseDetector owns its WordStream: nobody else starts or stops that stream; detector.stop() stops it (by design).
  Tested by: `PhraseDetectorTest`.
- The module has no Android import, no thread, timer or clock, and its only imports are core and `java.util.Locale`.
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
- This module never imports `audio` or `stt-ondevice`, and it never receives audio: words arrive through core's `WordStream` port. The adapter that feeds that port is `OnDeviceWordStream` in `stt-ondevice`, made through its public `SherpaOnnxWordStreamFactory`. No app wiring for it is on main yet.
- A phrase cut by an endpoint (half in one utterance, half in the next) is not reported. Device check.
- Device-only and NOT verified: that the Send offset lands on the word "and" on a real capture; false positives (N10); start latency (N1).
- Matching is exact word adjacency: no filler words and no fuzzy matching yet.
- The word source exists in `stt-ondevice` (`SherpaOnnxWordStreamFactory` gives a `FedWordStream`), but no app wiring is on main, so the detector cannot run on a device yet.
- The callback runs on the word-stream thread, not the take worker. Whoever wires it (PLANNED: `PhraseSendHandler`) must hand the Send to the take worker; in the approved design, start and stop are called only from the take worker, even though a stop from inside the callback is safe here.
- On a stream someone else started, `isListening` is true but no event arrives.
