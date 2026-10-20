# Ui — README

Screens: home, history, settings, how it works, about, auth, training, and the first-run
flow for the permissions Breaker needs.

## What it does now

Five screens are built, and a first-run flow runs first on a phone with no speech model.

**Home.** The launcher opens on this screen, after the first-run flow on a phone with no speech model: an on/off card with the switch, the speech model row (Ready, or "Not downloaded"), the server row ("Not available") and the last dictation; a big mic that says "Tap to talk"; the last transcript with a Copy button, which is drawn only while there is a transcript; and a menu with History, Settings, How it works and About. History and Settings open their screens through the app. How it works and About open their own screens through the app. A tap on the big mic starts a take (or answers why it cannot), and a second tap ends a take that the Home tap began; the take's line shows below the last transcript, and the mic takes the recording look while it records.

**Settings.** The appearance choice (System, Light or Dark), the routing mode, the three switches, the model row with its download button (shown while the model is not on the phone), and the server line, which is read only. While no server is set, the server line shows "(not set)" in the muted colour; the art does not draw a muted field, so that look is the smallest one from the theme's own tokens. Every change is written through the settings store the app passes in.

**History.** The transcriptions, newest first, from the history store the app passes in. Each row shows a mono timestamp in the phone's own short date and time, a source tag (phone or server) in a small outlined pill, the text, and Copy, Show all or Hide, and Delete buttons, which take the compact width (a 93.33 dp floor, so the three fit a 360 dp phone inside the card). A text that takes more than 6 lines at the card width is cut to 6 lines with an ellipsis; Show all opens the whole text and Hide folds it back. Copy puts the text on the clipboard, marked as sensitive, and always copies the whole text, even when the row is folded. Delete does not delete at once: the row is replaced by a "Deleted." line with an Undo button for 5 seconds. Only when that time ends is the transcription removed from the store. Undo brings the row back and the store is not touched. The list opens with 50 rows; Load more adds 50, up to 300. The list is read off the main thread, and a screen that closes still carries out the deletes waiting. If the app closes the screen, or the screen is rotated, a delete still waiting is carried out once, right after, on the serial dispatcher. If the store refuses that delete, nothing is said and the row stays in the store. If the app is killed during the 5 seconds, nothing is deleted. A screen result that arrives after the view has closed is dropped, and a failed read's sentence is not lost: it is shown with the next result. Delete All, search, long-press delete are not built; the route into this screen is built in the app.

**First run.** On a phone with no speech model the app shows this flow first: a welcome page, the four permission pages (microphone, overlay, notifications and accessibility), then the model download page with a bar, the percent and the megabytes. The flow ends when the model is on the phone. The missing list on the last permission page says what is still to be given. The pictures are fixed PNG files.

**Controls kit.** The shared look for the screens, internal to this module: the main button (a raised face with a lit top line and a darker lip; pushed down with no lip while pressed; flat and muted while disabled), the neutral Cancel button and the outlined button (both go flat and muted while disabled, the same as the main button), the red Delete button and the full-width button (both are main buttons, so they go flat the same way while disabled), the switch (its track is 48 by 80/3 dp, the art's 72 by 40 px at 1.5 px per dp), the Ready, Phone only and Failed badges, the text field (a drawn look only: it takes no typing; its words can be drawn muted, with the fill and the outline unchanged, and the art is silent about a muted field, so the muted colour is the smallest look from the theme's own tokens) and the slanted step marks. Every colour comes from the theme. The buttons on the History entry rows take a compact width: its floor is 93.33 dp (the 140 px of the History mock-up), the kit default stays 113.33 dp, a long label still grows its button, and no colour is added. The kit is drawn by the history screen (its Load more, Copy, Show all or Hide, Delete and Undo buttons), by the settings screen (the selected cell of its theme and routing rows, its switch rows, its server value field and its model download button) and by the first-run flow (its full-width permission and download buttons, its Next button, its step marks and its download bar); and the Ready, Phone only and Failed badges are drawn by no screen (the Settings model row draws its READY badge as a plain node). The geometry and the colours are plain Kotlin with JVM tests (`ui/controls/`); the views in `ui/render/controls/` only draw what that code returns.

**Layout.** A screen is a tree of nodes (`screen/Screen.kt`) that the renderer draws. Every distance is a spacing name, XS 4, S 8, M 12, L 16 and XL 32 dp from the ui-tokens scale, and the renderer reads each name by its own name. The title bar (`screen/TitleBar.kt`) opens Settings and History; Home has the menu icon and the logo word instead. Rules and stripes run the full width through the one padding place, the host's padding: the app pads the screen root by the system bars, the display cutout and the 16 dp gutter, and no screen adds its own outer padding. The Settings view follows a theme change as a whole. Stock Android widgets are named only in the render layer and in the host files, and the settings column and the page scroller are the two containers the rule allows by name. Back closes an open Home menu, through the same close as the dim.

## Public declarations

The module offers exactly ten, all in six files:

