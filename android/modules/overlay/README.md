# Overlay - README

The floating tile that appears over any app. It is a small draggable tile with the
CB mic drawn from path data, and it is where dictation is started, watched and
ended. The app tells the tile which of eight states to show, how loud the sound
is, and what sentence to display; the tile reports taps back through callbacks.
The microphone is the approved CB mic art. How it looks on a phone is not verified yet.
The tile never records and never inserts text. It has no text input, and its
window is asked to be not focusable, so the app underneath should keep focus and
its keyboard; that is not checked on a device.

## The eight states

The app pushes the state with `setState`. The tile never changes it, not even on
a tap.

| State | What the tile shows | A tap on the microphone calls |
|---|---|---|
| `IDLE` | the microphone with a plain outline | `onTap` |
| `ARMED` | the microphone with a pulsing outline | `onBegin` |
| `RECORDING` | the expanded tile, below | `onSend` |
| `SENDING` | the microphone; taps do nothing | nothing |
| `FAILED` | the outline turns to the failure colour | `onTap` |
| `SENT` | the outline turns green: the text is committed | `onTap` |
| `SENT_LOCAL` | the outline turns orange: the phone model committed it after the server failed | `onTap` |
| `MIC_BUSY` | the microphone with a red circle and slash (danger colour) over a dark outline; the circle and slash pulse slowly | `onTap` |

The armed outline pulses while the state is `ARMED` and the tile is shown: a platform animator
lowers and raises the outline's brightness from a quarter to full strength over 1.6 seconds. It stops
when the state leaves `ARMED`, when the tile is hidden or detached, when the window is not visible,
or when the screen turns off. The app drives nothing; with system animations off the outline stays steady.

## The busy pulse

While the tile is MIC_BUSY or SENDING and shown, the outline pulses slowly: a 3200 ms cycle
with the alpha ranging from 0.6 to 1.0. It uses the same run conditions as the
armed pulse (shown, attached, visible, lit screen, animations on). It stops
when the state leaves MIC_BUSY or SENDING, when the tile is hidden or detached, when the
window is not visible, or when the screen turns off.

## The expanded tile and the meter

While the state is `RECORDING` the tile widens to a window three 48 dp touch zones wide and one tile high: a red X (cancel) on
the left, the microphone in the middle, and a green check (send) on the right. The X is drawn in the danger colour and the check in the primary colour, each as a filled shape with a trim outline. The X and the check are each 90% of the mic's full width (side bar included), with their nearest path edge 8 dp from the mic outline and centred on the silhouette's centre line; `TileControlLayout.kt` holds that placement, and the mic zone is centred on the silhouette. The
microphone's grille is the level meter: six slots, lit from the left. Silence leaves
all six unlit. The app pushes the sound level with `setLevel` (0.0 to 1.0); a value
outside the range is held to it, and the tile redraws only when the number of lit
slots changes. Outside recording the six slots show the lit colour, because they are
part of the grille. The expanded tile cannot be dragged; only the small tile moves,
and its saved position is never changed by the wide one.

## The microphone

The microphone is the approved CB mic: straight-line shaded faces, a trigger bar, a
head with a tapered lower body, a cord stub, a frame with the six grille slots, a
ledge, and the word BREAKER as outlines. It is drawn from numeric path data copied
from the art, with no font file. A glowing outline runs along its silhouette and
takes the same colour per state as the ring did before. The tile draws no fill
behind the mic in any state, so only the mic and its glow show over the app. The
glow is a few stroke layers, not a blur. `GlowKind.TILE` draws the tile's three glow
strokes, their outer half width from `GlowSpec.TILE`'s reaches; `GlowKind.SCREEN`
draws the Home mic's halo and then those same strokes.

## How dictation ends

While recording, the check, or a tap on the microphone, calls `onSend`, and the X
calls `onCancel`. The send phrase is the app's job, not this module's. The text
goes into the target field through the commit module's accessibility service,
with a clipboard fallback (ADR-022); that is not done here. The tile does not
change the state on a tap, so `onSend` is called for every tap until the app
pushes `SENDING`; the app must tolerate a repeat.

## The notice

`showNotice(text)` shows a sentence from the app in the strip above the microphone, for
example why a tap did nothing. Line breaks and tabs become spaces, the text is
trimmed and cut to 80 characters, and it is ignored while recording. It stays
until the app calls `clearNotice()` or pushes a different state: the module has
no timer and no words of its own. `setDescription(text)` gives a screen reader the
words for the tile.

## How the app uses it

1. Make one tile with
   `FloatingTile.create(context, settings, onTap, theme, onSaveFailed, onBegin, onCancel, onSend, onNoticeDismissed, onCloseRequested)`
   and keep it. `settings` is core's `SettingsStore`, `theme` is the light or dark
   theme being shown, and the last six callbacks are optional. A callback that is
   null makes its tap do nothing. An exception thrown by a callback is not caught.
2. Call `show()` from the main thread. It answers `SHOWN`, `ALREADY_SHOWN`,
   `PERMISSION_MISSING` or `FAILED`. On `PERMISSION_MISSING`, send the user to the
   system page for drawing over other apps and call `show()` again.
3. Push `setState`, `setLevel`, `showNotice`, `clearNotice` and `setDescription`
   as things change. While the tile is hidden they are kept, and the next `show()`
   draws them. `hide()` while the tile is expanded or recording removes the window
   and calls no callback.
4. Call `setTheme(...)` when the theme changes, `onDisplayChanged()` on rotation
   or a size change, and `hide()` to take the tile away.

The tile keeps its position as fractions of the range it can move over and saves
it once when a drag ends. The foreground service that keeps the process alive is
the app's, not this module's.

`FloatingTile.setImeProbe(listener)`: a non-null listener is told when the bottom keyboard inset of the tile's window changes, and null turns it off. It never shows or hides the tile. No app code calls it.

## ScreenMic: the same mic on an app screen

`ScreenMic.create(context, theme, label, cancelLabel, sendLabel, onTap, onBegin, onCancel, onSend)` makes the mic as a plain view with no window, no drag, no close target and no notice strip; the app places it on Home (F45, ADR-026). It takes the same states and routes taps the same way as the tile. The three labels are passed in by the app (ui's `HomeControl`); this module holds none. A screen reader finds one button, and while recording two more: cancel and send, each at least 48 dp (`MicAccessibility`). It has no pulse. Whoever places it must take it out of its old parent before adding it again. The view also implements the public `ScreenMicProbe` interface: the app's debug source set reaches the mic only through it (push a state, route the touch path's log lines); it carries no behaviour of its own.

## Not built yet

- The app pushing `SENT` and `SENT_LOCAL`, and the state it pushes after a
  commit that only reached the clipboard (open: the app's decision).
- Sound or haptics, a time limit on the notice, and the window-ownership check.
- OPEN on the Home mic (phone test): the X and the check do not answer a tap while recording (cause not found); the app does not yet push `MIC_BUSY` and `SENDING` to it, so it does not show the tile's busy and sending faces; it follows the phone's light/dark setting, not the stored theme.
- Nothing here decides the keyboard tile. This module keeps the tile window; the app decides when it shows and hides, from the keyboard readings.

Nothing has been run on a device yet; the module card lists what the tests check
and what is not verified on a device.

## Tests

`./gradlew :android:modules:overlay:test`

Full module card: `AGENTS.md` in this folder.
