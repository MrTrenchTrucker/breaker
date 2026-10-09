# App — README

**Status:** in progress by the maintainers' team (issue #1). This branch is the module's main PR; it stays open until the module is complete.

Android entry point, dependency wiring, Gradle build.

Full module card: `AGENTS.md` in this folder.

## What the app is

The Android application. It owns the one place where the modules are put
together (`BreakerCompositionRoot`), the launcher activity that shows the
settings screen and the history screen, the manifest, and the Gradle build.

## What it puts together

- **Settings and the credential reference.** The settings store is built over
  the app's files folder, together with a small file that holds the reference to
  the user's credential (never the secret itself).
- **History.** The history database opens on its first use, which is the one
  start-up clean-up of old rows. That clean-up runs on the background (I/O)
  dispatcher, never on the main thread. The launcher has a History button, and a
  tap on the notification opens the same screen.
- **Dictation.** A `DictationComponent` joins the microphone capture of the audio
  module, the on-device rule-based formatter of the format module, the
  connectivity probe of the transport module and the dictation use cases of the
  core module. `DictationRunner` drives one dictation at a time: begin, finish,
  send, cancel.
- **Speech engine and model.** The on-device speech engine reads its models from
  a model store in the app's files folder. Two buttons sit on the launcher screen.
  "Download the speech model", downloads the model the settings select; the
  address and the checksum come from the model registry. A tap on the tile's
  microphone with no model installed starts nothing and says so, in a
  notification and in a sentence beside the tile. The recognizer behind the
  engine is the real one, over the engine library the app packages; it has not
  been tried on a device yet.
- **Text commit.** Dictated text is committed through the commit module's
  accessibility service. The app creates that committer once per process, from
  the application context, and hands it to the composition root. The service
  entry comes from the library's manifest. It has not been tried on a device.
- **The floating tile.** While the microphone service is on, the app shows the
  floating tile of the overlay module and decides what each tap does and which
  state the tile shows. It hides the tile when the service goes off. Showing it
  needs the permission to draw over other apps; without it the app posts a
  notification that opens Breaker, and tries to show the tile again the next time
  Breaker comes to the front. After a send, the tile shows SENT when the text
  landed or only reached the clipboard, and FAILED with its sentence when the
  commit failed. It keeps that until the next action.
- **The user's switch-off.** When the user switches dictation off (the
  notification's "Switch off" button), the app keeps that in a small file in its
  files folder, and a start of the launcher from a notification or the tile no
  longer switches dictation on. Opening Breaker from its launcher icon switches
  it on again.

## The microphone service

Android 14 and later do not let an app start a microphone service from the
background, so the app switches the service on ("arms" it) while it is visible,
as described in ADR-022 as amended:

- The launcher activity arms the service every time it becomes visible, unless
  the user switched dictation off; opening it from the launcher icon switches
  dictation on even then. The service can also be started from its own
  notification.
- While it is on, the service shows a quiet, ongoing notification with one
  "Switch off" button. Tapping the notification opens Breaker on the history of
  transcriptions. The launcher shows the history screen that the ui module
  builds.
- A tap on the floating tile only begins a recording inside the service that is
  already running. Sending, cancelling or an error ends the recording; the service
  stays on. The app shows the tile while the service is on and hides it when the
  service ends or the user switches off.
- A shake of the phone, read from the accelerometer, goes through the same check as a tap: it begins a recording only while the tile shows the armed or the sent state, and the sensor runs only while the service is on. Whether a shake does nothing with no tile shown is a device check, not a verified fact (see the Known Gotchas section of `AGENTS.md`).
- The service stops when the user switches dictation off, when the part that owns
  the dictation is closed, or when it is started with nothing switched on. If it
  ends on its own, the dictation under way is dropped.
- If the tile is tapped while the service is off, the app tries once to start it.
  If Android refuses, the answer is the sentence "Open Breaker once to switch
  dictation on."

## Network

Only encrypted (https) connections are allowed. Clear text is switched off in the
manifest and in `res/xml/network_security_config.xml`, which has no exceptions and
trusts only the system's certificate authorities. The manifest asks for exactly
these permissions: internet, record audio, foreground service, foreground service
of the microphone type, post notifications, and draw over other apps (six in all).
None of them is asked for while the app runs in app code: the user grants the
three that need it by hand in the system settings, or through the walk-through
in the settings screen.

## Walk-through and download status

The settings screen shows the setup walk-through (permissions: overlay,
microphone, notifications, accessibility) under the Download button. The
Download button shows a status line (downloading / failed / ready) using
ModelSentences words. The app asks for no permission at run time in app code;
the requests live in ui's screen.

## What is not built yet

Parts that are not merged are filled with a stand-in that answers with a plain
failure sentence, never a success:

- the real microphone driver (the audio module's work), so listening fails with a
  sentence for now;
- the server speech engine (the on-device engine with its recognizer, its model
  store and the download are built; the recognizer has not been tried on a
  device);
- asking the user for the microphone, notification and draw-over-other-apps
  permissions (onboarding), the switch-on screen and the spoken off word.

So the first build cannot produce text yet: the microphone slot cannot be opened
and the text commit fails.

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

Nothing here has been run on a device or an emulator. The list of what is not
verified (the service in the background, the restart from the notification,
whether Android lets the tile start the service when it is off, the tile on screen,
the model download on a phone, the first run through the three permissions, and
more) is in the Known Gotchas section of `AGENTS.md`.

The shake of the phone is not verified on a device either. Its device checks are the DEVICE CHECK LIST in the same section of `AGENTS.md`.
