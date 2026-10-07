package dev.breaker.dictation.crypto

import java.text.Normalizer

/**
 * Turns a password into the bytes that go into key derivation: the password
 * normalised to Unicode NFC, then encoded as UTF-8. Nothing else is done to it:
 * no case folding, no trimming and no NFKC.
 *
 * The same password can arrive composed or decomposed from different keyboards
 * (for example "é" as one code point or as "e" plus an accent), and the user
 * must still be able to log in. The web client does the same with
 * `String.prototype.normalize('NFC')`, so both sides derive the same bytes.
 *
 * An unpaired surrogate cannot be encoded as UTF-8 and becomes '?'.
 *
 * The caller owns the returned array and should zero it when done.
 */
internal fun passwordBytes(password: String): ByteArray =
    Normalizer.normalize(password, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
