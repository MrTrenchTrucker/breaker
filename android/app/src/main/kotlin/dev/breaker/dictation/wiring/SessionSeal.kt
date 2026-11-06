package dev.breaker.dictation.wiring

/**
 * Sealed and unsealed bytes of one session record. A seal hides the plaintext and
 * binds it to a purpose, so a record a seal wrote can be opened only by a seal that
 * shares the same key and the same purpose. "open" is a Kotlin keyword, so the read
 * side is named [unseal].
 */
internal interface SessionSeal {
    /** Encrypts [plain] into a self-describing blob: version byte, cipher IV, ciphertext, GCM tag. */
    fun seal(plain: ByteArray): ByteArray

    /** Reverses [seal]. Throws on a bad version, a tampered blob, the wrong key or a short input. */
    fun unseal(sealed: ByteArray): ByteArray
}
