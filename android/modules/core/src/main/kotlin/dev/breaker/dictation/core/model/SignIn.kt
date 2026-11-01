package dev.breaker.dictation.core.model

/**
 * The sealed key package a device receives when it signs in from the website: the
 * account's keys, locked so that only the private key made for that one sign-in can
 * open them.
 *
 * It is not a secret by itself, but it is the only thing between a reader and the
 * account's keys, so it is handled like one. The package lives only inside this
 * object. The constructor copies the array it is given, [copyBytes] returns a new copy
 * on every call, and [wipe] zeroes it. Arrays you pass in or get out are your own
 * copies; changing them never changes the package.
 *
 * It judges nothing except that it is not empty. What the bytes mean, and whether
 * they open, is decided by the code that opens it, in one place. There is no fixed
 * length here, because the sealed form is longer than the keys inside it.
 *
 * Two packages are equal only if they are the same object. To compare the contents,
 * compare [copyBytes] results.
 */
class SealedSignInPackage(bytes: ByteArray) {
    private val box: ByteArray

    init {
        require(bytes.isNotEmpty()) { "A sealed sign-in package cannot be empty" }
        box = bytes.copyOf()
    }

    /** A new copy of the package bytes. The caller owns it and should zero it when done. */
    fun copyBytes(): ByteArray = box.copyOf()

    /** Overwrite the package bytes with zeros. */
    fun wipe() = box.fill(0)

    /** Never prints the bytes, only how many there are. */
    override fun toString(): String = "SealedSignInPackage(${box.size} bytes)"
}

/**
 * The private key the app made for one sign-in, used to open the sealed key package.
 * Exactly 32 bytes (an X25519 private key).
 *
 * Whoever holds it can open the package that was sealed to it, so it is a secret. The
 * key lives only inside this object. The constructor copies the array it is given,
 * [copyBytes] returns a new copy on every call, and [wipe] zeroes the key. Arrays you
 * pass in or get out are your own copies; changing them never changes the key, and
 * wiping the key never touches them.
 *
 * Two keys are equal only if they are the same object. To compare the contents,
 * compare [copyBytes] results.
 */
class SignInPrivateKey(bytes: ByteArray) {
    private val key: ByteArray

    init {
        require(bytes.size == SIZE_BYTES) {
            "A sign-in private key must be $SIZE_BYTES bytes, got ${bytes.size}"
        }
        key = bytes.copyOf()
    }

    /** A new copy of the key bytes. The caller owns it and should zero it when done. */
    fun copyBytes(): ByteArray = key.copyOf()

    /** Overwrite the key material with zeros. Called when the sign-in ends. */
    fun wipe() = key.fill(0)

    /** Never prints the key. */
    override fun toString(): String = "SignInPrivateKey($SIZE_BYTES bytes)"

    companion object {
        /** The key length in bytes. */
        const val SIZE_BYTES = 32
    }
}

/**
 * The account's box private key, the one that opens results sealed to the account.
 * Exactly 32 bytes (an X25519 private key).
 *
 * It is a secret for as long as the account lives. The key lives only inside this
 * object. The constructor copies the array it is given, [copyBytes] returns a new copy
 * on every call, and [wipe] zeroes the key. Arrays you pass in or get out are your own
 * copies; changing them never changes the key, and wiping the key never touches them.
 *
 * Two keys are equal only if they are the same object. To compare the contents,
 * compare [copyBytes] results.
 */
class BoxPrivateKey(bytes: ByteArray) {
    private val key: ByteArray

    init {
        require(bytes.size == SIZE_BYTES) {
            "A box private key must be $SIZE_BYTES bytes, got ${bytes.size}"
        }
        key = bytes.copyOf()
    }

    /** A new copy of the key bytes. The caller owns it and should zero it when done. */
    fun copyBytes(): ByteArray = key.copyOf()

    /** Overwrite the key material with zeros. Called when the session ends. */
    fun wipe() = key.fill(0)

    /** Never prints the key. */
    override fun toString(): String = "BoxPrivateKey($SIZE_BYTES bytes)"

    companion object {
        /** The key length in bytes. */
        const val SIZE_BYTES = 32
    }
}

/**
 * The two keys a sign-in package holds: the data-encryption key and the box private key.
 *
 * The keys live only inside this object. [wipe] zeroes both parts. This is a plain
 * class, not a data class, so there is no generated `toString` or `copy` that could
 * print or duplicate the keys. Two pairs are equal only if they are the same object.
 */
class AccountKeys(val dek: DataEncryptionKey, val boxPrivateKey: BoxPrivateKey) {
    /** Zero both the data-encryption key and the box private key. */
    fun wipe() {
        dek.wipe()
        boxPrivateKey.wipe()
    }

    /** Never prints the keys. */
    override fun toString(): String = "AccountKeys(dek=$dek, boxPrivateKey=$boxPrivateKey)"
}

/**
 * Thrown when a cryptographic operation is refused: a key does not open the data, the
 * data was changed, or the data is not in the form the operation accepts.
 *
 * It lives in core because callers, for example the sign-in code, see only core and
 * must be able to tell "the operation was refused" from other failures. It is one
 * plain refusal on purpose: it carries no reason, so a caller cannot learn from it
 * which check failed. The message never contains a key, a byte or any text that was
 * being protected.
 */
class CryptoRefused : IllegalArgumentException("Cryptographic operation refused")
