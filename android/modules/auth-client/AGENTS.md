# AGENTS.md — android/modules/auth-client/

## Purpose

Login/register, token storage (Android Keystore), roles/scopes. Login/register and token management (F14, F22). The user's token
authenticates sync, training, and transcription API calls.

**Build phase:** Phase 11 (key bootstrap and re-key in Phase 19, reset in Phase 20). Needs first: `core` (on main) and the server's `sync-api`.

## Owns
Login/register, token storage (Android Keystore), roles/scopes.

## Public Interface `core.AuthService` port.

**Flow (ADR-006):**
- **Register** (`POST /v1/auth/register`): sends `{username, salt, kdf_params,
  kdf_version, auth_verifier}` — key derivation (Argon2id + HKDF) runs behind
  `core.KeyDerivation`, never here; this module never sees the password
  itself, only what crypto hands it to upload. First account ever = **admin**
  (F22).
- **Salt lookup** (`POST /v1/auth/salt`): asks for `{salt, kdf_params,
  kdf_version}` before login. No decoy — an unknown username gets an honest
  "no such account" response (ADR-006; account discovery sits inside the VPN
  perimeter, per the owner's scope decision in docs/01/05).
- **Login** (`POST /v1/auth/login`): sends only the auth verifier → receives
  a token (JWT/session), the user's `role`, the account's wrapped keys
  (`wrapped_dek`, `wrapped_box_privkey`, `box_pubkey` — `null` for a keyless
  account), the account's `key_version`, `prev_wrapped_dek` (the previous DEK
  wrapped with the current one while a re-key is unfinished, else `null`,
  handed to crypto so the re-key can finish), and `reset_at`/`rekeyed_at`
  (the account's last-reset and last-rekey timestamps, or `null`). Token →
  **Android Keystore**, never plaintext. If the returned `kdf_params` are
  below ADR-006's floor, this module refuses to proceed rather than derive
  against them — a server cannot talk a client down. If the wrapped keys
  come back null, this module hands the fresh KEK to crypto to bootstrap
  them (`POST /v1/auth/keys`) — see crypto's card — and also triggers the
  plaintext-transcription migration (ADR-006, a `key_version` 0 → 1 replace)
  for any account that synced data before it had a DEK. The app shows "this
  account was reset on \<date\>" when `reset_at` is a timestamp this device
  has not shown before, and "keys last changed on \<date\>" when `rekeyed_at`
  is, each on its own, so a routine password change never re-announces an old
  reset — the old password failing to work is NOT treated as a signal by
  itself, since that happens on every reset, legitimate or not (ADR-018).
- **Change password** (`POST /v1/auth/change-password`): the live session
  token identifies the account, but is never sufficient by itself — the
  request body carries `old_auth_verifier` (re-proving the current
  password) alongside `{new_salt, new_kdf_params, new_kdf_version,
  new_wrapped_dek, new_wrapped_box_privkey, new_box_pubkey,
  new_auth_verifier, new_key_version, prev_wrapped_dek}` (the old DEK wrapped
  with the new DEK, so an interrupted re-key can finish, ADR-006). If login
  returned `prev_wrapped_dek`, an earlier re-key is unfinished: this module
  lets it finish before it sends a new change (the server refuses one with
  409 until then). Revokes every other outstanding token. **Every call re-keys:** this module always asks crypto for a
  brand-new DEK and box keypair, never a re-wrap of the existing ones, so
  whoever held the old DEK loses access to anything synced from this point
  on (ADR-006) — there is no lighter path and no special case to detect a
  change that happens to follow a reset, which is exactly what bounds a
  completed reset's exposure (ADR-018).
- **Complete an admin/agent reset** (`POST /v1/auth/complete-reset`):
  authenticated by the admin-issued one-time reset code (≥128 random bits,
  single use, 24-hour expiry) **instead of a password or a session token** —
  the reset already revoked every token this account had, so there is none
  to send, and this call is refused for an agent token the same as the
  key-bearing endpoints above; uploads a fresh `{salt, kdf_params,
  kdf_version, auth_verifier}` and then bootstraps keys exactly like a new
  account (ADR-018). Presenting the code first is what determines who
  completes the reset — this module has no way to guarantee that is the
  account's legitimate owner rather than whoever holds the code.
- **Role:** the server returns the user's role (`admin`|`user`) in the login
  response; the client never trusts its own role claim for server actions
  (T16).

**Consumers:** sync (token), training-client (token + role), updater (admin
force-check gating), settings (server URL from first-run onboarding), crypto
(hands it the fields to upload; never called directly — see Known Gotchas).

**Agent token scopes (D29):** `transcribe` (default) | `admin`. Admin-scoped
tokens can call admin endpoints (e.g., `POST /v1/admin/reset-account`, F31,
ADR-018). The client shows the scope it holds but never trusts its own claim
server-side (T16).

## Invariants
- Register → first account admin; second account user (F22).
- Login → token in Keystore; logout wipes it.
- Force-check button visible only to admins (F21).
- Change-password revokes every other outstanding token; it requires both
  the live token AND `old_auth_verifier` — a token alone is refused.
- A completed reset leaves the account able to log in with whatever password
  was set by whoever presented the one-time code to `complete-reset` — in
  the normal flow that is the user, and the admin does not set it there, but
  this is not a guarantee the client can enforce or should claim (ADR-018).
  The account's very next password change — every password change rotates
  the DEK and box keypair, with no reset-specific case needed — is what
  bounds that gap.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Key derivation (crypto)
- Server auth logic (server/sync-api)

## Test Locations
- Unit (Kotlin): `android/modules/auth-client/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:auth-client:test`
- Contract: `tests/contract/test_auth_client_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_auth_client_contract.py`
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
- Tokens in the Android Keystore, never plaintext; roles server-enforced.
- Key derivation reaches it as `core.KeyDerivation` (`deriveKeys`), wired by `android/app` (ADR-001); it never imports `crypto`.
- `core.KeyDerivation` (`deriveKeys`, implemented by the crypto module) comes
  when Phase 11 builds it; the DEK/box-keypair calls stay on
  `core.CryptoService` and come when Phase 19 builds them (ADR-006, ADR-018) —
  this card describes the module's target flow, not a port that exists yet.
