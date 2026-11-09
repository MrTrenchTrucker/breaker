# AGENTS.md — android/modules/auth-client/

## Purpose

Login/register, token storage (the vault port is here, the Android Keystore sealed file is built in the app), roles/scopes. Login/register and token management (F14, F22). The user's token
authenticates sync, training, and transcription API calls.

**Build phase:** Phase 11 (key bootstrap and re-key in Phase 19, reset in Phase 20). Needs first: `core` (on main) and the server's `sync-api`.
**Status on main:** built so far in this module: the strict JSON reader (`JsonValue`, in `JsonTree.kt`), the request encoder for register, salt and login (`RequestEncoder`), the response decoder for those three answers (`AuthDecoder`, in `ResponseDecoder.kt`) and the KDF guard (`KdfGuard`). Also built, as groundwork for the calls and not as a flow: the HTTP port (`AuthHttp`, `AuthHttpRequest`, `AuthHttpReply`), the fixed-text failures (`AuthFailure`, `AuthFailureReason`), the account rules (`AccountRules`), the session vault port (`SessionVault`), the session record (`SessionRecord`), the address match (`ServerAddressMatch`), the server auth service (`ServerAuthService`, `ServerAuthReplies`), which implements the `core.AuthService` port over the HTTP port for register, login, sign-out and the stored session, and the pair of an address and a session (`AddressedSession`) that the service returns, and the sign-in hand-off finish (`HandoffFinish`, with `ExchangeUrl` and `ExchangeDecoder`), which implements the `core.HandoffReceiver` seam; see the list headed "Built so far" below. Built in the app and not in this module, which still holds only the ports: an implementation of the HTTP port (`AuthHttpAdapter`) and the Keystore-backed vault (`FileSessionVault` over `AesSessionSeal` and `KeystoreSessionKey`); the app supplies them and its composition root builds the service from them on first use (see the app card, `android/app/AGENTS.md`). The server speech engine reads the pair once for each request, through `serverTargetOf` in the app's composition root over `ServerAuthService.currentAddressedSession()` (see the app card). The data key and the box key a hand-off brings are stored through the `core.CredentialVault` port, and the Keystore-sealed key file behind it is built in the app. Planned, not built: the encoders and decoders for the later calls, and every flow below other than those four and the hand-off finish; the flow text below is the target design, not what the module does today.


## Owns
Login/register, token storage (the vault port is here, the Android Keystore sealed file is built in the app), roles/scopes. The HTTP port, the fixed-text failures, the account rules, the session vault port, the session record, the address match and the addressed session.

## Public Interface `core.AuthService` port, and `AddressedSession` (a session with the address it was read for), which `ServerAuthService.currentAddressedSession()` returns.

**Flow (ADR-006):**
- **Register** (`POST /v1/auth/register`): sends `{username, salt, kdf_params,
  kdf_version, auth_verifier}` — key derivation (Argon2id + HKDF) runs behind
  `core.KeyDerivation`, never here; the password travels as a CharArray that
  the service wipes after use, and this module never sees the password itself,
  only what crypto hands it to upload. A username is checked with
  `AccountRules.isValidUsername` (the one table in
  `shared/modules/api-contracts/examples/username_vectors.json`) before any
  request is sent; a bad one fails with `BAD_USERNAME` and its own sentence.
  First account ever = **admin**
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
  **Android Keystore** (built in the app: the session file is sealed under a Keystore key), never plaintext. If the returned `kdf_params` are
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

