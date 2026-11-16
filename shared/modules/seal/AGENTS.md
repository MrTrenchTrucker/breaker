# AGENTS.md — shared/modules/seal/

## Purpose

The sealed box (libsodium `crypto_box_seal`) opener and sealer shared by the phone and the
server: HSalsa20, `SealedBox.open` and `SealedBox.seal`, moved out of `android/modules/crypto`
so a server module can depend on a `shared/` module rather than an `android/*` one (ADR-018).
Pure Kotlin/JVM, no Android imports.

## Owns
HSalsa20 (the Salsa20 core without the final addition), the sealed-box opener `SealedBox.open`,
the sealed-box sealer `SealedBox.seal`, and the ephemeral key seam `EphemeralSource` (internal).

## Does Not Own
The phone's `CryptoRefused` mapping wrapper (android/modules/crypto); key derivation, wrapping
and the account key service (android/modules/crypto); the server's pending-sealed store and
intake route (server/modules/sync-api).

## Public Interface
- `SealedBox.open(box: ByteArray, recipientPrivateKey: ByteArray): ByteArray` — opens a libsodium
  `crypto_box_seal` box.
- `SealedBox.seal(recipientPublic: ByteArray, plaintext: ByteArray, maxPlaintextBytes: Int):
  ByteArray` — seals to a recipient public key. `maxPlaintextBytes` is the caller's own cap:
  required, no default.
- `SealRefused` — the module's own `IllegalArgumentException`, thrown for every refusal; the
  phone's thin wrapper maps it to `CryptoRefused`.
- `BodyCipher`, `DefaultBodyCipher`, `EphemeralSource`, `SecureRandomEphemeralSource` and
  `sealedAllZero` are `internal`, test-only.

## Depends On
- shared (registered in modules.toml)

## Invariants
- The module is pure JVM: no `android.*` or `androidx.*` import.
- Every refusal is the one plain `SealRefused`, with no cause and no byte of the box or key in
  the message.
- The tag is checked, in constant time, before any plaintext byte is made.
- `SealedBox.open` accepts a 48-byte box (a valid sealed empty message); `SealedBox.seal`
  refuses an empty plaintext (H6), so it can never make the 48-byte box the intake route refuses
  (intake minimum 49, `PendingSealedIntakeRoute.kt:120`).
- The plaintext cap is a required parameter: the module does not know the phone cap (524288,
  `AccountKeyServiceImpl.kt:23`) or the store cap (786,368, `PendingSealedStore.kt:30`); each
  caller states its own.
- The ephemeral source is the only randomness; the private bytes are zeroed after use.
- No main or test source over 300 lines (`ModuleHygieneTest`).

## Test Locations
- Unit (Kotlin): `src/test/kotlin/dev/breaker/dictation/seal/` — `SealVectorData.kt` (the `seal`
  and `seal_refusals` rows copied from `android/modules/crypto/.../CryptoVectorData.kt`),
  `SealedBoxSealerTest.kt`, `SealedBoxOpenerTest.kt`, `HSalsa20Test.kt`, `SealVectorFileTest.kt`,
  `SourceText.kt`, `ModuleFiles.kt` and `ModuleHygieneTest.kt` with `lineCap = 300`.
- Contract: `tests/contract/test_seal_contract.py`. Run: `python3 -m unittest discover -s tests/contract -t tests/contract -p test_seal_contract.py`

## Test Requirement
Every test added or touched for this module must be proven to fail loudly: break the protected
behavior on purpose, confirm the test fails and says why, then restore the code.

## Known Gotchas
- `SealVectorData.kt` is a literal copy of the `seal`/`seal_refusals` rows of
  `CryptoVectorData.kt`; the Python oracle written to compare this copy with the JSON proves the copy equals
  `examples/crypto_vectors.json`. Do not reformat a hex string.
- The module cannot import `CryptoRefused` (android/modules/core); the phone wrapper maps
  `SealRefused` back.
- `SealedBox`'s `BodyCipher` and `EphemeralSource` constructor parameters are internal: the
  public no-arg constructor is the production entry, the internal one is the test seam.
- The moved `SealVectorFileTest` does not extend the phone's `CryptoServiceTestFixture` (this
  module cannot depend on `android/modules/crypto`); the sign-in package vector is pinned by its
  plain bytes, not by `SignInPackage.parse`.
