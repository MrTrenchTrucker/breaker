# Overlay - README

The floating tile that appears over any app. It is a small draggable tile with a
stand-in microphone glyph. A tap calls the app's own `onTap`; the app starts
dictation, which happens in place on the tile (ADR-022), and
no Activity or window opens during dictation. The tile has no text input. Its
window is asked to be not focusable, so the app underneath should keep
focus and its keyboard; that is not checked on a device.

## How the app uses it

1. Make one tile with `FloatingTile.create(context, settings, onTap, theme, onSaveFailed)`
   and keep it. `settings` is core's `SettingsStore`, `theme` is the light or dark
   theme being shown, and `onSaveFailed` is optional.
2. Call `show()` from the main thread. It answers `SHOWN`, `ALREADY_SHOWN`,
   `PERMISSION_MISSING` or `FAILED`. On `PERMISSION_MISSING`, send the user to the
   system page for drawing over other apps and call `show()` again.
3. Call `setTheme(...)` when the theme changes, `onDisplayChanged()` on rotation
   or a size change, and `hide()` to take the tile away.

The tile keeps its position as fractions of the range it can move over and saves
it once when a drag ends. The foreground service that keeps the process alive is
the app's, not this module's.

## Not built yet

The LED bar meter, the state colors (sent, fallback, failure), the armed pulse
and the real microphone art. Nothing has been run on a device yet; the module
card lists what is and is not verified.

## Tests

`./gradlew :android:modules:overlay:test`

Full module card: `AGENTS.md` in this folder.