- `createHomeView(context, switch, modelReady, lastDictationAt, lastTranscript, transcriptTime, onCopy, onHistory, onHowItWorks, onAbout, onSettings, onTap, micView, settings)` builds the home view. It reads the stored theme through `settings` when the view is built. The app passes the switch, the answers for the model, the last dictation and the last transcript, the phone's time for the transcript, the copy request, the four screen requests (history, how it works, about and settings) and the request for a take from the big mic; every fact is read again on each drawing.
- `createSettingsView(context, settings, modelReady, downloadLine, onDownloadModel)` builds the settings view. It draws live through the theme controller, not the stored-theme reader.
- `createFirstRunView(context, accessibilityServiceComponent, modelReady, downloadLine, modelDownloadMb, onDownloadModel, settings, onFinished)` builds the first-run view described above. `onFinished` is called once, when the model is on the phone on the download page. `modelReady`, `downloadLine` and `onDownloadModel` mean the same as for the settings view, and `modelDownloadMb` gives the download size in decimal megabytes. The phone's light or dark mode is read once from `context`, and the stored theme is read through `settings` when the view is built.
- `createHowItWorksView(context, settings)` builds the How it works view. The screen reuses the welcome headings and whys from the first-run pages. It reads the stored theme through `settings` when the view is built.
- `createAboutView(context, settings)` builds the About view. The screen shows exactly two lines: what Breaker does and the license. No version line unless one already exists on main. It reads the stored theme through `settings` when the view is built.
- `createHistoryView(context, history, settings)` builds the history view. `history` is the core `HistoryStore` port; the screen only lists and deletes through it. It reads the stored theme through `settings` when the view is built.
- `BreakerSwitch` is the interface the app implements: `isOn()`, `switchOn()` and `switchOff()`. `switchOn()` answers `BreakerSwitch.Result`, which is nested in the interface: `On`, `AlreadyOn` or `Refused(sentence)`. The sentence is shown to the user as the app wrote it.
- `DownloadControl` is the interface a view answers to the app for the model download: `refresh()` re-reads the model providers and draws the screen again, `focusDownload(): Boolean` puts focus on the model step's download button, and three download calls with empty defaults (`downloadProgress`, `downloadChecking`, `downloadStopped`). The app casts the settings view and the first-run view to it.
- `ModelStepControl` extends `DownloadControl`; the interface the first-run view answers for its switch notice. Adds `showSwitchRefused(sentence)`: draws a switch-on that the app refused as the notice. The app casts the first-run view to it.

- `HomeControl` is the interface the home view answers: `showLine(line)` shows the line below the last-transcript box (nothing for null), and `showRecording(recording)` shows the recording look on the big mic. `refresh()` re-reads the app's facts and draws the screen again. The app casts the view from `createHomeView` to it.

Everything else is internal or private.

## How the app uses it

- Pass the Activity itself as the context. Permission prompts need an Activity, and a context that wraps one is unwrapped. With no Activity the system pages still open, but the two prompts report that they could not open.
- Pass the accessibility service's flattened component name as a String to `createFirstRunView`, for example `ComponentName(context, TheService::class.java).flattenToString()`. This module names no class of another module. The name is compared whole against the phone's list of enabled services; "pkg/pkg.Class" and "pkg/.Class" both match.
- Implement `BreakerSwitch` over whatever keeps Breaker running. For "off" to hold, the app must not switch Breaker on again at its next start.
- Pass the app's `HistoryStore` and settings store to `createHistoryView`. The screen never touches the history module directly, so the app must wire the store there and call the store's purge on its own schedule.

## What it stores

The first-run view writes one thing: whether the microphone prompt was asked, into the settings store the app passes in, after the system prompt has come back. The settings view writes the theme mode through the same store. Which permissions are granted and whether Breaker is on are read afresh every time the screen is drawn. The notification prompt that was already shown is remembered only in the running view, and so is the in-memory copy of the microphone flag, so that the next tap on the same button opens a settings page instead of asking again. The in-memory copies are gone when the view is; the stored microphone flag stays. The history screen keeps the rows it has shown so far and any delete still waiting for its 5-second Undo, also only in the running view. A delete still waiting when the view goes is carried out once, right after, on the serial dispatcher; the history screen does not otherwise keep anything.

## What is not unit-tested

How the views are measured, laid out and painted, and how taps are routed inside the host views, are not unit-tested. The same holds for `AndroidSetupPlatform` (reads the permission state and opens the settings pages) and `FirstRunHostView` (the scrolling container for the first-run screen). They are pinned by text scans in the test tree and by the device checklist in the card. The first-run flow has run on a phone in the test builds; the device checklist in AGENTS.md lists what is still unchecked.

The controls kit's views (`ui/render/controls/`) are not unit-tested either: how they measure, press and paint is pinned by text scans in the test tree and by the device checklist. The decisions the views make (a badge's fill, a button's width, the full-width width, the switch outline, which step marks are lit, where the badge pill sits) are plain Kotlin with their own JVM tests. The download bar's numbers (its fill, its percent and where its stripes sit) are plain Kotlin in `ui/controls/DownloadBarMath.kt`, with `DownloadBarMathTest`; `DownloadBarView` only draws what they return.

The history screen has unit tests: `HistoryIntentHandlerTest` and `HistoryScreenTest` drive the handler and the drawn tree with a fake store, clipboard and scheduler, so the 5-second Undo is checked by firing its window by hand, not by real time. `HistoryRenderCheckTest` reads the source text for the hand-off of pending deletes on detach, the clipboard's sensitive mark and the scheduling adapter (`ViewDelayedWork`); it does not run them. No test runs the real timer, the real clipboard or a real rotation; those are on the device checklist in AGENTS.md. The rule that folds a long text is unit-tested in `HistoryCollapseTest`; the line count the text is cut to, and whether its break settings match the TextView, are measured on a device only, and the device checklist in AGENTS.md lists both.


The theme control on the settings screen is three cells, System, Light and Dark, drawn as one segmented track. The selected cell is the stored choice, so System stays selected while the app follows the phone. The words on the first-run flow are drafts still to be checked. Each screen entry except the settings view reads the stored theme through the settings store each time a screen is built; the settings view draws live through the theme controller.

## Tests

From the repository root:

- `./gradlew :android:ui:test`
- `python3 -m unittest discover -s tests/contract -t tests/contract -p test_ui_contract.py`

Every run must report more than 0 tests.

Full module card, with the invariants, the test list and the known gotchas (including what is not yet verified on a device): `AGENTS.md` in this folder.
