# AGENTS.md — android/modules/crypto/

## Purpose

Client-side key derivation and encryption for encryption by default (F24,
ADR-006, ADR-018). Derives the user's KEK and auth verifier from their
password, generates and wraps the per-user DEK, encrypts/decrypts
transcriptions with AES-256-GCM, generates the owner's X25519 box keypair,
and unseals agent-token results sealed to it — all locally. Nothing here ever
sends the password, the KEK, or a plaintext DEK off the device.

**Build phase:** Phase 11 builds key derivation only (`deriveKeys`); Phase 19 builds the DEK, AES-256-GCM, the box keypair and unsealing. Needs first: `core` (on main).

## Owns
Client-side key derivation (Argon2id + HKDF), DEK generation/wrap/unwrap,
AES-256-GCM encrypt/decrypt, X25519 box keypair generation, sealed-box
unsealing.

## Public Interface `core.CryptoService` port.

**Key flow:**
1. **Registration:** the client derives one Argon2id output from the
   password and a random 16-byte salt (64 MiB memory, 3 iterations,
   parallelism 1, 32-byte output — ADR-006's floor, which this module
   refuses to derive below even if some future server response offers
   weaker parameters), then HKDF-splits it with distinct labels into a KEK
   (`breaker-kek-v1`) and an auth verifier (`breaker-auth-verifier-v1`).
   Only the salt, the KDF parameters/version, and the auth verifier are sent
   anywhere — never the password, the KEK, or a DEK. Registration alone
   creates no DEK and no box keypair (Phase 11 ships derivation only;
   ADR-006).
