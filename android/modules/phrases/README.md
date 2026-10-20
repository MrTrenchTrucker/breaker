# phrases - README

Hands-free voice control with two CB-radio phrases. "Breaker Breaker" is reported as a wake, and "And I'm Gone" as a send with the time where the phrase began. The app acts on both: it starts recording (F4) and sends the dictation on (F5); the cut before the phrase (F9) is made in core.

Today the module only finds the phrases. It reads the words a speech recogniser reports, from core's word stream, and tells the app when it hears "breaker breaker" (a wake) and when it hears "and I'm gone" (a send, with the time where that phrase began). It does not open the microphone and does not decide what to do; the app decides. The recogniser adapter and its public factory are in stt-ondevice (`SherpaOnnxWordStreamFactory`, which gives a `FedWordStream`). The app does not wire them to this module yet, so the module cannot run on a phone until it does. The wiring is designed but not built: an app class, `PhraseSendHandler`, will own the detector's start and stop and turn a Send into the end of the take (see the card).

Full module card, including how to run its tests: `AGENTS.md` in this folder.
