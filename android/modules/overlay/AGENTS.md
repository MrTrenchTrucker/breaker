# AGENTS.md - android/modules/overlay/

## Purpose

Floating tile = CB mic drawn from path data, with a glowing outline and a six-slot level meter in its grille (WindowManager overlay, F36). The floating tile that appears over any app and is where dictation is started, watched and ended. A tap on the microphone calls the app's `onTap` (idle or failed) or `onBegin` (armed), and the app decides whether that starts or stops dictation. The tile draws the approved CB mic from numeric path data (`TileArt.kt` and `TileLetters.kt`), and the X and the check from path data in `TileControlArt.kt`, not a stand-in glyph. How it looks on a phone is not verified (see Not verified on a device).

**What is built now:**
- A small draggable tile (64 dp) with the CB mic drawn from path data and no fill behind it, shown above other apps.
- Eight states, pushed by the app with `setState`: `IDLE`, `ARMED`, `RECORDING`, `SENDING`, `FAILED`, `SENT`, `SENT_LOCAL` and `MIC_BUSY`. The tile never
  changes the state itself, not even on a tap. The outline around the microphone takes its colour from the state. The
  outline pulses while the tile is ARMED and shown (the armed ring pulse, see Invariants). The app drives nothing: the pulse
  starts and stops with the state and with the tile being shown, attached, visible and on a lit screen.
- A level meter of six segments: the six grille slots of the microphone. The app feeds it with `setLevel` (0.0 to 1.0);
  the slots are lit from the left while the state is `RECORDING`, and silence leaves all six unlit.
- The expanded tile while `RECORDING`: [X cancel] [microphone with the meter in its grille] [check = send], in one window
  three 48 dp touch zones wide and one tile high (144 x 64 dp at the tile size): cancel, mic and send. The X and the check are each 90% of the mic's full width (side bar included), with their nearest path edge 8 dp from the mic silhouette path and centred on the silhouette's centre line; the placement is in `TileControlLayout.kt`. The mic zone (`TileLayout.micZone`) is centred on the silhouette. The expanded tile cannot be dragged.
- A notice: a sentence from the app (`showNotice`) drawn in the strip above the microphone, in the wide window (three tiles wide and one and a half tiles high), until the app
  clears it (`clearNotice`) or pushes a different state. The module holds no words of its own.
- A description (`setDescription`) that the view gives to screen readers as its content description.
- The callbacks `onTap`, `onBegin`, `onCancel`, `onSend` and `onNoticeDismissed`.
- ScreenMic (F45, ADR-026): the same mic as a plain view for an app screen (no window, no drag, no close target, no notice strip), made by `ScreenMic.create(context, theme, label, cancelLabel, sendLabel, onTap, onBegin, onCancel, onSend)`; the app places it in the Home slot. It measures from the height it is given (the tile square when idle, the three-zone width while `RECORDING`), routes taps through `TileRouting` like the tile (X = `onCancel`; check or mic while recording = `onSend`) and has no pulse: it draws the glow at full strength in every state.
- A tap on the strip of a notice window clears the notice and collapses the tile, calling `onNoticeDismissed` once (not `onTap`). The module has no timer.
- MIC_BUSY: the microphone is not available. The app decides when to show it. The tile shows a mic glyph with a red circle and diagonal slash (danger colour) over a dark outline (the danger colour shaded toward black). The circle and slash pulse slowly; the outline does not pulse. No text. A tap reports like IDLE.
- Close target (F38): while a small tile is dragged, a close target is shown centred near the bottom of the usable area; it goes when the drag ends. The target is a see-through dark box about the size of a keyboard space bar, with rounded corners, holding a side view of a long-nose truck pulling a lowboy that carries a strapped red X (path data in `CloseTargetArt.kt` and the two truck files). It is drawn from the dark palette in both themes, palette colours only: the box fill is the surface lightened, the edge the muted text colour, the truck the text colour shaded, the X the danger colour. While the tile's centre is inside the box, the fill is less see-through, the edge is the danger colour and drawn wider, and the X is lighter. The drop area is the drawn box; the four corners its arcs cut off are outside. A drop with the tile's centre inside the box hides the tile, calls `onCloseRequested` (no arguments, null by default) and does not save the position; a drop elsewhere saves as today. A tap never closes. The target is shown in the same overlay window type and is not focusable and not touchable.

**How dictation ends:** while the state is `RECORDING`, the check, or a tap on the microphone, calls `onSend`, and the X
calls `onCancel`. The send phrase is the app's job, not this module's. The tile never records and never inserts text: the
text goes in through the commit module's accessibility service, with a clipboard fallback (ADR-022).

**In:** `show()`/`hide()`, called by the app's DI wiring (ADR-001), not through
a core port: on a shake (`gesture`), on the wake phrase (`PhraseEvent.Wake`,
which `phrases` delivers through `core.PhraseTrigger`), or from a manual
toggle. `overlay` never imports `gesture` or `phrases`.
All calls are made from the main thread by the app's wiring.
**Out:** a tap on the microphone -> the app's `onTap` (idle, failed) or `onBegin` (armed) runs once; the check, or the
microphone while recording -> `onSend`; the X -> `onCancel`. A callback that is null does nothing. The tile does not open anything itself. Dictation happens in place on the tile (ADR-022); no Activity opens and no second window is added during dictation; only the tile itself uses `TYPE_APPLICATION_OVERLAY`.

**Pushed in by the app:** `setState`, `setLevel`, `showNotice`, `clearNotice` and `setDescription`, on the main thread. While the tile is hidden they are only kept, and the next `show()` draws them. A push that changes nothing the tile shows makes no window call.

The app now drives `setLevel` while recording: it works out the level from the microphone frames it already forwards to core, and pushes it only while the state is `RECORDING`.

