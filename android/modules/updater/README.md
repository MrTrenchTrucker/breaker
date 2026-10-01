# updater — README

Keeps Breaker up to date. Once a day it checks the project's own server for a newer release, and an admin can force a check. It installs only APKs whose signature and SHA-256 check out, and it can roll back. It replaces the base app's updater, which was removed because it pointed elsewhere.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
