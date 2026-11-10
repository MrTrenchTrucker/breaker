# updater — README

The due decision, the version order, the digest check, the update decision, the check itself (asking the
server for the latest release and saying whether it is newer), the download of the APK (only from the
configured server, verified by its SHA-256 before it lands) and the signature check (an Ed25519 signature over
the version and the digest, against pinned keys) are built. The install, the rollback and the schedule are
not built yet, and nothing in the app calls the signature check yet. The paragraph below is the target design.

Keeps Breaker up to date. Once a day it checks the project's own server for a newer release, and an admin can force a check. It installs only APKs whose signature and SHA-256 check out, and it can roll back. It replaces the base app's updater, which was removed because it pointed elsewhere.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
