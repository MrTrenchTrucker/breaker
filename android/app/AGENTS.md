# AGENTS.md — android/app/

## Purpose
Android entry point, dependency injection wiring, Gradle build.

The app also composes the dictation parts that exist on main and runs the armed microphone service (ADR-022 as amended); where a part is not merged the app passes an implementation that says so (a failure result), never a success.

**Build phase:** Phase 1. Needs first: `core` (on `main`); it wires each other module in as that module lands. This card covers the app scaffold: it wires `settings`, `ui`, `history`, `audio`, `format` and `transport`, and its launcher activity hosts the settings screen.

## Public Interface
app-level composition of all modules. Concretely, the app's own types are:
- `BreakerApp` - the `Application`; builds and holds the composition root and the microphone service controller (`dictationServiceController`), and schedules the start-up purge on the scope `createPurgeScope` returns.
- `BreakerCompositionRoot` - the single place modules are composed; takes a files directory (plain JVM, testable without Android), the history store (as a value, or as a supplier that is called on first use) and the service controller, and exposes the `SettingsStore`, the credential-reference `Keystore`, the `HistoryStore` and the `dictation` component (built on first use).
- `SettingsLauncherActivity` - the launcher activity; hosts the settings view the `ui` module builds, and asks the service controller to switch the microphone service on each time it becomes visible.
- `FileCredentialRefHolder` — a `Keystore` implementation backed by a file, holding the credential *reference* (never a secret).
- `SystemClockAdapter` — the app's `Clock` adapter (the real system clock; plain JVM).
- `AppStartPurge` - schedules the one start-up purge on the scope it is given (the app gives it the I/O-dispatcher scope of `PurgeScope`), off the main thread, reporting a failure to a callback instead of letting it escape.
- The composition root exposes the `HistoryStore` as a `LazyHistoryStore` over the supplier it is handed (the concrete `SqliteHistoryStore` the app builds, opened on first use); a store handed in as a value is exposed as it is.
- `PurgeScope` - `createPurgeScope()`: the app-lifetime scope on the I/O dispatcher with a supervisor job, for the start-up purge.
- Service package `dev.breaker.dictation.service` (plain Kotlin, tested on the JVM; the microphone service of ADR-022 as amended is armed while the app is visible or by its notification action, and the tile tap only begins capture inside the running service):
  - `DictationServiceController` - the one answer to "is the microphone service on" and its start and stop: `arm()` (the app is visible), `coldStart()` (the tile was tapped while it was off; one try, a refusal gives "Open Breaker once to switch dictation on."), `adopt()` (the service marks itself on when started from its notification), `disarm(reason)` (halts once), `serviceEnded()` (marks off without halting, then calls the listener), `setEndedListener(listener)` (tells the owner the service ended), and `isArmed`. It never throws.
  - `DisarmReason` - `USER_WORD` or `OWNER_CLOSED`; `StartResult` - `Started`, `AlreadyRunning` or `NotStarted(sentence)`; `MicPermission` - whether RECORD_AUDIO is granted; `ServiceLauncher` (with `LaunchResult`) - starts and halts the real service.
  - `ServiceAction` with `ACTION_ARM`, `ACTION_DISARM` and `serviceActionOf` - the exact-match reading of a start request's action; `ServiceStartDecisions` (with `StartDecision`) - the pure rule for what the service does with each request.
  - `ServiceStartHandler` and `ServiceHost` - what the service does with each start request; the first call is always the foreground, then the request is read. `ServiceHost` holds the three platform calls.
  - `ReportingMicSource` - wraps the microphone source and reports once when a take ends by itself; it releases a failed device itself, and stays silent after `markStopRequested`; `rearm` starts the next take.
  - `NotificationRoute` - the names of the route a tap on the ongoing notification carries (`EXTRA_ROUTE`, `ROUTE_HISTORY`) and the launcher activity it opens (`TARGET_ACTIVITY`).
  - `ServiceSentences` - the plain sentences the controller answers with.
- Android adapters in the service package (thin, one platform call per member, checked by text gates): `DictationForegroundService` (the microphone foreground service), `AndroidServiceLauncher`, `AndroidMicPermission`, and `DictationNotification` (module-internal; the quiet ongoing notification with one "Switch off" action; its words are in `strings.xml`).
- Wiring package `dev.breaker.dictation.wiring`:
  - `DictationComponent` - builds the capture, the two core use cases and the runner from their ports; `close()` drops the dictation, switches the service off and is idempotent; `bindTo(scope)` closes it when the scope ends.
  - `DictationRunner` - drives one dictation at a time: `begin()` (`BeginResult`), `finish()` (`FinishResult`), `send()`, `cancel()`, `onCaptureEnded()`, `onServiceEnded()`, `disarm()`. It stops capture, never the service, except `disarm()`. `RunnerSentences` holds its plain sentences.
  - `LazyHistoryStore` - a `HistoryStore` that builds the real one on first use; a supplier that throws is not remembered.
  - `UnavailableSttEngine`, `UnavailableTextCommitter`, `UnavailableMicSource` - the honest-failure slots for the engines, the text commit and the microphone that are not merged (with `LOCAL_UNAVAILABLE_DETAIL`, `SERVER_UNAVAILABLE_DETAIL`, `COMMIT_UNAVAILABLE_DETAIL`, `MIC_UNAVAILABLE_MESSAGE`); each answers with a failure, never a success.
  - `UuidIdSource` - the `IdSource` over random UUIDs.

