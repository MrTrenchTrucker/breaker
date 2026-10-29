# updater - README

Keeps Breaker up to date. Once a day it checks the project's own server for a newer release, and an admin can force a check. A release is offered only when its manifest comes from the update server and its signature checks out against a pinned key. An install takes only the release that the last verified check offered; it stages the APK and installs it only when its SHA-256 digest matches, its size is within the limit, and the file is not empty. There is no rollback: a refused or failed install leaves the running app as it was. It replaces the base app's updater, which was removed because it pointed elsewhere.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
