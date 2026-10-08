# Ui — README

Screens: history, settings, auth, training, and the setup walk-through for the
permissions Breaker needs.

## What it does now

Two screens are built.

**Settings.** The theme choice, the routing mode, the three switches and the read-only lines. Every change is written through the settings store the app passes in.

**Setup walk-through.** One step per permission, in this order:

1. Display over other apps (the overlay), so the tile can show.
2. Microphone, the one permission the switch needs.
3. Notifications, optional. The screen says plainly what is lost without them.
4. Accessibility, so the words can be typed into the field. The step lists what the service does and does not do. On Android 13 and later, while the service is off, it also explains the "Restricted setting" box, in three steps. That explanation has no status and no button, because the phone gives no way to read that state.

Below the steps is the on/off switch for Breaker. Only the microphone gates it. A missing overlay or accessibility step shows a warning next to the switch and does not block it. A "Check again" button draws the screen afresh. The screen also draws itself again whenever its window gets focus back, so a permission given on a system page shows when the user returns.

## Public declarations

The module offers exactly three, all in two files:

- `createSettingsView(context, settings)` builds the settings view.
- `createOnboardingView(context, switch, accessibilityServiceComponent)` builds the setup view.
- `BreakerSwitch` is the interface the app implements: `isOn()`, `switchOn()` and `switchOff()`. `switchOn()` answers `BreakerSwitch.Result`, which is nested in the interface: `On`, `AlreadyOn` or `Refused(sentence)`. The sentence is shown to the user as the app wrote it.

Everything else is internal or private.

## How the app uses it

- Pass the Activity itself as the context. Permission prompts need an Activity, and a context that wraps one is unwrapped. With no Activity the system pages still open, but the two prompts report that they could not open.
- Pass the accessibility service's flattened component name as a String, for example `ComponentName(context, TheService::class.java).flattenToString()`. This module names no class of another module. The name is compared whole against the phone's list of enabled services; "pkg/pkg.Class" and "pkg/.Class" both match.
- Implement `BreakerSwitch` over whatever keeps Breaker running. For "off" to hold, the app must not switch Breaker on again at its next start.

## What it stores

Nothing. Which permissions are granted and whether Breaker is on are read afresh every time the screen is drawn. The only thing remembered is, in the running view, which permission prompts were already shown, so that the next tap on the same button opens a settings page instead of asking again. It is gone when the view is.

## What is not unit-tested

How the views are measured, laid out and painted, and how taps are routed inside the host views, are not unit-tested. The same holds for `AndroidSetupPlatform` (reads the permission state and opens the settings pages), `SetupHostView` (the scrolling container for the setup screen) and `createOnboardingView`. They are pinned by text scans in the test tree and by the device checklist in the card. Nothing in this module has run on a device.

The theme control on the settings screen is a text action rather than a rocker toggle, which meets the function and not the look the design notes ask for. The words on the setup screen are drafts for review.

## Tests

From the repository root:

- `./gradlew :android:ui:test`
- `python3 -m unittest discover -s tests/contract -t tests/contract -p test_ui_contract.py`

Every run must report more than 0 tests.

Full module card, with the invariants, the test list and the known gotchas (including what is not yet verified on a device): `AGENTS.md` in this folder.
