package dev.breaker.server.syncapi.accounts

/**
 * The 32 secret bytes a client proves its password with. Deliberately not a data
 * class and without equals or hashCode: no generated text or comparison may expose
 * or leak the bytes.
 */
internal class AuthVerifier private constructor(bytes: ByteArray) {

    // Copied, so the caller's array (often reused or wiped) cannot change this value.
    private val data: ByteArray = bytes.copyOf()

    fun copyBytes(): ByteArray = data.copyOf()

    override fun toString(): String = "AuthVerifier(redacted)"

    companion object {
        const val LENGTH = 32

        fun fromBytes(bytes: ByteArray): AuthVerifier? =
            if (bytes.size == LENGTH) AuthVerifier(bytes) else null
    }
}
