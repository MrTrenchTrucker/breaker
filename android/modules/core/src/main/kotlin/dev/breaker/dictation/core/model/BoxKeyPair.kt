package dev.breaker.dictation.core.model

/**
 * The account's box key pair: the X25519 private key that opens results sealed to the
 * account, and the public key that others seal them to. The two halves belong together,
 * so they travel as one value.
 *
 * Only the private half is secret. [privateKey] is a [BoxPrivateKey], so it is copied
 * when built and when read and it never prints its bytes. The public half must be exactly
 * 32 bytes. The constructor copies the array it is given, [copyPublicKey] returns a new
 * copy on every call, and [wipe] zeroes the private key only. Arrays you pass in or get
 * out are your own copies; changing them never changes the pair.
 *
 * This is a plain class, not a data class, so there is no generated `toString` or `copy`
 * that could print or duplicate the key. Two pairs are equal only if they are the same
 * object.
 */
class BoxKeyPair(val privateKey: BoxPrivateKey, publicKey: ByteArray) {
    private val publicBytes: ByteArray

    init {
        require(publicKey.size == PUBLIC_KEY_SIZE_BYTES) {
            "A box public key must be $PUBLIC_KEY_SIZE_BYTES bytes, got ${publicKey.size}"
        }
        publicBytes = publicKey.copyOf()
    }

    /** A new copy of the public key bytes. The caller owns it. */
    fun copyPublicKey(): ByteArray = publicBytes.copyOf()

    /** Zero the private key. The public key is not secret and is left as it is. */
    fun wipe() = privateKey.wipe()

    /** Never prints a key byte. */
    override fun toString(): String = "BoxKeyPair(public key 32 bytes, private key hidden)"

    companion object {
        /** The public key length in bytes. */
        const val PUBLIC_KEY_SIZE_BYTES = 32
    }
}
