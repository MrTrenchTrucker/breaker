# format — README

**Status:** built and tested; the app wires only the rule-based formatter today (see below).

Turns raw dictation into clean, structured text (F8). It has two formatters, one that asks the server's language model and a simple rule-based one that runs on the phone. Both are built and tested, but the app wires only the rule-based one today, for every dictation. Formatting never changes the meaning and never adds content (N9).

The rule-based formatter has three deliberate limits (empty and
already-punctuated finals stay as-is, a sentence end right after a digit does
not start a new sentence, and a numbered run must be joined by `", "`); they
are documented in the module card under "Deliberate limits of the rule-based
formatter".

Full module card, including how to run its tests: `AGENTS.md` in this folder.
