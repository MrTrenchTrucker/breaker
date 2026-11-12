# web-fe — README

The Breaker website, served from its own container on the VPN. Built today: the sign-in page and its scripts, with the AES-GCM layer; the rest of this paragraph is planned, not built. Users register and log in, and view, search and copy their transcriptions from any device. Decryption happens in the browser, not on the server. It also hosts the APK for download (no CA certificate to download) and gives admins a panel for users, the transcription service and agent tokens. Breaker serves plain HTTP; TLS is the admin's choice through any reverse proxy (ADR-034).

Planned (ADR-035, ADR-036), partly built: a sign-in from the website hands the login to the app, and the sign-in page seals the account's keys to the app's key. The page shell and the flow logic are built, and the derivation is built too (Argon2id, then HKDF-SHA256, in `signin-kdf.js`); the box public key (`boxPublicKey`, X25519 through the served noble-curves copy) and the sealing step (`signin-seal.js`) are built too, and only the `available` flag keeps the form off. The account's device list (list, sign out one, sign out all) is built as three modules with no page around them yet; Settings will mount it.

Built now: an AES-GCM layer for the page (wrap and unwrap the data key, seal and open records) on vendored
@noble/ciphers 2.4.0. The sign-in page imports it through `signin.js`, but it never calls it while `available` is false, so the form stays disabled.

Full module card, including how to run its tests: `AGENTS.md` in this folder.
