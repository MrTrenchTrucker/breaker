# Ui — README

Screens: history, settings, auth, training, and the setup walk-through for the
permissions Breaker needs.

## What it does now

Three screens are built.

**Settings.** The theme choice, the routing mode, the three switches and the read-only lines. Every change is written through the settings store the app passes in.

**History.** The transcriptions, newest first, from the history store the app passes in. Each row shows a mono timestamp in the phone's own short date and time, a source tag (phone or server), the text, and Copy and Delete buttons. Copy puts the text on the clipboard, marked as sensitive. Delete does not delete at once: the row is replaced by a "Deleted." line with an Undo button for 5 seconds. Only when that time ends is the transcription removed from the store. Undo brings the row back and the store is not touched. The list opens with 50 rows; Load more adds 50, up to 300. If the app closes the screen, or the screen is rotated, a delete still waiting is carried out at once. If the store refuses that delete, nothing is said and the row stays in the store. If the app is killed during the 5 seconds, nothing is deleted. Delete All, search, long-press delete and the route into this screen are not built.

**Setup walk-through.** One step per permission, in this order:

1. Display over other apps (the overlay), so the tile can show.
2. Microphone, the one permission the switch needs.
3. Notifications, optional. The screen says plainly what is lost without them.
4. Accessibility, so the words can be typed into the field. The step lists what the service does and does not do. On Android 13 and later, while the service is off, it also explains the "Restricted setting" box, in three steps. That explanation has no status and no button, because the phone gives no way to read that state.

Below the steps is the on/off switch for Breaker. Only the microphone gates it. A missing overlay or accessibility step shows a warning next to the switch and does not block it. A "Check again" button draws the screen afresh. The screen also draws itself again whenever its window gets focus back, so a permission given on a system page shows when the user returns.

## Public declarations

The module offers exactly four, all in two files:

- `createSettingsView(context, settings)` builds the settings view.
- `createOnboardingView(context, switch, accessibilityServiceComponent)` builds the setup view.
- `createHistoryView(context, history)` builds the history view. `history` is the core `HistoryStore` port; the screen only lists and deletes through it.
- `BreakerSwitch` is the interface the app implements: `isOn()`, `switchOn()` and `switchOff()`. `switchOn()` answers `BreakerSwitch.Result`, which is nested in the interface: `On`, `AlreadyOn` or `Refused(sentence)`. The sentence is shown to the user as the app wrote it.

Everything else is internal or private.

## How the app uses it

- Pass the Activity itself as the context. Permission prompts need an Activity, and a context that wraps one is unwrapped. With no Activity the system pages still open, but the two prompts report that they could not open.
- Pass the accessibility service's flattened component name as a String, for example `ComponentName(context, TheService::class.java).flattenToString()`. This module names no class of another module. The name is compared whole against the phone's list of enabled services; "pkg/pkg.Class" and "pkg/.Class" both match.
- Implement `BreakerSwitch` over whatever keeps Breaker running. For "off" to hold, the app must not switch Breaker on again at its next start.
- Pass the app's `HistoryStore` to `createHistoryView`. The screen never touches the history module directly, so the app must wire the store there and call the store's purge on its own schedule.

## What it stores

Nothing is written to disk. Which permissions are granted and whether Breaker is on are read afresh every time the screen is drawn. The only thing remembered is, in the running view, which permission prompts were already shown, so that the next tap on the same button opens a settings page instead of asking again. It is gone when the view is. The history screen keeps the rows it has shown so far and any delete still waiting for its 5-second Undo, also only in the running view. A delete still waiting when the view goes is carried out at once; the history screen does not otherwise keep anything.

## What is not unit-tested

How the views are measured, laid out and painted, and how taps are routed inside the host views, are not unit-tested. The same holds for `AndroidSetupPlatform` (reads the permission state and opens the settings pages), `SetupHostView` (the scrolling container for the setup screen) and `createOnboardingView`. They are pinned by text scans in the test tree and by the device checklist in the card. Nothing in this module has run on a device.

The history screen has unit tests: `HistoryIntentHandlerTest` and `HistoryScreenTest` drive the handler and the drawn tree with a fake store, clipboard and scheduler, so the 5-second Undo is checked by firing its window by hand, not by real time. `HistoryRenderCheckTest` reads the source text for the commit on detach, the clipboard's sensitive mark and the scheduling adapter (`ViewDelayedWork`); it does not run them. No test runs the real timer, the real clipboard or a real rotation; those are on the device checklist in AGENTS.md.

The theme control on the settings screen is a text action rather than a rocker toggle, which meets the function and not the look the design notes ask for. The words on the setup screen are drafts for review.

## Tests

From the repository root:

- `./gradlew :android:ui:test`
- `python3 -m unittest discover -s tests/contract -t tests/contract -p test_ui_contract.py`

Every run must report more than 0 tests.

Full module card, with the invariants, the test list and the known gotchas (including what is not yet verified on a device): `AGENTS.md` in this folder.