**Built so far (the groundwork under the flows above):**
- **HTTP port** (`AuthHttp`): one request gives one reply. `AuthHttpRequest` holds the method, url, headers and body; `AuthHttpReply` holds the status and the whole body. Neither prints more than the method or the status. The app supplies the implementation; this module has no HTTP client of its own. A non-2xx status is a reply; the implementation throws `java.io.IOException` when the server cannot be reached or the reply cannot be read.
- **Fixed-text failures** (`AuthFailure`, `AuthFailureReason`): nine reasons, one fixed sentence each, no input in any sentence and no cause attached.
- **Account rules** (`AccountRules`, all pure): the three call paths, the logout path and the hand-off exchange path, the key-derivation numbers, and the salt size. The password length (at least 12) is counted in Unicode code points on a CharArray, at registration only. The username rule is 1 to 64 characters from letters, digits, dot, underscore and hyphen, by plain comparison. Its one table is `shared/modules/api-contracts/examples/username_vectors.json`; the client copy is checked against it by test. The server address may be http or https (ADR-034), with no backslash, no user info, query or hash, and no whitespace after the scheme; one trailing slash is dropped, and a base that still ends in a slash is refused.
- **Session vault port** (`SessionVault`): saves, loads and clears one opaque string. This module gives no protection at rest by itself; the app backs the port with a file sealed under an Android Keystore key, so the stored text is sealed at rest there. The tests use a fake vault (`FakeSessionVault`).
- **Session record** (`SessionRecord`, internal): the text form of a session with its six members, the last being the server address the session was made with. The reader is strict: any other text returns null, and a record with a missing, empty or non-text server address is refused. The address is kept exactly as given.
- **Address match** (`ServerAddressMatch`, internal): one pure function that says whether two server addresses are the same server. The scheme and the host are compared without case, one trailing slash is ignored, and the port and the path are compared exactly. An empty address, an empty host, a backslash, whitespace or a control character never matches. The server auth service uses it to decide whether a stored session may be used at the current address.
- **Server auth service** (`ServerAuthService`, `ServerAuthReplies`): implements the `core.AuthService` port over the HTTP port: register, login, logout and current session. One lock covers register, login and logout. The server address is read once per call. The session is stored with the address it was made with, exactly as typed. `currentSession()` returns null, and sign-out sends no request, when the address read now is not the same server as the stored one (`ServerAddressMatch.same`); a changed and a cleared address both end there. `currentAddressedSession()` is the one place that rule lives: it returns the stored session together with the address read now (`AddressedSession`), so a caller that needs both takes them from one call and never joins a session to an address read at another moment. It reads the address once, inside the lock, and only when a record was loaded and decoded; it returns null when there is no readable record or the address read now is not the same server; it writes nothing and sends no request; a throw from the address provider propagates, as it does from `currentSession()`, which returns `currentAddressedSession()?.session`. Sign-out always clears the vault. `currentSession()` waits for a sign-in or sign-out in progress, and an unreadable vault gives no session. After the login reply, a new sign-in revokes, best effort, the session the vault already holds, at that session's own server with that session's own bearer, so a token never goes to another server. A failed save clears the vault, then revokes the new token. A failure the HTTP port throws that is not a network failure is a server error. The JSON body is zeroed after it is sent. The password array is cleared on every path.
- **Hand-off finish** (`HandoffFinish`, public; `ExchangeUrl`, `ExchangeDecoder` and `ExchangeAnswer`, internal): implements `core.HandoffReceiver`. The exchange URL follows the same address rule as every account call, and the address is read once per hand-off. The device name, when given, is 1 to 64 code points with no control character and no surrogate, else nothing is sent. The reply must be status 200 and one object holding a 43-character URL-safe session text, a role of user or admin, a key version from 0 up, a base64 key package that is not empty, a username that is not blank and an integer account id (read, not kept); extra members are ignored. The session user id is the username, as in a password login. It takes two HTTP instances, one for the exchange and one for the logout, so each keeps its own limits.
- **Device list read** (`DeviceListDecoder` and `DeviceRow`, internal): reads the answer of the device list
  route (path /v1/auth/devices) into rows: strict, exactly the member devices, at most 50 rows, each row
  exactly id, name, last_seen and current, a name of 1 to 64 code points or null, the time in the feed form;
  every refusal is an `AuthDecodeException` with a fixed text that carries nothing from the body; nothing
  calls it and the server route is not built yet.

## Invariants
- Register → first account admin; second account user (F22).
- Login: the session is stored in the vault, which the app seals under an Android Keystore key; logout wipes it. A hand-off finish also stores account keys through the credential vault, and the app's clearing vault wipes them on logout; a password login only saves its session and leaves such keys alone.
- Planned, not built (F21): the force-check button is visible only to admins. No force-check button exists on main.
- Change-password revokes every other outstanding token; it requires both
  the live token AND `old_auth_verifier` — a token alone is refused.
- A completed reset leaves the account able to log in with whatever password
  was set by whoever presented the one-time code to `complete-reset` — in
  the normal flow that is the user, and the admin does not set it there, but
  this is not a guarantee the client can enforce or should claim (ADR-018).
  The account's very next password change — every password change rotates
  the DEK and box keypair, with no reset-specific case needed — is what
  bounds that gap.

