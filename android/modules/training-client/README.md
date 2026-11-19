# training-client — README

Built and unit-tested, not tried on a phone: the recorder, which takes a phrase sample and refuses one that is too short, too long or silent; the store, which keeps samples on the phone and remembers which ones the server confirmed; the typed port to the training server, with a scriptable fake that lives in the tests; the client, which uploads only when training is started, deletes a sample on the phone only after the server confirmed it, polls the job, and returns the downloaded model only if its size, SHA-256 digest and sample count check out (up to 32 MiB; the one combined model is fetched once); delete-all, which removes the phone's copies first and keeps a pending mark if the server could not be reached; and a wrapper that lets core's `PhraseTraining` port reach the client.

Not built: any screen or user-facing word, the real network client, the shared API operations, the server, the install of the downloaded model as a keyword model, and a settings control.

In-app voice phrase training. The user records the wake phrase ("Breaker Breaker") and the send phrase ("And I'm Gone") in their own surroundings. The server trains a small model for that user, and the phone downloads it and checks its checksum (built, as above); the server and the install of the model are not built. Decided, not built (ADR-051): the phone then uses that model for the phrases, and it keeps the default keyword model as the fallback whenever the user model is missing or fails its check.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
