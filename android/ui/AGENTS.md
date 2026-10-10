# AGENTS.md — android/ui/

## Purpose
Screens: history, settings, auth, training, and the setup walk-through that
takes the user through the permissions Breaker needs (overlay, microphone,
notifications, accessibility) and holds the switch that turns Breaker on and
off. Built so far: the settings screen, the setup walk-through and the history
screen.

**Build phase:** Phase 1 (the app shell's first screens). Each screen arrives with its feature's phase, and the Trucking theme with Phase 22. Needs first: `core` and `shared/ui-tokens` (both on `main`).

## Owns
screen components; the setup walk-through (one step per permission, then Breaker's on/off switch) and the thin adapters in render/ that read the phone's permission state and open its settings pages; the history screen (the list, copy, delete with a 5-second Undo, load more) and its thin adapters in render/ for the clipboard and the delayed work; every store and clipboard call runs on one process-wide serial dispatcher (screen/history/HistoryDispatcher.kt), and while a read is out the screen shows the last drawn state; consumes `shared/ui-tokens` (Trucking theme).

## Public Interface
Four declarations; everything else in the module is internal or private.
- `createSettingsView(context, settings)`: builds the settings screen as a view.
- `createOnboardingView(context, switch, accessibilityServiceComponent)`: builds the setup walk-through and the on/off switch as a view. The app passes the accessibility service's flattened component name as a String ("pkg/pkg.Class"; "pkg/.Class" is accepted too).
- `createHistoryView(context, history)`: builds the history screen as a view. `history` is core's `HistoryStore` port; `list(limit)` and `delete(id)` are the only calls the screen makes. The screen follows the phone's light and dark mode and keeps no setting.
- `BreakerSwitch`: the interface the app implements: `isOn()`, `switchOn()` (answers `BreakerSwitch.Result`: `On`, `AlreadyOn` or `Refused` with a sentence) and `switchOff()`. The result type is nested in the interface, so it adds no fifth top-level name.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_ui_tokens (registered in modules.toml)

## Invariants
screens render with ui-tokens; light/dark toggle persists (F25).
- This module stores nothing. Settings go through the store the app passes in; for the setup walk-through, what is granted and whether Breaker is on are read afresh every time the screen is drawn. The only memory is, in the running view, which permission prompts were already shown. Tested by: `PersistenceGateTest`, `SetupIntentHandlerTest`, `SetupAdapterGateTest`.
- Only the microphone gates the switch. Overlay or accessibility missing shows a warning near the switch; notifications are optional and the switch works without them. The handler does not refuse "switch on" itself: the gate is the disabled button on the screen and the app's own `Refused`. Tested by: `SetupModelTest`, `SetupScreenTest`, `SetupIntentHandlerTest`.
- The accessibility service is identified by a name the caller passes in as a String. The name is compared against the phone's list as a whole entry, case-sensitive, in either spelling of a class that starts with the package, and never as a part of a longer name. This module names no class of another module. Tested by: `AccessibilityListTest`, `SetupAdapterGateTest`.
- android.* is named only in render/ and SettingsEntry.kt. Tested by: `AndroidConfinementGateTest`. The history screen (screen/history) imports no android package; its clipboard and its scheduling are seams that the render/ adapters implement.
- The public surface is exactly the four declarations above. Tested by: `PublicSurfaceGateTest`, `ObserverBindingTest`, `SetupAdapterGateTest`.
- Every button on the setup screen reports one of eight agreed action strings; an unknown string, or any other kind of intent, is not accepted and changes nothing; no branch over the intents falls back to a default. Tested by: `SetupScreenTest`, `SetupIntentHandlerTest`, `NodeModelTest`, `SettingsIntentHandlerSetupTest`.
- Every button on the history screen reports one of four agreed action names (COPY, DELETE, UNDO, MORE) inside a History intent. The setup and settings handlers refuse a History intent they do not own; no test checks that yet. Tested by: `HistoryIntentHandlerTest` (an unknown id, action or row, and any other intent, is refused without a call).
- All words the user reads on the setup screen live in one object, are plain ASCII and carry no process words. Tested by: `SetupTextsTest`.
- All words the user reads on the history screen live in one object, `HistoryTexts` in screen/history/: plain ASCII, no process words, and no other user-facing sentence. The timestamp has no word of its own: it is the phone's own short date and time, in the phone's zone. Tested by: `HistoryScreenTest` (every chrome word is a `HistoryTexts` constant). The plain ASCII and no-process-word rule is not checked by any test yet. The failed-read sentence, "Could not load history.", is `HistoryTexts.NOTICE_LIST_FAILED` and sits in the same object.
- A transcription's text never appears in a notice or in an exception message. Every store and clipboard call is guarded, so no exception from them reaches the caller. Copy goes through the clipboard seam and marks the clip as sensitive. Tested by: `HistoryIntentHandlerTest` (a copy that throws shows only the copy failure sentence, with no text and no failure detail) and `HistoryRenderCheckTest` (the clipboard adapter sets the sensitive mark, checked by reading its source text).
- On the history screen, the rows shown so far live only in the running view and are gone when the view is. A delete still waiting for its Undo window is carried out once, right after the view goes, on the serial dispatcher (see the two cases below); if the app dies in that short gap, the rows stay. Tested by: `AsyncHistoryTest` for the close and the deletes, named in the close bullet below; the rows going with the view is not tested.
- The history list opens with 50 rows, newest first, exactly in the order the port returns them. Load more asks the port for 50 more. The cap is 300 rows; when the store holds 300 or more rows, at 300 there is no Load more button and a limit note shows instead, and when the store holds fewer rows than the cap the last page shows neither the note nor the button. Tested by: `HistoryIntentHandlerTest` (the first page of fifty, Load more, the 300 cap, "a store of 280 rows ends at 280 rows with neither the limit note nor Load more") and `HistoryScreenTest` (rows in the port's order, Load more only after a full page, the limit note at the cap, "a short last page at the cap shows neither the limit note nor Load more").
- Delete hides the row at once and, in its place, shows a block reading "Deleted." with an "Undo" action. Nothing is deleted at the tap. Undo cancels the wait and shows the row again; the store is not touched. Tested by: `HistoryIntentHandlerTest` (a Delete opens one window and calls no store; Undo cancels it; a second Undo or a late Undo calls nothing) and `HistoryScreenTest` (the Undo block takes the row's place).
- The Undo window is 5000 ms (UNDO_WINDOW_MS). When it ends, the row is deleted once through the port. A false or thrown result shows "Could not delete it." and the list is drawn again from the port. When a window ends on its own, the screen is drawn again through the view's change hook. Tested by: `HistoryIntentHandlerTest` (the window is 5000 ms; the window end deletes once and hands the screen to the change hook once; a refusal and a throw both show the delete failure, and the row comes back). These tests fire the window by hand with a fake scheduler; real time is not checked. `HistoryRenderCheckTest` checks the hook by reading the host's source text.
- A first read that fails shows "Could not load history." (`HistoryTexts.NOTICE_LIST_FAILED`) in place of the list and never the empty-state line. A Load more read that fails keeps the rows shown and shows the sentence once; the next read that works clears it. Tested by: `HistoryScreenTest` (a failed first read) and `HistoryIntentHandlerTest` (a Load more read that throws; a good read after a failed one).
- Two cases, stated separately. If the app dies inside the Undo window, nothing is deleted: the rows stay in the store, so this case fails safe. If the screen closes, or rotates so that the view detaches, every pending delete is carried out once, right after, on the serial dispatcher, each exactly once: this case fails towards delete, on purpose. If the app dies in the short gap between the detach and those deletes, the rows stay in the store, the same fail-safe as a death inside the window. Tested by: `HistoryIntentHandlerTest` (close deletes each pending row once and leaves no live window), `AsyncHistoryTest` (`closing with two pending deletes draws nothing and deletes both once in tap order`, `a second close after a reattach commits the new pending deletes`) and `HistoryRenderCheckTest` (the host closes the model once on detach, before the superclass is told, checked by reading its source text). The first case, app death inside the window, is not tested by any test. A delete the store refuses while the screen is closing or rotating is silent: the row stays in the store, and the user has already seen "Deleted."
- The only scheduling is behind the DelayedWork seam. Its real implementation is in render/ViewDelayedWork.kt, the only file that uses postDelayed and removeCallbacks. `HistoryRenderCheckTest` checks that by reading the source text: only that file uses the two calls, its cancel removes the posted runnable, and SettingsEntry.kt holds none of the scheduling words. Across the main tree, the scheduling words and GlobalScope, withContext, async and Dispatchers.Main appear in code only in that file, except that the async file may use launch. No test runs the real queue.
- Rotation ordering holds only when the old view's close is submitted before the new view's first read. An Activity recreate tears the old view down first, so it is covered. Building the new view before the old one is detached is not covered: a deleted row can show once until the next read. Tested by: `AsyncHistoryTest` (`a deleted row can show once when the new view reads before the old view closes`), which pins this limit and does not claim it is safe.
- A result posted from the serial queue to a detached view is parked by View.post until the view is attached again. Every result carries the epoch of the view's life it was made in, and reattaching moves the epoch on, so a parked read, tap or window-end result from before the close is dropped when it runs after the reattach; a read is also dropped if a newer read or tap was made after it. A delayed window runnable that fires after close finds its pending entry gone and draws nothing. Tested by: `AsyncHistoryTest` (`a tap result parked across a close and a reattach is not drawn`, `a window end result parked across a close and a reattach is not drawn`, `a window end result after the view closed is not drawn`, `a window runnable that fires after close finds nothing pending and draws nothing`, `a notice from a read parked across a reattach is not shown on the next tap`).
- A read whose result is dropped carries its failed-read notice forward: the notice is shown as a second notice after the next result's own notice, so the sentence is not lost; a read drawn later clears it. Tested by: `HistoryNoticeCarryTest` and `AsyncHistoryTest` (`a dropped read does not swallow the list failure sentence`, `a good read drawn after a dropped failing read leaves no carried sentence`, `the carried sentence is not doubled when the tap shows it too`).
- The model's scope has no exception handler. A store exception that escapes the handler's own guards ends the app, as the old main-thread call did. Tested by: none in the tree yet.
- Rows past the 90-day retention may still show until the app calls the history module's purge (ADR-010: a row expires when it is more than 90 days old at the purge). This screen does not call the purge. Tested by: none in the tree yet.
- The setup screen is drawn again when the window gets focus back, so a permission granted on a system page shows on return. Pinned by a text scan only. Tested by: `SetupAdapterGateTest`.

## Does Not Own
- Business logic (core)
- Platform adapters (modules/*) other than the thin adapters in render/ (setup, clipboard, delayed work)
- The service that keeps Breaker running (the app)
- The accessibility service itself (its own module)
- The overlay tile (the overlay module)
- Storage of transcriptions and the 90-day purge (the history module)
- Not built yet, ordered next: the speech model becomes the FIRST step of the setup walk-through, with the same
  shape as the permission steps: a sentence, a status line "Not done yet" or "Done", and one button "Download the speech
  model" that calls back into the app (the download itself, its progress and its sentences stay the app's). The app's
  separate download button above the walk-through then goes. This is an interface change to
  `createOnboardingView` (a model step parameter from the app), approved here.
- Not built yet. Delete All and search are in the design but not built: Delete All (the port has no delete-all, and it needs type-to-confirm); search (the port has none, and the node model has no text input); the app route to the history screen; long-press delete; true paging (needs a port change).

## Test Locations
- Unit (Kotlin): `android/ui/src/test/kotlin/dev/breaker/dictation/ui/`. Run: `./gradlew :android:ui:test`. Test classes by folder:
  - gate/: `AndroidConfinementGateTest`, `GateSelfTest`, `NoColourGateTest`, `NoPrivateSpacingGateTest`, `PersistenceGateTest`, `PublicSurfaceGateTest`
  - render/: `ObserverBindingTest`, `RenderMathTest`, `SetupAdapterGateTest`, `SetupEntryGateTest`, `HistoryRenderCheckTest` (source-text checks of the history host, the delayed work, the clipboard adapter, the scheduling words, the dispatcher's one-wide limit, the host's store mentions and the entry function; no view is run).
  - screen/: `NodeModelTest`
  - screen/onboarding/: `AccessibilityListTest`, `SetupIntentHandlerTest`, `SetupModelTest`, `SetupScreenTest`, `SetupTextsTest`
  - screen/settings/: `SettingsIntentHandlerSetupTest`, `SettingsIntentHandlerTest`, `SettingsScreenEditTest`, `SettingsScreenTest`
  - screen/history/:
    - `HistoryIntentHandlerTest`: the handler's pages, Delete and Undo windows, close, copy and failed reads, with a fake store, clipboard and scheduler.
    - `AsyncHistoryTest`: the model's serial queue, read generations, tap results, close and reattach, with fake store, clipboard, scheduler and queue.
    - `HistoryNoticeCarryTest`: a dropped read's failed-read notice, carried to the next result's own notice.
    - `HistoryScreenTest`: the drawn tree, from its rows, Undo block, Load more and cap to its notices, colours and texts.
    - `TimestampFormatTest`: the row time for a US and a German locale, and that it is not a fixed pattern.
  - theme/: `PhoneModeTest`, `ThemeControllerFailureTest`, `ThemeControllerTest`, `ThemeTest`
  - write/: `SettingsWriteTest`
  - testing/: `FakeSettingsStoreTest`; helpers without tests: `FakeBreakerSwitch`, `FakeClipboardSink`, `FakeHistoryStore`, `FakeSettingsStore`, `FakeSetupPlatform`
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
- The history screen takes core's HistoryStore port through android_core; the module never imports the history module.
- Consumes shared/ui-tokens — do not hardcode colors in screens.
- The restricted-setting state cannot be read by any public API, so that step only explains: it has no status and no "Open app info" button.
- The texts on the setup screen are drafts for review. The accessibility limits are written from the accessibility decision record and are not word for word the service description.
- Breaker stays off after the user switches it off only if the app does not switch it on again at start.
- "Asked before" for the permission prompts lives in the view only. After a refusal in an earlier session, or after the view is rebuilt, the first tap on the microphone or notifications button may seem to do nothing.
- A notice lasts one drawing and has no live region, so a screen reader is not told when it appears. A focus change right after a tap may clear it at once.
- The history notices and the Undo block carry the same no-live-region limit as the setup notices. No test checks this.
- The named catches in AndroidSetupPlatform (ActivityNotFoundException, SecurityException and RuntimeException) stay on purpose: they name the failures expected, and the adapter check requires the names.
- NOT VERIFIED ON A DEVICE (nothing in this module has run on one):
  - the overlay page deep link, where it lands on the phone's skin, and the vendor's label for that page (it may not read "Display over other apps");
  - both permission dialogs and permanent denial (microphone, and notifications from Android 13);
  - the accessibility list and the order of the restricted-setting steps on Android 13 to 16;
  - the component name form the phone stores in the enabled list (pkg/pkg.Class or pkg/.Class; both are accepted);
  - the status re-check on return from a system page or a dialog, and the scroll position after a redraw (it may land at the top);
  - the switch really starting the service, and isOn() answering true right after On;
  - OFF holding across the next start and after the process is killed (the app's part);
  - the history screen: the 5000 ms Undo window in practice, a rotation carrying out pending deletes once, right after, on the serial dispatcher, the clip marked sensitive on Android 13 and later, the phone's short date and time in each language, and the scroll position after Load more;
  - the store reads and deletes no longer run on the main thread; owed on a device: the real Dispatchers.IO serial queue, the clipboard write and View.postDelayed called from the serial thread, rotation under load;
  - TalkBack, large font and the largest display size;
  - dark mode, including the contrast of the done and not-done words on the surface.
