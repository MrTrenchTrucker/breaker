# AGENTS.md - android/modules/overlay/

## Purpose

Floating tile = CB mic glyph + LED bar meter (WindowManager overlay, F36). The floating tile that appears over any app and is where dictation is started, watched and ended. A tap on the microphone calls the app's `onTap` (idle or failed) or `onBegin` (armed), and the app decides whether that starts or stops dictation. The real CB mic art is **not built yet**: the tile draws a stand-in glyph (a placeholder; final mic art later).

**What is built now:**
- A small draggable tile (56 dp) with a stand-in glyph, shown above other apps.
- Seven states, pushed by the app with `setState`: `IDLE`, `ARMED`, `RECORDING`, `SENDING`, `FAILED`, `SENT` and `SENT_LOCAL`. The tile never
  changes the state itself, not even on a tap. The ring around the microphone takes its colour from the state. The
  ring pulses while the tile is ARMED and shown (the armed ring pulse, see Invariants). The app drives nothing: the pulse
  starts and stops with the state and with the tile being shown, attached, visible and on a lit screen.
- An LED bar meter of 12 segments, fed by the app with `setLevel` (0.0 to 1.0), drawn directly above the microphone
  while the state is `RECORDING`.
- The expanded tile while `RECORDING`: [X cancel] [microphone with the meter above it] [check = send], in one window
  three tiles wide. The expanded tile cannot be dragged.
- A notice: a sentence from the app (`showNotice`) drawn above the microphone, in the same wide window, until the app
  clears it (`clearNotice`) or pushes a different state. The module holds no words of its own.
- A description (`setDescription`) that the view gives to screen readers as its content description.
- The callbacks `onTap`, `onBegin`, `onCancel` and `onSend`.
- MIC_BUSY: the microphone is not available. The app decides when to show it. The tile shows a mic glyph with a red circle and diagonal slash (danger colour), pulsing slowly. No text. A tap reports like IDLE.

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
- Permissions: the app asks for its permissions together at startup (F11): microphone, sensor, overlay (drawing
  over other apps), foreground service and notifications, and it has internet access for the server. This module
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
- The wide window (a notice, or recording) is placed around the square tile: its microphone cell lies over the square
  tile where the screen allows, and the window is moved only as far as needed to stay inside the usable area. It never
  changes the saved position. Next to a screen edge the microphone therefore appears at a different place when the
  window opens. A tap on the wide window is read against the place the module asked the window to have.

**Overlay spoofing (T4):**
- What the module does: the tile is tap-only (no text input of any kind), and the tile view asks the system to
  drop touches that arrive while another window covers it (`setFilterTouchesWhenObscured(true)`).
- What the module does NOT do: it does not verify window ownership and makes no such claim. The threat-model row
  for T4 also names that check; it is not built here.
- Not stealing focus is a property of the window flag above (`FLAG_NOT_FOCUSABLE`); T4 is about spoofing.

**Theme:** the app passes the theme the tile should show, light or dark (ui-tokens `ThemeMode`, not core's
`ThemeMode` which also has SYSTEM). The tile draws from the ui-tokens palette only: surface behind, primary for
the glyph, trim for the detail, a ring in trim (idle), primary (armed, recording, sending), danger (failed), the palette's
`sent` field (SENT) or its `warning` field (SENT_LOCAL), primary for the lit meter segments and the page background colour (bg) for the unlit ones, text for the X, the check and the
notice. No colour value is written in this module's sources.

**Glyph:** a stand-in glyph drawn in code from a few rectangles (a head, a handle, a grille, thin outlines). It is a
placeholder and not the real CB mic art; final mic art comes later and replaces it. The tile is 56 dp square with 4 dp corners, and the
outline lines are 2 dp thick.
The ring around the microphone is 2 dp thick and sits just inside the edge of the microphone's cell.

**Look & feel (F36):**
- **Built:** the seven states, the LED bar meter and the expanded tile, as described below, and the armed ring pulse
  (one platform animator, see Invariants; the app drives nothing). **Not built yet:** the real CB mic art (final mic art later), the
  app's pushing of the sent and fallback states. Each line below says which it is.
- The tile IS the **CB mic glyph** (favicon art from `shared/ui-tokens`) - not built yet: the tile draws a
  stand-in glyph drawn in code, and the favicon art is not used.
- **LED bar meter** (digital Cobra-style segments) renders **directly above the
  mic** when the mic is awake and recording; fills with audio level - built: 12 segments lit from the left, the level
  times 12 taken up to the next whole segment, no smoothing; lit in the primary colour and unlit in the page
  background colour. The audio-level feed itself is the app's: it pushes the level with `setLevel`.
