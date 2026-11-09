# updater — README

Only the due decision (`UpdateSchedule.isDue`) and two small pure helpers (`UpdateOrder.isNewer`,
`ApkDigest.matches`) are built; the rest of this module is not built yet, and the paragraph below
is the target design.

Keeps Breaker up to date. Once a day it checks the project's own server for a newer release, and an admin can force a check. It installs only APKs whose signature and SHA-256 check out, and it can roll back. It replaces the base app's updater, which was removed because it pointed elsewhere.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
