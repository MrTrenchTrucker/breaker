package dev.breaker.dictation.seal

/**
 * Thrown when a sealed-box operation is refused: a box is too short, a key is not 32 bytes,
 * the shared secret is all zero, the tag does not match, or the library fails.
 *
 * This module is shared by the phone and the server, so it cannot import the phone's
 * `CryptoRefused` (android/modules/core) and carries its own plain refusal instead. It is
 * one plain refusal on purpose: it carries no reason, so a caller cannot learn which check
 * failed, and its message never contains a key, a byte or any text that was protected.
 * The phone's thin `SealedBoxOpener` wrapper maps it back to `CryptoRefused` (S-3).
 */
class SealRefused : IllegalArgumentException("Sealed box operation refused")
