# ADR-024: The tile shows busy and loudness, and hides itself when no keyboard is in use

**Status:** accepted, amended 2026-10-12 (busy cue: no spinner; shake, keyboard and battery)
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
   ring takes a colour of its own from the palette and pulses on its own
   rhythm. There is no spinner; any further motion belongs to the animated
   texture in item 4, in the art phase.
2. **Level feed.** While capturing, the audio module reports a loudness value;
   the app passes it to the tile at a limited rate. The tile owns no clock and
   never reads the microphone itself. The exact interface is proposed in a
   design note and approved by a maintainer before any code.
3. **Keyboard-aware visibility.** The tile can hide while no text input is in
   use and return when one is. Which signal decides (a focused text field, or a
   visible keyboard) is chosen by a temporary developer setting with three
   values: off, field focus, keyboard visible. The setting exists only to test
   both ways. It is removed before release, after the project owner picks one.
   A shake still brings the tile back and starts
   dictation while it is hidden this way (F4, F38).
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

Amended 2026-10-12: item 1 no longer says a spinner shows. The first design
named one; the busy cue is the ring's own colour and pulse, and any further
motion waits for the texture (item 4).

Amended 2026-10-12: the second device test showed a shake doing nothing and the
tile not coming up with the keyboard, and the loudness bar still not moving.
Item 3 is read as: a shake works with the keyboard open and the tile
hidden; the tile appears when a text input opens or the keyboard comes up; and
the app tells the user to allow unrestricted battery use, because the system
may stop listening for a shake in the background (F12). Item 2 is unchanged:
the bar follows the voice live.
