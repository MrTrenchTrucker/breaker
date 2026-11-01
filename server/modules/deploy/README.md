# deploy — README

Wires Breaker's own containers (whisper-server, sync-api, web-fe, training) onto the self-hosted server securely, and ships updates. It covers binding to the VPN interface only, the server's own TLS certificate (ADR-032, decided in principle), API keys kept out of git, APK signing, and a git-pull deploy of tagged releases with rollback.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