- State colors: the ring around the microphone is the trim colour when idle, the primary colour when armed, recording
  or sending, and the danger colour after a failure (all palette fields). **SENT = green** (the palette's `sent` field:
  copy confirmed) and **SENT_LOCAL = orange** (the palette's `warning` field: committed by the phone model after the
  server path failed) are built as states and colours; the tile only shows what the app pushes. The armed ring pulses
  while the tile is ARMED (see Invariants), and is steady when animations are off. While sending, the tile looks like the armed tile
  but does not pulse;
  the app's description is the only cue.

**Not built yet:**
- The real CB mic art (final mic art later; the glyph stays a placeholder).
- The app's pushing of SENT and SENT_LOCAL, and the state the app pushes after a clipboard-only commit (OPEN: the app's
  decision, not decided here).
- Next slice, ordered (F38). (1) A tap anywhere on the wide window of a notice clears the notice and the tile
  collapses to the small tile, and a new optional callback `onNoticeDismissed` (no arguments, null by default) is
  called once so the app drops its notice line; `onTap` is not called (today only the microphone answers and calls `onTap`, which can show
  the same notice again, so the user could not get the small tile back). The time limit on a notice is the app's,
  not this module's. (2) Close target: while a small tile is dragged, a close target (an X in a circle, palette
  colours only, placeholder art) is shown centred near the bottom of the usable area; it goes when the drag ends.
  A drop with the tile's centre inside the target hides the tile, calls a new optional callback `onCloseRequested`
  (no arguments, null by default) and does not save the position; a drop elsewhere saves as today. A tap never
  closes. The target is shown in the same overlay window type and is not focusable and not touchable.
