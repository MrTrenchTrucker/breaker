# auth-client — README

Handles registering and logging in, and keeps the user's token safe in the Android Keystore. That token authenticates sync, training and transcription calls. The password never leaves the phone: the module sends only a value derived from it (see ADR-006). It also runs password changes and completing an admin reset. A session the server issues expires 30 days after its last use (ADR-031); the client keeps no clock for it and learns that a session is over when the server refuses the key. When the phone has no live session, Home shows a quiet notice with a way to sign in (ADR-033, planned, not built).

Planned, not built (ADR-035, ADR-036): signing in on the Breaker website hands the login to the app. The app makes a one-time secret and a key pair for that sign-in, opens the sealed key package it receives, and signs out this device only.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
