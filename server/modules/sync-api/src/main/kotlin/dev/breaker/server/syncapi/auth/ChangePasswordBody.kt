package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AuthVerifier
import dev.breaker.server.syncapi.accounts.KdfBounds
import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.KeySizes
import dev.breaker.server.syncapi.accounts.withinBounds
import java.util.Base64

// The text and decoded sizes of the byte members that KeySizes does not name: the 16-byte client salt
// and the standard padded base64 text lengths of 16, 32 and 60 bytes.
private const val SALT_BYTES = 16
private const val SALT_TEXT_LENGTH = 24
private const val VERIFIER_TEXT_LENGTH = 44
private const val PUBKEY_TEXT_LENGTH = 44
private const val WRAPPED_TEXT_LENGTH = 80

/**
 * The ten values of a change-password request, all checked. Byte values are copied in and copied out,
 * so a caller cannot change what the body holds. The printed text is fixed, so no key or verifier
 * byte can reach a message by string conversion.
 */
internal class ChangePasswordBody(
    oldVerifier: AuthVerifier,
    newSalt: ByteArray,
    val newKdf: KdfParams,
    val newKdfVersion: Int,
    newVerifier: AuthVerifier,
    newWrappedDek: ByteArray,
    newWrappedBoxPrivkey: ByteArray,
    newBoxPubkey: ByteArray,
    val newKeyVersion: Int,
    prevWrappedDek: ByteArray,
) {
    init {
        require(newSalt.size == SALT_BYTES) { "new_salt must be $SALT_BYTES bytes" }
        require(newWrappedDek.size == KeySizes.WRAPPED_DEK) { "new_wrapped_dek must be ${KeySizes.WRAPPED_DEK} bytes" }
        require(newWrappedBoxPrivkey.size == KeySizes.WRAPPED_BOX_PRIVKEY) {
            "new_wrapped_box_privkey must be ${KeySizes.WRAPPED_BOX_PRIVKEY} bytes"
        }
        require(newBoxPubkey.size == KeySizes.BOX_PUBKEY) { "new_box_pubkey must be ${KeySizes.BOX_PUBKEY} bytes" }
        require(prevWrappedDek.size == KeySizes.WRAPPED_DEK) { "prev_wrapped_dek must be ${KeySizes.WRAPPED_DEK} bytes" }
    }

    // An AuthVerifier already copies its bytes and cannot be changed, so it is held as it is.
    private val oldHeld: AuthVerifier = oldVerifier
    private val newHeld: AuthVerifier = newVerifier
    private val saltBytes: ByteArray = newSalt.copyOf()
    private val dekBytes: ByteArray = newWrappedDek.copyOf()
    private val privkeyBytes: ByteArray = newWrappedBoxPrivkey.copyOf()
    private val pubkeyBytes: ByteArray = newBoxPubkey.copyOf()
    private val prevDekBytes: ByteArray = prevWrappedDek.copyOf()

    val oldVerifier: AuthVerifier
        get() = oldHeld

    val newVerifier: AuthVerifier
        get() = newHeld

    val newSalt: ByteArray
        get() = saltBytes.copyOf()

    val newWrappedDek: ByteArray
        get() = dekBytes.copyOf()

    val newWrappedBoxPrivkey: ByteArray
        get() = privkeyBytes.copyOf()

    val newBoxPubkey: ByteArray
        get() = pubkeyBytes.copyOf()

    val prevWrappedDek: ByteArray
        get() = prevDekBytes.copyOf()

    override fun toString(): String = "ChangePasswordBody"
}

/**
 * Reads a change-password request body. Returns null for anything that is not exactly one JSON object
 * with the ten members, each once, of the right type, size and range. It never throws.
 *
 * The reader is strict on purpose. Whitespace is only space, tab, line feed and carriage return.
 * An integer is plain ASCII digits with no sign, no extra zero in front, no fraction and no exponent.
 * A member name that is not expected, or is given twice, refuses the body. Nothing may follow the object.
 */
internal fun parseChangePasswordBody(text: String): ChangePasswordBody? {
    return try {
        ChangeBodyReader(text).readBody()
    } catch (_: Exception) {
        null
    }
}