2. **First login with no keys yet (Phase 19, or any later first login on a
   keyless account — ADR-006's bootstrap):** generate a 256-bit DEK and an
   X25519 box keypair, wrap the DEK with the KEK and the box private key with
   the DEK, and upload both wrapped forms plus the plain box public key.
3. **Ordinary login:** re-derive KEK + auth verifier from the password and
   the stored salt/params; send only the auth verifier; unwrap the DEK with
   the KEK and the box private key with the DEK; cache both in memory only
   (Android Keystore holds the wrapped copies, never the unwrapped keys).
   Also recompute the box public key from the just-unwrapped private key
   (`crypto_scalarmult_base`) and compare it to the `box_pubkey` the login
   response carried — a mismatch means the stored key was swapped since this
   device last set it (T26), and this module surfaces that as tampering
   rather than silently accepting the new key or re-bootstrapping over it.
4. **Encrypt/decrypt:** transcriptions encrypt with AES-256-GCM before
   upload; decrypt after download. Server stores ciphertext only.
5. **Unseal (ADR-018):** for each pending sealed record the account has
   (agent-token job results sealed to this account's box public key), unseal
   with the box private key and immediately re-encrypt the plaintext under
   the ordinary DEK, producing ciphertext indistinguishable from any other
   transcription. This module has no way to tell a record `whisper-server`
   actually sealed from one someone with database write access inserted
   directly — sealing is unauthenticated by construction (T26) — so this
   step converts either kind without review. `android/modules/sync` fetches
   the records and uploads the result; this module only does the
   cryptography.
6. **Password change (ADR-006/ADR-018) — every call re-keys, there is no
   lighter re-wrap path:** if login returned `prev_wrapped_dek`, first finish
   that earlier, unfinished re-key (the server refuses a new change with 409
   until it is done); then convert any pending sealed record still
   under the current keys (step 5, so nothing is left sealed to a box key
   about to be replaced); then generate a **new** DEK and a **new** box
   keypair, wrap the new DEK with the new KEK and the new box private key with
   the new DEK, wrap the OLD DEK with the new DEK (`prev_wrapped_dek`), and
   send all of it with `new_key_version` = current + 1 through
   `change-password`. Only after that call succeeds are the account's records
   below the new `key_version` re-encrypted under the new DEK and replaced
   (`android/modules/sync`); if that is interrupted, the next login returns
   `prev_wrapped_dek`, which this module unwraps with the new DEK so the
   remaining records can still be read (ADR-006). The device's own
   not-yet-synced queue is re-encrypted under the new DEK at the same time,
   before it is pushed.
   There is no special case for a change that happens to follow a reset —
   this is what every password change does, which is what bounds how long
   whoever completed a reset (normally the user, but see ADR-018) keeps
   access to anything written after this point.

**Interfaces:**
- `deriveKeys(password, salt, kdfParams) -> {kek, authVerifier}` — one
  Argon2id run, then two HKDF expansions with distinct labels; there is no
  function that returns the KEK alone, so nothing that computes the auth
  verifier without the password can also produce the KEK.
- `generateDek() -> Dek` / `wrapDek(dek, kek) -> WrappedDek` /
  `unwrapDek(wrappedDek, kek) -> Dek`
- `encrypt(plaintext, dek) -> {ciphertext, nonce, tag}`
- `decrypt({ciphertext, nonce, tag}, dek) -> plaintext`
- `generateBoxKeypair() -> {boxPubkey, boxPrivkey}` /
  `wrapBoxPrivkey(boxPrivkey, dek) -> WrappedBoxPrivkey` /
  `unwrapBoxPrivkey(wrapped, dek) -> boxPrivkey`
- `unsealAndReencrypt(sealedBox, boxPrivkey, dek) -> {ciphertext, nonce, tag}`
  — libsodium `crypto_box_seal` open, immediately followed by an ordinary
  `encrypt`. This module never seals (that happens server-side in
  `whisper-server`, ADR-018) — it only opens.

`core.CryptoService` (the port these implement) gains the derivation
operations when Phase 11 builds them and the DEK/box operations when Phase
19 builds them — see ADR-006's and ADR-018's Consequences for that timeline;
this card describes the module's target shape, not a change to `core` itself.

**Security notes:**
- Same Argon2id parameters, HKDF labels, and test vectors as the server and
  web FE (shared spec in api-contracts, R21) — all clients must interoperate,
  and so must the one libsodium sealed-box construction (ADR-018) across a
  JVM sealer and Android/browser unsealers.
- No plaintext keys on disk; memory-only DEK and box private key; wipe on
  lock/logout.
- The client enforces a 12-character minimum password and warns further on
  common/weak passwords (ADR-006) — a nudge, not a guarantee: see the
  honest limit below.
- **Honest limit (ADR-006):** this module keeps the password and every
  key it derives from leaving the device. It does not, and cannot, stop
  someone who later obtains the stored auth-verifier hash or a wrapped DEK
  from testing password guesses offline at one Argon2id run per guess.

## Invariants
- Round-trip encrypt/decrypt with shared test vectors (interop with web FE
  and, for the sealed box, with the server's libsodium binding).
- Ciphertext at rest; server cannot decrypt without the password (F24, T17) —
  this is not a claim that the password cannot be guessed offline (T25).
- Logout wipes the DEK and box private key; re-login unwraps again.
- A keyless account (no `wrapped_dek` yet) generates and uploads its DEK +
  box keypair exactly once, on its first login after both exist client-side.

## Depends On
- android (registered in modules.toml)
- android_core (registered in modules.toml)

## Does Not Own
- Key wrapping of any kind performed server-side — there is none; the server
  never wraps or unwraps a key (server/sync-api)
- Token storage (auth-client)
- Sealing a job result to the owner's public key — that happens server-side,
  in `whisper-server`, using a JVM libsodium binding (ADR-018)

## Test Locations
- Unit (Kotlin): `android/modules/crypto/src/test/kotlin/`, created with the module's first code. Run: `./gradlew :android:modules:crypto:test`
- Contract: `tests/contract/test_crypto_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_crypto_contract.py`
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
- An old-version record this device cannot decrypt keeps `prev_wrapped_dek` alive, so the server refuses every password change (409) until it is gone. Tell the user which record is stuck and let them delete it; deleting it clears the block (ADR-006).
- KDF params + test vectors must match the server and web FE (interop); the
  sealed-box construction (libsodium `crypto_box_seal`, no Tink) must match
  `whisper-server`'s JVM binding, pinned by the same cross-platform vectors.
- A keyless account's first login uploads its DEK + box keypair exactly
  once — the server refuses a second upload (ADR-006), so a retry after a
  dropped response must check whether the keys already landed before
  regenerating them.
- Do not confuse "first login ever" with "a password change" — both generate
  a fresh DEK + box keypair, but the first is a bootstrap (`POST
  /v1/auth/keys`, no prior wrapped DEK to replace) and the second rides
  `POST /v1/auth/change-password`, which replaces the prior wrapped DEK on
  **every** call, not only one that happens to follow a reset (ADR-006).
