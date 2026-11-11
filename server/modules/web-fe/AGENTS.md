# AGENTS.md — server/modules/web-fe/

## Purpose

Debian container website (client-side decrypt, Trucking UI), APK/model hosting, admin panel. Debian container website for Breaker: registration, login, and
view/search/copy of the user's transcriptions from any device on the VPN.
Also hosts the APK for download (F15, F17). No certificate handling in Breaker; TLS is the admin's choice through any reverse proxy (ADR-034).

**What is built today.** The sign-in page (`signin.html`, `css/signin.css` and the scripts under `js/`), the AES-GCM layer and the device list modules; see Built now below. In `js/signin-crypto.js` the key derivation is built and wired, while the box public key and the sealing are stubs that throw `not available`, so the page disables its form and says that signing in through the website is not available yet. Every other page, route and feature described in this card (Pages, Hosting, Security, Client-side derivation and decryption, the first-login key bootstrap, Search, Responsive, Screens) is planned, not built, unless Built now says otherwise.

**Pages:**
- **Landing** — Breaker intro + download links: APK, docs.
- **Auth** — login form; **registration card on first login** (F22): if no
  accounts exist yet, the first registration creates the **admin** account.
  **Complete reset** — shown instead of the ordinary login form when the
  browser arrives with a reset code (out-of-band, from the admin): username,
  the one-time code, and a new password, posting to
  `POST /v1/auth/complete-reset` (ADR-018); this page then runs the exact
  same client-side key bootstrap as a first-ever login (below).
- **Dashboard** — user's transcriptions: list, search, copy; sync status. On
  login, a banner reads "this account was reset on \<date\>" when `reset_at`
  is new to this browser, and "keys last changed on \<date\>" when
  `rekeyed_at` is, each on its own (ADR-006/ADR-018) — these are the actual
  signals a reset happened, not "the old password stopped
  working," which fires on every reset, legitimate or not.
- **Admin panel** (admin only) — manage users (assign/revoke admin), server
  health, latest GIT tag, trigger force update check. **Reset account**
  action per user: calls `POST /v1/admin/reset-account` and shows the
  returned one-time code once, for the admin to relay out of band; the panel
  does not send it anywhere itself (ADR-018).
- **Admin → Transcription Services** (F26): configure the transcription service
  endpoint — name, base URL (Docker network / IP / external), optional API key,
  enabled. **Test connection** button probes the endpoint and reports
  reachable/unreachable before it's relied on. Admin-only (T20).
- **Admin → Agent Tokens** (F27): generate scoped tokens (name, optional
  expiry, owner account), list, revoke. Agents use them against the API
  directly; results land in the owner's account.
- **Admin → Store** (F29): clear the transcription store per-user or for all
  users (with confirmation). Retention status shown (3-month TTL, F28).
- **Downloads → Models** (F30): on-device model + checksums served for phone
  download (licensing permitting; fallback to upstream with pinned checksums [2]).

**Planned (ADR-036), partly built: sign-in hand-off page** (the page shell and the flow logic are built, see Built now below; the derivation is built, and the box key and sealing steps are stubs). When the app opens the Auth page with a one-time challenge and the app's public key, the page signs the person in. It then derives the KEK in the browser, unwraps the account's keys and seals them to the app's public key, and sends the browser back to the app through the `breaker://` link with a one-time code. The honest limit below applies to that sealing step too: this container serves the page's JavaScript.

The planned address of the sign-in page is `<server>/signin.html#challenge=<43>&app_key=<43>`. The challenge and the app's public key sit in the URL fragment, never the query, so they are not sent to the server and stay out of access logs and the Referer header. `challenge` is 43 URL-safe characters (the base64url SHA-256 of the one-time secret). `app_key` is the app's X25519 public key as 32 bytes of base64url without padding, which is 43 characters. Both are shape-checked before the password field is enabled, and any other fragment gives one error and no form. The page clears the fragment with `history.replaceState` right after reading it. When sign-in is done the page opens `breaker://handoff?code=<43>` (the one-time code from the start route) with `location.replace`, so the return link is never left in the history.

