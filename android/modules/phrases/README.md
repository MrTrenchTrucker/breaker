# phrases — README

Hands-free voice control with two CB-radio phrases. "Breaker Breaker" wakes the app and starts recording (F4). "And I'm Gone" stops recording, cuts the phrase out of the audio so it never shows up in the text (F9), and sends the dictation on (F5).

**Status:** in progress by the maintainers' team (issue #16). This branch is the module's main PR; it stays open until the module is complete.

Today the module only finds the phrases. It reads the words a speech recogniser reports, from core's word stream, and tells the app when it hears "breaker breaker" (a wake) and when it hears "and I'm gone" (a send, with the time where that phrase began). It does not open the microphone and does not decide what to do; the app decides. The recogniser adapter that feeds the word stream is not built yet, so the module cannot run on a phone until it is.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