- Words on the tile of its own, sound or haptics, the window-ownership check (T4), the
  audio capture and the audio-level feed (the app's), the foreground service (the app's), asking the user for the
  overlay permission (the app's), and any gesture or phrase code.

**Build phase:** Phase 6, together with `gesture`. Needs first: `core` and `ui-tokens` (both on main).

## Invariants
- Tile floats above other apps; a tap on the microphone while idle or failed calls the app's `onTap` once and the tile does not move (F4, F5).
- Tile is draggable and position persists.
- The position is saved once per drag end, never while the finger moves, and every other setting is kept.
- No focus stealing; other apps keep focus while tile is shown (the window is asked to be not focusable; not checked on a device).
- T4: the tile is tap-only and drops touches while covered. It does not verify window ownership.
- Tile renders a stand-in mic glyph (the CB mic art is not built yet); the LED bar fills above it while recording (F36).
- State colors: a failure turns the ring to the danger colour (F36). SENT is green (the palette's `sent` field) and
  SENT_LOCAL is orange (the palette's `warning` field).
- The tile never chooses SENT or SENT_LOCAL: only the app pushes them.
- No clocks and no threads in the module: no long press, no timeouts, no background work.
- No timers, clocks or threads in the module. The one exception is the armed ring pulse: one platform `ValueAnimator` in ArmedPulse.kt, on the main thread, running only while the tile is ARMED, shown, attached, visible, the screen is on and system animations are on; it never changes the state or what a tap does.
  and the notice stays until the app clears it or pushes a different state.
  Tested by: `ModuleHygieneTest`, `TileViewGateTest`, `PulseRulesTest`.
- The busy ring pulse: while the tile is MIC_BUSY and shown, the ring pulses slowly (period 3200 ms, alpha min 0.6). It stops when the state leaves MIC_BUSY, when the tile is hidden or detached, when the window is not visible, or when the screen turns off — the same run conditions as the armed pulse.
- Colours are palette fields only: every colour the tile draws is a ui-tokens palette field; no colour value and no new token is written in this module.
  Tested by: `TileStyleTest`, `TileGlyphTest`, `TileViewGateTest`, `TileViewMappingGateTest`, `TileViewDrawingGateTest`, `AdapterGateTest`.
- The module holds no words of its own: the notice and the description come from the app.
  Tested by: `MainSourceWordsGateTest`, `TileAdapterWiringGateTest`.
- Only a collapsed tile drags and saves its position: the wide window (a notice, or recording) cannot be dragged, and a slide that starts on a button or in the strip moves nothing and saves nothing.
  Tested by: `TileControllerGestureRulesTest`, `TileControllerButtonsTest`, `TileControllerDragTest`.
- A tap on a collapsed tile is a tap on the microphone from any point of it.
  Tested by: `TileControllerGestureRulesTest`, `TileControllerDragTest`.
- The module never changes the state by itself: a tap leaves the state as it is, so `onSend` is called again for every
  tap on the check or the microphone until the app pushes `SENDING`, and the app must tolerate it.
  Tested by: `TileControllerButtonsTest`, `TileRoutingTest`.
- A level pushed outside `RECORDING` is ignored and reset on entering `RECORDING`; it is also reset on leaving it.
  Tested by: `TileControllerStateTest`.
- A callback runs only for a tap; `show()`, `hide()` and every push from the app call none, and `hide()` while expanded or recording removes the window without a callback.
  Tested by: `TileControllerButtonsTest`, `TileControllerGestureRulesTest`, `TileControllerHiddenTouchTest`.
- A push that changes nothing the tile shows makes no window call, and the meter redraws only when the number of lit segments changes.
  Tested by: `TileControllerStateTest`, `TileControllerNoticeTest`, `TileControllerHiddenTouchTest`.
- The usable area is read again at every move; it is never cached.
- Every test passes on one core and in parallel: none waits on a clock, depends on core count or scheduling
  order, or depends on another test class having run.

## Owns
Floating tile = CB mic glyph + LED bar meter (WindowManager overlay, F36). Built: the draggable tile with a
stand-in glyph, the seven states, the armed ring pulse (one platform animator), the LED bar meter, the expanded recording tile with cancel and send, and the notice and
description the app pushes. Not built yet: the real CB mic art.

## Public Interface
FloatingTile (via create), ShowResult, TileState

- `FloatingTile`, built only through `FloatingTile.create(context, settings, onTap, theme, onSaveFailed, onBegin, onCancel, onSend)`:
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
  - A callback that is null makes the tap do nothing. An exception thrown by any callback is not caught by the module.
- `FloatingTile.show(): ShowResult`, `hide()`, `setTheme(theme)`, `onDisplayChanged()`, the property `isShown`, the pushes from the app `setState(state)`, `setLevel(level)`, `showNotice(text)`, `clearNotice()` and `setDescription(text)`, and the property `state`.
  - `TileState.MIC_BUSY` — public; the overlay face when the microphone is not available. The app decides when to show it. The tile shows a mic glyph with a red circle and diagonal slash (danger colour), pulsing slowly. No text. A tap reports like IDLE.
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
  - `showNotice`: a sentence from the app, shown above the microphone. Each line break and each tab becomes one space
    (a CR LF pair is two line breaks, so two spaces), the text is trimmed and cut to 80 characters without splitting a
    surrogate pair, and a blank text shows nothing. It is ignored while `RECORDING`. It stays until `clearNotice()` or a
    different state; the module has no timer.
  - `clearNotice`: takes the sentence away; does nothing when there is none.
  - `setDescription`: the words a screen reader reads for the tile; null removes them.
- `ShowResult`, in this order, and what the app should do:
  - `SHOWN`: the tile is on screen. Nothing more to do.
  - `ALREADY_SHOWN`: it was already on screen. Nothing changed.
  - `PERMISSION_MISSING`: the app may not draw over other apps. Nothing was added and no setting was read or
    changed. Send the user to the system overlay-permission page and call `show()` again afterwards.
  - `FAILED`: the permission is there but the system refused the window. The tile is still hidden; a later
    `show()` may try again.
- `TileState`, in this order, and what the tile shows:
  - `IDLE`: dictation is off or not ready. The microphone with a plain ring. A tap on the microphone calls `onTap`.
  - `ARMED`: dictation is on and ready. The ring pulses (the armed ring pulse, see Invariants) and is steady when animations are off. A tap on the microphone calls `onBegin`.
  - `RECORDING`: the microphone is open. The tile widens to the X, the microphone with the meter above it, and the check.
  - `SENDING`: recording is over and the text is on its way. Taps do nothing.
  - `FAILED`: the last try did not work. The ring is in the danger colour. A tap on the microphone calls `onTap`.
  - `SENT`: the text is committed (copy confirmed). The ring is in the `sent` colour (green). A tap on the microphone
    calls `onTap`, as in `IDLE`.
  - `SENT_LOCAL`: the text is committed by the phone model after the server path failed. The ring is in the `warning`
    colour (orange). A tap on the microphone calls `onTap`, as in `IDLE`.
  - `MIC_BUSY` — the microphone is not available. The app decides when to show it. The tile shows a mic glyph with a red circle and diagonal slash (danger colour), pulsing slowly. No text. A tap reports like IDLE.
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
- The glyph and the sizes (`TileGlyphTest`): every rectangle inside the unit square with an area, every role drawn,
  the colours from the palette in light and dark, the 56 dp tile at least the minimum touch target, the 4 dp corner
  within the hard-edge limit, the 2 dp outline width, and every outline line being 2 over 56 of the unit square.
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
  `TileGestureEdgesTest`, `TileRoutingTest`): each pair of tapped part and state, for all seven states, is routed as the table says (a tap on SENT or SENT_LOCAL is a plain tap); idle and failed call
  `onTap`, armed calls `onBegin` and stays armed, recording sends on the microphone and the check and cancels on the X,
  sending ignores taps, and under a notice only the microphone answers; a missing callback does nothing; one tap with a
  wobble is one call; `hide()`, `show()` and the pushes call no callback; an exception from a callback reaches the caller
  and the tile keeps working; a slide on the wide tile never moves it or saves; a change of shape under a pressed finger
  ends the gesture; `hide()` while recording clears it; a tap anywhere on the square tile is a tap on the microphone;
  on the wide tile a point less than a pixel above or left of the window is outside it, not the first row or column.
- Layout, meter, notice text and colours (`TileLayoutTest`, `TileLayoutZonesTest`, `LedMeterTest`, `NoticeRulesTest`,
  `TileStyleTest`): window sizes and cell places per shape and tile size, with half-open edges; the wide window sits over
  the square tile and is moved only as far as needed to stay on screen; the meter segments partition the meter exactly;
  the lit count is linear, goes up to the next whole segment and never goes down as the level rises; the notice rules
  (null and blank give nothing, breaks and tabs become spaces, trimmed, cut at 80 without splitting a surrogate pair);
  every colour is an opaque palette field and only the ring depends on the state.
- The Android adapter cannot run on a JVM. Its source text is read by `AdapterGateTest`, which checks: android
  names only in the three adapter files; the overlay window type and no legacy type; the names `FLAG_NOT_FOCUSABLE`
  and `FLAG_LAYOUT_IN_SCREEN` are used in the window code (not that they are combined into the layout flags
  argument); the add is inside a try that catches a refusal and answers `REFUSED`; the covered-touch filter is
  switched on and not undone; no text input word; no colour literal; the manifest declares exactly the one
  permission; the permission answer comes from the system and never from a literal `true`.
- The tile view and the window adapter are read as text too (`TileViewGateTest`, `TileViewDrawingGateTest`,
  `MainSourceWordsGateTest`): `setFrame` sets x, y, width and height and calls `updateViewLayout`; the window flags are the
  three of the plain tile and are never changed; the add has three catches, the last a `RuntimeException`, and answers
  `REFUSED`; the view takes every colour from the face's look and sets none through a setter; it starts no timer or
  animation and writes no log; it sets the content description from the face; it lights the segments below the lit count,
  cuts its cells from the height when square and a third of the width when wide, draws the X on the left and the check on
  the right and only while recording, and wraps the notice to the strip in at most two lines with an ellipsis; no main
  source holds a literal with words of its own.
- What each part of the tile view is painted with and where (`TileViewMappingGateTest`): each glyph part, the base, the
  ring, the X and the check, and the notice text take their own colour of the look; the glyph and the ring are drawn
  into the microphone cell; the X and the check are two different strokes each; the draw stops when the cell side is
  not positive, a meter segment too small for its gap is skipped, the segments are inset by the gap on all four edges
  and the ring is as thick as `RING_DP`; no colour is made from numbers and no literal is given to a colour call.
- The hand-overs between values in the window adapter and the view (`TileAdapterWiringGateTest`): `setFrame` copies x,
  y, width and height each into the window parameter of the same name and a move keeps the size; a good add keeps the
  view and its parameters and answers `ADDED` last; a new window starts with the idle square face; a theme change
  repaints the face with the look of its own state; the removal swallows only a view the system already removed;
  `applyFace` stores the face and redraws at once; the notice strip draws the notice and never the description; the
  touch handler gives the sink the screen position of the finger that owns the gesture and answers true.
- Hygiene (`ModuleHygieneTest`): main sources hold no thread primitive, no clock read, no network class and no
  file over 300 lines; test sources hold no clock or thread word.
- The public surface (`PublicSurfaceTest`): the four `ShowResult` values and the seven `TileState` values in order and the
  public tile members are read by reflection; that every other top-level declaration is `internal` is read from the source text.

- The armed ring pulse (`PulseRulesTest`, `PulseAlphaTest`, `TileControllerPulseTest`, `PulseGateTest`): the pure rules run only when all five conditions hold and only an armed, shown tile wants a pulse; a non-finite alpha is drawn at full strength; the controller asks for the pulse before each face, stops it on hide and on leaving armed, and gives the same tap answers with the pulse on or off; the animator is named in one file only, the view's four hooks reach the pure rule, and the view passes the system's animator switch as the fifth argument (`PulseGateTest`: the view asks the system whether animations are on). All of this is read as text, not run.

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
- The look of the stand-in glyph.
- Whether `setFilterTouchesWhenObscured` behaves as intended.
- The armed ring pulse on a phone: that the animator really runs and stops with the hooks above; that the screen-off and window-visibility callbacks fire as the documentation describes; that a system animation scale change while the pulse runs applies at the next start; how the ring looks.
- The battery cost of the armed ring pulse (see Known Gotchas).
- How the green (SENT) and orange (SENT_LOCAL) rings read in sunlight, in light and dark.
- The expanded tile, the meter and the notice: see "Known Gotchas" below.

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
- The tile is 56 dp square, and the outline lines of the glyph are 2 dp thick. The wide window is three tiles wide and one and a half tiles high.
- A display change (rotation, fold) while a finger is dragging is ignored and is not replayed when the drag ends:
  the tile is placed again at the next `show()` or the next `onDisplayChanged()`, and a further drag move also
  clamps it to the new screen.
- Only the finger that went down is followed. A second finger that lands, moves or lifts changes nothing.
- If the system has already removed the window, moving it does nothing and `hide()` is safe. The tile still counts
  as shown until `hide()` is called.
- The window position x counts from the left edge in any layout direction, because the window uses `LEFT` gravity,
  not `START`.
- The module never changes the state, so `onSend` is called for every tap on the check or the microphone while the state is still `RECORDING`. A double tap on a tile that has just opened lands on the microphone and ends an empty recording. The app must tolerate a repeated `onSend` and an empty recording, and push `SENDING` to stop it. The module has no clock to wait out a second tap.
- Pushing the state the tile already has changes nothing, so it does not clear a notice; only a different state or `clearNotice()` does.
- A level pushed outside `RECORDING` is ignored, and the meter starts empty when `RECORDING` is entered.
- While `SENDING` the tile looks like the armed tile without the pulse (a collapsed tile with the steady primary ring); the app's description is the only cue.
- The MIC_BUSY slash is a code-drawn placeholder: eight stair-step rectangles from corner to corner of the tile, drawn on top of the microphone. Final art comes later (issue #87).
- No text is drawn on the tile in any MIC_BUSY state. The accessibility label is the only content description.
- A tap on a MIC_BUSY tile reports a tap exactly like IDLE. The app decides what to do.
- `hide()` while the tile is expanded or recording removes the window and calls no callback; the pushed state, level and notice are kept and the next `show()` draws them.
- Battery: while armed with the screen on, the pulse redraws the tile every frame for as long as dictation stays armed. The cost is not measured. Device check: battery over 30 minutes armed, pulse on versus animations off (steady ring).
- The pulse stops when the state leaves ARMED, when the tile is hidden or detached, when the window is not visible, and when the screen turns off; a system animation scale change applies at the next start.
- **Not verified on a device (the expanded tile and what it draws).** Nothing here has been run on a device:
  - Focus with the user's keyboard up: that the wide window, like the square one, leaves the field underneath focused and the keyboard on screen.
  - The expanded window over other apps, and partial covers (another window over only part of the tile, and what the system does with the touches there).
  - Meter legibility: the unlit segment is the page background colour, which is only about 1.09 to 1 against the tile surface in both themes, so the off segments are faint and the lit ones stand out by lightness.
  - Touch size of the X, the check and the microphone (each cell is one tile, 56 dp), and the feel of a tap on the wide tile.
  - The notice text size and fit: it is drawn in at most two lines with the end cut by an ellipsis, so a sentence of 80 characters may not show whole.
  - The X and the check have no accessibility node of their own: the tile is one view with one description, so a screen reader cannot reach the X; what a screen reader does with a double tap on the tile is not known.
  - The window jumps at a screen edge when it expands: next to an edge the window is moved to stay on screen, so the microphone appears at a different place, and a window wider than the usable area loses part of the send cell.
  - A CR LF pair in a notice shows as two spaces.
  - Colour pair choices: the contrast of the ring, the meter, the X, the check and the notice text against the tile surface was not measured on a device.