**Built now: the AES-GCM layer and the sign-in page logic.** The AES-GCM layer (`src/main/resources/web/js/aesgcm.js`) wraps and unwraps the
account's data key (60 bytes: nonce 12, ciphertext 32, tag 16, AES-256-GCM under the 32-byte key,
empty associated data) and seals and opens records (nonce 12, tag 16, associated data given by the
caller as bytes). It uses the vendored copies of @noble/ciphers 2.4.0 in
`shared/modules/web-libs/noble-ciphers/` (seven files, never edited, pinned by hash).
The sign-in page logic is two ES modules: `signin-link.js` (pure helpers for the
link fragment, the base64 forms and the password bytes) and `signin.js` (the flow from the sign-in link to
the return link). In `signin.js` every crypto step is an injected function (`getSalt`, `derive`, `login`,
`boxPublicKey`, `seal`, `startHandoff`); only the two key unwraps use the real `aesgcm.js`. The page shell is built now too:
`signin.html`, `css/signin.css`, `js/signin-page.js` (the DOM glue, the only sign-in file that touches the document and the window), `js/signin-net.js` (the four calls to the server's own routes, the logout one fire and forget) and `js/signin-crypto.js` (`available` is false; `derive` is built and never throws, while `boxPublicKey` and `seal` are stubs that throw `not available`, so the form stays disabled). `js/signin-kdf.js` holds the Argon2id and HKDF-SHA256 steps that `derive` calls.
The page links `css/tokens.css`, which is not in this folder: whoever serves the page copies it from `shared/modules/ui-tokens/src/main/resources/tokens.css`. How these files reach a browser is an open owner question. Still planned: the real box public key and seal functions (they replace the two stubs), serving, and the end-to-end test.
The `random` option of `createCipher` is a test hook: page code must never pass it.

**Built now: the device list module (ADR-035, ADR-036), no page yet.**
Three ES modules with no page around them: `devices-net.js` (the three calls to the server's own
device routes: list, sign out one, sign out all), `devices.js` (the flow, every dependency injected,
the fixed message per status, no DOM access) and `devices-view.js` (`mountDevices(root, deps)`, the
DOM part, textContent only). The view builds its own list, message element, one sign-out button per
row and a "Sign out all my devices" control behind an in-view confirm step. A device name from the
server is untrusted text and is written with textContent. How these files reach a browser is still
the open owner question above, so no page calls `mountDevices` yet; only the module's own tests call it.

**Hosting:**
- `/Breaker.apk` — latest signed APK (from the git-pull build).
- Published SHA-256 for the APK on the download page (tamper check).

**Security:** standard web hardening — plain HTTP on Local Server; CSP, input
escaping, no secrets in frontend. No certificate handling in Breaker; TLS is the admin's choice through any reverse proxy (ADR-034). All data fetched via `sync-api` with the
user's token; the FE itself never touches raw audio.
The device list sends the user's session token only as a bearer header on the three device calls; no
cookie, no cache, no storage, and no token in the URL. A hostile device name renders as literal text.

**Client-side derivation and decryption (F24, ADR-006):** transcriptions
arrive as ciphertext (once the account has bootstrapped — see below). The
browser derives one Argon2id output from the user's password and the
account's salt (64 MiB, 3 iterations, parallelism 1 — same parameters and
KDF version as the Android app, and this page refuses to derive against
weaker parameters if the server ever hands them back) and HKDF-splits it
into a KEK and an auth verifier; it sends only the verifier to log in,
unwraps the DEK locally with the KEK, and decrypts with AES-256-GCM **in the
browser**. The browser does exactly what Android does. It normalises the
password to Unicode NFC with `String.prototype.normalize('NFC')` and encodes it
as UTF-8, with no case folding, no trimming and nothing else, so a password
typed composed or decomposed still logs in. Before it derives anything it
checks the parameters: memory 64 to 256 MiB, iterations 3 to 10, parallelism 1
to 4, output length exactly 32 bytes, whatever `kdf_version` is claimed (login
hands the stored values back, and a buggy or hostile one must not make the page
try to allocate it); a `kdf_version` other than 1 is refused before the range checks, and both give the same `kdf_refused` result;
the output length is fixed at 32 bytes by kdf_version 1 and is not sent.
The HKDF step is HKDF-SHA256 Extract with an empty salt (RFC 5869
treats it as 32 zero bytes) over the 32-byte Argon2id output, then Expand with
`info` set to the ASCII bytes of the label (`breaker-kek-v1` or
`breaker-auth-verifier-v1`) and length 32. The KEK and the auth verifier are
each exactly 32 bytes, the page sends exactly 32 verifier bytes, and how they
are written on the wire (base64 or hex) is decided in api-contracts. The
registration form is planned, not built: it will enforce a 12-character minimum password and
warns further on common/weak ones (ADR-006) — the sealed-box unseal (ADR-018) is
decided, not built: ADR-040 chooses the noble libraries for it, `signin-crypto.js` still has stubs that throw `not available` for the box public key and the seal,
and the libsodium files `libsodium-sumo.mjs` and `libsodium-wrappers.mjs` are vendored and pinned but never served. The server never receives the
password or the KEK over the wire — but see the honest limit below, which is
not the same limit Android gets. Shared spec + test vectors in api-contracts
(R21).

