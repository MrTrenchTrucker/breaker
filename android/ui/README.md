# Ui — README

Screens: history, settings, auth, training, and the setup walk-through for the
permissions Breaker needs.

## What it does now

Three screens are built.

**Settings.** The theme choice, the routing mode, the three switches and the read-only lines. Every change is written through the settings store the app passes in.

**History.** The transcriptions, newest first, from the history store the app passes in. Each row shows a mono timestamp in the phone's own short date and time, a source tag (phone or server), the text, and Copy and Delete buttons. Copy puts the text on the clipboard, marked as sensitive. Delete does not delete at once: the row is replaced by a "Deleted." line with an Undo button for 5 seconds. Only when that time ends is the transcription removed from the store. Undo brings the row back and the store is not touched. The list opens with 50 rows; Load more adds 50, up to 300. The list is read off the main thread, and a screen that closes still carries out the deletes waiting. If the app closes the screen, or the screen is rotated, a delete still waiting is carried out once, right after, on the serial dispatcher. If the store refuses that delete, nothing is said and the row stays in the store. If the app is killed during the 5 seconds, nothing is deleted. A screen result that arrives after the view has closed is dropped, and a failed read's sentence is not lost: it is shown with the next result. Delete All, search, long-press delete are not built; the route into this screen is built in the app.

**Setup walk-through.** The model step first, then one step per permission, in this order:

1. Speech model. Breaker needs a speech model on your phone to turn your voice into text. The step says "Not done yet" until the model is on the phone, then "Done". While it is not there, the button "Download the speech model" starts the download, and a line under it gives the download size, says it comes from GitHub and that your server is not used, and says the file is checked before it is used.
2. Display over other apps (the overlay), so the tile can show.
3. Microphone, the one permission the switch needs.
4. Notifications, optional. The screen says plainly what is lost without them.
5. Accessibility, so the words can be typed into the field. The step lists what the service does and does not do. On Android 13 and later, while the service is off, it also explains the "Restricted setting" box, in three steps. That explanation has no status and no button, because the phone gives no way to read that state.

Below the steps is the on/off switch for Breaker. Only the microphone gates it. A missing overlay or accessibility step shows a warning next to the switch and does not block it. A "Check again" button draws the screen afresh. The screen also draws itself again whenever its window gets focus back, so a permission given on a system page shows when the user returns.

**Controls kit.** The shared look for the new screens, internal to this module and not used by any screen yet: the main button (a raised face with a lit top line and a darker lip; pushed down with no lip while pressed; flat and muted while disabled), the neutral Cancel button, the red Delete button, the full-width button, the switch, the Ready, Phone only and Failed badges, the text field (a drawn look only: it takes no typing) and the slanted step marks. Every colour comes from the theme. The geometry and the colours are plain Kotlin with JVM tests (`ui/controls/`); the views in `ui/render/controls/` only draw what that code returns.

## Public declarations

The module offers exactly five, all in three files:

- `createSettingsView(context, settings)` builds the settings view.
- `createOnboardingView(context, switch, accessibilityServiceComponent, modelReady, downloadLine, modelDownloadMb, onDownloadModel, settings)` builds the setup view. The four parameters after the accessibility service's name carry the speech model step: `modelReady` says whether the model is on the phone, `downloadLine` gives the sentence under its button (or null), `modelDownloadMb` gives the download size in decimal megabytes, and `onDownloadModel` asks the app to start the download. `settings` is the app's settings store, in which the walk-through keeps whether the microphone prompt was asked.
- `createHistoryView(context, history)` builds the history view. `history` is the core `HistoryStore` port; the screen only lists and deletes through it.
- `BreakerSwitch` is the interface the app implements: `isOn()`, `switchOn()` and `switchOff()`. `switchOn()` answers `BreakerSwitch.Result`, which is nested in the interface: `On`, `AlreadyOn` or `Refused(sentence)`. The sentence is shown to the user as the app wrote it.
- `ModelStepControl` is the interface the setup view answers: `refresh()` redraws the walk-through, and `focusDownload(): Boolean` puts focus on the model step's download button. It answers true when the button is drawn and took focus; false when there is no button to focus (the model is ready, or nothing is drawn yet) or the button would not take focus. `showSwitchRefused(sentence)` draws a refused switch-on as the walk-through's notice, the same notice the switch button draws; a blank sentence gives the switch-failed notice. The refusal stays drawn on each drawing that is not a tap, until a tap or a read that finds the switch on clears it. The app casts the setup view to it.

Everything else is internal or private.

## How the app uses it

- Pass the Activity itself as the context. Permission prompts need an Activity, and a context that wraps one is unwrapped. With no Activity the system pages still open, but the two prompts report that they could not open.
- Pass the accessibility service's flattened component name as a String, for example `ComponentName(context, TheService::class.java).flattenToString()`. This module names no class of another module. The name is compared whole against the phone's list of enabled services; "pkg/pkg.Class" and "pkg/.Class" both match.
- Implement `BreakerSwitch` over whatever keeps Breaker running. For "off" to hold, the app must not switch Breaker on again at its next start.
- Pass the app's `HistoryStore` to `createHistoryView`. The screen never touches the history module directly, so the app must wire the store there and call the store's purge on its own schedule.

## What it stores

The walk-through writes one thing: whether the microphone prompt was asked, into the settings store the app passes in, after the system prompt has come back. Which permissions are granted and whether Breaker is on are read afresh every time the screen is drawn. The notification prompt that was already shown is remembered only in the running view, and so is the in-memory copy of the microphone flag, so that the next tap on the same button opens a settings page instead of asking again. The in-memory copies are gone when the view is; the stored microphone flag stays. The history screen keeps the rows it has shown so far and any delete still waiting for its 5-second Undo, also only in the running view. A delete still waiting when the view goes is carried out once, right after, on the serial dispatcher; the history screen does not otherwise keep anything.

## What is not unit-tested

How the views are measured, laid out and painted, and how taps are routed inside the host views, are not unit-tested. The same holds for `AndroidSetupPlatform` (reads the permission state and opens the settings pages), `SetupHostView` (the scrolling container for the setup screen) and `createOnboardingView`. They are pinned by text scans in the test tree and by the device checklist in the card. The setup walk-through has run on a phone in the test builds; the device checklist in AGENTS.md lists what is still unchecked.

The controls kit's views (`ui/render/controls/`) are not unit-tested either: how they measure, press and paint is pinned by text scans in the test tree and by the device checklist. The decisions the views make (a badge's fill, a button's width, the full-width width, the switch outline, which step marks are lit, where the badge pill sits) are plain Kotlin with their own JVM tests.

The history screen has unit tests: `HistoryIntentHandlerTest` and `HistoryScreenTest` drive the handler and the drawn tree with a fake store, clipboard and scheduler, so the 5-second Undo is checked by firing its window by hand, not by real time. `HistoryRenderCheckTest` reads the source text for the hand-off of pending deletes on detach, the clipboard's sensitive mark and the scheduling adapter (`ViewDelayedWork`); it does not run them. No test runs the real timer, the real clipboard or a real rotation; those are on the device checklist in AGENTS.md.

The theme control on the settings screen is a text action rather than a rocker toggle, which meets the function and not the look the design notes ask for. The words on the setup screen are drafts for review.

## Tests

From the repository root:

- `./gradlew :android:ui:test`
- `python3 -m unittest discover -s tests/contract -t tests/contract -p test_ui_contract.py`

Every run must report more than 0 tests.

Full module card, with the invariants, the test list and the known gotchas (including what is not yet verified on a device): `AGENTS.md` in this folder.
