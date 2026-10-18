# ADR-023: A speech engine that prints capitals is fixed in the speech module, not in the formatter

**Status:** accepted
**Date:** 2026-10-12

## Context
The on-device English speech model prints every word in capital letters. The
formatter only capitalises the first letter of a sentence, so nothing in the
app turns that text back into normal sentences, and every dictation arrives
in capitals.

## Decision
The on-device speech module normalises case at the source:

- If a transcript contains letters and none of them is lower case, it is
  lower-cased, and the standalone word "I" and the contractions "I'm", "I'll",
  "I've" and "I'd" get their capital back.
- A transcript that already has any lower-case letter is left untouched.
- The rule applies to the final result and to every update of the live word
  stream, so the live words and the final text agree.
- The module's public interface does not change. The formatter is untouched
  and still adds the capital at the start of each sentence.

## Reasons
Only this one engine has the behaviour, so only this module should know about
it. Putting the rule in the formatter would make it guess, for every engine,
whether capitals are intended.

## Consequences
- Names and acronyms (for example "NASA") come out in lower case. This is a
  known limit; restoring them needs a vocabulary and is out of scope.
- With formatting switched off the text is at least lower case.
- A new engine that prints mixed case needs no change.