**First-login key bootstrap (ADR-006, once per account, here or on
Android):** when login returns `wrapped_dek: null`, this page generates a
256-bit DEK, wraps it with the freshly derived KEK, generates an X25519 box
keypair, wraps the box private key with the (unwrapped) DEK — **not** the
KEK — and uploads all of it via `POST /v1/auth/keys`. This is the same
mechanism whether the account predates encryption (Phases 11–18), was just
registered, or was just reset. The same login also checks
`GET /v1/transcriptions` for any row still at `key_version` 0 (plaintext,
left over from before encryption existed), encrypts each one under the new
DEK, and replaces it at `key_version` 1 via `POST /v1/sync`'s atomic-replace
path — a real replace of that row, keyed on its existing `client_id` at a
higher `key_version`, not a second, same-version push the server would
simply no-op (ADR-006) — letting the server delete the plaintext copy only
once the ciphertext has actually landed. A web-FE-only user (no Android app)
has no other client that would ever do this.

**On login, the browser first checks the box key (ADR-018, T26):** it
recomputes the box public key from the just-unwrapped private key
(`crypto_scalarmult_base`) and compares it to the `box_pubkey` the login
response carried; a mismatch means the stored key was swapped, and the page
surfaces it as tampering rather than silently accepting it or re-bootstrapping
over it.

**On login, the browser also converts any pending sealed agent-token results
(ADR-018; planned, not built: the pending-sealed route does not exist yet):** it lists `GET /v1/sync/pending-sealed`, unseals each with the
(just-unwrapped) box private key, re-encrypts the plaintext under the DEK,
and uploads it via the ordinary sync path with the pending record's id as the
client id, at the account's current `key_version` — a genuinely new record,
so the ordinary (client id, `key_version`) dedupe (N12) makes a second device
racing to convert the same one a no-op, not a duplicate. This is how an
agent's transcription first appears in the dashboard, automatically and with
no review step — which is also the honest limit: this page cannot tell a
sealed record `whisper-server` actually produced from one anyone with
database write access inserted directly into the pending-sealed store; it
converts either kind the same way (T26, ADR-018).

