# updater — README

The due decision, the version order, the digest check, the update decision and the check itself (asking the
server for the latest release and saying whether it is newer) are built. The download, the signature check,
the install, the rollback and the schedule are not built yet, and the paragraph below is the target design.

Keeps Breaker up to date. Once a day it checks the project's own server for a newer release, and an admin can force a check. It installs only APKs whose signature and SHA-256 check out, and it can roll back. It replaces the base app's updater, which was removed because it pointed elsewhere.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
