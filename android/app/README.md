# App — README

**Status:** in progress by the maintainers' team (issue #1). This branch is the module's main PR; it stays open until the module is complete.

Android entry point, dependency wiring, Gradle build.

Full module card: `AGENTS.md` in this folder.

## What the app is

The Android application. It owns the one place where the modules are put
together (`BreakerCompositionRoot`), the launcher activity that shows the
settings screen, the manifest, and the Gradle build.

## What it puts together

- **Settings and the credential reference.** The settings store is built over
  the app's files folder, together with a small file that holds the reference to
  the user's credential (never the secret itself).
- **History.** The history database is opened the first time the history is used,
  not when the app starts or when the settings are read. The one start-up clean-up
  of old rows runs on the background (I/O) dispatcher.
- **Dictation.** A `DictationComponent` joins the microphone capture of the audio
  module, the on-device rule-based formatter of the format module, the
  connectivity probe of the transport module and the dictation use cases of the
  core module. `DictationRunner` drives one dictation at a time: begin, finish,
  send, cancel.

## The microphone service

Android 14 and later do not let an app start a microphone service from the
background, so the app switches the service on ("arms" it) while it is visible,
as described in ADR-022 as amended:

- The launcher activity arms the service every time it becomes visible. The
  service can also be started from its own notification.
- While it is on, the service shows a quiet, ongoing notification with one
  "Switch off" button. Tapping the notification opens Breaker on the history of
  transcriptions (the screen itself belongs to the ui module).
- A tap on the floating tile only begins a recording inside the service that is
  already running. Sending, cancelling or an error ends the recording; the service
  stays on.
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
of the microphone type, and post notifications.

## What is not built yet

Parts that are not merged are filled with a stand-in that answers with a plain
failure sentence, never a success:

- the real microphone driver (the audio module's work), so listening fails with a
  sentence for now;
- on-device and server speech engines, and putting the text into another app's
  field;
- asking the user for the microphone and notification permissions (onboarding),
  the switch-on screen, the spoken off word and the history screen.

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
whether Android lets the tile start the service when it is off, and more) is in
the Known Gotchas section of `AGENTS.md`.
