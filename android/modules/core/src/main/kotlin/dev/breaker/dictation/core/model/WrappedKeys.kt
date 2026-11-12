package dev.breaker.dictation.core.model

/**
 * The account's box private key as the server stores it: sealed under the data-encryption
 * key.
 *
 * It is 60 raw bytes when well formed (nonce 12, ciphertext 32, tag 16), but this type does
 * not judge the size: it only refuses an empty array. The crypto module refuses a wrong
 * size, as it does for [WrappedDek]. It is a separate type from [WrappedPreviousDek] on
 * purpose, so the compiler keeps the two out of each other's slot.
 */
class WrappedBoxPrivateKey(val bytes: ByteArray) {
    init {
        require(bytes.isNotEmpty()) { "A wrapped key cannot be empty" }
    }

    /** Never prints the key. */
    override fun toString(): String = "WrappedBoxPrivateKey(${bytes.size} bytes)"
}

/**
 * The previous data-encryption key as the server stores it after a password change: sealed
 * under the new data-encryption key.
 *
 * It is 60 raw bytes when well formed (nonce 12, ciphertext 32, tag 16), but this type does
 * not judge the size: it only refuses an empty array. The crypto module refuses a wrong
 * size, as it does for [WrappedDek]. It is a separate type from [WrappedBoxPrivateKey] on
 * purpose, so the compiler keeps the two out of each other's slot.
 */
class WrappedPreviousDek(val bytes: ByteArray) {
    init {
        require(bytes.isNotEmpty()) { "A wrapped key cannot be empty" }
    }

    /** Never prints the key. */
    override fun toString(): String = "WrappedPreviousDek(${bytes.size} bytes)"
}
