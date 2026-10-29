# deploy — README

Wires Breaker's own containers (whisper-server, sync-api, web-fe, training) onto the self-hosted server securely, and ships updates. It covers binding to the VPN interface only, TLS certificates, API keys kept out of git, APK signing, a git-pull deploy of tagged releases, and a rollback that publishes the older source as a new release with a higher versionCode and a higher version name.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
