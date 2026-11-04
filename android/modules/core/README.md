# Core — README

The pure-Kotlin domain core of the Android app: everything the app decides,
with nothing platform-specific in it.

What ships:
- `model/` — the values the domain passes around: transcriptions and the dictation
  session's state machine, settings, speech-to-text requests and results, phrase
  models and the events a phrase detector reports, cipher text and keys, account
  sessions, update releases. The values that hold dictated text print their ids and
  lengths, never the text.
- `port/` — the interfaces feature modules implement: speech engines, the audio
  source, the text committer, stores, the connectivity probe, crypto, sync and
  updates. The full list, with signatures, is on the module card.
- `usecase/` — `DictateUseCase` (audio to formatted text, routing between the
  on-device and server engines), `SendUseCase` (put the text where the user is
  typing, and always keep a copy in history) and `LocalModeEgress` (the rule that
  keeps a phone transcript away from cloud formatting).

Two rules shape the module. It has no Android imports and no I/O, so it runs under
plain JUnit. And a dictation that runs on the phone never falls through to a server
or a cloud formatter.

The sign-in start is the core half of signing in from the website. It keeps one sign-in
attempt in memory at a time: a secret text, its challenge and an X25519 key pair. It builds
the sign-in link, reads the return link, and passes a valid return code to a receiver with
the attempt. The attempt lives in memory only (no Keystore, no file, no settings, no log), and
core stores, logs and sends none of it. A Kotlin String cannot be wiped, so the secret is kept
as bytes and read only through a copy; the exchange request, built later, is to make its String
at the moment of the call and nowhere else.

Run the tests with `./gradlew :android:modules:core:test`.

Full module card: `AGENTS.md` in this folder.