**Honest limit (ADR-006) — this is the one client where "offline guessing
only" is NOT the floor:** a passive reader of the server (a DB/backup
breach, a log line) only gets to guess the password offline at Argon2id
cost, same as Android. But this page's own Argon2id/HKDF/AES-GCM JavaScript
is **served by this same container, on every login** — there is no signed
build pinning it the way the Android APK is signed. A compromised server, or
anyone with write access to this module's own files, can serve a version of
this very page that reads the password (or the derived KEK, or the unwrapped
DEK) out of the DOM and sends it home, at the user's next login — directly,
with no Argon2id cost and no guessing (T27). That is a materially different
guarantee than Android's, and this card does not paper over the difference:
"the server never receives the password or the KEK" is true of the wire
protocol, not of the code this container hands the browser. This app ships
no rate limiting itself. The sign-in limit (5 wrong passwords, then a 15-minute wait) is built on
the server (ADR-036); this page only shows its locked answer (auth hardening is otherwise out of
scope, docs/01/05 — see T25). A raw, unhashed leaked `auth_verifier` is a separate,
worse case than offline guessing: it is a login credential outright.

**Search is client-side (consequence of F24):** because the server stores
ciphertext only, it **cannot search server-side**. The browser decrypts the
user's transcriptions locally, then searches in the browser. No server-side
search index; search terms never leave the browser. Fine for private-scale
data; do NOT build server-side search without breaking encryption.

**Theme (F25):** **Trucking** design language via CSS variables from
`shared/ui-tokens` — white/black/green, light + dark modes, hard edges (≤ 4 px
radius), stripe accents, condensed uppercase display type. One-file swap.

**Responsive (F35):** mobile-first breakpoints — mobile (< 640 px, portrait,
bottom tab bar) · tablet (640–1024 px) · desktop (> 1024 px, landscape,
sidebar). A phone logging into the container gets the mobile layout
automatically; it looks like the Android app.

**Screens:**
- **Landing (not logged in)** — header: CB mic badge + BREAKER wordmark + green
  underline stripe + **Log In button top-right** (always visible, no scrolling).
  Hero card ("Your words, your rig") + registration card on first visit (first
  account = admin). Download badges: **Get the App** (primary green) ·
  **Get the Model** (white outline). Three clear paths for every visitor: **Log
  In · Register · Get the App**.
- **Auth** — login + registration card (first account = admin) + complete-reset
  form (shown given a reset code instead of ordinary login).
- **Sign-in (hand-off)** - the page at `<server>/signin.html#challenge=<43>&app_key=<43>` (placeholders only), opened by the app with a one-time challenge and the app key in the fragment. Elements: a username field, a password field (type password), a submit button that stays disabled until `signin-crypto.js` reports `available` as true, and one status line (role status, aria-live polite) that shows one message at a time: the not-available text, the bad link text, or the result of the run.
- **Dashboard** — transcriptions: list, search (client-side), copy, **delete
  single**, **delete all (own)** with confirmation (F34); reset notice banner
  (above) on the first login after a reset.
- **Settings** — profile, **password change** (old password + new password;
  if login returned `prev_wrapped_dek`, the page first finishes that earlier,
  unfinished re-key, because the server refuses a new change with 409 until
  then; every change generates and uploads a brand-new DEK and box keypair, together
  with the old DEK wrapped under the new one (`prev_wrapped_dek`), advances
  `key_version`, and then re-encrypts and replaces the account's own
  transcriptions still below it, finishing at the next login if it is
  interrupted — there is no lighter re-wrap path, and no
  special case for a change that happens to follow a reset, ADR-006/ADR-018),
  **logout**, **delete account** (hidden/blocked for the last remaining
  admin, F33), theme toggle (light/dark/system).
- **Admin panel** — users/roles, transcription service config + test, agent
  tokens, store clear (per-user/all), health, force update check, reset
  account (shows the one-time code once).

**Build phase:** Phase 12 (APK hosting in Phase 14, roles polish in 17, client-side crypto in 19, admin config in 20, the theme in 22). Needs first: `sync-api` (Phase 11) and `ui-tokens` (on main).

