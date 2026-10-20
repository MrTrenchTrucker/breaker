# whisper-server — README

Breaker's own transcription service on the server. The phone sends audio to it, it queues each job in order, and it forwards the audio to whichever speech-to-text service the admin has set up (by default an existing Whisper X service). It never runs a speech model itself. Callers get a job id and poll for the result.

None of it is on main yet: the first three slices are on the module branch, and the runnable entry point (slice 4) is planned, not built. See the card.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
