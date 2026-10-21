# ADR-026: The Home mic is the floating tile's mic, drawn and driven by the same code

**Status:** accepted, amended 2026-10-20 (busy and sending faces, slot size, accessibility, launch defects, open defects)
**Date:** 2026-10-18

## Context
The Home screen has a large microphone (F44). On the first device build that
carried it, the owner found four problems. The mic was so large that Home had
to be scrolled. Nothing on it showed that a recording was running. Only a
second tap ended a recording, and that tap did not look like a button. There
was no way to cancel. The floating tile already solves all of this: it shows
the voice level in the mic's grille, puts an X and a check beside the mic while
recording, and ends or cancels through the same recording code the app uses
for every take.

Home and the tile drew the mic separately: the tile from path data in the
overlay module, Home from a vector drawable in the app. They could drift
apart, and Home had none of the tile's recording behaviour.

## Decision
1. The Home mic is the tile's mic. The overlay module, which owns the CB mic
   drawing, offers a second public way to show it: a mic for an app screen.
   It uses the tile's own drawing, level slots, X, check, busy cue and tap
   zones, with no floating window, no drag, no close target and no notice
   strip. Nothing is copied: the tile and the Home mic call the same code.
2. The app builds that mic and hands it to the Home screen, which only places
   it. The ui module does not use the overlay module; the app composes them
   (ADR-001). No module's dependencies change.
3. On Home, one tap on the mic starts a recording. While it records, the grille
   slots follow the voice level (F40) and an X (left) and a check (right) sit
   beside the mic, sized and spaced as on the tile (F36). The check, or a
   second tap on the mic, ends the recording. The text is saved to history and
   copied to the clipboard; it is not typed into any field, because no text
   field is in use while Breaker's own screen is open. The X cancels: nothing
   is transcribed or saved, and the microphone is released.
4. The Home mic and the tile start, end and cancel a recording through the
   same take code and the same single background worker. Starting a recording
   from Home never runs on the screen's main thread.
5. The voice level reaches every mic that is on screen, the tile and the Home
   mic, from the one place the app works it out.
6. The Home mic is sized so that the whole Home screen fits without
   scrolling, and it looks and announces itself as a button.

## Reasons
One drawing and one set of recording rules mean the two mics cannot drift
apart, and a fix to one is a fix to both. The tile's behaviour has already been
checked on a phone; reusing it is faster and safer than building a second one.
Letting the app compose the two modules keeps the rule that modules do not use
each other directly.

## Consequences
- The overlay module's public surface grows by one entry (the app-screen mic),
  and its card says it owns that mic too.
- `createHomeView` takes the mic as a parameter; the Home screen no longer draws
  a mic of its own. The app's vector drawable stays only where other screens
  still use it.
- The X and check may later be restyled to sit closer to the app's buttons; that
  is a change to the shared drawing and shows on the tile too, so it needs the
  owner's approval.
- The window behind every app screen takes the screen's own background colour,
  and screen content stays clear of the status bar and the navigation bar
  (no white edges, nothing hidden under the bars).

Amended 2026-10-20 (project owner, 2026-10-20 order). What main
holds, and what the APK 8 phone test showed is still missing:
- **Same faces, same signal (OPEN).** Item 1 covers every face, not only
  recording. When another app holds the microphone the tile shows its red
  circle with a line (`TileState.MIC_BUSY`); after the check it shows its
  sending look (`TileState.SENDING`) while the local transcription runs. The
  Home mic must show the same two faces from the same signal (one source, no
  copy): busy for as long as the tile shows it, and the sending look from the
  end of recording until the result, then idle. At main the Home wiring
  (`HomeTakeWiring`) pushes only `RECORDING`, `ARMED` and `IDLE` to the Home
  mic, so both are OPEN.
- **Slot size (built).** The ui module gives the mic a slot of
  `HomeGeometry.slotHeightDp(usable)`, held between 100 and 150 dp
  (`MIC_SLOT_MIN_DP`, `MIC_SLOT_MAX_DP`), and centres the mic in it
  (`HomeHostView.attachMicSlot`); below a 610 dp usable height the slot keeps
  its floor and the page may scroll (`HomeFitTest`).
- **Labels and accessibility (built).** The labels (Tap to talk, Cancel, Done)
  exist once, in the ui module's `HomeControl` companion read from
  `HomeTexts`; the app passes them to the overlay, which holds no literal. The
  mic reports itself as a Button with a click action; while recording it
  exposes two virtual children, Cancel and Done, each at least 48 dp
  (`overlay/MicAccessibility.kt`, `MicAccessibilityTest`). Follow-up (OPEN): a
  screen-reader double tap is routed through the last real touch's stored
  gesture (`ScreenMic.route()`), so after an X or check touch it can reach the
  wrong zone; the route must be decided from the model state alone.
- **Theme (OPEN, follow-up).** `HomeScreenMic.setTheme` has no caller, so the
  Home mic follows the phone's light or dark setting, not the stored theme.
- **Three launch defects (fixed in main, PRs #193, #194, #195).** The mic is
  removed from its old parent before each redraw re-adds it; the settings view
  is set before the first-run decision; the top bar's touch gap measures
  itself. Details and the rules drawn from them are in ADR-027.
- **Open defects from the APK 8 phone test.** (A, blocker) while recording, the
  X and check are drawn but cannot be tapped and the take gets stuck; the
  cause is not found. Required: X cancels, check finishes, a second tap on the
  mic finishes. (B) the busy face and (C) the sending face, above. (D) the new
  transcript reaches Home late; required: it is on Home as soon as the take
  settles.
