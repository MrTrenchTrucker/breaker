# deploy — README

Eight scripts are built (`scripts/health-check.sh`, `scripts/release-tags.sh`, `scripts/backup-data.py`, `scripts/vpn-bind.sh`, `scripts/git-pull.sh`, `scripts/install.sh`, `scripts/update.sh`, `scripts/rollback.sh`) with their shared helpers `scripts/deploy-common.sh` and the schema-record helper, and tests; the three image recipes, the one compose file, the env example and the secrets gate are built; the build, sign and serve steps, the APK signing, the release manifest writer and the key rotation are not, and the paragraph below is the target design.

Wires Breaker's own containers (whisper-server, sync-api, web-fe, training) onto the self-hosted server securely, and ships updates. It covers binding to the VPN interface only, plain HTTP on Local Server, API keys kept out of git, APK signing, and a git-pull deploy of tagged releases with rollback. No certificate handling in Breaker; TLS is the admin's choice through any reverse proxy (ADR-034). Planned (approved 2026-11-18, not built): the install script prints the first-admin install code once, at the end of install (ADR-043).

Full module card, including how to run its tests: `AGENTS.md` in this folder.
