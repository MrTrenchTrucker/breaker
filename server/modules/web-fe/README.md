# web-fe — README

The Breaker website, served from its own container on the VPN. Users register and log in, and view, search and copy their transcriptions from any device. Decryption happens in the browser, not on the server. It also hosts the APK for download (no CA certificate to download) and gives admins a panel for users, the transcription service and agent tokens. Breaker serves plain HTTP; TLS is the admin's choice through any reverse proxy (ADR-034).

Full module card, including how to run its tests: `AGENTS.md` in this folder.
