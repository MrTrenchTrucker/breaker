# ADR-037: The busy face follows the microphone and clears when it is free

**Status:** accepted; built and proven on the JVM against fakes; the platform reading is not yet tried on a phone
**Date:** 2026-11-02

Related: ARCHITECTURE F37 (the microphone yields to every other app), ADR-022 (text insert, wake listening), ADR-024 (the tile's faces), ADR-026 (the Home mic is the tile's mic).

## Context

When another app or a phone call holds the microphone, Breaker lets it go and ends its take. The tile then shows the busy face: the microphone with a red circle and a slash through it (ARCHITECTURE F37, ADR-024). That part works as designed.

The gap is how the busy face ends. Before this record the app kept a flag that said the microphone was taken. The flag cleared only when the person did something: a new take began, the switch went off, or the next take ended. Nothing looked at the microphone while the tile sat idle. So the other app let go, the microphone was free, and the tile still said it was taken until the person tapped.

The project owner met this with the Home mic and with the floating tile. The request was an update to the architecture, a decision record, and the cards of the modules involved.

The design never said how the face ends. ADR-024 names "microphone busy" only as one of the final outcomes of a take. The app card clears the flag at the three points above. The audio card checks whether the phone silences our own recorder during a take. It does not check whether another app has let go. So this is a gap in the design, not only a slip in the code.

## Decision

Two words first. A callback is a function the phone calls when something changes, so the app does not have to keep asking. A port is a small interface that one module offers and another module uses, so neither needs to know how the other works.

1. **The busy face is a live reading of the microphone.** It is not a flag that waits for a tap. While the face shows, the app watches for the other app to let go, and clears the face by itself.
2. **How the app watches.** It uses the phone's recording-state callback (`AudioManager.registerAudioRecordingCallback`). The phone pushes the list of active recordings each time the list changes. There is no timer and no polling. The app registers the callback only while the busy face shows, and when a begin is refused because the microphone is taken. It unregisters when the face clears and when the switch goes off. No new permission and no new dependency are needed.
3. **One read at the start.** The phone is not promised to send an answer the moment the callback is registered. So the app also reads the current list once, at registration (`AudioManager.getActiveRecordingConfigurations`), and applies the same free test to it. The callback then handles every later change.
4. **The free test.** The microphone is free when both of these hold: no other app is recording in the list, and `AudioManager.getMode()` is not `MODE_IN_CALL`. Our own recording is left out of the list by its session (the id the phone gives each recording). During a cellular call the mode is `MODE_IN_CALL`, so the face stays busy. `MODE_IN_COMMUNICATION` is not used. An app that forgets to restore that mode would leave the face stuck, which brings the bug back. Internet calls normally record through the recorder, so they are expected to show in the list (this is not verified, see the limits below). Reading the mode needs no permission.
5. **If the list cannot be read,** the face stays, and the tap clear is the fallback.
6. **Where it lives.** The callback is a new port of the audio module, because the audio module owns the recorder. The port lets the app start and stop watching. It hands the app its answers; which thread they arrive on is chosen when the code is written. The app owns the face flag, which lives in its take-yield controller, and it wires the port to that flag. The overlay does not change: it already draws `MIC_BUSY` when the app asks. No port of `core` changes.
7. **The flag clears on release too.** Rule 19 of the app card gets a fourth clear point. The clear points become: a begin starts, the switch goes off, a take ends, or the phone reports that the microphone is free.
8. **Acceptance tests,** for the code change that follows. Each one must be seen failing before it passes:
   - the face clears when the callback reports the microphone free;
   - the face stays while another app still holds the microphone;
   - our own recording does not count as busy;
   - the callback is unregistered when the face clears, and when the switch goes off;
   - a list that throws, or comes back empty, keeps the face;
   - the one read at registration clears the face when nobody else is recording;
   - the in-call mode keeps the face;
   - a stuck in-communication mode does not keep the face.

## Reasons

- A push costs nothing while nothing changes. A timer wakes the phone for no reason, and it answers late.
- The audio module owns the recorder, so the call to the phone belongs there. The app already owns the face.
- Watching only while the face shows keeps the cost to the short time the face is up.
- No new permission and no new dependency means nothing new for the user to allow.
- The one read at registration covers the case where the phone sends no first answer.
- Keeping the tap clear means a failure to read the list leaves the person no worse off than before this record.
- The mode test keeps the face during a cellular call, and leaving out `MODE_IN_COMMUNICATION` means a forgotten mode cannot hold the face for ever.

## Consequences

Rules in: the busy face is a live reading of the microphone; the app watches only while the face shows; the free test is no other recording and a mode that is not `MODE_IN_CALL`; the watcher is a port of the audio module and the app wires it to its face flag; the tap clear stays as the fallback.

Rules out: a timer or polling loop; clearing the face only on the next tap; a watcher that runs all the time; a change to the overlay or to a `core` port for this.

**Limits that remain.** These are written down so nobody reads the design as stronger than it is.

- A VoIP app that holds the microphone without recording through the recorder is not seen. This is not verified.
- If the phone leaves the in-call mode set after a call ends, the face stays until the phone resets the mode. The tap clear still works.
- Delivery of the callback while the app is not on screen is not verified. It needs a phone check: the project owner tries it with the screen off and another app holding the microphone.

## Alternatives rejected

- **A timer or polling loop.** It wastes power and it lags behind the real change.
- **A foreground service that only watches.** It is heavier and needs more than the callback does.
- **Clearing the face only on the next tap.** That is the bug itself.
- **Using `MODE_IN_COMMUNICATION` in the free test.** An app that forgets to restore the mode would leave the face stuck, and the face would never clear.

## Platform facts relied on

These come from an outside check of the platform's reference pages (`AudioManager.AudioRecordingCallback` and `AudioRecordingConfiguration`; the silenced flag exists from API level 29). Each answer is written as it was given. None of them has been tried on a phone yet.

1. The callback exists from API level 24 and delivers the active recordings on every change. The app's minimum is level 30, so it is covered. Confirmed.
2. A normal app is shown other apps' recordings without a special permission (their content is hidden), so counting them is possible. Confirmed.
3. A silenced or privacy-sensitive recording still appears in the list. Partly confirmed. A phone call may not appear, because its audio path is below the recorder layer. That is why the free test also reads the mode.
4. Our own recording is told apart by its session id, or by our own recorder's state. Confirmed.
5. `AudioManager.getMode()` needs no permission and is the same for the whole device. It reads `MODE_IN_CALL` during a cellular call. Confirmed. For internet calls it is partly confirmed.

## What is built

The watcher, the app wiring and the tests are built. The audio module holds the `MicReleaseWatcher` port, the one free reading (`micIsFree` and `freeReading`) and the platform adapter `AndroidMicReleaseWatcher`, which `AndroidMicSource.createReleaseWatcher` builds. The app starts the watcher when the busy flag flips to taken and stops it on every flip to clear. A free reading clears the flag and the notice line, and repaints the tile when the tile is armed. The tests drive all of it on the JVM against fakes, and a source-reading test pins the one place the tile host wires the real watcher.

Not yet tried on a phone: the platform adapter itself (the callback registration, the one read at the start, and the exclusion of our own recording by its session id). A device check is still owed for another recording app, an incoming call and an outgoing call. The tap clear is unchanged.
