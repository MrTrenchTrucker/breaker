package dev.breaker.dictation.core.model

/**
 * The key-derivation settings the server stored for an account, or handed back.
 *
 * A plain carrier: [memoryKib] is the memory cost in KiB, [iterations] the number of
 * passes, [parallelism] the number of lanes, [outputLength] the tag length in bytes
 * and [version] the KDF version number.
 *
 * It judges nothing. Any Int is accepted, including 0, negatives and Int.MAX_VALUE.
 * The limits are applied by the implementation of `KeyDerivation`, not here. There is
 * one place for the rule, so a hostile or buggy stored value reaches that
 * implementation and is refused there with a reason.
 */
data class KdfParams(
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val outputLength: Int,
    val version: Int,
)

/**
 * The key that wraps the data key. Exactly 32 bytes (an AES-256 key).
 *
 * The key lives only inside this object. The constructor copies the array it is
 * given, [copyBytes] returns a new copy on every call, and [wipe] zeroes the key.
 * Arrays you pass in or get out are your own copies; changing them never changes the
 * key, and wiping the key never touches them.
 *
 * Two keys are equal only if they are the same object. To compare the contents,
 * compare [copyBytes] results.
 */
class KeyEncryptionKey(bytes: ByteArray) {
    private val key: ByteArray

    init {
        require(bytes.size == SIZE_BYTES) {
            "A key-encryption key must be $SIZE_BYTES bytes, got ${bytes.size}"
        }
        key = bytes.copyOf()
    }

    /** A new copy of the key bytes. The caller owns it and should zero it when done. */
    fun copyBytes(): ByteArray = key.copyOf()

    /** Overwrite the key material with zeros. Called when the session ends. */
    fun wipe() = key.fill(0)

    /** Never prints the key. */
    override fun toString(): String = "KeyEncryptionKey($SIZE_BYTES bytes)"

    companion object {
        /** The key length in bytes. */
        const val SIZE_BYTES = 32
    }
}

/**
 * The fixed-length pseudorandom login value sent to the server instead of the
 * password. Exactly 32 bytes.
 *
 * It is itself a login credential: anyone holding the raw value can log in. So it is
 * redacted and wiped like a key. The value lives only inside this object. The
 * constructor copies the array it is given, [copyBytes] returns a new copy on every
 * call, and [wipe] zeroes it. Arrays you pass in or get out are your own copies.
 *
 * Two verifiers are equal only if they are the same object. To compare the contents,
 * compare [copyBytes] results.
 */
class AuthVerifier(bytes: ByteArray) {
    private val value: ByteArray

    init {
        require(bytes.size == SIZE_BYTES) {
            "An auth verifier must be $SIZE_BYTES bytes, got ${bytes.size}"
        }
        value = bytes.copyOf()
    }

    /** A new copy of the verifier bytes. The caller owns it and should zero it when done. */
    fun copyBytes(): ByteArray = value.copyOf()

    /** Overwrite the verifier with zeros. */
    fun wipe() = value.fill(0)

    /** Never prints the value. */
    override fun toString(): String = "AuthVerifier($SIZE_BYTES bytes)"

    companion object {
        /** The verifier length in bytes. */
        const val SIZE_BYTES = 32
    }
}

/**
 * The pair one derivation returns: the key-encryption key and the login verifier.
 *
 * The key lives only inside this object. [wipe] zeroes both parts. This is a plain
 * class, not a data class, so there is no generated `toString` or `copy` that could
 * print or duplicate the keys. Two pairs are equal only if they are the same object.
 */
class DerivedKeys(val kek: KeyEncryptionKey, val authVerifier: AuthVerifier) {
    /** Zero both the key-encryption key and the login verifier. */
    fun wipe() {
        kek.wipe()
        authVerifier.wipe()
    }

    /** Never prints the keys. */
    override fun toString(): String = "DerivedKeys(kek=$kek, authVerifier=$authVerifier)"
}

/** Why a derivation was refused. */
enum class KdfRefusal {
    /** The memory cost is under the lowest value accepted. */
    MEMORY_BELOW_FLOOR,

    /** The memory cost is over the highest value accepted. */
    MEMORY_ABOVE_CEILING,

    /** The number of passes is under the lowest value accepted. */
    ITERATIONS_BELOW_FLOOR,

    /** The number of passes is over the highest value accepted. */
    ITERATIONS_ABOVE_CEILING,

    /** The number of lanes is under the lowest value accepted. */
    PARALLELISM_BELOW_FLOOR,

    /** The number of lanes is over the highest value accepted. */
    PARALLELISM_ABOVE_CEILING,

    /** The tag length is not 32 bytes. */
    OUTPUT_LENGTH_NOT_32,

    /** The salt is not the length accepted. */
    BAD_SALT_LENGTH,

    /** The KDF version number is not one that is supported. */
    UNSUPPORTED_VERSION,
}

/**
 * Thrown when the parameters or the salt for a key derivation are not acceptable.
 *
 * It lives in core because callers, for example the sign-in code, see only core and
 * must be able to tell "the stored parameters were refused" from other failures. The
 * message never contains the password or the salt.
 */
class KdfRefused(val reason: KdfRefusal) :
    IllegalArgumentException("Key derivation refused: ${reason.name}")
