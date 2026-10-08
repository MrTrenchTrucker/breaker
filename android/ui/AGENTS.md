# AGENTS.md — android/ui/

## Purpose
Screens: history, settings, auth, training, and the setup walk-through that
takes the user through the permissions Breaker needs (overlay, microphone,
notifications, accessibility) and holds the switch that turns Breaker on and
off. Built so far: the settings screen and the setup walk-through.

**Build phase:** Phase 1 (the app shell's first screens). Each screen arrives with its feature's phase, and the Trucking theme with Phase 22. Needs first: `core` and `shared/ui-tokens` (both on `main`).

## Owns
screen components; the setup walk-through (one step per permission, then Breaker's on/off switch) and the thin adapters in render/ that read the phone's permission state and open its settings pages; consumes `shared/ui-tokens` (Trucking theme).

## Public Interface
Three declarations; everything else in the module is internal or private.
- `createSettingsView(context, settings)`: builds the settings screen as a view.
- `createOnboardingView(context, switch, accessibilityServiceComponent)`: builds the setup walk-through and the on/off switch as a view. The app passes the accessibility service's flattened component name as a String ("pkg/pkg.Class"; "pkg/.Class" is accepted too).
- `BreakerSwitch`: the interface the app implements: `isOn()`, `switchOn()` (answers `BreakerSwitch.Result`: `On`, `AlreadyOn` or `Refused` with a sentence) and `switchOff()`. The result type is nested in the interface, so it adds no fourth top-level name.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_ui_tokens (registered in modules.toml)

## Invariants
screens render with ui-tokens; light/dark toggle persists (F25).
- This module stores nothing. Settings go through the store the app passes in; for the setup walk-through, what is granted and whether Breaker is on are read afresh every time the screen is drawn. The only memory is, in the running view, which permission prompts were already shown. Tested by: `PersistenceGateTest`, `SetupIntentHandlerTest`, `SetupAdapterGateTest`.
- Only the microphone gates the switch. Overlay or accessibility missing shows a warning near the switch; notifications are optional and the switch works without them. The handler does not refuse "switch on" itself: the gate is the disabled button on the screen and the app's own `Refused`. Tested by: `SetupModelTest`, `SetupScreenTest`, `SetupIntentHandlerTest`.
- The accessibility service is identified by a name the caller passes in as a String. The name is compared against the phone's list as a whole entry, case-sensitive, in either spelling of a class that starts with the package, and never as a part of a longer name. This module names no class of another module. Tested by: `AccessibilityListTest`, `SetupAdapterGateTest`.
- android.* is named only in render/ and SettingsEntry.kt. Tested by: `AndroidConfinementGateTest`.
- The public surface is exactly the three declarations above. Tested by: `PublicSurfaceGateTest`, `ObserverBindingTest`, `SetupAdapterGateTest`.
- Every button on the setup screen reports one of eight agreed action strings; an unknown string, or any other kind of intent, is not accepted and changes nothing; no branch over the intents falls back to a default. Tested by: `SetupScreenTest`, `SetupIntentHandlerTest`, `NodeModelTest`, `SettingsIntentHandlerSetupTest`.
- All words the user reads on the setup screen live in one object, are plain ASCII and carry no process words. Tested by: `SetupTextsTest`.
- The setup screen is drawn again when the window gets focus back, so a permission granted on a system page shows on return. Pinned by a text scan only. Tested by: `SetupAdapterGateTest`.

## Does Not Own
- Business logic (core)
- Platform adapters (modules/*) other than the thin setup adapters
- The service that keeps Breaker running (the app)
- The accessibility service itself (its own module)
- The overlay tile (the overlay module)

## Test Locations
- Unit (Kotlin): `android/ui/src/test/kotlin/dev/breaker/dictation/ui/`. Run: `./gradlew :android:ui:test`. Test classes by folder:
  - gate/: `AndroidConfinementGateTest`, `GateSelfTest`, `NoColourGateTest`, `NoPrivateSpacingGateTest`, `PersistenceGateTest`, `PublicSurfaceGateTest`
  - render/: `ObserverBindingTest`, `RenderMathTest`, `SetupAdapterGateTest`, `SetupEntryGateTest`
  - screen/: `NodeModelTest`
  - screen/onboarding/: `AccessibilityListTest`, `SetupIntentHandlerTest`, `SetupModelTest`, `SetupScreenTest`, `SetupTextsTest`
  - screen/settings/: `SettingsIntentHandlerSetupTest`, `SettingsIntentHandlerTest`, `SettingsScreenEditTest`, `SettingsScreenTest`
  - theme/: `PhoneModeTest`, `ThemeControllerFailureTest`, `ThemeControllerTest`, `ThemeTest`
  - write/: `SettingsWriteTest`
  - testing/: `FakeSettingsStoreTest`; helpers without tests: `FakeBreakerSwitch`, `FakeSettingsStore`, `FakeSetupPlatform`
- Contract: `tests/contract/test_ui_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_ui_contract.py`
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

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
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

## Known Gotchas
- Consumes shared/ui-tokens — do not hardcode colors in screens.
- The restricted-setting state cannot be read by any public API, so that step only explains: it has no status and no "Open app info" button.
- The texts on the setup screen are drafts for review. The accessibility limits are written from the accessibility decision record and are not word for word the service description.
- Breaker stays off after the user switches it off only if the app does not switch it on again at start.
- "Asked before" for the permission prompts lives in the view only. After a refusal in an earlier session, or after the view is rebuilt, the first tap on the microphone or notifications button may seem to do nothing.
- A notice lasts one drawing and has no live region, so a screen reader is not told when it appears. A focus change right after a tap may clear it at once.
- NOT VERIFIED ON A DEVICE (nothing in this module has run on one):
  - the overlay page deep link, where it lands on the phone's skin, and the vendor's label for that page (it may not read "Display over other apps");
  - both permission dialogs and permanent denial (microphone, and notifications from Android 13);
  - the accessibility list and the order of the restricted-setting steps on Android 13 to 16;
  - the component name form the phone stores in the enabled list (pkg/pkg.Class or pkg/.Class; both are accepted);
  - the status re-check on return from a system page or a dialog, and the scroll position after a redraw (it may land at the top);
  - the switch really starting the service, and isOn() answering true right after On;
  - OFF holding across the next start and after the process is killed (the app's part);
  - TalkBack, large font and the largest display size;
  - dark mode, including the contrast of the done and not-done words on the surface.
