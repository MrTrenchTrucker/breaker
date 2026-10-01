# android — README

The phone client for Breaker: a Kotlin, native Android app for Android 11 and later (arm64-v8a first). Its own server is the main path for transcription and formatting, and the phone's on-device engine is the fallback. It is built from a reviewed fork of an open-source Android dictation app, with that fork's four security fixes applied.

**Sub-modules:** app, ui, core, audio, stt-ondevice, stt-server, transport, format, phrases, gesture, overlay, commit, history, settings, sync, auth-client, training-client, updater, crypto — each with its own AGENTS.md + README.md.

Full module card: `AGENTS.md` in this folder.
