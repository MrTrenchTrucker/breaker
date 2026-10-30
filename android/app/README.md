# App — README

**Status:** in progress by the maintainers' team (issue #1). This branch is the module's main PR; it stays open until the module is complete. Home level sink lifecycle: `SettingsLauncherActivity` keeps the sink that `addHomeLevelSink` returns and removes it in `onDestroy` through `TileHost.removeHomeLevelSink`, so a rotation does not leave a feed holding the old Activity; `CompositeLevelSink` is safe to add to and remove from while publish runs on the audio thread (CopyOnWriteArrayList).

Android entry point, dependency wiring, Gradle build.

Full module card: `AGENTS.md` in this folder.

## What the app is

The Android application. It owns the one place where the modules are put
together (`BreakerCompositionRoot`), the launcher activity that shows the home
screen, the settings screen and the history screen, the manifest, and the Gradle build.

## What it puts together

- **The home screen.** The launcher opens on the Home screen the ui module builds, after the first-run view on a phone with no speech model: an on/off card with the switch, the speech model row (Ready, or "Not downloaded"), the server row ("Not available") and the last dictation; a big mic; the last transcript with a Copy button, which is drawn only while there is a transcript; and a menu with History, Settings, How it works and About. History and Settings open their screens. How it works and About open their own screens. The big mic is the overlay module's `ScreenMic` (ADR-026), which ui places in its slot and the app composes (`wiring/HomeScreenMic.kt`, `HomeTakeWiring.createScreenMic`, `host/ActivityHelpers.createHomeMic`). A tap starts a take (or answers why it cannot); while it records, X cancels (nothing saved, the mic released) and the check or a second tap finishes it (saved to History, copied to the clipboard); begin, cancel and finish run on the one take worker the tile also uses, never the main thread. The take's line shows below the last transcript. One level source feeds the tile and the Home mic (`TileHost`'s composite level sink). OPEN from the APK 8 phone test: the X and check are not tappable while recording (blocker), and the Home mic does not yet show the tile's mic-busy face or sending look, nor the new transcript as soon as the take settles (`AGENTS.md`, Known Gotchas). Apart from the first-run view, screens open only from this menu, and the two notification routes are the only other entries: the model route opens Settings and asks for its download button to be in focus (a device check is owed), and the history route opens History. On Home, Back closes an open menu first. Back from How it works, About or Settings returns to Home; Back from History returns to Settings when Settings is open, and to Home otherwise. `SettingsLauncherActivity.kt` sits at the 300-line cap for every Kotlin file of this module.
- **The window colour.** `paintWindowBackground` (`wiring/AndroidInsets.kt`) paints the window from the palette: painted when the activity is created; a theme switched inside Settings is not repainted until the app is reopened. When the settings fail to load, the window falls back to the default theme instead of crashing the launch.
- **Settings and the credential reference.** The settings store is built over
  the app's files folder (`settings.properties`), together with a small file that holds the reference to
  the user's credential (never the secret itself).
- **History.** The history database (app-private, `breaker_history.db`) opens on its first use, which is the one
  start-up clean-up of old rows. That clean-up runs on the background (I/O)
  dispatcher, never on the main thread. The clean-up guards against a forward clock jump: it purges strictly when the phone keeps its clock automatically, and when automatic time is off and the last purge is more than 30 days old it skips the purge and reports clock skew for at most seven days, then purges as normal. It guards against a forward jump of more than 30 days while automatic time is off; a smaller jump can purge rows up to that many days early. The Home menu opens the history screen, and a tap
  on the notification opens the same screen.
- **Dictation.** A `DictationComponent` joins the microphone capture of the audio
  module, the on-device rule-based formatter of the format module, the
  connectivity probe of the transport module and the dictation use cases of the
  core module. `DictationRunner` drives one dictation at a time: begin, finish,
  send, cancel.
- **First run.** A phone with no speech model sees a first-run view (a welcome, the permission pages and a download page with a bar) in place of the home screen and the settings column. The app keeps a small done marker in its files folder, written when the model is on the phone at the end of the first run, so the first run does not come back.
- **Speech engine and model.** The on-device speech engine reads its models from
  a model store in the app's files folder. Its download button sits in the settings view, in the settings column that the Settings item on Home opens.
  "Download the speech model", downloads the model the settings select; the
  address and the checksum come from the model registry. A tap on the tile's
  microphone with no model installed starts nothing and says so, in a
  notification and in a sentence beside the tile. The recognizer behind the
  engine is the real one, over the engine library the app packages; it has run on a
  phone in test builds.
- **Text commit.** Dictated text is committed through the commit module's
  accessibility service. The app creates that committer once per process, from
  the application context, and hands it to the composition root. The service
  entry comes from the library's manifest. It has run on a phone in test builds.
- **The floating tile.** While the microphone service is on, the app shows the
  floating tile of the overlay module and decides what each tap does and which
  state the tile shows. It hides the tile when the service goes off. Showing it
  needs the permission to draw over other apps; without it the app posts a
  notification that opens Breaker, and tries to show the tile again the next time
  Breaker comes to the front. After a send, the tile shows SENT when the text
  landed or only reached the clipboard, and FAILED with its sentence when the
  commit failed. It keeps that until the next action.
- **The keyboard tile.** While dictation is switched on, the tile comes up when a keyboard is on
  screen and a text field is in focus. It goes away when the keyboard goes away, but only if a
  keyboard show brought it up. A keyboard show follows the same check as a shake: nothing is shown
  while the switch is off or while a take is in flight. A keyboard-gone hide that comes during a
  take is held and applied once when the take ends, and only if the keyboard is still gone. A
  keyboard show, a switch-off or a user close drops a held hide. A user close also forgets that the
  keyboard raised the tile, so a keyboard that goes away later does not hide a tile the user closed or
  brought back with a shake, unless a keyboard show has raised it since. If the user closes the tile
  while the keyboard is up, the tile stays down until the keyboard goes away and comes back; a close made
  while no keyboard is up does not stop the next keyboard show from bringing the tile back.
- **The user's switch-off.** When the user switches dictation off (the
  notification's "Switch off" button, or the switch on the Home card), the app keeps that in a small file in its
  files folder, and a start of the launcher from a notification or the tile no
  longer switches dictation on. Opening Breaker from its launcher icon switches
  it on again, once for each activity the icon opens; a tap on the icon while
  Breaker is still open does not switch it on.

**Debug builds only.** The debug source set (`android/app/src/debug/`) holds the Home mic probe: an exported broadcast receiver for the action `dev.breaker.debug.MIC` and a not-exported start provider, which push a mic state or trigger a Home redraw through the overlay's public `ScreenMicProbe` interface and log under `BreakerMicProbe`; they never start a take. The release build holds none of it, and the diagnostic parts in main (the `ScreenMicProbe` interface, the log sink and the touch-down and route log lines in `ScreenMicProbeKit`, the `HomeHostView` probe log lines) are removed before release. The host click decision in `ScreenMicProbeKit` is production code and stays.

## The microphone service

Android 14 and later do not let an app start a microphone service from the
background, so the app switches the service on ("arms" it) while it is visible,
as described in ADR-022 as amended:

- The launcher activity arms the service every time it becomes visible, unless
  the user switched dictation off; opening it from the launcher icon switches
  dictation on even then, once for each activity the icon opens; a tap on the
  icon while Breaker is still open does not. The service can also be started
  from its own notification.
- While it is on, the service shows a quiet, ongoing notification with one
  "Switch off" button. Tapping the notification opens Breaker on the history of
  transcriptions. The launcher shows the history screen that the ui module
  builds.
- A tap on the floating tile only begins a recording inside the service that is
  already running. Sending, cancelling, an error or a second tap on the Home big mic (for a take the Home tap began) ends the recording; the service
  stays on. The app shows the tile while the service is on and hides it when the
  service ends or the user switches off.
- A shake of the phone, read from the accelerometer, never begins a recording. It goes to the tile: with the tile closed or hidden it brings the tile back, and with the tile on screen or during a recording it does nothing. The sensor runs only while the service is on. Whether a shake brings the tile back with no overlay permission is a device check, not a verified fact (see the Known Gotchas section of `AGENTS.md`).
- The service stops when the user switches dictation off, when the part that owns
  the dictation is closed, or when it is started with nothing switched on. If it
  ends on its own, the dictation under way is dropped.
- If the tile is tapped while the service is off, the app tries once to start it; a tap on the Home big mic does the same when a model is ready, unless the user switched dictation off, and then it shows the off sentence.
  If Android refuses, the answer is the sentence "Open Breaker once to switch
  dictation on."

## Network

Only encrypted (https) connections are allowed. Clear text is switched off in the
manifest and in `res/xml/network_security_config.xml`, which has no exceptions and
trusts only the system's certificate authorities. The manifest asks for exactly
these permissions: internet, record audio, foreground service, foreground service
of the microphone type, post notifications, and draw over other apps (six in all).
None of them is asked for while the app runs in app code: the user grants the
three that need it by hand in the system settings, or through the first-run flow
on a phone with no speech model.

## Settings and download status

The settings column, which the Settings item on Home opens, holds only the settings view
(`createSettingsView` in the ui module). Its model row holds the download button while the model is not on the phone; under that row,
a status line (downloading or failed) uses ModelSentences
words, and is blank once the model is ready (there is no ready sentence). The app asks for no permission at run time in app code;
the requests live in ui's screen.

## What is not built yet

These are the main parts still not built in the app; the Known Gotchas section
of `AGENTS.md` lists the smaller open items.

- The server speech engine. Its slot holds a stand-in that answers with a plain
  failure sentence and never a success: "Server transcription is not available
  yet." (`SERVER_UNAVAILABLE_DETAIL` in `wiring/UnavailableSlots.kt`, wired at
  `BreakerCompositionRoot.kt` `serverEngine = UnavailableSttEngine(`). The
  on-device engine with its recognizer, its model store and the download are
  built; the recognizer has run on a phone in test builds.