## Invariants
- First visitor sees a registration card; first account = admin (F22).
- Landing shows the login path + download badges without scrolling (F35).
- Login → dashboard shows only that user's transcriptions (N13).
- Admin panel visible only to admins; role changes take effect immediately.
- AES-GCM layer, guard one: every nonce comes from the CSPRNG (there is no nonce parameter on seal and no
  fallback entropy). Each cipher object remembers its nonces; a nonce already used is never used again
  (draw again, at most 3 draws, then refuse).
- AES-GCM layer, guard two: the running total of plaintext bytes sealed by one cipher object stays strictly
  below 64 GiB (2 to the power 36). A message that would make the total reach or pass it is refused and
  nothing is encrypted.
- Every failure of the layer is one refusal with one fixed message; no partial plaintext comes out.
- APK downloads work over plain HTTP on Local Server; no certificate warnings because no certificate authority is involved. No certificate handling in Breaker; TLS is the admin's choice through any reverse proxy (ADR-034).

## Owns
Debian container website (client-side decrypt, Trucking UI), APK/model hosting, admin panel.

## Public Interface
The sign-in page `signin.html`, with its entry script `js/signin-page.js`. The page uses the browser modules `js/signin.js`, `js/signin-crypto.js`, `js/signin-kdf.js` (which `signin-crypto.js` calls for `derive`), `js/signin-net.js`, `js/signin-link.js` and `js/aesgcm.js`, all under `src/main/resources/web`.

## Depends On
- server (registered in modules.toml)
- server_sync_api (registered in modules.toml)
- shared_ui_tokens (registered in modules.toml)
- shared_web_libs (registered in modules.toml)

## Does Not Own
- API logic (sync-api)
- Design tokens (shared/ui-tokens)
- TLS and certificates: Breaker serves plain HTTP; an admin who wants https puts a reverse proxy in front (ADR-034).

## Test Locations
- Unit (Kotlin/Ktor, ADR-017): `server/modules/web-fe/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :server:modules:web-fe:test` once this module's build file applies the Kotlin plugin (today it applies `base` only, so there is no test task yet).
- Contract: `tests/contract/test_web_fe_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_web_fe_contract.py`
- JavaScript (AES-GCM layer): `server/modules/web-fe/src/test/js/aesgcm.test.mjs`. Run: `python3 -m unittest discover -s tests/unit/web_fe` (the runner assembles the web-libs test tree first; `node --test` alone in the source tree fails to find the vendored libraries; needs node 20.19.0 or newer).
- JavaScript (sign-in page logic): `signin-link.test.mjs`, `signin.test.mjs`, `signin-kdf.test.mjs`, `signin-crypto.test.mjs`, `signin-flow.test.mjs`, `signin-edge.test.mjs`, `signin-net.test.mjs`, `signin-logout.test.mjs`, `signin-page.test.mjs` and `signin-wire.test.mjs` in the same test folder as `aesgcm.test.mjs`, run by the same command as the line above. The fixtures for them are in `signin-fixtures.mjs`, `signin-page-fixtures.mjs` and `signin-vectors.mjs`, helpers and not test files. With the AES-GCM tests and the three devices test files (`devices-net.test.mjs`, `devices.test.mjs`, `devices-view.test.mjs`) the runner runs the whole set, and the count of passing tests it must report is pinned in `tests/unit/web_fe/test_aesgcm_js.py`, which is the one place the count lives.
- Runner: `tests/unit/web_fe/test_aesgcm_js.py` runs those tests, fails (never skips) when node is missing or older than 20.19.0, and pins the number of passing tests. Run: `python3 -m unittest discover -s tests/unit/web_fe`
- Pins: `tests/contract/web_fe_aesgcm_pins.py` (collected by `test_web_fe_contract.py`) pins the hashes of the seven vendored files, the folder contents, the imports of `aesgcm.js` and the NOTICE entry. `tests/contract/web_fe_signin_pins.py` (collected by `test_web_fe_contract.py`) pins the sign-in source files: the imports of `signin.js` and of `signin-link.js`, the forbidden tokens, banned words, short code tags, digit and hex runs, ASCII only, long base64-looking literals, the line cap, the test file names and the sha256 of `aesgcm.js`. `tests/contract/web_fe_signin_kdf_pins.py` (collected by `test_web_fe_contract.py`) pins `signin-kdf.js`: its imports, its two exports, its two label lines and the join that builds the label text, the forbidden tokens and the line cap. `tests/contract/web_fe_signin_page_pins.py` (collected by `test_web_fe_contract.py`) pins the sign-in page files with `check_signin_page_sources`: the html rules, the css rules, the forbidden tokens of the page and network files, the crypto facade (`available` is false, its exports and the two `not available` throws), and the repo text scan of the new files; every rule has a firing control.
- Every run must report more than 0 tests. A mistyped path or pattern runs nothing and still prints OK.

