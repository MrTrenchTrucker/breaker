# AGENTS.md — android/modules/crypto/

## Purpose

Client-side key derivation and encryption for encryption by default (F24,
ADR-006, ADR-018). Derives the user's KEK and auth verifier from their
password, generates and wraps the per-user DEK, encrypts/decrypts
transcriptions with AES-256-GCM, generates the owner's X25519 box keypair,
and unseals agent-token results sealed to it — all locally. Nothing here ever
sends the password, the KEK, or a plaintext DEK off the device.

**Build phase:** Phase 11 builds key derivation (`deriveKeys`). BUILT in the sign-in slice: unwrapping the data key with a key-encryption key, AES-256-GCM encrypt and decrypt with associated data, nonces from a secure random source, and opening a sign-in package (a sealed box) with the one-time private key. Decided, not built (Phase 19): generating the data key and the box keypair, wrapping a new data key, wrapping the box private key, unwrapping the box private key from its own wrapped form, unsealing results sealed to the account (`unsealAndReencrypt`), and the Android Keystore store. Needs first: `core` (on main).

## Owns
Client-side key derivation (Argon2id + HKDF), DEK generation/wrap/unwrap,
AES-256-GCM encrypt/decrypt, X25519 box keypair generation, sealed-box
unsealing.

## Public Interface `core.CryptoService` port with its implementation `CryptoServiceImpl`, and `KdfImpl`, the `core.KeyDerivation` implementation.

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
   ADR-006). Before it becomes bytes the password is normalised to Unicode
   NFC (`java.text.Normalizer.Form.NFC`) and encoded as UTF-8, with no case
   folding, no trimming and nothing else, so the same password typed composed
   or decomposed on different keyboards still logs in. The HKDF step is
   HKDF-SHA256 Extract with an empty salt (RFC 5869 treats it as 32 zero
   bytes) over the 32-byte Argon2id output, then Expand with `info` set to the
   ASCII bytes of the label and length 32. The KEK (an AES-256 key) and the
   auth verifier are each exactly 32 bytes; the client sends exactly 32
   verifier bytes and the server refuses any other length before hashing (the
   wire encoding, base64 or hex, is decided in api-contracts).