- The spoken phrases. The phrases module detects the wake and send phrases in
  heard words, but the app does not use it yet, and no spoken off word is built.
  PLANNED: a `PhraseSendHandler` in the wiring owns the spoken Send (ADR-004); it
  needs an app edge to the phrases module that is not in the registry yet.
- The secret-resolving device Keystore. The app keeps only the reference to the
  credential (`FileCredentialRefHolder`), never a secret; the Keystore that
  resolves the reference is not built and not verified.

The real microphone (`appMicSource(audioManager)` in `wiring/AndroidSwaps.kt`,
which returns `AndroidMicSource.create`), the first-run view (`createFirstRunView` in the ui module),
and the on/off switch on Home (with the switch in
`wiring/BreakerSwitchAdapter.kt`) and the text commit (`appTextCommitter` in
`wiring/AndroidSwaps.kt`) are built. All three have run on a phone in test builds;
the cases not yet checked on a device are in the "Not verified" section of this
README and the Known Gotchas section of `AGENTS.md`.

The server formatter slot holds the on-device rule-based formatter, and the
server path is not reached while the server engine slot fails.

## Running the tests

The tests run on a plain JVM, with no Android classes:

    ./gradlew :android:app:test

The contract test for the module:

    python3 -m unittest discover -s tests/contract -t tests/contract -p test_app_contract.py

