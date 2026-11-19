# training-client — README

Nothing in this module is built yet: it holds only its card, this README and a build file, and the text below is the target design.

In-app voice phrase training. The user records the wake phrase ("Breaker Breaker") and the send phrase ("And I'm Gone") in their own surroundings. The server trains a small model for that user, and the phone downloads it, checks its checksum and installs it. Decided, not built (ADR-051): the phone then uses that model for the phrases, and it keeps the default keyword model as the fallback whenever the user model is missing or fails its check.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
