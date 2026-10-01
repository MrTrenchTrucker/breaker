# AGENTS.md — server/modules/web-fe/

## Purpose

Debian container website (client-side decrypt, Trucking UI), APK/cert/model hosting, admin panel. Debian container website for Breaker: registration, login, and
view/search/copy of the user's transcriptions from any device on the VPN.
Also hosts the APK and CA certificate for download (F15, F17, F18).

**Pages:**
- **Landing** — Breaker intro + download links: APK, CA certificate, docs.
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

**Hosting:**
- `/Breaker.apk` — latest signed APK (from the git-pull build).
- `/Breaker-CA.crt` — the self-hosted CA certificate (one-time install, F18).
- Published SHA-256 for the APK on the download page (tamper check).

**Security:** standard web hardening — HTTPS only (self-hosted CA), CSP, input
escaping, no secrets in frontend. All data fetched via `sync-api` with the
user's token; the FE itself never touches raw audio.

**Client-side derivation and decryption (F24, ADR-006):** transcriptions
arrive as ciphertext (once the account has bootstrapped — see below). The
browser derives one Argon2id output from the user's password and the
account's salt (64 MiB, 3 iterations, parallelism 1 — same parameters and
KDF version as the Android app, and this page refuses to derive against
weaker parameters if the server ever hands them back) and HKDF-splits it
into a KEK and an auth verifier; it sends only the verifier to log in,
unwraps the DEK locally with the KEK, and decrypts with AES-256-GCM **in the
browser**. The registration form enforces a 12-character minimum password and
warns further on common/weak ones (ADR-006) — libsodium.js runs the
sealed-box unseal (ADR-018) the same way. The server never receives the
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
(ADR-018):** it lists `GET /v1/sync/pending-sealed`, unseals each with the
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
no rate limiting on login either (auth hardening is explicitly out of scope,
docs/01/05 — see T25). A raw, unhashed leaked `auth_verifier` is a separate,
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
  **Install Certificate** (black outline) · **Get the Model** (white outline).
  Four clear paths for every visitor: **Log In · Register · Get the App · Get
  the Cert**.
- **Auth** — login + registration card (first account = admin) + complete-reset
  form (shown given a reset code instead of ordinary login).
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

**Build phase:** Phase 12 (APK and certificate hosting in Phase 14, roles polish in 17, client-side crypto in 19, admin config in 20, the theme in 22). Needs first: `sync-api` (Phase 11) and `ui-tokens` (on main).

## Invariants
- First visitor sees a registration card; first account = admin (F22).
- Landing shows the login path + download badges without scrolling (F35).
- Login → dashboard shows only that user's transcriptions (N13).
- Admin panel visible only to admins; role changes take effect immediately.
- APK + cert downloads work over HTTPS with no cert warnings (after CA install).

## Owns
Debian container website (client-side decrypt, Trucking UI), APK/cert/model hosting, admin panel.

## Public Interface
WebApp

## Depends On
- server (registered in modules.toml)
- server_sync_api (registered in modules.toml)
- shared_ui_tokens (registered in modules.toml)

## Does Not Own
- API logic (sync-api)
- Design tokens (shared/ui-tokens)

## Test Locations
- Unit (Kotlin/Ktor, ADR-017): `server/modules/web-fe/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :server:modules:web-fe:test` once this module's build file applies the Kotlin plugin (today it applies `base` only, so there is no test task yet).
- Contract: `tests/contract/test_web_fe_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_web_fe_contract.py`
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
- Two devices racing to convert the same pending sealed record is harmless:
  sync dedupes on the (client id, `key_version`) pair, so the second upload
  is a no-op, not a duplicate (ADR-018).
- Never derive the KEK from anything but the password itself — a decoy, a
  server-chosen fallback, or any path that doesn't run the real Argon2id
  reintroduces exactly the overclaim ADR-006's amendment removed.