## Owns
app entry, DI container (composition root), Gradle build, and the file-backed credential-reference holder (a `Keystore` impl that holds the reference, not the secret), the foreground microphone service and its notification, the https-only network configuration, the manifest, and the honest-failure slots for the parts that are not merged.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- android_ui (registered in modules.toml)
- android_settings (registered in modules.toml)
- android_history (registered in modules.toml)
- android_audio (registered in modules.toml)
- android_format (registered in modules.toml)
- android_transport (registered in modules.toml)

## Invariants
app launches to the settings screen; DI wiring composes all modules.

1. The microphone service stops on a defined set of paths and no other. The user's off word and the owner closing switch it off (`disarm`); the service ending on its own (a kill, a refusal inside the service, the notification's "Switch off") marks it off and drops the dictation under way. Capture stops on send, cancel, error and capture-ended, and the service stays on. A service started with nothing on, or with no valid switch-on action, stops at once. Tested by: `ServiceControllerTest`, `ServiceControllerOrderTest`, `ServiceStartDecisionTest`, `ServiceStartHandlerTest`, `DictationRunnerTest`, `DictationRunnerEndTest`, `DictationComponentTest`, `DictationComponentServiceTest`.
2. The foreground call is the first thing done on every start request, even one that is about to stop (the platform ends an app whose started foreground service never reaches the foreground). A refusal there ends the service quietly and marks the switch off; nothing is thrown out of the service. Tested by: `ServiceStartHandlerTest`, `ServiceShapeGateTest`, `ServiceAdapterGateTest`.
3. The app declares exactly the permissions INTERNET, RECORD_AUDIO, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE and POST_NOTIFICATIONS, and the one private service of the microphone type with no intent filter. Clear-text traffic is off everywhere with no exceptions: the network configuration has one base config, trusts the system store only, and has no host entry, no override, no user certificate and no pinned key. Tested by: `ManifestGateTest`, `NetworkConfigGateTest`, `ServiceAdapterGateTest`.
4. A part that is not merged is passed as an implementation that answers with a failure, never a success. The server formatter slot holds the on-device rule-based formatter, and the server path is not reached while the server engine slot fails. Tested by: `UnavailableSlotsTest`, `DictationFlowTest`, `RootSlotsGateTest`, `DictationComponentTest`, `DictationRunnerTest`.
5. A microphone that fails is released by the app's own wrapper and then reported once; a stop the app asked for is never reported as an end by itself; the stop the capture thread cannot pay is owed and paid on the caller's thread by the next begin, cancel, disarm or service end. Tested by: `ReportingMicSourceTest`, `DictationRunnerEndTest`, `DictationComponentTest`.
6. The app asks for no permission at run time (onboarding does), and the notification words live in one place (`strings.xml`, with `ServiceSentences` for the controller's sentences). Tested by: `NotificationTextGateTest`, `PlatformAdapterGateTest`; the no-run-time-request part has no test of its own and holds because no main file asks.
7. The history database is opened on first use, never by reading the settings or the keystore. Tested by: `LazyHistoryStoreTest`, `LazyHistoryRootTest`.
8. The notification is quiet and ongoing, and a tap on it opens the launcher activity carrying the history route. Tested by: `NotificationContentGateTest`, `NotificationRouteTest`, `PlatformAdapterGateTest`.

## Does Not Own
- Screen implementations (ui)
- Domain logic (core)
- The secret-resolving device Keystore (not built and not verified; the app's `FileCredentialRefHolder` holds only the reference)
- The real microphone driver (the audio module's; the app's microphone slot answers with a failure until a driver is passed in)
- Model loading and the speech engines
- The overlay (tile) and the gesture that begins and ends a take
- Accessibility text commit
- The permission requests and the onboarding that asks for them
- The history screen and the switch-on screen

## Test Locations
- Unit (Kotlin): `android/app/src/test/kotlin/` — `CompositionRootTest` (JVM, no Android): proves the composition root is wired file-to-file (a `save` on one instance is visible to a second over the same directory; the exposed `Keystore` carries the reference a `save` wrote; the reference survives a second `FileCredentialRefHolder` over the same file). Run: `./gradlew :android:app:test`
- Service tests (JVM, no Android): `android/app/src/test/kotlin/dev/breaker/dictation/service/` - `ServiceControllerTest`, `ServiceControllerOrderTest`, `ServiceControllerRaceTest` (the controller re-entered during its permission check or its halt), `ServiceStartDecisionTest`, `ServiceStartHandlerTest`, `ReportingMicSourceTest`, `ReportingMicSourceOffsetTest` (the read buffer, offset and length pass through unchanged), `ServiceSentencesTest` (the exact words of the controller's three sentences), `NotificationRouteTest`; text gates over the Android adapters and the notification: `ServiceAdapterGateTest`, `ServiceShapeGateTest`, `PlatformAdapterGateTest`, `NotificationContentGateTest`, `ServiceFilesGateTest` (the service is not bound, it reports its end before destroy, the notification id is positive, the launcher addresses only the service). Their shared fakes are in `ServiceFakes.kt`.
- Wiring tests (JVM, no Android): `android/app/src/test/kotlin/dev/breaker/dictation/wiring/` - `DictationRunnerTest`, `DictationRunnerEndTest`, `DictationRunnerDisarmTest` (the capture and session are dropped before the service is halted), `DictationRunnerFinishTest` (a finish that fails still stops the audio exactly once and drops the dictation), `DictationComponentTest`, `DictationComponentServiceTest`, `DictationFlowTest`, `LazyHistoryStoreTest`, `LazyHistoryRootTest`, `PurgeScopeTest`, `UnavailableSlotsTest`. Their shared fakes are in `WiringFakes.kt` and `WiringMicFakes.kt`.
- Gate tests over the app's own files: `android/app/src/test/kotlin/dev/breaker/dictation/gates/` - `ManifestGateTest`, `NetworkConfigGateTest`, `NotificationTextGateTest`, `RootSlotsGateTest`, `AppLayoutGateTest`, `AppWiringGateTest` (the wiring in the application class, the launcher activity and the composition root), `BuildFileGateTest` (the build file's application id, platform levels and dependency list), `AppPrivacyScanTest` (helpers `AppSourceFiles` and `AppXml.kt`). Each gate runs against the real files and fires on samples: most samples are the real text with one edit, some are small sources written by hand, and an edit whose target text is missing fails by name.
- Top level: `AppStartPurgeTest`, `FileCredentialRefHolderTest`, `SystemClockAdapterTest`.
- Contract: `tests/contract/test_app_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_app_contract.py`
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
- Phase 1 deliverable — DI wiring must be the ONLY place modules are composed.
- **NOT VERIFIED on a device or emulator.** What is checked for this scaffold is
  compile (`assembleDebug` plus the unit test) and the JVM tests of the
  composition root. The launcher activity and the real settings view are **not
  exercised** — the app has not been launched on a device or an emulator.
- **Start-up purge is one-shot and only logged.** `BreakerApp` schedules
  `SqliteHistoryStore.purgeExpired()` once on an app-lifetime coroutine scope
  on the I/O dispatcher (`createPurgeScope`), off the main thread. A failure is passed to a callback that logs it
  (`Log.w`); it is not retried and does not crash the app. Purge scheduling
  beyond app start is not built.
- `FileCredentialRefHolder` holds a credential *reference*, never a secret. The
  platform `Keystore` that would resolve the reference against a secret is **not
  built and not verified**; nothing in this module is a claim that a key is kept
  safe on the device.
- **NOT VERIFIED on a device** (the microphone service and its arming, ADR-022 as amended): that the armed service survives the app going to the background and records on a tile tap while another app is in front; that the notification action restarts it after a kill; whether Android 14 and later let the tile start a microphone service when it is not armed (the platform documentation lists no overlay window as an exemption, so the app arms the service while it is visible and does not rely on the overlay); the exact time allowed between `startForegroundService` and `startForeground`; the quiet notification when POST_NOTIFICATIONS is denied; the microphone privacy indicator.
- A refusal that happens inside the service (Android 14 and later may refuse the microphone type only there) is not shown to the user as a sentence in this change: the dictation is dropped and the switch reads off. The screen module decides what to show.
- The capture loop of the audio module ends a take by itself when the device fails, or after a long run of empty reads, and does not close the source. A failed read is handled by `ReportingMicSource`, which releases the device and reports the end once; it relies on the source returning a negative value when it fails. A long run of empty reads is not seen: the stop stays owed until the next begin, cancel, disarm or service end. The future real microphone driver must return a negative code on failure, and its `close()` must be safe to call twice and from the capture thread.
- The real `AudioRecord` microphone source does not exist yet: the microphone slot is `UnavailableMicSource`, so listening fails with a plain sentence. Model loading, the overlay and the accessibility text commit are not built either; the engine and commit slots fail with their sentences.
- The activity switches the service on every time it becomes visible, and nothing remembers a user's "Switch off".
- The draft notification text says "Tap the tile to start dictating." while tapping the notification itself opens the history; the words of the notification (and of `ServiceSentences` and `RunnerSentences`) are drafts that await final wording.
- With POST_NOTIFICATIONS denied the drawer shows nothing, so "Switch off" is unreachable except through the system settings.
- A late destroy of an old service instance can mark the next one off and drop its capture (the service has no instance token).
- Nothing in the app reads `compositionRoot.dictation` yet, so the ended listener is registered only when the screen module first uses the component.