2. **First login with no keys yet (Phase 19, or any later first login on a
   keyless account — ADR-006's bootstrap):** generate a 256-bit DEK and an
   X25519 box keypair, wrap the DEK with the KEK and the box private key with
   the DEK, and upload both wrapped forms plus the plain box public key.
3. **Ordinary login:** re-derive KEK + auth verifier from the password and
   the stored salt/params; send only the auth verifier; unwrap the DEK with
   the KEK and the box private key with the DEK; cache both in memory only
   (the Android Keystore is to hold the wrapped DEK, never the unwrapped keys; decided and not built).
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
- `generateDek() -> Dek` / `wrapDek(dek, kek) -> WrappedDek` (decided, not built:
  generating a data key and wrapping a new one are later work)
- `unwrapDek(wrappedDek, kek) -> DataEncryptionKey` (built). The wrapped data key has ONE byte form,
  so that the website page builds the same bytes: 60 raw bytes (not base64),
  nonce(12) || ciphertext(32) || tag(16), AES-256-GCM under the key-encryption key, with
  EMPTY associated data. Any other length, or a wrong key, is refused. The salt field of
  the wrapped key is not used. The caller derives the key-encryption key; no password
  reaches this operation.
- `encrypt(plaintext, dek, aad) -> {ciphertext, nonce, tag}` (built): AES-256-GCM with a
  12-byte nonce that comes only from a secure random source and a 16-byte tag. The
  associated data is opaque bytes built by the caller (the client id as UTF-8, a newline,
  the decimal key version); that form is pinned by test vectors (ADR-006 P2).
- `decrypt({ciphertext, nonce, tag}, dek, aad) -> plaintext` (built): refuses a nonce
  that is not 12 bytes, a tag that is not 16 bytes, a key that is not 32 bytes, a wrong
  key, wrong associated data and any damage, all as the one plain `CryptoRefused`.
- `openSignInPackage(sealedPackage, signInPrivateKey) -> {dek, boxPrivateKey}` (built):
  opens the sealed box (libsodium `crypto_box_seal`, the ADR-018 construction) with the
  one-time private key, then reads the package plaintext. The package plaintext has ONE
  byte form: exactly 65 bytes = 0x01 || data key (32) || box private key (32). It is
  refused unless the length is exactly 65 and the first byte is 1; there is no key
  version inside and the keys are not inspected. The shared known-answer vector (a real
  `crypto_box_seal` box, with its secret key and public key) is in `SignInPackageTest`.
  A sealed box uses a random ephemeral key, so the website page cannot reproduce those
  bytes; its own test is to OPEN that box with the secret key and get the 65 bytes, and
  to do its own seal-then-open round trip.
- `generateBoxKeypair() -> {boxPubkey, boxPrivkey}` /
  `wrapBoxPrivkey(boxPrivkey, dek) -> WrappedBoxPrivkey` /
  `unwrapBoxPrivkey(wrapped, dek) -> boxPrivkey` (decided, not built: this module does
  not yet generate, wrap or unwrap the account's box key pair)
- `unsealAndReencrypt(sealedBox, boxPrivkey, dek) -> {ciphertext, nonce, tag}` (decided,
  not built). This is the libsodium `crypto_box_seal` open, immediately followed by an
  ordinary `encrypt`, applied to results sealed to the account. This module never seals
  (that happens server-side in `whisper-server`, ADR-018); it only opens.

`deriveKeys` implements its own core port, `core.KeyDerivation`;
`core.CryptoService` is the port the other operations implement, and
`CryptoServiceImpl` implements it. The port changed once, in the sign-in slice
(ADR-006, planned items P1 to P4): `unwrapDek` takes the key-encryption key,
`encrypt` and `decrypt` take the associated data, and `openSignInPackage` was added.
The operations marked "decided, not built" above are still not built.

**Security notes:**
- Same Argon2id parameters, HKDF labels, and test vectors as the server and
  web FE (shared spec in api-contracts, R21) — all clients must interoperate,
  and so must the one libsodium sealed-box construction (ADR-018) across a
  JVM sealer and Android/browser unsealers.
- The module checks the KDF parameters before it derives anything, whatever
  `kdf_version` is claimed: memory 64 to 256 MiB, iterations 3 to 10,
  parallelism 1 to 4, output length exactly 32 bytes. Login hands the stored
  parameters back to the phone, so a buggy or hostile value must not make
  every login try to allocate it. A `kdf_version` other than 1 is refused
  after those range checks.
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
- A keyless account (no `wrapped_dek` yet) will generate and upload its DEK +
  box keypair exactly once, on its first login after both exist client-side (decided, not built: Phase 19).

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
- Unit (Kotlin): `android/modules/crypto/src/test/kotlin/`, one test class beside each implementation class. Run: `./gradlew :android:modules:crypto:test`
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
- Bouncy Castle keeps its own internal copies of key material (the private-key
  parameters, the key agreement and the cipher state), and this module cannot zero
  them; it zeroes only the arrays it owns.
- A keyless account's first login uploads its DEK + box keypair exactly
  once — the server refuses a second upload (ADR-006), so a retry after a
  dropped response must check whether the keys already landed before
  regenerating them.
- Do not confuse "first login ever" with "a password change" — both generate
  a fresh DEK + box keypair, but the first is a bootstrap (`POST
  /v1/auth/keys`, no prior wrapped DEK to replace) and the second rides
  `POST /v1/auth/change-password`, which replaces the prior wrapped DEK on
  **every** call, not only one that happens to follow a reset (ADR-006).
- The contract's `encrypted` field is ONE string: nonce || ciphertext || tag, joined and
  base64-encoded together. `toEncryptedText` here still gives three separate base64
  parts, and joining them into the single string is NOT built in this module. That is
  the sync module's job.
- The wrapped data key and the sign-in package plaintext have one byte form each (see
  Interfaces). The website page must build exactly those bytes, so change neither form
  without changing the page and the shared vectors in the same step.