private class ChangeBodyReader(text: String) : StrictJsonCursor(text) {
    fun readBody(): ChangePasswordBody {
        skipSpace()
        expect('{')
        var oldVerifier: AuthVerifier? = null
        var newSalt: ByteArray? = null
        var newKdf: KdfParams? = null
        var newKdfVersion: Int? = null
        var newVerifier: AuthVerifier? = null
        var newWrappedDek: ByteArray? = null
        var newWrappedBoxPrivkey: ByteArray? = null
        var newBoxPubkey: ByteArray? = null
        var newKeyVersion: Int? = null
        var prevWrappedDek: ByteArray? = null
        while (true) {
            skipSpace()
            val name = readString()
            skipSpace()
            expect(':')
            skipSpace()
            when (name) {
                "old_auth_verifier" -> oldVerifier = once(oldVerifier, readVerifier())
                "new_salt" -> newSalt = once(newSalt, readBytes(SALT_TEXT_LENGTH, SALT_BYTES))
                "new_kdf_params" -> newKdf = once(newKdf, readKdfParams())
                "new_kdf_version" -> newKdfVersion = once(newKdfVersion, readInteger())
                "new_auth_verifier" -> newVerifier = once(newVerifier, readVerifier())
                "new_wrapped_dek" ->
                    newWrappedDek = once(newWrappedDek, readBytes(WRAPPED_TEXT_LENGTH, KeySizes.WRAPPED_DEK))
                "new_wrapped_box_privkey" ->
                    newWrappedBoxPrivkey =
                        once(newWrappedBoxPrivkey, readBytes(WRAPPED_TEXT_LENGTH, KeySizes.WRAPPED_BOX_PRIVKEY))
                "new_box_pubkey" ->
                    newBoxPubkey = once(newBoxPubkey, readBytes(PUBKEY_TEXT_LENGTH, KeySizes.BOX_PUBKEY))
                "new_key_version" -> newKeyVersion = once(newKeyVersion, readInteger())
                "prev_wrapped_dek" ->
                    prevWrappedDek = once(prevWrappedDek, readBytes(WRAPPED_TEXT_LENGTH, KeySizes.WRAPPED_DEK))
                else -> refuse()
            }
            skipSpace()
            val next = readChar()
            if (next == '}') break
            if (next != ',') refuse()
        }
        skipSpace()
        if (position != text.length) refuse()

        val kdf = newKdf ?: refuse()
        val kdfVersion = newKdfVersion ?: refuse()
        val keyVersion = newKeyVersion ?: refuse()
        if (kdfVersion != KdfBounds.REQUIRED_KDF_VERSION || !kdf.withinBounds() || keyVersion < 1) refuse()
        return ChangePasswordBody(
            oldVerifier = oldVerifier ?: refuse(),
            newSalt = newSalt ?: refuse(),
            newKdf = kdf,
            newKdfVersion = kdfVersion,
            newVerifier = newVerifier ?: refuse(),
            newWrappedDek = newWrappedDek ?: refuse(),
            newWrappedBoxPrivkey = newWrappedBoxPrivkey ?: refuse(),
            newBoxPubkey = newBoxPubkey ?: refuse(),
            newKeyVersion = keyVersion,
            prevWrappedDek = prevWrappedDek ?: refuse(),
        )
    }

    // The nested object: exactly memory_kib, iterations and parallelism, each once, each an integer.
    private fun readKdfParams(): KdfParams {
        expect('{')
        var memoryKib: Int? = null
        var iterations: Int? = null
        var parallelism: Int? = null
        while (true) {
            skipSpace()
            val name = readString()
            skipSpace()
            expect(':')
            skipSpace()
            when (name) {
                "memory_kib" -> memoryKib = once(memoryKib, readInteger())
                "iterations" -> iterations = once(iterations, readInteger())
                "parallelism" -> parallelism = once(parallelism, readInteger())
                else -> refuse()
            }
            skipSpace()
            val next = readChar()
            if (next == '}') break
            if (next != ',') refuse()
        }
        return KdfParams(memoryKib ?: refuse(), iterations ?: refuse(), parallelism ?: refuse())
    }

    private fun readVerifier(): AuthVerifier {
        val bytes = readBytes(VERIFIER_TEXT_LENGTH, AuthVerifier.LENGTH)
        return AuthVerifier.fromBytes(bytes) ?: refuse()
    }

    // Standard padded base64: the text must have exactly [textLength] characters and strict decoding
    // must give exactly [byteCount] bytes.
    private fun readBytes(textLength: Int, byteCount: Int): ByteArray {
        val value = readString()
        if (value.length != textLength) refuse()
        val bytes = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            refuse()
        }
        if (bytes.size != byteCount) refuse()
        return bytes
    }
}
