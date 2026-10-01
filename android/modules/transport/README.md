# transport — README

**Status:** in progress by the maintainers' team (issue #11). This branch is the module's main PR; it stays open until the module is complete.

Decides, for each dictation, which speech-to-text backend to use. The server is tried first, and the phone's own engine is the fallback when the server can't be reached. Audio is never dropped: it waits in a queue if needed. It never silently falls through to a cloud service; that was a security fix in the base app.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
