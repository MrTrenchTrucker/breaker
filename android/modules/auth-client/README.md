# auth-client — README

Handles registering and logging in, and is to keep the user's token safe in the Android Keystore (decided and not built). That token authenticates sync, training and transcription calls. The password never leaves the phone: the module sends only a value derived from it (see ADR-006). It also runs password changes and completing an admin reset. A session the server issues expires 30 days after its last use (ADR-031); the client keeps no clock for it and learns that a session is over when the server refuses the key. When the phone has no live session, Home shows a quiet notice with a way to sign in (ADR-033, planned, not built).

Built today: the wire codec only (the strict JSON reader, the request encoder for register, salt and login, the response decoder for those three answers, the KDF guard). The calls themselves, the password change, the reset completion and the token storage above are planned, not built.

Planned, not built (ADR-035, ADR-036): signing in on the Breaker website hands the login to the app. The app makes a one-time secret and a key pair for that sign-in, opens the sealed key package it receives, and signs out this device only.

Built outside this module (core, crypto, ui, app): the start of that sign-in: the one-time secret and key pair, the link to the website, the return link `breaker://handoff`, the start screen and the return activity. Planned, not built: the receiver that trades the returned code for the session and the sealed key package, and the storage of the result.

logout has no body to map and no code is written for it.
Full module card, including how to run its tests: `AGENTS.md` in this folder.
