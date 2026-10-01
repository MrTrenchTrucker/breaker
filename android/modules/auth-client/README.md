# auth-client — README

Handles registering and logging in, and keeps the user's token safe in the Android Keystore. That token authenticates sync, training and transcription calls. The password never leaves the phone: the module sends only a value derived from it (see ADR-006). It also runs password changes and completing an admin reset.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