Check that a run reports more than 0 tests; a mistyped path runs nothing and
still prints OK.

## Not verified

Test builds have run on one phone: the tile on screen, the model download, the
permission walk-through, the level bar and a take transcribed on the phone. Most
edge cases have not been checked on a device. Every change to layout, Views,
redraws or the activity lifecycle now runs on an Android 16 emulator (upgrade and
fresh install, five window sizes, rotation, background and dark mode) before an
APK goes out; three launch crashes found there are fixed in main.

NOT REPRODUCED: the project owner saw history and the speech model gone after an
update. Model, settings and history are all app-private, so an update keeps
them and an uninstall wipes them. An in-place 6 to 8 update keeps them (emulator
test); APKs 1-5 had different signing keys, so 5 to 6 needed an uninstall. The project owner ruled that an uninstall
removes everything (ADR-028); the manifest change that stops backup restoring data is BUILT: the manifest sets
`allowBackup="false"` and two rules files exclude every domain. The
list of what is not verified (the service in the background, the restart from the
notification, whether Android lets the tile start the service when it is off, and
more) is in the Known Gotchas section of `AGENTS.md`.

The first-run view has not run on a phone; the JVM tests and the source-text rules check it only.

A shake has been seen on a phone in an earlier test build; the current rule (a shake only shows the tile) has not been checked on a device. Its device checks are the DEVICE CHECK LIST in the same section of `AGENTS.md`.
