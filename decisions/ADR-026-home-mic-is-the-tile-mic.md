# ADR-026: The Home mic is the floating tile's mic, drawn and driven by the same code

**Status:** accepted
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