- Built (ADR-031, ADR-033): a session token the server issues expires 30 days after its last use. The client keeps no clock for it: it learns that a session is no longer live when the server refuses the key, and `currentSession()` reports what was stored, not whether the server still accepts it. The signed-out notice on Home is fed from this; nothing in this module opens a sign-in screen.
- Built (ADR-035, ADR-036): the sign-in hand-off from the website. At the start of sign-in the app makes a one-time secret and a key pair, and sends a challenge made from the secret, with the public key, to the website. `HandoffFinish` is this module's part: it trades the returned code and the secret for a session and the sealed key package in one `POST /v1/auth/handoff/exchange` (no Authorization header; the reply is read by `ExchangeDecoder`, which refuses anything outside the contract). It opens the package with the private key of the attempt through `core.CryptoService`, then stores the keys through `core.CredentialVault` first and the session record (with the server address) through the session vault second. The device's own password sign-in stays as the fallback. Sign-out ends this device only.
  Failure and wipe rules: the attempt is wiped on every path; the secret and key copies the finish makes are zeroed, and the secret text lives in one Kotlin String while the body is built (a String cannot be zeroed). A failure before the server answers with a session sends nothing and stores nothing. A failure after it sends one best-effort logout of that new session through its own HTTP instance (an unreadable answer sends it only when the session text can be found); the logout reply is never read and nothing is retried. An unreadable answer or a package that does not open stores nothing and clears nothing: a session or keys the phone already holds stay. A failed store clears both stores, each in its own try. The finish blocks on the network, so the caller runs it off the main thread.
  Not done by the finish: a session the phone already holds is not revoked by it. Built outside this module (core, crypto, ui, app): the one-time secret and key pair (`SignInStarter`, implemented in crypto), the link to the website, the parser of the return link `breaker://handoff`, the injected `HandoffReceiver` seam, the start screen, the return activity, and the Keystore-sealed key file behind `core.CredentialVault`.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)
- shared_api_contracts (registered in modules.toml)

## Does Not Own
- Key derivation (crypto)
- Server auth logic (server/sync-api)

## Test Locations
- Unit (Kotlin): `android/modules/auth-client/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:auth-client:test`
- Username table: `UsernameVectorData.kt` is the client copy of `shared/modules/api-contracts/examples/username_vectors.json`; `UsernameVectorsTest.kt` runs every row through `AccountRules.isValidUsername`, `ServerUsernameRefusalTest.kt` checks the refusal (own reason, no request, password array zeroed), and `tests/unit/shared/api-contracts/test_username_vectors.py` checks that the copy equals the json.
- Server auth service: `ServerLoginTest.kt`, `ServerLoginFailureTest.kt`, `ServerRegisterTest.kt`, `ServerLogoutTest.kt`, `ServerSessionTest.kt`, `ServerLockTest.kt`, `ServerNoLeakTest.kt`, `ServerSourceScanTest.kt`, `ServerPasswordWipeTest.kt`, `ServerUsernameRefusalTest.kt` and `ServerAddressRequirementTest.kt` and `ServerAddressedSessionTest.kt` (the pair: same address, the address read now, the mismatch table, no record, a corrupt record, an unreadable vault, one address read when a record exists and none otherwise, no write and no request, the delegation of `currentSession()`, the fixed text, a provider throw), with the helpers `FakeAuthHttp.kt` and `FakeKeyDerivation.kt`.
- Hand-off finish: `ExchangeUrlTest.kt`, `ExchangeDecoderTest.kt` (each refusal and its family, the accepted bounds, `tokenShapeIfPresent`), `HandoffRequestTest.kt` (method, url, headers, body bytes, escaping), `HandoffSuccessTest.kt` (keys then session, the stored record), `HandoffFailureTest.kt` (one test for each step of the finish and for the statuses 400, 401, 413 and 503), `HandoffWipeTest.kt`, `HandoffLogoutTest.kt` and `HandoffSameStoreTest.kt` (a real `ServerAuthService` over the same vault: the session, the pair and the other address), with the helpers `HandoffFakes.kt` (`FakeCrypto`, `FakeCredentialVault`, `HandoffRig`, `makeAttempt`). The request body build step cannot be made to fail from a test; the limits of the exchange are the app's and are not tested here.
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
- Tokens go in the Android Keystore, never plaintext (built in the app: the session file is sealed under a Keystore key); roles server-enforced.
- Key derivation reaches it as `core.KeyDerivation` (`deriveKeys`), wired by `android/app` (ADR-001), whose composition root hands in `KdfImpl` on first use of the service; it never imports `crypto`.
- `core.KeyDerivation` (`deriveKeys`, implemented by the crypto module) is built: `KdfImpl` in the crypto module derives the keys. The five `core.CryptoService` operations
  are built (see the core card); the DEK/box-keypair calls stay on `core.CryptoService` and come when Phase 19 builds them (ADR-006, ADR-018) —
  this card's flow for those calls is still the target.