**Implementation:**
- `WindowManager` + `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE` (Android 11+, the app's minimum). The window
  is also `FLAG_NOT_TOUCH_MODAL` and `FLAG_LAYOUT_IN_SCREEN`, with top and left gravity (`LEFT`, not `START`), so its
  x and y are meant to be counted from the top-left screen corner whatever the layout direction. That is the same
  origin as the usable area the tile is placed in. The window is asked to be not focusable, so other apps should
  keep focus while the tile is shown; this is not checked on a device.
- **Caveat (verified in research):** overlay windows cannot take focus and an
  EditText inside an overlay won't raise the keyboard - so the tile is **tap-only**.
  The tile has no text input and its window is asked to be not focusable, so the user's own keyboard should stay
  up and the target field should keep focus while dictating in place (ADR-022). This is by design and is not verified on a device (see "Other apps
  keeping focus and the keyboard" in the list under "Not verified on a device").
- Permission: the module's own manifest declares `android.permission.SYSTEM_ALERT_WINDOW`. It is the one
  permission this module adds. With it missing, `show()` returns `PERMISSION_MISSING` and changes nothing; the
  app sends the user to the system page for the permission and calls `show()` again.
- Reference: LexiSharp Keyboard's overlay management (WindowManager API,
  permissions, lifecycle, multi-window coordination).
- Draggable tile (only the square one; the wide window of a notice or of recording cannot be dragged); position persisted to settings.
- Permissions: the app asks for its permissions at first start, one screen per permission (F11): microphone, overlay (drawing
  over other apps), notifications and the accessibility service. The foreground service permission is granted
  at install, with no screen, and the app has internet access for the server. This module
  adds only `SYSTEM_ALERT_WINDOW`, declared in its own manifest; the others belong to other parts of the app. The
  foreground service that keeps the process alive while the tile is up belongs to the app, not to this module.

**Position:**
- A position is a pair of fractions of the MOVABLE range: the usable area (the screen minus system bars and
  cutouts) minus the tile size, on each axis, measured from the top-left corner of the usable area. So 0 and 1 are
  both fully on screen, and 0.5 / 0.5 is centred. (Core's own comment on `TilePosition` says "fractions of the
  screen"; this definition is the precise one.)
- Saved through core's `SettingsStore` (`AppSettings.tilePosition`), once when a drag ends, never while the finger
  moves, and not on a tap or on show. The other settings are kept: the module loads, changes the position and
  saves.
- Each time `show()` adds the tile it reads the saved position (`ALREADY_SHOWN` and `PERMISSION_MISSING` read
  nothing). If the read throws, the tile starts in the centre (0.5 / 0.5).
- A failed save is swallowed: the tile stays where it was dropped, and `onSaveFailed` (optional, no arguments) is
  called once for that drag end. The next drag end tries again.
- The usable area and the tile size are read again at every move and at `onDisplayChanged()`, never kept.
- The wide window (a notice, or recording) is placed around the square tile: for a notice its microphone cell lies over the square
  tile where the screen allows (a notice window starts one strip above the tile's top, a recording window starts at the tile's own top and keeps the mic silhouette over the square tile, with the mic zone centred on it), and the window is moved only as far as needed to stay inside the usable area. It never
  changes the saved position. Next to a screen edge the microphone therefore appears at a different place when the
  window opens. A tap on the wide window is read against the place the module asked the window to have.

**Overlay spoofing (T4):**
- What the module does: the tile is tap-only (no text input of any kind), and the tile view asks the system to
  drop touches that arrive while another window covers it (`setFilterTouchesWhenObscured(true)`).
- What the module does NOT do: it does not verify window ownership and makes no such claim. The threat-model row
  for T4 also names that check; it is not built here.
- Not stealing focus is a property of the window flag above (`FLAG_NOT_FOCUSABLE`); T4 is about spoofing.

**Theme:** the app passes the theme the tile should show, light or dark (ui-tokens `ThemeMode`, not core's
`ThemeMode` which also has SYSTEM). The tile draws from the ui-tokens palette only (through `TileLook`, and `shade()` of its fields with the named
factors in `ArtFactors`): the body is dark in both themes. Its shapes, the grille well and the faces on the body are the
body colour (`bodyColor(look)`: the text colour in the light theme, the surface colour in the dark theme). The frame and
ledge faces shade the trim colour. The BREAKER letters are primary, the outline is trim (idle), primary (armed, recording),
warning (sending), danger (failed), the palette's `sent` field (SENT) or its `warning` field (SENT_LOCAL), the lit grille
slots are primary and the unlit ones the page background colour (bg). The notice text is drawn over a halo of the surface
colour (`look.background`). The X and the check are filled shapes with a trim outline (`look.glyphOutline`) and have no halo. The window draws no fill, so no surface is drawn behind the mic.
No colour value is written in this module's sources.

**Glyph:** the approved CB mic: straight-line shaded faces, a trigger bar, a head with a tapered lower body, a cord stub,
a frame with six grille slots, a ledge, and the word BREAKER as outlines. The shapes are numeric path data copied from the
art's SVG (`TileArt.kt`, and the BREAKER outlines in `TileLetters.kt`); the X and the check paths are in `TileControlArt.kt`; there is no font file. The busy sign, the glow widths and the art-to-cell frame are worked out in `TileArtGeometry.kt`, which has no android names, and `TileArtDraw.kt` only draws them. The art is drawn at 90 percent
of the 64 dp cell (`ART_FILL`) so the glow fits, and the tile is 64 dp square with no fill, so no corner is drawn.
The outline is 2 dp thick and runs along the silhouette of the trigger bar, the head and lower body, and the cord stub. Outside
it are three glow layers, 4, 6 and 8 dp wide, with alpha 0.45, 0.25 and 0.12 going outward. The glow is approximate stroke
layers, not a blur. `GlowKind.TILE` draws those three glow strokes, their outer half width from `GlowSpec.TILE`'s reaches;
`GlowKind.SCREEN` draws the Home mic's halo and then those same strokes.
Faces: each face is a shade by a named factor in `ArtFactors` (a positive factor mixes toward white, a negative one toward
black, 0 is the colour itself). The body shapes and the faces on the body (trigger face and stripe, lower left and right,
ledge edge) shade `bodyColor(look)`, the darker of the text and surface colours by relative luminance, so the body is dark in both
themes. The frame and ledge faces shade the trim colour (`look.glyphOutline`): black in the light theme, where they cannot
get darker and are flat, and white in the dark theme. They sit on the dark body.

**Look & feel (F36):**
- **Built:** the eight states, the level meter in the grille and the expanded tile, as described below, the CB mic drawn
  from path data with its glowing outline, and the armed ring pulse (one platform animator, see Invariants; the app drives
  nothing). **Not built yet:** the app's pushing of the sent and fallback states. Each line below says which it is.
- The tile IS the **CB mic** (the approved art, drawn from path data) - built. The favicon art from `shared/ui-tokens`
  is not used.
- **Level meter** (the six grille slots of the mic) fills with audio level while the mic is recording - built: six
  segments lit from the left, the level times 6 taken up to the next whole segment, no smoothing; lit in the primary
  colour and unlit in the page background colour. Outside `RECORDING` all six slots show the lit colour, because the
  slots are the mic's grille and not only a meter. The audio-level feed itself is the app's: it pushes the level with `setLevel`.
- State colors: the outline around the microphone is the trim colour when idle, the primary colour when armed or
  recording, the warning colour while sending, and the danger colour after a failure (all palette fields). **SENT = green** (the palette's `sent` field:
  copy confirmed) and **SENT_LOCAL = orange** (the palette's `warning` field: committed by the phone model after the
  server path failed) are built as states and colours; the tile only shows what the app pushes. The armed outline pulses
  while the tile is ARMED (see Invariants), and is steady when animations are off. While sending, the tile shows
  the warning outline and pulses slowly (the busy pulse, same as MIC_BUSY); the app's description is the only cue.

**Not built yet:**
- The app's pushing of SENT and SENT_LOCAL, and the state the app pushes after a clipboard-only commit (OPEN: the app's
  decision, not decided here).
- Words on the tile of its own, sound or haptics, the window-ownership check (T4), the
  audio capture and the audio-level feed (the app's), the foreground service (the app's), asking the user for the
  overlay permission (the app's), and any gesture or phrase code.
- OPEN (Home mic, from the phone test): (a) BLOCKER: while recording, the X and the check on the Home mic are drawn but do not answer a tap and the take gets stuck; cause not found (leads: the tile's overlay window over the mic, the zone mapping in the centred slot, a parent taking the touch, a Home redraw re-parenting the mic mid-gesture). Required: X cancels, check finishes, a second tap on the mic finishes. (b) The app pushes only `IDLE`, `ARMED` and `RECORDING` to the Home mic; required: it shows `MIC_BUSY` from the same signal as the tile and `SENDING` from the end of recording until the result, then idle (ScreenMic already draws the busy sign for `MIC_BUSY`; it has no busy pulse). (c) `ScreenMic.setTheme` has no caller: the Home mic follows the phone's light/dark setting, not the stored theme.
- Keyboard tile: this module keeps owning the tile window; the show/hide decision is the app's (`TileCoordinator` acts on `decision` in `KeyboardTileDecision.kt`), which reads the keyboard window and the focused text field. `setImeProbe` has no app caller.

**Build phase:** Phase 6, together with `gesture`. Needs first: `core` and `ui-tokens` (both on main).

## Invariants
- Tile floats above other apps; a tap on the microphone while idle or failed calls the app's `onTap` once and the tile does not move (F4, F5).
- Tile is draggable and position persists.
- The position is saved once per drag end, never while the finger moves, and every other setting is kept.
- No focus stealing; other apps keep focus while tile is shown (the window is asked to be not focusable; not checked on a device).
- T4: the tile is tap-only and drops touches while covered. It does not verify window ownership.
- Tile renders the CB mic from path data, with no fill behind it in any state; the six grille slots are the level meter while recording (F36).
  Tested by: `TileArtTest`, `TileArtGeometryTest`, `TileRecordingLayoutTest`, `ControlArtSvgTest` (the X and check path numbers, held as svg element text in the test, against `ControlArt` and Kotlin literals), `ControlDrawGateTest` (the fill and outline passes, with the rules in `ControlDrawRules`), and the view's source-text rule for the fill (re-pointed; see Test Locations).
- Faces and glow take their colours only from `TileLook` fields: `shade()` with the named factors in `ArtFactors` (the body colour from `bodyColor(look)`), and the glow through `glowDrawColor` with the outline colour and the pulse alpha. The glow is three stroke layers. `GlowKind.TILE` draws the tile's three glow strokes, their outer half width from `GlowSpec.TILE`'s reaches; `GlowKind.SCREEN` draws the Home mic's halo and then those same strokes.
  Tested by: `TileArtFacesTest`, `TileArtRulesTest`, `PulseRulesTest`.
- State colors: a failure turns the outline to the danger colour (F36). SENT is green (the palette's `sent` field) and
  SENT_LOCAL is orange (the palette's `warning` field). The outline colour per state is the old ring mapping, unchanged.
  Tested by: `TileOutlineColourTest`.
- The tile never chooses SENT or SENT_LOCAL: only the app pushes them.
- No clocks and no threads in the module: no long press, no timeouts, no background work.
- No timers, clocks or threads in the module. The one exception is the armed ring pulse: one platform `ValueAnimator` in ArmedPulse.kt, on the main thread, running only while the tile is ARMED, shown, attached, visible, the screen is on and system animations are on; it never changes the state or what a tap does, and the notice stays until the app clears it or pushes a different state.
  Tested by: `ModuleHygieneTest`, `TileViewGateTest`, `PulseRulesTest`.
- The busy ring pulse: while the tile is MIC_BUSY or SENDING and shown, the outline pulses slowly (period 3200 ms, alpha min 0.6). It stops when the state leaves MIC_BUSY or SENDING, when the tile is hidden or detached, when the window is not visible, or when the screen turns off; the same run conditions as the armed pulse.
- Colours are palette fields only: every colour the tile draws is a ui-tokens palette field; no colour value and no new token is written in this module.
  Tested by: `TileStyleTest`, `TileGlyphTest`, `TileViewGateTest`, `TileViewMappingGateTest`, `TileViewDrawingGateTest`, `ControlDrawGateTest`, `AdapterGateTest`.
- The module holds no words of its own: the notice and the description come from the app, and ScreenMic's three labels are passed in by the app (one source: ui `HomeControl.TAP_TO_TALK`, `CANCEL`, `DONE`).
  Tested by: `MainSourceWordsGateTest`, `TileAdapterWiringGateTest`.
- ScreenMic accessibility: the view is class Button with `ACTION_CLICK`; outside `RECORDING` it has no children; while `RECORDING` it exposes exactly two virtual children, cancel then send, each lifted to at least 48 dp (`MicAccessibility.MIN_TARGET_DP`), and a child's click runs only its own callback. ScreenMic draws the X and the check only while `RECORDING`. The Home mic view also implements the public `ScreenMicProbe` interface (the debug probe's handle; see Public Interface); the interface carries no behaviour of its own.
  Tested by: `MicAccessibilityTest`, `MicAccessibilityFloorTest`, `MicA11yStateTest`, `ScreenMicRecordingLookGateTest`.
- Only a collapsed tile drags and saves its position: the wide window (a notice, or recording) cannot be dragged, and a slide that starts on a button or in the strip moves nothing and saves nothing.
  Tested by: `TileControllerGestureRulesTest`, `TileControllerButtonsTest`, `TileControllerDragTest`.
- A tap on a collapsed tile is a tap on the microphone from any point of it.
  Tested by: `TileControllerGestureRulesTest`, `TileControllerDragTest`.
- A tap on the strip of a notice window clears the notice and collapses the tile, calling `onNoticeDismissed` once and not `onTap`.
  Tested by: `TileControllerNoticeDismissTest`, `TileControllerButtonsTest`, `TileRoutingTest`.
- The module never changes the state by itself: a tap leaves the state as it is, so `onSend` is called again for every
  tap on the check or the microphone until the app pushes `SENDING`, and the app must tolerate it.
  Tested by: `TileControllerButtonsTest`, `TileRoutingTest`.
- A level pushed outside `RECORDING` is stored but not lit; the meter starts empty on entering `RECORDING`, and it is also reset on leaving it.
  Tested by: `TileControllerStateTest`.
- A callback runs only for a tap; `show()`, `hide()` and every push from the app call none, and `hide()` while expanded or recording removes the window without a callback.
  Tested by: `TileControllerButtonsTest`, `TileControllerGestureRulesTest`, `TileControllerHiddenTouchTest`.
- A push that changes nothing the tile shows makes no window call, and the meter redraws only when the number of lit segments changes.
  Tested by: `TileControllerStateTest`, `TileControllerNoticeTest`, `TileControllerHiddenTouchTest`.
- The usable area is read again at every move; it is never cached.
- Every test passes on one core and in parallel: none waits on a clock, depends on core count or scheduling
  order, or depends on another test class having run.

## Owns
Floating tile = CB mic drawn from path data, with a glowing outline and a six-slot level meter in its grille (WindowManager overlay, F36). Built: the draggable tile with the CB mic, the eight states, the armed ring pulse (one platform animator), the six-slot level meter, the expanded recording tile with cancel and send (zones placed by `TileControlLayout.kt`), and the notice and
description the app pushes. ScreenMic = the same mic for an app screen, with no window, no drag and no notice strip; the app places it on Home (F45, ADR-026). ScreenMic reports itself as a button; while recording it exposes two virtual accessibility children, cancel and send, each at least 48 dp, named by labels the app passes in (the pure model is `MicAccessibility`, focus and hover memory is `MicA11yState`; the focus, hover and content-changed events are device behaviour, not unit tested). The module also owns `ScreenMicProbe`, the debug probe's handle on the Home mic (public; see Public Interface). Not built yet: nothing in the mic art; its look on a device is still to be checked.

## Public Interface
FloatingTile (via create), FloatingTile.setImeProbe, ShowResult, TileState, ScreenMic (via create), ScreenMicProbe

- `FloatingTile`, built only through `FloatingTile.create(context, settings, onTap, theme, onSaveFailed, onBegin, onCancel, onSend, onNoticeDismissed, onCloseRequested)`:
  - `context`: any context; the tile keeps only the application context.
  - `settings`: core's `SettingsStore`.
  - `onTap`: what the app does when the microphone is tapped while the tile is idle or failed. Called once per tap on
    the main thread. An exception it throws is not caught by the module.
  - `theme`: the theme shown first (ui-tokens `ThemeMode`, LIGHT or DARK).
  - `onSaveFailed`: optional, no arguments, null by default.
  - `onBegin`: optional, no arguments, null by default. Called once when the microphone is tapped while the tile is armed.
  - `onCancel`: optional, no arguments, null by default. Called once when the X is tapped while the tile is recording.
  - `onSend`: optional, no arguments, null by default. Called when the check, or the microphone, is tapped while the
    tile is recording. The tile does not change its state, so every such tap calls it until the app pushes another
    state; the app must tolerate a repeat.
  - `onNoticeDismissed`: optional, no arguments, null by default. Called once when the strip of a notice window is tapped.
  - `onCloseRequested`: optional, no arguments, null by default. Called when a drag of the collapsed tile ends on the close target.
  - A callback that is null makes the tap do nothing. An exception thrown by any callback is not caught by the module.
- `FloatingTile.show(): ShowResult`, `hide()`, `setTheme(theme)`, `onDisplayChanged()`, the property `isShown`, the pushes from the app `setState(state)`, `setLevel(level)`, `showNotice(text)`, `clearNotice()` and `setDescription(text)`, and the property `state`.
  - `TileState.MIC_BUSY` - public; the overlay face when the microphone is not available. The app decides when to show it. The tile shows a mic glyph with a red circle and diagonal slash (danger colour) over a dark outline. The circle and slash pulse slowly; the outline does not pulse. No text. A tap reports like IDLE.
  - `setTheme`: while the tile is shown it is repainted at once; while hidden the choice is kept for the next `show()`.
  - `onDisplayChanged`: call it when the screen size, density or insets change. While shown and not being dragged
    the tile moves to where its saved fraction falls on the new screen (a wide window is placed again around it);
    otherwise nothing happens.
  - `hide`: does nothing when the tile is hidden. A drag in progress is ended and saved first. While the tile is
    expanded or recording, the window goes and no callback runs; the pushed state, level and sentence are kept, and the
    next `show()` draws them.
  - `state`: the state the app last pushed; `IDLE` until it pushes one.
  - `setState`: shows the state. A different state removes any notice, and entering or leaving `RECORDING` sets the
    meter to empty. Pushing the state the tile already has changes nothing (so it keeps a notice). While hidden the
    state is kept for the next `show()`.
  - `setLevel`: 0.0 (silence) to 1.0 (full). A value outside the range is held to it and a value that is not a number
    counts as 0. The meter is lit only while the state is `RECORDING`.
  - `showNotice`: a sentence from the app, shown in the strip above the microphone. Each line break and each tab becomes one space
    (a CR LF pair is two line breaks, so two spaces), the text is trimmed and cut to 80 characters without splitting a
    surrogate pair, and a blank text shows nothing. It is ignored while `RECORDING`. It stays until `clearNotice()` or a
    different state; the module has no timer.
  - `clearNotice`: takes the sentence away; does nothing when there is none.
  - `setDescription`: the words a screen reader reads for the tile; null removes them.
- `FloatingTile.setImeProbe(listener)`: a non-null listener is told the bottom keyboard inset and its visibility when they change; null turns it off. It never shows or hides the tile. No app code calls it.
- `ShowResult`, in this order, and what the app should do:
  - `SHOWN`: the tile is on screen. Nothing more to do.
  - `ALREADY_SHOWN`: it was already on screen. Nothing changed.
  - `PERMISSION_MISSING`: the app may not draw over other apps. Nothing was added and no setting was read or
    changed. Send the user to the system overlay-permission page and call `show()` again afterwards.
  - `FAILED`: the permission is there but the system refused the window. The tile is still hidden; a later
    `show()` may try again.
- `TileState`, in this order, and what the tile shows:
  - `IDLE`: dictation is off or not ready. The microphone with a plain outline. A tap on the microphone calls `onTap`.
  - `ARMED`: dictation is on and ready. The outline pulses (the armed ring pulse, see Invariants) and is steady when animations are off. A tap on the microphone calls `onBegin`.
  - `RECORDING`: the microphone is open. The window is three 48 dp touch zones wide and one tile high: the X, the microphone with the six grille slots lit as the level meter, and the check.
  - `SENDING`: recording is over and the text is on its way. The tile shows the warning outline and pulses
    slowly (the busy pulse, same as MIC_BUSY). Taps do nothing.
  - `FAILED`: the last try did not work. The outline is in the danger colour. A tap on the microphone calls `onTap`.
  - `SENT`: the text is committed (copy confirmed). The outline is in the `sent` colour (green). A tap on the microphone
    calls `onTap`, as in `IDLE`.
  - `SENT_LOCAL`: the text is committed by the phone model after the server path failed. The outline is in the `warning`
    colour (orange). A tap on the microphone calls `onTap`, as in `IDLE`.
  - `MIC_BUSY` - the microphone is not available. The app decides when to show it. The tile shows a mic glyph with a red circle and diagonal slash (danger colour) over a dark outline. The circle and slash pulse slowly; the outline does not pulse. No text. A tap reports like IDLE.
- `ScreenMicProbe` (the debug probe's handle on the Home mic): `probeSetState(state)` pushes the state to the mic the same way the app pushes it, on the main thread, and answers whether it pushed; `probeTouchLog(log)` routes each line of the mic's touch path to `log`, and `null` stops logging. The app's debug source set reaches the mic only through this interface, never through the view's internals; it carries no behaviour of its own.
- Everything else is `internal`.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_ui_tokens (registered in modules.toml)

## Does Not Own
- Dictation itself: recording, and putting the text into the target field (the app and the commit module, ADR-022); the overlay never inserts text.
- Gesture/voice triggers
- What a callback does: whether `onTap`, `onBegin`, `onCancel` or `onSend` starts, stops, sends or discards a recording, and what the app does after it is called (the app).
- The send phrase: listening for it and acting on it (the app); the tile only has the check and the microphone.
- The text commit: putting the text into the target field (the commit module).
- The permission request and the microphone service: sending the user to the overlay-permission page, the foreground service that listens and records, and the audio capture (the app).
- Which state the app pushes after a commit, including SENT, SENT_LOCAL and the state after a clipboard-only commit
  (the app; the tile only shows the state it is given).
- Deciding when the armed ring pulse runs: the module decides it from the state and the tile's own shown, attached, visible and screen state; the app only pushes the state.

## Test Locations
- Unit (Kotlin): `android/modules/overlay/src/test/kotlin/`. Run: `./gradlew :android:modules:overlay:test`
- Contract: `tests/contract/test_overlay_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_overlay_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.
- All the test classes named below are in `android/modules/overlay/src/test/kotlin/dev/breaker/dictation/overlay/`, which also holds the helpers `FakeTileWindow`, `FakeSettingsStore`, `TestData`, `SourceText`, `ModuleFiles`, `AdapterRules`, `TileAdapterWiringRules` and `PulseSourceRules` (they hold no tests).

**Verified by tests on a plain JVM** (the class that checks each line is named in brackets):
- Show and hide (`TileControllerShowHideTest`, `FloatingTileFacadeTest`): the four `ShowResult` answers, a second
  show (also after the permission was taken away: it answers `ALREADY_SHOWN` and asks the system nothing), a show
  after a hide, a hide while hidden, a hide that drops a pressed finger which was not a drag (a release after the
  next show does not tap), a failed settings read starting the tile in the centre, and the public tile passing each
  call, each push from the app and each answer through unchanged, with `create` handing each callback on under its own
  name. A missing permission adds nothing and touches no setting, and the next `show()` works once the permission is
  there.
- Placement math and clamping (`TilePlacementTest`): fractions to pixels and back on screens that do not start at
  0,0, round half up, all four edges, a zero movable range, a tile larger than the area, an inverted pixel box refused and an empty one accepted.
- Tap versus drag (`TouchInterpreterTest`, `TouchInterpreterGestureTest`): the touch slop as a straight-line distance, exactly the slop is still a
  tap, the first drag step carries the whole distance from the down point, the sub-pixel remainder is carried, a
  drag that returns to its start is still a drag, a cancel with and without a drag, signs in all four directions, a
  second press inside a drag starts a fresh gesture, and a move or a release with no press gives nothing.
- Drag behaviour in the tile (`TileControllerDragTest`, `TileControllerDragStepTest`): a tap calls `onTap` once and
  moves and saves nothing; a drag moves by the finger delta and never taps; the clamp on all four sides; the usable
  area and the tile size are read again at every step; a cancel in a drag saves once and does not tap; a cancel
  without a drag does nothing; a tap after a finished drag still taps; touches while hidden do nothing; a display
  change while shown places the tile again from its fraction, does nothing while hidden and is ignored in the
  middle of a drag; a drag after a display change starts from the new place.
- A hide in the middle of a drag (in the show and hide class above): the dragged position is saved and the window is
  removed once, and the next show uses that position. The order of the save and the removal is not observable on the
  test doubles, so it is not checked.
- Persistence (`TileControllerPersistTest`): one save per drag end with the fraction of the final clamped place,
  also after a drag past an edge and back; nothing while the finger moves and nothing on a tap; load, change the
  position, save, so every other setting is kept (also one changed during the drag); the saved position is restored
  by the next show and fitted to a different screen; a failed save and a failed read are swallowed, reported once,
  leave the tile where it is and do not stop the next save; no callback is fine; the extreme fractions.
- Theme (`TileThemeTest`): at construction, set before show, set while shown (applied once), set while hidden
  (no window call).
- The glyph and the sizes (`TileGlyphTest`, until the art replaces its rectangles): every rectangle inside the unit square with an area, every role drawn,
  the colours from the palette in light and dark, the 64 dp tile at least the minimum touch target, the 4 dp corner
  within the hard-edge limit, the 2 dp outline width, and every outline line being 2 over 64 of the unit square.
- Pushes from the app (`TileControllerStateTest`, `TileControllerHideTest`, `TileControllerNoticeTest`,
  `TileControllerNoticeClearTest`, `TileControllerHiddenTouchTest`): the tile starts
  idle; pushes while hidden touch no window and the next `show()` draws them; `show()` adds, then draws, then resizes
  only when the shape is wide; a different state removes the notice and the same state keeps it; the level goes back to
  zero on entering or leaving recording, a level outside recording draws nothing, levels outside 0 to 1 are held and a
  value that is not a number is 0; the tile redraws only when the lit count changes; the description reaches the face; a
  theme change draws the face again in the new colours; a notice widens the window and leaves the saved position alone, is
  ignored while recording, is cleaned before it is drawn, is kept on screen and is placed again after a display change;
  `hide()` keeps the state, the level and the notice for the next `show()`; a sentence is gone from the face when
  recording starts, also when it was pushed while hidden; after a theme change a push that changes nothing draws
  nothing; a press made while hidden is not a tap after the next `show()`, and touches on the old place of a hidden
  tile call nothing.
- Buttons and callbacks (`TileControllerButtonsTest`, `TileControllerCallbackTest`, `TileControllerGestureRulesTest`,
  `TileGestureEdgesTest`, `TileRoutingTest`, `TileControllerNoticeDismissTest`): each pair of tapped part and state, for all eight states, is routed as the table says (a tap on SENT or SENT_LOCAL is a plain tap); idle and failed call
  `onTap`, armed calls `onBegin` and stays armed, recording sends on the microphone and the check and cancels on the X,
  sending ignores taps, and under a notice the microphone taps and the strip dismisses; a missing callback does nothing; one tap with a
  wobble is one call; `hide()`, `show()` and the pushes call no callback; an exception from a callback reaches the caller
  and the tile keeps working; a slide on the wide tile never moves it or saves; a change of shape under a pressed finger
  ends the gesture; `hide()` while recording clears it; a tap anywhere on the square tile is a tap on the microphone;
  on the wide tile a point less than a pixel above or left of the window is outside it, not the first row or column; on the recording window a tap is read against the three 48 dp zones (cancel, mic, send), so a tap in the mic zone is the microphone.
- Layout, meter, notice text and colours (`TileLayoutTest`, `TileLayoutZonesTest`, `LedMeterTest`, `NoticeRulesTest`,
  `TileStyleTest`): window sizes and cell places per shape and tile size, with half-open edges, and the recording zones (cancel, mic, send) at 48 dp each that tile the window; the wide window sits over
  the square tile and is moved only as far as needed to stay on screen; the six grille slots are lit left to right and never more than six (`TileArtRulesTest`);
  the lit count is linear, goes up to the next whole segment and never goes down as the level rises; the notice rules
  (null and blank give nothing, breaks and tabs become spaces, trimmed, cut at 80 without splitting a surrogate pair);
  every colour is an opaque palette field and only the outline depends on the state.
- The Android adapter cannot run on a JVM. Its source text is read by `AdapterGateTest`, which checks: android
  names only in the eight adapter files: WindowManagerTileWindow, TileView, FloatingTile, ArmedPulse, CloseTargetView, CloseTargetWindow, TileArtDraw and ScreenMic; the overlay window type and no legacy type; the names `FLAG_NOT_FOCUSABLE`
  and `FLAG_LAYOUT_IN_SCREEN` are used in the window code (not that they are combined into the layout flags
  argument); the add is inside a try that catches a refusal and answers `REFUSED`; the covered-touch filter is
  switched on and not undone; no text input word; no colour literal; the manifest declares exactly the one
  permission; the permission answer comes from the system and never from a literal `true`.
- The tile view and the window adapter are read as text too (`TileViewGateTest`, `TileViewDrawingGateTest`, `TileViewHaloGateTest`, `ControlDrawGateTest`,
  `MainSourceWordsGateTest`): `setFrame` sets x, y, width and height and calls `updateViewLayout`; the window flags are the
  three of the plain tile and are never changed; the add has three catches, the last a `RuntimeException`, and answers
  `REFUSED`; the view takes every colour from the face's look and sets none through a setter; it starts no timer or
  animation and writes no log; it sets the content description from the face; it lights the six grille slots through `slotLit`,
  cuts its cells from the height when collapsed or recording and a third of the width for a notice, draws the X on the left and the check on
  the right and only while recording, and wraps the notice to the strip in at most two lines with an ellipsis; no main
  source holds a literal with words of its own.
- What each part of the tile view is painted with and where (the view's mapping rules, re-pointed to the path-data mic):
  each mic face, slot and letter, the outline, the X and the check (each with its trim outline, `look.glyphOutline`), and the notice text take their own colour of the look;
  the window draws no fill of any kind; the mic and the outline are drawn into the microphone cell; the X and the check are
  two filled shapes, each with a trim outline (`look.glyphOutline`) and no halo; the draw stops when the cell side is not positive;
  the outline is as thick as `RING_DP`; no colour is made from numbers and no literal is given to a colour call.
- The hand-overs between values in the window adapter and the view (`TileAdapterWiringGateTest`): `setFrame` copies x,
  y, width and height each into the window parameter of the same name and a move keeps the size; a good add keeps the
  view and its parameters and answers `ADDED` last; a new window starts with the idle square face; a theme change
  repaints the face with the look of its own state; the removal swallows only a view the system already removed;
  `applyFace` stores the face and redraws at once; the notice strip draws the notice and never the description; the
  touch handler gives the sink the screen position of the finger that owns the gesture and answers true.
- Hygiene (`ModuleHygieneTest`): main sources hold no thread primitive, no clock read, no network class and no
  file over 300 lines; test sources hold no clock or thread word.
- The public surface (`PublicSurfaceTest`): the four `ShowResult` values and the eight `TileState` values in order and the
  public tile members are read by reflection; that every other top-level declaration is `internal` is read from the source text.

- The armed ring pulse (`PulseRulesTest`, `PulseAlphaTest`, `TileControllerPulseTest`, `PulseGateTest`): the pure rules run only when all five conditions hold and only an armed, shown tile wants a pulse; a non-finite alpha is drawn at full strength; the controller asks for the pulse before each face, stops it on hide and on leaving armed, and gives the same tap answers with the pulse on or off; the animator is named in one file only, the view's four hooks reach the pure rule, and the view passes the system's animator switch as the fifth argument (`PulseGateTest`: the view asks the system whether animations are on). All of this is read as text, not run.
- The inset probe (`InsetChangeFilterTest`, `FloatingTileImeProbeTest`): the filter accepts the first pair, a repeated pair once, a changed bottom or visibility, and the same pair again after `forget()`; the facade hands the listener to the window, a null listener reaches the window as null, a show and a hide make the same window calls with the probe on and off, and setting the probe changes no window call. `ImeProbeSourceGateTest` reads the window adapter as text: one attach of the inset listener, reached only with a listener, detached on null, asking the filter before it calls the listener, returning the view's own answer, `add()` keeping the plain tile's three flags, and no main file naming the keyboard signal setting.

- ScreenMic (`MicAccessibilityTest`, `MicAccessibilityFloorTest`, `MicA11yStateTest`, `ScreenMicRecordingLookGateTest`, `MicBusyFaceTest`): the pure accessibility model, the 48 dp floor and the click order in `ScreenMic.kt` (read as text), hover and focus memory, the X and check drawn only while recording (text), and the busy face's colour, routing and sign. No test draws ScreenMic, measures it in a real layout or sends it a real touch; the Home mic is proven only on the emulator and a phone.

**Art and look (the new test files):**
- `TileArtTest`: the shape, op and number counts and the coordinate sums equal the art's table; every shape is closed; the six
  slots rise strictly in x, have equal width and sit inside the well; every coordinate is inside the 1024 box, and the scaled
  box stays inside the cell.
- `TileArtGeometryTest`: the busy sign's centre, radius, stroke and slash ends, the glow widths 4, 6 and 8 dp, and the art-to-cell frame (scale, centre and origin) for a square and a non-square cell; plain JVM, no android names.
- `ControlArtSvgTest`: holds the svg element text of the two buttons as strings in the test (it does not read an art file) and checks the parsed path numbers against `ControlArt` and against Kotlin literals; checks the outline width (22) and miter limit (10) against `ArtMetrics`, the bounds and corners of each shape, the danger and primary face colours, that neither part is in `TileArt.all`, and the placement of the 396 unit box in a 200 unit cell; plain JVM.
- `ControlDrawRules`: holds the text rules for `drawControl` (two passes on one path, the fill in the face colour of its part, the outline in `look.glyphOutline` with the miter join, butt cap, the control miter limit and outline width, the paint join, miter and cap read before the first pass and put back after the cell block, both passes inside the cell block) and for the two constants and the two face arms. It is a rules file, not a test; `ControlDrawGateTest` runs it.
- `ControlDrawGateTest`: runs the `ControlDrawRules` checks first on edited copies of the real source (each edit fails by name when its text is missing) and then on the real `TileArtDraw.kt` and `TileArtRules.kt`; reads text only, does not run the view.
- `TileArtRulesTest`: the shade arithmetic (identity at 0, white at 1, black at -1, alpha kept, NaN and out-of-range held);
  `slotLit` for each lit count from 0 to 6 in `RECORDING`, and all lit outside it; the glow alphas.
- `TileArtFacesTest`: one test per face with its literal ARGB in the light and in the dark palette; the faces that shade the
  trim are flat in the light theme.
- `TileRecordingLayoutTest`: the recording window is three 48 dp zones wide and one tile high, with no strip; the notice window keeps
  its strip; the mic art cell keeps the 64 dp side, and the cancel, mic and send zones are 48 dp each.
- `TileControlPlacementTest`: the X and the check in the recording window (`ControlLayout` in `TileControlLayout.kt`): each is 90% of the silhouette's full width within 0.01 px, its nearest path edge is 8 dp from the silhouette outline, it is centred on the silhouette's centre line and lies inside its own 48 dp zone (cancel or send); the window width, the mic zone centring, and the button art extents match the art data.
- `TileOutlineColourTest`: the outline colour in each of the eight states equals the old ring mapping, as a literal table in
  both themes.
- The glow arithmetic (`glowDrawColor`) is in `PulseRulesTest`.

**Not verified by any test:** the lift of a second finger in the tile view (only the first finger is followed) and the
gravity value are read in the code only.
What the view paints, and where, how it hands over a touch, and the guards around moving and removing the window are
checked only as text: no test draws the view or sends it a touch.

**Not verified on a device:**
- The window really appearing above other apps.
- Other apps keeping focus and the keyboard.
- Drag feel and the real touch slop.
- The permission grant flow, and revocation while the tile is shown.
- System-bar and cutout insets, and whether the window position is really counted from the screen corner with a
  cutout or system bars.
- Right-to-left layouts: by design they do not affect the tile (left gravity), but this has not been tried on a
  device.
- Rotation re-layout.
- Density.
- Vendor quirks.
- The look of the mic art: the glow on a light and a dark wallpaper, the 2 dp outline at tile size, and the six grille slots at tile size.
- Whether `setFilterTouchesWhenObscured` behaves as intended.
- The armed ring pulse on a phone: that the animator really runs and stops with the hooks above; that the screen-off and window-visibility callbacks fire as the documentation describes; that a system animation scale change while the pulse runs applies at the next start; how the outline looks.
- The battery cost of the armed ring pulse (see Known Gotchas).
- How the green (SENT) and orange (SENT_LOCAL) outlines read in sunlight, in light and dark.
- The expanded tile, the meter and the notice: see "Known Gotchas" below.
- System detach: the proposed fix adds a race (a late `onDetached` from a removed view), so it stays a device check.

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
- asks a small set of sub-agents for advice on design, risks and tests
  before and during the build;
- hands the finished, checked module over as a single pull request for review.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- The overlay window is asked to be not focusable and the tile is tap-only (focus is not verified on a device); dictation happens in place on the tile and no Activity or window opens during dictation (ADR-022).
- Call everything from the main thread. The module takes no locks and starts nothing in the background.
- The tile disappears with the process. The app's foreground service owns the lifetime: if the process dies, the
  tile is gone and the app has to call `show()` again.
- Make one `FloatingTile` with `create` per use and keep it. Each one owns its own window, so two tiles made for the
  same job would show two windows.
- An exception thrown by any of the app's callbacks (`onTap`, `onBegin`, `onCancel`, `onSend`) is not caught by the module.
- The tile is 64 dp square, and the outline is 2 dp thick. The notice window is three tiles wide and one and a half tiles high; the recording window is three 48 dp touch zones wide and one tile high.
- The mic art cell is no longer the mic zone: the art cell (64 dp at the tile size) can overlap the cancel and send zones beside it, and touch only follows the zones (`TileLayout.micZone` is the mic's touch zone), not the art.
- The close-target box centre sits at 0.905 of the usable height, tuned under a Galaxy S25 Ultra keyboard so a dropped tile lands in the box while the field stays reachable.
- The close-target box keeps the same size in landscape as in portrait: its window width comes from the screen's short side and its height from the long side, clamped to the bounds, so it is not half-size in landscape.
- A display change (rotation, fold) while a finger is dragging is ignored and is not replayed when the drag ends:
  the tile is placed again at the next `show()` or the next `onDisplayChanged()`, and a further drag move also
  clamps it to the new screen.
- Only the finger that went down is followed. A second finger that lands, moves or lifts changes nothing.
- ScreenMic is built once by the app and re-added on every Home redraw: its host must take it out of its old parent first (ui `attachMicSlot` does). A TalkBack double tap on the host is routed by the mic's model state alone: the Provider's `performAction` calls `ScreenMicProbeKit.hostAction(view)`, which dispatches `MicTapZone.hostClick(state)` — never the stored touch. A real finger still reads the stored down point through `route()`. Pinned: `ScreenMicHostClickTest` (the state-only host decision) and `ScreenMicHostClickWiringGateTest` (the call site: `performAction` dispatches `hostAction`, not `route`).
- If the system has already removed the window, moving it does nothing and `hide()` is safe. The tile still counts
  as shown until `hide()` is called.
- The window position x counts from the left edge in any layout direction, because the window uses `LEFT` gravity,
  not `START`.
- The module never changes the state, so `onSend` is called for every tap on the check or the microphone while the state is still `RECORDING`. A double tap on a tile that has just opened lands on the microphone and ends an empty recording. The app must tolerate a repeated `onSend` and an empty recording, and push `SENDING` to stop it. The module has no clock to wait out a second tap.
- Pushing the state the tile already has changes nothing, so it does not clear a notice; only a different state or `clearNotice()` does.
- A level pushed outside `RECORDING` is stored but not lit, and the meter starts empty when `RECORDING` is entered.
- While `SENDING` the tile shows the warning outline and pulses slowly (the busy pulse, same as MIC_BUSY); the app's description is the only cue.
- `SENDING` and `SENT_LOCAL` share the warning outline. They are told apart only by the pulse: it runs while sending and stops at `SENT_LOCAL`. This is interim, for the first test build; the final colours come in the art phase.
- The MIC_BUSY circle-and-slash is the art's own geometry, drawn from its numbers: a red ring 92 art units wide (mid radius 394, centre 512, 500 in the 1024 box), and a 45-degree slash 92 units wide through the centre. Under them a dark outline 124 units wide (92 plus 16 on each side), in the danger colour shaded toward black, not pulsing. The ring and the slash pulse with the busy pulse. Its look on a device is not checked.
- No text is drawn on the tile in any MIC_BUSY state. The accessibility label is the only content description.
- A tap on a MIC_BUSY tile reports a tap exactly like IDLE. The app decides what to do.
- sendA11y is guarded. It sends nothing and builds no event while accessibility is off (AccessibilityManager.isEnabled is false), because the platform throws IllegalStateException ("Accessibility off") from requestSendAccessibilityEvent and that kills the app; with accessibility on it is unchanged. The decision is ScreenMicA11y.shouldSend(enabled), and ScreenMicA11ySourceGateTest pins the guard before AccessibilityEvent.obtain. Found by the debug mic probe on the emulator, 2026-10-20.
- `hide()` while the tile is expanded or recording removes the window and calls no callback; the pushed state, level and notice are kept and the next `show()` draws them.
- Battery: while armed with the screen on, the pulse redraws the tile every frame for as long as dictation stays armed. The cost is not measured. Device check: battery over 30 minutes armed, pulse on versus animations off (steady ring).
- The pulse stops when the state leaves ARMED, when the tile is hidden or detached, when the window is not visible, and when the screen turns off; a system animation scale change applies at the next start.
- **Not verified on a device (the expanded tile and what it draws).** Nothing here has been run on a device:
  - Focus with the user's keyboard up: that the wide window, like the square one, leaves the field underneath focused and the keyboard on screen.
  - The expanded window over other apps, and partial covers (another window over only part of the tile, and what the system does with the touches there).
  - Meter legibility: the unlit slot is the page background colour (bg), drawn on the body, which is the text colour in the light theme and the surface colour in the dark theme. The contrast of the lit and unlit slots on that body was not measured on a device.
  - Touch size of the X, the check and the microphone (each zone is 48 dp wide and one tile, 64 dp, high), and the feel of a tap on the wide tile.
  - The notice text size and fit: it is drawn in at most two lines with the end cut by an ellipsis, so a sentence of 80 characters may not show whole.
  - The X and the check have no accessibility node of their own: the tile is one view with one description, so a screen reader cannot reach the X; what a screen reader does with a double tap on the tile is not known.
  - The window jumps at a screen edge when it expands: next to an edge the window is moved to stay on screen, so the microphone appears at a different place, and a window wider than the usable area loses part of the send zone.
  - A CR LF pair in a notice shows as two spaces.
  - Colour pair choices: the contrast of the outline, the grille slots, the X, the check, their trim outlines and the notice text against the wallpaper and the body colour was not measured on a device.
- Frame and ledge faces: they shade the trim colour. In the light theme the trim is black, so they cannot get darker and are flat at black. In the dark theme the trim is white, and they shade toward black. In both themes they sit on the dark body (`bodyColor(look)`).
- The glow is an approximation: three stroke layers of falling alpha (0.45, 0.25, 0.12) at 4, 6 and 8 dp, not a blur. How it reads at tile size, on a light or a dark wallpaper, is not checked on a device.
- The outline is 2 dp on the silhouette edge at tile size. Whether it reads on a phone is not checked.
- The ui-tokens card still says 12 to 16 segments for the LED bar. The tile does not use that range: it has six grille slots (`LedMeter.SEGMENTS` = 6). The range belongs to the token owner, who moves it later.
- The notice text has no plate behind it. It is drawn over a halo of the surface colour (`look.background`), `HALO_DP` wider and drawn first. The halo is a stroke, not a plate. The X and the check have no halo: they are filled shapes with a trim outline.
- The code keeps the names `drawRing`, `ringDrawColor` and `RING_DP` for the outline, so the pinned rules keep holding. This card says outline.
- **Not verified on a device (the close target).** The look and touch of the truck box are NOT verified without a device: how the box and the truck look at the real screen density, how thin the edge reads against the wallpaper, whether the truck reads as a truck at this size, and how the vendor delivers touches to the overlay window are not checked on a device.