## Test Requirement
Every test added or touched for this module must be proven to fail loudly:
break the protected behavior on purpose, confirm the test fails and says why,
then restore the code. A test that only ever passes proves nothing. This
applies to every tier and invariant listed above, not only the ones that seem
fragile.

## How This Module Is Built
One engineer owns this module and delivers it as one pull request, working on
one module at a time. The owner does not write the module's code. The owner:
- splits the work into sub-modules and has sub-agents write each one, code and
  tests;
- coordinates and orchestrates those sub-agents, checks every piece of their
  work, and sends back anything that is wrong until it is right;
- convenes a small council of sub-agents to advise on design, risks and tests
  before and during the build;
- hands the finished, checked module directly to the reviewer as a single pull
  request.
The owner's own work is orchestration, checking and correction, not writing
code.

This is how the project's own team builds modules. It is recommended for AI
agents, not required: an outside contributor may write the code themselves
(`.github/CONTRIBUTING.md`).

## Known Gotchas
- Search is client-side (ciphertext at rest) — never build server-side search.
- The AES-GCM guards live inside one page load. The limit across page loads (2 to the power 32 random
  nonces per data key, ADR-006) is decided there and is NOT built here.
- Wipe limit (sign-in page): the password bytes, the derived KEK and verifier, the DEK, the box private key, the package and the app key copy are Uint8Arrays that are zero-filled after use and on pagehide (`wipeAll`). This is a limit, not a guarantee: the JavaScript garbage collector and copies made inside libraries cannot be wiped by the page, so a secret may outlive the zero-fill in memory. Nothing is stored in localStorage, sessionStorage, IndexedDB, a cookie or the URL; the one-time code and the sealed package are not secrets and are not wiped.
- Page limits (sign-in page): the typed password is a JavaScript string, so it cannot be zero-filled; the page clears the password field after each run and on pagehide. The session token is a string too, so the run drops it and does not wipe it. The form stays disabled until `signin-crypto.js` reports `available` as true, which needs the real box public key and seal functions. The logout runs once per run, after a session token exists: on success just before the redirect, and on every later failure. Its request is fire and forget: it is not awaited and its answer is never read, so a slow or failed logout never holds up the redirect.
- Never edit the vendored noble-ciphers files under `shared/modules/web-libs/noble-ciphers/`;
  replace them only by a new exact copy of a newer
  release, with the hashes in the pins updated in the same change.
- Two devices racing to convert the same pending sealed record is harmless:
  sync dedupes on the (client id, `key_version`) pair, so the second upload
  is a no-op, not a duplicate (ADR-018).
- Never derive the KEK from anything but the password itself — a decoy, a
  server-chosen fallback, or any path that doesn't run the real Argon2id
  reintroduces exactly the overclaim ADR-006's amendment removed.
