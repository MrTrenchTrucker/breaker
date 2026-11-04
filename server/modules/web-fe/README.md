# web-fe — README

The Breaker website, served from its own container on the VPN. Users register and log in, and view, search and copy their transcriptions from any device. Decryption happens in the browser, not on the server. It also hosts the APK for download (no CA certificate to download) and gives admins a panel for users, the transcription service and agent tokens. Breaker serves plain HTTP; TLS is the admin's choice through any reverse proxy (ADR-034).

Planned, not built (ADR-035, ADR-036): a sign-in from the website hands the login to the app, and the sign-in page seals the account's keys to the app's key. Settings will also list the account's devices, with a sign-out for each.

Built now: an AES-GCM layer for the page (wrap and unwrap the data key, seal and open records) on vendored
@noble/ciphers 2.4.0; no page uses it yet.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
