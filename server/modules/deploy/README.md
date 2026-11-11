# deploy — README

Four helper scripts are built (`scripts/health-check.sh`, `scripts/release-tags.sh`, `scripts/backup-data.py` and `scripts/vpn-bind.sh`, a check that changes nothing, with tests); nothing else in this module is built yet, and the paragraph below is the target design.

Wires Breaker's own containers (whisper-server, sync-api, web-fe, training) onto the self-hosted server securely, and ships updates. It covers binding to the VPN interface only, plain HTTP on Local Server, API keys kept out of git, APK signing, and a git-pull deploy of tagged releases with rollback. No certificate handling in Breaker; TLS is the admin's choice through any reverse proxy (ADR-034).

Full module card, including how to run its tests: `AGENTS.md` in this folder.
