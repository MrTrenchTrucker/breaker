# ADR-024: The tile shows busy and loudness, and hides itself when no keyboard is in use

**Status:** accepted
**Date:** 2026-10-12

## Context
The first device test showed four gaps in what the floating tile tells the
user. After the check mark nothing shows that the text is being processed,
because the sending look is the same as a resting tile. The level bar above the
microphone never moves, because the overlay can draw a level but nothing feeds
it one. The tile stays on screen even when no text box is in use. And the
tile has no animated texture yet.

## Decision
1. **Busy cue.** From the check tap until the final outcome (sent, sent on the
   phone, failed, or microphone busy) the tile shows a visible busy cue: the
   ring pulses on its own rhythm and a spinner shows. Until the final art
   exists the spinner uses palette colours only.
2. **Level feed.** While capturing, the audio module reports a loudness value;
   the app passes it to the tile at a limited rate. The tile owns no clock and
   never reads the microphone itself. The exact interface is proposed in a
   design note and approved by a maintainer before any code.
3. **Keyboard-aware visibility.** The tile can hide while no text input is in
   use and return when one is. Which signal decides (a focused text field, or a
   visible keyboard) is chosen by a temporary developer setting with three
   values: off, field focus, keyboard visible. The setting exists only to test
   both ways. It is removed before release, after the project owner picks one.
   Whether shaking the phone still starts dictation while the tile is hidden
   this way is not decided yet.
4. **Animated texture.** The tile gets an animated texture while it is
   listening and busy; the project owner suggested radio waves. This is graphics
   work: it waits for the art phase and lives in the design tokens and the
   overlay, not in logic.

## Reasons
Each gap makes the tile look dead at the moment the user needs to know what it
is doing. Items 1 and 2 are small and mechanical; item 3 depends on how
Android reports keyboards, which is cheaper to test than to argue.

## Consequences
- The overlay, audio and app modules each change; the overlay changes first.
- The developer setting is the only deliberately temporary piece; the code
  change that adds it also adds a release check and a removal list for it.
- The texture needs the art phase before it can ship.
