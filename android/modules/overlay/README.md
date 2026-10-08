# Overlay - README

The floating tile that appears over any app. It is a small draggable tile with a
stand-in microphone glyph, and it is where dictation is started, watched and
ended. The app tells the tile which of seven states to show, how loud the sound
is, and what sentence to display; the tile reports taps back through callbacks.
The microphone is a placeholder drawing until the final mic art comes later.
The tile never records and never inserts text. It has no text input, and its
window is asked to be not focusable, so the app underneath should keep focus and
its keyboard; that is not checked on a device.

## The seven states

The app pushes the state with `setState`. The tile never changes it, not even on
a tap.

| State | What the tile shows | A tap on the microphone calls |
|---|---|---|
| `IDLE` | the microphone with a plain ring | `onTap` |
| `ARMED` | the microphone with a steady ring | `onBegin` |
| `RECORDING` | the expanded tile, below | `onSend` |
| `SENDING` | the microphone; taps do nothing | nothing |
| `FAILED` | the ring turns to the failure colour | `onTap` |
| `SENT` | the ring turns green: the text is committed | `onTap` |
| `SENT_LOCAL` | the ring turns orange: the phone model committed it after the server failed | `onTap` |

The armed ring can show a pulse. The pulse rule is a pure function that turns a
pushed phase into a ring brightness, but nothing in the module moves the phase
over time, so the ring stays steady until the app pushes a phase.

## The expanded tile and the meter

While the state is `RECORDING` the tile widens to three tiles: an X (cancel) on
the left, the microphone in the middle with the LED bar meter directly above it,
and a check (send) on the right. The meter has 12 segments. The app pushes the
sound level with `setLevel` (0.0 to 1.0); a value outside the range is held to it,
and the tile redraws only when the number of lit segments changes. The expanded
tile cannot be dragged; only the small tile moves, and its saved position is
never changed by the wide one.

## How dictation ends

While recording, the check, or a tap on the microphone, calls `onSend`, and the X
calls `onCancel`. The send phrase is the app's job, not this module's. The text
goes into the target field through the commit module's accessibility service,
with a clipboard fallback (ADR-022); that is not done here. The tile does not
change the state on a tap, so `onSend` is called for every tap until the app
pushes `SENDING`; the app must tolerate a repeat.

## The notice

`showNotice(text)` shows a sentence from the app above the microphone, for
example why a tap did nothing. Line breaks and tabs become spaces, the text is
trimmed and cut to 80 characters, and it is ignored while recording. It stays
until the app calls `clearNotice()` or pushes a different state: the module has
no timer and no words of its own. `setDescription(text)` gives a screen reader the
words for the tile.

## How the app uses it

1. Make one tile with
   `FloatingTile.create(context, settings, onTap, theme, onSaveFailed, onBegin, onCancel, onSend)`
   and keep it. `settings` is core's `SettingsStore`, `theme` is the light or dark
   theme being shown, and the last four callbacks are optional. A callback that is
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

## Not built yet

- The final microphone art (the glyph stays a placeholder until then).
- Driving the armed pulse over time: the app must push the phase.
- The app pushing `SENT` and `SENT_LOCAL`, and the state it pushes after a
  commit that only reached the clipboard (open: the app's decision).
- Sound or haptics, a time limit on the notice, and the window-ownership check.

Nothing has been run on a device yet; the module card lists what the tests check
and what is not verified on a device.

## Tests

`./gradlew :android:modules:overlay:test`

Full module card: `AGENTS.md` in this folder.
