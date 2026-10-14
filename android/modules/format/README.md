# format — README

**Status:** in progress by the maintainers' team (issue #15). This branch is the module's main PR; it stays open until the module is complete.

Turns raw dictation into clean, structured text (F8), using the server's language model when it's reachable and a simple rule-based formatter on the phone when it isn't. Formatting never changes the meaning and never adds content (N9).

The rule-based formatter has three deliberate limits (empty and
already-punctuated finals stay as-is, a sentence end right after a digit does
not start a new sentence, and a numbered run must be joined by `", "`); they
are documented in the module card under "Deliberate limits of the rule-based
formatter".

Full module card, including how to run its tests: `AGENTS.md` in this folder.
