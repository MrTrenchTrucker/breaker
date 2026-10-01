# sync — README

Keeps every transcription in step with the server (F13). Dictation made offline waits in a queue and is sent when the server is reachable, even in local-only mode, and nothing is lost or sent twice (N12).

After each login it also collects agent-token results sealed to the owner
(ADR-018): it fetches them, has the crypto module unseal and re-encrypt each
one, and syncs it like any other transcription.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
