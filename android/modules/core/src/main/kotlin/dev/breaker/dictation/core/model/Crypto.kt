package dev.breaker.dictation.core.model

/**
 * Ciphertext plus the nonce and tag it needs to be opened again.
 *
 * All three parts travel together: a ciphertext without its nonce or its tag is
 * not decryptable, and the tag is what proves nobody altered it. The ciphertext
 * itself may be empty: sealing an empty message produces no ciphertext bytes,
 * only the tag, and that is a real value that opens back to the empty message.
 */
class CipherText(val ciphertext: ByteArray, val nonce: ByteArray, val tag: ByteArray) {
    init {
        require(nonce.isNotEmpty()) { "A nonce is required to decrypt" }
        require(tag.isNotEmpty()) { "An authentication tag is required to decrypt" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is CipherText &&
                    ciphertext.contentEquals(other.ciphertext) &&
                    nonce.contentEquals(other.nonce) &&
                    tag.contentEquals(other.tag)
                )

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        return result
    }

    /** Never prints the bytes. */
    override fun toString(): String =
        "CipherText(${ciphertext.size} bytes, nonce=${nonce.size}, tag=${tag.size})"
}

/** A data-encryption key. Held in memory only, and wiped on logout. */
class DataEncryptionKey(val bytes: ByteArray) {
    init {
        require(bytes.isNotEmpty()) { "A data-encryption key cannot be empty" }
    }

    /** Overwrite the key material. Called when the session ends. */
    fun wipe() = bytes.fill(0)

    /** Never prints the key. */
    override fun toString(): String = "DataEncryptionKey(${bytes.size} bytes)"
}

/** A data-encryption key as the server stores it: unusable without the password. */
class WrappedDek(val bytes: ByteArray, val salt: ByteArray) {
    init {
        require(bytes.isNotEmpty()) { "A wrapped key cannot be empty" }
        require(salt.isNotEmpty()) { "A wrapped key needs its salt" }
    }

    /** Never prints the key. */
    override fun toString(): String = "WrappedDek(${bytes.size} bytes, salt=${salt.size})"
}

/**
 * Text with a per-user key applied. What the server stores and returns.
 *
 * The ciphertext may be the empty string: an empty message seals to no ciphertext
 * bytes, only a nonce and a tag. Whitespace-only is not a ciphertext and is refused.
 */
data class EncryptedText(val ciphertext: String, val nonce: String, val tag: String) {
    init {
        require(ciphertext.isEmpty() || ciphertext.isNotBlank()) { "ciphertext cannot be whitespace-only" }
        require(nonce.isNotBlank()) { "nonce cannot be blank" }
        require(tag.isNotBlank()) { "tag cannot be blank" }
    }
}
