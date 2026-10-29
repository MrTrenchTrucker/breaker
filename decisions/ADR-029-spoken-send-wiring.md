# ADR-029: The spoken send ("And I'm Gone") is wired through one app class, PhraseSendHandler

**Status:** accepted, amended 2026-10-29 (a spoken Send acts only while the tile is shown)
**Date:** 2026-10-20
**Approved by:** the project owner (2026-10-20 order)

Accepted. Built: the app wiring (PhraseSendHandler, the feed tap and the owner link) is in the app module.
It carries out ADR-004 (two phrases, audio trimmed at the send-phrase
onset) and keeps ADR-022's microphone-sharing rules.

## Context
The parts exist but are not connected. The `phrases` module has
`PhraseDetector` (`start(onPhrase)`, `stop()`), which reports where the send
phrase began. The `stt-ondevice` module has `FedWordStream.feed(samples)`. The
app's take code already accepts a trim point:
`DictationRunner.finish(trimBeforeMs: Long? = null)`. The app module does not
depend on `android_phrases` (its `depends_on` in `modules.toml` does not list
it), so nothing in the app can start the detector today, and no class decides
when a spoken send ends a take.

The detector's callback runs on the word-stream thread, while every take
begin, cancel and finish runs on the single take worker (`TileHost.takeWorker`,
ADR-026). The send must not run on the wrong thread, must not fire twice for
one take, and must never lose a take when the word stream fails.

## Decision
1. **One owner.** A new app wiring class, `PhraseSendHandler`, is the one owner
   of the detector's start and stop, of the audio tee to the word stream, and
   of handing a spoken send to the take. No other class starts or stops the
   detector.
2. **Threads.** `PhraseDetector.start` and `stop` are called only from the take
   worker. The detector's callback (word-stream thread) does not finish the
   take itself: `PhraseSendHandler` submits `finish(trimBeforeMs)` to the take
   worker, with `trimBeforeMs` set where the send phrase began, so the phrase
   never reaches the text (ADR-004).
3. **One send per take.** The rule that a take is sent once lives in
   `PhraseSendHandler` and serves both the Home mic and the tile. The tile's
   tap path keeps its own `TileCoordinator.sendPushed` flag for the tap; there
   is no second copy of the spoken-send rule.
4. **Stop is idempotent.** Cancel, a device failure and a microphone yield
   (ADR-022) each stop the detector, and stopping twice is safe.
5. **The tee (option A).** The audio capture thread passes each frame to
   `FedWordStream.feed`. Feeding never blocks the capture thread and never
   throws into it: the hand-off is bounded, its drop policy is stated in the
   code, and dropped frames are counted. A word-stream failure, or any of the
   five paths on which the stream gives no final result (pinned by
   `OnDeviceWordStreamNoFinalTest`), never stops or loses the take; the take
   then ends by tap, check or cancel as before.
6. **Order of work.** The phrase wiring lands after the Home mic defects and
   APK 8. The new registry edge `app -> android_phrases` is added in that code
   change, not before: `modules.toml` `depends_on`, `android/app/build.gradle.kts`,
   the app card and README, and the generated dependency map, together with a
   short ADR for the new edge.
7. `TileCoordinator.kt` and `HomeTakeAdapter` keep their own thin call sites to
   the take's begin and cancel. A shared helper for those steps is a later
   follow-up, not part of this design.

## Reasons
- One owner for start, stop, tee and send means one place to check for the
  thread rule and the once-per-take rule; two owners would drift, as the two
  mics did before ADR-026.
- Running every take step on the take worker keeps the rule ADR-026 already
  relies on; the detector's own thread only reports.
- The take is the user's words. A failing phrase detector must cost only the
  hands-free send, never the recording.

## Consequences
Rules in: `PhraseSendHandler` in the app's wiring; a new dependency from the
app to the `phrases` module, added with its code; a bounded, counting,
non-throwing hand-off from capture to the word stream; tests for the
once-per-take rule, the idempotent stop and the five no-final paths during a
take.

Rules out: finishing a take from the word-stream thread; a second copy of the
once-per-take rule in the tile or the Home code; a feed that can block or
crash audio capture; adding the registry edge before the code that uses it.

Amended 2026-10-29: a spoken Send for a take the tile holds acts only while the
tile is shown. With the tile hidden it does nothing and logs one line through
the existing phrase reason.
