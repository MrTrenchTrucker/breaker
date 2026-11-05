# training — README

Nothing in this module is built yet: it holds only its card, this README and a build file, and the paragraph below is the target design.

The per-user voice phrase training service. The phone uploads the user's recorded samples, and this service trains a small wake/send phrase model for that user. It publishes the model with a checksum that the phone verifies before installing it. It runs on the CPU by default, and can use a GPU through Local Inference if the model needs it.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
