# sync-api — README

The server's main API: accounts and logins (users, tokens, roles), syncing transcriptions, and the app's update check. It keeps every user's data separate from everyone else's (N13), and it only ever stores encrypted transcriptions once an account has its keys (ADR-006).

Full module card, including how to run its tests: `AGENTS.md` in this folder.
