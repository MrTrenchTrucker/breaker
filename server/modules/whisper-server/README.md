# whisper-server — README

Breaker's own transcription service on the server. The phone sends audio to it, it queues each job in order, and it forwards the audio to whichever speech-to-text service the admin has set up (by default an existing Whisper X service). It never runs a speech model itself. Callers get a job id and poll for the result.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
